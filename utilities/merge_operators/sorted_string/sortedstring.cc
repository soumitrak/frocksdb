// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// This source code is licensed under both the GPLv2 (found in the
// COPYING file in the root directory) and Apache 2.0 License
// (found in the LICENSE.Apache file in the root directory).

#include "utilities/merge_operators/sorted_string/sortedstring.h"

#include <algorithm>

namespace ROCKSDB_NAMESPACE {

void SortedStringMergeOperator::SplitInto(const Slice& s,
                                          std::vector<std::string>* out) {
  if (s.empty()) return;
  const char* p = s.data();
  const char* const end = p + s.size();
  const char* token_start = p;
  while (p <= end) {
    if (p == end || *p == ',') {
      if (p > token_start) {
        out->emplace_back(token_start, p - token_start);
      }
      token_start = p + 1;
    }
    ++p;
  }
}

std::string SortedStringMergeOperator::Join(
    const std::vector<std::string>& items) {
  if (items.empty()) return {};
  std::string result;
  // Reserve approximate capacity to avoid repeated reallocations
  size_t total = items.size() - 1;  // commas
  for (const auto& s : items) total += s.size();
  result.reserve(total);
  for (size_t i = 0; i < items.size(); ++i) {
    if (i > 0) result += ',';
    result += items[i];
  }
  return result;
}

std::vector<std::string> SortedStringMergeOperator::MergeSorted(
    const std::vector<std::string>& left,
    const std::vector<std::string>& right) {
  std::vector<std::string> merged;
  merged.reserve(left.size() + right.size());
  std::merge(left.begin(), left.end(), right.begin(), right.end(),
             std::back_inserter(merged));
  return merged;
}

bool SortedStringMergeOperator::FullMergeV2(
    const MergeOperationInput& merge_in,
    MergeOperationOutput* merge_out) const {
  std::vector<std::string> all_items;

  // Collect items from the existing base value (already sorted)
  if (merge_in.existing_value != nullptr) {
    SplitInto(*merge_in.existing_value, &all_items);
  }

  // Collect items from each operand (each may be a single string or a
  // sorted comma-separated list produced by previous PartialMerge calls)
  for (const auto& operand : merge_in.operand_list) {
    SplitInto(operand, &all_items);
  }

  // Sort all collected items and return the joined result
  std::sort(all_items.begin(), all_items.end());
  merge_out->new_value = Join(all_items);
  return true;
}

bool SortedStringMergeOperator::PartialMerge(const Slice& /*key*/,
                                              const Slice& left,
                                              const Slice& right,
                                              std::string* new_value,
                                              Logger* /*logger*/) const {
  // Both left and right are sorted comma-separated lists; merge-sort them
  std::vector<std::string> left_items;
  std::vector<std::string> right_items;
  SplitInto(left, &left_items);
  SplitInto(right, &right_items);

  auto merged = MergeSorted(left_items, right_items);
  *new_value = Join(merged);
  return true;
}

}  // namespace ROCKSDB_NAMESPACE
