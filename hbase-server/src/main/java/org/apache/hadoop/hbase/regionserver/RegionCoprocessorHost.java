/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;

import org.apache.commons.collections.map.AbstractReferenceMap;
import org.apache.commons.collections.map.ReferenceMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.Coprocessor;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.client.Append;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.CoprocessorHost;
import org.apache.hadoop.hbase.coprocessor.CoprocessorService;
import org.apache.hadoop.hbase.coprocessor.EndpointObserver;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.RegionObserver;
import org.apache.hadoop.hbase.coprocessor.RegionObserver.MutationType;
import org.apache.hadoop.hbase.filter.ByteArrayComparable;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.io.FSDataInputStreamWrapper;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.io.Reference;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.regionserver.HRegion.Operation;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionRequest;
import org.apache.hadoop.hbase.regionserver.wal.HLogKey;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Message;
import com.google.protobuf.Service;

/**
 * Implements the coprocessor environment and runtime support for coprocessors
 * loaded within a {@link HRegion}.
 */
public class RegionCoprocessorHost
    extends CoprocessorHost<RegionCoprocessorHost.RegionEnvironment> {

  private static final Log LOG = LogFactory.getLog(RegionCoprocessorHost.class);
  // The shared data map
  private static ReferenceMap sharedDataMap =
      new ReferenceMap(AbstractReferenceMap.HARD, AbstractReferenceMap.WEAK);

  /**
   * Encapsulation of the environment of each coprocessor
   */
  static class RegionEnvironment extends CoprocessorHost.Environment
      implements RegionCoprocessorEnvironment {

    private HRegion region;
    private RegionServerServices rsServices;
    ConcurrentMap<String, Object> sharedData;

    /**
     * Constructor
     * @param impl the coprocessor instance
     * @param priority chaining priority
     */
    public RegionEnvironment(final Coprocessor impl, final int priority,
        final int seq, final Configuration conf, final HRegion region,
        final RegionServerServices services, final ConcurrentMap<String, Object> sharedData) {
      super(impl, priority, seq, conf);
      this.region = region;
      this.rsServices = services;
      this.sharedData = sharedData;
    }

    /** @return the region */
    @Override
    public HRegion getRegion() {
      return region;
    }

    /** @return reference to the region server services */
    @Override
    public RegionServerServices getRegionServerServices() {
      return rsServices;
    }

    public void shutdown() {
      super.shutdown();
    }

    @Override
    public ConcurrentMap<String, Object> getSharedData() {
      return sharedData;
    }
  }

  /** The region server services */
  RegionServerServices rsServices;
  /** The region */
  HRegion region;

  /**
   * Constructor
   * @param region the region
   * @param rsServices interface to available region server functionality
   * @param conf the configuration
   */
  public RegionCoprocessorHost(final HRegion region,
      final RegionServerServices rsServices, final Configuration conf) {
    super(rsServices);
    this.conf = conf;
    this.rsServices = rsServices;
    this.region = region;
    this.pathPrefix = Integer.toString(this.region.getRegionInfo().hashCode());

    // load system default cp's from configuration.
    loadSystemCoprocessors(conf, REGION_COPROCESSOR_CONF_KEY);

    // load system default cp's for user tables from configuration.
    if (!region.getRegionInfo().getTable().isSystemTable()) {
      loadSystemCoprocessors(conf, USER_REGION_COPROCESSOR_CONF_KEY);
    }

    // load Coprocessor From HDFS
    loadTableCoprocessors(conf);
  }

  void loadTableCoprocessors(final Configuration conf) {
    // scan the table attributes for coprocessor load specifications
    // initialize the coprocessors
    List<RegionEnvironment> configured = new ArrayList<RegionEnvironment>();
    for (Map.Entry<ImmutableBytesWritable,ImmutableBytesWritable> e:
        region.getTableDesc().getValues().entrySet()) {
      String key = Bytes.toString(e.getKey().get()).trim();
      String spec = Bytes.toString(e.getValue().get()).trim();
      if (HConstants.CP_HTD_ATTR_KEY_PATTERN.matcher(key).matches()) {
        // found one
        try {
          Matcher matcher = HConstants.CP_HTD_ATTR_VALUE_PATTERN.matcher(spec);
          if (matcher.matches()) {
            // jar file path can be empty if the cp class can be loaded
            // from class loader.
            Path path = matcher.group(1).trim().isEmpty() ?
                null : new Path(matcher.group(1).trim());
            String className = matcher.group(2).trim();
            int priority = matcher.group(3).trim().isEmpty() ?
                Coprocessor.PRIORITY_USER : Integer.valueOf(matcher.group(3));
            String cfgSpec = null;
            try {
              cfgSpec = matcher.group(4);
            } catch (IndexOutOfBoundsException ex) {
              // ignore
            }
            Configuration ourConf;
            if (cfgSpec != null) {
              cfgSpec = cfgSpec.substring(cfgSpec.indexOf('|') + 1);
              // do an explicit deep copy of the passed configuration
              ourConf = new Configuration(false);
              HBaseConfiguration.merge(ourConf, conf);
              Matcher m = HConstants.CP_HTD_ATTR_VALUE_PARAM_PATTERN.matcher(cfgSpec);
              while (m.find()) {
                ourConf.set(m.group(1), m.group(2));
              }
            } else {
              ourConf = conf;
            }
            // Load encompasses classloading and coprocessor initialization
            try {
              RegionEnvironment env = load(path, className, priority, ourConf);
              configured.add(env);
              LOG.info("Loaded coprocessor " + className + " from HTD of " +
                region.getTableDesc().getTableName().getNameAsString() + " successfully.");
            } catch (Throwable t) {
              // Coprocessor failed to load, do we abort on error?
              if (conf.getBoolean(ABORT_ON_ERROR_KEY, DEFAULT_ABORT_ON_ERROR)) {
                abortServer(className, t);
              } else {
                LOG.error("Failed to load coprocessor " + className, t);
              }
            }
          } else {
            LOG.error("Malformed table coprocessor specification: key=" + key +
              ", spec: " + spec);
          }
        } catch (Exception ioe) {
          LOG.error("Malformed table coprocessor specification: key=" + key +
            ", spec: " + spec);
        }
      }
    }
    // add together to coprocessor set for COW efficiency
    coprocessors.addAll(configured);
  }

  @Override
  public RegionEnvironment createEnvironment(Class<?> implClass,
      Coprocessor instance, int priority, int seq, Configuration conf) {
    // Check if it's an Endpoint.
    // Due to current dynamic protocol design, Endpoint
    // uses a different way to be registered and executed.
    // It uses a visitor pattern to invoke registered Endpoint
    // method.
    for (Class<?> c : implClass.getInterfaces()) {
      if (CoprocessorService.class.isAssignableFrom(c)) {
        region.registerService( ((CoprocessorService)instance).getService() );
      }
    }
    ConcurrentMap<String, Object> classData;
    // make sure only one thread can add maps
    synchronized (sharedDataMap) {
      // as long as at least one RegionEnvironment holds on to its classData it will
      // remain in this map
      classData = (ConcurrentMap<String, Object>)sharedDataMap.get(implClass.getName());
      if (classData == null) {
        classData = new ConcurrentHashMap<String, Object>();
        sharedDataMap.put(implClass.getName(), classData);
      }
    }
    return new RegionEnvironment(instance, priority, seq, conf, region,
        rsServices, classData);
  }

  /**
   * HBASE-4014 : This is used by coprocessor hooks which are not declared to throw exceptions.
   *
   * For example, {@link
   * org.apache.hadoop.hbase.regionserver.RegionCoprocessorHost#preOpen()} and
   * {@link org.apache.hadoop.hbase.regionserver.RegionCoprocessorHost#postOpen()} are such hooks.
   *
   * See also
   * {@link org.apache.hadoop.hbase.master.MasterCoprocessorHost#handleCoprocessorThrowable(
   *    CoprocessorEnvironment, Throwable)}
   * @param env The coprocessor that threw the exception.
   * @param e The exception that was thrown.
   */
  private void handleCoprocessorThrowableNoRethrow(
      final CoprocessorEnvironment env, final Throwable e) {
    try {
      handleCoprocessorThrowable(env,e);
    } catch (IOException ioe) {
      // We cannot throw exceptions from the caller hook, so ignore.
      LOG.warn(
        "handleCoprocessorThrowable() threw an IOException while attempting to handle Throwable " +
        e + ". Ignoring.",e);
    }
  }

  /**
   * Invoked before a region open.
   *
   * @throws IOException Signals that an I/O exception has occurred.
   */
  public void preOpen() throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver) env.getInstance()).preOpen(ctx);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * Invoked after a region open
   */
  public void postOpen() {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver) env.getInstance()).postOpen(ctx);
        } catch (Throwable e) {
          handleCoprocessorThrowableNoRethrow(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * Invoked after log replay on region
   */
  public void postLogReplay() {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver) env.getInstance()).postLogReplay(ctx);
        } catch (Throwable e) {
          handleCoprocessorThrowableNoRethrow(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * Invoked before a region is closed
   * @param abortRequested true if the server is aborting
   */
  public void preClose(final boolean abortRequested) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver) env.getInstance()).preClose(ctx, abortRequested);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
      }
    }
  }

  /**
   * Invoked after a region is closed
   * @param abortRequested true if the server is aborting
   */
  public void postClose(final boolean abortRequested) {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver) env.getInstance()).postClose(ctx, abortRequested);
        } catch (Throwable e) {
          handleCoprocessorThrowableNoRethrow(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
      }
      shutdown(env);
    }
  }

  /**
   * See
   * {@link RegionObserver#preCompactScannerOpen(ObserverContext, Store, List, ScanType, long, InternalScanner, CompactionRequest)}
   */
  public InternalScanner preCompactScannerOpen(final Store store,
      final List<StoreFileScanner> scanners, final ScanType scanType, final long earliestPutTs,
      final CompactionRequest request) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    InternalScanner s = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          s = ((RegionObserver) env.getInstance()).preCompactScannerOpen(ctx, store,
            scanners, scanType, earliestPutTs, s, request);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env,e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return s;
  }

  /**
   * Called prior to selecting the {@link StoreFile}s for compaction from the list of currently
   * available candidates.
   * @param store The store where compaction is being requested
   * @param candidates The currently available store files
   * @param request custom compaction request
   * @return If {@code true}, skip the normal selection process and use the current list
   * @throws IOException
   */
  public boolean preCompactSelection(final Store store, final List<StoreFile> candidates,
      final CompactionRequest request) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    boolean bypass = false;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver) env.getInstance()).preCompactSelection(ctx, store, candidates,
            request);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env,e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        bypass |= ctx.shouldBypass();
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return bypass;
  }

  /**
   * Called after the {@link StoreFile}s to be compacted have been selected from the available
   * candidates.
   * @param store The store where compaction is being requested
   * @param selected The store files selected to compact
   * @param request custom compaction
   */
  public void postCompactSelection(final Store store, final ImmutableList<StoreFile> selected,
      final CompactionRequest request) {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver) env.getInstance()).postCompactSelection(ctx, store, selected,
            request);
        } catch (Throwable e) {
          handleCoprocessorThrowableNoRethrow(env,e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * Called prior to rewriting the store files selected for compaction
   * @param store the store being compacted
   * @param scanner the scanner used to read store data during compaction
   * @param scanType type of Scan
   * @param request the compaction that will be executed
   * @throws IOException
   */
  public InternalScanner preCompact(final Store store, final InternalScanner scanner,
      final ScanType scanType, final CompactionRequest request) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    boolean bypass = false;
    InternalScanner s = scanner;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          s = ((RegionObserver) env.getInstance()).preCompact(ctx, store, s, scanType,
            request);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env,e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        bypass |= ctx.shouldBypass();
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return bypass ? null : s;
  }

  /**
   * Called after the store compaction has completed.
   * @param store the store being compacted
   * @param resultFile the new store file written during compaction
   * @param request the compaction that is being executed
   * @throws IOException
   */
  public void postCompact(final Store store, final StoreFile resultFile,
      final CompactionRequest request) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver) env.getInstance()).postCompact(ctx, store, resultFile, request);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * Invoked before a memstore flush
   * @throws IOException
   */
  public InternalScanner preFlush(final Store store, final InternalScanner scanner) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    boolean bypass = false;
    InternalScanner s = scanner;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          s = ((RegionObserver)env.getInstance()).preFlush(ctx, store, s);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env,e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        bypass |= ctx.shouldBypass();
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return bypass ? null : s;
  }

  /**
   * Invoked before a memstore flush
   * @throws IOException
   */
  public void preFlush() throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).preFlush(ctx);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * See
   * {@link RegionObserver#preFlushScannerOpen(ObserverContext,
   *    Store, KeyValueScanner, InternalScanner)}
   */
  public InternalScanner preFlushScannerOpen(final Store store,
      final KeyValueScanner memstoreScanner) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    InternalScanner s = null;
    for (RegionEnvironment env : coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          s = ((RegionObserver) env.getInstance()).preFlushScannerOpen(ctx, store,
            memstoreScanner, s);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return s;
  }

  /**
   * Invoked after a memstore flush
   * @throws IOException
   */
  public void postFlush() throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).postFlush(ctx);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * Invoked after a memstore flush
   * @throws IOException
   */
  public void postFlush(final Store store, final StoreFile storeFile) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).postFlush(ctx, store, storeFile);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * Invoked just before a split
   * @throws IOException
   */
  public void preSplit() throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).preSplit(ctx);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * Invoked just before a split
   * @throws IOException
   */
  public void preSplit(final byte[] splitRow) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).preSplit(ctx, splitRow);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * Invoked just after a split
   * @param l the new left-hand daughter region
   * @param r the new right-hand daughter region
   * @throws IOException
   */
  public void postSplit(final HRegion l, final HRegion r) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).postSplit(ctx, l, r);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  public boolean preSplitBeforePONR(final byte[] splitKey, 
      final List<Mutation> metaEntries) throws IOException {
    boolean bypass = false;
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env : coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver) env.getInstance()).preSplitBeforePONR(ctx, splitKey, metaEntries);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        bypass |= ctx.shouldBypass();
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return bypass;
  }

  public void preSplitAfterPONR() throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env : coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver) env.getInstance()).preSplitAfterPONR(ctx);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * Invoked just before the rollback of a failed split is started
   * @throws IOException
   */
  public void preRollBackSplit() throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env : coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver) env.getInstance()).preRollBackSplit(ctx);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * Invoked just after the rollback of a failed split is done
   * @throws IOException
   */
  public void postRollBackSplit() throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env : coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver) env.getInstance()).postRollBackSplit(ctx);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * Invoked after a split is completed irrespective of a failure or success.
   * @throws IOException
   */
  public void postCompleteSplit() throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env : coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver) env.getInstance()).postCompleteSplit(ctx);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  // RegionObserver support

  /**
   * @param row the row key
   * @param family the family
   * @param result the result set from the region
   * @return true if default processing should be bypassed
   * @exception IOException Exception
   */
  public boolean preGetClosestRowBefore(final byte[] row, final byte[] family,
      final Result result) throws IOException {
    boolean bypass = false;
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).preGetClosestRowBefore(ctx, row, family, result);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        bypass |= ctx.shouldBypass();
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return bypass;
  }

  /**
   * @param row the row key
   * @param family the family
   * @param result the result set from the region
   * @exception IOException Exception
   */
  public void postGetClosestRowBefore(final byte[] row, final byte[] family,
      final Result result) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).postGetClosestRowBefore(ctx, row, family, result);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * @param get the Get request
   * @return true if default processing should be bypassed
   * @exception IOException Exception
   */
  public boolean preGet(final Get get, final List<Cell> results)
      throws IOException {
    boolean bypass = false;
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).preGetOp(ctx, get, results);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        bypass |= ctx.shouldBypass();
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return bypass;
  }

  /**
   * @param get the Get request
   * @param results the result sett
   * @exception IOException Exception
   */
  public void postGet(final Get get, final List<Cell> results)
  throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).postGetOp(ctx, get, results);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * @param get the Get request
   * @return true or false to return to client if bypassing normal operation,
   * or null otherwise
   * @exception IOException Exception
   */
  public Boolean preExists(final Get get) throws IOException {
    boolean bypass = false;
    boolean exists = false;
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          exists = ((RegionObserver)env.getInstance()).preExists(ctx, get, exists);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        bypass |= ctx.shouldBypass();
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return bypass ? exists : null;
  }

  /**
   * @param get the Get request
   * @param exists the result returned by the region server
   * @return the result to return to the client
   * @exception IOException Exception
   */
  public boolean postExists(final Get get, boolean exists)
      throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          exists = ((RegionObserver)env.getInstance()).postExists(ctx, get, exists);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return exists;
  }

  /**
   * @param put The Put object
   * @param edit The WALEdit object.
   * @param durability The durability used
   * @return true if default processing should be bypassed
   * @exception IOException Exception
   */
  public boolean prePut(final Put put, final WALEdit edit, final Durability durability)
      throws IOException {
    boolean bypass = false;
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).prePut(ctx, put, edit, durability);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        bypass |= ctx.shouldBypass();
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return bypass;
  }

  /**
   * @param put The Put object
   * @param edit The WALEdit object.
   * @param durability The durability used
   * @exception IOException Exception
   */
  public void postPut(final Put put, final WALEdit edit, final Durability durability)
      throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).postPut(ctx, put, edit, durability);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * @param delete The Delete object
   * @param edit The WALEdit object.
   * @param durability The durability used
   * @return true if default processing should be bypassed
   * @exception IOException Exception
   */
  public boolean preDelete(final Delete delete, final WALEdit edit, final Durability durability)
      throws IOException {
    boolean bypass = false;
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).preDelete(ctx, delete, edit, durability);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        bypass |= ctx.shouldBypass();
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return bypass;
  }

  /**
   * @param delete The Delete object
   * @param edit The WALEdit object.
   * @param durability The durability used
   * @exception IOException Exception
   */
  public void postDelete(final Delete delete, final WALEdit edit, final Durability durability)
      throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).postDelete(ctx, delete, edit, durability);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }
  
  /**
   * @param miniBatchOp
   * @return true if default processing should be bypassed
   * @throws IOException
   */
  public boolean preBatchMutate(
      final MiniBatchOperationInProgress<Mutation> miniBatchOp) throws IOException {
    boolean bypass = false;
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env : coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver) env.getInstance()).preBatchMutate(ctx, miniBatchOp);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        bypass |= ctx.shouldBypass();
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return bypass;
  }

  /**
   * @param miniBatchOp
   * @throws IOException
   */
  public void postBatchMutate(
      final MiniBatchOperationInProgress<Mutation> miniBatchOp) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env : coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver) env.getInstance()).postBatchMutate(ctx, miniBatchOp);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  public void postBatchMutateIndispensably(
      final MiniBatchOperationInProgress<Mutation> miniBatchOp, final boolean success)
      throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env : coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver) env.getInstance()).postBatchMutateIndispensably(ctx, miniBatchOp,
            success);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * @param row row to check
   * @param family column family
   * @param qualifier column qualifier
   * @param compareOp the comparison operation
   * @param comparator the comparator
   * @param put data to put if check succeeds
   * @return true or false to return to client if default processing should
   * be bypassed, or null otherwise
   * @throws IOException e
   */
  public Boolean preCheckAndPut(final byte [] row, final byte [] family,
      final byte [] qualifier, final CompareOp compareOp,
      final ByteArrayComparable comparator, final Put put)
    throws IOException {
    boolean bypass = false;
    boolean result = false;
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          result = ((RegionObserver)env.getInstance()).preCheckAndPut(ctx, row, family, qualifier,
            compareOp, comparator, put, result);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        bypass |= ctx.shouldBypass();
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return bypass ? result : null;
  }

  /**
   * @param row row to check
   * @param family column family
   * @param qualifier column qualifier
   * @param compareOp the comparison operation
   * @param comparator the comparator
   * @param put data to put if check succeeds
   * @throws IOException e
   */
  public boolean postCheckAndPut(final byte [] row, final byte [] family,
      final byte [] qualifier, final CompareOp compareOp,
      final ByteArrayComparable comparator, final Put put,
      boolean result)
    throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          result = ((RegionObserver)env.getInstance()).postCheckAndPut(ctx, row, family,
            qualifier, compareOp, comparator, put, result);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return result;
  }

  /**
   * @param row row to check
   * @param family column family
   * @param qualifier column qualifier
   * @param compareOp the comparison operation
   * @param comparator the comparator
   * @param delete delete to commit if check succeeds
   * @return true or false to return to client if default processing should
   * be bypassed, or null otherwise
   * @throws IOException e
   */
  public Boolean preCheckAndDelete(final byte [] row, final byte [] family,
      final byte [] qualifier, final CompareOp compareOp,
      final ByteArrayComparable comparator, final Delete delete)
      throws IOException {
    boolean bypass = false;
    boolean result = false;
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          result = ((RegionObserver)env.getInstance()).preCheckAndDelete(ctx, row, family,
            qualifier, compareOp, comparator, delete, result);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        bypass |= ctx.shouldBypass();
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return bypass ? result : null;
  }

  /**
   * @param row row to check
   * @param family column family
   * @param qualifier column qualifier
   * @param compareOp the comparison operation
   * @param comparator the comparator
   * @param delete delete to commit if check succeeds
   * @throws IOException e
   */
  public boolean postCheckAndDelete(final byte [] row, final byte [] family,
      final byte [] qualifier, final CompareOp compareOp,
      final ByteArrayComparable comparator, final Delete delete,
      boolean result) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          result = ((RegionObserver)env.getInstance()).postCheckAndDelete(ctx, row, family,
            qualifier, compareOp, comparator, delete, result);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return result;
  }

  /**
   * @param append append object
   * @return result to return to client if default operation should be
   * bypassed, null otherwise
   * @throws IOException if an error occurred on the coprocessor
   */
  public Result preAppend(final Append append) throws IOException {
    boolean bypass = false;
    Result result = null;
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          result = ((RegionObserver)env.getInstance()).preAppend(ctx, append);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        bypass |= ctx.shouldBypass();
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return bypass ? result : null;
  }

  /**
   * @param increment increment object
   * @return result to return to client if default operation should be
   * bypassed, null otherwise
   * @throws IOException if an error occurred on the coprocessor
   */
  public Result preIncrement(final Increment increment) throws IOException {
    boolean bypass = false;
    Result result = null;
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          result = ((RegionObserver)env.getInstance()).preIncrement(ctx, increment);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        bypass |= ctx.shouldBypass();
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return bypass ? result : null;
  }

  /**
   * @param append Append object
   * @param result the result returned by the append
   * @throws IOException if an error occurred on the coprocessor
   */
  public void postAppend(final Append append, final Result result) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).postAppend(ctx, append, result);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * @param increment increment object
   * @param result the result returned by postIncrement
   * @throws IOException if an error occurred on the coprocessor
   */
  public Result postIncrement(final Increment increment, Result result) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          result = ((RegionObserver)env.getInstance()).postIncrement(ctx, increment, result);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return result;
  }

  /**
   * @param scan the Scan specification
   * @return scanner id to return to client if default operation should be
   * bypassed, false otherwise
   * @exception IOException Exception
   */
  public RegionScanner preScannerOpen(final Scan scan) throws IOException {
    boolean bypass = false;
    RegionScanner s = null;
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          s = ((RegionObserver)env.getInstance()).preScannerOpen(ctx, scan, s);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        bypass |= ctx.shouldBypass();
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return bypass ? s : null;
  }

  /**
   * See
   * {@link RegionObserver#preStoreScannerOpen(ObserverContext,
   *    Store, Scan, NavigableSet, KeyValueScanner)}
   */
  public KeyValueScanner preStoreScannerOpen(final Store store, final Scan scan,
      final NavigableSet<byte[]> targetCols) throws IOException {
    KeyValueScanner s = null;
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          s = ((RegionObserver) env.getInstance()).preStoreScannerOpen(ctx, store, scan,
            targetCols, s);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return s;
  }

  /**
   * @param scan the Scan specification
   * @param s the scanner
   * @return the scanner instance to use
   * @exception IOException Exception
   */
  public RegionScanner postScannerOpen(final Scan scan, RegionScanner s) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          s = ((RegionObserver)env.getInstance()).postScannerOpen(ctx, scan, s);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return s;
  }

  /**
   * @param s the scanner
   * @param results the result set returned by the region server
   * @param limit the maximum number of results to return
   * @return 'has next' indication to client if bypassing default behavior, or
   * null otherwise
   * @exception IOException Exception
   */
  public Boolean preScannerNext(final InternalScanner s,
      final List<Result> results, final int limit) throws IOException {
    boolean bypass = false;
    boolean hasNext = false;
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          hasNext = ((RegionObserver)env.getInstance()).preScannerNext(ctx, s, results, limit,
            hasNext);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        bypass |= ctx.shouldBypass();
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return bypass ? hasNext : null;
  }

  /**
   * @param s the scanner
   * @param results the result set returned by the region server
   * @param limit the maximum number of results to return
   * @param hasMore
   * @return 'has more' indication to give to client
   * @exception IOException Exception
   */
  public boolean postScannerNext(final InternalScanner s,
      final List<Result> results, final int limit, boolean hasMore)
      throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          hasMore = ((RegionObserver)env.getInstance()).postScannerNext(ctx, s, results, limit,
            hasMore);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return hasMore;
  }

  /**
   * This will be called by the scan flow when the current scanned row is being filtered out by the
   * filter.
   * @param s the scanner
   * @param currentRow The current rowkey which got filtered out
   * @param offset offset to rowkey
   * @param length length of rowkey
   * @return whether more rows are available for the scanner or not
   * @throws IOException
   */
  public boolean postScannerFilterRow(final InternalScanner s, final byte[] currentRow,
      final int offset, final short length) throws IOException {
    boolean hasMore = true; // By default assume more rows there.
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env : coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          hasMore = ((RegionObserver) env.getInstance()).postScannerFilterRow(ctx, s, currentRow,
            offset, length, hasMore);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return hasMore;
  }
  
  /**
   * @param s the scanner
   * @return true if default behavior should be bypassed, false otherwise
   * @exception IOException Exception
   */
  public boolean preScannerClose(final InternalScanner s) throws IOException {
    boolean bypass = false;
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).preScannerClose(ctx, s);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        bypass |= ctx.shouldBypass();
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return bypass;
  }

  /**
   * @param s the scanner
   * @exception IOException Exception
   */
  public void postScannerClose(final InternalScanner s) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).postScannerClose(ctx, s);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * @param info
   * @param logKey
   * @param logEdit
   * @return true if default behavior should be bypassed, false otherwise
   * @throws IOException
   */
  public boolean preWALRestore(final HRegionInfo info, final HLogKey logKey,
      final WALEdit logEdit) throws IOException {
    boolean bypass = false;
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).preWALRestore(ctx, info, logKey, logEdit);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        bypass |= ctx.shouldBypass();
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return bypass;
  }

  /**
   * @param info
   * @param logKey
   * @param logEdit
   * @throws IOException
   */
  public void postWALRestore(final HRegionInfo info, final HLogKey logKey, final WALEdit logEdit)
      throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).postWALRestore(ctx, info, logKey, logEdit);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * @param familyPaths pairs of { CF, file path } submitted for bulk load
   * @return true if the default operation should be bypassed
   * @throws IOException
   */
  public boolean preBulkLoadHFile(final List<Pair<byte[], String>> familyPaths) throws IOException {
    boolean bypass = false;
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver)env.getInstance()).preBulkLoadHFile(ctx, familyPaths);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        bypass |= ctx.shouldBypass();
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return bypass;
  }

  /**
   * @param familyPaths pairs of { CF, file path } submitted for bulk load
   * @param hasLoaded whether load was successful or not
   * @return the possibly modified value of hasLoaded
   * @throws IOException
   */
  public boolean postBulkLoadHFile(final List<Pair<byte[], String>> familyPaths,
      boolean hasLoaded) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env: coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          hasLoaded = ((RegionObserver)env.getInstance()).postBulkLoadHFile(ctx, familyPaths,
            hasLoaded);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return hasLoaded;
  }

  public void postStartRegionOperation(final Operation op) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env : coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver) env.getInstance()).postStartRegionOperation(ctx, op);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  public void postCloseRegionOperation(final Operation op) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env : coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((RegionObserver) env.getInstance()).postCloseRegionOperation(ctx, op);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  /**
   * @param fs fileystem to read from
   * @param p path to the file
   * @param in {@link FSDataInputStreamWrapper}
   * @param size Full size of the file
   * @param cacheConf
   * @param r original reference file. This will be not null only when reading a split file.
   * @return a Reader instance to use instead of the base reader if overriding
   * default behavior, null otherwise
   * @throws IOException
   */
  public StoreFile.Reader preStoreFileReaderOpen(final FileSystem fs, final Path p,
      final FSDataInputStreamWrapper in, final long size, final CacheConfig cacheConf,
      final Reference r) throws IOException {
    StoreFile.Reader reader = null;
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env : coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          reader = ((RegionObserver) env.getInstance()).preStoreFileReaderOpen(ctx, fs, p, in,
            size, cacheConf, r, reader);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return reader;
  }

  /**
   * @param fs fileystem to read from
   * @param p path to the file
   * @param in {@link FSDataInputStreamWrapper}
   * @param size Full size of the file
   * @param cacheConf
   * @param r original reference file. This will be not null only when reading a split file.
   * @param reader the base reader instance
   * @return The reader to use
   * @throws IOException
   */
  public StoreFile.Reader postStoreFileReaderOpen(final FileSystem fs, final Path p,
      final FSDataInputStreamWrapper in, final long size, final CacheConfig cacheConf,
      final Reference r, StoreFile.Reader reader) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env : coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          reader = ((RegionObserver) env.getInstance()).postStoreFileReaderOpen(ctx, fs, p, in,
            size, cacheConf, r, reader);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return reader;
  }

  public Cell postMutationBeforeWAL(final MutationType opType, final Mutation mutation,
      final Cell oldCell, Cell newCell) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env : coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          newCell = ((RegionObserver) env.getInstance()).postMutationBeforeWAL(ctx, opType,
            mutation, oldCell, newCell);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return newCell;
  }

  public Message preEndpointInvocation(final Service service, final String methodName,
      Message request) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env : coprocessors) {
      if (env.getInstance() instanceof EndpointObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          request = ((EndpointObserver) env.getInstance()).preEndpointInvocation(ctx, service,
            methodName, request);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return request;
  }

  public void postEndpointInvocation(final Service service, final String methodName,
      final Message request, final Message.Builder responseBuilder) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env : coprocessors) {
      if (env.getInstance() instanceof EndpointObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          ((EndpointObserver) env.getInstance()).postEndpointInvocation(ctx, service,
            methodName, request, responseBuilder);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
  }

  public DeleteTracker postInstantiateDeleteTracker(DeleteTracker tracker) throws IOException {
    ObserverContext<RegionCoprocessorEnvironment> ctx = null;
    for (RegionEnvironment env : coprocessors) {
      if (env.getInstance() instanceof RegionObserver) {
        ctx = ObserverContext.createAndPrepare(env, ctx);
        Thread currentThread = Thread.currentThread();
        ClassLoader cl = currentThread.getContextClassLoader();
        try {
          currentThread.setContextClassLoader(env.getClassLoader());
          tracker = ((RegionObserver) env.getInstance()).postInstantiateDeleteTracker(ctx, 
              tracker);
        } catch (Throwable e) {
          handleCoprocessorThrowable(env, e);
        } finally {
          currentThread.setContextClassLoader(cl);
        }
        if (ctx.shouldComplete()) {
          break;
        }
      }
    }
    return tracker;
  }
}
