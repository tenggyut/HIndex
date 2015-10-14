/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hadoop.hbase.io.hfile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.hadoop.hbase.io.compress.Compression.Algorithm;
import org.apache.hadoop.hbase.io.hfile.BlockType.BlockCategory;
import org.apache.hadoop.hbase.regionserver.BloomType;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Make sure we always cache important block types, such as index blocks, as
 * long as we have a block cache, even though block caching might be disabled
 * for the column family.
 */
@Category(MediumTests.class)
@RunWith(Parameterized.class)
public class TestForceCacheImportantBlocks {

  private final HBaseTestingUtility TEST_UTIL = HBaseTestingUtility.createLocalHTU();

  private static final String TABLE = "myTable";
  private static final String CF = "myCF";
  private static final byte[] CF_BYTES = Bytes.toBytes(CF);
  private static final int MAX_VERSIONS = 3;
  private static final int NUM_HFILES = 5;

  private static final int ROWS_PER_HFILE = 100;
  private static final int NUM_ROWS = NUM_HFILES * ROWS_PER_HFILE;
  private static final int NUM_COLS_PER_ROW = 50;
  private static final int NUM_TIMESTAMPS_PER_COL = 50;

  /** Extremely small block size, so that we can get some index blocks */
  private static final int BLOCK_SIZE = 256;
  
  private static final Algorithm COMPRESSION_ALGORITHM =
      Compression.Algorithm.GZ;
  private static final BloomType BLOOM_TYPE = BloomType.ROW;

  private final int hfileVersion;
  private final boolean cfCacheEnabled;

  @Parameters
  public static Collection<Object[]> parameters() {
    // HFile versions
    return Arrays.asList(new Object[][] {
        new Object[] { new Integer(2), false },
        new Object[] { new Integer(2), true }
    });
  }

  public TestForceCacheImportantBlocks(int hfileVersion,
      boolean cfCacheEnabled) {
    this.hfileVersion = hfileVersion;
    this.cfCacheEnabled = cfCacheEnabled;
    TEST_UTIL.getConfiguration().setInt(HFile.FORMAT_VERSION_KEY,
        hfileVersion);
  }

  @Test
  public void testCacheBlocks() throws IOException {
    // Set index block size to be the same as normal block size.
    TEST_UTIL.getConfiguration().setInt(HFileBlockIndex.MAX_CHUNK_SIZE_KEY,
        BLOCK_SIZE);


    HColumnDescriptor hcd =
        new HColumnDescriptor(Bytes.toBytes(CF))
            .setMaxVersions(MAX_VERSIONS)
            .setCompressionType(COMPRESSION_ALGORITHM)
            .setBloomFilterType(BLOOM_TYPE);
    hcd.setBlocksize(BLOCK_SIZE);
    hcd.setBlockCacheEnabled(cfCacheEnabled);
    HRegion region = TEST_UTIL.createTestRegion(TABLE, hcd);
    writeTestData(region);

    for (int i = 0; i < NUM_ROWS; ++i) {
      Get get = new Get(Bytes.toBytes("row" + i));
      region.get(get);
    }

    List<BlockCategory> importantBlockCategories =
        new ArrayList<BlockCategory>();
    importantBlockCategories.add(BlockCategory.BLOOM);
    if (hfileVersion == 2) {
      // We only have index blocks for HFile v2.
      importantBlockCategories.add(BlockCategory.INDEX);
    }
  }


  private void writeTestData(HRegion region) throws IOException {
    for (int i = 0; i < NUM_ROWS; ++i) {
      Put put = new Put(Bytes.toBytes("row" + i));
      for (int j = 0; j < NUM_COLS_PER_ROW; ++j) {
        for (long ts = 1; ts < NUM_TIMESTAMPS_PER_COL; ++ts) {
          put.add(CF_BYTES, Bytes.toBytes("col" + j), ts,
              Bytes.toBytes("value" + i + "_" + j + "_" + ts));
        }
      }
      region.put(put);
      if ((i + 1) % ROWS_PER_HFILE == 0) {
        region.flushcache();
      }
    }
  }

}

