// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// This source code is licensed under both the GPLv2 (found in the
// COPYING file in the root directory) and Apache 2.0 License
// (found in the LICENSE.Apache file in the root directory).

#include "rocksjni/associative_merge_operator_jni_callback.h"

#include <chrono>
#include <cstring>

#include "logging/logging.h"
#include "rocksjni/cplusplus_to_java_convert.h"
#include "rocksjni/portal.h"

namespace ROCKSDB_NAMESPACE {

// ---------------------------------------------------------------------------
// Thread-local JNIEnv cache
//
// RocksDB's compaction/flush threads are long-lived pool threads.  Calling
// AttachCurrentThread + DetachCurrentThread on every Merge invocation is
// expensive (1-100 µs on Linux).  We attach once per thread and cache the
// JNIEnv* for the lifetime of that thread.  The struct's destructor detaches
// cleanly when the thread exits.
// ---------------------------------------------------------------------------
namespace {
struct TlJniEnvCache {
  JavaVM* jvm = nullptr;
  JNIEnv* env = nullptr;
  bool owned = false;  // true iff we called AttachCurrentThread

  ~TlJniEnvCache() {
    if (owned && jvm != nullptr) {
      jvm->DetachCurrentThread();
    }
  }

  JNIEnv* GetOrAttach(JavaVM* vm) {
    if (env != nullptr) {
      return env;
    }
    jvm = vm;
    jboolean attached = JNI_FALSE;
    env = JniUtil::getJniEnv(jvm, &attached);
    owned = (attached == JNI_TRUE);
    return env;
  }
};
thread_local TlJniEnvCache tl_env_cache;
}  // namespace

// ---------------------------------------------------------------------------
// Constructor / Destructor
// ---------------------------------------------------------------------------

AssociativeMergeOperatorJniCallback::AssociativeMergeOperatorJniCallback(
    JNIEnv* env, jobject joperator)
    : JniCallback(env, joperator),
      m_bridge_clazz(nullptr),
      m_jbytebuffer_clazz(nullptr),
      m_jmerge_mid(nullptr),
      m_tl_buf_key(nullptr),
      m_tl_buf_existing(nullptr),
      m_tl_buf_value(nullptr),
      m_tl_output_buf(nullptr) {

  // Get and cache the bridge class globally
  m_bridge_clazz =
      AbstractAssociativeMergeOperatorJniBridge::getJClass(env);
  if (m_bridge_clazz == nullptr) {
    fprintf(stderr,
            "Failed to get AbstractAssociativeMergeOperatorJniBridge class\n");
    return;
  }
  m_bridge_clazz =
      static_cast<jclass>(env->NewGlobalRef(m_bridge_clazz));

  // Cache ByteBuffer class — needed for creating direct ByteBuffer wrappers
  jclass local_bb_clazz = env->FindClass("java/nio/ByteBuffer");
  if (local_bb_clazz == nullptr) {
    fprintf(stderr, "Failed to get ByteBuffer class\n");
    return;
  }
  m_jbytebuffer_clazz =
      static_cast<jclass>(env->NewGlobalRef(local_bb_clazz));
  env->DeleteLocalRef(local_bb_clazz);

  // Cache the mergeInternal method ID
  m_jmerge_mid =
      AbstractAssociativeMergeOperatorJniBridge::getMergeInternalMethodId(
          env, m_bridge_clazz);
  if (m_jmerge_mid == nullptr) {
    fprintf(stderr, "Failed to get mergeInternal method ID\n");
    return;
  }

  // Fetch and cache the operator name from Java
  jmethodID jname_mid =
      AbstractAssociativeMergeOperatorJni::getNameMethodId(env);
  if (jname_mid != nullptr) {
    jstring jname = static_cast<jstring>(
        env->CallObjectMethod(m_jcallback_obj, jname_mid));
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
      m_name = std::make_unique<char[]>(1);
    } else if (jname != nullptr) {
      jboolean has_exception = JNI_FALSE;
      m_name = JniUtil::copyString(env, jname, &has_exception);
      env->DeleteLocalRef(jname);
      if (has_exception) {
        m_name = std::make_unique<char[]>(1);
      }
    } else {
      m_name = std::make_unique<char[]>(1);
    }
  } else {
    m_name = std::make_unique<char[]>(1);
  }

  // Shared unref handler for all input buffer pools and the output buffer pool.
  // Frees the C++ backing allocation and the JNI global ref on thread exit.
  auto unref = [](void* ptr) {
    auto* tlb = reinterpret_cast<MergeTlBuf*>(ptr);
    jboolean attached = JNI_FALSE;
    JNIEnv* e = JniUtil::getJniEnv(tlb->jvm, &attached);
    if (e != nullptr) {
      void* buf = e->GetDirectBufferAddress(tlb->jbuf);
      delete[] static_cast<char*>(buf);
      e->DeleteGlobalRef(tlb->jbuf);
      JniUtil::releaseJniEnv(tlb->jvm, attached);
    }
    delete tlb;
  };

  // Set up thread-local buffer pools for key / existing / value.
  // Each pool lazily allocates a per-thread direct ByteBuffer of
  // kMaxReusedBufferSize bytes on first use and reuses it thereafter,
  // avoiding a JNI allocation on every Merge call for small payloads.
  m_tl_buf_key      = new ThreadLocalPtr(unref);
  m_tl_buf_existing = new ThreadLocalPtr(unref);
  m_tl_buf_value    = new ThreadLocalPtr(unref);

  // Thread-local output ByteBuffer: Java writes the merge result directly into
  // C++ heap memory, eliminating the jbyteArray return value and the
  // GetByteArrayRegion copy that was the dominant cost in the copy phase.
  m_tl_output_buf = new ThreadLocalPtr(unref);
}

AssociativeMergeOperatorJniCallback::~AssociativeMergeOperatorJniCallback() {
  jboolean attached_thread = JNI_FALSE;
  JNIEnv* env = getJniEnv(&attached_thread);
  if (env == nullptr) {
    return;
  }
  if (m_bridge_clazz != nullptr) {
    env->DeleteGlobalRef(m_bridge_clazz);
  }
  if (m_jbytebuffer_clazz != nullptr) {
    env->DeleteGlobalRef(m_jbytebuffer_clazz);
  }
  delete m_tl_buf_key;
  delete m_tl_buf_existing;
  delete m_tl_buf_value;
  delete m_tl_output_buf;
  releaseJniEnv(attached_thread);
}

const char* AssociativeMergeOperatorJniCallback::Name() const {
  return m_name.get();
}

// ---------------------------------------------------------------------------
// GetOrAllocBuffer  (input buffers — key / existing / value)
//
// For src.size() <= kMaxReusedBufferSize: data is memcpy'd into a per-thread
// pooled direct ByteBuffer (allocated lazily).  Returns a new local ref so
// the caller can uniformly DeleteLocalRef without worrying about global refs.
//
// For larger src: wraps src.data() directly in a fresh NewDirectByteBuffer
// (zero-copy, no pool needed since the Slice outlives the JNI call).
// ---------------------------------------------------------------------------
jobject AssociativeMergeOperatorJniCallback::GetOrAllocBuffer(
    JNIEnv* env, const Slice& src, ThreadLocalPtr* tl_buf) const {
  if (static_cast<int32_t>(src.size()) <= kMaxReusedBufferSize) {
    auto* tlb = reinterpret_cast<MergeTlBuf*>(tl_buf->Get());
    if (tlb == nullptr) {
      // First call on this thread: allocate backing memory and a global-ref
      // ByteBuffer wrapping it.
      char* backing = new char[kMaxReusedBufferSize];
      jobject local = env->NewDirectByteBuffer(backing, kMaxReusedBufferSize);
      if (local == nullptr) {
        delete[] backing;
        return nullptr;
      }
      jobject global = env->NewGlobalRef(local);
      env->DeleteLocalRef(local);
      if (global == nullptr) {
        delete[] backing;
        return nullptr;
      }
      tlb = new MergeTlBuf(m_jvm, global);
      tl_buf->Reset(tlb);
    }
    void* buf_ptr = env->GetDirectBufferAddress(tlb->jbuf);
    if (buf_ptr == nullptr) {
      return nullptr;
    }
    std::memcpy(buf_ptr, src.data(), src.size());
    // Return a local ref so cleanup is uniform (caller always DeleteLocalRef).
    return env->NewLocalRef(tlb->jbuf);
  } else {
    // src too large for pool: wrap in-place, zero-copy.
    return env->NewDirectByteBuffer(
        const_cast<void*>(static_cast<const void*>(src.data())),
        static_cast<jlong>(src.size()));
  }
}

// ---------------------------------------------------------------------------
// GetOrAllocOutputBuffer
//
// Returns the per-thread output ByteBuffer global ref, lazily allocating it at
// kOutputBufCapacity on first use.  The buffer is backed by C++ heap memory;
// Java writes the merge result into it directly.
// ---------------------------------------------------------------------------
jobject AssociativeMergeOperatorJniCallback::GetOrAllocOutputBuffer(
    JNIEnv* env) const {
  auto* tlb = reinterpret_cast<MergeTlBuf*>(m_tl_output_buf->Get());
  if (tlb != nullptr) {
    return tlb->jbuf;
  }
  char* backing = new char[kOutputBufCapacity];
  jobject local =
      env->NewDirectByteBuffer(backing, static_cast<jlong>(kOutputBufCapacity));
  if (local == nullptr) {
    delete[] backing;
    return nullptr;
  }
  jobject global = env->NewGlobalRef(local);
  env->DeleteLocalRef(local);
  if (global == nullptr) {
    delete[] backing;
    return nullptr;
  }
  m_tl_output_buf->Reset(new MergeTlBuf(m_jvm, global));
  return global;
}

// ---------------------------------------------------------------------------
// Merge
// ---------------------------------------------------------------------------
bool AssociativeMergeOperatorJniCallback::Merge(
    const Slice& key,
    const Slice* existing_value,
    const Slice& value,
    std::string* new_value,
    Logger* logger) const {
  // SK: using Clock = std::chrono::steady_clock;
  // SK: using Micros = std::chrono::microseconds;

  // SK: const auto t_total_start = Clock::now();

  // ── Phase: env ────────────────────────────────────────────────────────────
  // Use the per-thread cached env to avoid AttachCurrentThread/Detach on every
  // call from background compaction threads.
  // SK: const auto t_env_start = Clock::now();
  JNIEnv* env = tl_env_cache.GetOrAttach(m_jvm);
  // SK: const long long env_us = std::chrono::duration_cast<Micros>(Clock::now() - t_env_start).count();

  if (env == nullptr) {
    return false;
  }

  // All locals declared before the first goto to avoid jump-past-init errors.
  jobject jkey       = nullptr;
  jobject jexisting  = nullptr;
  jobject jvalue     = nullptr;
  jobject joutput    = nullptr;
  bool success = false;
  // SK: long long wrap_us = 0;
  // SK: long long jni_us  = 0;
  // SK: long long copy_us = 0;

  // ── Phase: wrap ───────────────────────────────────────────────────────────
  // Wrap each input Slice as a direct ByteBuffer (pooled for small payloads).
  // Also obtain the thread-local output ByteBuffer that Java will write into.
  {
    // SK: const auto t_wrap_start = Clock::now();

    jkey = GetOrAllocBuffer(env, key, m_tl_buf_key);
    if (jkey == nullptr || env->ExceptionCheck()) {
      env->ExceptionClear();
      goto cleanup;
    }

    if (existing_value != nullptr) {
      jexisting = GetOrAllocBuffer(env, *existing_value, m_tl_buf_existing);
      if (jexisting == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        goto cleanup;
      }
    }

    jvalue = GetOrAllocBuffer(env, value, m_tl_buf_value);
    if (jvalue == nullptr || env->ExceptionCheck()) {
      env->ExceptionClear();
      goto cleanup;
    }

    joutput = GetOrAllocOutputBuffer(env);
    if (joutput == nullptr || env->ExceptionCheck()) {
      env->ExceptionClear();
      goto cleanup;
    }

    // SK: wrap_us = std::chrono::duration_cast<Micros>(Clock::now() - t_wrap_start).count();
  }

  // ── Phase: jni ────────────────────────────────────────────────────────────
  // Invoke Java mergeInternal(operator, key, keyLen, existing, existingLen,
  //                            value, valueLen, output).
  // Java writes the result directly into joutput (C++ memory) and returns the
  // number of bytes written.  No byte[] is allocated; no GetByteArrayRegion.
  {
    // SK: const auto t_jni_start = Clock::now();
    const jint result_len = env->CallStaticIntMethod(
        m_bridge_clazz,
        m_jmerge_mid,
        m_jcallback_obj,
        jkey,
        static_cast<jint>(key.size()),
        jexisting,
        existing_value != nullptr
            ? static_cast<jint>(existing_value->size())
            : -1,
        jvalue,
        static_cast<jint>(value.size()),
        joutput);
    // SK: jni_us = std::chrono::duration_cast<Micros>(Clock::now() - t_jni_start).count();

    if (env->ExceptionCheck()) {
      env->ExceptionClear();
      goto cleanup;
    }

    if (result_len >= 0) {
      // ── Phase: copy ────────────────────────────────────────────────────────
      // The result is already in C++ memory (the direct ByteBuffer backing).
      // This assign() is a plain C++ memcpy — no JVM involvement whatsoever.
      // SK: const auto t_copy_start = Clock::now();
      const char* output_ptr = static_cast<const char*>(
          env->GetDirectBufferAddress(joutput));
      if (output_ptr != nullptr) {
        new_value->assign(output_ptr, static_cast<size_t>(result_len));
        success = true;
      }
      // SK: copy_us = std::chrono::duration_cast<Micros>(Clock::now() - t_copy_start) .count();
    }
  }

cleanup:
  if (jkey      != nullptr) env->DeleteLocalRef(jkey);
  if (jexisting != nullptr) env->DeleteLocalRef(jexisting);
  if (jvalue    != nullptr) env->DeleteLocalRef(jvalue);
  // joutput is a global ref (owned by m_tl_output_buf) — do NOT DeleteLocalRef.
  // No releaseJniEnv — env is cached in tl_env_cache; detach happens on thread
  // exit via TlJniEnvCache::~TlJniEnvCache().
/* // SK:
  {
    const long long total_us =
        std::chrono::duration_cast<Micros>(Clock::now() - t_total_start)
            .count();
    ROCKS_LOG_INFO(logger,
        "Merge total=%lld env=%lld wrap=%lld jni=%lld copy=%lld "
        "overhead=%lld (success=%d)",
        total_us, env_us, wrap_us, jni_us, copy_us,
        total_us - env_us - wrap_us - jni_us - copy_us,
        static_cast<int>(success));
  }
*/
  return success;
}

}  // namespace ROCKSDB_NAMESPACE
