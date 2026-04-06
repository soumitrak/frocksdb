// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// Copyright (c) 2014, Vlad Balan (vlad.gm@gmail.com).  All rights reserved.
//  This source code is licensed under both the GPLv2 (found in the
//  COPYING file in the root directory) and Apache 2.0 License
//  (found in the LICENSE.Apache file in the root directory).
//
// This file implements the "bridge" between Java and C++
// for ROCKSDB_NAMESPACE::MergeOperator.

#include "rocksdb/merge_operator.h"

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>

#include <memory>
#include <string>

#include "include/org_rocksdb_AbstractAssociativeMergeOperator.h"
#include "include/org_rocksdb_AbstractMergeOperator.h"
#include "include/org_rocksdb_StringAppendOperator.h"
#include "include/org_rocksdb_UInt64AddOperator.h"
#include "rocksdb/db.h"
#include "rocksdb/memtablerep.h"
#include "rocksdb/options.h"
#include "rocksdb/slice_transform.h"
#include "rocksdb/statistics.h"
#include "rocksdb/table.h"
#include "rocksjni/cplusplus_to_java_convert.h"
#include "rocksjni/associative_merge_operator_jni_callback.h"
#include "rocksjni/merge_operator_jni_callback.h"
#include "rocksjni/portal.h"
#include "utilities/merge_operators.h"

/*
 * Class:     org_rocksdb_StringAppendOperator
 * Method:    newSharedStringAppendOperator
 * Signature: (C)J
 */
jlong Java_org_rocksdb_StringAppendOperator_newSharedStringAppendOperator__C(
    JNIEnv* /*env*/, jclass /*jclazz*/, jchar jdelim) {
  auto* sptr_string_append_op =
      new std::shared_ptr<ROCKSDB_NAMESPACE::MergeOperator>(
          ROCKSDB_NAMESPACE::MergeOperators::CreateStringAppendOperator(
              (char)jdelim));
  return GET_CPLUSPLUS_POINTER(sptr_string_append_op);
}

jlong Java_org_rocksdb_StringAppendOperator_newSharedStringAppendOperator__Ljava_lang_String_2(
    JNIEnv* env, jclass /*jclass*/, jstring jdelim) {
  jboolean has_exception = JNI_FALSE;
  auto delim =
      ROCKSDB_NAMESPACE::JniUtil::copyStdString(env, jdelim, &has_exception);
  if (has_exception == JNI_TRUE) {
    return 0;
  }
  auto* sptr_string_append_op =
      new std::shared_ptr<ROCKSDB_NAMESPACE::MergeOperator>(
          ROCKSDB_NAMESPACE::MergeOperators::CreateStringAppendOperator(delim));
  return GET_CPLUSPLUS_POINTER(sptr_string_append_op);
}

/*
 * Class:     org_rocksdb_StringAppendOperator
 * Method:    disposeInternal
 * Signature: (J)V
 */
void Java_org_rocksdb_StringAppendOperator_disposeInternal(JNIEnv* /*env*/,
                                                           jobject /*jobj*/,
                                                           jlong jhandle) {
  auto* sptr_string_append_op =
      reinterpret_cast<std::shared_ptr<ROCKSDB_NAMESPACE::MergeOperator>*>(
          jhandle);
  delete sptr_string_append_op;  // delete std::shared_ptr
}

/*
 * Class:     org_rocksdb_UInt64AddOperator
 * Method:    newSharedUInt64AddOperator
 * Signature: ()J
 */
jlong Java_org_rocksdb_UInt64AddOperator_newSharedUInt64AddOperator(
    JNIEnv* /*env*/, jclass /*jclazz*/) {
  auto* sptr_uint64_add_op =
      new std::shared_ptr<ROCKSDB_NAMESPACE::MergeOperator>(
          ROCKSDB_NAMESPACE::MergeOperators::CreateUInt64AddOperator());
  return GET_CPLUSPLUS_POINTER(sptr_uint64_add_op);
}

/*
 * Class:     org_rocksdb_UInt64AddOperator
 * Method:    disposeInternal
 * Signature: (J)V
 */
void Java_org_rocksdb_UInt64AddOperator_disposeInternal(JNIEnv* /*env*/,
                                                        jobject /*jobj*/,
                                                        jlong jhandle) {
  auto* sptr_uint64_add_op =
      reinterpret_cast<std::shared_ptr<ROCKSDB_NAMESPACE::MergeOperator>*>(
          jhandle);
  delete sptr_uint64_add_op;  // delete std::shared_ptr
}

/*
 * Class:     org_rocksdb_AbstractMergeOperator
 * Method:    createNewMergeOperator
 * Signature: ()J
 */
jlong Java_org_rocksdb_AbstractMergeOperator_createNewMergeOperator(
    JNIEnv* env, jobject jmerge_operator) {
  auto* callback = new ROCKSDB_NAMESPACE::MergeOperatorJniCallback(
      env, jmerge_operator);
  auto* sptr =
      new std::shared_ptr<ROCKSDB_NAMESPACE::MergeOperator>(callback);
  return GET_CPLUSPLUS_POINTER(sptr);
}

/*
 * Class:     org_rocksdb_AbstractMergeOperator
 * Method:    disposeInternal
 * Signature: (J)V
 */
void Java_org_rocksdb_AbstractMergeOperator_disposeInternal(
    JNIEnv* /*env*/, jobject /*jobj*/, jlong jhandle) {
  auto* sptr =
      reinterpret_cast<std::shared_ptr<ROCKSDB_NAMESPACE::MergeOperator>*>(
          jhandle);
  delete sptr;  // delete std::shared_ptr wrapper
}

/*
 * Class:     org_rocksdb_AbstractAssociativeMergeOperator
 * Method:    createNewAssociativeMergeOperator
 * Signature: ()J
 */
jlong Java_org_rocksdb_AbstractAssociativeMergeOperator_createNewAssociativeMergeOperator(
    JNIEnv* env, jobject joperator) {
  auto* callback =
      new ROCKSDB_NAMESPACE::AssociativeMergeOperatorJniCallback(
          env, joperator);
  // Upcast AssociativeMergeOperator* to MergeOperator* in the shared_ptr so
  // that the handle type is identical to AbstractMergeOperator handles.  This
  // lets Options::setMergeOperator reuse the same native method for both.
  auto* sptr =
      new std::shared_ptr<ROCKSDB_NAMESPACE::MergeOperator>(callback);
  return GET_CPLUSPLUS_POINTER(sptr);
}

/*
 * Class:     org_rocksdb_AbstractAssociativeMergeOperator
 * Method:    disposeInternal
 * Signature: (J)V
 */
void Java_org_rocksdb_AbstractAssociativeMergeOperator_disposeInternal(
    JNIEnv* /*env*/, jobject /*jobj*/, jlong jhandle) {
  auto* sptr =
      reinterpret_cast<std::shared_ptr<ROCKSDB_NAMESPACE::MergeOperator>*>(
          jhandle);
  delete sptr;  // delete std::shared_ptr wrapper
}
