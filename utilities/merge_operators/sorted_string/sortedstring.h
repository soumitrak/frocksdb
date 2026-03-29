// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// This source code is licensed under both the GPLv2 (found in the
// COPYING file in the root directory) and Apache 2.0 License
// (found in the LICENSE.Apache file in the root directory).
//
// A MergeOperator for RocksDB that maintains values as comma-separated
// sorted lists of strings and performs merge-sort style merging.

#pragma once

#include <string>
#include <vector>

#include "rocksdb/merge_operator.h"
#include "rocksdb/slice.h"

namespace ROCKSDB_NAMESPACE {

/**
 * SortedStringMergeOperator maintains values as comma-separated sorted lists
 * of strings. Each merge operand is treated as either a single string or an
 * already-sorted comma-separated list (produced by PartialMerge during
 * compaction). FullMergeV2 collects all items from the existing value and all
 * operands, sorts them, and returns the joined result. PartialMerge performs
 * a two-way merge sort on two already-sorted lists, progressively reducing
 * operand count during compaction.
 *
 * Factory name  : "SortedStringMergeOperator"
 * Short nickname: "sortedstring"
 *
 * Usage from Java:
 *   options.setMergeOperatorName("SortedStringMergeOperator");
 *   // or equivalently:
 *   options.setMergeOperatorName("sortedstring");
 */
class SortedStringMergeOperator : public MergeOperator {
 public:
  static const char* kClassName() { return "SortedStringMergeOperator"; }
  static const char* kNickName() { return "sortedstring"; }
  const char* Name() const override { return kClassName(); }
  const char* NickName() const override { return kNickName(); }

  bool FullMergeV2(const MergeOperationInput& merge_in,
                   MergeOperationOutput* merge_out) const override;

  bool PartialMerge(const Slice& key, const Slice& left, const Slice& right,
                    std::string* new_value, Logger* logger) const override;

 private:
  // Splits a comma-separated Slice into individual strings, appending to out.
  static void SplitInto(const Slice& s, std::vector<std::string>* out);

  // Joins a vector of strings into a single comma-separated string.
  static std::string Join(const std::vector<std::string>& items);

  // Merges two sorted string vectors via std::merge, returning the result.
  static std::vector<std::string> MergeSorted(
      const std::vector<std::string>& left,
      const std::vector<std::string>& right);
};

}  // namespace ROCKSDB_NAMESPACE
