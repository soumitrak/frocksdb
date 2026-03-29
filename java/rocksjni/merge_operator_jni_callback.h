// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// This source code is licensed under both the GPLv2 (found in the
// COPYING file in the root directory) and Apache 2.0 License
// (found in the LICENSE.Apache file in the root directory).

#ifndef JAVA_ROCKSJNI_MERGEOPERATORJNICALLBACK_H_
#define JAVA_ROCKSJNI_MERGEOPERATORJNICALLBACK_H_

#include <jni.h>
#include <memory>
#include <string>
#include "rocksdb/merge_operator.h"
#include "rocksdb/slice.h"
#include "rocksjni/jnicallback.h"

namespace ROCKSDB_NAMESPACE {

/**
 * MergeOperatorJniCallback is a C++ implementation of MergeOperator that
 * delegates to a user-defined Java AbstractMergeOperator via JNI.
 *
 * Each merge call creates lightweight direct ByteBuffers that wrap the
 * RocksDB Slice memory in-place, so no data is copied before invoking the
 * Java callbacks.
 *
 * Thread Safety:
 * - Merge operations (FullMergeV2, PartialMerge) may be called from
 *   background compaction threads and user threads.
 * - JNI calls are protected by JniUtil's thread attachment mechanism.
 */
class MergeOperatorJniCallback : public JniCallback, public MergeOperator {
 public:
  /**
   * Constructs a new MergeOperatorJniCallback.
   *
   * @param env              the JNI environment
   * @param jmerge_operator  a Java AbstractMergeOperator instance
   */
  MergeOperatorJniCallback(JNIEnv* env, jobject jmerge_operator);

  /**
   * Destroys the callback object and releases all JNI global references.
   */
  ~MergeOperatorJniCallback() override;

  /**
   * Returns the name of this merge operator (cached from Java).
   * This is called by RocksDB for compatibility checking.
   */
  const char* Name() const override;

  /**
   * Implements the FullMergeV2 operation by calling the Java fullMerge method.
   */
  bool FullMergeV2(const MergeOperationInput& merge_in,
                   MergeOperationOutput* merge_out) const override;

  /**
   * Implements the PartialMerge operation by calling the Java partialMerge
   * method. PartialMerge is optional but improves compaction performance.
   */
  bool PartialMerge(const Slice& key,
                    const Slice& left_operand,
                    const Slice& right_operand,
                    std::string* new_value,
                    Logger* logger) const override;

 private:
  // Cached operator name (fetched once from Java in constructor)
  std::unique_ptr<char[]> m_name;

  // Cached JNI classes and method IDs
  jclass m_abstract_merge_operator_jni_bridge_clazz;
  jclass m_jbytebuffer_clazz;
  jmethodID m_jfull_merge_mid;
  jmethodID m_jpartial_merge_mid;

  // Helper: copy a jbyteArray result into a C++ string
  bool CopyByteArrayToString(JNIEnv* env, jbyteArray jarray,
                              std::string* output) const;

  // Prevent copying
  MergeOperatorJniCallback(const MergeOperatorJniCallback&);
  MergeOperatorJniCallback& operator=(const MergeOperatorJniCallback&);
};

}  // namespace ROCKSDB_NAMESPACE
#endif  // JAVA_ROCKSJNI_MERGEOPERATORJNICALLBACK_H_
