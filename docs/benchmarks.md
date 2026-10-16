# Reproducible local duplicate-index benchmark

This benchmark calls the production `LshDuplicateIndex` directly. It measures
local lexical candidate retrieval, not Jira latency, MCP round trips, human
accuracy, or a production service load test. It uses Java/Python standard
libraries and already compiled project classes. It makes no network requests.

## Reproduce

With JDK 21, Python 3, and the populated Maven cache:

```sh
mvn -o verify
python3 scripts/benchmark.py
```

The driver compiles `benchmarks/DuplicateBenchmark.java` into
`target/benchmark-classes` using local `javac`. It starts sequential JVMs with
`-Xms128m -Xmx2g`. Default runs: 1K, 10K, and 50K mixed issues, three independent
JVM repetitions per size, 200 sampled queries per repetition; three repetitions
of a 1K identical-text stress case; and two reproducibility runs of a 500-issue
brute-force assessment. Each process has a ten-minute timeout. A failure stops
the run and records incomplete results instead of claiming success.

For a shorter run or different resource budget:

```sh
python3 scripts/benchmark.py --sizes 1000 10000 --repetitions 1 --queries 100 --heap 1g --output target/benchmarks/quick.json
```

A smaller heap may fail on larger corpora. There is no automatic downsizing,
download, external dataset, or dependency installation. `mvn clean` removes the
benchmark outputs. The raw default report is `target/benchmarks/results.json`;
[initial observations](benchmark-results.md) preserve the earlier 128-row run,
with its raw snapshot. [Tuning results](tuning-results.md) compare the original
and current defaults on the same new corpus. A new run uses the current defaults
and does not overwrite either documentation snapshot.

## Workload and fixed settings

The mixed corpus uses `java.util.Random` with seed 20261014 and five issues per
group: two identical texts, two lightly edited variants (2 and 8 replacements),
and a heavily edited variant (25 replacements). Each summary has a component
name, shared template, and group identifier. Each description has a shared
preamble and 40 generated tokens drawn from a 2K-token vocabulary. Replacements
use another 2K-token vocabulary. These are deliberately synthetic lexical
clusters; group membership is **not a human duplicate label**. Shared templates
can cause large cross-group buckets and expensive candidate reranking.

The stress corpus has 1K different keys with identical summary/description text.
Each query should retrieve all 999 other issues, making bucket degeneration
visible. This stress size limits runtime; it does not establish the cost of
querying 50K identical issues.

The driver reads signature rows and bands from application.properties: the
current defaults are 64 rows and 32 bands of 2 rows. Shingles remain 2 tokens,
hash seed 20261007, and Jaccard cutoff 0.30. The archived initial result used
128 rows / 64 bands; the tuning report contains the paired current comparison.
No settings are tuned by the driver. Corpus SHA-256 fingerprints are recorded
and checked across repetitions. Query order is a seeded shuffle without
replacement, using corpus seed XOR `0x7175657279`; the first 200 are measured.
The report records settings, seeds, exact JVM commands, OS/architecture, Java
version, CPU count, heap limits, raw runs, and summaries.

## Timing and memory methodology

Each process first builds an independent 1K warm-up corpus and performs 200
queries. Corpus creation and fingerprinting are outside index-build timing.
Index build times constructor plus all upserts, including tokenization,
signatures, and bucket insertion. After building, 100 queries on the measured
index warm the query path. The measured queries include candidate collection,
exact Jaccard reranking, explanations, sorting, and result allocation. They
exclude JSON serialization, transport, fixture loading, and snapshot fetching.
No results are cached. p50/p95 use the nearest-rank definition; summaries take
the median of each per-run metric (a median of p95 values is not a pooled p95).

Heap delta is `(totalMemory - freeMemory)` after index construction minus the
same reading before construction, with `System.gc()` requested at both points.
The generated corpus is live at both readings. This is an **approximation of
incremental retained Java heap**, not peak heap, RSS, total application memory,
or a JVM allocation profile. GC requests are advisory and other VM allocations
can affect it. Reported query GC counts/time help interpret latency outliers.
Explicit GC and JVM startup are outside reported latency. Warm-up is limited;
these are single-machine observations, not JMH measurements or confidence bounds.
Other applications, CPU scheduling, JIT compilation, and GC can change timings.

## Brute-force quality check

The quality corpus has 500 issues from a separate fixed seed (20261015), the
same generator, and unchanged index settings. All 124,750 unordered pairs are
scored by an independent exact Jaccard implementation. An oracle-positive pair
means only Jaccard >= 0.30. The indexed path queries every issue and checks every
returned score against the independent implementation; any extra below-threshold
result fails the driver. Candidate retrieval recall is recovered oracle pairs
divided by all oracle pairs. With exact reranking, an above-threshold pair that
was retrieved must also be returned.

Raw candidate pairs are half the total directed retrieved-candidate count;
this index's bucket sharing is symmetric. Missed oracle pairs are recorded by
zero-based corpus indices. Empty-oracle recall is `undefined`. Two independent
JVM runs must agree on fingerprints, oracle counts, candidates, and missed pairs.
Brute-force time excludes shingle generation. Indexed all-query time includes
independent score checks, so these times are **not an apples-to-apples speedup**.
Do not extrapolate the small oracle check to 50K quality or real bug detection.

The seed is separate from the scale workload, but the synthetic generator is
shared: this is not independent human-held-out validation. Human precision,
recall, and F1 remain unmeasured. To add those later, obtain real independently
labeled pairs with a documented sampling process; split by issue/group to avoid
leakage, tune only on a development split, freeze the configuration, and report
held-out confusion counts and uncertainty. Do not label generated groups as
human ground truth or turn Jaccard scores into calibrated probabilities.

## Interpretation limits

There are no candidate or result caps. Large buckets can make a single query
score most issues; querying an entire backlog can approach quadratic work.
The measured mixed workload also contains broad template buckets. A benchmark
that finishes at 50K does not prove near-linear scaling, low memory usage, or
production readiness. The optional REST adapter fetches and rebuilds the index
per duplicate request, so its total call cost includes work excluded from these
index-only query measurements.

## Source provenance

The archived reports retain the hashes of the source used for each measurement.
A comment-only cleanup on October 16, 2026 changed source-file hashes without
changing Java tokens or Python behavior. Historical hashes remain unchanged;
new runs record hashes of the current source.
