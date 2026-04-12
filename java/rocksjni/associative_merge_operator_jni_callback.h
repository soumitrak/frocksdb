// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// This source code is licensed under both the GPLv2 (found in the
// COPYING file in the root directory) and Apache 2.0 License
// (found in the LICENSE.Apache file in the root directory).

#ifndef JAVA_ROCKSJNI_ASSOCIATIVEMERGEOPERATORJNICALLBACK_H_
#define JAVA_ROCKSJNI_ASSOCIATIVEMERGEOPERATORJNICALLBACK_H_

#include <jni.h>
#include <memory>
#include <string>
#include "port/port.h"
#include "rocksdb/merge_operator.h"
#include "rocksdb/slice.h"
#include "rocksjni/jnicallback.h"
#include "util/thread_local.h"

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
  // Holds a thread-local direct ByteBuffer (as a JNI global ref), the raw C++
  // backing pointer, and the JVM pointer needed to release the global ref on
  // thread exit.  Storing backing separately lets the unref handler free C++
  // memory without needing a live JNIEnv (important during JVM shutdown).
  struct MergeTlBuf {
    JavaVM* jvm;
    jobject jbuf;     // JNI global ref to a direct ByteBuffer
    char*   backing;  // C++ heap memory that jbuf wraps
    MergeTlBuf(JavaVM* _jvm, jobject _jbuf, char* _backing)
        : jvm(_jvm), jbuf(_jbuf), backing(_backing) {}
  };

  // Data up to this many bytes is copied into a pooled thread-local ByteBuffer
  // instead of creating a fresh NewDirectByteBuffer on every Merge call.
  static constexpr int32_t kMaxReusedBufferSize = 1024;

  // Cached operator name (fetched once from Java in constructor)
  std::unique_ptr<char[]> m_name;

  // Cached JNI classes and method IDs
  jclass m_bridge_clazz;
  jclass m_jbytebuffer_clazz;
  jmethodID m_jmerge_mid;

  // Thread-local ByteBuffer pools for the three Merge input arguments.
  ThreadLocalPtr* m_tl_buf_key;
  ThreadLocalPtr* m_tl_buf_existing;
  ThreadLocalPtr* m_tl_buf_value;

  // Thread-local output ByteBuffer backed by C++ heap memory.
  // Java writes the merge result directly into it, eliminating the
  // jbyteArray allocation and the GetByteArrayRegion copy.
  static constexpr size_t kOutputBufCapacity = 64 * 1024 * 1024;  // 64 MiB
  ThreadLocalPtr* m_tl_output_buf;

  // Returns a local-ref ByteBuffer wrapping src. If src.size() fits within
  // kMaxReusedBufferSize the data is memcpy'd into the per-thread pooled buffer
  // (avoiding a JNI allocation). Otherwise a fresh NewDirectByteBuffer is
  // returned. In both cases the caller must DeleteLocalRef the result.
  jobject GetOrAllocBuffer(JNIEnv* env, const Slice& src,
                           ThreadLocalPtr* tl_buf) const;

  // Returns the per-thread output ByteBuffer global ref (lazily allocated at
  // kOutputBufCapacity). The caller must NOT DeleteLocalRef this.
  jobject GetOrAllocOutputBuffer(JNIEnv* env) const;

  AssociativeMergeOperatorJniCallback(
      const AssociativeMergeOperatorJniCallback&) = delete;
  AssociativeMergeOperatorJniCallback& operator=(
      const AssociativeMergeOperatorJniCallback&) = delete;
};

}  // namespace ROCKSDB_NAMESPACE
#endif  // JAVA_ROCKSJNI_ASSOCIATIVEMERGEOPERATORJNICALLBACK_H_
