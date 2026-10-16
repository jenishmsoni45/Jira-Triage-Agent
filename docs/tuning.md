# LSH configuration selection

This experiment compares local lexical retrieval configurations. It does not use
real Jira issues or establish human duplicate accuracy. The production index and
exact Jaccard reranking are unchanged; only signature/band settings are compared.

## Reproduce

With JDK 21, Python 3, and the existing Maven cache:

```sh
mvn -o verify
python3 scripts/tune_lsh.py
```

The driver compiles the benchmark using local `javac`, runs sequential JVMs with
`-Xms128m -Xmx2g`, and writes `target/tuning/results.json`. No downloads or external
calls occur. Each JVM has a ten-minute timeout. Results are saved atomically after
every successful run, with `complete: false` until the whole experiment finishes.
An exception records a failure; it cannot produce a completed report.

The script records source hashes, environment, commands, corpus fingerprints,
all raw results, the selection policy, and the choice timestamp. It writes the
choice to disk **before** generating or measuring held-out corpora. It does not
change the application configuration automatically. A rerun can produce different
cost measurements and, near the heap gate, a different recommendation.

## Comparison and policy

Candidate `(signature rows, bands)` combinations:
`(128,64)`, `(128,32)`, `(128,16)`, `(192,64)`, `(256,64)`, `(384,128)`, `(64,32)`,
and `(96,48)`. Each band uses `signature rows / bands` consecutive rows. The
original default is `(128,64)`. Shingle size 2, MinHash seed 20261007, and Jaccard
threshold 0.30 stay fixed. There are no candidate caps or hash-seed searches.

Before measuring, the selection rules were fixed:

1. At least 95% lexical-oracle recall on **each** development corpus.
2. At least 90% recall on **each** development corpus for the more difficult
   pairs with `0.30 <= Jaccard < 0.40`.
3. At most 1.10 times baseline estimated index heap at 10K issues, comparing
   medians of three JVM runs. Heap is approximate; this is not a memory guarantee.
4. Among eligible configurations, choose the lowest total raw candidate-pair
   count across development corpora. Break ties by signature size, then bands.
5. Freeze that choice. It must pass both recall floors on each held-out corpus
   to recommend promotion. Otherwise retain the baseline; never select a second
   configuration using held-out scores.

Latency is measured and reported but is not used to choose between eligible
settings. Candidate counts are reproducible and less noisy than elapsed time.

## Corpora and separation

Development quality uses three independent 500-issue corpora, seeds 43001–43003.
Every unordered pair is scored by an independent Jaccard implementation. Each
corpus has 124,750 pairs. The `quality-v2` generator uses five issues per group:
two identical texts, then variants with 2, 8, and 12 token replacements. Unlike
the earlier scale generator's 25 replacements, 12 replacements provide positive
pairs close to the threshold. Group identifiers include the seed. The driver
fails immediately if a quality corpus has no positives or no near-threshold
positives, and checks every returned score against independent Jaccard.

Development cost uses the original mixed scale generator, 10K issues, seed 43004,
and three JVM repetitions/configuration. It retains the heavier 25-token edits.
Execution order is shuffled with fixed seed 43005 to reduce fixed-order effects.

Final held-out quality uses two new 1K corpora, seeds 45001 and 45002: 499,500
unordered pairs each. Only the baseline and frozen choice are evaluated.
Final scale measurements use seed 45003 at 1K, 10K, and 50K, three JVM repetitions
per configuration/size; scheduling is shuffled with seed 45004. The identical-text
stress case uses 1K issues, three repetitions, and must retrieve and return all
999 other issues. Fingerprints must match across compared configurations; repeated
scale retrieval counts must also agree.

This separates issue groups, corpus seeds, and selection from evaluation, but the
synthetic generator is shared. It is not independent human-held-out validation.
The recall floors apply to this experiment, not arbitrary backlogs. The metric
is recovery of above-threshold lexical pairs, not semantic duplicate recall.

## Measurement details

The [benchmark methodology](benchmarks.md) defines warm-up, timing boundaries,
percentiles, and approximate heap deltas. Each timed run measures 200 distinct
queries after 100 query warm-ups. Summaries are medians of three per-run metrics,
including the per-run p95. Measurements exclude MCP/HTTP/JSON and corpus generation.
Quality queries also verify scores and are not latency comparisons to brute force.

The production REST adapter rebuilds a complete fetched index per duplicate call,
so these index-only query timings do not describe its total request latency.
Large identical buckets remain expensive under every setting; no tuning step
removes the possible quadratic cost of querying every issue in such a backlog.

## Development history and interpretation

The initial pilot lacked near-threshold positives: its 14-token edits placed
those pairs below 0.30. No new setting was promoted from that invalid experiment.
The generator was corrected to 12 edits, empty-stratum rejection was added, and
fresh seeds were used. The first valid six-configuration grid retained the
baseline: narrower buckets lost recall and the largest signature exceeded the
heap budget. Two smaller signatures with two rows per band were then added to
the development grid. Recall/heap gates were not relaxed. Final evaluation seeds
45001–45003 were unused until the expanded-grid choice was frozen; earlier
baseline-only evaluation seeds were retired.

These development iterations are disclosed to avoid presenting the experiment
as an untouched one-shot study. The final checked-in results are the complete
expanded-grid run; earlier results cannot substitute for its held-out evidence.
See [results](tuning-results.md) and the [raw snapshot](tuning-results.json).
