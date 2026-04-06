// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// This source code is licensed under both the GPLv2 (found in the
// COPYING file in the root directory) and Apache 2.0 License
// (found in the LICENSE.Apache file in the root directory).

#include "rocksjni/associative_merge_operator_jni_callback.h"
#include "rocksjni/portal.h"
#include "rocksjni/cplusplus_to_java_convert.h"

namespace ROCKSDB_NAMESPACE {

AssociativeMergeOperatorJniCallback::AssociativeMergeOperatorJniCallback(
    JNIEnv* env, jobject joperator)
    : JniCallback(env, joperator),
      m_bridge_clazz(nullptr),
      m_jbytebuffer_clazz(nullptr),
      m_jmerge_mid(nullptr) {

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
  releaseJniEnv(attached_thread);
}

const char* AssociativeMergeOperatorJniCallback::Name() const {
  return m_name.get();
}

bool AssociativeMergeOperatorJniCallback::Merge(
    const Slice& key,
    const Slice* existing_value,
    const Slice& value,
    std::string* new_value,
    Logger* /*logger*/) const {
  jboolean attached_thread = JNI_FALSE;
  JNIEnv* env = getJniEnv(&attached_thread);
  if (env == nullptr) {
    return false;
  }

  // All locals declared before the first goto to avoid jump-past-init errors.
  jobject jkey = nullptr;
  jobject jexisting = nullptr;
  jobject jvalue = nullptr;
  jbyteArray jresult = nullptr;
  bool success = false;

  // Wrap key Slice as a direct ByteBuffer — zero-copy.
  jkey = env->NewDirectByteBuffer(
      const_cast<void*>(static_cast<const void*>(key.data())),
      static_cast<jlong>(key.size()));
  if (jkey == nullptr || env->ExceptionCheck()) {
    env->ExceptionClear();
    goto cleanup;
  }

  // Wrap existing value if present.
  if (existing_value != nullptr) {
    jexisting = env->NewDirectByteBuffer(
        const_cast<void*>(static_cast<const void*>(existing_value->data())),
        static_cast<jlong>(existing_value->size()));
    if (jexisting == nullptr || env->ExceptionCheck()) {
      env->ExceptionClear();
      goto cleanup;
    }
  }

  // Wrap merge operand.
  jvalue = env->NewDirectByteBuffer(
      const_cast<void*>(static_cast<const void*>(value.data())),
      static_cast<jlong>(value.size()));
  if (jvalue == nullptr || env->ExceptionCheck()) {
    env->ExceptionClear();
    goto cleanup;
  }

  // Invoke Java mergeInternal(operator, key, keyLen, existing, existingLen,
  //                            value, valueLen)
  jresult = static_cast<jbyteArray>(env->CallStaticObjectMethod(
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
      static_cast<jint>(value.size())));

  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    goto cleanup;
  }

  if (jresult != nullptr) {
    success = CopyByteArrayToString(env, jresult, new_value);
  }

cleanup:
  if (jkey != nullptr) env->DeleteLocalRef(jkey);
  if (jexisting != nullptr) env->DeleteLocalRef(jexisting);
  if (jvalue != nullptr) env->DeleteLocalRef(jvalue);
  if (jresult != nullptr) env->DeleteLocalRef(jresult);
  releaseJniEnv(attached_thread);
  return success;
}

bool AssociativeMergeOperatorJniCallback::CopyByteArrayToString(
    JNIEnv* env, jbyteArray jarray, std::string* output) const {
  if (jarray == nullptr) {
    return false;
  }
  jsize len = env->GetArrayLength(jarray);
  if (len < 0) {
    return false;
  }
  jbyte* data = env->GetByteArrayElements(jarray, nullptr);
  if (data == nullptr) {
    return false;
  }
  output->assign(reinterpret_cast<const char*>(data), len);
  env->ReleaseByteArrayElements(jarray, data, JNI_ABORT);
  return true;
}

}  // namespace ROCKSDB_NAMESPACE
