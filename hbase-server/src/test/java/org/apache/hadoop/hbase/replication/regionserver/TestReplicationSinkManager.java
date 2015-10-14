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
package org.apache.hadoop.hbase.replication.regionserver;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.SmallTests;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.AdminService;
import org.apache.hadoop.hbase.replication.ReplicationPeers;
import org.apache.hadoop.hbase.replication.regionserver.ReplicationSinkManager.SinkPeer;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import com.google.common.collect.Lists;

@Category(SmallTests.class)
public class TestReplicationSinkManager {

  private static final String PEER_CLUSTER_ID = "PEER_CLUSTER_ID";

  private ReplicationPeers replicationPeers;
  private ReplicationSinkManager sinkManager;

  @Before
  public void setUp() {
    replicationPeers = mock(ReplicationPeers.class);
    sinkManager = new ReplicationSinkManager(mock(HConnection.class),
                      PEER_CLUSTER_ID, replicationPeers, new Configuration());
  }

  @Test
  public void testChooseSinks() {
    List<ServerName> serverNames = Lists.newArrayList();
    for (int i = 0; i < 20; i++) {
      serverNames.add(mock(ServerName.class));
    }

    when(replicationPeers.getRegionServersOfConnectedPeer(PEER_CLUSTER_ID))
          .thenReturn(serverNames);

    sinkManager.chooseSinks();

    assertEquals(2, sinkManager.getSinks().size());

  }

  @Test
  public void testChooseSinks_LessThanRatioAvailable() {
    List<ServerName> serverNames = Lists.newArrayList(mock(ServerName.class),
      mock(ServerName.class));

    when(replicationPeers.getRegionServersOfConnectedPeer(PEER_CLUSTER_ID))
          .thenReturn(serverNames);

    sinkManager.chooseSinks();

    assertEquals(1, sinkManager.getSinks().size());
  }

  @Test
  public void testReportBadSink() {
    ServerName serverNameA = mock(ServerName.class);
    ServerName serverNameB = mock(ServerName.class);
    when(replicationPeers.getRegionServersOfConnectedPeer(PEER_CLUSTER_ID)).thenReturn(
      Lists.newArrayList(serverNameA, serverNameB));

    sinkManager.chooseSinks();
    // Sanity check
    assertEquals(1, sinkManager.getSinks().size());

    SinkPeer sinkPeer = new SinkPeer(serverNameA, mock(AdminService.BlockingInterface.class));

    sinkManager.reportBadSink(sinkPeer);

    // Just reporting a bad sink once shouldn't have an effect
    assertEquals(1, sinkManager.getSinks().size());

  }

  /**
   * Once a SinkPeer has been reported as bad more than BAD_SINK_THRESHOLD times, it should not
   * be replicated to anymore.
   */
  @Test
  public void testReportBadSink_PastThreshold() {
    List<ServerName> serverNames = Lists.newArrayList();
    for (int i = 0; i < 20; i++) {
      serverNames.add(mock(ServerName.class));
    }
    when(replicationPeers.getRegionServersOfConnectedPeer(PEER_CLUSTER_ID))
          .thenReturn(serverNames);


    sinkManager.chooseSinks();
    // Sanity check
    assertEquals(2, sinkManager.getSinks().size());

    ServerName serverName = sinkManager.getSinks().get(0);

    SinkPeer sinkPeer = new SinkPeer(serverName, mock(AdminService.BlockingInterface.class));

    for (int i = 0; i <= ReplicationSinkManager.DEFAULT_BAD_SINK_THRESHOLD; i++) {
      sinkManager.reportBadSink(sinkPeer);
    }

    // Reporting a bad sink more than the threshold count should remove it
    // from the list of potential sinks
    assertEquals(1, sinkManager.getSinks().size());
  }

  @Test
  public void testReportBadSink_DownToZeroSinks() {
    List<ServerName> serverNames = Lists.newArrayList();
    for (int i = 0; i < 20; i++) {
      serverNames.add(mock(ServerName.class));
    }
    when(replicationPeers.getRegionServersOfConnectedPeer(PEER_CLUSTER_ID))
          .thenReturn(serverNames);


    sinkManager.chooseSinks();
    // Sanity check

    List<ServerName> sinkList = sinkManager.getSinks();
    assertEquals(2, sinkList.size());

    ServerName serverNameA = sinkList.get(0);
    ServerName serverNameB = sinkList.get(1);

    SinkPeer sinkPeerA = new SinkPeer(serverNameA, mock(AdminService.BlockingInterface.class));
    SinkPeer sinkPeerB = new SinkPeer(serverNameB, mock(AdminService.BlockingInterface.class));

    for (int i = 0; i <= ReplicationSinkManager.DEFAULT_BAD_SINK_THRESHOLD; i++) {
      sinkManager.reportBadSink(sinkPeerA);
      sinkManager.reportBadSink(sinkPeerB);
    }

    // We've gone down to 0 good sinks, so the replication sinks
    // should have been refreshed now
    assertEquals(2, sinkManager.getSinks().size());
  }

}
