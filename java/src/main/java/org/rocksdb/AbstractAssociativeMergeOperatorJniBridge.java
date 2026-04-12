// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// This source code is licensed under both the GPLv2 (found in the
// COPYING file in the root directory) and Apache 2.0 License
// (found in the LICENSE.Apache file in the root directory).

package org.rocksdb;

import java.nio.ByteBuffer;

/**
 * AbstractAssociativeMergeOperatorJniBridge is a package-private class that
 * holds static methods called directly by C++ JNI code during merge operations.
 *
 * This class is intentionally hidden from users and is only for internal
 * use by the JNI layer.
 */
class AbstractAssociativeMergeOperatorJniBridge {

  /**
   * Called by C++ JNI to invoke the Merge operation.
   *
   * <p>The C++ caller pre-allocates {@code output} as a direct ByteBuffer
   * backed by C++ memory. Writing into it avoids any Java-heap allocation for
   * the result and eliminates the {@code GetByteArrayRegion} copy.
   *
   * @param operator    the Java merge operator instance
   * @param key         ByteBuffer wrapping the key (direct, position=0)
   * @param keyLen      number of valid bytes in key
   * @param existing    ByteBuffer wrapping the existing value (direct, position=0),
   *                    or null if key has no prior value
   * @param existingLen number of valid bytes in existing (-1 if existing is null)
   * @param value       ByteBuffer wrapping the merge operand (direct, position=0)
   * @param valueLen    number of valid bytes in value
   * @param output      direct ByteBuffer backed by C++ memory that receives the result
   *
   * @return bytes written to output (≥ 0), or -1 if the merge failed
   */
  @SuppressWarnings("PMD.UnusedPrivateMethod")
  private static int mergeInternal(
      final AbstractAssociativeMergeOperator operator,
      final ByteBuffer key, final int keyLen,
      final ByteBuffer existing, final int existingLen,
      final ByteBuffer value, final int valueLen,
      final ByteBuffer output) {

    // rewind() resets position to 0 so pooled input buffers are safe to reuse.
    key.rewind();
    key.limit(keyLen);

    if (existing != null && existingLen >= 0) {
      existing.rewind();
      existing.limit(existingLen);
    }

    value.rewind();
    value.limit(valueLen);

    // Clear the output buffer so the operator always starts writing at position 0.
    output.clear();

    return operator.merge(key, existing, value, output);
  }
}
