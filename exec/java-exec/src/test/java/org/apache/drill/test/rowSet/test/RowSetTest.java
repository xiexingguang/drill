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
package org.apache.drill.test.rowSet.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;

import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.record.BatchSchema;
import org.apache.drill.exec.record.metadata.TupleMetadata;
import org.apache.drill.exec.vector.ValueVector;
import org.apache.drill.exec.vector.accessor.ArrayReader;
import org.apache.drill.exec.vector.accessor.ArrayWriter;
import org.apache.drill.exec.vector.accessor.ObjectType;
import org.apache.drill.exec.vector.accessor.ScalarElementReader;
import org.apache.drill.exec.vector.accessor.ScalarReader;
import org.apache.drill.exec.vector.accessor.ScalarWriter;
import org.apache.drill.exec.vector.accessor.TupleReader;
import org.apache.drill.exec.vector.accessor.TupleWriter;
import org.apache.drill.exec.vector.accessor.ValueType;
import org.apache.drill.exec.vector.complex.MapVector;
import org.apache.drill.exec.vector.complex.RepeatedMapVector;
import org.apache.drill.test.SubOperatorTest;
import org.apache.drill.test.rowSet.RowSet.ExtendableRowSet;
import org.apache.drill.test.rowSet.RowSet.SingleRowSet;
import org.apache.drill.test.rowSet.RowSetComparison;
import org.apache.drill.test.rowSet.RowSetReader;
import org.apache.drill.test.rowSet.RowSetWriter;
import org.apache.drill.test.rowSet.SchemaBuilder;
import org.junit.Test;

/**
 * Test row sets. Since row sets are a thin wrapper around vectors,
 * readers and writers, this is also a test of those constructs.
 * <p>
 * Tests basic protocol of the writers: <pre><code>
 * row : tuple
 * tuple : column *
 * column : scalar obj | array obj | tuple obj
 * scalar obj : scalar
 * array obj : array writer
 * array writer : element
 * element : column
 * tuple obj : tuple</code></pre>
 */

public class RowSetTest extends SubOperatorTest {

  /**
   * Test the simplest constructs: a row with top-level scalar
   * columns.
   * <p>
   * The focus here is the structure of the readers and writers, along
   * with the row set loader and verifier that use those constructs.
   * That is, while this test uses the int vector, this test is not
   * focused on that vector.
   */

  @Test
  public void testScalarStructure() {
    TupleMetadata schema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .buildSchema();
    ExtendableRowSet rowSet = fixture.rowSet(schema);
    RowSetWriter writer = rowSet.writer();

    // Required Int
    // Verify the invariants of the "full" and "simple" access paths

    assertEquals(ObjectType.SCALAR, writer.column("a").type());
    assertSame(writer.column("a"), writer.column(0));
    assertSame(writer.scalar("a"), writer.scalar(0));
    assertSame(writer.column("a").scalar(), writer.scalar("a"));
    assertSame(writer.column(0).scalar(), writer.scalar(0));
    assertEquals(ValueType.INTEGER, writer.scalar(0).valueType());

    // Sanity checks

    try {
      writer.column(0).array();
      fail();
    } catch (UnsupportedOperationException e) {
      // Expected
    }
    try {
      writer.column(0).tuple();
      fail();
    } catch (UnsupportedOperationException e) {
      // Expected
    }

    // Test the various ways to get at the scalar writer.

    writer.column("a").scalar().setInt(10);
    writer.save();
    writer.scalar("a").setInt(20);
    writer.save();
    writer.column(0).scalar().setInt(30);
    writer.save();
    writer.scalar(0).setInt(40);
    writer.save();

    // Finish the row set and get a reader.

    SingleRowSet actual = writer.done();
    RowSetReader reader = actual.reader();

    // Verify invariants

    assertEquals(ObjectType.SCALAR, reader.column(0).type());
    assertSame(reader.column("a"), reader.column(0));
    assertSame(reader.scalar("a"), reader.scalar(0));
    assertSame(reader.column("a").scalar(), reader.scalar("a"));
    assertSame(reader.column(0).scalar(), reader.scalar(0));
    assertEquals(ValueType.INTEGER, reader.scalar(0).valueType());

    // Test various accessors: full and simple

    assertTrue(reader.next());
    assertEquals(10, reader.column("a").scalar().getInt());
    assertTrue(reader.next());
    assertEquals(20, reader.scalar("a").getInt());
    assertTrue(reader.next());
    assertEquals(30, reader.column(0).scalar().getInt());
    assertTrue(reader.next());
    assertEquals(40, reader.scalar(0).getInt());
    assertFalse(reader.next());

    // Test the above again via the writer and reader
    // utility classes.

    SingleRowSet expected = fixture.rowSetBuilder(schema)
        .addRow(10)
        .addRow(20)
        .addRow(30)
        .addRow(40)
        .build();
    new RowSetComparison(expected).verifyAndClearAll(actual);
  }

  /**
   * Test a record with a top level array. The focus here is on the
   * scalar array structure.
   *
   * @throws VectorOverflowException should never occur
   */

  @Test
  public void testScalarArrayStructure() {
    TupleMetadata schema = new SchemaBuilder()
        .addArray("a", MinorType.INT)
        .buildSchema();
    ExtendableRowSet rowSet = fixture.rowSet(schema);
    RowSetWriter writer = rowSet.writer();

    // Repeated Int
    // Verify the invariants of the "full" and "simple" access paths

    assertEquals(ObjectType.ARRAY, writer.column("a").type());

    assertSame(writer.column("a"), writer.column(0));
    assertSame(writer.array("a"), writer.array(0));
    assertSame(writer.column("a").array(), writer.array("a"));
    assertSame(writer.column(0).array(), writer.array(0));

    assertEquals(ObjectType.SCALAR, writer.column("a").array().entry().type());
    assertEquals(ObjectType.SCALAR, writer.column("a").array().entryType());
    assertSame(writer.array(0).entry().scalar(), writer.array(0).scalar());
    assertEquals(ValueType.INTEGER, writer.array(0).scalar().valueType());

    // Sanity checks

    try {
      writer.column(0).scalar();
      fail();
    } catch (UnsupportedOperationException e) {
      // Expected
    }
    try {
      writer.column(0).tuple();
      fail();
    } catch (UnsupportedOperationException e) {
      // Expected
    }

    // Write some data

    ScalarWriter intWriter = writer.array("a").scalar();
    intWriter.setInt(10);
    intWriter.setInt(11);
    writer.save();
    intWriter.setInt(20);
    intWriter.setInt(21);
    intWriter.setInt(22);
    writer.save();
    intWriter.setInt(30);
    writer.save();
    intWriter.setInt(40);
    intWriter.setInt(41);
    writer.save();

    // Finish the row set and get a reader.

    SingleRowSet actual = writer.done();
    RowSetReader reader = actual.reader();

    // Verify the invariants of the "full" and "simple" access paths

    assertEquals(ObjectType.ARRAY, writer.column("a").type());

    assertSame(reader.column("a"), reader.column(0));
    assertSame(reader.array("a"), reader.array(0));
    assertSame(reader.column("a").array(), reader.array("a"));
    assertSame(reader.column(0).array(), reader.array(0));

    assertEquals(ObjectType.SCALAR, reader.column("a").array().entryType());
    assertEquals(ValueType.INTEGER, reader.array(0).elements().valueType());

    // Read and verify the rows

    ScalarElementReader intReader = reader.array(0).elements();
    assertTrue(reader.next());
    assertEquals(2, intReader.size());
    assertEquals(10, intReader.getInt(0));
    assertEquals(11, intReader.getInt(1));
    assertTrue(reader.next());
    assertEquals(3, intReader.size());
    assertEquals(20, intReader.getInt(0));
    assertEquals(21, intReader.getInt(1));
    assertEquals(22, intReader.getInt(2));
    assertTrue(reader.next());
    assertEquals(1, intReader.size());
    assertEquals(30, intReader.getInt(0));
    assertTrue(reader.next());
    assertEquals(2, intReader.size());
    assertEquals(40, intReader.getInt(0));
    assertEquals(41, intReader.getInt(1));
    assertFalse(reader.next());

    // Test the above again via the writer and reader
    // utility classes.

    SingleRowSet expected = fixture.rowSetBuilder(schema)
        .addSingleCol(new int[] {10, 11})
        .addSingleCol(new int[] {20, 21, 22})
        .addSingleCol(new int[] {30})
        .addSingleCol(new int[] {40, 41})
        .build();
    new RowSetComparison(expected)
      .verifyAndClearAll(actual);
  }

  /**
   * Test a simple map structure at the top level of a row.
   *
   * @throws VectorOverflowException should never occur
   */

  @Test
  public void testMapStructure() {
    TupleMetadata schema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .addMap("m")
          .addArray("b", MinorType.INT)
          .buildMap()
        .buildSchema();
    ExtendableRowSet rowSet = fixture.rowSet(schema);
    RowSetWriter writer = rowSet.writer();

    // Map and Int
    // Test Invariants

    assertEquals(ObjectType.SCALAR, writer.column("a").type());
    assertEquals(ObjectType.SCALAR, writer.column(0).type());
    assertEquals(ObjectType.TUPLE, writer.column("m").type());
    assertEquals(ObjectType.TUPLE, writer.column(1).type());
    assertSame(writer.column(1).tuple(), writer.tuple(1));

    TupleWriter mapWriter = writer.column(1).tuple();
    assertEquals(ObjectType.SCALAR, mapWriter.column("b").array().entry().type());
    assertEquals(ObjectType.SCALAR, mapWriter.column("b").array().entryType());

    ScalarWriter aWriter = writer.column("a").scalar();
    ScalarWriter bWriter = writer.column("m").tuple().column("b").array().entry().scalar();
    assertSame(bWriter, writer.tuple(1).array(0).scalar());
    assertEquals(ValueType.INTEGER, bWriter.valueType());

    // Sanity checks

    try {
      writer.column(1).scalar();
      fail();
    } catch (UnsupportedOperationException e) {
      // Expected
    }
    try {
      writer.column(1).array();
      fail();
    } catch (UnsupportedOperationException e) {
      // Expected
    }

    // Write data

    aWriter.setInt(10);
    bWriter.setInt(11);
    bWriter.setInt(12);
    writer.save();
    aWriter.setInt(20);
    bWriter.setInt(21);
    bWriter.setInt(22);
    writer.save();
    aWriter.setInt(30);
    bWriter.setInt(31);
    bWriter.setInt(32);
    writer.save();

    // Finish the row set and get a reader.

    SingleRowSet actual = writer.done();
    RowSetReader reader = actual.reader();

    assertEquals(ObjectType.SCALAR, reader.column("a").type());
    assertEquals(ObjectType.SCALAR, reader.column(0).type());
    assertEquals(ObjectType.TUPLE, reader.column("m").type());
    assertEquals(ObjectType.TUPLE, reader.column(1).type());
    assertSame(reader.column(1).tuple(), reader.tuple(1));

    ScalarReader aReader = reader.column(0).scalar();
    TupleReader mReader = reader.column(1).tuple();
    assertEquals(ObjectType.SCALAR, mReader.column("b").array().entryType());
    ScalarElementReader bReader = mReader.column(0).elements();
    assertEquals(ValueType.INTEGER, bReader.valueType());

    assertTrue(reader.next());
    assertEquals(10, aReader.getInt());
    assertEquals(11, bReader.getInt(0));
    assertEquals(12, bReader.getInt(1));
    assertTrue(reader.next());
    assertEquals(20, aReader.getInt());
    assertEquals(21, bReader.getInt(0));
    assertEquals(22, bReader.getInt(1));
    assertTrue(reader.next());
    assertEquals(30, aReader.getInt());
    assertEquals(31, bReader.getInt(0));
    assertEquals(32, bReader.getInt(1));
    assertFalse(reader.next());

    // Verify that the map accessor's value count was set.

    @SuppressWarnings("resource")
    MapVector mapVector = (MapVector) actual.container().getValueVector(1).getValueVector();
    assertEquals(actual.rowCount(), mapVector.getAccessor().getValueCount());

    SingleRowSet expected = fixture.rowSetBuilder(schema)
        .addRow(10, new Object[] {new int[] {11, 12}})
        .addRow(20, new Object[] {new int[] {21, 22}})
        .addRow(30, new Object[] {new int[] {31, 32}})
        .build();
    new RowSetComparison(expected)
      .verifyAndClearAll(actual);
  }

  @Test
  public void testRepeatedMapStructure() {
    TupleMetadata schema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .addMapArray("m")
          .add("b", MinorType.INT)
          .add("c", MinorType.INT)
          .buildMap()
        .buildSchema();
    ExtendableRowSet rowSet = fixture.rowSet(schema);
    RowSetWriter writer = rowSet.writer();

    // Map and Int
    // Pick out components and lightly test. (Assumes structure
    // tested earlier is still valid, so no need to exhaustively
    // test again.)

    assertEquals(ObjectType.SCALAR, writer.column("a").type());
    assertEquals(ObjectType.ARRAY, writer.column("m").type());

    ArrayWriter maWriter = writer.column(1).array();
    assertEquals(ObjectType.TUPLE, maWriter.entryType());

    TupleWriter mapWriter = maWriter.tuple();
    assertEquals(ObjectType.SCALAR, mapWriter.column("b").type());
    assertEquals(ObjectType.SCALAR, mapWriter.column("c").type());

    ScalarWriter aWriter = writer.column("a").scalar();
    ScalarWriter bWriter = mapWriter.scalar("b");
    ScalarWriter cWriter = mapWriter.scalar("c");
    assertEquals(ValueType.INTEGER, aWriter.valueType());
    assertEquals(ValueType.INTEGER, bWriter.valueType());
    assertEquals(ValueType.INTEGER, cWriter.valueType());

    // Write data

    aWriter.setInt(10);
    bWriter.setInt(101);
    cWriter.setInt(102);
    maWriter.save(); // Advance to next array position
    bWriter.setInt(111);
    cWriter.setInt(112);
    maWriter.save();
    writer.save();

    aWriter.setInt(20);
    bWriter.setInt(201);
    cWriter.setInt(202);
    maWriter.save();
    bWriter.setInt(211);
    cWriter.setInt(212);
    maWriter.save();
    writer.save();

    aWriter.setInt(30);
    bWriter.setInt(301);
    cWriter.setInt(302);
    maWriter.save();
    bWriter.setInt(311);
    cWriter.setInt(312);
    maWriter.save();
    writer.save();

    // Finish the row set and get a reader.

    SingleRowSet actual = writer.done();
    RowSetReader reader = actual.reader();

    // Verify reader structure

    assertEquals(ObjectType.SCALAR, reader.column("a").type());
    assertEquals(ObjectType.ARRAY, reader.column("m").type());

    ArrayReader maReader = reader.column(1).array();
    assertEquals(ObjectType.TUPLE, maReader.entryType());

    TupleReader mapReader = maReader.tuple();
    assertEquals(ObjectType.SCALAR, mapReader.column("b").type());
    assertEquals(ObjectType.SCALAR, mapReader.column("c").type());

    ScalarReader aReader = reader.column("a").scalar();
    ScalarReader bReader = mapReader.scalar("b");
    ScalarReader cReader = mapReader.scalar("c");
    assertEquals(ValueType.INTEGER, aReader.valueType());
    assertEquals(ValueType.INTEGER, bReader.valueType());
    assertEquals(ValueType.INTEGER, cReader.valueType());

    // Row 1: use index accessors

    assertTrue(reader.next());
    assertEquals(10, aReader.getInt());
    TupleReader ixReader = maReader.tuple(0);
    assertEquals(101, ixReader.scalar(0).getInt());
    assertEquals(102, ixReader.scalar(1).getInt());
    ixReader = maReader.tuple(1);
    assertEquals(111, ixReader.scalar(0).getInt());
    assertEquals(112, ixReader.scalar(1).getInt());

    // Row 2: use common accessor with explicit positioning,
    // but access scalars through the map reader.

    assertTrue(reader.next());
    assertEquals(20, aReader.getInt());
    maReader.setPosn(0);
    assertEquals(201, mapReader.scalar(0).getInt());
    assertEquals(202, mapReader.scalar(1).getInt());
    maReader.setPosn(1);
    assertEquals(211, mapReader.scalar(0).getInt());
    assertEquals(212, mapReader.scalar(1).getInt());

    // Row 3: use common accessor for scalars

    assertTrue(reader.next());
    assertEquals(30, aReader.getInt());
    maReader.setPosn(0);
    assertEquals(301, bReader.getInt());
    assertEquals(302, cReader.getInt());
    maReader.setPosn(1);
    assertEquals(311, bReader.getInt());
    assertEquals(312, cReader.getInt());

    assertFalse(reader.next());

    // Verify that the map accessor's value count was set.

    @SuppressWarnings("resource")
    RepeatedMapVector mapVector = (RepeatedMapVector) actual.container().getValueVector(1).getValueVector();
    assertEquals(3, mapVector.getAccessor().getValueCount());

    // Verify the readers and writers again using the testing tools.

    SingleRowSet expected = fixture.rowSetBuilder(schema)
        .addRow(10, new Object[] {new Object[] {101, 102}, new Object[] {111, 112}})
        .addRow(20, new Object[] {new Object[] {201, 202}, new Object[] {211, 212}})
        .addRow(30, new Object[] {new Object[] {301, 302}, new Object[] {311, 312}})
        .build();
    new RowSetComparison(expected)
      .verifyAndClearAll(actual);
  }

  /**
   * Test an array of ints (as an example fixed-width type)
   * at the top level of a schema.
   */

  @Test
  public void testTopFixedWidthArray() {
    BatchSchema batchSchema = new SchemaBuilder()
        .add("c", MinorType.INT)
        .addArray("a", MinorType.INT)
        .build();

    ExtendableRowSet rs1 = fixture.rowSet(batchSchema);
    RowSetWriter writer = rs1.writer();
    writer.scalar(0).setInt(10);
    ScalarWriter array = writer.array(1).scalar();
    array.setInt(100);
    array.setInt(110);
    writer.save();
    writer.scalar(0).setInt(20);
    array.setInt(200);
    array.setInt(120);
    array.setInt(220);
    writer.save();
    writer.scalar(0).setInt(30);
    writer.save();

    SingleRowSet result = writer.done();

    RowSetReader reader = result.reader();
    assertTrue(reader.next());
    assertEquals(10, reader.scalar(0).getInt());
    ScalarElementReader arrayReader = reader.array(1).elements();
    assertEquals(2, arrayReader.size());
    assertEquals(100, arrayReader.getInt(0));
    assertEquals(110, arrayReader.getInt(1));
    assertTrue(reader.next());
    assertEquals(20, reader.scalar(0).getInt());
    assertEquals(3, arrayReader.size());
    assertEquals(200, arrayReader.getInt(0));
    assertEquals(120, arrayReader.getInt(1));
    assertEquals(220, arrayReader.getInt(2));
    assertTrue(reader.next());
    assertEquals(30, reader.scalar(0).getInt());
    assertEquals(0, arrayReader.size());
    assertFalse(reader.next());

    SingleRowSet rs2 = fixture.rowSetBuilder(batchSchema)
      .addRow(10, new int[] {100, 110})
      .addRow(20, new int[] {200, 120, 220})
      .addRow(30, null)
      .build();

    new RowSetComparison(rs1)
      .verifyAndClearAll(rs2);
  }

  /**
   * Test filling a row set up to the maximum number of rows.
   * Values are small enough to prevent filling to the
   * maximum buffer size.
   */

  @Test
  public void testRowBounds() {
    BatchSchema batchSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .build();

    ExtendableRowSet rs = fixture.rowSet(batchSchema);
    RowSetWriter writer = rs.writer();
    int count = 0;
    while (! writer.isFull()) {
      writer.scalar(0).setInt(count++);
      writer.save();
    }
    writer.done();

    assertEquals(ValueVector.MAX_ROW_COUNT, count);
    // The writer index points past the writable area.
    // But, this is fine, the valid() method says we can't
    // write at this location.
    assertEquals(ValueVector.MAX_ROW_COUNT, writer.rowIndex());
    assertEquals(ValueVector.MAX_ROW_COUNT, rs.rowCount());
    rs.clear();
  }

  /**
   * Test filling a row set up to the maximum vector size.
   * Values in the first column are small enough to prevent filling to the
   * maximum buffer size, but values in the second column
   * will reach maximum buffer size before maximum row size.
   * The result should be the number of rows that fit, with the
   * partial last row not counting. (A complete application would
   * reload the partial row into a new row set.)
   */

  @Test
  public void testBufferBounds() {
    BatchSchema batchSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .build();

    String varCharValue;
    try {
      byte rawValue[] = new byte[512];
      Arrays.fill(rawValue, (byte) 'X');
      varCharValue = new String(rawValue, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }

    ExtendableRowSet rs = fixture.rowSet(batchSchema);
    RowSetWriter writer = rs.writer();
    int count = 0;
    try {

      // Test overflow. This is not a typical use case: don't want to
      // hit overflow without overflow handling. In this case, we throw
      // away the last row because the row set abstraction does not
      // implement vector overflow other than throwing an exception.

      for (;;) {
        writer.scalar(0).setInt(count);
        writer.scalar(1).setString(varCharValue);

        // Won't get here on overflow.
        writer.save();
        count++;
      }
    } catch (IndexOutOfBoundsException e) {
      assertTrue(e.getMessage().contains("Overflow"));
    }
    writer.done();

    assertTrue(count < ValueVector.MAX_ROW_COUNT);
    assertEquals(count, writer.rowIndex());
    assertEquals(count, rs.rowCount());
    rs.clear();
  }
}
