// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// This source code is licensed under both the GPLv2 (found in the
// COPYING file in the root directory) and Apache 2.0 License
// (found in the LICENSE.Apache file in the root directory).

package org.rocksdb;

import java.nio.ByteBuffer;

/**
 * AbstractMergeOperatorJniBridge is a package-private class that holds
 * static methods called directly by C++ JNI code during merge operations.
 *
 * This class is intentionally hidden from users and is only for internal
 * use by the JNI layer.
 */
class AbstractMergeOperatorJniBridge {

  /**
   * Called by C++ JNI to invoke the FullMergeV2 operation.
   *
   * @param mergeOperator  the Java merge operator instance
   * @param key            ByteBuffer wrapping the key (direct, position=0)
   * @param keyLen         number of valid bytes in key
   * @param existing       ByteBuffer wrapping the existing value (direct, position=0),
   *                       or null if key has no prior value
   * @param existingLen    number of valid bytes in existing (-1 if existing is null)
   * @param operands       array of ByteBuffers, each wrapping an operand (direct, position=0)
   * @param operandLens    array of operand lengths (operandLens[i] = length of operands[i])
   *
   * @return byte[] containing the merged result, or null if merge failed
   */
  @SuppressWarnings("PMD.UnusedPrivateMethod")
  private static byte[] fullMergeInternal(
      final AbstractMergeOperator mergeOperator,
      final ByteBuffer key, final int keyLen,
      final ByteBuffer existing, final int existingLen,
      final ByteBuffer[] operands, final int[] operandLens) {

    // Set limits on the key buffer
    key.limit(keyLen);

    // Set limit on existing buffer (or leave it as null)
    if (existing != null && existingLen >= 0) {
      existing.limit(existingLen);
    }

    // Set limits on each operand buffer
    for (int i = 0; i < operands.length; i++) {
      operands[i].limit(operandLens[i]);
    }

    // Invoke the user's fullMerge implementation
    // existing is null iff existingLen was -1
    final byte[] result = mergeOperator.fullMerge(key, existing, operands);
    return result;
  }

  /**
   * Called by C++ JNI to invoke the PartialMerge operation.
   *
   * @param mergeOperator  the Java merge operator instance
   * @param key            ByteBuffer wrapping the key (direct, position=0)
   * @param keyLen         number of valid bytes in key
   * @param left           ByteBuffer wrapping the left operand (direct, position=0)
   * @param leftLen        number of valid bytes in left
   * @param right          ByteBuffer wrapping the right operand (direct, position=0)
   * @param rightLen       number of valid bytes in right
   *
   * @return byte[] containing the merged result, or null to decline partial merge
   */
  @SuppressWarnings("PMD.UnusedPrivateMethod")
  private static byte[] partialMergeInternal(
      final AbstractMergeOperator mergeOperator,
      final ByteBuffer key, final int keyLen,
      final ByteBuffer left, final int leftLen,
      final ByteBuffer right, final int rightLen) {

    // Set limits on all buffers
    key.limit(keyLen);
    left.limit(leftLen);
    right.limit(rightLen);

    // Invoke the user's partialMerge implementation
    final byte[] result = mergeOperator.partialMerge(key, left, right);
    return result;
  }
}
