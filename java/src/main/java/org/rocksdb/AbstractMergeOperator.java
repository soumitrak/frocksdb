// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// This source code is licensed under both the GPLv2 (found in the
// COPYING file in the root directory) and Apache 2.0 License
// (found in the LICENSE.Apache file in the root directory).

package org.rocksdb;

import java.nio.ByteBuffer;

/**
 * AbstractMergeOperator is a base class for user-defined merge operators
 * that can be invoked by RocksDB during compaction and Get() operations.
 *
 * The merge operator defines how multiple values at the same key are combined.
 * Users should extend this class and implement {@link #fullMerge} to define
 * the merge logic. Optionally, {@link #partialMerge} can be implemented
 * for efficiency during compaction when merging intermediate operands.
 *
 * <p><b>Thread Safety</b>: Merge operators are called from background
 * compaction threads and user threads (for Get()). Implementations should
 * be thread-safe or use thread-local state if needed.
 *
 * <p><b>Name Stability</b>: The {@link #name()} must be stable across
 * database opens. Changing the name can make existing databases inaccessible.
 */
public abstract class AbstractMergeOperator extends RocksCallbackObject {

  /**
   * Creates a new AbstractMergeOperator.
   */
  protected AbstractMergeOperator() {
    super();
  }

  @Override
  protected long initializeNative(final long... nativeParameterHandles) {
    return createNewMergeOperator();
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
   * Applies a stack of merge operands on top of an optional existing value.
   *
   * <p>This method is called during Get() and compaction when RocksDB needs
   * to produce a final value from an existing value (if any) and a sequence
   * of merge operands.
   *
   * <p>The order of operands is from oldest to newest (chronological).
   *
   * @param key       a ByteBuffer containing the key being merged
   *                  (position=0, limit=key length, read-only)
   * @param existing  a ByteBuffer containing the existing value for this key,
   *                  or null if the key did not previously exist
   *                  (if non-null: position=0, limit=value length, read-only)
   * @param operands  an array of ByteBuffers, one per merge operand,
   *                  in chronological order (oldest first).
   *                  Each buffer has position=0, limit=operand length, read-only.
   *                  May be empty if no operands were queued.
   *
   * @return the merged result as a byte array, or null to signal failure.
   *         Returning null causes the merge to fail; RocksDB behavior depends
   *         on merge failure scope.
   */
  public abstract byte[] fullMerge(ByteBuffer key, ByteBuffer existing,
                                    ByteBuffer[] operands);

  /**
   * Optionally merges two adjacent merge operands during compaction.
   *
   * <p>This method is called during compaction to efficiently combine two
   * consecutive operands before a base value is encountered. Implementing
   * this can significantly improve compaction performance.
   *
   * <p>If this method returns null or false is not overridden, RocksDB will
   * preserve both operands and combine them later with {@link #fullMerge}.
   *
   * @param key   a ByteBuffer containing the key (read-only)
   * @param left  a ByteBuffer containing the left (older) operand (read-only)
   * @param right a ByteBuffer containing the right (newer) operand (read-only)
   *
   * @return the merged result as a byte array if the merge succeeded,
   *         or null to decline partial merge (RocksDB will keep both operands)
   */
  public byte[] partialMerge(ByteBuffer key, ByteBuffer left,
                              ByteBuffer right) {
    return null;
  }

  private native long createNewMergeOperator();
}
