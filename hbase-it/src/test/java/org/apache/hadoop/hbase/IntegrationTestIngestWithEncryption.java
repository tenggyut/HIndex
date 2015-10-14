/*
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

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Waiter.Predicate;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.io.crypto.KeyProviderForTesting;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.io.hfile.HFileReaderV3;
import org.apache.hadoop.hbase.io.hfile.HFileWriterV3;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.hadoop.hbase.regionserver.wal.SecureProtobufLogReader;
import org.apache.hadoop.hbase.regionserver.wal.SecureProtobufLogWriter;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import org.junit.Before;
import org.junit.experimental.categories.Category;

@Category(IntegrationTests.class)
public class IntegrationTestIngestWithEncryption extends IntegrationTestIngest {

  static {
    // These log level changes are only useful when running on a localhost
    // cluster.
    Logger.getLogger(HFileReaderV3.class).setLevel(Level.TRACE);
    Logger.getLogger(HFileWriterV3.class).setLevel(Level.TRACE);
    Logger.getLogger(SecureProtobufLogReader.class).setLevel(Level.TRACE);
    Logger.getLogger(SecureProtobufLogWriter.class).setLevel(Level.TRACE);
  }

  @Override
  public void setUpCluster() throws Exception {
    util = getTestingUtil(null);
    Configuration conf = util.getConfiguration();
    conf.setInt(HFile.FORMAT_VERSION_KEY, 3);
    if (!util.isDistributedCluster()) {
      // Inject the test key provider and WAL alternative if running on a
      // localhost cluster; otherwise, whether or not the schema change below
      // takes effect depends on the distributed cluster site configuration.
      conf.set(HConstants.CRYPTO_KEYPROVIDER_CONF_KEY, KeyProviderForTesting.class.getName());
      conf.set(HConstants.CRYPTO_MASTERKEY_NAME_CONF_KEY, "hbase");
      conf.setClass("hbase.regionserver.hlog.reader.impl", SecureProtobufLogReader.class,
        HLog.Reader.class);
      conf.setClass("hbase.regionserver.hlog.writer.impl", SecureProtobufLogWriter.class,
        HLog.Writer.class);
      conf.setBoolean(HConstants.ENABLE_WAL_ENCRYPTION, true);
    }
    super.setUpCluster();
  }

  @Before
  @Override
  public void setUp() throws Exception {
    // Initialize the cluster. This invokes LoadTestTool -init_only, which
    // will create the test table, appropriately pre-split
    super.setUp();

    // Update the test table schema so HFiles from this point will be written with
    // encryption features enabled.
    final HBaseAdmin admin = util.getHBaseAdmin();
    HTableDescriptor tableDescriptor =
        new HTableDescriptor(admin.getTableDescriptor(Bytes.toBytes(getTablename())));
    for (HColumnDescriptor columnDescriptor: tableDescriptor.getColumnFamilies()) {
      columnDescriptor.setEncryptionType("AES");
      LOG.info("Updating CF schema for " + getTablename() + "." +
        columnDescriptor.getNameAsString());
      admin.disableTable(getTablename());
      admin.modifyColumn(getTablename(), columnDescriptor);
      admin.enableTable(getTablename());
      util.waitFor(30000, 1000, true, new Predicate<IOException>() {
        @Override
        public boolean evaluate() throws IOException {
          return admin.isTableAvailable(getTablename());
        }
      });
    }
  }

  public static void main(String[] args) throws Exception {
    Configuration conf = HBaseConfiguration.create();
    IntegrationTestingUtility.setUseDistributedCluster(conf);
    int ret = ToolRunner.run(conf, new IntegrationTestIngestWithEncryption(), args);
    System.exit(ret);
  }
}
