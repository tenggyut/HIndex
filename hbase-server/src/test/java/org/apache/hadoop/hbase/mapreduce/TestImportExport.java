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
package org.apache.hadoop.hbase.mapreduce;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterBase;
import org.apache.hadoop.hbase.filter.PrefixFilter;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.Import.KeyValueImporter;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.hadoop.hbase.regionserver.wal.HLogKey;
import org.apache.hadoop.hbase.regionserver.wal.WALActionsListener;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.LauncherSecurityManager;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper.Context;
import org.apache.hadoop.util.GenericOptionsParser;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Tests the table import and table export MR job functionality
 */
@Category(MediumTests.class)
public class TestImportExport {
  private static final HBaseTestingUtility UTIL = new HBaseTestingUtility();
  private static final byte[] ROW1 = Bytes.toBytes("row1");
  private static final byte[] ROW2 = Bytes.toBytes("row2");
  private static final String FAMILYA_STRING = "a";
  private static final String FAMILYB_STRING = "b";
  private static final byte[] FAMILYA = Bytes.toBytes(FAMILYA_STRING);
  private static final byte[] FAMILYB = Bytes.toBytes(FAMILYB_STRING);
  private static final byte[] QUAL = Bytes.toBytes("q");
  private static final String OUTPUT_DIR = "outputdir";
  private static String FQ_OUTPUT_DIR;
  private static final String EXPORT_BATCH_SIZE = "100";

  private static long now = System.currentTimeMillis();

  @BeforeClass
  public static void beforeClass() throws Exception {
    UTIL.startMiniCluster();
    UTIL.startMiniMapReduceCluster();
    FQ_OUTPUT_DIR =  new Path(OUTPUT_DIR).makeQualified(FileSystem.get(UTIL.getConfiguration())).toString();
  }

  @AfterClass
  public static void afterClass() throws Exception {
    UTIL.shutdownMiniMapReduceCluster();
    UTIL.shutdownMiniCluster();
  }

  @Before
  @After
  public void cleanup() throws Exception {
    FileSystem fs = FileSystem.get(UTIL.getConfiguration());
    fs.delete(new Path(OUTPUT_DIR), true);
  }

  /**
   * Runs an export job with the specified command line args
   * @param args
   * @return true if job completed successfully
   * @throws IOException
   * @throws InterruptedException
   * @throws ClassNotFoundException
   */
  boolean runExport(String[] args) throws IOException, InterruptedException, ClassNotFoundException {
    // need to make a copy of the configuration because to make sure different temp dirs are used.
    GenericOptionsParser opts = new GenericOptionsParser(new Configuration(UTIL.getConfiguration()), args);
    Configuration conf = opts.getConfiguration();
    args = opts.getRemainingArgs();
    Job job = Export.createSubmittableJob(conf, args);
    job.waitForCompletion(false);
    return job.isSuccessful();
  }

  /**
   * Runs an import job with the specified command line args
   * @param args
   * @return true if job completed successfully
   * @throws IOException
   * @throws InterruptedException
   * @throws ClassNotFoundException
   */
  boolean runImport(String[] args) throws IOException, InterruptedException, ClassNotFoundException {
    // need to make a copy of the configuration because to make sure different temp dirs are used.
    GenericOptionsParser opts = new GenericOptionsParser(new Configuration(UTIL.getConfiguration()), args);
    Configuration conf = opts.getConfiguration();
    args = opts.getRemainingArgs();
    Job job = Import.createSubmittableJob(conf, args);
    job.waitForCompletion(false);
    return job.isSuccessful();
  }

  /**
   * Test simple replication case with column mapping
   * @throws Exception
   */
  @Test
  public void testSimpleCase() throws Exception {
    String EXPORT_TABLE = "exportSimpleCase";
    HTable t = UTIL.createTable(Bytes.toBytes(EXPORT_TABLE), FAMILYA, 3);
    Put p = new Put(ROW1);
    p.add(FAMILYA, QUAL, now, QUAL);
    p.add(FAMILYA, QUAL, now+1, QUAL);
    p.add(FAMILYA, QUAL, now+2, QUAL);
    t.put(p);
    p = new Put(ROW2);
    p.add(FAMILYA, QUAL, now, QUAL);
    p.add(FAMILYA, QUAL, now+1, QUAL);
    p.add(FAMILYA, QUAL, now+2, QUAL);
    t.put(p);

    String[] args = new String[] {
        EXPORT_TABLE,
        FQ_OUTPUT_DIR,
        "1000", // max number of key versions per key to export
    };
    assertTrue(runExport(args));

    String IMPORT_TABLE = "importTableSimpleCase";
    t = UTIL.createTable(Bytes.toBytes(IMPORT_TABLE), FAMILYB, 3);
    args = new String[] {
        "-D" + Import.CF_RENAME_PROP + "="+FAMILYA_STRING+":"+FAMILYB_STRING,
        IMPORT_TABLE,
        FQ_OUTPUT_DIR
    };
    assertTrue(runImport(args));

    Get g = new Get(ROW1);
    g.setMaxVersions();
    Result r = t.get(g);
    assertEquals(3, r.size());
    g = new Get(ROW2);
    g.setMaxVersions();
    r = t.get(g);
    assertEquals(3, r.size());
  }

  /**
   * Test export hbase:meta table
   *
   * @throws Exception
   */
  @Test
  public void testMetaExport() throws Exception {
    String EXPORT_TABLE = TableName.META_TABLE_NAME.getNameAsString();
    String[] args = new String[] { EXPORT_TABLE, FQ_OUTPUT_DIR, "1", "0", "0" };
    assertTrue(runExport(args));
  }

  /**
   * Test import data from 0.94 exported file
   * @throws Exception
   */
  @Test
  public void testImport94Table() throws Exception {
    URL url = TestImportExport.class.getResource(
        "exportedTableIn94Format");
    Path importPath = new Path(url.getPath());
    FileSystem fs = FileSystem.get(UTIL.getConfiguration());
    fs.copyFromLocalFile(importPath, new Path(FQ_OUTPUT_DIR + Path.SEPARATOR
        + "exportedTableIn94Format"));
    String IMPORT_TABLE = "importTableExportedFrom94";
    HTable t = UTIL.createTable(Bytes.toBytes(IMPORT_TABLE), Bytes.toBytes("f1"), 3);
    String[] args = new String[] {
        "-Dhbase.import.version=0.94" ,
        IMPORT_TABLE, FQ_OUTPUT_DIR
    };
    assertTrue(runImport(args));

    /* exportedTableIn94Format contains 5 rows
     ROW         COLUMN+CELL
     r1          column=f1:c1, timestamp=1383766761171, value=val1
     r2          column=f1:c1, timestamp=1383766771642, value=val2
     r3          column=f1:c1, timestamp=1383766777615, value=val3
     r4          column=f1:c1, timestamp=1383766785146, value=val4
     r5          column=f1:c1, timestamp=1383766791506, value=val5
     */
    assertEquals(5, UTIL.countRows(t));
    t.close();
  }

  /**
   * Test export scanner batching
   */
   @Test
   public void testExportScannerBatching() throws Exception {
    String BATCH_TABLE = "exportWithBatch";
    HTableDescriptor desc = new HTableDescriptor(TableName.valueOf(BATCH_TABLE));
    desc.addFamily(new HColumnDescriptor(FAMILYA)
        .setMaxVersions(1)
    );
    UTIL.getHBaseAdmin().createTable(desc);
    HTable t = new HTable(UTIL.getConfiguration(), BATCH_TABLE);

    Put p = new Put(ROW1);
    p.add(FAMILYA, QUAL, now, QUAL);
    p.add(FAMILYA, QUAL, now+1, QUAL);
    p.add(FAMILYA, QUAL, now+2, QUAL);
    p.add(FAMILYA, QUAL, now+3, QUAL);
    p.add(FAMILYA, QUAL, now+4, QUAL);
    t.put(p);

    String[] args = new String[] {
        "-D" + Export.EXPORT_BATCHING + "=" + EXPORT_BATCH_SIZE,  // added scanner batching arg.
        BATCH_TABLE,
        FQ_OUTPUT_DIR
    };
    assertTrue(runExport(args));

    FileSystem fs = FileSystem.get(UTIL.getConfiguration());
    fs.delete(new Path(FQ_OUTPUT_DIR), true);
    t.close();
  }

  @Test
  public void testWithDeletes() throws Exception {
    String EXPORT_TABLE = "exportWithDeletes";
    HTableDescriptor desc = new HTableDescriptor(TableName.valueOf(EXPORT_TABLE));
    desc.addFamily(new HColumnDescriptor(FAMILYA)
        .setMaxVersions(5)
        .setKeepDeletedCells(true)
    );
    UTIL.getHBaseAdmin().createTable(desc);
    HTable t = new HTable(UTIL.getConfiguration(), EXPORT_TABLE);

    Put p = new Put(ROW1);
    p.add(FAMILYA, QUAL, now, QUAL);
    p.add(FAMILYA, QUAL, now+1, QUAL);
    p.add(FAMILYA, QUAL, now+2, QUAL);
    p.add(FAMILYA, QUAL, now+3, QUAL);
    p.add(FAMILYA, QUAL, now+4, QUAL);
    t.put(p);

    Delete d = new Delete(ROW1, now+3);
    t.delete(d);
    d = new Delete(ROW1);
    d.deleteColumns(FAMILYA, QUAL, now+2);
    t.delete(d);

    String[] args = new String[] {
        "-D" + Export.RAW_SCAN + "=true",
        EXPORT_TABLE,
        FQ_OUTPUT_DIR,
        "1000", // max number of key versions per key to export
    };
    assertTrue(runExport(args));

    String IMPORT_TABLE = "importWithDeletes";
    desc = new HTableDescriptor(TableName.valueOf(IMPORT_TABLE));
    desc.addFamily(new HColumnDescriptor(FAMILYA)
        .setMaxVersions(5)
        .setKeepDeletedCells(true)
    );
    UTIL.getHBaseAdmin().createTable(desc);
    t.close();
    t = new HTable(UTIL.getConfiguration(), IMPORT_TABLE);
    args = new String[] {
        IMPORT_TABLE,
        FQ_OUTPUT_DIR
    };
    assertTrue(runImport(args));

    Scan s = new Scan();
    s.setMaxVersions();
    s.setRaw(true);
    ResultScanner scanner = t.getScanner(s);
    Result r = scanner.next();
    Cell[] res = r.rawCells();
    assertTrue(CellUtil.isDeleteFamily(res[0]));
    assertEquals(now+4, res[1].getTimestamp());
    assertEquals(now+3, res[2].getTimestamp());
    assertTrue(CellUtil.isDelete(res[3]));
    assertEquals(now+2, res[4].getTimestamp());
    assertEquals(now+1, res[5].getTimestamp());
    assertEquals(now, res[6].getTimestamp());
    t.close();
  }

  /**
   * Create a simple table, run an Export Job on it, Import with filtering on,  verify counts,
   * attempt with invalid values.
   */
  @Test
  public void testWithFilter() throws Exception {
    // Create simple table to export
    String EXPORT_TABLE = "exportSimpleCase_ImportWithFilter";
    HTableDescriptor desc = new HTableDescriptor(TableName.valueOf(EXPORT_TABLE));
    desc.addFamily(new HColumnDescriptor(FAMILYA).setMaxVersions(5));
    UTIL.getHBaseAdmin().createTable(desc);
    HTable exportTable = new HTable(UTIL.getConfiguration(), EXPORT_TABLE);

    Put p = new Put(ROW1);
    p.add(FAMILYA, QUAL, now, QUAL);
    p.add(FAMILYA, QUAL, now + 1, QUAL);
    p.add(FAMILYA, QUAL, now + 2, QUAL);
    p.add(FAMILYA, QUAL, now + 3, QUAL);
    p.add(FAMILYA, QUAL, now + 4, QUAL);
    exportTable.put(p);

    // Having another row would actually test the filter.
    p = new Put(ROW2);
    p.add(FAMILYA, QUAL, now, QUAL);
    exportTable.put(p);
    // Flush the commits.
    exportTable.flushCommits();

    // Export the simple table
    String[] args = new String[] { EXPORT_TABLE, FQ_OUTPUT_DIR, "1000" };
    assertTrue(runExport(args));

    // Import to a new table
    String IMPORT_TABLE = "importWithFilter";
    desc = new HTableDescriptor(TableName.valueOf(IMPORT_TABLE));
    desc.addFamily(new HColumnDescriptor(FAMILYA).setMaxVersions(5));
    UTIL.getHBaseAdmin().createTable(desc);

    HTable importTable = new HTable(UTIL.getConfiguration(), IMPORT_TABLE);
    args = new String[] { "-D" + Import.FILTER_CLASS_CONF_KEY + "=" + PrefixFilter.class.getName(),
        "-D" + Import.FILTER_ARGS_CONF_KEY + "=" + Bytes.toString(ROW1), IMPORT_TABLE, FQ_OUTPUT_DIR,
        "1000" };
    assertTrue(runImport(args));

    // get the count of the source table for that time range
    PrefixFilter filter = new PrefixFilter(ROW1);
    int count = getCount(exportTable, filter);

    Assert.assertEquals("Unexpected row count between export and import tables", count,
      getCount(importTable, null));

    // and then test that a broken command doesn't bork everything - easier here because we don't
    // need to re-run the export job

    args = new String[] { "-D" + Import.FILTER_CLASS_CONF_KEY + "=" + Filter.class.getName(),
        "-D" + Import.FILTER_ARGS_CONF_KEY + "=" + Bytes.toString(ROW1) + "", EXPORT_TABLE,
        FQ_OUTPUT_DIR, "1000" };
    assertFalse(runImport(args));

    // cleanup
    exportTable.close();
    importTable.close();
  }

  /**
   * Count the number of keyvalues in the specified table for the given timerange
   * @param start
   * @param end
   * @param table
   * @return
   * @throws IOException
   */
  private int getCount(HTable table, Filter filter) throws IOException {
    Scan scan = new Scan();
    scan.setFilter(filter);
    ResultScanner results = table.getScanner(scan);
    int count = 0;
    for (Result res : results) {
      count += res.size();
    }
    results.close();
    return count;
  }
  
  /**
   * test main method. Import should print help and call System.exit
   */
  @Test
  public void testImportMain() throws Exception {
    PrintStream oldPrintStream = System.err;
    SecurityManager SECURITY_MANAGER = System.getSecurityManager();
    LauncherSecurityManager newSecurityManager= new LauncherSecurityManager();
    System.setSecurityManager(newSecurityManager);
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    String[] args = {};
    System.setErr(new PrintStream(data));
    try {
      System.setErr(new PrintStream(data));
      Import.main(args);
      fail("should be SecurityException");
    } catch (SecurityException e) {
      assertEquals(-1, newSecurityManager.getExitCode());
      assertTrue(data.toString().contains("Wrong number of arguments:"));
      assertTrue(data.toString().contains("-Dimport.bulk.output=/path/for/output"));
      assertTrue(data.toString().contains("-Dimport.filter.class=<name of filter class>"));
      assertTrue(data.toString().contains("-Dimport.bulk.output=/path/for/output"));
      assertTrue(data.toString().contains("-Dmapred.reduce.tasks.speculative.execution=false"));
    } finally {
      System.setErr(oldPrintStream);
      System.setSecurityManager(SECURITY_MANAGER);
    }
  }

  /**
   * test main method. Export should print help and call System.exit
   */
  @Test
  public void testExportMain() throws Exception {
    PrintStream oldPrintStream = System.err;
    SecurityManager SECURITY_MANAGER = System.getSecurityManager();
    LauncherSecurityManager newSecurityManager= new LauncherSecurityManager();
    System.setSecurityManager(newSecurityManager);
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    String[] args = {};
    System.setErr(new PrintStream(data));
    try {
      System.setErr(new PrintStream(data));
      Export.main(args);
      fail("should be SecurityException");
    } catch (SecurityException e) {
      assertEquals(-1, newSecurityManager.getExitCode());
      assertTrue(data.toString().contains("Wrong number of arguments:"));
      assertTrue(data.toString().contains(
              "Usage: Export [-D <property=value>]* <tablename> <outputdir> [<versions> " +
              "[<starttime> [<endtime>]] [^[regex pattern] or [Prefix] to filter]]"));
      assertTrue(data.toString().contains("-D hbase.mapreduce.scan.column.family=<familyName>"));
      assertTrue(data.toString().contains("-D hbase.mapreduce.include.deleted.rows=true"));
      assertTrue(data.toString().contains("-Dhbase.client.scanner.caching=100"));
      assertTrue(data.toString().contains("-Dmapred.map.tasks.speculative.execution=false"));
      assertTrue(data.toString().contains("-Dmapred.reduce.tasks.speculative.execution=false"));
      assertTrue(data.toString().contains("-Dhbase.export.scanner.batch=10"));
    } finally {
      System.setErr(oldPrintStream);
      System.setSecurityManager(SECURITY_MANAGER);
    }
  }

  /**
   * Test map method of Importer
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  @Test
  public void testKeyValueImporter() throws Exception {
    KeyValueImporter importer = new KeyValueImporter();
    Configuration configuration = new Configuration();
    Context ctx = mock(Context.class);
    when(ctx.getConfiguration()).thenReturn(configuration);

    doAnswer(new Answer<Void>() {

      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        ImmutableBytesWritable writer = (ImmutableBytesWritable) invocation.getArguments()[0];
        KeyValue key = (KeyValue) invocation.getArguments()[1];
        assertEquals("Key", Bytes.toString(writer.get()));
        assertEquals("row", Bytes.toString(key.getRow()));
        return null;
      }
    }).when(ctx).write(any(ImmutableBytesWritable.class), any(KeyValue.class));

    importer.setup(ctx);
    Result value = mock(Result.class);
    KeyValue[] keys = {
        new KeyValue(Bytes.toBytes("row"), Bytes.toBytes("family"), Bytes.toBytes("qualifier"),
            Bytes.toBytes("value")),
        new KeyValue(Bytes.toBytes("row"), Bytes.toBytes("family"), Bytes.toBytes("qualifier"),
            Bytes.toBytes("value1")) };
    when(value.rawCells()).thenReturn(keys);
    importer.map(new ImmutableBytesWritable(Bytes.toBytes("Key")), value, ctx);

  }

  /**
   * Test addFilterAndArguments method of Import This method set couple
   * parameters into Configuration
   */
  @Test
  public void testAddFilterAndArguments() throws IOException {
    Configuration configuration = new Configuration();

    List<String> args = new ArrayList<String>();
    args.add("param1");
    args.add("param2");

    Import.addFilterAndArguments(configuration, FilterBase.class, args);
    assertEquals("org.apache.hadoop.hbase.filter.FilterBase", 
        configuration.get(Import.FILTER_CLASS_CONF_KEY));
    assertEquals("param1,param2", configuration.get(Import.FILTER_ARGS_CONF_KEY));
  }

  @Test
  public void testDurability() throws IOException, InterruptedException, ClassNotFoundException {
    // Create an export table.
    String exportTableName = "exporttestDurability";
    HTable exportTable = UTIL.createTable(Bytes.toBytes(exportTableName), FAMILYA, 3);

    // Insert some data
    Put put = new Put(ROW1);
    put.add(FAMILYA, QUAL, now, QUAL);
    put.add(FAMILYA, QUAL, now + 1, QUAL);
    put.add(FAMILYA, QUAL, now + 2, QUAL);
    exportTable.put(put);

    put = new Put(ROW2);
    put.add(FAMILYA, QUAL, now, QUAL);
    put.add(FAMILYA, QUAL, now + 1, QUAL);
    put.add(FAMILYA, QUAL, now + 2, QUAL);
    exportTable.put(put);

    // Run the export
    String[] args = new String[] { exportTableName, FQ_OUTPUT_DIR, "1000"};
    assertTrue(runExport(args));

    // Create the table for import
    String importTableName = "importTestDurability1";
    HTable importTable = UTIL.createTable(Bytes.toBytes(importTableName), FAMILYA, 3);

    // Register the hlog listener for the import table
    TableWALActionListener walListener = new TableWALActionListener(importTableName);
    HLog hLog = UTIL.getMiniHBaseCluster().getRegionServer(0).getWAL();
    hLog.registerWALActionsListener(walListener);

    // Run the import with SKIP_WAL
    args =
        new String[] { "-D" + Import.WAL_DURABILITY + "=" + Durability.SKIP_WAL.name(),
            importTableName, FQ_OUTPUT_DIR };
    assertTrue(runImport(args));
    //Assert that the wal is not visisted
    assertTrue(!walListener.isWALVisited());
    //Ensure that the count is 2 (only one version of key value is obtained)
    assertTrue(getCount(importTable, null) == 2);

    // Run the import with the default durability option
    importTableName = "importTestDurability2";
    importTable = UTIL.createTable(Bytes.toBytes(importTableName), FAMILYA, 3);
    hLog.unregisterWALActionsListener(walListener);    
    walListener = new TableWALActionListener(importTableName);
    hLog.registerWALActionsListener(walListener);
    args = new String[] { importTableName, FQ_OUTPUT_DIR };
    assertTrue(runImport(args));
    //Assert that the wal is visisted
    assertTrue(walListener.isWALVisited());
    //Ensure that the count is 2 (only one version of key value is obtained)
    assertTrue(getCount(importTable, null) == 2);
  }

  /**
   * This listens to the {@link #visitLogEntryBeforeWrite(HTableDescriptor, HLogKey, WALEdit)} to
   * identify that an entry is written to the Write Ahead Log for the given table.
   */
  private static class TableWALActionListener implements WALActionsListener {

    private String tableName;
    private boolean isVisited = false;

    public TableWALActionListener(String tableName) {
      this.tableName = tableName;
    }

    @Override
    public void preLogRoll(Path oldPath, Path newPath) throws IOException {
      // Not interested in this method.
    }

    @Override
    public void postLogRoll(Path oldPath, Path newPath) throws IOException {
      // Not interested in this method.
    }

    @Override
    public void preLogArchive(Path oldPath, Path newPath) throws IOException {
      // Not interested in this method.
    }

    @Override
    public void postLogArchive(Path oldPath, Path newPath) throws IOException {
      // Not interested in this method.
    }

    @Override
    public void logRollRequested() {
      // Not interested in this method.
    }

    @Override
    public void logCloseRequested() {
      // Not interested in this method.
    }

    @Override
    public void visitLogEntryBeforeWrite(HRegionInfo info, HLogKey logKey, WALEdit logEdit) {
      // Not interested in this method.
    }

    @Override
    public void visitLogEntryBeforeWrite(HTableDescriptor htd, HLogKey logKey, WALEdit logEdit) {
      if (tableName.equalsIgnoreCase(htd.getNameAsString())) {
        isVisited = true;
      }
    }

    public boolean isWALVisited() {
      return isVisited;
    }
  }  
}