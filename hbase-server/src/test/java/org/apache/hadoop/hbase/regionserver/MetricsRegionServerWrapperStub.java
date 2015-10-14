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

package org.apache.hadoop.hbase.regionserver;

public class MetricsRegionServerWrapperStub implements MetricsRegionServerWrapper {

  @Override
  public String getServerName() {
    return "test";
  }

  @Override
  public String getClusterId() {
    return "tClusterId";
  }

  @Override
  public String getZookeeperQuorum() {
    return "zk";
  }

  @Override
  public String getCoprocessors() {
    return "co-process";
  }

  @Override
  public long getStartCode() {
    return 100;
  }

  @Override
  public long getNumOnlineRegions() {
    return 101;
  }

  @Override
  public long getNumStores() {
    return 2;
  }

  @Override
  public long getNumStoreFiles() {
    return 300;
  }

  @Override
  public long getMemstoreSize() {
    return 1025;
  }

  @Override
  public long getStoreFileSize() {
    return 1900;
  }

  @Override
  public double getRequestsPerSecond() {
    return 0;
  }

  @Override
  public long getTotalRequestCount() {
    return 899;
  }

  @Override
  public long getReadRequestsCount() {
    return 997;
  }

  @Override
  public long getWriteRequestsCount() {
    return 707;
  }

  @Override
  public long getCheckAndMutateChecksFailed() {
    return 401;
  }

  @Override
  public long getCheckAndMutateChecksPassed() {
    return 405;
  }

  @Override
  public long getStoreFileIndexSize() {
    return 406;
  }

  @Override
  public long getTotalStaticIndexSize() {
    return 407;
  }

  @Override
  public long getTotalStaticBloomSize() {
    return 408;
  }

  @Override
  public long getNumMutationsWithoutWAL() {
    return 409;
  }

  @Override
  public long getDataInMemoryWithoutWAL() {
    return 410;
  }

  @Override
  public int getPercentFileLocal() {
    return 99;
  }

  @Override
  public int getCompactionQueueSize() {
    return 411;
  }

  @Override
  public int getSmallCompactionQueueSize() {
    return 0;
  }

  @Override
  public int getLargeCompactionQueueSize() {
    return 0;
  }

  @Override
  public int getFlushQueueSize() {
    return 412;
  }

  @Override
  public long getBlockCacheFreeSize() {
    return 413;
  }

  @Override
  public long getBlockCacheCount() {
    return 414;
  }

  @Override
  public long getBlockCacheSize() {
    return 415;
  }

  @Override
  public long getBlockCacheHitCount() {
    return 416;
  }

  @Override
  public long getBlockCacheMissCount() {
    return 417;
  }

  @Override
  public long getBlockCacheEvictedCount() {
    return 418;
  }

  @Override
  public int getBlockCacheHitPercent() {
    return 98;
  }

  @Override
  public int getBlockCacheHitCachingPercent() {
    return 97;
  }


  @Override
  public long getUpdatesBlockedTime() {
    return 419;
  }

  @Override
  public void forceRecompute() {
    //IGNORED.
  }

  @Override
  public long getNumHLogFiles() {
    return 10;
  }

  @Override
  public long getHLogFileSize() {
    return 1024000;
  }

}