// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// This source code is licensed under both the GPLv2 (found in the
// COPYING file in the root directory) and Apache 2.0 License
// (found in the LICENSE.Apache file in the root directory).

import org.rocksdb.Options;
import org.rocksdb.RocksDBException;

/**
 * CppMergeOperatorBenchmark benchmarks the native C++ SortedStringMergeOperator,
 * resolved by factory name via {@code Options.setMergeOperatorName()}.
 *
 * <p>The operator is registered in the RocksDB merge-operator factory under the
 * class name {@code "SortedStringMergeOperator"} (nick name {@code "sortedstring"}).
 * When the DB is opened, RocksDB resolves the name through
 * {@code MergeOperators::CreateFromStringId()} and instantiates the C++
 * implementation. All merge computation (FullMergeV2 and PartialMerge) therefore
 * happens natively without any JNI boundary crossing per merge call.
 *
 * <p>This serves as the baseline for comparing against {@link JavaMergeOperatorBenchmark},
 * where merge logic runs in Java and is invoked via JNI callbacks.
 *
 * <p>Usage:
 * <pre>
 *   java -Djava.library.path=target -cp target/classes:samples/target/classes \
 *     CppMergeOperatorBenchmark [dbPath [numKeys [mergesPerKey [stringLen]]]]
 * </pre>
 *
 * <p>Default parameters: 10 keys, 2000 merges/key, 8-character strings.
 */
public class CppMergeOperatorBenchmark extends MergeOperatorBenchmark {

  private static final int DEFAULT_NUM_KEYS = 10;
  private static final int DEFAULT_MERGES_PER_KEY = 2000;
  private static final int DEFAULT_STRING_LEN = 8;

  /** Factory name used to resolve the operator via {@code MergeOperators::CreateFromStringId}. */
  private static final String OPERATOR_NAME = "SortedStringMergeOperator";

  @Override
  protected void configureOptions(final Options opts) {
    opts.setMergeOperatorName(OPERATOR_NAME);
  }

  @Override
  protected String operatorDescription() {
    return "SortedStringMergeOperator (native C++, resolved by factory name \""
        + OPERATOR_NAME + "\")";
  }

  public static void main(final String[] args) throws RocksDBException {
    final String dbPath = args.length > 0 ? args[0] : "/tmp/merge_bench_cpp";
    final int numKeys = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_NUM_KEYS;
    final int mergesPerKey = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_MERGES_PER_KEY;
    final int stringLen = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_STRING_LEN;

    new CppMergeOperatorBenchmark().run(dbPath, numKeys, mergesPerKey, stringLen);
  }
}
