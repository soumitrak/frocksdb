// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// This source code is licensed under both the GPLv2 (found in the
// COPYING file in the root directory) and Apache 2.0 License
// (found in the LICENSE.Apache file in the root directory).

import org.rocksdb.JavaSortedStringMergeOperator;
import org.rocksdb.Options;
import org.rocksdb.RocksDBException;

/**
 * JavaMergeOperatorBenchmark benchmarks the pure-Java SortedStringMergeOperator.
 *
 * <p>The merge operator is implemented in Java by extending
 * {@link org.rocksdb.AbstractMergeOperator}. Every time RocksDB needs to
 * evaluate a merge (during {@code db.get()} or compaction), the C++ engine
 * invokes the Java callbacks {@code fullMerge} and {@code partialMerge} via
 * JNI. This benchmark measures the additional overhead of those JNI crossings
 * compared to the baseline established by {@link CppMergeOperatorBenchmark}.
 *
 * <p>Usage:
 * <pre>
 *   java -Djava.library.path=target -cp target/classes:samples/target/classes \
 *     JavaMergeOperatorBenchmark [dbPath [numKeys [mergesPerKey [stringLen]]]]
 * </pre>
 *
 * <p>Default parameters: 10 keys, 2000 merges/key, 8-character strings.
 */
public class JavaMergeOperatorBenchmark extends MergeOperatorBenchmark {

  private static final int DEFAULT_NUM_KEYS = 10;
  private static final int DEFAULT_MERGES_PER_KEY = 2000;
  private static final int DEFAULT_STRING_LEN = 8;

  @Override
  protected void configureOptions(final Options opts) {
    opts.setMergeOperator(new JavaSortedStringMergeOperator());
  }

  @Override
  protected String operatorDescription() {
    return "JavaSortedStringMergeOperator (pure Java, invoked via JNI callbacks)";
  }

  public static void main(final String[] args) throws RocksDBException {
    final String dbPath = args.length > 0 ? args[0] : "/tmp/merge_bench_java";
    final int numKeys = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_NUM_KEYS;
    final int mergesPerKey = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_MERGES_PER_KEY;
    final int stringLen = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_STRING_LEN;

    new JavaMergeOperatorBenchmark().run(dbPath, numKeys, mergesPerKey, stringLen);
  }
}
