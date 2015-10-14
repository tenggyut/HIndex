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
package org.apache.hadoop.hbase.master.balancer;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.master.RegionPlan;
import org.apache.hadoop.hbase.util.Bytes;

/**
 * Class used to be the base of unit tests on load balancers. It gives helper
 * methods to create maps of {@link ServerName} to lists of {@link HRegionInfo}
 * and to check list of region plans.
 *
 */
public class BalancerTestBase {

  protected static Random rand = new Random();
  static int regionId = 0;

  /**
   * Invariant is that all servers have between floor(avg) and ceiling(avg)
   * number of regions.
   */
  public void assertClusterAsBalanced(List<ServerAndLoad> servers) {
    int numServers = servers.size();
    int numRegions = 0;
    int maxRegions = 0;
    int minRegions = Integer.MAX_VALUE;
    for (ServerAndLoad server : servers) {
      int nr = server.getLoad();
      if (nr > maxRegions) {
        maxRegions = nr;
      }
      if (nr < minRegions) {
        minRegions = nr;
      }
      numRegions += nr;
    }
    if (maxRegions - minRegions < 2) {
      // less than 2 between max and min, can't balance
      return;
    }
    int min = numRegions / numServers;
    int max = numRegions % numServers == 0 ? min : min + 1;

    for (ServerAndLoad server : servers) {
      assertTrue(server.getLoad() >= 0);
      assertTrue(server.getLoad() <= max);
      assertTrue(server.getLoad() >= min);
    }
  }

  protected String printStats(List<ServerAndLoad> servers) {
    int numServers = servers.size();
    int totalRegions = 0;
    for (ServerAndLoad server : servers) {
      totalRegions += server.getLoad();
    }
    float average = (float) totalRegions / numServers;
    int max = (int) Math.ceil(average);
    int min = (int) Math.floor(average);
    return "[srvr=" + numServers + " rgns=" + totalRegions + " avg=" + average + " max=" + max
        + " min=" + min + "]";
  }

  protected List<ServerAndLoad> convertToList(final Map<ServerName, List<HRegionInfo>> servers) {
    List<ServerAndLoad> list = new ArrayList<ServerAndLoad>(servers.size());
    for (Map.Entry<ServerName, List<HRegionInfo>> e : servers.entrySet()) {
      list.add(new ServerAndLoad(e.getKey(), e.getValue().size()));
    }
    return list;
  }

  protected String printMock(List<ServerAndLoad> balancedCluster) {
    SortedSet<ServerAndLoad> sorted = new TreeSet<ServerAndLoad>(balancedCluster);
    ServerAndLoad[] arr = sorted.toArray(new ServerAndLoad[sorted.size()]);
    StringBuilder sb = new StringBuilder(sorted.size() * 4 + 4);
    sb.append("{ ");
    for (int i = 0; i < arr.length; i++) {
      if (i != 0) {
        sb.append(" , ");
      }
      sb.append(arr[i].getServerName().getHostname());
      sb.append(":");
      sb.append(arr[i].getLoad());
    }
    sb.append(" }");
    return sb.toString();
  }

  /**
   * This assumes the RegionPlan HSI instances are the same ones in the map, so
   * actually no need to even pass in the map, but I think it's clearer.
   *
   * @param list
   * @param plans
   * @return
   */
  protected List<ServerAndLoad> reconcile(List<ServerAndLoad> list,
                                          List<RegionPlan> plans,
                                          Map<ServerName, List<HRegionInfo>> servers) {
    List<ServerAndLoad> result = new ArrayList<ServerAndLoad>(list.size());
    if (plans == null) return result;
    Map<ServerName, ServerAndLoad> map = new HashMap<ServerName, ServerAndLoad>(list.size());
    for (ServerAndLoad sl : list) {
      map.put(sl.getServerName(), sl);
    }
    for (RegionPlan plan : plans) {
      ServerName source = plan.getSource();

      updateLoad(map, source, -1);
      ServerName destination = plan.getDestination();
      updateLoad(map, destination, +1);

      servers.get(source).remove(plan.getRegionInfo());
      servers.get(destination).add(plan.getRegionInfo());
    }
    result.clear();
    result.addAll(map.values());
    return result;
  }

  protected void updateLoad(final Map<ServerName, ServerAndLoad> map,
                            final ServerName sn,
                            final int diff) {
    ServerAndLoad sal = map.get(sn);
    if (sal == null) sal = new ServerAndLoad(sn, 0);
    sal = new ServerAndLoad(sn, sal.getLoad() + diff);
    map.put(sn, sal);
  }

  protected Map<ServerName, List<HRegionInfo>> mockClusterServers(int[] mockCluster) {
    return mockClusterServers(mockCluster, -1);
  }

  protected BaseLoadBalancer.Cluster mockCluster(int[] mockCluster) {
    return new BaseLoadBalancer.Cluster(mockClusterServers(mockCluster, -1), null, null);
  }

  protected Map<ServerName, List<HRegionInfo>> mockClusterServers(int[] mockCluster, int numTables) {
    int numServers = mockCluster.length;
    Map<ServerName, List<HRegionInfo>> servers = new TreeMap<ServerName, List<HRegionInfo>>();
    for (int i = 0; i < numServers; i++) {
      int numRegions = mockCluster[i];
      ServerAndLoad sal = randomServer(0);
      List<HRegionInfo> regions = randomRegions(numRegions, numTables);
      servers.put(sal.getServerName(), regions);
    }
    return servers;
  }

  private Queue<HRegionInfo> regionQueue = new LinkedList<HRegionInfo>();

  protected List<HRegionInfo> randomRegions(int numRegions) {
    return randomRegions(numRegions, -1);
  }

  protected List<HRegionInfo> randomRegions(int numRegions, int numTables) {
    List<HRegionInfo> regions = new ArrayList<HRegionInfo>(numRegions);
    byte[] start = new byte[16];
    byte[] end = new byte[16];
    rand.nextBytes(start);
    rand.nextBytes(end);
    for (int i = 0; i < numRegions; i++) {
      if (!regionQueue.isEmpty()) {
        regions.add(regionQueue.poll());
        continue;
      }
      Bytes.putInt(start, 0, numRegions << 1);
      Bytes.putInt(end, 0, (numRegions << 1) + 1);
      TableName tableName =
          TableName.valueOf("table" + (numTables > 0 ? rand.nextInt(numTables) : i));
      HRegionInfo hri = new HRegionInfo(tableName, start, end, false, regionId++);
      regions.add(hri);
    }
    return regions;
  }

  protected void returnRegions(List<HRegionInfo> regions) {
    regionQueue.addAll(regions);
  }

  private Queue<ServerName> serverQueue = new LinkedList<ServerName>();

  protected ServerAndLoad randomServer(final int numRegionsPerServer) {
    if (!this.serverQueue.isEmpty()) {
      ServerName sn = this.serverQueue.poll();
      return new ServerAndLoad(sn, numRegionsPerServer);
    }
    String host = "srv" + rand.nextInt(100000);
    int port = rand.nextInt(60000);
    long startCode = rand.nextLong();
    ServerName sn = ServerName.valueOf(host, port, startCode);
    return new ServerAndLoad(sn, numRegionsPerServer);
  }

  protected List<ServerAndLoad> randomServers(int numServers, int numRegionsPerServer) {
    List<ServerAndLoad> servers = new ArrayList<ServerAndLoad>(numServers);
    for (int i = 0; i < numServers; i++) {
      servers.add(randomServer(numRegionsPerServer));
    }
    return servers;
  }

  protected void returnServer(ServerName server) {
    serverQueue.add(server);
  }

  protected void returnServers(List<ServerName> servers) {
    this.serverQueue.addAll(servers);
  }

}
