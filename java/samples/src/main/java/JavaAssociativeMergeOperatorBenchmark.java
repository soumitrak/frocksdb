// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// This source code is licensed under both the GPLv2 (found in the
// COPYING file in the root directory) and Apache 2.0 License
// (found in the LICENSE.Apache file in the root directory).

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.rocksdb.AbstractAssociativeMergeOperator;
import org.rocksdb.Options;
import org.rocksdb.RocksDBException;

/**
 * JavaAssociativeMergeOperatorBenchmark benchmarks a pure-Java sorted-string
 * merge operator implemented via {@link AbstractAssociativeMergeOperator}.
 *
 * <p>The operator maintains values as comma-separated sorted lists of strings,
 * identical in behaviour to the {@code JavaSortedStringMergeOperator} used in
 * {@link JavaMergeOperatorBenchmark}. The difference is the interface: rather
 * than implementing {@code fullMerge} (all operands at once) and
 * {@code partialMerge} (two operands), only a single {@code merge} method is
 * needed. RocksDB's C++ {@code AssociativeMergeOperator} base class drives both
 * {@code FullMergeV2} and {@code PartialMerge} by calling {@code merge()} once
 * per operand.
 *
 * <p>Because both the {@code existing} value and the new {@code value} are
 * always sorted comma-separated lists (a single-item write from {@code db.merge()}
 * is trivially sorted, and subsequent partial-merge calls fold two sorted lists),
 * each invocation is a two-way merge sort.
 *
 * <p>This benchmark uses the same four-phase structure, parameters, and
 * validation as {@link JavaMergeOperatorBenchmark}, making the results directly
 * comparable.
 *
 * <p>Usage:
 * <pre>
 *   java -Djava.library.path=target -cp target/classes:samples/target/classes \
 *     JavaAssociativeMergeOperatorBenchmark [dbPath [numKeys [mergesPerKey [stringLen]]]]
 * </pre>
 *
 * <p>Default parameters: 10 keys, 2000 merges/key, 8-character strings.
 */
public class JavaAssociativeMergeOperatorBenchmark extends MergeOperatorBenchmark {
  // public static final Logger LOG = Logger.getLogger(JavaAssociativeMergeOperatorBenchmark.class.getName());

  private static final int DEFAULT_NUM_KEYS = 10;
  private static final int DEFAULT_MERGES_PER_KEY = 2000;
  private static final int DEFAULT_STRING_LEN = 8;

  /**
   * Pure-Java associative sorted-string merge operator.
   *
   * <p>Each call receives an optional {@code existing} sorted comma-separated
   * list and a {@code value} that is either a single item (from a raw
   * {@code db.merge()} call) or a sorted comma-separated list produced by a
   * prior associative fold. The two lists are combined via two-way merge sort.
   */
  static class JavaAssociativeSortedStringMergeOperator
      extends AbstractAssociativeMergeOperator {

    @Override
    public String name() {
      return "JavaAssociativeSortedStringMergeOperator";
    }

    @Override
    public int merge(final ByteBuffer key, final ByteBuffer existing,
                     final ByteBuffer value, final ByteBuffer output) {
      // LOG.info("merge() start");
      final String[] leftItems =
          (existing != null && existing.remaining() > 0)
              ? readString(existing).split(",", -1)
              : new String[0];
      final String[] rightItems = readString(value).split(",", -1);

      // Two-way merge sort of two sorted lists
      final List<String> merged = new ArrayList<>(leftItems.length + rightItems.length);
      int i = 0;
      int j = 0;
      while (i < leftItems.length && j < rightItems.length) {
        if (leftItems[i].compareTo(rightItems[j]) <= 0) {
          merged.add(leftItems[i++]);
        } else {
          merged.add(rightItems[j++]);
        }
      }
      while (i < leftItems.length) {
        merged.add(leftItems[i++]);
      }
      while (j < rightItems.length) {
        merged.add(rightItems[j++]);
      }

      // Write directly into the C++-backed output ByteBuffer — no byte[] allocation,
      // no GetByteArrayRegion copy on the JNI return path.
      boolean first = true;
      for (final String item : merged) {
        if (!first) output.put((byte) ',');
        first = false;
        output.put(item.getBytes(StandardCharsets.UTF_8));
      }
      // LOG.info("merge() end");
      return output.position();
    }

    private static String readString(final ByteBuffer buf) {
      final byte[] bytes = new byte[buf.remaining()];
      buf.get(bytes);
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  @Override
  protected void configureOptions(final Options opts) {
    opts.setMergeOperator(new JavaAssociativeSortedStringMergeOperator());
  }

  @Override
  protected String operatorDescription() {
    return "JavaAssociativeSortedStringMergeOperator"
        + " (pure Java, AbstractAssociativeMergeOperator, invoked via JNI callbacks)";
  }

  public static void main(final String[] args) throws RocksDBException {
    final String dbPath =
        args.length > 0 ? args[0] : "/tmp/frocksdb";
    final int numKeys =
        args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_NUM_KEYS;
    final int mergesPerKey =
        args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_MERGES_PER_KEY;
    final int stringLen =
        args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_STRING_LEN;

    new JavaAssociativeMergeOperatorBenchmark().run(dbPath, numKeys, mergesPerKey, stringLen);
  }
}
