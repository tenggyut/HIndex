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

package org.apache.hadoop.hbase;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.util.RegionSplitter;
import org.apache.hadoop.hbase.util.RegionSplitter.SplitAlgorithm;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * An integration test to detect regressions in HBASE-7220. Create
 * a table with many regions and verify it completes within a
 * reasonable amount of time.
 * @see <a href="https://issues.apache.org/jira/browse/HBASE-7220">HBASE-7220</a>
 */
@Category(IntegrationTests.class)
public class IntegrationTestManyRegions {

  private static final String CLASS_NAME
    = IntegrationTestManyRegions.class.getSimpleName();

  protected static final Log LOG
    = LogFactory.getLog(IntegrationTestManyRegions.class);
  protected static final String TABLE_NAME = CLASS_NAME;
  protected static final String COLUMN_NAME = "f";
  protected static final String REGION_COUNT_KEY
    = String.format("hbase.%s.regions", CLASS_NAME);
  protected static final String REGIONSERVER_COUNT_KEY
    = String.format("hbase.%s.regionServers", CLASS_NAME);
  protected static final String TIMEOUT_MINUTES_KEY
    = String.format("hbase.%s.timeoutMinutes", CLASS_NAME);

  protected static final int DEFAULT_REGION_COUNT = 1000;
  protected static final int DEFAULT_REGIONSERVER_COUNT = 1;
  // running this test on my laptop consistently takes about 2.5
  // minutes. A timeout of 4 minutes should be reasonably safe.
  protected static final int DEFAULT_TIMEOUT_MINUTES = 4;
  protected static final IntegrationTestingUtility util
    = new IntegrationTestingUtility();

  protected static final int REGION_COUNT = util.getConfiguration()
    .getInt(REGION_COUNT_KEY, DEFAULT_REGION_COUNT);
  protected static final int REGION_SERVER_COUNT = util.getConfiguration()
    .getInt(REGIONSERVER_COUNT_KEY, DEFAULT_REGIONSERVER_COUNT);
  protected static final int TIMEOUT_MINUTES = util.getConfiguration()
    .getInt(TIMEOUT_MINUTES_KEY, DEFAULT_TIMEOUT_MINUTES);

  @Before
  public void setUp() throws Exception {
    LOG.info(String.format("Initializing cluster with %d region servers.",
      REGION_SERVER_COUNT));
    util.initializeCluster(REGION_SERVER_COUNT);
    LOG.info("Cluster initialized");

    HBaseAdmin admin = util.getHBaseAdmin();
    if (admin.tableExists(TABLE_NAME)) {
      LOG.info(String.format("Deleting existing table %s.", TABLE_NAME));
      if (admin.isTableEnabled(TABLE_NAME)) admin.disableTable(TABLE_NAME);
      admin.deleteTable(TABLE_NAME);
      LOG.info(String.format("Existing table %s deleted.", TABLE_NAME));
    }
    LOG.info("Cluster ready");
  }

  @After
  public void tearDown() throws IOException {
    LOG.info("Cleaning up after test.");
    HBaseAdmin admin = util.getHBaseAdmin();
    if (admin.tableExists(TABLE_NAME)) {
      if (admin.isTableEnabled(TABLE_NAME)) admin.disableTable(TABLE_NAME);
      admin.deleteTable(TABLE_NAME);
    }
    LOG.info("Restoring cluster.");
    util.restoreCluster();
    LOG.info("Cluster restored.");
  }

  @Test
  public void testCreateTableWithRegions() throws Exception {
    CountDownLatch doneSignal = new CountDownLatch(1);
    Worker worker = new Worker(doneSignal, util.getHBaseAdmin());
    Thread t = new Thread(worker);

    LOG.info("Launching worker thread to create the table.");
    t.start();
    boolean workerComplete = false;
    workerComplete = doneSignal.await(TIMEOUT_MINUTES, TimeUnit.MINUTES);
    if (!workerComplete) {
      t.interrupt();
      fail("Timeout limit expired.");
    }
    assertTrue("Table creation failed.", worker.isSuccess());
  }

  private static class Worker implements Runnable {
    private final CountDownLatch doneSignal;
    private final HBaseAdmin admin;
    private boolean success = false;

    public Worker(final CountDownLatch doneSignal, final HBaseAdmin admin) {
      this.doneSignal = doneSignal;
      this.admin = admin;
    }

    public boolean isSuccess() {
      return this.success;
    }

    @Override
    public void run() {
      long startTime, endTime;
      HTableDescriptor desc = new HTableDescriptor(TableName.valueOf(TABLE_NAME));
      desc.addFamily(new HColumnDescriptor(COLUMN_NAME));
      SplitAlgorithm algo = new RegionSplitter.HexStringSplit();
      byte[][] splits = algo.split(REGION_COUNT);

      LOG.info(String.format("Creating table %s with %d splits.",
        TABLE_NAME, REGION_COUNT));
      startTime = System.currentTimeMillis();
      try {
        admin.createTable(desc, splits);
        endTime = System.currentTimeMillis();
        success = true;
        LOG.info(String.format("Pre-split table created successfully in %dms.",
          (endTime - startTime)));
      } catch (IOException e) {
        LOG.error("Failed to create table", e);
      } finally {
        doneSignal.countDown();
      }
    }
  }
}
