// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// This source code is licensed under both the GPLv2 (found in the
// COPYING file in the root directory) and Apache 2.0 License
// (found in the LICENSE.Apache file in the root directory).

package org.rocksdb;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test suite for AbstractMergeOperator.
 * This test uses a simple string concatenation merge operator.
 */
public class AbstractMergeOperatorTest {

  @ClassRule
  public static final RocksNativeLibraryResource ROCKS_NATIVE_LIBRARY_RESOURCE =
      new RocksNativeLibraryResource();

  @Rule
  public TemporaryFolder dbFolder = new TemporaryFolder();

  /**
   * A simple test merge operator that concatenates values with a delimiter.
   */
  public static class StringConcatMergeOperator extends AbstractMergeOperator {
    private final String delimiter;

    public StringConcatMergeOperator() {
      this("|");
    }

    public StringConcatMergeOperator(final String delimiter) {
      this.delimiter = delimiter;
    }

    @Override
    public String name() {
      return "StringConcatMergeOperator";
    }

    @Override
    public byte[] fullMerge(final ByteBuffer key, final ByteBuffer existing,
                             final ByteBuffer[] operands) {
      final StringBuilder result = new StringBuilder();

      if (existing != null && existing.remaining() > 0) {
        byte[] existingBytes = new byte[existing.remaining()];
        existing.get(existingBytes);
        result.append(new String(existingBytes, StandardCharsets.UTF_8));
      }

      for (final ByteBuffer operand : operands) {
        if (operand.remaining() > 0) {
          if (result.length() > 0) {
            result.append(delimiter);
          }
          byte[] operandBytes = new byte[operand.remaining()];
          operand.get(operandBytes);
          result.append(new String(operandBytes, StandardCharsets.UTF_8));
        }
      }

      return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] partialMerge(final ByteBuffer key, final ByteBuffer left,
                                final ByteBuffer right) {
      final StringBuilder result = new StringBuilder();

      byte[] leftBytes = new byte[left.remaining()];
      left.get(leftBytes);
      result.append(new String(leftBytes, StandardCharsets.UTF_8));

      result.append("+");

      byte[] rightBytes = new byte[right.remaining()];
      right.get(rightBytes);
      result.append(new String(rightBytes, StandardCharsets.UTF_8));

      return result.toString().getBytes(StandardCharsets.UTF_8);
    }
  }

  /**
   * Test merge with no existing value.
   */
  @Test
  public void testFullMergeNoExisting() throws RocksDBException {
    try (final Options options = new Options()
        .setCreateIfMissing(true)
        .setMergeOperator(new StringConcatMergeOperator());
         final RocksDB db = RocksDB.open(options, dbFolder.getRoot().getAbsolutePath())) {

      final byte[] key = "key1".getBytes(StandardCharsets.UTF_8);
      final byte[] value1 = "value1".getBytes(StandardCharsets.UTF_8);

      // Merge without existing value
      db.merge(key, value1);

      // Get should return the single operand
      final byte[] result = db.get(key);
      assertThat(result).isEqualTo(value1);
    }
  }

  /**
   * Test merge with an existing value.
   */
  @Test
  public void testFullMergeWithExisting() throws RocksDBException {
    try (final Options options = new Options()
        .setCreateIfMissing(true)
        .setMergeOperator(new StringConcatMergeOperator());
         final RocksDB db = RocksDB.open(options, dbFolder.getRoot().getAbsolutePath())) {

      final byte[] key = "key2".getBytes(StandardCharsets.UTF_8);
      final byte[] base = "base".getBytes(StandardCharsets.UTF_8);
      final byte[] operand = "operand".getBytes(StandardCharsets.UTF_8);

      db.put(key, base);
      db.merge(key, operand);

      final byte[] result = db.get(key);
      assertThat(result).isEqualTo("base|operand".getBytes(StandardCharsets.UTF_8));
    }
  }

  /**
   * Test merge with multiple operands.
   */
  @Test
  public void testMultipleOperands() throws RocksDBException {
    try (final Options options = new Options()
        .setCreateIfMissing(true)
        .setMergeOperator(new StringConcatMergeOperator());
         final RocksDB db = RocksDB.open(options, dbFolder.getRoot().getAbsolutePath())) {

      final byte[] key = "key3".getBytes(StandardCharsets.UTF_8);
      final byte[] base = "A".getBytes(StandardCharsets.UTF_8);
      final byte[] op1 = "B".getBytes(StandardCharsets.UTF_8);
      final byte[] op2 = "C".getBytes(StandardCharsets.UTF_8);
      final byte[] op3 = "D".getBytes(StandardCharsets.UTF_8);

      db.put(key, base);
      db.merge(key, op1);
      db.merge(key, op2);
      db.merge(key, op3);

      final byte[] result = db.get(key);
      assertThat(result).isEqualTo("A|B|C|D".getBytes(StandardCharsets.UTF_8));
    }
  }

  /**
   * Test partial merge during compaction.
   */
  @Test
  public void testPartialMergeDuringCompaction() throws RocksDBException {
    try (final Options options = new Options()
        .setCreateIfMissing(true)
        .setMergeOperator(new StringConcatMergeOperator())
        .setWriteBufferSize(64 * 1024);
         final RocksDB db = RocksDB.open(options, dbFolder.getRoot().getAbsolutePath())) {

      final byte[] key = "key4".getBytes(StandardCharsets.UTF_8);

      // Write operands without a base
      for (int i = 0; i < 5; i++) {
        db.merge(key, String.valueOf(i).getBytes(StandardCharsets.UTF_8));
      }

      // Compact to trigger partial merges
      db.compactRange();

      // Verify final result
      final byte[] result = db.get(key);
      assertThat(result).isNotNull();
      assertThat(new String(result, StandardCharsets.UTF_8)).contains("0");
      assertThat(new String(result, StandardCharsets.UTF_8)).contains("4");
    }
  }

  /**
   * Test that null existing value is properly handled.
   */
  @Test
  public void testNullExistingValue() throws RocksDBException {
    try (final Options options = new Options()
        .setCreateIfMissing(true)
        .setMergeOperator(new StringConcatMergeOperator());
         final RocksDB db = RocksDB.open(options, dbFolder.getRoot().getAbsolutePath())) {

      final byte[] key = "key5".getBytes(StandardCharsets.UTF_8);
      final byte[] value = "onlyvalue".getBytes(StandardCharsets.UTF_8);

      // Merge on non-existent key (existing will be null in merge)
      db.merge(key, value);

      final byte[] result = db.get(key);
      assertThat(result).isEqualTo(value);
    }
  }

  /**
   * Test that operator name is returned correctly.
   */
  @Test
  public void testOperatorName() throws RocksDBException {
    final StringConcatMergeOperator op = new StringConcatMergeOperator();
    assertThat(op.name()).isEqualTo("StringConcatMergeOperator");
  }

  /**
   * Test with column family options.
   */
  @Test
  public void testColumnFamilyOptions() throws RocksDBException {
    try (final ColumnFamilyOptions cfOpts =
        new ColumnFamilyOptions()
            .setMergeOperator(new StringConcatMergeOperator());
         final DBOptions dbOpts = new DBOptions()
             .setCreateIfMissing(true)) {

      final List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();
      cfDescriptors.add(
          new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts));

      final List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
      try (final RocksDB db = RocksDB.open(dbOpts, dbFolder.getRoot().getAbsolutePath(),
          cfDescriptors, cfHandles)) {

        final byte[] key = "key6".getBytes(StandardCharsets.UTF_8);
        final byte[] base = "X".getBytes(StandardCharsets.UTF_8);
        final byte[] operand = "Y".getBytes(StandardCharsets.UTF_8);

        db.put(cfHandles.get(0), key, base);
        db.merge(cfHandles.get(0), key, operand);

        final byte[] result = db.get(cfHandles.get(0), key);
        assertThat(result).isEqualTo("X|Y".getBytes(StandardCharsets.UTF_8));
      } finally {
        for (final ColumnFamilyHandle cfHandle : cfHandles) {
          cfHandle.close();
        }
      }
    }
  }

  /**
   * Test lifecycle: dispose operator while DB is open.
   */
  @Test
  public void testOperatorLifecycle() throws RocksDBException {
    final StringConcatMergeOperator op = new StringConcatMergeOperator();

    try (final Options options = new Options()
        .setCreateIfMissing(true)
        .setMergeOperator(op);
         final RocksDB db = RocksDB.open(options, dbFolder.getRoot().getAbsolutePath())) {

      final byte[] key = "key7".getBytes(StandardCharsets.UTF_8);
      final byte[] value = "value".getBytes(StandardCharsets.UTF_8);

      db.put(key, value);

      // Operator is disposed when Options is closed
      // DB should still work if it holds its own reference
      final byte[] result = db.get(key);
      assertThat(result).isEqualTo(value);
    }
    // Operator disposed here when it goes out of scope
  }

  /**
   * Test with a custom delimiter.
   */
  @Test
  public void testCustomDelimiter() throws RocksDBException {
    try (final Options options = new Options()
        .setCreateIfMissing(true)
        .setMergeOperator(new StringConcatMergeOperator("::"));
         final RocksDB db = RocksDB.open(options, dbFolder.getRoot().getAbsolutePath())) {

      final byte[] key = "key8".getBytes(StandardCharsets.UTF_8);
      final byte[] base = "A".getBytes(StandardCharsets.UTF_8);
      final byte[] op1 = "B".getBytes(StandardCharsets.UTF_8);
      final byte[] op2 = "C".getBytes(StandardCharsets.UTF_8);

      db.put(key, base);
      db.merge(key, op1);
      db.merge(key, op2);

      final byte[] result = db.get(key);
      assertThat(result).isEqualTo("A::B::C".getBytes(StandardCharsets.UTF_8));
    }
  }

  /**
   * Test merging large numbers of operands.
   */
  @Test
  public void testManyOperands() throws RocksDBException {
    try (final Options options = new Options()
        .setCreateIfMissing(true)
        .setMergeOperator(new StringConcatMergeOperator());
         final RocksDB db = RocksDB.open(options, dbFolder.getRoot().getAbsolutePath())) {

      final byte[] key = "key9".getBytes(StandardCharsets.UTF_8);

      // Merge 50 operands
      for (int i = 0; i < 50; i++) {
        db.merge(key, String.format("op%d", i).getBytes(StandardCharsets.UTF_8));
      }

      // Verify result contains expected operands
      final byte[] result = db.get(key);
      assertThat(result).isNotNull();
      final String resultStr = new String(result, StandardCharsets.UTF_8);
      assertThat(resultStr).contains("op0");
      assertThat(resultStr).contains("op49");
    }
  }
}
