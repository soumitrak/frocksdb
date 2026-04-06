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
   * @param operator    the Java merge operator instance
   * @param key         ByteBuffer wrapping the key (direct, position=0)
   * @param keyLen      number of valid bytes in key
   * @param existing    ByteBuffer wrapping the existing value (direct, position=0),
   *                    or null if key has no prior value
   * @param existingLen number of valid bytes in existing (-1 if existing is null)
   * @param value       ByteBuffer wrapping the merge operand (direct, position=0)
   * @param valueLen    number of valid bytes in value
   *
   * @return byte[] containing the merged result, or null if merge failed
   */
  @SuppressWarnings("PMD.UnusedPrivateMethod")
  private static byte[] mergeInternal(
      final AbstractAssociativeMergeOperator operator,
      final ByteBuffer key, final int keyLen,
      final ByteBuffer existing, final int existingLen,
      final ByteBuffer value, final int valueLen) {

    key.limit(keyLen);

    if (existing != null && existingLen >= 0) {
      existing.limit(existingLen);
    }

    value.limit(valueLen);

    return operator.merge(key, existing, value);
  }
}
