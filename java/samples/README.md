# Merge Operator JNI Overhead Benchmark

This directory contains benchmark applications that measure the performance
overhead of JNI when calling a RocksDB merge operator.

| Application | Operator | Interface | Value type |
|---|---|---|---|
| `CppMergeOperatorBenchmark` | `SortedStringMergeOperator` | Native C++, resolved by factory name | Comma-separated sorted strings |
| `JavaMergeOperatorBenchmark` | `JavaSortedStringMergeOperator` | `AbstractMergeOperator` (fullMerge + partialMerge) | Comma-separated sorted strings |
| `JavaAssociativeMergeOperatorBenchmark` | `JavaAssociativeSortedStringMergeOperator` | `AbstractAssociativeMergeOperator` (single merge method) | Comma-separated sorted strings |

---

## Prerequisites

The native RocksDB library must be built **before** running the benchmarks.
Run the following from the **repository root** (`frocksdb/`):

```bash
make rocksdbjava DEBUG_LEVEL=0 -j$(nproc)
```

This compiles the native JNI library and all Java classes, writing the library
to `java/target/librocksdbjni-linux64.so` (or the appropriate platform name).

### CMake-based build (optional)

If you prefer a CMake build, first ensure the test JARs are present, then
build:

```bash
# From the repository root
cd java && make resolve_test_deps && cd ..
cmake -B build -DJNI=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build --target rocksdbjni -j$(nproc)
```

The CMake build writes `build/java/librocksdbjni.so`. The benchmark Makefile
targets automatically copy this into `java/target/` when no library is already
present there (bootstrap only — an existing library produced by `make
rocksdbjava` is never overwritten). You can override the CMake output directory
if you built elsewhere:

```bash
cd java
make merge_benchmark CMAKE_BUILD_DIR=/path/to/cmake-build
```

---

## All benchmark commands run from `java/`

Every `make` command below must be run from the `java/` subdirectory:

```bash
cd java
```

---

## Building the Benchmark Classes

```bash
make merge_benchmark_build
```

This compiles `MergeOperatorBenchmark.java`, `CppMergeOperatorBenchmark.java`,
`JavaMergeOperatorBenchmark.java`, and `JavaAssociativeMergeOperatorBenchmark.java`
into `samples/target/classes/`.

---

## Running the Benchmarks

### Run the C++ benchmark only

The operator is resolved by factory name `"SortedStringMergeOperator"`. All merge
computation happens natively in C++ with no JNI crossing per merge call.

```bash
make merge_benchmark_cpp
```

### Run the Java benchmark only

The operator extends `AbstractMergeOperator`. Each time RocksDB evaluates a merge
(during `db.get()` or compaction), it invokes the Java callbacks `fullMerge` and
`partialMerge` via JNI.

```bash
make merge_benchmark_java
```

### Run the Java associative merge operator benchmark only

The operator extends `AbstractAssociativeMergeOperator` and maintains the same
comma-separated sorted-string values as the other two benchmarks, making results
directly comparable. Users implement a single `merge(key, existing, value)` method;
RocksDB's C++ base class drives both `FullMergeV2` and `PartialMerge` by calling it
once per operand.

```bash
make merge_benchmark_associative_java
```

### Run all three benchmarks in sequence

```bash
make merge_benchmark
```

---

## Customising Parameters

All three benchmarks share the same parameters:

| Variable | Default | Description |
|---|---|---|
| `MERGE_BENCH_KEYS` | `10` | Number of distinct RocksDB keys |
| `MERGE_BENCH_MERGES` | `2000` | Number of merge operands written per key |
| `MERGE_BENCH_STRLEN` | `8` | Length of each randomly-generated string operand |

Examples (all run from `frocksdb/java/`):

```bash
# Larger workload for all benchmarks
make merge_benchmark MERGE_BENCH_KEYS=20 MERGE_BENCH_MERGES=5000

# Shorter strings to stress merge frequency over data volume
make merge_benchmark MERGE_BENCH_MERGES=10000 MERGE_BENCH_STRLEN=4

# Only the associative benchmark with custom params
make merge_benchmark_associative_java MERGE_BENCH_KEYS=5 MERGE_BENCH_MERGES=3000
```

You can also run the classes directly. The commands below must be run from
`frocksdb/java/`:

```bash
java -Djava.library.path=target \
     -cp "target/classes:samples/target/classes:target/*" \
     CppMergeOperatorBenchmark /tmp/bench_cpp 10 2000 8

java -Djava.library.path=target \
     -cp "target/classes:samples/target/classes:target/*" \
     JavaMergeOperatorBenchmark /tmp/bench_java 10 2000 8

java -Djava.library.path=target \
     -cp "target/classes:samples/target/classes:target/*" \
     JavaAssociativeMergeOperatorBenchmark /tmp/bench_java_assoc 10 2000 8
```

---

## What the Benchmark Measures

Each run executes four timed phases:

| Phase | Operation | JNI relevance |
|---|---|---|
| **Write** | `N × M` calls to `db.merge()` | None — operands are buffered in the memtable; the merge operator is not called |
| **Read (pre-compact)** | `db.get()` for each key | **Primary site of JNI overhead**: triggers `FullMergeV2` which calls the Java operator once per key (sorted-string) or repeatedly per operand (associative) |
| **Compact** | `db.compactRange()` | Triggers `PartialMerge` / associative folding in background threads via JNI callbacks |
| **Read (post-compact)** | `db.get()` for each key | Expected to be fast — the merged value is already stored |

After the timed phases, the sorted-string benchmarks run a **validation** step
that checks every key's value is a correctly sorted, comma-separated list. The
associative benchmark validates that every counter equals the expected sum.

---

## Interpreting Results

The delta between C++ and Java runs in **Phase 2** and **Phase 3** quantifies
the JNI callback overhead per merge operator interface:

- **`JavaMergeOperatorBenchmark`** — each `FullMergeV2` calls Java once with
  all operands as a `ByteBuffer[]`. `PartialMerge` calls Java once per adjacent
  operand pair during compaction.
- **`JavaAssociativeMergeOperatorBenchmark`** — RocksDB's C++ `AssociativeMergeOperator`
  base class drives `FullMergeV2` and `PartialMerge` internally by calling Java
  `merge()` once per operand. The JNI crossing count per `FullMergeV2` is higher
  (one call per operand vs. one call total), but the Java implementation is simpler
  (one method, no operand list). Values are the same sorted strings, so results are
  directly comparable with the other two benchmarks.

**Phase 1 (Write)** and **Phase 4 (Read post-compact)** should be nearly identical
across all benchmarks because neither involves the merge operator.

### Sample output (indicative only — actual numbers vary by hardware)

```
╔══════════════════════════════════════════════════════╗
║         Merge Operator JNI Overhead Benchmark        ║
╚══════════════════════════════════════════════════════╝
  Operator   : SortedStringMergeOperator (native C++, ...)
  Keys       : 10
  Merges/key : 2000
  Total ops  : 20000
  String len : 8

  Phase 1 (Write):               45 ms   (20000 merge calls, 2250.00 ns/op)
  Phase 2 (Read pre-compact):    38 ms   (10 get calls, 3800000.00 ns/op)
  Phase 3 (Compact):            120 ms   (compactRange())
  Phase 4 (Read post-compact):    2 ms   (10 get calls, 200000.00 ns/op)

  Validation (pre-compact):  10/10 keys OK
  Validation (post-compact): 10/10 keys OK

── Summary ─────────────────────────────────────────────
  Write                  :       45 ms
  Read (pre-compact)     :       38 ms  ← FullMerge cost
  Compact                :      120 ms  ← PartialMerge cost
  Read (post-compact)    :        2 ms
  Merge-relevant total   :      158 ms  (read + compact)

  VALIDATION PASSED: all 10 keys produce correctly sorted values
```

---

## How the C++ Operator is Resolved

`CppMergeOperatorBenchmark` calls:

```java
opts.setMergeOperatorName("SortedStringMergeOperator");
```

This invokes `MergeOperators::CreateFromStringId("SortedStringMergeOperator")` in
C++, which looks up the name in the ObjectLibrary registry and instantiates
`SortedStringMergeOperator` from
`utilities/merge_operators/sorted_string/sortedstring.cc`. The operator is
registered in `utilities/merge_operators.cc` under both its class name
`"SortedStringMergeOperator"` and the short nick name `"sortedstring"`, so both
of the following are equivalent:

```java
opts.setMergeOperatorName("SortedStringMergeOperator");
opts.setMergeOperatorName("sortedstring");
```
