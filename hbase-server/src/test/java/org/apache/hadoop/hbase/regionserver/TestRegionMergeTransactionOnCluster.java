/**
 * Copyright The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hadoop.hbase.regionserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;

import org.apache.commons.lang.math.RandomUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.LargeTests;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.catalog.MetaReader;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.exceptions.MergeRegionException;
import org.apache.hadoop.hbase.UnknownRegionException;
import org.apache.hadoop.hbase.master.AssignmentManager;
import org.apache.hadoop.hbase.master.HMaster;
import org.apache.hadoop.hbase.master.RegionStates;
import org.apache.hadoop.hbase.master.RegionState.State;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.PairOfSameType;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import com.google.common.base.Joiner;

/**
 * Like {@link TestRegionMergeTransaction} in that we're testing
 * {@link RegionMergeTransaction} only the below tests are against a running
 * cluster where {@link TestRegionMergeTransaction} is tests against bare
 * {@link HRegion}.
 */
@Category(LargeTests.class)
public class TestRegionMergeTransactionOnCluster {
  private static final Log LOG = LogFactory
      .getLog(TestRegionMergeTransactionOnCluster.class);
  private static final int NB_SERVERS = 3;

  private static final byte[] FAMILYNAME = Bytes.toBytes("fam");
  private static final byte[] QUALIFIER = Bytes.toBytes("q");

  private static byte[] ROW = Bytes.toBytes("testRow");
  private static final int INITIAL_REGION_NUM = 10;
  private static final int ROWSIZE = 200;
  private static byte[][] ROWS = makeN(ROW, ROWSIZE);

  private static int waitTime = 60 * 1000;

  private static final HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();

  private static HMaster master;
  private static HBaseAdmin admin;

  @BeforeClass
  public static void beforeAllTests() throws Exception {
    // Start a cluster
    TEST_UTIL.startMiniCluster(NB_SERVERS);
    MiniHBaseCluster cluster = TEST_UTIL.getHBaseCluster();
    master = cluster.getMaster();
    master.balanceSwitch(false);
    admin = TEST_UTIL.getHBaseAdmin();
  }

  @AfterClass
  public static void afterAllTests() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  @Test
  public void testWholesomeMerge() throws Exception {
    LOG.info("Starting testWholesomeMerge");
    final TableName tableName =
        TableName.valueOf("testWholesomeMerge");

    // Create table and load data.
    HTable table = createTableAndLoadData(master, tableName);
    // Merge 1st and 2nd region
    mergeRegionsAndVerifyRegionNum(master, tableName, 0, 1,
        INITIAL_REGION_NUM - 1);

    // Merge 2nd and 3th region
    PairOfSameType<HRegionInfo> mergedRegions =
      mergeRegionsAndVerifyRegionNum(master, tableName, 1, 2,
        INITIAL_REGION_NUM - 2);

    verifyRowCount(table, ROWSIZE);

    // Randomly choose one of the two merged regions
    HRegionInfo hri = RandomUtils.nextBoolean() ?
      mergedRegions.getFirst() : mergedRegions.getSecond();
    MiniHBaseCluster cluster = TEST_UTIL.getHBaseCluster();
    AssignmentManager am = cluster.getMaster().getAssignmentManager();
    RegionStates regionStates = am.getRegionStates();
    long start = EnvironmentEdgeManager.currentTimeMillis();
    while (!regionStates.isRegionInState(hri, State.MERGED)) {
      assertFalse("Timed out in waiting one merged region to be in state MERGED",
        EnvironmentEdgeManager.currentTimeMillis() - start > 60000);
      Thread.sleep(500);
    }

    // We should not be able to assign it again
    am.assign(hri, true, true);
    assertFalse("Merged region can't be assigned",
      regionStates.isRegionInTransition(hri));
    assertTrue(regionStates.isRegionInState(hri, State.MERGED));

    // We should not be able to unassign it either
    am.unassign(hri, true, null);
    assertFalse("Merged region can't be unassigned",
      regionStates.isRegionInTransition(hri));
    assertTrue(regionStates.isRegionInState(hri, State.MERGED));

    table.close();
  }

  @Test
  public void testCleanMergeReference() throws Exception {
    LOG.info("Starting testCleanMergeReference");
    admin.enableCatalogJanitor(false);
    try {
      final TableName tableName =
          TableName.valueOf("testCleanMergeReference");
      // Create table and load data.
      HTable table = createTableAndLoadData(master, tableName);
      // Merge 1st and 2nd region
      mergeRegionsAndVerifyRegionNum(master, tableName, 0, 1,
          INITIAL_REGION_NUM - 1);
      verifyRowCount(table, ROWSIZE);
      table.close();

      List<Pair<HRegionInfo, ServerName>> tableRegions = MetaReader
          .getTableRegionsAndLocations(master.getCatalogTracker(),
              tableName);
      HRegionInfo mergedRegionInfo = tableRegions.get(0).getFirst();
      HTableDescriptor tableDescritor = master.getTableDescriptors().get(
          tableName);
      Result mergedRegionResult = MetaReader.getRegionResult(
          master.getCatalogTracker(), mergedRegionInfo.getRegionName());

      // contains merge reference in META
      assertTrue(mergedRegionResult.getValue(HConstants.CATALOG_FAMILY,
          HConstants.MERGEA_QUALIFIER) != null);
      assertTrue(mergedRegionResult.getValue(HConstants.CATALOG_FAMILY,
          HConstants.MERGEB_QUALIFIER) != null);

      // merging regions' directory are in the file system all the same
      HRegionInfo regionA = HRegionInfo.getHRegionInfo(mergedRegionResult,
          HConstants.MERGEA_QUALIFIER);
      HRegionInfo regionB = HRegionInfo.getHRegionInfo(mergedRegionResult,
          HConstants.MERGEB_QUALIFIER);
      FileSystem fs = master.getMasterFileSystem().getFileSystem();
      Path rootDir = master.getMasterFileSystem().getRootDir();

      Path tabledir = FSUtils.getTableDir(rootDir, mergedRegionInfo.getTable());
      Path regionAdir = new Path(tabledir, regionA.getEncodedName());
      Path regionBdir = new Path(tabledir, regionB.getEncodedName());
      assertTrue(fs.exists(regionAdir));
      assertTrue(fs.exists(regionBdir));

      admin.compact(mergedRegionInfo.getRegionName());
      // wait until merged region doesn't have reference file
      long timeout = System.currentTimeMillis() + waitTime;
      HRegionFileSystem hrfs = new HRegionFileSystem(
          TEST_UTIL.getConfiguration(), fs, tabledir, mergedRegionInfo);
      while (System.currentTimeMillis() < timeout) {
        if (!hrfs.hasReferences(tableDescritor)) {
          break;
        }
        Thread.sleep(50);
      }
      assertFalse(hrfs.hasReferences(tableDescritor));

      // run CatalogJanitor to clean merge references in hbase:meta and archive the
      // files of merging regions
      int cleaned = admin.runCatalogScan();
      assertTrue(cleaned > 0);
      assertFalse(fs.exists(regionAdir));
      assertFalse(fs.exists(regionBdir));

      mergedRegionResult = MetaReader.getRegionResult(
          master.getCatalogTracker(), mergedRegionInfo.getRegionName());
      assertFalse(mergedRegionResult.getValue(HConstants.CATALOG_FAMILY,
          HConstants.MERGEA_QUALIFIER) != null);
      assertFalse(mergedRegionResult.getValue(HConstants.CATALOG_FAMILY,
          HConstants.MERGEB_QUALIFIER) != null);

    } finally {
      admin.enableCatalogJanitor(true);
    }
  }

  /**
   * This test tests 1, merging region not online;
   * 2, merging same two regions; 3, merging unknown regions.
   * They are in one test case so that we don't have to create
   * many tables, and these tests are simple.
   */
  @Test
  public void testMerge() throws Exception {
    LOG.info("Starting testMerge");
    final TableName tableName = TableName.valueOf("testMerge");

    try {
      // Create table and load data.
      HTable table = createTableAndLoadData(master, tableName);
      RegionStates regionStates = master.getAssignmentManager().getRegionStates();
      List<HRegionInfo> regions = regionStates.getRegionsOfTable(tableName);
      // Fake offline one region
      HRegionInfo a = regions.get(0);
      HRegionInfo b = regions.get(1);
      regionStates.regionOffline(a);
      try {
        // Merge offline region. Region a is offline here
        admin.mergeRegions(a.getEncodedNameAsBytes(), b.getEncodedNameAsBytes(), false);
        fail("Offline regions should not be able to merge");
      } catch (IOException ie) {
        assertTrue("Exception should mention regions not online",
          ie.getMessage().contains("regions not online")
            && ie instanceof MergeRegionException);
      }
      try {
        // Merge the same region: b and b.
        admin.mergeRegions(b.getEncodedNameAsBytes(), b.getEncodedNameAsBytes(), true);
        fail("A region should not be able to merge with itself, even forcifully");
      } catch (IOException ie) {
        assertTrue("Exception should mention regions not online",
          ie.getMessage().contains("region to itself")
            && ie instanceof MergeRegionException);
      }
      try {
        // Merge unknown regions
        admin.mergeRegions(Bytes.toBytes("-f1"), Bytes.toBytes("-f2"), true);
        fail("Unknown region could not be merged");
      } catch (IOException ie) {
        assertTrue("UnknownRegionException should be thrown",
          ie instanceof UnknownRegionException);
      }
      table.close();
    } finally {
      TEST_UTIL.deleteTable(tableName);
    }
  }

  private PairOfSameType<HRegionInfo> mergeRegionsAndVerifyRegionNum(
      HMaster master, TableName tablename,
      int regionAnum, int regionBnum, int expectedRegionNum) throws Exception {
    PairOfSameType<HRegionInfo> mergedRegions =
      requestMergeRegion(master, tablename, regionAnum, regionBnum);
    waitAndVerifyRegionNum(master, tablename, expectedRegionNum);
    return mergedRegions;
  }

  private PairOfSameType<HRegionInfo> requestMergeRegion(
      HMaster master, TableName tablename,
      int regionAnum, int regionBnum) throws Exception {
    List<Pair<HRegionInfo, ServerName>> tableRegions = MetaReader
        .getTableRegionsAndLocations(master.getCatalogTracker(),
            tablename);
    HRegionInfo regionA = tableRegions.get(regionAnum).getFirst();
    HRegionInfo regionB = tableRegions.get(regionBnum).getFirst();
    TEST_UTIL.getHBaseAdmin().mergeRegions(
      regionA.getEncodedNameAsBytes(),
      regionB.getEncodedNameAsBytes(), false);
    return new PairOfSameType<HRegionInfo>(regionA, regionB);
  }

  private void waitAndVerifyRegionNum(HMaster master, TableName tablename,
      int expectedRegionNum) throws Exception {
    List<Pair<HRegionInfo, ServerName>> tableRegionsInMeta;
    List<HRegionInfo> tableRegionsInMaster;
    long timeout = System.currentTimeMillis() + waitTime;
    while (System.currentTimeMillis() < timeout) {
      tableRegionsInMeta = MetaReader.getTableRegionsAndLocations(
          master.getCatalogTracker(), tablename);
      tableRegionsInMaster = master.getAssignmentManager().getRegionStates()
          .getRegionsOfTable(tablename);
      if (tableRegionsInMeta.size() == expectedRegionNum
          && tableRegionsInMaster.size() == expectedRegionNum) {
        break;
      }
      Thread.sleep(250);
    }

    tableRegionsInMeta = MetaReader.getTableRegionsAndLocations(
        master.getCatalogTracker(), tablename);
    LOG.info("Regions after merge:" + Joiner.on(',').join(tableRegionsInMeta));
    assertEquals(expectedRegionNum, tableRegionsInMeta.size());
  }

  private HTable createTableAndLoadData(HMaster master, TableName tablename)
      throws Exception {
    return createTableAndLoadData(master, tablename, INITIAL_REGION_NUM);
  }

  private HTable createTableAndLoadData(HMaster master, TableName tablename,
      int numRegions) throws Exception {
    assertTrue("ROWSIZE must > numregions:" + numRegions, ROWSIZE > numRegions);
    byte[][] splitRows = new byte[numRegions - 1][];
    for (int i = 0; i < splitRows.length; i++) {
      splitRows[i] = ROWS[(i + 1) * ROWSIZE / numRegions];
    }

    HTable table = TEST_UTIL.createTable(tablename, FAMILYNAME, splitRows);
    loadData(table);
    verifyRowCount(table, ROWSIZE);

    // sleep here is an ugly hack to allow region transitions to finish
    long timeout = System.currentTimeMillis() + waitTime;
    List<Pair<HRegionInfo, ServerName>> tableRegions;
    while (System.currentTimeMillis() < timeout) {
      tableRegions = MetaReader.getTableRegionsAndLocations(
          master.getCatalogTracker(), tablename);
      if (tableRegions.size() == numRegions)
        break;
      Thread.sleep(250);
    }

    tableRegions = MetaReader.getTableRegionsAndLocations(
        master.getCatalogTracker(), tablename);
    LOG.info("Regions after load: " + Joiner.on(',').join(tableRegions));
    assertEquals(numRegions, tableRegions.size());
    return table;
  }

  private static byte[][] makeN(byte[] base, int n) {
    byte[][] ret = new byte[n][];
    for (int i = 0; i < n; i++) {
      ret[i] = Bytes.add(base, Bytes.toBytes(String.format("%04d", i)));
    }
    return ret;
  }

  private void loadData(HTable table) throws IOException {
    for (int i = 0; i < ROWSIZE; i++) {
      Put put = new Put(ROWS[i]);
      put.add(FAMILYNAME, QUALIFIER, Bytes.toBytes(i));
      table.put(put);
    }
  }

  private void verifyRowCount(HTable table, int expectedRegionNum)
      throws IOException {
    ResultScanner scanner = table.getScanner(new Scan());
    int rowCount = 0;
    while (scanner.next() != null) {
      rowCount++;
    }
    assertEquals(expectedRegionNum, rowCount);
    scanner.close();
  }
}
