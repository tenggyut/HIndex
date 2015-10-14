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
package org.apache.hadoop.hbase.client;

import static org.apache.hadoop.hbase.HBaseTestingUtility.fam1;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.CellScannable;
import org.apache.hadoop.hbase.CellScanner;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.coprocessor.CoprocessorHost;
import org.apache.hadoop.hbase.coprocessor.ProtobufCoprocessorService;
import org.apache.hadoop.hbase.ipc.DelegatingPayloadCarryingRpcController;
import org.apache.hadoop.hbase.ipc.PayloadCarryingRpcController;
import org.apache.hadoop.hbase.ipc.RpcControllerFactory;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import com.google.common.collect.Lists;

@Category(MediumTests.class)
public class TestRpcControllerFactory {

  public static class StaticRpcControllerFactory extends RpcControllerFactory {

    public StaticRpcControllerFactory(Configuration conf) {
      super(conf);
    }

    public PayloadCarryingRpcController newController() {
      return new CountingRpcController(super.newController());
    }

    public PayloadCarryingRpcController newController(final CellScanner cellScanner) {
      return new CountingRpcController(super.newController(cellScanner));
    }

    public PayloadCarryingRpcController newController(final List<CellScannable> cellIterables) {
      return new CountingRpcController(super.newController(cellIterables));
    }
  }

  public static class CountingRpcController extends DelegatingPayloadCarryingRpcController {

    private static AtomicInteger INT_PRIORITY = new AtomicInteger();
    private static AtomicInteger TABLE_PRIORITY = new AtomicInteger();

    public CountingRpcController(PayloadCarryingRpcController delegate) {
      super(delegate);
    }

    @Override
    public void setPriority(int priority) {
      super.setPriority(priority);
      INT_PRIORITY.incrementAndGet();
    }

    @Override
    public void setPriority(TableName tn) {
      super.setPriority(tn);
      // ignore counts for system tables - it could change and we really only want to check on what
      // the client should change
      if (!tn.isSystemTable()) {
        TABLE_PRIORITY.incrementAndGet();
      }

    }
  }

  private static final HBaseTestingUtility UTIL = new HBaseTestingUtility();

  @BeforeClass
  public static void setup() throws Exception {
    // load an endpoint so we have an endpoint to test - it doesn't matter which one, but
    // this is already in tests, so we can just use it.
    Configuration conf = UTIL.getConfiguration();
    conf.set(CoprocessorHost.REGION_COPROCESSOR_CONF_KEY,
      ProtobufCoprocessorService.class.getName());
    
    UTIL.startMiniCluster();
  }

  @AfterClass
  public static void teardown() throws Exception {
    UTIL.shutdownMiniCluster();
  }

  /**
   * check some of the methods and make sure we are incrementing each time. Its a bit tediuous to
   * cover all methods here and really is a bit brittle since we can always add new methods but
   * won't be sure to add them here. So we just can cover the major ones.
   * @throws Exception on failure
   */
  @Test
  public void testCountController() throws Exception {
    Configuration conf = new Configuration(UTIL.getConfiguration());
    // setup our custom controller
    conf.set(RpcControllerFactory.CUSTOM_CONTROLLER_CONF_KEY,
      StaticRpcControllerFactory.class.getName());

    TableName name = TableName.valueOf("testcustomcontroller");
    UTIL.createTable(name, fam1).close();

    // change one of the connection properties so we get a new HConnection with our configuration
    conf.setInt(HConstants.HBASE_RPC_TIMEOUT_KEY, HConstants.DEFAULT_HBASE_RPC_TIMEOUT + 1);

    HTable table = new HTable(conf, name);
    table.setAutoFlushTo(false);
    byte[] row = Bytes.toBytes("row");
    Put p = new Put(row);
    p.add(fam1, fam1, Bytes.toBytes("val0"));
    table.put(p);
    table.flushCommits();
    Integer counter = 1;
    counter = verifyCount(counter);

    Delete d = new Delete(row);
    d.deleteColumn(fam1, fam1);
    table.delete(d);
    counter = verifyCount(counter);

    Put p2 = new Put(row);
    p2.add(fam1, Bytes.toBytes("qual"), Bytes.toBytes("val1"));
    table.batch(Lists.newArrayList(p, p2), new Object[2]);
    // this only goes to a single server, so we don't need to change the count here
    counter = verifyCount(counter);

    Append append = new Append(row);
    append.add(fam1, fam1, Bytes.toBytes("val2"));
    table.append(append);
    counter = verifyCount(counter);

    // and check the major lookup calls as well
    Get g = new Get(row);
    table.get(g);
    counter = verifyCount(counter);

    ResultScanner scan = table.getScanner(fam1);
    scan.next();
    scan.close();
    counter = verifyCount(counter);

    Get g2 = new Get(row);
    table.get(Lists.newArrayList(g, g2));
    // same server, so same as above for not changing count
    counter = verifyCount(counter);

    // make sure all the scanner types are covered
    Scan scanInfo = new Scan(row);
    // regular small
    scanInfo.setSmall(true);
    counter = doScan(table, scanInfo, counter);

    // reversed, small
    scanInfo.setReversed(true);
    counter = doScan(table, scanInfo, counter);

    // reversed, regular
    scanInfo.setSmall(false);
    counter = doScan(table, scanInfo, counter);

    table.close();
  }

  int doScan(HTable table, Scan scan, int expectedCount) throws IOException {
    ResultScanner results = table.getScanner(scan);
    results.next();
    results.close();
    return verifyCount(expectedCount);
  }

  int verifyCount(Integer counter) {
    assertEquals(counter.intValue(), CountingRpcController.TABLE_PRIORITY.get());
    assertEquals(0, CountingRpcController.INT_PRIORITY.get());
    return counter + 1;
  }
}