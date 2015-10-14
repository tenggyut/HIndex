/**
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
package org.apache.hadoop.hbase.regionserver;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.test.MetricsAssertHelper;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import static org.junit.Assert.*;

import java.io.IOException;


@Category(MediumTests.class)
public class TestRegionServerMetrics {
  private static final Log LOG = LogFactory.getLog(TestRegionServerMetrics.class);
  private static MetricsAssertHelper metricsHelper;

  static {
    Logger.getLogger("org.apache.hadoop.hbase").setLevel(Level.DEBUG);
  }

  private static MiniHBaseCluster cluster;
  private static HRegionServer rs;
  private static Configuration conf;
  private static HBaseTestingUtility TEST_UTIL;
  private static MetricsRegionServer metricsRegionServer;
  private static MetricsRegionServerSource serverSource;

  @BeforeClass
  public static void startCluster() throws Exception {
    metricsHelper = CompatibilityFactory.getInstance(MetricsAssertHelper.class);
    TEST_UTIL = new HBaseTestingUtility();
    conf = TEST_UTIL.getConfiguration();
    conf.getLong("hbase.splitlog.max.resubmit", 0);
    // Make the failure test faster
    conf.setInt("zookeeper.recovery.retry", 0);
    conf.setInt(HConstants.REGIONSERVER_INFO_PORT, -1);

    TEST_UTIL.startMiniCluster(1, 1);
    cluster = TEST_UTIL.getHBaseCluster();

    cluster.waitForActiveAndReadyMaster();

    while (cluster.getLiveRegionServerThreads().size() < 1) {
      Threads.sleep(100);
    }

    rs = cluster.getRegionServer(0);
    metricsRegionServer = rs.getMetrics();
    serverSource = metricsRegionServer.getMetricsSource();
  }

  @AfterClass
  public static void after() throws Exception {
    if (TEST_UTIL != null) {
      TEST_UTIL.shutdownMiniCluster();
    }
  }

  @Test(timeout = 300000)
  public void testRegionCount() throws Exception {
    String regionMetricsKey = "regionCount";
    long regions = metricsHelper.getGaugeLong(regionMetricsKey, serverSource);
    // Creating a table should add one region
    TEST_UTIL.createTable(Bytes.toBytes("table"), Bytes.toBytes("cf"));
    metricsHelper.assertGaugeGt(regionMetricsKey, regions, serverSource);
  }

  @Test
  public void testLocalFiles() throws Exception {
    metricsHelper.assertGauge("percentFilesLocal", 0, serverSource);
  }

  @Test
  public void testRequestCount() throws Exception {
    String tableNameString = "testRequestCount";
    byte[] tName = Bytes.toBytes(tableNameString);
    byte[] cfName = Bytes.toBytes("d");
    byte[] row = Bytes.toBytes("rk");
    byte[] qualifier = Bytes.toBytes("qual");
    byte[] initValue = Bytes.toBytes("Value");
    byte[] nextValue = Bytes.toBytes("NEXT VAL");


    TEST_UTIL.createTable(tName, cfName);

    new HTable(conf, tName).close(); //wait for the table to come up.
    metricsRegionServer.getRegionServerWrapper().forceRecompute();
    long requests = metricsHelper.getCounter("totalRequestCount", serverSource);
    long readRequests = metricsHelper.getCounter("readRequestCount", serverSource);
    long writeRequests = metricsHelper.getCounter("writeRequestCount", serverSource);

    HTable table = new HTable(conf, tName);

    Put p = new Put(row);


    p.add(cfName, qualifier, initValue);

    for (int i=0; i< 30; i++) {
      table.put(p);
    }


    table.flushCommits();

    Get g = new Get(row);
    for (int i=0; i< 10; i++) {
      table.get(g);
    }


    for ( HRegionInfo i:table.getRegionLocations().keySet()) {
      MetricsRegionAggregateSource agg = rs.getRegion(i.getRegionName())
          .getMetrics()
          .getSource()
          .getAggregateSource();
      String prefix = "namespace_"+NamespaceDescriptor.DEFAULT_NAMESPACE_NAME_STR+
          "_table_"+tableNameString +
          "_region_" + i.getEncodedName()+
          "_metric";
      metricsHelper.assertCounter(prefix + "_getNumOps", 10, agg);
      metricsHelper.assertCounter(prefix + "_mutateCount", 30, agg);
    }


    metricsRegionServer.getRegionServerWrapper().forceRecompute();
    metricsHelper.assertCounterGt("totalRequestCount", requests + 39, serverSource);
    metricsHelper.assertCounterGt("readRequestCount", readRequests + 9, serverSource);
    metricsHelper.assertCounterGt("writeRequestCount", writeRequests + 29, serverSource);

    table.close();
  }

  @Test
  public void testMutationsWithoutWal() throws Exception {
    byte[] tableName = Bytes.toBytes("testMutationsWithoutWal");
    byte[] cf = Bytes.toBytes("d");
    byte[] row = Bytes.toBytes("rk");
    byte[] qualifier = Bytes.toBytes("qual");
    byte[] val = Bytes.toBytes("Value");

    metricsRegionServer.getRegionServerWrapper().forceRecompute();

    TEST_UTIL.createTable(tableName, cf);

    HTable t = new HTable(conf, tableName);

    Put p = new Put(row);
    p.add(cf, qualifier, val);
    p.setDurability(Durability.SKIP_WAL);

    t.put(p);
    t.flushCommits();

    metricsRegionServer.getRegionServerWrapper().forceRecompute();
    metricsHelper.assertGauge("mutationsWithoutWALCount", 1, serverSource);
    long minLength = row.length + cf.length + qualifier.length + val.length;
    metricsHelper.assertGaugeGt("mutationsWithoutWALSize", minLength, serverSource);

    t.close();
  }

  @Test
  public void testStoreCount() throws Exception {
    byte[] tableName = Bytes.toBytes("testStoreCount");
    byte[] cf = Bytes.toBytes("d");
    byte[] row = Bytes.toBytes("rk");
    byte[] qualifier = Bytes.toBytes("qual");
    byte[] val = Bytes.toBytes("Value");

    metricsRegionServer.getRegionServerWrapper().forceRecompute();
    long stores = metricsHelper.getGaugeLong("storeCount", serverSource);
    long storeFiles = metricsHelper.getGaugeLong("storeFileCount", serverSource);

    TEST_UTIL.createTable(tableName, cf);

    //Force a hfile.
    HTable t = new HTable(conf, tableName);
    Put p = new Put(row);
    p.add(cf, qualifier, val);
    t.put(p);
    t.flushCommits();
    TEST_UTIL.getHBaseAdmin().flush(tableName);

    metricsRegionServer.getRegionServerWrapper().forceRecompute();
    metricsHelper.assertGauge("storeCount", stores +1, serverSource);
    metricsHelper.assertGauge("storeFileCount", storeFiles + 1, serverSource);

    t.close();
  }

  @Test
  public void testCheckAndPutCount() throws Exception {
    String tableNameString = "testCheckAndPutCount";
    byte[] tableName = Bytes.toBytes(tableNameString);
    byte[] cf = Bytes.toBytes("d");
    byte[] row = Bytes.toBytes("rk");
    byte[] qualifier = Bytes.toBytes("qual");
    byte[] valOne = Bytes.toBytes("Value");
    byte[] valTwo = Bytes.toBytes("ValueTwo");
    byte[] valThree = Bytes.toBytes("ValueThree");

    TEST_UTIL.createTable(tableName, cf);
    HTable t = new HTable(conf, tableName);
    Put p = new Put(row);
    p.add(cf, qualifier, valOne);
    t.put(p);
    t.flushCommits();

    Put pTwo = new Put(row);
    pTwo.add(cf, qualifier, valTwo);
    t.checkAndPut(row, cf, qualifier, valOne, pTwo);
    t.flushCommits();

    Put pThree = new Put(row);
    pThree.add(cf, qualifier, valThree);
    t.checkAndPut(row, cf, qualifier, valOne, pThree);
    t.flushCommits();


    metricsRegionServer.getRegionServerWrapper().forceRecompute();
    metricsHelper.assertCounter("checkMutateFailedCount", 1, serverSource);
    metricsHelper.assertCounter("checkMutatePassedCount", 1, serverSource);

    t.close();
  }

  @Test
  public void testIncrement() throws Exception {
    String tableNameString = "testIncrement";
    byte[] tableName = Bytes.toBytes(tableNameString);
    byte[] cf = Bytes.toBytes("d");
    byte[] row = Bytes.toBytes("rk");
    byte[] qualifier = Bytes.toBytes("qual");
    byte[] val = Bytes.toBytes(0l);


    TEST_UTIL.createTable(tableName, cf);
    HTable t = new HTable(conf, tableName);

    Put p = new Put(row);
    p.add(cf, qualifier, val);
    t.put(p);
    t.flushCommits();

    for(int count = 0; count< 13; count++) {
      Increment inc = new Increment(row);
      inc.addColumn(cf, qualifier, 100);
      t.increment(inc);
    }

    t.flushCommits();

    metricsRegionServer.getRegionServerWrapper().forceRecompute();
    metricsHelper.assertCounter("incrementNumOps", 13, serverSource);

    t.close();
  }

  @Test
  public void testAppend() throws Exception {
    String tableNameString = "testAppend";
    byte[] tableName = Bytes.toBytes(tableNameString);
    byte[] cf = Bytes.toBytes("d");
    byte[] row = Bytes.toBytes("rk");
    byte[] qualifier = Bytes.toBytes("qual");
    byte[] val = Bytes.toBytes("One");


    TEST_UTIL.createTable(tableName, cf);
    HTable t = new HTable(conf, tableName);

    Put p = new Put(row);
    p.add(cf, qualifier, val);
    t.put(p);
    t.flushCommits();

    for(int count = 0; count< 73; count++) {
      Append append = new Append(row);
      append.add(cf, qualifier, Bytes.toBytes(",Test"));
      t.append(append);
    }

    t.flushCommits();

    metricsRegionServer.getRegionServerWrapper().forceRecompute();
    metricsHelper.assertCounter("appendNumOps", 73, serverSource);

    t.close();
  }

  @Test
  public void testScanNext() throws IOException {
    String tableNameString = "testScanNext";
    byte[] tableName = Bytes.toBytes(tableNameString);
    byte[] cf = Bytes.toBytes("d");
    byte[] qualifier = Bytes.toBytes("qual");
    byte[] val = Bytes.toBytes("One");


    TEST_UTIL.createTable(tableName, cf);
    HTable t = new HTable(conf, tableName);
    t.setAutoFlush(false, true);
    for (int insertCount =0; insertCount < 100; insertCount++) {
      Put p = new Put(Bytes.toBytes("" + insertCount + "row"));
      p.add(cf, qualifier, val);
      t.put(p);
    }
    t.flushCommits();

    Scan s = new Scan();
    s.setBatch(1);
    s.setCaching(1);
    ResultScanner resultScanners = t.getScanner(s);

    for (int nextCount = 0; nextCount < 30; nextCount++) {
      Result result = resultScanners.next();
      assertNotNull(result);
      assertEquals(1, result.size());
    }
    for ( HRegionInfo i:t.getRegionLocations().keySet()) {
      MetricsRegionAggregateSource agg = rs.getRegion(i.getRegionName())
          .getMetrics()
          .getSource()
          .getAggregateSource();
      String prefix = "namespace_"+NamespaceDescriptor.DEFAULT_NAMESPACE_NAME_STR+
          "_table_"+tableNameString +
          "_region_" + i.getEncodedName()+
          "_metric";
      metricsHelper.assertCounter(prefix + "_scanNextNumOps", 30, agg);
    }
  }
}
