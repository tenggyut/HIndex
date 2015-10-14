/*
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
package org.apache.hadoop.hbase.security.access;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.NamespaceDescriptor;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.coprocessor.MasterCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.generated.AccessControlProtos.AccessControlService;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.security.access.Permission.Action;
import org.apache.hadoop.hbase.util.Bytes;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import com.google.common.collect.ListMultimap;
import com.google.protobuf.BlockingRpcChannel;

@Category(MediumTests.class)
public class TestNamespaceCommands extends SecureTestUtil {
  private static HBaseTestingUtility UTIL = new HBaseTestingUtility();
  private static String TestNamespace = "ns1";
  private static Configuration conf;
  private static MasterCoprocessorEnvironment CP_ENV;
  private static AccessController ACCESS_CONTROLLER;
  
  // user with all permissions
  private static User SUPERUSER;
  // user with rw permissions
  private static User USER_RW;
  // user with create table permissions alone
  private static User USER_CREATE;
  // user with permission on namespace for testing all operations.
  private static User USER_NSP_WRITE;
  
  @BeforeClass
  public static void beforeClass() throws Exception {
    conf = UTIL.getConfiguration();
    enableSecurity(conf);

    SUPERUSER = User.createUserForTesting(conf, "admin", new String[] { "supergroup" });
    USER_RW = User.createUserForTesting(conf, "rw_user", new String[0]);
    USER_CREATE = User.createUserForTesting(conf, "create_user", new String[0]);
    USER_NSP_WRITE = User.createUserForTesting(conf, "namespace_write", new String[0]);

    UTIL.startMiniCluster();
    // Wait for the ACL table to become available
    UTIL.waitTableAvailable(AccessControlLists.ACL_TABLE_NAME.getName(), 30 * 1000);

    ACCESS_CONTROLLER = (AccessController) UTIL.getMiniHBaseCluster().getMaster()
      .getCoprocessorHost()
        .findCoprocessor(AccessController.class.getName());

    UTIL.getHBaseAdmin().createNamespace(NamespaceDescriptor.create(TestNamespace).build());

    grantOnNamespace(UTIL, USER_NSP_WRITE.getShortName(),
      TestNamespace, Permission.Action.WRITE);
  }
  
  @AfterClass
  public static void afterClass() throws Exception {
    UTIL.getHBaseAdmin().deleteNamespace(TestNamespace);
    UTIL.shutdownMiniCluster();
  }

  @Test
  public void testAclTableEntries() throws Exception {
    String userTestNamespace = "userTestNsp";
    HTable acl = new HTable(conf, AccessControlLists.ACL_TABLE_NAME);
    try {
      // Grant and check state in ACL table
      grantOnNamespace(UTIL, userTestNamespace, TestNamespace,
        Permission.Action.WRITE);

      Result result = acl.get(new Get(Bytes.toBytes(userTestNamespace)));
      assertTrue(result != null);
      ListMultimap<String, TablePermission> perms =
          AccessControlLists.getNamespacePermissions(conf, TestNamespace);
      assertEquals(2, perms.size());
      List<TablePermission> namespacePerms = perms.get(userTestNamespace);
      assertTrue(perms.containsKey(userTestNamespace));
      assertEquals(1, namespacePerms.size());
      assertEquals(TestNamespace,
        namespacePerms.get(0).getNamespace());
      assertEquals(null, namespacePerms.get(0).getFamily());
      assertEquals(null, namespacePerms.get(0).getQualifier());
      assertEquals(1, namespacePerms.get(0).getActions().length);
      assertEquals(Permission.Action.WRITE, namespacePerms.get(0).getActions()[0]);

      // Revoke and check state in ACL table
      revokeFromNamespace(UTIL, userTestNamespace, TestNamespace,
        Permission.Action.WRITE);

      perms = AccessControlLists.getNamespacePermissions(conf, TestNamespace);
      assertEquals(1, perms.size());
    } finally {
      acl.close();
    }
  }
  
  @Test
  public void testModifyNamespace() throws Exception {
    AccessTestAction modifyNamespace = new AccessTestAction() {
      public Object run() throws Exception {
        ACCESS_CONTROLLER.preModifyNamespace(ObserverContext.createAndPrepare(CP_ENV, null),
          NamespaceDescriptor.create(TestNamespace).addConfiguration("abc", "156").build());
        return null;
      }
    };
    // verify that superuser or hbase admin can modify namespaces.
    verifyAllowed(modifyNamespace, SUPERUSER);
    // all others should be denied
    verifyDenied(modifyNamespace, USER_NSP_WRITE, USER_CREATE, USER_RW);
  }
  
  @Test
  public void testGrantRevoke() throws Exception{
    final String testUser = "testUser";

    // Test if client API actions are authorized

    AccessTestAction grantAction = new AccessTestAction() {
      public Object run() throws Exception {
        HTable acl = new HTable(conf, AccessControlLists.ACL_TABLE_NAME);
        try {
          BlockingRpcChannel service =
              acl.coprocessorService(HConstants.EMPTY_START_ROW);
          AccessControlService.BlockingInterface protocol =
            AccessControlService.newBlockingStub(service);
          ProtobufUtil.grant(protocol, testUser, TestNamespace, Action.WRITE);
        } finally {
          acl.close();
        }
        return null;
      }
    };

    AccessTestAction revokeAction = new AccessTestAction() {
      public Object run() throws Exception {
        HTable acl = new HTable(conf, AccessControlLists.ACL_TABLE_NAME);
        try {
          BlockingRpcChannel service =
              acl.coprocessorService(HConstants.EMPTY_START_ROW);
          AccessControlService.BlockingInterface protocol =
            AccessControlService.newBlockingStub(service);
          ProtobufUtil.revoke(protocol, testUser, TestNamespace, Action.WRITE);
        } finally {
          acl.close();
        }
        return null;
      }
    };

    // Only HBase super user should be able to grant and revoke permissions to
    // namespaces
    verifyAllowed(grantAction, SUPERUSER);
    verifyDenied(grantAction, USER_CREATE, USER_RW);
    verifyAllowed(revokeAction, SUPERUSER);
    verifyDenied(revokeAction, USER_CREATE, USER_RW);    
  }
}
