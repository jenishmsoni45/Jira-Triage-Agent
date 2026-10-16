# Initial synthetic benchmark results

Historical report: these results used the original 128-row/64-band settings.
For the current default and paired comparison, see [tuning results](tuning-results.md).

Run date: October 14, 2026 (America/Toronto); raw timestamp is October 15 UTC.
Environment: macOS 27.0 ARM64, 10 reported logical CPUs, OpenJDK 21.0.8
(Homebrew), Python 3.14.8. JVM heap: `-Xms128m -Xmx2g`.

Three separate JVM runs per scale/stress case, 200 measured queries per run.
Each table value is the median of the three per-run values. See
[methodology and commands](benchmarks.md) and the [raw snapshot](benchmark-results.json).

| Workload | Issues | Build (ms) | Query p50 (ms) | Query p95 (ms) | Mean candidates/query | Approx. index heap delta (MiB) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| mixed | 1,000 | 60.3 | 0.48 | 0.71 | 364.1 | 14.6 |
| mixed | 10,000 | 303.4 | 3.49 | 6.30 | 3,371.8 | 151.4 |
| mixed | 50,000 | 1566.7 | 16.91 | 32.74 | 16,082.0 | 760.2 |
| boilerplate | 1,000 | 10.7 | 0.95 | 5.69 | 999.0 | 6.0 |

The 50K workload retrieved an average of **16,082 candidates per query**
(about 32% of the corpus). Its largest visited bucket contained 25,427 issues.
Template overlap makes the current 64-band configuration broad; reranking these
candidates costs real time. The heap estimate was about **760 MiB** for the index
alone. Neither number is evidence of a lightweight or production-ready service.

The identical-text stress case retrieved all 999 other issues per query, with
a 1,000-issue bucket. No candidate limits hid that worst-case behavior.
This stress case was measured at 1K, not 50K.

On the separate 500-issue lexical assessment, both independent runs agreed:

| Quantity | Observed |
| --- | ---: |
| Exhaustively scored unordered pairs | 124,750 |
| Pairs with exact shingle Jaccard ≥ 0.30 | 600 |
| Oracle pairs recovered by LSH plus reranking | 600 |
| Missed oracle pairs | 0 |
| Raw LSH candidate pairs before reranking | 45,953 |
| Lexical-oracle candidate recall on this corpus | 100% |

The high recall comes with many extra candidates. This is a synthetic lexical
oracle, **not human-labeled precision, recall, or F1**, and does not establish
quality at 50K or on real Jira backlogs. The report makes no near-linear scaling
guarantee or comparison claiming a speedup over brute force.

Corpus fingerprints and candidate/match counts agreed across scale repetitions.
Both quality runs agreed on the fingerprint, counts, and missed-pair list.
Timings and heap estimates varied. The raw snapshot preserves every run, query
GC observations, configuration, source hashes, and portable command arguments.
No real tenant, external service, or downloaded dataset was used.
