/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.thrift2;

import static org.apache.hadoop.hbase.thrift2.ThriftUtilities.*;
import static org.apache.thrift.TBaseHelper.byteBufferToByteArray;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.HTablePool;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.RowMutations;
import org.apache.hadoop.hbase.thrift.ThriftMetrics;
import org.apache.hadoop.hbase.thrift2.generated.*;
import org.apache.thrift.TException;

/**
 * This class is a glue object that connects Thrift RPC calls to the HBase client API primarily
 * defined in the HTableInterface.
 */
@InterfaceAudience.Private
public class ThriftHBaseServiceHandler implements THBaseService.Iface {

  // TODO: Size of pool configuraple
  private final HTablePool htablePool;
  private static final Log LOG = LogFactory.getLog(ThriftHBaseServiceHandler.class);

  // nextScannerId and scannerMap are used to manage scanner state
  // TODO: Cleanup thread for Scanners, Scanner id wrap
  private final AtomicInteger nextScannerId = new AtomicInteger(0);
  private final Map<Integer, ResultScanner> scannerMap =
      new ConcurrentHashMap<Integer, ResultScanner>();

  public static THBaseService.Iface newInstance(Configuration conf, ThriftMetrics metrics) {
    THBaseService.Iface handler = new ThriftHBaseServiceHandler(conf);
    return (THBaseService.Iface) Proxy.newProxyInstance(handler.getClass().getClassLoader(),
      new Class[] { THBaseService.Iface.class }, new THBaseServiceMetricsProxy(handler, metrics));
  }

  private static class THBaseServiceMetricsProxy implements InvocationHandler {
    private final THBaseService.Iface handler;
    private final ThriftMetrics metrics;

    private THBaseServiceMetricsProxy(THBaseService.Iface handler, ThriftMetrics metrics) {
      this.handler = handler;
      this.metrics = metrics;
    }

    @Override
    public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
      Object result;
      try {
        long start = now();
        result = m.invoke(handler, args);
        int processTime = (int) (now() - start);
        metrics.incMethodTime(m.getName(), processTime);
      } catch (InvocationTargetException e) {
        throw e.getTargetException();
      } catch (Exception e) {
        throw new RuntimeException("unexpected invocation exception: " + e.getMessage());
      }
      return result;
    }
  }

  private static long now() {
    return System.nanoTime();
  }

  ThriftHBaseServiceHandler(Configuration conf) {
    int maxPoolSize = conf.getInt("hbase.thrift.htablepool.size.max", 1000);
    htablePool = new HTablePool(conf, maxPoolSize);
  }

  private HTableInterface getTable(ByteBuffer tableName) {
    return htablePool.getTable(byteBufferToByteArray(tableName));
  }

  private void closeTable(HTableInterface table) throws TIOError {
    try {
      table.close();
    } catch (IOException e) {
      throw getTIOError(e);
    }
  }

  private TIOError getTIOError(IOException e) {
    TIOError err = new TIOError();
    err.setMessage(e.getMessage());
    return err;
  }

  /**
   * Assigns a unique ID to the scanner and adds the mapping to an internal HashMap.
   * @param scanner to add
   * @return Id for this Scanner
   */
  private int addScanner(ResultScanner scanner) {
    int id = nextScannerId.getAndIncrement();
    scannerMap.put(id, scanner);
    return id;
  }

  /**
   * Returns the Scanner associated with the specified Id.
   * @param id of the Scanner to get
   * @return a Scanner, or null if the Id is invalid
   */
  private ResultScanner getScanner(int id) {
    return scannerMap.get(id);
  }

  /**
   * Removes the scanner associated with the specified ID from the internal HashMap.
   * @param id of the Scanner to remove
   * @return the removed Scanner, or <code>null</code> if the Id is invalid
   */
  protected ResultScanner removeScanner(int id) {
    return scannerMap.remove(id);
  }

  @Override
  public boolean exists(ByteBuffer table, TGet get) throws TIOError, TException {
    HTableInterface htable = getTable(table);
    try {
      return htable.exists(getFromThrift(get));
    } catch (IOException e) {
      throw getTIOError(e);
    } finally {
      closeTable(htable);
    }
  }

  @Override
  public TResult get(ByteBuffer table, TGet get) throws TIOError, TException {
    HTableInterface htable = getTable(table);
    try {
      return resultFromHBase(htable.get(getFromThrift(get)));
    } catch (IOException e) {
      throw getTIOError(e);
    } finally {
      closeTable(htable);
    }
  }

  @Override
  public List<TResult> getMultiple(ByteBuffer table, List<TGet> gets) throws TIOError, TException {
    HTableInterface htable = getTable(table);
    try {
      return resultsFromHBase(htable.get(getsFromThrift(gets)));
    } catch (IOException e) {
      throw getTIOError(e);
    } finally {
      closeTable(htable);
    }
  }

  @Override
  public void put(ByteBuffer table, TPut put) throws TIOError, TException {
    HTableInterface htable = getTable(table);
    try {
      htable.put(putFromThrift(put));
    } catch (IOException e) {
      throw getTIOError(e);
    } finally {
      closeTable(htable);
    }
  }

  @Override
  public boolean checkAndPut(ByteBuffer table, ByteBuffer row, ByteBuffer family,
      ByteBuffer qualifier, ByteBuffer value, TPut put) throws TIOError, TException {
    HTableInterface htable = getTable(table);
    try {
      return htable.checkAndPut(byteBufferToByteArray(row), byteBufferToByteArray(family),
        byteBufferToByteArray(qualifier), (value == null) ? null : byteBufferToByteArray(value),
        putFromThrift(put));
    } catch (IOException e) {
      throw getTIOError(e);
    } finally {
      closeTable(htable);
    }
  }

  @Override
  public void putMultiple(ByteBuffer table, List<TPut> puts) throws TIOError, TException {
    HTableInterface htable = getTable(table);
    try {
      htable.put(putsFromThrift(puts));
    } catch (IOException e) {
      throw getTIOError(e);
    } finally {
      closeTable(htable);
    }
  }

  @Override
  public void deleteSingle(ByteBuffer table, TDelete deleteSingle) throws TIOError, TException {
    HTableInterface htable = getTable(table);
    try {
      htable.delete(deleteFromThrift(deleteSingle));
    } catch (IOException e) {
      throw getTIOError(e);
    } finally {
      closeTable(htable);
    }
  }

  @Override
  public List<TDelete> deleteMultiple(ByteBuffer table, List<TDelete> deletes) throws TIOError,
      TException {
    HTableInterface htable = getTable(table);
    try {
      htable.delete(deletesFromThrift(deletes));
    } catch (IOException e) {
      throw getTIOError(e);
    } finally {
      closeTable(htable);
    }
    return Collections.emptyList();
  }

  @Override
  public boolean checkAndDelete(ByteBuffer table, ByteBuffer row, ByteBuffer family,
      ByteBuffer qualifier, ByteBuffer value, TDelete deleteSingle) throws TIOError, TException {
    HTableInterface htable = getTable(table);

    try {
      if (value == null) {
        return htable.checkAndDelete(byteBufferToByteArray(row), byteBufferToByteArray(family),
          byteBufferToByteArray(qualifier), null, deleteFromThrift(deleteSingle));
      } else {
        return htable.checkAndDelete(byteBufferToByteArray(row), byteBufferToByteArray(family),
          byteBufferToByteArray(qualifier), byteBufferToByteArray(value),
          deleteFromThrift(deleteSingle));
      }
    } catch (IOException e) {
      throw getTIOError(e);
    } finally {
      closeTable(htable);
    }
  }

  @Override
  public TResult increment(ByteBuffer table, TIncrement increment) throws TIOError, TException {
    HTableInterface htable = getTable(table);
    try {
      return resultFromHBase(htable.increment(incrementFromThrift(increment)));
    } catch (IOException e) {
      throw getTIOError(e);
    } finally {
      closeTable(htable);
    }
  }

  @Override
  public TResult append(ByteBuffer table, TAppend append) throws TIOError, TException {
    HTableInterface htable = getTable(table);
    try {
      return resultFromHBase(htable.append(appendFromThrift(append)));
    } catch (IOException e) {
      throw getTIOError(e);
    } finally {
      closeTable(htable);
    }
  }

  @Override
  public int openScanner(ByteBuffer table, TScan scan) throws TIOError, TException {
    HTableInterface htable = getTable(table);
    ResultScanner resultScanner = null;
    try {
      resultScanner = htable.getScanner(scanFromThrift(scan));
    } catch (IOException e) {
      throw getTIOError(e);
    } finally {
      closeTable(htable);
    }
    return addScanner(resultScanner);
  }

  @Override
  public List<TResult> getScannerRows(int scannerId, int numRows) throws TIOError,
      TIllegalArgument, TException {
    ResultScanner scanner = getScanner(scannerId);
    if (scanner == null) {
      TIllegalArgument ex = new TIllegalArgument();
      ex.setMessage("Invalid scanner Id");
      throw ex;
    }

    try {
      return resultsFromHBase(scanner.next(numRows));
    } catch (IOException e) {
      throw getTIOError(e);
    }
  }

  @Override
  public List<TResult> getScannerResults(ByteBuffer table, TScan scan, int numRows)
      throws TIOError, TException {
    HTableInterface htable = getTable(table);
    List<TResult> results = null;
    ResultScanner scanner = null;
    try {
      scanner = htable.getScanner(scanFromThrift(scan));
      results = resultsFromHBase(scanner.next(numRows));
    } catch (IOException e) {
      throw getTIOError(e);
    } finally {
      if (scanner != null) {
        scanner.close();
      }
      closeTable(htable);
    }
    return results;
  }

  @Override
  public void closeScanner(int scannerId) throws TIOError, TIllegalArgument, TException {
    LOG.debug("scannerClose: id=" + scannerId);
    ResultScanner scanner = getScanner(scannerId);
    if (scanner == null) {
      String message = "scanner ID is invalid";
      LOG.warn(message);
      TIllegalArgument ex = new TIllegalArgument();
      ex.setMessage("Invalid scanner Id");
      throw ex;
    }
    scanner.close();
    removeScanner(scannerId);
  }

  @Override
  public void mutateRow(ByteBuffer table, TRowMutations rowMutations) throws TIOError, TException {
    HTableInterface htable = getTable(table);
    try {
      htable.mutateRow(rowMutationsFromThrift(rowMutations));
    } catch (IOException e) {
      throw getTIOError(e);
    } finally {
      closeTable(htable);
    }
  }

}
