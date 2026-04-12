// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// This source code is licensed under both the GPLv2 (found in the
// COPYING file in the root directory) and Apache 2.0 License
// (found in the LICENSE.Apache file in the root directory).

package org.rocksdb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test suite for AbstractAssociativeMergeOperator.
 *
 * Uses two different example operators:
 * <ul>
 *   <li>{@link LongAddMergeOperator} - adds long values (counter use-case)</li>
 *   <li>{@link StringAppendMergeOperator} - appends strings with a delimiter</li>
 * </ul>
 */
public class AbstractAssociativeMergeOperatorTest {

  @ClassRule
  public static final RocksNativeLibraryResource ROCKS_NATIVE_LIBRARY_RESOURCE =
      new RocksNativeLibraryResource();

  @Rule
  public TemporaryFolder dbFolder = new TemporaryFolder();

  // Test operators

  /**
   * Associative merge operator that treats values as big-endian encoded longs
   * and adds them. If the existing value is null (first merge), treats it as 0.
   */
  public static class LongAddMergeOperator extends AbstractAssociativeMergeOperator {

    @Override
    public String name() {
      return "LongAddMergeOperator";
    }

    @Override
    public int merge(final ByteBuffer key, final ByteBuffer existing,
                     final ByteBuffer value, final ByteBuffer output) {
      final long existingLong = existing == null ? 0L : existing.getLong();
      final long delta = value.getLong();
      output.order(ByteOrder.BIG_ENDIAN).putLong(existingLong + delta);
      return Long.BYTES;
    }
  }

  /**
   * Associative merge operator that appends string values with a delimiter.
   */
  public static class StringAppendMergeOperator extends AbstractAssociativeMergeOperator {
    private final String delimiter;

    public StringAppendMergeOperator() {
      this("|");
    }

    public StringAppendMergeOperator(final String delimiter) {
      this.delimiter = delimiter;
    }

    @Override
    public String name() {
      return "StringAppendMergeOperator";
    }

    @Override
    public int merge(final ByteBuffer key, final ByteBuffer existing,
                     final ByteBuffer value, final ByteBuffer output) {
      if (existing != null && existing.remaining() > 0) {
        final byte[] existingBytes = new byte[existing.remaining()];
        existing.get(existingBytes);
        output.put(existingBytes);
        output.put(delimiter.getBytes(StandardCharsets.UTF_8));
      }
      final byte[] valueBytes = new byte[value.remaining()];
      value.get(valueBytes);
      output.put(valueBytes);
      return output.position();
    }
  }

  // Helpers

  private static byte[] longToBytes(final long v) {
    final byte[] b = new byte[Long.BYTES];
    ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).putLong(v);
    return b;
  }

  private static long bytesToLong(final byte[] b) {
    return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getLong();
  }

  // Tests

  /**
   * Merge with no existing value (null existing) should return the single operand result.
   */
  @Test
  public void testMergeNoExistingValue() throws RocksDBException {
    try (final Options options = new Options()
        .setCreateIfMissing(true)
        .setMergeOperator(new LongAddMergeOperator());
         final RocksDB db = RocksDB.open(options, dbFolder.getRoot().getAbsolutePath())) {

      final byte[] key = "counter".getBytes(StandardCharsets.UTF_8);

      db.merge(key, longToBytes(42L));

      final byte[] result = db.get(key);
      assertThat(result).isNotNull();
      assertThat(bytesToLong(result)).isEqualTo(42L);
    }
  }

  /**
   * Merge on top of an existing put value.
   */
  @Test
  public void testMergeWithExistingValue() throws RocksDBException {
    try (final Options options = new Options()
        .setCreateIfMissing(true)
        .setMergeOperator(new LongAddMergeOperator());
         final RocksDB db = RocksDB.open(options, dbFolder.getRoot().getAbsolutePath())) {

      final byte[] key = "counter".getBytes(StandardCharsets.UTF_8);

      db.put(key, longToBytes(100L));
      db.merge(key, longToBytes(23L));

      final byte[] result = db.get(key);
      assertThat(result).isNotNull();
      assertThat(bytesToLong(result)).isEqualTo(123L);
    }
  }

  /**
   * Multiple consecutive merge calls accumulate correctly.
   */
  @Test
  public void testMultipleMerges() throws RocksDBException {
    try (final Options options = new Options()
        .setCreateIfMissing(true)
        .setMergeOperator(new LongAddMergeOperator());
         final RocksDB db = RocksDB.open(options, dbFolder.getRoot().getAbsolutePath())) {

      final byte[] key = "counter".getBytes(StandardCharsets.UTF_8);

      for (int i = 1; i <= 10; i++) {
        db.merge(key, longToBytes(i));
      }

      // Sum of 1..10 = 55
      final byte[] result = db.get(key);
      assertThat(result).isNotNull();
      assertThat(bytesToLong(result)).isEqualTo(55L);
    }
  }

  /**
   * After compaction, the accumulated value should still be correct.
   */
  @Test
  public void testMergeAfterCompaction() throws RocksDBException {
    try (final Options options = new Options()
        .setCreateIfMissing(true)
        .setMergeOperator(new LongAddMergeOperator());
         final RocksDB db = RocksDB.open(options, dbFolder.getRoot().getAbsolutePath())) {

      final byte[] key = "counter".getBytes(StandardCharsets.UTF_8);

      db.put(key, longToBytes(0L));
      for (int i = 1; i <= 5; i++) {
        db.merge(key, longToBytes(i));
      }

      db.compactRange();

      final byte[] result = db.get(key);
      assertThat(result).isNotNull();
      assertThat(bytesToLong(result)).isEqualTo(15L); // 0 + 1+2+3+4+5
    }
  }

  /**
   * StringAppendMergeOperator test: appends strings with delimiter.
   */
  @Test
  public void testStringAppendOperator() throws RocksDBException {
    try (final Options options = new Options()
        .setCreateIfMissing(true)
        .setMergeOperator(new StringAppendMergeOperator(","));
         final RocksDB db = RocksDB.open(options, dbFolder.getRoot().getAbsolutePath())) {

      final byte[] key = "list".getBytes(StandardCharsets.UTF_8);

      db.merge(key, "apple".getBytes(StandardCharsets.UTF_8));
      db.merge(key, "banana".getBytes(StandardCharsets.UTF_8));
      db.merge(key, "cherry".getBytes(StandardCharsets.UTF_8));

      final byte[] result = db.get(key);
      assertThat(result).isNotNull();
      assertThat(new String(result, StandardCharsets.UTF_8))
          .isEqualTo("apple,banana,cherry");
    }
  }

  /**
   * StringAppend with an existing put value.
   */
  @Test
  public void testStringAppendWithExistingValue() throws RocksDBException {
    try (final Options options = new Options()
        .setCreateIfMissing(true)
        .setMergeOperator(new StringAppendMergeOperator("-"));
         final RocksDB db = RocksDB.open(options, dbFolder.getRoot().getAbsolutePath())) {

      final byte[] key = "key".getBytes(StandardCharsets.UTF_8);

      db.put(key, "first".getBytes(StandardCharsets.UTF_8));
      db.merge(key, "second".getBytes(StandardCharsets.UTF_8));
      db.merge(key, "third".getBytes(StandardCharsets.UTF_8));

      final byte[] result = db.get(key);
      assertThat(result).isNotNull();
      assertThat(new String(result, StandardCharsets.UTF_8))
          .isEqualTo("first-second-third");
    }
  }

  /**
   * Operator name is returned correctly.
   */
  @Test
  public void testOperatorName() {
    try (final LongAddMergeOperator op = new LongAddMergeOperator()) {
      assertThat(op.name()).isEqualTo("LongAddMergeOperator");
    }
    try (final StringAppendMergeOperator op = new StringAppendMergeOperator()) {
      assertThat(op.name()).isEqualTo("StringAppendMergeOperator");
    }
  }

  /**
   * Verify that the operator works when set via ColumnFamilyOptions.
   */
  @Test
  public void testColumnFamilyOptions() throws RocksDBException {
    try (final ColumnFamilyOptions cfOpts =
        new ColumnFamilyOptions()
            .setMergeOperator(new LongAddMergeOperator());
         final DBOptions dbOpts = new DBOptions()
             .setCreateIfMissing(true)) {

      final List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();
      cfDescriptors.add(
          new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts));

      final List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
      try (final RocksDB db = RocksDB.open(dbOpts, dbFolder.getRoot().getAbsolutePath(),
          cfDescriptors, cfHandles)) {

        final byte[] key = "counter".getBytes(StandardCharsets.UTF_8);

        db.put(cfHandles.get(0), key, longToBytes(10L));
        db.merge(cfHandles.get(0), key, longToBytes(5L));

        final byte[] result = db.get(cfHandles.get(0), key);
        assertThat(result).isNotNull();
        assertThat(bytesToLong(result)).isEqualTo(15L);
      } finally {
        for (final ColumnFamilyHandle cfHandle : cfHandles) {
          cfHandle.close();
        }
      }
    }
  }

  /**
   * Large number of merges to stress-test associative folding.
   */
  @Test
  public void testManyMerges() throws RocksDBException {
    try (final Options options = new Options()
        .setCreateIfMissing(true)
        .setMergeOperator(new LongAddMergeOperator());
         final RocksDB db = RocksDB.open(options, dbFolder.getRoot().getAbsolutePath())) {

      final byte[] key = "counter".getBytes(StandardCharsets.UTF_8);
      final int n = 1000;

      for (int i = 0; i < n; i++) {
        db.merge(key, longToBytes(1L));
      }

      final byte[] result = db.get(key);
      assertThat(result).isNotNull();
      assertThat(bytesToLong(result)).isEqualTo(n);
    }
  }
}
