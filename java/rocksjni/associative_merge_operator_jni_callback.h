// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// This source code is licensed under both the GPLv2 (found in the
// COPYING file in the root directory) and Apache 2.0 License
// (found in the LICENSE.Apache file in the root directory).

#ifndef JAVA_ROCKSJNI_ASSOCIATIVEMERGEOPERATORJNICALLBACK_H_
#define JAVA_ROCKSJNI_ASSOCIATIVEMERGEOPERATORJNICALLBACK_H_

#include <jni.h>
#include <memory>
#include <string>
#include "rocksdb/merge_operator.h"
#include "rocksdb/slice.h"
#include "rocksjni/jnicallback.h"

namespace ROCKSDB_NAMESPACE {

/**
 * AssociativeMergeOperatorJniCallback is a C++ implementation of
 * AssociativeMergeOperator that delegates to a user-defined Java
 * AbstractAssociativeMergeOperator via JNI.
 *
 * Users only implement a single merge(key, existing, value) method in Java.
 * The C++ AssociativeMergeOperator base class handles FullMergeV2 and
 * PartialMerge automatically by calling Merge() repeatedly.
 *
 * Each Merge() call creates lightweight direct ByteBuffers that wrap the
 * RocksDB Slice memory in-place, so no data is copied before invoking the
 * Java callback.
 *
 * Thread Safety:
 * - Merge() may be called from background compaction threads and user threads.
 * - JNI calls are protected by JniUtil's thread attachment mechanism.
 */
class AssociativeMergeOperatorJniCallback
    : public JniCallback, public AssociativeMergeOperator {
 public:
  /**
   * Constructs a new AssociativeMergeOperatorJniCallback.
   *
   * @param env       the JNI environment
   * @param joperator a Java AbstractAssociativeMergeOperator instance
   */
  AssociativeMergeOperatorJniCallback(JNIEnv* env, jobject joperator);

  /**
   * Destroys the callback object and releases all JNI global references.
   */
  ~AssociativeMergeOperatorJniCallback() override;

  /**
   * Returns the name of this merge operator (cached from Java).
   * This is called by RocksDB for compatibility checking.
   */
  const char* Name() const override;

  /**
   * Implements the Merge operation by calling the Java merge method.
   *
   * @param key            the key
   * @param existing_value the existing value, or nullptr if key has no value
   * @param value          the merge operand to apply
   * @param new_value      output: the merged result
   * @param logger         logger (unused)
   * @return true on success, false on failure
   */
  bool Merge(const Slice& key, const Slice* existing_value,
             const Slice& value, std::string* new_value,
             Logger* logger) const override;

 private:
  // Cached operator name (fetched once from Java in constructor)
  std::unique_ptr<char[]> m_name;

  // Cached JNI classes and method IDs
  jclass m_bridge_clazz;
  jclass m_jbytebuffer_clazz;
  jmethodID m_jmerge_mid;

  // Helper: copy a jbyteArray result into a C++ string
  bool CopyByteArrayToString(JNIEnv* env, jbyteArray jarray,
                              std::string* output) const;

  // Prevent copying
  AssociativeMergeOperatorJniCallback(
      const AssociativeMergeOperatorJniCallback&);
  AssociativeMergeOperatorJniCallback& operator=(
      const AssociativeMergeOperatorJniCallback&);
};

}  // namespace ROCKSDB_NAMESPACE
#endif  // JAVA_ROCKSJNI_ASSOCIATIVEMERGEOPERATORJNICALLBACK_H_
