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

package org.apache.hadoop.hbase.regionserver;

import org.apache.hadoop.hbase.metrics.BaseSource;

/**
 * Interface for classes that expose metrics about the regionserver.
 */
public interface MetricsRegionServerSource extends BaseSource {

  /**
   * The name of the metrics
   */
  String METRICS_NAME = "Server";

  /**
   * The name of the metrics context that metrics will be under.
   */
  String METRICS_CONTEXT = "regionserver";

  /**
   * Description
   */
  String METRICS_DESCRIPTION = "Metrics about HBase RegionServer";

  /**
   * The name of the metrics context that metrics will be under in jmx
   */
  String METRICS_JMX_CONTEXT = "RegionServer,sub=" + METRICS_NAME;

  /**
   * Update the Put time histogram
   *
   * @param t time it took
   */
  void updatePut(long t);

  /**
   * Update the Delete time histogram
   *
   * @param t time it took
   */
  void updateDelete(long t);

  /**
   * Update the Get time histogram .
   *
   * @param t time it took
   */
  void updateGet(long t);

  /**
   * Update the Increment time histogram.
   *
   * @param t time it took
   */
  void updateIncrement(long t);

  /**
   * Update the Append time histogram.
   *
   * @param t time it took
   */
  void updateAppend(long t);

  /**
   * Update the Replay time histogram.
   *
   * @param t time it took
   */
  void updateReplay(long t);

  /**
   * Increment the number of slow Puts that have happened.
   */
  void incrSlowPut();

  /**
   * Increment the number of slow Deletes that have happened.
   */
  void incrSlowDelete();

  /**
   * Increment the number of slow Gets that have happened.
   */
  void incrSlowGet();

  /**
   * Increment the number of slow Increments that have happened.
   */
  void incrSlowIncrement();

  /**
   * Increment the number of slow Appends that have happened.
   */
  void incrSlowAppend();

  // Strings used for exporting to metrics system.
  String REGION_COUNT = "regionCount";
  String REGION_COUNT_DESC = "Number of regions";
  String STORE_COUNT = "storeCount";
  String STORE_COUNT_DESC = "Number of Stores";
  String HLOGFILE_COUNT = "hlogFileCount";
  String HLOGFILE_COUNT_DESC = "Number of HLog Files";
  String HLOGFILE_SIZE = "hlogFileSize";
  String HLOGFILE_SIZE_DESC = "Size of all HLog Files";
  String STOREFILE_COUNT = "storeFileCount";
  String STOREFILE_COUNT_DESC = "Number of Store Files";
  String MEMSTORE_SIZE = "memStoreSize";
  String MEMSTORE_SIZE_DESC = "Size of the memstore";
  String STOREFILE_SIZE = "storeFileSize";
  String STOREFILE_SIZE_DESC = "Size of storefiles being served.";
  String TOTAL_REQUEST_COUNT = "totalRequestCount";
  String TOTAL_REQUEST_COUNT_DESC =
      "Total number of requests this RegionServer has answered.";
  String READ_REQUEST_COUNT = "readRequestCount";
  String READ_REQUEST_COUNT_DESC =
      "Number of read requests this region server has answered.";
  String WRITE_REQUEST_COUNT = "writeRequestCount";
  String WRITE_REQUEST_COUNT_DESC =
      "Number of mutation requests this region server has answered.";
  String CHECK_MUTATE_FAILED_COUNT = "checkMutateFailedCount";
  String CHECK_MUTATE_FAILED_COUNT_DESC =
      "Number of Check and Mutate calls that failed the checks.";
  String CHECK_MUTATE_PASSED_COUNT = "checkMutatePassedCount";
  String CHECK_MUTATE_PASSED_COUNT_DESC =
      "Number of Check and Mutate calls that passed the checks.";
  String STOREFILE_INDEX_SIZE = "storeFileIndexSize";
  String STOREFILE_INDEX_SIZE_DESC = "Size of indexes in storefiles on disk.";
  String STATIC_INDEX_SIZE = "staticIndexSize";
  String STATIC_INDEX_SIZE_DESC = "Uncompressed size of the static indexes.";
  String STATIC_BLOOM_SIZE = "staticBloomSize";
  String STATIC_BLOOM_SIZE_DESC =
      "Uncompressed size of the static bloom filters.";
  String NUMBER_OF_MUTATIONS_WITHOUT_WAL = "mutationsWithoutWALCount";
  String NUMBER_OF_MUTATIONS_WITHOUT_WAL_DESC =
      "Number of mutations that have been sent by clients with the write ahead logging turned off.";
  String DATA_SIZE_WITHOUT_WAL = "mutationsWithoutWALSize";
  String DATA_SIZE_WITHOUT_WAL_DESC =
      "Size of data that has been sent by clients with the write ahead logging turned off.";
  String PERCENT_FILES_LOCAL = "percentFilesLocal";
  String PERCENT_FILES_LOCAL_DESC =
      "The percent of HFiles that are stored on the local hdfs data node.";
  String COMPACTION_QUEUE_LENGTH = "compactionQueueLength";
  String LARGE_COMPACTION_QUEUE_LENGTH = "largeCompactionQueueLength";
  String SMALL_COMPACTION_QUEUE_LENGTH = "smallCompactionQueueLength";
  String COMPACTION_QUEUE_LENGTH_DESC = "Length of the queue for compactions.";
  String FLUSH_QUEUE_LENGTH = "flushQueueLength";
  String FLUSH_QUEUE_LENGTH_DESC = "Length of the queue for region flushes";
  String BLOCK_CACHE_FREE_SIZE = "blockCacheFreeSize";
  String BLOCK_CACHE_FREE_DESC =
      "Size of the block cache that is not occupied.";
  String BLOCK_CACHE_COUNT = "blockCacheCount";
  String BLOCK_CACHE_COUNT_DESC = "Number of block in the block cache.";
  String BLOCK_CACHE_SIZE = "blockCacheSize";
  String BLOCK_CACHE_SIZE_DESC = "Size of the block cache.";
  String BLOCK_CACHE_HIT_COUNT = "blockCacheHitCount";
  String BLOCK_CACHE_HIT_COUNT_DESC = "Count of the hit on the block cache.";
  String BLOCK_CACHE_MISS_COUNT = "blockCacheMissCount";
  String BLOCK_COUNT_MISS_COUNT_DESC =
      "Number of requests for a block that missed the block cache.";
  String BLOCK_CACHE_EVICTION_COUNT = "blockCacheEvictionCount";
  String BLOCK_CACHE_EVICTION_COUNT_DESC =
      "Count of the number of blocks evicted from the block cache.";
  String BLOCK_CACHE_HIT_PERCENT = "blockCountHitPercent";
  String BLOCK_CACHE_HIT_PERCENT_DESC =
      "Percent of block cache requests that are hits";
  String BLOCK_CACHE_EXPRESS_HIT_PERCENT = "blockCacheExpressHitPercent";
  String BLOCK_CACHE_EXPRESS_HIT_PERCENT_DESC =
      "The percent of the time that requests with the cache turned on hit the cache.";
  String RS_START_TIME_NAME = "regionServerStartTime";
  String ZOOKEEPER_QUORUM_NAME = "zookeeperQuorum";
  String SERVER_NAME_NAME = "serverName";
  String CLUSTER_ID_NAME = "clusterId";
  String RS_START_TIME_DESC = "RegionServer Start Time";
  String ZOOKEEPER_QUORUM_DESC = "Zookeeper Quorum";
  String SERVER_NAME_DESC = "Server Name";
  String CLUSTER_ID_DESC = "Cluster Id";
  String UPDATES_BLOCKED_TIME = "updatesBlockedTime";
  String UPDATES_BLOCKED_DESC =
      "Number of MS updates have been blocked so that the memstore can be flushed.";
  String DELETE_KEY = "delete";
  String GET_KEY = "get";
  String INCREMENT_KEY = "increment";
  String MUTATE_KEY = "mutate";
  String APPEND_KEY = "append";
  String REPLAY_KEY = "replay";
  String SCAN_NEXT_KEY = "scanNext";
  String SLOW_MUTATE_KEY = "slowPutCount";
  String SLOW_GET_KEY = "slowGetCount";
  String SLOW_DELETE_KEY = "slowDeleteCount";
  String SLOW_INCREMENT_KEY = "slowIncrementCount";
  String SLOW_APPEND_KEY = "slowAppendCount";
  String SLOW_MUTATE_DESC =
      "The number of Multis that took over 1000ms to complete";
  String SLOW_DELETE_DESC =
      "The number of Deletes that took over 1000ms to complete";
  String SLOW_GET_DESC = "The number of Gets that took over 1000ms to complete";
  String SLOW_INCREMENT_DESC =
      "The number of Increments that took over 1000ms to complete";
  String SLOW_APPEND_DESC =
      "The number of Appends that took over 1000ms to complete";


}
