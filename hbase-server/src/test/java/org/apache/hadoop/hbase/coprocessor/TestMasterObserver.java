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

package org.apache.hadoop.hbase.coprocessor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.CountDownLatch;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.master.AssignmentManager;
import org.apache.hadoop.hbase.master.HMaster;
import org.apache.hadoop.hbase.master.MasterCoprocessorHost;
import org.apache.hadoop.hbase.master.RegionPlan;
import org.apache.hadoop.hbase.master.RegionState;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos.SnapshotDescription;
import org.apache.hadoop.hbase.protobuf.generated.MasterProtos.GetTableDescriptorsRequest;
import org.apache.hadoop.hbase.protobuf.RequestConverter;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Threads;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests invocation of the {@link org.apache.hadoop.hbase.coprocessor.MasterObserver}
 * interface hooks at all appropriate times during normal HMaster operations.
 */
@Category(MediumTests.class)
public class TestMasterObserver {
  private static final Log LOG = LogFactory.getLog(TestMasterObserver.class);

  public static CountDownLatch tableCreationLatch = new CountDownLatch(1);

  public static class CPMasterObserver implements MasterObserver {

    private boolean bypass = false;
    private boolean preCreateTableCalled;
    private boolean postCreateTableCalled;
    private boolean preDeleteTableCalled;
    private boolean postDeleteTableCalled;
    private boolean preModifyTableCalled;
    private boolean postModifyTableCalled;
    private boolean preCreateNamespaceCalled;
    private boolean postCreateNamespaceCalled;
    private boolean preDeleteNamespaceCalled;
    private boolean postDeleteNamespaceCalled;
    private boolean preModifyNamespaceCalled;
    private boolean postModifyNamespaceCalled;
    private boolean preAddColumnCalled;
    private boolean postAddColumnCalled;
    private boolean preModifyColumnCalled;
    private boolean postModifyColumnCalled;
    private boolean preDeleteColumnCalled;
    private boolean postDeleteColumnCalled;
    private boolean preEnableTableCalled;
    private boolean postEnableTableCalled;
    private boolean preDisableTableCalled;
    private boolean postDisableTableCalled;
    private boolean preMoveCalled;
    private boolean postMoveCalled;
    private boolean preAssignCalled;
    private boolean postAssignCalled;
    private boolean preUnassignCalled;
    private boolean postUnassignCalled;
    private boolean preRegionOfflineCalled;
    private boolean postRegionOfflineCalled;
    private boolean preBalanceCalled;
    private boolean postBalanceCalled;
    private boolean preBalanceSwitchCalled;
    private boolean postBalanceSwitchCalled;
    private boolean preShutdownCalled;
    private boolean preStopMasterCalled;
    private boolean preMasterInitializationCalled;
    private boolean postStartMasterCalled;
    private boolean startCalled;
    private boolean stopCalled;
    private boolean preSnapshotCalled;
    private boolean postSnapshotCalled;
    private boolean preCloneSnapshotCalled;
    private boolean postCloneSnapshotCalled;
    private boolean preRestoreSnapshotCalled;
    private boolean postRestoreSnapshotCalled;
    private boolean preDeleteSnapshotCalled;
    private boolean postDeleteSnapshotCalled;
    private boolean preCreateTableHandlerCalled;
    private boolean postCreateTableHandlerCalled;
    private boolean preDeleteTableHandlerCalled;
    private boolean postDeleteTableHandlerCalled;
    private boolean preAddColumnHandlerCalled;
    private boolean postAddColumnHandlerCalled;
    private boolean preModifyColumnHandlerCalled;
    private boolean postModifyColumnHandlerCalled;
    private boolean preDeleteColumnHandlerCalled;
    private boolean postDeleteColumnHandlerCalled;
    private boolean preEnableTableHandlerCalled;
    private boolean postEnableTableHandlerCalled;
    private boolean preDisableTableHandlerCalled;
    private boolean postDisableTableHandlerCalled;
    private boolean preModifyTableHandlerCalled;
    private boolean postModifyTableHandlerCalled;
    private boolean preGetTableDescriptorsCalled;
    private boolean postGetTableDescriptorsCalled;

    public void enableBypass(boolean bypass) {
      this.bypass = bypass;
    }

    public void resetStates() {
      preCreateTableCalled = false;
      postCreateTableCalled = false;
      preDeleteTableCalled = false;
      postDeleteTableCalled = false;
      preModifyTableCalled = false;
      postModifyTableCalled = false;
      preCreateNamespaceCalled = false;
      postCreateNamespaceCalled = false;
      preDeleteNamespaceCalled = false;
      postDeleteNamespaceCalled = false;
      preModifyNamespaceCalled = false;
      postModifyNamespaceCalled = false;
      preAddColumnCalled = false;
      postAddColumnCalled = false;
      preModifyColumnCalled = false;
      postModifyColumnCalled = false;
      preDeleteColumnCalled = false;
      postDeleteColumnCalled = false;
      preEnableTableCalled = false;
      postEnableTableCalled = false;
      preDisableTableCalled = false;
      postDisableTableCalled = false;
      preMoveCalled= false;
      postMoveCalled = false;
      preAssignCalled = false;
      postAssignCalled = false;
      preUnassignCalled = false;
      postUnassignCalled = false;
      preRegionOfflineCalled = false;
      postRegionOfflineCalled = false;
      preBalanceCalled = false;
      postBalanceCalled = false;
      preBalanceSwitchCalled = false;
      postBalanceSwitchCalled = false;
      preSnapshotCalled = false;
      postSnapshotCalled = false;
      preCloneSnapshotCalled = false;
      postCloneSnapshotCalled = false;
      preRestoreSnapshotCalled = false;
      postRestoreSnapshotCalled = false;
      preDeleteSnapshotCalled = false;
      postDeleteSnapshotCalled = false;
      preCreateTableHandlerCalled = false;
      postCreateTableHandlerCalled = false;
      preDeleteTableHandlerCalled = false;
      postDeleteTableHandlerCalled = false;
      preModifyTableHandlerCalled = false;
      postModifyTableHandlerCalled = false;
      preAddColumnHandlerCalled = false;
      postAddColumnHandlerCalled = false;
      preModifyColumnHandlerCalled = false;
      postModifyColumnHandlerCalled = false;
      preDeleteColumnHandlerCalled = false;
      postDeleteColumnHandlerCalled = false;
      preEnableTableHandlerCalled = false;
      postEnableTableHandlerCalled = false;
      preDisableTableHandlerCalled = false;
      postDisableTableHandlerCalled = false;
      preModifyTableHandlerCalled = false;
      postModifyTableHandlerCalled = false;
      preGetTableDescriptorsCalled = false;
      postGetTableDescriptorsCalled = false;
    }

    @Override
    public void preCreateTable(ObserverContext<MasterCoprocessorEnvironment> env,
        HTableDescriptor desc, HRegionInfo[] regions) throws IOException {
      if (bypass) {
        env.bypass();
      }
      preCreateTableCalled = true;
    }

    @Override
    public void postCreateTable(ObserverContext<MasterCoprocessorEnvironment> env,
        HTableDescriptor desc, HRegionInfo[] regions) throws IOException {
      postCreateTableCalled = true;
    }

    public boolean wasCreateTableCalled() {
      return preCreateTableCalled && postCreateTableCalled;
    }

    public boolean preCreateTableCalledOnly() {
      return preCreateTableCalled && !postCreateTableCalled;
    }

    @Override
    public void preDeleteTable(ObserverContext<MasterCoprocessorEnvironment> env,
        TableName tableName) throws IOException {
      if (bypass) {
        env.bypass();
      }
      preDeleteTableCalled = true;
    }

    @Override
    public void postDeleteTable(ObserverContext<MasterCoprocessorEnvironment> env,
        TableName tableName) throws IOException {
      postDeleteTableCalled = true;
    }

    public boolean wasDeleteTableCalled() {
      return preDeleteTableCalled && postDeleteTableCalled;
    }

    public boolean preDeleteTableCalledOnly() {
      return preDeleteTableCalled && !postDeleteTableCalled;
    }

    @Override
    public void preModifyTable(ObserverContext<MasterCoprocessorEnvironment> env,
        TableName tableName, HTableDescriptor htd) throws IOException {
      if (bypass) {
        env.bypass();
      }else{
        env.shouldBypass();
      }
      preModifyTableCalled = true;
    }

    @Override
    public void postModifyTable(ObserverContext<MasterCoprocessorEnvironment> env,
        TableName tableName, HTableDescriptor htd) throws IOException {
      postModifyTableCalled = true;
    }

    public boolean wasModifyTableCalled() {
      return preModifyTableCalled && postModifyTableCalled;
    }

    public boolean preModifyTableCalledOnly() {
      return preModifyTableCalled && !postModifyTableCalled;
    }

    @Override
    public void preCreateNamespace(ObserverContext<MasterCoprocessorEnvironment> env,
        NamespaceDescriptor ns) throws IOException {
      if (bypass) {
        env.bypass();
      }
      preCreateNamespaceCalled = true;
    }

    @Override
    public void postCreateNamespace(ObserverContext<MasterCoprocessorEnvironment> env,
        NamespaceDescriptor ns) throws IOException {
      postCreateNamespaceCalled = true;
    }

    public boolean wasCreateNamespaceCalled() {
      return preCreateNamespaceCalled && postCreateNamespaceCalled;
    }

    public boolean preCreateNamespaceCalledOnly() {
      return preCreateNamespaceCalled && !postCreateNamespaceCalled;
    }

    @Override
    public void preDeleteNamespace(ObserverContext<MasterCoprocessorEnvironment> env,
        String name) throws IOException {
      if (bypass) {
        env.bypass();
      }
      preDeleteNamespaceCalled = true;
    }

    @Override
    public void postDeleteNamespace(ObserverContext<MasterCoprocessorEnvironment> env,
        String name) throws IOException {
      postDeleteNamespaceCalled = true;
    }

    public boolean wasDeleteNamespaceCalled() {
      return preDeleteNamespaceCalled && postDeleteNamespaceCalled;
    }

    public boolean preDeleteNamespaceCalledOnly() {
      return preDeleteNamespaceCalled && !postDeleteNamespaceCalled;
    }

    @Override
    public void preModifyNamespace(ObserverContext<MasterCoprocessorEnvironment> env,
        NamespaceDescriptor ns) throws IOException {
      if (bypass) {
        env.bypass();
      }
      preModifyNamespaceCalled = true;
    }

    @Override
    public void postModifyNamespace(ObserverContext<MasterCoprocessorEnvironment> env,
        NamespaceDescriptor ns) throws IOException {
      postModifyNamespaceCalled = true;
    }

    public boolean wasModifyNamespaceCalled() {
      return preModifyNamespaceCalled && postModifyNamespaceCalled;
    }

    public boolean preModifyNamespaceCalledOnly() {
      return preModifyNamespaceCalled && !postModifyNamespaceCalled;
    }

    @Override
    public void preAddColumn(ObserverContext<MasterCoprocessorEnvironment> env,
        TableName tableName, HColumnDescriptor column) throws IOException {
      if (bypass) {
        env.bypass();
      }else{
        env.shouldBypass();
      }

      preAddColumnCalled = true;
    }

    @Override
    public void postAddColumn(ObserverContext<MasterCoprocessorEnvironment> env,
        TableName tableName, HColumnDescriptor column) throws IOException {
      postAddColumnCalled = true;
    }

    public boolean wasAddColumnCalled() {
      return preAddColumnCalled && postAddColumnCalled;
    }

    public boolean preAddColumnCalledOnly() {
      return preAddColumnCalled && !postAddColumnCalled;
    }

    @Override
    public void preModifyColumn(ObserverContext<MasterCoprocessorEnvironment> env,
        TableName tableName, HColumnDescriptor descriptor) throws IOException {
      if (bypass) {
        env.bypass();
      }
      preModifyColumnCalled = true;
    }

    @Override
    public void postModifyColumn(ObserverContext<MasterCoprocessorEnvironment> env,
        TableName tableName, HColumnDescriptor descriptor) throws IOException {
      postModifyColumnCalled = true;
    }

    public boolean wasModifyColumnCalled() {
      return preModifyColumnCalled && postModifyColumnCalled;
    }

    public boolean preModifyColumnCalledOnly() {
      return preModifyColumnCalled && !postModifyColumnCalled;
    }

    @Override
    public void preDeleteColumn(ObserverContext<MasterCoprocessorEnvironment> env,
        TableName tableName, byte[] c) throws IOException {
      if (bypass) {
        env.bypass();
      }
      preDeleteColumnCalled = true;
    }

    @Override
    public void postDeleteColumn(ObserverContext<MasterCoprocessorEnvironment> env,
        TableName tableName, byte[] c) throws IOException {
      postDeleteColumnCalled = true;
    }

    public boolean wasDeleteColumnCalled() {
      return preDeleteColumnCalled && postDeleteColumnCalled;
    }

    public boolean preDeleteColumnCalledOnly() {
      return preDeleteColumnCalled && !postDeleteColumnCalled;
    }

    @Override
    public void preEnableTable(ObserverContext<MasterCoprocessorEnvironment> env,
        TableName tableName) throws IOException {
      if (bypass) {
        env.bypass();
      }
      preEnableTableCalled = true;
    }

    @Override
    public void postEnableTable(ObserverContext<MasterCoprocessorEnvironment> env,
        TableName tableName) throws IOException {
      postEnableTableCalled = true;
    }

    public boolean wasEnableTableCalled() {
      return preEnableTableCalled && postEnableTableCalled;
    }

    public boolean preEnableTableCalledOnly() {
      return preEnableTableCalled && !postEnableTableCalled;
    }

    @Override
    public void preDisableTable(ObserverContext<MasterCoprocessorEnvironment> env,
        TableName tableName) throws IOException {
      if (bypass) {
        env.bypass();
      }
      preDisableTableCalled = true;
    }

    @Override
    public void postDisableTable(ObserverContext<MasterCoprocessorEnvironment> env,
        TableName tableName) throws IOException {
      postDisableTableCalled = true;
    }

    public boolean wasDisableTableCalled() {
      return preDisableTableCalled && postDisableTableCalled;
    }

    public boolean preDisableTableCalledOnly() {
      return preDisableTableCalled && !postDisableTableCalled;
    }

    @Override
    public void preMove(ObserverContext<MasterCoprocessorEnvironment> env,
        HRegionInfo region, ServerName srcServer, ServerName destServer)
    throws IOException {
      if (bypass) {
        env.bypass();
      }
      preMoveCalled = true;
    }

    @Override
    public void postMove(ObserverContext<MasterCoprocessorEnvironment> env, HRegionInfo region,
        ServerName srcServer, ServerName destServer)
    throws IOException {
      postMoveCalled = true;
    }

    public boolean wasMoveCalled() {
      return preMoveCalled && postMoveCalled;
    }

    public boolean preMoveCalledOnly() {
      return preMoveCalled && !postMoveCalled;
    }

    @Override
    public void preAssign(ObserverContext<MasterCoprocessorEnvironment> env,
        final HRegionInfo regionInfo) throws IOException {
      if (bypass) {
        env.bypass();
      }
      preAssignCalled = true;
    }

    @Override
    public void postAssign(ObserverContext<MasterCoprocessorEnvironment> env,
        final HRegionInfo regionInfo) throws IOException {
      postAssignCalled = true;
    }

    public boolean wasAssignCalled() {
      return preAssignCalled && postAssignCalled;
    }

    public boolean preAssignCalledOnly() {
      return preAssignCalled && !postAssignCalled;
    }

    @Override
    public void preUnassign(ObserverContext<MasterCoprocessorEnvironment> env,
        final HRegionInfo regionInfo, final boolean force) throws IOException {
      if (bypass) {
        env.bypass();
      }
      preUnassignCalled = true;
    }

    @Override
    public void postUnassign(ObserverContext<MasterCoprocessorEnvironment> env,
        final HRegionInfo regionInfo, final boolean force) throws IOException {
      postUnassignCalled = true;
    }

    public boolean wasUnassignCalled() {
      return preUnassignCalled && postUnassignCalled;
    }

    public boolean preUnassignCalledOnly() {
      return preUnassignCalled && !postUnassignCalled;
    }

    @Override
    public void preRegionOffline(ObserverContext<MasterCoprocessorEnvironment> env,
        final HRegionInfo regionInfo) throws IOException {
      preRegionOfflineCalled = true;
    }

    @Override
    public void postRegionOffline(ObserverContext<MasterCoprocessorEnvironment> env,
        final HRegionInfo regionInfo) throws IOException {
      postRegionOfflineCalled = true;
    }

    public boolean wasRegionOfflineCalled() {
      return preRegionOfflineCalled && postRegionOfflineCalled;
    }

    public boolean preRegionOfflineCalledOnly() {
      return preRegionOfflineCalled && !postRegionOfflineCalled;
    }

    @Override
    public void preBalance(ObserverContext<MasterCoprocessorEnvironment> env)
        throws IOException {
      if (bypass) {
        env.bypass();
      }
      preBalanceCalled = true;
    }

    @Override
    public void postBalance(ObserverContext<MasterCoprocessorEnvironment> env,
        List<RegionPlan> plans) throws IOException {
      postBalanceCalled = true;
    }

    public boolean wasBalanceCalled() {
      return preBalanceCalled && postBalanceCalled;
    }

    public boolean preBalanceCalledOnly() {
      return preBalanceCalled && !postBalanceCalled;
    }

    @Override
    public boolean preBalanceSwitch(ObserverContext<MasterCoprocessorEnvironment> env, boolean b)
        throws IOException {
      if (bypass) {
        env.bypass();
      }
      preBalanceSwitchCalled = true;
      return b;
    }

    @Override
    public void postBalanceSwitch(ObserverContext<MasterCoprocessorEnvironment> env,
        boolean oldValue, boolean newValue) throws IOException {
      postBalanceSwitchCalled = true;
    }

    public boolean wasBalanceSwitchCalled() {
      return preBalanceSwitchCalled && postBalanceSwitchCalled;
    }

    public boolean preBalanceSwitchCalledOnly() {
      return preBalanceSwitchCalled && !postBalanceSwitchCalled;
    }

    @Override
    public void preShutdown(ObserverContext<MasterCoprocessorEnvironment> env)
        throws IOException {
      preShutdownCalled = true;
    }

    @Override
    public void preStopMaster(ObserverContext<MasterCoprocessorEnvironment> env)
        throws IOException {
      preStopMasterCalled = true;
    }

    @Override
    public void preMasterInitialization(
        ObserverContext<MasterCoprocessorEnvironment> ctx) throws IOException {
      preMasterInitializationCalled = true;
    }
    
    public boolean wasMasterInitializationCalled(){
      return preMasterInitializationCalled;
    }
    
    @Override
    public void postStartMaster(ObserverContext<MasterCoprocessorEnvironment> ctx)
        throws IOException {
      postStartMasterCalled = true;
    }

    public boolean wasStartMasterCalled() {
      return postStartMasterCalled;
    }

    @Override
    public void start(CoprocessorEnvironment env) throws IOException {
      startCalled = true;
    }

    @Override
    public void stop(CoprocessorEnvironment env) throws IOException {
      stopCalled = true;
    }

    public boolean wasStarted() { return startCalled; }

    public boolean wasStopped() { return stopCalled; }

    @Override
    public void preSnapshot(final ObserverContext<MasterCoprocessorEnvironment> ctx,
        final SnapshotDescription snapshot, final HTableDescriptor hTableDescriptor)
        throws IOException {
      preSnapshotCalled = true;
    }

    @Override
    public void postSnapshot(final ObserverContext<MasterCoprocessorEnvironment> ctx,
        final SnapshotDescription snapshot, final HTableDescriptor hTableDescriptor)
        throws IOException {
      postSnapshotCalled = true;
    }

    public boolean wasSnapshotCalled() {
      return preSnapshotCalled && postSnapshotCalled;
    }

    @Override
    public void preCloneSnapshot(final ObserverContext<MasterCoprocessorEnvironment> ctx,
        final SnapshotDescription snapshot, final HTableDescriptor hTableDescriptor)
        throws IOException {
      preCloneSnapshotCalled = true;
    }

    @Override
    public void postCloneSnapshot(final ObserverContext<MasterCoprocessorEnvironment> ctx,
        final SnapshotDescription snapshot, final HTableDescriptor hTableDescriptor)
        throws IOException {
      postCloneSnapshotCalled = true;
    }

    public boolean wasCloneSnapshotCalled() {
      return preCloneSnapshotCalled && postCloneSnapshotCalled;
    }

    @Override
    public void preRestoreSnapshot(final ObserverContext<MasterCoprocessorEnvironment> ctx,
        final SnapshotDescription snapshot, final HTableDescriptor hTableDescriptor)
        throws IOException {
      preRestoreSnapshotCalled = true;
    }

    @Override
    public void postRestoreSnapshot(final ObserverContext<MasterCoprocessorEnvironment> ctx,
        final SnapshotDescription snapshot, final HTableDescriptor hTableDescriptor)
        throws IOException {
      postRestoreSnapshotCalled = true;
    }

    public boolean wasRestoreSnapshotCalled() {
      return preRestoreSnapshotCalled && postRestoreSnapshotCalled;
    }

    @Override
    public void preDeleteSnapshot(final ObserverContext<MasterCoprocessorEnvironment> ctx,
        final SnapshotDescription snapshot) throws IOException {
      preDeleteSnapshotCalled = true;
    }

    @Override
    public void postDeleteSnapshot(final ObserverContext<MasterCoprocessorEnvironment> ctx,
        final SnapshotDescription snapshot) throws IOException {
      postDeleteSnapshotCalled = true;
    }

    public boolean wasDeleteSnapshotCalled() {
      return preDeleteSnapshotCalled && postDeleteSnapshotCalled;
    }

    @Override
    public void preCreateTableHandler(
        ObserverContext<MasterCoprocessorEnvironment> env,
        HTableDescriptor desc, HRegionInfo[] regions) throws IOException {
      if (bypass) {
        env.bypass();
      }
      preCreateTableHandlerCalled = true;
    }

    @Override
    public void postCreateTableHandler(
        ObserverContext<MasterCoprocessorEnvironment> ctx,
        HTableDescriptor desc, HRegionInfo[] regions) throws IOException {
      postCreateTableHandlerCalled = true;
      tableCreationLatch.countDown();
    }

    public boolean wasPreCreateTableHandlerCalled(){
      return preCreateTableHandlerCalled;
    }
    public boolean wasCreateTableHandlerCalled() {
      return preCreateTableHandlerCalled && postCreateTableHandlerCalled;
    }

    public boolean wasCreateTableHandlerCalledOnly() {
      return preCreateTableHandlerCalled && !postCreateTableHandlerCalled;
    }

    @Override
    public void preDeleteTableHandler(
        ObserverContext<MasterCoprocessorEnvironment> env, TableName tableName)
        throws IOException {
      if (bypass) {
        env.bypass();
      }
      preDeleteTableHandlerCalled = true;
    }

    @Override
    public void postDeleteTableHandler(
        ObserverContext<MasterCoprocessorEnvironment> ctx, TableName tableName)
        throws IOException {
      postDeleteTableHandlerCalled = true;
    }

    public boolean wasDeleteTableHandlerCalled() {
      return preDeleteTableHandlerCalled && postDeleteTableHandlerCalled;
    }

    public boolean wasDeleteTableHandlerCalledOnly() {
      return preDeleteTableHandlerCalled && !postDeleteTableHandlerCalled;
    }
    @Override
    public void preModifyTableHandler(
        ObserverContext<MasterCoprocessorEnvironment> env, TableName tableName,
        HTableDescriptor htd) throws IOException {
      if (bypass) {
        env.bypass();
      }
      preModifyTableHandlerCalled = true;
    }

    @Override
    public void postModifyTableHandler(
        ObserverContext<MasterCoprocessorEnvironment> env, TableName tableName,
        HTableDescriptor htd) throws IOException {
      postModifyTableHandlerCalled = true;
    }

    public boolean wasModifyTableHandlerCalled() {
      return preModifyColumnHandlerCalled && postModifyColumnHandlerCalled;
    }

    public boolean wasModifyTableHandlerCalledOnly() {
      return preModifyColumnHandlerCalled && !postModifyColumnHandlerCalled;
    }

    @Override
    public void preAddColumnHandler(
        ObserverContext<MasterCoprocessorEnvironment> env, TableName tableName,
        HColumnDescriptor column) throws IOException {
      if (bypass) {
        env.bypass();
      }
      preAddColumnHandlerCalled = true;
    }

    @Override
    public void postAddColumnHandler(
        ObserverContext<MasterCoprocessorEnvironment> ctx, TableName tableName,
        HColumnDescriptor column) throws IOException {
      postAddColumnHandlerCalled = true;
    }
    public boolean wasAddColumnHandlerCalled() {
      return preAddColumnHandlerCalled && postAddColumnHandlerCalled;
    }

    public boolean preAddColumnHandlerCalledOnly() {
      return preAddColumnHandlerCalled && !postAddColumnHandlerCalled;
    }

    @Override
    public void preModifyColumnHandler(
        ObserverContext<MasterCoprocessorEnvironment> env, TableName tableName,
        HColumnDescriptor descriptor) throws IOException {
      if (bypass) {
        env.bypass();
      }
      preModifyColumnHandlerCalled = true;
    }

    @Override
    public void postModifyColumnHandler(
        ObserverContext<MasterCoprocessorEnvironment> ctx, TableName tableName,
        HColumnDescriptor descriptor) throws IOException {
      postModifyColumnHandlerCalled = true;
    }

    public boolean wasModifyColumnHandlerCalled() {
      return preModifyColumnHandlerCalled && postModifyColumnHandlerCalled;
    }

    public boolean preModifyColumnHandlerCalledOnly() {
      return preModifyColumnHandlerCalled && !postModifyColumnHandlerCalled;
    }
    @Override
    public void preDeleteColumnHandler(
        ObserverContext<MasterCoprocessorEnvironment> env, TableName tableName,
        byte[] c) throws IOException {
      if (bypass) {
        env.bypass();
      }
      preDeleteColumnHandlerCalled = true;
    }

    @Override
    public void postDeleteColumnHandler(
        ObserverContext<MasterCoprocessorEnvironment> ctx, TableName tableName,
        byte[] c) throws IOException {
      postDeleteColumnHandlerCalled = true;
    }

    public boolean wasDeleteColumnHandlerCalled() {
      return preDeleteColumnHandlerCalled && postDeleteColumnHandlerCalled;
    }

    public boolean preDeleteColumnHandlerCalledOnly() {
      return preDeleteColumnHandlerCalled && !postDeleteColumnHandlerCalled;
    }

    @Override
    public void preEnableTableHandler(
        ObserverContext<MasterCoprocessorEnvironment> env, TableName tableName)
        throws IOException {
      if (bypass) {
        env.bypass();
      }
      preEnableTableHandlerCalled = true;
    }

    @Override
    public void postEnableTableHandler(
        ObserverContext<MasterCoprocessorEnvironment> ctx, TableName tableName)
        throws IOException {
      postEnableTableHandlerCalled = true;
    }

    public boolean wasEnableTableHandlerCalled() {
      return preEnableTableHandlerCalled && postEnableTableHandlerCalled;
    }

    public boolean preEnableTableHandlerCalledOnly() {
      return preEnableTableHandlerCalled && !postEnableTableHandlerCalled;
    }

    @Override
    public void preDisableTableHandler(
        ObserverContext<MasterCoprocessorEnvironment> env, TableName tableName)
        throws IOException {
      if (bypass) {
        env.bypass();
      }
      preDisableTableHandlerCalled = true;
    }

    @Override
    public void postDisableTableHandler(
        ObserverContext<MasterCoprocessorEnvironment> ctx, TableName tableName)
        throws IOException {
      postDisableTableHandlerCalled = true;
    }

    public boolean wasDisableTableHandlerCalled() {
      return preDisableTableHandlerCalled && postDisableTableHandlerCalled;
    }

    public boolean preDisableTableHandlerCalledOnly() {
      return preDisableTableHandlerCalled && !postDisableTableHandlerCalled;
    }

    @Override
    public void preGetTableDescriptors(ObserverContext<MasterCoprocessorEnvironment> ctx,
        List<TableName> tableNamesList, List<HTableDescriptor> descriptors)
        throws IOException {
      preGetTableDescriptorsCalled = true;
    }

    @Override
    public void postGetTableDescriptors(ObserverContext<MasterCoprocessorEnvironment> ctx,
        List<HTableDescriptor> descriptors) throws IOException {
      postGetTableDescriptorsCalled = true;
    }

    public boolean wasGetTableDescriptorsCalled() {
      return preGetTableDescriptorsCalled && postGetTableDescriptorsCalled;
    }
  }

  private static HBaseTestingUtility UTIL = new HBaseTestingUtility();
  private static byte[] TEST_SNAPSHOT = Bytes.toBytes("observed_snapshot");
  private static TableName TEST_TABLE =
      TableName.valueOf("observed_table");
  private static byte[] TEST_CLONE = Bytes.toBytes("observed_clone");
  private static byte[] TEST_FAMILY = Bytes.toBytes("fam1");
  private static byte[] TEST_FAMILY2 = Bytes.toBytes("fam2");
  private static byte[] TEST_FAMILY3 = Bytes.toBytes("fam3");

  @BeforeClass
  public static void setupBeforeClass() throws Exception {
    Configuration conf = UTIL.getConfiguration();
    conf.set(CoprocessorHost.MASTER_COPROCESSOR_CONF_KEY,
        CPMasterObserver.class.getName());
    conf.set("hbase.master.hfilecleaner.plugins",
      "org.apache.hadoop.hbase.master.cleaner.HFileLinkCleaner," +
      "org.apache.hadoop.hbase.master.snapshot.SnapshotHFileCleaner");
    conf.set("hbase.master.logcleaner.plugins",
      "org.apache.hadoop.hbase.master.snapshot.SnapshotLogCleaner");
    // We need more than one data server on this test
    UTIL.startMiniCluster(2);
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    UTIL.shutdownMiniCluster();
  }

  @Test
  public void testStarted() throws Exception {
    MiniHBaseCluster cluster = UTIL.getHBaseCluster();

    HMaster master = cluster.getMaster();
    assertTrue("Master should be active", master.isActiveMaster());
    MasterCoprocessorHost host = master.getCoprocessorHost();
    assertNotNull("CoprocessorHost should not be null", host);
    CPMasterObserver cp = (CPMasterObserver)host.findCoprocessor(
        CPMasterObserver.class.getName());
    assertNotNull("CPMasterObserver coprocessor not found or not installed!", cp);

    // check basic lifecycle
    assertTrue("MasterObserver should have been started", cp.wasStarted());
    assertTrue("preMasterInitialization() hook should have been called",
        cp.wasMasterInitializationCalled());
    assertTrue("postStartMaster() hook should have been called",
        cp.wasStartMasterCalled());
  }

  @Test
  public void testTableOperations() throws Exception {
    MiniHBaseCluster cluster = UTIL.getHBaseCluster();

    HMaster master = cluster.getMaster();
    MasterCoprocessorHost host = master.getCoprocessorHost();
    CPMasterObserver cp = (CPMasterObserver)host.findCoprocessor(
        CPMasterObserver.class.getName());
    cp.enableBypass(true);
    cp.resetStates();
    assertFalse("No table created yet", cp.wasCreateTableCalled());

    // create a table
    HTableDescriptor htd = new HTableDescriptor(TEST_TABLE);
    htd.addFamily(new HColumnDescriptor(TEST_FAMILY));
    HBaseAdmin admin = UTIL.getHBaseAdmin();

    tableCreationLatch = new CountDownLatch(1);
    admin.createTable(htd);
    // preCreateTable can't bypass default action.
    assertTrue("Test table should be created", cp.wasCreateTableCalled());
    tableCreationLatch.await();
    assertTrue("Table pre create handler called.", cp
        .wasPreCreateTableHandlerCalled());
    assertTrue("Table create handler should be called.",
        cp.wasCreateTableHandlerCalled());

    tableCreationLatch = new CountDownLatch(1);
    admin.disableTable(TEST_TABLE);
    assertTrue(admin.isTableDisabled(TEST_TABLE));
    // preDisableTable can't bypass default action.
    assertTrue("Coprocessor should have been called on table disable",
      cp.wasDisableTableCalled());
    assertTrue("Disable table handler should be called.",
        cp.wasDisableTableHandlerCalled());

    // enable
    assertFalse(cp.wasEnableTableCalled());
    admin.enableTable(TEST_TABLE);
    assertTrue(admin.isTableEnabled(TEST_TABLE));
    // preEnableTable can't bypass default action.
    assertTrue("Coprocessor should have been called on table enable",
      cp.wasEnableTableCalled());
    assertTrue("Enable table handler should be called.",
        cp.wasEnableTableHandlerCalled());

    admin.disableTable(TEST_TABLE);
    assertTrue(admin.isTableDisabled(TEST_TABLE));

    // modify table
    htd.setMaxFileSize(512 * 1024 * 1024);
    modifyTableSync(admin, TEST_TABLE, htd);
    // preModifyTable can't bypass default action.
    assertTrue("Test table should have been modified",
      cp.wasModifyTableCalled());

    // add a column family
    admin.addColumn(TEST_TABLE, new HColumnDescriptor(TEST_FAMILY2));
    assertTrue("New column family shouldn't have been added to test table",
      cp.preAddColumnCalledOnly());

    // modify a column family
    HColumnDescriptor hcd1 = new HColumnDescriptor(TEST_FAMILY2);
    hcd1.setMaxVersions(25);
    admin.modifyColumn(TEST_TABLE, hcd1);
    assertTrue("Second column family should be modified",
      cp.preModifyColumnCalledOnly());

    // delete table
    admin.deleteTable(TEST_TABLE);
    assertFalse("Test table should have been deleted",
        admin.tableExists(TEST_TABLE));
    // preDeleteTable can't bypass default action.
    assertTrue("Coprocessor should have been called on table delete",
        cp.wasDeleteTableCalled());
    assertTrue("Delete table handler should be called.",
        cp.wasDeleteTableHandlerCalled());

    // turn off bypass, run the tests again
    cp.enableBypass(false);
    cp.resetStates();

    admin.createTable(htd);
    assertTrue("Test table should be created", cp.wasCreateTableCalled());
    tableCreationLatch.await();
    assertTrue("Table pre create handler called.", cp
        .wasPreCreateTableHandlerCalled());
    assertTrue("Table create handler should be called.",
        cp.wasCreateTableHandlerCalled());

    // disable
    assertFalse(cp.wasDisableTableCalled());
    assertFalse(cp.wasDisableTableHandlerCalled());
    admin.disableTable(TEST_TABLE);
    assertTrue(admin.isTableDisabled(TEST_TABLE));
    assertTrue("Coprocessor should have been called on table disable",
      cp.wasDisableTableCalled());
    assertTrue("Disable table handler should be called.",
        cp.wasDisableTableHandlerCalled());

    // modify table
    htd.setMaxFileSize(512 * 1024 * 1024);
    modifyTableSync(admin, TEST_TABLE, htd);
    assertTrue("Test table should have been modified",
        cp.wasModifyTableCalled());
    // add a column family
    admin.addColumn(TEST_TABLE, new HColumnDescriptor(TEST_FAMILY2));
    assertTrue("New column family should have been added to test table",
        cp.wasAddColumnCalled());
    assertTrue("Add column handler should be called.",
        cp.wasAddColumnHandlerCalled());

    // modify a column family
    HColumnDescriptor hcd = new HColumnDescriptor(TEST_FAMILY2);
    hcd.setMaxVersions(25);
    admin.modifyColumn(TEST_TABLE, hcd);
    assertTrue("Second column family should be modified",
        cp.wasModifyColumnCalled());
    assertTrue("Modify table handler should be called.",
        cp.wasModifyColumnHandlerCalled());

    // enable
    assertFalse(cp.wasEnableTableCalled());
    assertFalse(cp.wasEnableTableHandlerCalled());
    admin.enableTable(TEST_TABLE);
    assertTrue(admin.isTableEnabled(TEST_TABLE));
    assertTrue("Coprocessor should have been called on table enable",
        cp.wasEnableTableCalled());
    assertTrue("Enable table handler should be called.",
        cp.wasEnableTableHandlerCalled());

    // disable again
    admin.disableTable(TEST_TABLE);
    assertTrue(admin.isTableDisabled(TEST_TABLE));

    // delete column
    assertFalse("No column family deleted yet", cp.wasDeleteColumnCalled());
    assertFalse("Delete table column handler should not be called.",
        cp.wasDeleteColumnHandlerCalled());
    admin.deleteColumn(TEST_TABLE, TEST_FAMILY2);
    HTableDescriptor tableDesc = admin.getTableDescriptor(TEST_TABLE);
    assertNull("'"+Bytes.toString(TEST_FAMILY2)+"' should have been removed",
        tableDesc.getFamily(TEST_FAMILY2));
    assertTrue("Coprocessor should have been called on column delete",
        cp.wasDeleteColumnCalled());
    assertTrue("Delete table column handler should be called.",
        cp.wasDeleteColumnHandlerCalled());

    // delete table
    assertFalse("No table deleted yet", cp.wasDeleteTableCalled());
    assertFalse("Delete table handler should not be called.",
        cp.wasDeleteTableHandlerCalled());
    admin.deleteTable(TEST_TABLE);
    assertFalse("Test table should have been deleted",
        admin.tableExists(TEST_TABLE));
    assertTrue("Coprocessor should have been called on table delete",
        cp.wasDeleteTableCalled());
    assertTrue("Delete table handler should be called.",
        cp.wasDeleteTableHandlerCalled());
  }

  @Test
  public void testSnapshotOperations() throws Exception {
    MiniHBaseCluster cluster = UTIL.getHBaseCluster();
    HMaster master = cluster.getMaster();
    MasterCoprocessorHost host = master.getCoprocessorHost();
    CPMasterObserver cp = (CPMasterObserver)host.findCoprocessor(
        CPMasterObserver.class.getName());
    cp.resetStates();

    // create a table
    HTableDescriptor htd = new HTableDescriptor(TEST_TABLE);
    htd.addFamily(new HColumnDescriptor(TEST_FAMILY));
    HBaseAdmin admin = UTIL.getHBaseAdmin();

    tableCreationLatch = new CountDownLatch(1);
    admin.createTable(htd);
    tableCreationLatch.await();
    tableCreationLatch = new CountDownLatch(1);

    admin.disableTable(TEST_TABLE);
    assertTrue(admin.isTableDisabled(TEST_TABLE));

    try {
      // Test snapshot operation
      assertFalse("Coprocessor should not have been called yet",
        cp.wasSnapshotCalled());
      admin.snapshot(TEST_SNAPSHOT, TEST_TABLE);
      assertTrue("Coprocessor should have been called on snapshot",
        cp.wasSnapshotCalled());

      // Test clone operation
      admin.cloneSnapshot(TEST_SNAPSHOT, TEST_CLONE);
      assertTrue("Coprocessor should have been called on snapshot clone",
        cp.wasCloneSnapshotCalled());
      assertFalse("Coprocessor restore should not have been called on snapshot clone",
        cp.wasRestoreSnapshotCalled());
      admin.disableTable(TEST_CLONE);
      assertTrue(admin.isTableDisabled(TEST_TABLE));
      admin.deleteTable(TEST_CLONE);

      // Test restore operation
      cp.resetStates();
      admin.restoreSnapshot(TEST_SNAPSHOT);
      assertTrue("Coprocessor should have been called on snapshot restore",
        cp.wasRestoreSnapshotCalled());
      assertFalse("Coprocessor clone should not have been called on snapshot restore",
        cp.wasCloneSnapshotCalled());

      admin.deleteSnapshot(TEST_SNAPSHOT);
      assertTrue("Coprocessor should have been called on snapshot delete",
        cp.wasDeleteSnapshotCalled());
    } finally {
      admin.deleteTable(TEST_TABLE);
    }
  }

  @Test
  public void testNamespaceOperations() throws Exception {
    MiniHBaseCluster cluster = UTIL.getHBaseCluster();
    String testNamespace = "observed_ns";
    HMaster master = cluster.getMaster();
    MasterCoprocessorHost host = master.getCoprocessorHost();
    CPMasterObserver cp = (CPMasterObserver)host.findCoprocessor(
        CPMasterObserver.class.getName());

    cp.enableBypass(false);
    cp.resetStates();


    // create a table
    HBaseAdmin admin = UTIL.getHBaseAdmin();
    admin.createNamespace(NamespaceDescriptor.create(testNamespace).build());
    assertTrue("Test namespace should be created", cp.wasCreateNamespaceCalled());

    assertNotNull(admin.getNamespaceDescriptor(testNamespace));

    // turn off bypass, run the tests again
    cp.enableBypass(true);
    cp.resetStates();

    admin.modifyNamespace(NamespaceDescriptor.create(testNamespace).build());
    assertTrue("Test namespace should not have been modified",
        cp.preModifyNamespaceCalledOnly());

    assertNotNull(admin.getNamespaceDescriptor(testNamespace));

    admin.deleteNamespace(testNamespace);
    assertTrue("Test namespace should not have been deleted", cp.preDeleteNamespaceCalledOnly());

    assertNotNull(admin.getNamespaceDescriptor(testNamespace));

    cp.enableBypass(false);
    cp.resetStates();

    // delete table
    admin.modifyNamespace(NamespaceDescriptor.create(testNamespace).build());
    assertTrue("Test namespace should have been modified", cp.wasModifyNamespaceCalled());

    admin.deleteNamespace(testNamespace);
    assertTrue("Test namespace should have been deleted", cp.wasDeleteNamespaceCalled());

    cp.enableBypass(true);
    cp.resetStates();

    admin.createNamespace(NamespaceDescriptor.create(testNamespace).build());
    assertTrue("Test namespace should not be created", cp.preCreateNamespaceCalledOnly());
  }

  private void modifyTableSync(HBaseAdmin admin, TableName tableName, HTableDescriptor htd)
      throws IOException {
    admin.modifyTable(tableName, htd);
    //wait until modify table finishes
    for (int t = 0; t < 100; t++) { //10 sec timeout
      HTableDescriptor td = admin.getTableDescriptor(htd.getTableName());
      if (td.equals(htd)) {
        break;
      }
      Threads.sleep(100);
    }
  }

  @Test
  public void testRegionTransitionOperations() throws Exception {
    MiniHBaseCluster cluster = UTIL.getHBaseCluster();

    HMaster master = cluster.getMaster();
    MasterCoprocessorHost host = master.getCoprocessorHost();
    CPMasterObserver cp = (CPMasterObserver)host.findCoprocessor(
        CPMasterObserver.class.getName());
    cp.enableBypass(false);
    cp.resetStates();

    HTable table = UTIL.createTable(TEST_TABLE, TEST_FAMILY);

    try {
      UTIL.createMultiRegions(table, TEST_FAMILY);
      UTIL.waitUntilAllRegionsAssigned(TEST_TABLE);
  
      NavigableMap<HRegionInfo, ServerName> regions = table.getRegionLocations();
      Map.Entry<HRegionInfo, ServerName> firstGoodPair = null;
      for (Map.Entry<HRegionInfo, ServerName> e: regions.entrySet()) {
        if (e.getValue() != null) {
          firstGoodPair = e;
          break;
        }
      }
      assertNotNull("Found a non-null entry", firstGoodPair);
      LOG.info("Found " + firstGoodPair.toString());
      // Try to force a move
      Collection<ServerName> servers = master.getClusterStatus().getServers();
      String destName = null;
      String serverNameForFirstRegion = firstGoodPair.getValue().toString();
      LOG.info("serverNameForFirstRegion=" + serverNameForFirstRegion);
      boolean found = false;
      // Find server that is NOT carrying the first region
      for (ServerName info : servers) {
        LOG.info("ServerName=" + info);
        if (!serverNameForFirstRegion.equals(info.getServerName())) {
          destName = info.toString();
          found = true;
          break;
        }
      }
      assertTrue("Found server", found);
      LOG.info("Found " + destName);
      master.moveRegion(null,RequestConverter.buildMoveRegionRequest(
        firstGoodPair.getKey().getEncodedNameAsBytes(),Bytes.toBytes(destName)));
      assertTrue("Coprocessor should have been called on region move",
        cp.wasMoveCalled());
  
      // make sure balancer is on
      master.balanceSwitch(true);
      assertTrue("Coprocessor should have been called on balance switch",
          cp.wasBalanceSwitchCalled());
  
      // turn balancer off
      master.balanceSwitch(false);
  
      // wait for assignments to finish, if any
      AssignmentManager mgr = master.getAssignmentManager();
      Collection<RegionState> transRegions =
        mgr.getRegionStates().getRegionsInTransition().values();
      for (RegionState state : transRegions) {
        mgr.getRegionStates().waitOnRegionToClearRegionsInTransition(state.getRegion());
      }
  
      // move half the open regions from RS 0 to RS 1
      HRegionServer rs = cluster.getRegionServer(0);
      byte[] destRS = Bytes.toBytes(cluster.getRegionServer(1).getServerName().toString());
      //Make sure no regions are in transition now
      waitForRITtoBeZero(master);
      List<HRegionInfo> openRegions = ProtobufUtil.getOnlineRegions(rs);
      int moveCnt = openRegions.size()/2;
      for (int i=0; i<moveCnt; i++) {
        HRegionInfo info = openRegions.get(i);
        if (!info.isMetaTable()) {
          master.moveRegion(null,RequestConverter.buildMoveRegionRequest(
            openRegions.get(i).getEncodedNameAsBytes(), destRS));
        }
      }
      //Make sure no regions are in transition now
      waitForRITtoBeZero(master);
      // now trigger a balance
      master.balanceSwitch(true);
      boolean balanceRun = master.balance();
      assertTrue("Coprocessor should be called on region rebalancing",
          cp.wasBalanceCalled());
    } finally {
      UTIL.deleteTable(TEST_TABLE);
    }
  }

  private void waitForRITtoBeZero(HMaster master) throws Exception {
    // wait for assignments to finish
    AssignmentManager mgr = master.getAssignmentManager();
    Collection<RegionState> transRegions =
      mgr.getRegionStates().getRegionsInTransition().values();
    for (RegionState state : transRegions) {
      mgr.getRegionStates().waitOnRegionToClearRegionsInTransition(state.getRegion());
    }
  }

  @Test
  public void testTableDescriptorsEnumeration() throws Exception {
    MiniHBaseCluster cluster = UTIL.getHBaseCluster();

    HMaster master = cluster.getMaster();
    MasterCoprocessorHost host = master.getCoprocessorHost();
    CPMasterObserver cp = (CPMasterObserver)host.findCoprocessor(
        CPMasterObserver.class.getName());
    cp.resetStates();

    GetTableDescriptorsRequest req =
        RequestConverter.buildGetTableDescriptorsRequest((List<TableName>)null);
    master.getTableDescriptors(null, req);

    assertTrue("Coprocessor should be called on table descriptors request",
      cp.wasGetTableDescriptorsCalled());
  }

}
