// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// This source code is licensed under both the GPLv2 (found in the
// COPYING file in the root directory) and Apache 2.0 License
// (found in the LICENSE.Apache file in the root directory).

package org.rocksdb;

import java.nio.ByteBuffer;

/**
 * AbstractAssociativeMergeOperator is a base class for user-defined merge
 * operators that implement the simpler associative merge interface.
 *
 * Unlike {@link AbstractMergeOperator}, which requires implementing
 * {@code fullMerge()} over a list of operands, this class only requires
 * implementing a single {@link #merge} method that combines one operand
 * into an optional existing value. RocksDB automatically handles chaining
 * multiple operands and partial merging during compaction.
 *
 * <p>Use this interface when your merge operation is associative and
 * commutative - for example, integer addition, set union, or string append.
 *
 * <p><b>Thread Safety</b>: Merge operators are called from background
 * compaction threads and user threads (for Get()). Implementations should
 * be thread-safe or use thread-local state if needed.
 *
 * <p><b>Name Stability</b>: The {@link #name()} must be stable across
 * database opens. Changing the name can make existing databases inaccessible.
 */
public abstract class AbstractAssociativeMergeOperator extends RocksCallbackObject {

  /**
   * Creates a new AbstractAssociativeMergeOperator.
   */
  protected AbstractAssociativeMergeOperator() {
    super();
  }

  @Override
  protected long initializeNative(final long... nativeParameterHandles) {
    return createNewAssociativeMergeOperator();
  }

  /**
   * Returns the name of this merge operator.
   *
   * <p>This name is used for DB compatibility checking. It must not change
   * across database re-opens with the same merge operator instance.
   * Names beginning with "rocksdb." are reserved for internal use.
   *
   * @return a stable name for this merge operator
   */
  public abstract String name();

  /**
   * Merges a single operand value on top of an optional existing value.
   *
   * <p>This method is called during Get() and compaction. RocksDB will call
   * it repeatedly to fold multiple operands into a base value.
   *
   * <p>The result must be written directly into {@code output} starting at
   * its current position. The framework pre-clears the buffer before each
   * call (position=0, limit=capacity). Implementations must not flip or
   * rewind the buffer — the framework reads {@code output.position()} bytes
   * after the call returns.
   *
   * <p>The output buffer is backed by C++ memory, so writing into it avoids
   * any Java heap allocation for the result and eliminates the JNI copy that
   * would otherwise be needed to return a {@code byte[]} to the native side.
   *
   * @param key      a ByteBuffer containing the key being merged
   *                 (position=0, limit=key length, read-only)
   * @param existing a ByteBuffer containing the existing value for this key,
   *                 or null if the key did not previously exist
   *                 (if non-null: position=0, limit=value length, read-only)
   * @param value    a ByteBuffer containing the merge operand to apply
   *                 (position=0, limit=operand length, read-only)
   * @param output   a writable direct ByteBuffer to receive the merged result;
   *                 write the result bytes starting at the current position
   *
   * @return the number of bytes written to {@code output} (≥ 0), or -1 to
   *         signal a merge failure.
   */
  public abstract int merge(ByteBuffer key, ByteBuffer existing, ByteBuffer value,
      ByteBuffer output);

  private native long createNewAssociativeMergeOperator();
}
