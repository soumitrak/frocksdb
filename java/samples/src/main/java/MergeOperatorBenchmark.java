// Copyright (c) Facebook, Inc. and its affiliates. All Rights Reserved.
// This source code is licensed under both the GPLv2 (found in the
// COPYING file in the root directory) and Apache 2.0 License
// (found in the LICENSE.Apache file in the root directory).

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

// Load the native library when this class is first referenced, exactly as
// RocksDBSample does.  The Makefile passes -Djava.library.path=target and
// also puts target/* on the classpath so the JAR-embedded .so is found when
// the .so is not extracted as a standalone file.


/**
 * MergeOperatorBenchmark is an abstract benchmark driver for measuring the
 * overhead of JNI when calling a RocksDB merge operator.
 *
 * <p>The operator keeps sorted strings concatenated by comma. Each merge
 * operand is a single random string; the merge operation implements a
 * merge-sort routine to keep the accumulated value sorted.
 *
 * <p>The benchmark measures four phases separately:
 * <ol>
 *   <li><b>Write</b>: issue {@code numKeys * mergesPerKey} {@code db.merge()}
 *       calls. No merge computation happens here; operands are buffered in the
 *       memtable.</li>
 *   <li><b>Read (pre-compact)</b>: call {@code db.get()} for each key. This
 *       triggers a {@code FullMerge} with all {@code mergesPerKey} operands,
 *       which is the primary site of JNI overhead for the Java operator.</li>
 *   <li><b>Compact</b>: call {@code db.compactRange()} to flush and compact.
 *       This triggers {@code PartialMerge} calls, progressively reducing
 *       operands and measuring compaction-time JNI overhead.</li>
 *   <li><b>Read (post-compact)</b>: call {@code db.get()} again. After
 *       compaction the value is already merged and stored as a regular record,
 *       so this phase is expected to be fast for both operators.</li>
 * </ol>
 *
 * <p>Subclasses implement {@link #configureOptions} to plug in either the
 * native C++ or the pure-Java merge operator.
 */
public abstract class MergeOperatorBenchmark {

  static {
    RocksDB.loadLibrary();
  }

  private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz";

  /** Configures the merge operator on the given Options before opening the DB. */
  protected abstract void configureOptions(Options opts);

  /** Human-readable description of the operator under test. */
  protected abstract String operatorDescription();

  /**
   * Runs the full benchmark.
   *
   * @param dbPath       path for the temporary RocksDB instance
   * @param numKeys      number of distinct keys to use
   * @param mergesPerKey number of merge operands written per key
   * @param stringLen    length of each randomly-generated operand string
   */
  public final void run(final String dbPath, final int numKeys,
                        final int mergesPerKey, final int stringLen)
      throws RocksDBException {

    System.out.println("╔══════════════════════════════════════════════════════╗");
    System.out.println("║         Merge Operator JNI Overhead Benchmark        ║");
    System.out.println("╚══════════════════════════════════════════════════════╝");
    System.out.println("  Operator   : " + operatorDescription());
    System.out.println("  Keys       : " + numKeys);
    System.out.println("  Merges/key : " + mergesPerKey);
    System.out.println("  Total ops  : " + (numKeys * mergesPerKey));
    System.out.println("  String len : " + stringLen);
    System.out.println();

    // Pre-generate all test data so data generation cost is excluded from timing
    final Random rng = new Random(0xdeadbeefL);
    final byte[][] keyBytes = new byte[numKeys][];
    final byte[][][] operandBytes = new byte[numKeys][mergesPerKey][];
    for (int k = 0; k < numKeys; k++) {
      keyBytes[k] = ("key_" + String.format("%04d", k)).getBytes(StandardCharsets.UTF_8);
      for (int m = 0; m < mergesPerKey; m++) {
        operandBytes[k][m] = randomString(rng, stringLen).getBytes(StandardCharsets.UTF_8);
      }
    }

    deleteDirectory(dbPath);

    try (final Options opts = new Options().setCreateIfMissing(true)) {
      configureOptions(opts);

      try (final RocksDB db = RocksDB.open(opts, dbPath)) {

        // ── Phase 1: Write ───────────────────────────────────────────────────
        final long writeStart = System.nanoTime();
        for (int k = 0; k < numKeys; k++) {
          for (int m = 0; m < mergesPerKey; m++) {
            db.merge(keyBytes[k], operandBytes[k][m]);
          }
        }
        final long writeNs = System.nanoTime() - writeStart;
        printPhase("Phase 1 (Write)", writeNs,
            numKeys * mergesPerKey + " merge calls, "
                + String.format("%.2f", nsPerOp(writeNs, numKeys * mergesPerKey))
                + " ns/op");

        // ── Phase 2: Read (pre-compact) ──────────────────────────────────────
        // Each get() triggers FullMerge across all mergesPerKey operands.
        final byte[][] preCompactResults = new byte[numKeys][];
        final long readPreStart = System.nanoTime();
        for (int k = 0; k < numKeys; k++) {
          preCompactResults[k] = db.get(keyBytes[k]);
        }
        final long readPreNs = System.nanoTime() - readPreStart;
        printPhase("Phase 2 (Read pre-compact)", readPreNs,
            numKeys + " get calls, "
                + String.format("%.2f", nsPerOp(readPreNs, numKeys)) + " ns/op");

        // ── Phase 3: Compact ─────────────────────────────────────────────────
        // Flushes memtable and triggers PartialMerge + FullMerge in compaction.
        final long compactStart = System.nanoTime();
        db.compactRange();
        final long compactNs = System.nanoTime() - compactStart;
        printPhase("Phase 3 (Compact)", compactNs, "compactRange()");

        // ── Phase 4: Read (post-compact) ─────────────────────────────────────
        // After compaction the value is already merged; this measures read cost.
        final byte[][] postCompactResults = new byte[numKeys][];
        final long readPostStart = System.nanoTime();
        for (int k = 0; k < numKeys; k++) {
          postCompactResults[k] = db.get(keyBytes[k]);
        }
        final long readPostNs = System.nanoTime() - readPostStart;
        printPhase("Phase 4 (Read post-compact)", readPostNs,
            numKeys + " get calls, "
                + String.format("%.2f", nsPerOp(readPostNs, numKeys)) + " ns/op");

        // ── Phase 5: Validate ─────────────────────────────────────────────────
        System.out.println();
        System.out.println("── Validation ─────────────────────────────────────────");
        int passedPre = validate(keyBytes, preCompactResults, mergesPerKey, "pre-compact");
        int passedPost = validate(keyBytes, postCompactResults, mergesPerKey, "post-compact");

        System.out.println();
        System.out.println("── Summary ─────────────────────────────────────────────");
        final long totalJniBoundaryNs = readPreNs + compactNs;
        System.out.printf("  Write                  : %8d ms%n", writeNs / 1_000_000);
        System.out.printf("  Read (pre-compact)     : %8d ms  ← FullMerge cost%n",
            readPreNs / 1_000_000);
        System.out.printf("  Compact                : %8d ms  ← PartialMerge cost%n",
            compactNs / 1_000_000);
        System.out.printf("  Read (post-compact)    : %8d ms%n", readPostNs / 1_000_000);
        System.out.printf("  Merge-relevant total   : %8d ms  (read + compact)%n",
            totalJniBoundaryNs / 1_000_000);
        System.out.println();
        if (passedPre == numKeys && passedPost == numKeys) {
          System.out.println("  VALIDATION PASSED: all " + numKeys + " keys produce"
              + " correctly sorted values");
        } else {
          System.out.println("  VALIDATION FAILED: pre=" + passedPre + "/" + numKeys
              + "  post=" + passedPost + "/" + numKeys);
        }
        System.out.println();
      }
    }

    deleteDirectory(dbPath);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private static void printPhase(final String name, final long nanos,
                                  final String detail) {
    System.out.printf("  %-28s %6d ms   (%s)%n", name + ":", nanos / 1_000_000, detail);
  }

  private static double nsPerOp(final long nanos, final int ops) {
    return ops == 0 ? 0.0 : (double) nanos / ops;
  }

  /**
   * Validates that every result is non-null, has exactly {@code expectedCount}
   * comma-separated items, and that all items are in non-descending order.
   *
   * @return the number of keys that passed validation
   */
  private int validate(final byte[][] keys, final byte[][] results,
                       final int expectedCount, final String label) {
    int passed = 0;
    for (int k = 0; k < keys.length; k++) {
      final String keyName = new String(keys[k], StandardCharsets.UTF_8);
      if (results[k] == null) {
        System.out.println("  [FAIL/" + label + "] " + keyName + ": result is null");
        continue;
      }
      final String value = new String(results[k], StandardCharsets.UTF_8);
      final String[] items = value.split(",", -1);
      if (items.length != expectedCount) {
        System.out.println("  [FAIL/" + label + "] " + keyName + ": expected "
            + expectedCount + " items but got " + items.length);
        continue;
      }
      int violationIdx = firstSortViolation(items);
      if (violationIdx >= 0) {
        System.out.println("  [FAIL/" + label + "] " + keyName + ": not sorted at index "
            + violationIdx + " (\"" + items[violationIdx - 1] + "\" > \""
            + items[violationIdx] + "\")");
        continue;
      }
      passed++;
    }
    System.out.println("  Validation (" + label + "): " + passed + "/" + keys.length
        + " keys OK");
    return passed;
  }

  /** Returns the index of the first out-of-order element, or -1 if sorted. */
  private static int firstSortViolation(final String[] items) {
    for (int i = 1; i < items.length; i++) {
      if (items[i - 1].compareTo(items[i]) > 0) return i;
    }
    return -1;
  }

  private static String randomString(final Random rng, final int len) {
    final char[] chars = new char[len];
    for (int i = 0; i < len; i++) {
      chars[i] = ALPHABET.charAt(rng.nextInt(ALPHABET.length()));
    }
    return new String(chars);
  }

  private static void deleteDirectory(final String path) {
    final File dir = new File(path);
    if (dir.exists()) {
      deleteRecursive(dir);
    }
  }

  private static void deleteRecursive(final File file) {
    if (file.isDirectory()) {
      final File[] children = file.listFiles();
      if (children != null) {
        for (final File child : children) {
          deleteRecursive(child);
        }
      }
    }
    file.delete();
  }
}
