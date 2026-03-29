// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// This source code is licensed under both the GPLv2 (found in the
// COPYING file in the root directory) and Apache 2.0 License
// (found in the LICENSE.Apache file in the root directory).

package org.rocksdb;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JavaSortedStringMergeOperator is a pure Java merge operator that maintains
 * values as comma-separated sorted lists of strings.
 *
 * <p>Each merge operand is treated as either a single string or an
 * already-sorted comma-separated list (produced by {@link #partialMerge}
 * during compaction). The full merge collects all items from the existing
 * value and all operands, sorts them, and returns the joined result.
 * The partial merge uses a two-way merge sort to combine two sorted lists.
 *
 * <p>This operator is used in the JNI benchmark to compare the overhead of
 * calling a Java merge operator via JNI against the equivalent native C++
 * implementation ({@code SortedStringMergeOperator}, registered under the factory
 * name {@code "SortedStringMergeOperator"}).
 *
 * <p><b>Thread Safety</b>: This implementation is stateless and therefore
 * thread-safe. All state is allocated on the stack per invocation.
 */
public class JavaSortedStringMergeOperator extends AbstractMergeOperator {

  @Override
  public String name() {
    return "JavaSortedStringMergeOperator";
  }

  /**
   * Merges the existing value and all operands into a single sorted
   * comma-separated string.
   *
   * <p>Each operand is either a single item (as written by a {@code merge()}
   * call) or a sorted comma-separated list produced by a previous
   * {@link #partialMerge} call. All items are collected, sorted, and joined.
   */
  @Override
  public byte[] fullMerge(final ByteBuffer key, final ByteBuffer existing,
                           final ByteBuffer[] operands) {
    final List<String> allItems = new ArrayList<>();

    // Collect items from the existing base value (already sorted)
    if (existing != null && existing.remaining() > 0) {
      splitInto(readString(existing), allItems);
    }

    // Collect items from each operand
    for (final ByteBuffer operand : operands) {
      if (operand.remaining() > 0) {
        splitInto(readString(operand), allItems);
      }
    }

    Collections.sort(allItems);
    return String.join(",", allItems).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Performs a two-way merge sort on two sorted comma-separated lists.
   *
   * <p>Assumes both {@code left} and {@code right} are already sorted.
   * Called during compaction to progressively reduce the number of operands.
   */
  @Override
  public byte[] partialMerge(final ByteBuffer key, final ByteBuffer left,
                              final ByteBuffer right) {
    final String[] leftItems = readString(left).split(",", -1);
    final String[] rightItems = readString(right).split(",", -1);

    // Two-way merge sort
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

    return String.join(",", merged).getBytes(StandardCharsets.UTF_8);
  }

  // Reads the remaining bytes of a ByteBuffer as a UTF-8 string.
  private static String readString(final ByteBuffer buf) {
    final byte[] bytes = new byte[buf.remaining()];
    buf.get(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  // Splits a comma-separated string and adds non-empty tokens to the list.
  private static void splitInto(final String str, final List<String> out) {
    if (str.isEmpty()) return;
    for (final String token : str.split(",", -1)) {
      if (!token.isEmpty()) {
        out.add(token);
      }
    }
  }
}
