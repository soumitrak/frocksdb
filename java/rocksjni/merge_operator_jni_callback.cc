// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// This source code is licensed under both the GPLv2 (found in the
// COPYING file in the root directory) and Apache 2.0 License
// (found in the LICENSE.Apache file in the root directory).

#include "rocksjni/merge_operator_jni_callback.h"
#include "rocksjni/portal.h"
#include "rocksjni/cplusplus_to_java_convert.h"

namespace ROCKSDB_NAMESPACE {

MergeOperatorJniCallback::MergeOperatorJniCallback(JNIEnv* env,
                                                   jobject jmerge_operator)
    : JniCallback(env, jmerge_operator),
      m_abstract_merge_operator_jni_bridge_clazz(nullptr),
      m_jbytebuffer_clazz(nullptr),
      m_jfull_merge_mid(nullptr),
      m_jpartial_merge_mid(nullptr) {

  // Get and cache the bridge class globally
  m_abstract_merge_operator_jni_bridge_clazz =
      AbstractMergeOperatorJniBridge::getJClass(env);
  if (m_abstract_merge_operator_jni_bridge_clazz == nullptr) {
    fprintf(stderr, "Failed to get AbstractMergeOperatorJniBridge class\n");
    return;
  }
  m_abstract_merge_operator_jni_bridge_clazz =
      static_cast<jclass>(env->NewGlobalRef(
          m_abstract_merge_operator_jni_bridge_clazz));

  // Cache ByteBuffer class — needed to build the operand ByteBuffer[] array
  jclass local_bb_clazz = env->FindClass("java/nio/ByteBuffer");
  if (local_bb_clazz == nullptr) {
    fprintf(stderr, "Failed to get ByteBuffer class\n");
    return;
  }
  m_jbytebuffer_clazz =
      static_cast<jclass>(env->NewGlobalRef(local_bb_clazz));
  env->DeleteLocalRef(local_bb_clazz);

  // Cache method IDs
  m_jfull_merge_mid = AbstractMergeOperatorJniBridge::getFullMergeInternalMethodId(
      env, m_abstract_merge_operator_jni_bridge_clazz);
  if (m_jfull_merge_mid == nullptr) {
    fprintf(stderr, "Failed to get fullMergeInternal method ID\n");
    return;
  }

  m_jpartial_merge_mid = AbstractMergeOperatorJniBridge::getPartialMergeInternalMethodId(
      env, m_abstract_merge_operator_jni_bridge_clazz);
  if (m_jpartial_merge_mid == nullptr) {
    fprintf(stderr, "Failed to get partialMergeInternal method ID\n");
    return;
  }

  // Fetch and cache the operator name from Java
  jmethodID jname_mid = AbstractMergeOperatorJni::getNameMethodId(env);
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

MergeOperatorJniCallback::~MergeOperatorJniCallback() {
  jboolean attached_thread = JNI_FALSE;
  JNIEnv* env = getJniEnv(&attached_thread);
  if (env == nullptr) {
    return;
  }
  if (m_abstract_merge_operator_jni_bridge_clazz != nullptr) {
    env->DeleteGlobalRef(m_abstract_merge_operator_jni_bridge_clazz);
  }
  if (m_jbytebuffer_clazz != nullptr) {
    env->DeleteGlobalRef(m_jbytebuffer_clazz);
  }
  releaseJniEnv(attached_thread);
}

const char* MergeOperatorJniCallback::Name() const {
  return m_name.get();
}

bool MergeOperatorJniCallback::FullMergeV2(
    const MergeOperationInput& merge_in,
    MergeOperationOutput* merge_out) const {
  jboolean attached_thread = JNI_FALSE;
  JNIEnv* env = getJniEnv(&attached_thread);
  if (env == nullptr) {
    return false;
  }

  // All locals declared before the first goto to avoid jump-past-init errors.
  jobject jkey = nullptr;
  jobject jexisting = nullptr;
  jobjectArray joperands = nullptr;
  jintArray joperand_lens = nullptr;
  jbyteArray jresult = nullptr;
  bool success = false;

  // Wrap key Slice as a direct ByteBuffer — zero-copy, valid for this call.
  jkey = env->NewDirectByteBuffer(
      const_cast<void*>(static_cast<const void*>(merge_in.key.data())),
      static_cast<jlong>(merge_in.key.size()));
  if (jkey == nullptr || env->ExceptionCheck()) {
    env->ExceptionClear();
    goto cleanup;
  }

  // Wrap existing value if present.
  if (merge_in.existing_value != nullptr) {
    jexisting = env->NewDirectByteBuffer(
        const_cast<void*>(
            static_cast<const void*>(merge_in.existing_value->data())),
        static_cast<jlong>(merge_in.existing_value->size()));
    if (jexisting == nullptr || env->ExceptionCheck()) {
      env->ExceptionClear();
      goto cleanup;
    }
  }

  // Build ByteBuffer[] for operands.
  joperands = env->NewObjectArray(
      static_cast<jsize>(merge_in.operand_list.size()),
      m_jbytebuffer_clazz, nullptr);
  if (joperands == nullptr || env->ExceptionCheck()) {
    env->ExceptionClear();
    goto cleanup;
  }

  joperand_lens = env->NewIntArray(
      static_cast<jsize>(merge_in.operand_list.size()));
  if (joperand_lens == nullptr || env->ExceptionCheck()) {
    env->ExceptionClear();
    goto cleanup;
  }

  for (size_t i = 0; i < merge_in.operand_list.size(); i++) {
    const Slice& operand = merge_in.operand_list[i];
    jobject joperand = env->NewDirectByteBuffer(
        const_cast<void*>(static_cast<const void*>(operand.data())),
        static_cast<jlong>(operand.size()));
    if (joperand == nullptr || env->ExceptionCheck()) {
      env->ExceptionClear();
      goto cleanup;
    }
    env->SetObjectArrayElement(joperands, static_cast<jsize>(i), joperand);
    env->DeleteLocalRef(joperand);
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
      goto cleanup;
    }
    jint operand_len = static_cast<jint>(operand.size());
    env->SetIntArrayRegion(joperand_lens, static_cast<jsize>(i), 1,
                           &operand_len);
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
      goto cleanup;
    }
  }

  // Invoke Java fullMergeInternal(operator, key, keyLen, existing,
  //                                existingLen, operands, operandLens)
  jresult = static_cast<jbyteArray>(env->CallStaticObjectMethod(
      m_abstract_merge_operator_jni_bridge_clazz,
      m_jfull_merge_mid,
      m_jcallback_obj,
      jkey,
      static_cast<jint>(merge_in.key.size()),
      jexisting,
      merge_in.existing_value != nullptr
          ? static_cast<jint>(merge_in.existing_value->size())
          : -1,
      joperands,
      joperand_lens));

  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    goto cleanup;
  }

  if (jresult != nullptr) {
    success = CopyByteArrayToString(env, jresult, &merge_out->new_value);
  }

cleanup:
  if (jkey != nullptr) env->DeleteLocalRef(jkey);
  if (jexisting != nullptr) env->DeleteLocalRef(jexisting);
  if (joperands != nullptr) env->DeleteLocalRef(joperands);
  if (joperand_lens != nullptr) env->DeleteLocalRef(joperand_lens);
  if (jresult != nullptr) env->DeleteLocalRef(jresult);
  releaseJniEnv(attached_thread);
  return success;
}

bool MergeOperatorJniCallback::PartialMerge(
    const Slice& key,
    const Slice& left_operand,
    const Slice& right_operand,
    std::string* new_value,
    Logger* /*logger*/) const {
  jboolean attached_thread = JNI_FALSE;
  JNIEnv* env = getJniEnv(&attached_thread);
  if (env == nullptr) {
    return false;
  }

  jobject jkey = nullptr;
  jobject jleft = nullptr;
  jobject jright = nullptr;
  jbyteArray jresult = nullptr;
  bool success = false;

  jkey = env->NewDirectByteBuffer(
      const_cast<void*>(static_cast<const void*>(key.data())),
      static_cast<jlong>(key.size()));
  if (jkey == nullptr || env->ExceptionCheck()) {
    env->ExceptionClear();
    goto cleanup;
  }

  jleft = env->NewDirectByteBuffer(
      const_cast<void*>(static_cast<const void*>(left_operand.data())),
      static_cast<jlong>(left_operand.size()));
  if (jleft == nullptr || env->ExceptionCheck()) {
    env->ExceptionClear();
    goto cleanup;
  }

  jright = env->NewDirectByteBuffer(
      const_cast<void*>(static_cast<const void*>(right_operand.data())),
      static_cast<jlong>(right_operand.size()));
  if (jright == nullptr || env->ExceptionCheck()) {
    env->ExceptionClear();
    goto cleanup;
  }

  // Invoke Java partialMergeInternal(operator, key, keyLen,
  //                                   left, leftLen, right, rightLen)
  jresult = static_cast<jbyteArray>(env->CallStaticObjectMethod(
      m_abstract_merge_operator_jni_bridge_clazz,
      m_jpartial_merge_mid,
      m_jcallback_obj,
      jkey,
      static_cast<jint>(key.size()),
      jleft,
      static_cast<jint>(left_operand.size()),
      jright,
      static_cast<jint>(right_operand.size())));

  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    goto cleanup;
  }

  if (jresult != nullptr) {
    // null return means "decline partial merge" — leave new_value untouched
    success = CopyByteArrayToString(env, jresult, new_value);
  }

cleanup:
  if (jkey != nullptr) env->DeleteLocalRef(jkey);
  if (jleft != nullptr) env->DeleteLocalRef(jleft);
  if (jright != nullptr) env->DeleteLocalRef(jright);
  if (jresult != nullptr) env->DeleteLocalRef(jresult);
  releaseJniEnv(attached_thread);
  return success;
}

bool MergeOperatorJniCallback::CopyByteArrayToString(JNIEnv* env,
                                                      jbyteArray jarray,
                                                      std::string* output) const {
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
