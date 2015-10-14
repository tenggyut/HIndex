/**
 * Copyright The Apache Software Foundation
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
package org.apache.hadoop.hbase.snapshot;

import org.apache.hadoop.hbase.LargeTests;
import org.apache.hadoop.hbase.mapreduce.HadoopSecurityEnabledUserProviderForTesting;
import org.apache.hadoop.hbase.security.UserProvider;
import org.apache.hadoop.hbase.security.access.AccessControlLists;
import org.apache.hadoop.hbase.security.access.SecureTestUtil;

import org.junit.BeforeClass;
import org.junit.experimental.categories.Category;

/**
 * Reruns TestExportSnapshot using ExportSnapshot in secure mode.
 */
@Category(LargeTests.class)
public class TestSecureExportSnapshot extends TestExportSnapshot {
  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    setUpBaseConf(TEST_UTIL.getConfiguration());

    // set the always on security provider
    UserProvider.setUserProviderForTesting(TEST_UTIL.getConfiguration(),
      HadoopSecurityEnabledUserProviderForTesting.class);

    // setup configuration
    SecureTestUtil.enableSecurity(TEST_UTIL.getConfiguration());

    TEST_UTIL.startMiniCluster(3);
    TEST_UTIL.startMiniMapReduceCluster();

    // Wait for the ACL table to become available
    TEST_UTIL.waitTableEnabled(AccessControlLists.ACL_TABLE_NAME.getName());
  }
}
