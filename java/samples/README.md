# Merge Operator JNI Overhead Benchmark

This directory contains two benchmark applications that measure the performance
overhead of JNI when calling a RocksDB merge operator. Both operators maintain
values as **comma-separated sorted strings** and implement merge-sort-style merge
routines.

| Application | Operator | Implementation |
|---|---|---|
| `CppMergeOperatorBenchmark` | `SortedStringMergeOperator` | Native C++, resolved by factory name |
| `JavaMergeOperatorBenchmark` | `JavaSortedStringMergeOperator` | Pure Java, invoked via JNI callbacks |

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
and `JavaMergeOperatorBenchmark.java` into `samples/target/classes/`.

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

### Run both benchmarks in sequence

```bash
make merge_benchmark
```

---

## Customising Parameters

Three parameters can be overridden on the command line:

| Variable | Default | Description |
|---|---|---|
| `MERGE_BENCH_KEYS` | `10` | Number of distinct RocksDB keys |
| `MERGE_BENCH_MERGES` | `2000` | Number of merge operands written per key |
| `MERGE_BENCH_STRLEN` | `8` | Length of each randomly-generated string |

Examples (all run from `frocksdb/java/`):

```bash
# Larger workload
make merge_benchmark MERGE_BENCH_KEYS=20 MERGE_BENCH_MERGES=5000

# Shorter strings to stress merge frequency over data volume
make merge_benchmark MERGE_BENCH_MERGES=10000 MERGE_BENCH_STRLEN=4

# Only the C++ benchmark with custom params
make merge_benchmark_cpp MERGE_BENCH_KEYS=5 MERGE_BENCH_MERGES=3000
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
```

---

## What the Benchmark Measures

Each run executes four timed phases:

| Phase | Operation | JNI relevance |
|---|---|---|
| **Write** | `MERGE_BENCH_KEYS × MERGE_BENCH_MERGES` calls to `db.merge()` | None — operands are buffered in the memtable; the merge operator is not called |
| **Read (pre-compact)** | `db.get()` for each key | **Primary site of JNI overhead**: triggers `FullMergeV2` with all raw operands |
| **Compact** | `db.compactRange()` | Triggers repeated `PartialMerge` calls to progressively reduce operand count |
| **Read (post-compact)** | `db.get()` for each key | Expected to be fast for both operators — the merged value is already stored |

After the timed phases, a **validation** step checks that every key's value is a
correctly sorted, comma-separated list with the expected item count.

---

## Interpreting Results

The delta between the C++ and Java runs in **Phase 2 (Read pre-compact)** and
**Phase 3 (Compact)** quantifies the JNI callback overhead:

- **Phase 2** — one JNI call per key (`FullMergeV2` with all operands). With 10
  keys this is 10 JNI calls; the cost is dominated by the merge computation itself.
- **Phase 3** — many JNI calls (one `PartialMerge` per adjacent operand pair
  processed during compaction). This phase amplifies the per-call JNI overhead.

**Phase 1 (Write)** and **Phase 4 (Read post-compact)** should be nearly identical
between the two benchmarks because neither involves the merge operator.

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
