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
package org.apache.drill.exec.store.easy.text.compliant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import org.apache.drill.categories.RowSetTests;
import org.apache.drill.common.exceptions.UserRemoteException;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.ExecConstants;
import org.apache.drill.exec.record.metadata.SchemaBuilder;
import org.apache.drill.exec.record.metadata.TupleMetadata;
import org.apache.drill.exec.rpc.RpcException;
import org.apache.drill.test.rowSet.DirectRowSet;
import org.apache.drill.test.rowSet.RowSet;
import org.apache.drill.test.rowSet.RowSetBuilder;
import org.apache.drill.test.rowSet.RowSetComparison;
import org.apache.drill.test.rowSet.RowSetReader;
import org.apache.drill.test.rowSet.RowSetUtilities;
import org.joda.time.LocalDate;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Test the schema mechanism in the context of a simple CSV tables.
 * The focus here is on the schema mechanism, which can be explored
 * with simple tables of just a few rows.
 */
@Category(RowSetTests.class)
public class TestCsvWithSchema extends BaseCsvTest {

  protected static final String FILE1_NAME = "file1.csv";
  protected static final String FILE_N_NAME = "file%d.csv";

  protected static String basicFileContents[] = {
      "intcol,datecol,str,dub",
      "10,2019-03-20,it works!,1234.5"
  };

  public static final String raggedMulti1Contents[] = {
      "id,name,date,gender",
      "1,wilma,2019-01-18,female",
      "2,fred,2019-01-19,male",
      "4,betty,2019-05-04"
  };

  public static final String multi1Contents[] = {
      "id,name,date,gender",
      "1,wilma,2019-01-18,female",
      "2,fred,2019-01-19,male",
      "4,betty,2019-05-04,NA"
  };

  public static final String multi2Contents[] = {
      "id,name,date",
      "3,barney,2001-01-16"
  };

  public static final String multi2FullContents[] = {
      "id,name,date",
      "3,barney,2001-01-16,NA"
  };

  public static final String reordered2Contents[] = {
      "name,id,date",
      "barney,3,2001-01-16"
  };

  public static final String multi3Contents[] = {
      "name,date",
      "dino,2018-09-01"
  };

  public static final String nameOnlyContents[] = {
      "name",
      "dino"
  };

  public static final String SCHEMA_SQL = "create or replace schema (" +
      "id int not null, " +
      "`date` date format 'yyyy-MM-dd', " +
      "gender varchar not null default 'NA', " +
      "comment varchar not null default 'ABC') " +
      "for table %s";

  @BeforeClass
  public static void setup() throws Exception {
    BaseCsvTest.setup(false,  true);
  }

  private static String buildTable(String tableName, String[]...fileContents) throws IOException {
    File rootDir = new File(testDir, tableName);
    rootDir.mkdir();
    for (int i = 0; i < fileContents.length; i++) {
      String fileName = String.format(FILE_N_NAME, i);
      buildFile(new File(rootDir, fileName), fileContents[i]);
    }
    return "`dfs.data`.`" + tableName + "`";
  }

  private void enableSchema(boolean enable) {
    client.alterSession(ExecConstants.STORE_TABLE_USE_SCHEMA_FILE, enable);
  }

  private void resetSchema() {
    client.resetSession(ExecConstants.STORE_TABLE_USE_SCHEMA_FILE);
  }

  /**
   * Test the simplest possible case: a table with one file:
   * <ul>
   * <li>Column in projection, table, and schema</li>
   * <li>Column in projection and table but not in schema.</li>
   * <li>Column in projection and schema, but not in table.</li>
   * <li>Column in projection, but not in schema or table.</li>
   * </ul>
   */
  @Test
  public void testBasicSchema() throws Exception {
    String tablePath = buildTable("basic", basicFileContents);

    try {
      enableV3(true);
      enableSchema(true);
      String schemaSql = "create schema (intcol int not null, datecol date not null, " +
          "`dub` double not null, `extra` bigint not null default '20') " +
          "for table " + tablePath;
      run(schemaSql);
      String sql = "SELECT `intcol`, `datecol`, `str`, `dub`, `extra`, `missing` FROM " + tablePath;
      RowSet actual = client.queryBuilder().sql(sql).rowSet();

      TupleMetadata expectedSchema = new SchemaBuilder()
          .add("intcol", MinorType.INT)      // Has a schema
          .add("datecol", MinorType.DATE)    // Has a schema
          .add("str", MinorType.VARCHAR)     // No schema, retains type
          .add("dub", MinorType.FLOAT8)      // Has a schema
          .add("extra", MinorType.BIGINT)    // No data, has default value
          .add("missing", MinorType.VARCHAR) // No data, no schema, default type
          .buildSchema();
      RowSet expected = new RowSetBuilder(client.allocator(), expectedSchema)
          .addRow(10, new LocalDate(2019, 3, 20), "it works!", 1234.5D, 20L, "")
          .build();
      RowSetUtilities.verify(expected, actual);
    } finally {
      resetV3();
      resetSchema();
    }
  }

  /**
   * Show that the projection framework generates a reasonable default value
   * if told to create a required column that does not exist. In this case,
   * the default default [sic] value for an INT column is 0. There is no
   * "default" value set in the schema, so we use a "default default" instead.)
   */
  @Test
  public void testMissingRequiredCol() throws Exception {
    String tableName = "missingReq";
    String tablePath = buildTable(tableName, multi3Contents);

    try {
      enableV3(true);
      enableSchema(true);
      run(SCHEMA_SQL, tablePath);
      String sql = "SELECT id, `name` FROM " + tablePath;
      RowSet actual = client.queryBuilder().sql(sql).rowSet();

      TupleMetadata expectedSchema = new SchemaBuilder()
          .add("id", MinorType.INT)
          .add("name", MinorType.VARCHAR)
          .buildSchema();
      RowSet expected = new RowSetBuilder(client.allocator(), expectedSchema)
          .addRow(0, "dino")
          .build();
      RowSetUtilities.verify(expected, actual);
    } finally {
      resetV3();
      resetSchema();
    }
  }

  /**
   * Use a user-provided default value for a missing required column.
   */
  @Test
  public void testRequiredColDefault() throws Exception {
    String tableName = "missingReq";
    String tablePath = buildTable(tableName, multi3Contents);

    try {
      enableV3(true);
      enableSchema(true);
      String schemaSql = SCHEMA_SQL.replace("id int not null", "id int not null default '-1'");
      run(schemaSql, tablePath);
      String sql = "SELECT id, `name`, `date` FROM " + tablePath;
      RowSet actual = client.queryBuilder().sql(sql).rowSet();

      TupleMetadata expectedSchema = new SchemaBuilder()
          .add("id", MinorType.INT)
          .add("name", MinorType.VARCHAR)
          .addNullable("date", MinorType.DATE)
          .buildSchema();
      RowSet expected = new RowSetBuilder(client.allocator(), expectedSchema)
          .addRow(-1, "dino", new LocalDate(2018, 9, 1))
          .build();
      RowSetUtilities.verify(expected, actual);
    } finally {
      resetV3();
      resetSchema();
    }
  }

  /**
   * Use a user-provided default value for a missing required column.
   */
  @Test
  public void testDateColDefault() throws Exception {
    String tableName = "missingDate";
    String tablePath = buildTable(tableName, nameOnlyContents);

    try {
      enableV3(true);
      enableSchema(true);
      String schemaSql = SCHEMA_SQL.replace("`date` date format 'yyyy-MM-dd'",
          "`date` date format 'yyyy-MM-dd' default '2001-02-03'");
      run(schemaSql, tablePath);
      String sql = "SELECT id, `name`, `date` FROM " + tablePath;
      RowSet actual = client.queryBuilder().sql(sql).rowSet();

      TupleMetadata expectedSchema = new SchemaBuilder()
          .add("id", MinorType.INT)
          .add("name", MinorType.VARCHAR)
          .addNullable("date", MinorType.DATE)
          .buildSchema();
      RowSet expected = new RowSetBuilder(client.allocator(), expectedSchema)
          .addRow(0, "dino", new LocalDate(2001, 2, 3))
          .build();
      RowSetUtilities.verify(expected, actual);
    } finally {
      resetV3();
      resetSchema();
    }
  }

  /**
   * Use a schema with explicit projection to get a consistent view
   * of the table schema, even if columns are missing, rows are ragged,
   * and column order changes.
   * <p>
   * Force the scans to occur in distinct fragments so the order of the
   * file batches is random.
   */
  @Test
  public void testMultiFileSchema() throws Exception {
    RowSet expected1 = null;
    RowSet expected2 = null;
    try {
      enableV3(true);
      enableSchema(true);
      enableMultiScan();
      String tablePath = buildTable("multiFileSchema", raggedMulti1Contents, reordered2Contents);
      run(SCHEMA_SQL, tablePath);

      // Wildcard expands to union of schema + table. In this case
      // all table columns appear in the schema (though not all schema
      // columns appear in the table.)

      String sql = "SELECT id, `name`, `date`, gender, comment FROM " + tablePath;
      TupleMetadata expectedSchema = new SchemaBuilder()
          .add("id", MinorType.INT)
          .add("name", MinorType.VARCHAR)
          .addNullable("date", MinorType.DATE)
          .add("gender", MinorType.VARCHAR)
          .add("comment", MinorType.VARCHAR)
          .buildSchema();
      expected1 = new RowSetBuilder(client.allocator(), expectedSchema)
          .addRow(1, "wilma", new LocalDate(2019, 1, 18), "female", "ABC")
          .addRow(2, "fred", new LocalDate(2019, 1, 19), "male", "ABC")
          .addRow(4, "betty", new LocalDate(2019, 5, 4), "", "ABC")
          .build();
      expected2 = new RowSetBuilder(client.allocator(), expectedSchema)
          .addRow(3, "barney", new LocalDate(2001, 1, 16), "NA", "ABC")
          .build();

      // Loop 10 times so that, as the two reader fragments read the two
      // files, we end up with (acceptable) races that read the files in
      // random order.

      for (int i = 0; i < 10; i++) {
        boolean sawSchema = false;
        boolean sawFile1 = false;
        boolean sawFile2 = false;
        Iterator<DirectRowSet> iter = client.queryBuilder().sql(sql).rowSetIterator();
        while (iter.hasNext()) {
          RowSet result = iter.next();
          if (result.rowCount() == 3) {
            sawFile1 = true;
            new RowSetComparison(expected1).verifyAndClear(result);
          } else if (result.rowCount() == 1) {
            sawFile2 = true;
            new RowSetComparison(expected2).verifyAndClear(result);
          } else {
            assertEquals(0, result.rowCount());
            sawSchema = true;
          }
        }
        assertTrue(sawSchema);
        assertTrue(sawFile1);
        assertTrue(sawFile2);
      }
    } finally {
      expected1.clear();
      expected2.clear();
      client.resetSession(ExecConstants.ENABLE_V3_TEXT_READER_KEY);
      client.resetSession(ExecConstants.STORE_TABLE_USE_SCHEMA_FILE);
      client.resetSession(ExecConstants.MIN_READER_WIDTH_KEY);
    }
  }
  /**
   * Test the schema we get in V2 when the table read order is random.
   * Worst-case: the two files have different column counts and
   * column orders.
   * <p>
   * Though the results are random, we iterate 10 times which, in most runs,
   * shows the random variation in schemas:
   * <ul>
   * <li>Sometimes the first batch has three columns, sometimes four.</li>
   * <li>Sometimes the column `id` is in position 0, sometimes in position 1
   * (correlated with the above).</li>
   * <li>Due to the fact that sometimes the first file (with four columns)
   * is returned first, sometimes the second file (with three columns) is
   * returned first.</li>
   * </ul>
   */
  @Test
  public void testSchemaRaceV2() throws Exception {
    try {
      enableV3(false);
      enableSchema(false);
      enableMultiScan();
      String tablePath = buildTable("schemaRaceV2", multi1Contents, reordered2Contents);
      boolean sawFile1First = false;
      boolean sawFile2First = false;
      boolean sawFullSchema = false;
      boolean sawPartialSchema = false;
      boolean sawIdAsCol0 = false;
      boolean sawIdAsCol1 = false;
      String sql = "SELECT * FROM " + tablePath;
      for (int i = 0; i < 10; i++) {
        Iterator<DirectRowSet> iter = client.queryBuilder().sql(sql).rowSetIterator();
        int batchCount = 0;
        while(iter.hasNext()) {
          batchCount++;
          RowSet result = iter.next();
          TupleMetadata resultSchema = result.schema();
          if (resultSchema.size() == 4) {
            sawFullSchema = true;
          } else {
            assertEquals(3, resultSchema.size());
            sawPartialSchema = true;
          }
          if (resultSchema.index("id") == 0) {
            sawIdAsCol0 = true;
          } else {
            assertEquals(1, resultSchema.index("id"));
            sawIdAsCol1 = true;
          }
          if (batchCount == 1) {
            RowSetReader reader = result.reader();
            assertTrue(reader.next());
            String id = reader.scalar("id").getString();
            if (id.equals("1")) {
              sawFile1First = true;
            } else {
              assertEquals("3", id);
              sawFile2First = true;
            }
          }
          result.clear();
        }
      }

      // Outcome is random (which is the key problem). Don't assert on these
      // because doing so can lead to a flakey test.

      if (!sawFile1First || ! sawFile2First || !sawFullSchema || !sawPartialSchema || !sawIdAsCol0 || !sawIdAsCol1) {
        System.out.println("Some variations did not occur");
        System.out.println(String.format("File 1 first: %s", sawFile1First));
        System.out.println(String.format("File 1 second: %s", sawFile2First));
        System.out.println(String.format("Full schema: %s", sawFullSchema));
        System.out.println(String.format("Partial schema: %s", sawPartialSchema));
        System.out.println(String.format("`id` as col 0: %s", sawIdAsCol0));
        System.out.println(String.format("`id` as col 1: %s", sawIdAsCol1));
      }
      // Sanity checks
      assertTrue(sawFullSchema);
      assertTrue(sawFile1First || sawFile2First);
      assertTrue(sawIdAsCol0 || sawIdAsCol1);
    } finally {
      resetV3();
      resetSchema();
      resetMultiScan();
    }
  }

  /**
   * Show that, without schema, the hard schema change for the "missing"
   * gender column causes an error in the sort operator when presented with
   * one batch in which gender is VARCHAR, another in which it is
   * Nullable INT. This is a consequence of using SELECT * on a distributed
   * scan.
   */
  @Test
  public void testWildcardSortFailure() throws Exception {
    try {
      enableSchema(false);
      enableMultiScan();
      enableV3(false);
      String tablePath = buildTable("wildcardSortV2", multi1Contents, reordered2Contents);
      doTestWildcardSortFailure(tablePath);
      enableV3(true);
      doTestWildcardSortFailure(tablePath);
    } finally {
      resetV3();
      resetSchema();
      resetMultiScan();
    }
  }

  private void doTestWildcardSortFailure(String tablePath) throws Exception {
    String sql = "SELECT * FROM " + tablePath + " ORDER BY id";
    boolean sawError = false;
    for (int i = 0; i < 10; i++) {
      try {
        // When this fails it will print a nasty stack trace.
        RowSet result = client.queryBuilder().sql(sql).rowSet();
        assertEquals(4, result.rowCount());
        result.clear();
      } catch (RpcException e) {
        assertTrue(e.getCause() instanceof UserRemoteException);
        sawError = true;
        break;
      }
    }
    assertTrue(sawError);
  }

  /**
   * Test an explicit projection with a sort. Using the sort 1) will blow up
   * if the internal schema is inconsistent, and 2) allows easier verification
   * of the merged table results.
   * <p>
   * Fails with <code><pre>
   * #: id, name, gender
   * 0: "1", "barney  ", "      "
   * 1: "2", "    ", "    "
   * 2: "3", " 
    * ", ""
   * 3: "4", "  
   *    " java.lang.NegativeArraySizeException: null
   *      at io.netty.buffer.DrillBuf.unsafeGetMemory(DrillBuf.java:852) ~[classes/:4.0.48.Final]
   * </pre></code>
   */
  @Test
  @Ignore("Vectors get corrupted somehow")
  public void testV2ExplicitSortFailure() throws Exception {
    try {
      enableSchema(false);
      enableMultiScan();
      enableV3(false);
      // V2 fails on ragged columns, use consistent columns
      String tablePath = buildTable("explicitSortV2", multi1Contents, multi2FullContents);
      String sql = "SELECT id, name, gender FROM " + tablePath + " ORDER BY id";
      TupleMetadata expectedSchema = new SchemaBuilder()
          .add("id", MinorType.VARCHAR)
          .add("name", MinorType.VARCHAR)
          .add("gender", MinorType.VARCHAR)
          .buildSchema();
      RowSet expected = new RowSetBuilder(client.allocator(), expectedSchema)
          .addRow("1", "wilma", "female")
          .addRow("2", "fred", "male")
          .addRow("3", "barney", "NA")
          .addRow("4", "betty", "NA")
          .build();
      boolean sawError = false;
      for (int i = 0; i < 10; i++) {
        try {
          RowSet result = client.queryBuilder().sql(sql).rowSet();
          result.print();
          new RowSetComparison(expected).verifyAndClear(result);
        } catch (RpcException e) {
          assertTrue(e.getCause() instanceof UserRemoteException);
          sawError = true;
          break;
        }
      }
      expected.clear();
      assertTrue(sawError);
    } finally {
      resetV3();
      resetSchema();
      resetMultiScan();
    }
  }

  /**
   * Because V3 uses VARCHAR for missing columns, and handles ragged rows, there
   * is no vector corruption, and the sort operator sees a uniform schema, even
   * without a schema.
   * <p>
   * This and other tests enable multiple scan fragments, even for small files,
   * then run the test multiple times to generate the result set in different
   * orders (file1 first sometimes, file2 other times.)
   */
  @Test
  public void testV3ExplicitSort() throws Exception {
    try {
      enableSchema(false);
      enableMultiScan();
      enableV3(true);
      // V3 handles ragged columns
      String tablePath = buildTable("v3ExplictSort", raggedMulti1Contents, reordered2Contents);
      String sql = "SELECT id, name, gender FROM " + tablePath + " ORDER BY id";
      TupleMetadata expectedSchema = new SchemaBuilder()
          .add("id", MinorType.VARCHAR)
          .add("name", MinorType.VARCHAR)
          .add("gender", MinorType.VARCHAR)
          .buildSchema();
      RowSet expected = new RowSetBuilder(client.allocator(), expectedSchema)
          .addRow("1", "wilma", "female")
          .addRow("2", "fred", "male")
          .addRow("3", "barney", "")
          .addRow("4", "betty", "")
          .build();
      for (int i = 0; i < 10; i++) {
        RowSet result = client.queryBuilder().sql(sql).rowSet();
        new RowSetComparison(expected).verifyAndClear(result);
      }
      expected.clear();
    } finally {
      resetV3();
      resetSchema();
      resetMultiScan();
    }
  }

  /**
   * Adding a schema makes the data types more uniform, and fills in defaults
   * for missing columns. Note that the default is not applied (at least at
   * present) if a column is missing within a file that says it has the
   * column.
   */
  @Test
  public void testSchemaExplicitSort() throws Exception {
    try {
      enableSchema(true);
      enableMultiScan();
      enableV3(true);
      // V3 handles ragged columns
      String tablePath = buildTable("v3ExplictSort", raggedMulti1Contents, reordered2Contents);
      run(SCHEMA_SQL, tablePath);
      String sql = "SELECT id, name, gender FROM " + tablePath + " ORDER BY id";
      TupleMetadata expectedSchema = new SchemaBuilder()
          .add("id", MinorType.INT)
          .add("name", MinorType.VARCHAR)
          .add("gender", MinorType.VARCHAR)
          .buildSchema();
      RowSet expected = new RowSetBuilder(client.allocator(), expectedSchema)
          .addRow(1, "wilma", "female")
          .addRow(2, "fred", "male")
          .addRow(3, "barney", "NA")
          .addRow(4, "betty", "")
          .build();
      for (int i = 0; i < 10; i++) {
        RowSet result = client.queryBuilder().sql(sql).rowSet();
        new RowSetComparison(expected).verifyAndClear(result);
      }
      expected.clear();
    } finally {
      resetV3();
      resetSchema();
      resetMultiScan();
    }
  }

  /**
   * Test the case that a file does not contain a required column (in this case,
   * id in the third file.) There are two choices. 1) we could fail the query,
   * 2) we can muddle through as best we can. The scan framework chooses to
   * muddle through by assuming a default value of 0 for the missing int
   * column.
   * <p>
   * Inserts an ORDER BY to force a single batch in a known order. Assumes
   * the other ORDER BY tests pass.
   * <p>
   * This test shows that having consistent types is sufficient for the sort
   * operator to work; the DAG will include a project operator that reorders
   * the columns when produced by readers in different orders. (Column ordering
   * is more an abstract concept anyway in a columnar system such as Drill.)
   */
  @Test
  public void testMultiFileSchemaMissingCol() throws Exception {
    RowSet expected = null;
    try {
      enableV3(true);
      enableSchema(true);
      enableMultiScan();
      String tablePath = buildTable("schemaMissingCols", raggedMulti1Contents,
          reordered2Contents, multi3Contents);
      run(SCHEMA_SQL, tablePath);

      // Wildcard expands to union of schema + table. In this case
      // all table columns appear in the schema (though not all schema
      // columns appear in the table.)

      String sql = "SELECT id, `name`, `date`, gender, comment FROM " +
          tablePath + " ORDER BY id";
      TupleMetadata expectedSchema = new SchemaBuilder()
          .add("id", MinorType.INT)
          .add("name", MinorType.VARCHAR)
          .addNullable("date", MinorType.DATE)
          .add("gender", MinorType.VARCHAR)
          .add("comment", MinorType.VARCHAR)
          .buildSchema();
      expected = new RowSetBuilder(client.allocator(), expectedSchema)
          .addRow(0, "dino", new LocalDate(2018, 9, 1), "NA", "ABC")
          .addRow(1, "wilma", new LocalDate(2019, 1, 18), "female", "ABC")
          .addRow(2, "fred", new LocalDate(2019, 1, 19), "male", "ABC")
          .addRow(3, "barney", new LocalDate(2001, 1, 16), "NA", "ABC")
          .addRow(4, "betty", new LocalDate(2019, 5, 4), "", "ABC")
          .build();

      // Loop 10 times so that, as the two reader fragments read the two
      // files, we end up with (acceptable) races that read the files in
      // random order.

      for (int i = 0; i < 10; i++) {
        RowSet results = client.queryBuilder().sql(sql).rowSet();
        new RowSetComparison(expected).verifyAndClear(results);
      }
    } finally {
      expected.clear();
      resetV3();
      resetMultiScan();
      resetSchema();
    }
  }

  /**
   * Test lenient wildcard projection.
   * The schema contains all columns in the table; the schema ensures
   * a consistent schema regardless of file shape or read order. The sort
   * operator works because it sees the consistent schema, despite great
   * variation in inputs.
   */
  @Test
  public void testWildcardV3LenientSchema() throws Exception {
    String tableName = "wildcardLenientV3";
    String tablePath = buildTable(tableName, multi1Contents,
        reordered2Contents, nameOnlyContents);

    try {
      enableV3(true);
      enableSchema(true);
      run(SCHEMA_SQL, tablePath);
      String sql = "SELECT * FROM " + tablePath + "ORDER BY id";
      RowSet actual = client.queryBuilder().sql(sql).rowSet();

      TupleMetadata expectedSchema = new SchemaBuilder()
          .add("id", MinorType.INT)
          .addNullable("date", MinorType.DATE)
          .add("gender", MinorType.VARCHAR)
          .add("comment", MinorType.VARCHAR)
          .add("name", MinorType.VARCHAR)
          .buildSchema();
      RowSet expected = new RowSetBuilder(client.allocator(), expectedSchema)
          .addRow(0, null, "NA", "ABC", "dino")
          .addRow(1, new LocalDate(2019, 1, 18), "female", "ABC", "wilma")
          .addRow(2, new LocalDate(2019, 1, 19), "male", "ABC", "fred")
          .addRow(3, new LocalDate(2001, 1, 16), "NA", "ABC", "barney")
          .addRow(4, new LocalDate(2019, 5, 4), "NA", "ABC", "betty")
          .build();
      RowSetUtilities.verify(expected, actual);
    } finally {
      resetV3();
      resetSchema();
    }
  }

  /**
   * Test wildcard projection with a strict schema: only schema columns are
   * projected.
   */
  @Test
  public void testWildcardV3StrictSchema() throws Exception {
    String tableName = "wildcardStrictV3";
    String tablePath = buildTable(tableName, multi1Contents,
        reordered2Contents, nameOnlyContents);

    try {
      enableV3(true);
      enableSchema(true);
      String sql = SCHEMA_SQL +
          " PROPERTIES ('" + TupleMetadata.IS_STRICT_SCHEMA_PROP + "'='true')";
      run(sql, tablePath);
      sql = "SELECT * FROM " + tablePath + "ORDER BY id";
      RowSet actual = client.queryBuilder().sql(sql).rowSet();

      TupleMetadata expectedSchema = new SchemaBuilder()
          .add("id", MinorType.INT)
          .addNullable("date", MinorType.DATE)
          .add("gender", MinorType.VARCHAR)
          .add("comment", MinorType.VARCHAR)
          .buildSchema();
      RowSet expected = new RowSetBuilder(client.allocator(), expectedSchema)
          .addRow(0, null, "NA", "ABC")
          .addRow(1, new LocalDate(2019, 1, 18), "female", "ABC")
          .addRow(2, new LocalDate(2019, 1, 19), "male", "ABC")
          .addRow(3, new LocalDate(2001, 1, 16), "NA", "ABC")
          .addRow(4, new LocalDate(2019, 5, 4), "NA", "ABC")
          .build();
      RowSetUtilities.verify(expected, actual);
    } finally {
      resetV3();
      resetSchema();
    }
  }

  /**
   * Test a strict schema where it is needed most: in a scan with multiple
   * fragments, each of which sees a diffrent reader schema. The output schema
   * ensures that each scan independently reports the same schema, so that the
   * downstream sort operator gets a single consistent batch schema.
   */
  @Test
  public void testMultiFragmentStrictSchema() throws Exception {
    String tableName = "wildcardStrict2V3";
    String tablePath = buildTable(tableName, multi1Contents,
        reordered2Contents, nameOnlyContents);

    try {
      enableV3(true);
      enableMultiScan();
      enableSchema(true);
      String sql = SCHEMA_SQL +
          " PROPERTIES ('" + TupleMetadata.IS_STRICT_SCHEMA_PROP + "'='true')";
      run(sql, tablePath);
      sql = "SELECT * FROM " + tablePath + "ORDER BY id";
      RowSet actual = client.queryBuilder().sql(sql).rowSet();

      TupleMetadata expectedSchema = new SchemaBuilder()
          .add("id", MinorType.INT)
          .addNullable("date", MinorType.DATE)
          .add("gender", MinorType.VARCHAR)
          .add("comment", MinorType.VARCHAR)
          .buildSchema();
      RowSet expected = new RowSetBuilder(client.allocator(), expectedSchema)
          .addRow(0, null, "NA", "ABC")
          .addRow(1, new LocalDate(2019, 1, 18), "female", "ABC")
          .addRow(2, new LocalDate(2019, 1, 19), "male", "ABC")
          .addRow(3, new LocalDate(2001, 1, 16), "NA", "ABC")
          .addRow(4, new LocalDate(2019, 5, 4), "NA", "ABC")
          .build();
      RowSetUtilities.verify(expected, actual);
    } finally {
      resetV3();
      resetMultiScan();
      resetSchema();
    }
  }
}