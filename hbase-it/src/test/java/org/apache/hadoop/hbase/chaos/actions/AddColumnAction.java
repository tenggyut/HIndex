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

package org.apache.hadoop.hbase.chaos.actions;

import java.io.IOException;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.util.Bytes;

/**
 * Action the adds a column family to a table.
 */
public class AddColumnAction extends Action {
  private final byte[] tableName;
  private final String tableNameString;
  private HBaseAdmin admin;

  public AddColumnAction(String tableName) {
    tableNameString = tableName;
    this.tableName = Bytes.toBytes(tableName);
  }

  @Override
  public void init(ActionContext context) throws IOException {
    super.init(context);
    this.admin = context.getHBaseIntegrationTestingUtility().getHBaseAdmin();
  }

  @Override
  public void perform() throws Exception {
    HTableDescriptor tableDescriptor = admin.getTableDescriptor(tableName);
    HColumnDescriptor columnDescriptor = null;

    while(columnDescriptor == null ||
        tableDescriptor.getFamily(columnDescriptor.getName()) != null) {
      columnDescriptor = new HColumnDescriptor(RandomStringUtils.randomAlphabetic(5));
    }

    LOG.debug("Performing action: Adding " + columnDescriptor + " to " + tableNameString);

    tableDescriptor.addFamily(columnDescriptor);
    admin.modifyTable(tableName, tableDescriptor);
  }
}
