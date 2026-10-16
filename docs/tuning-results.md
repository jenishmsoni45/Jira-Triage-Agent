# Observed LSH tuning results

Run date: October 14, 2026 (America/Toronto), October 15 UTC. Full experiment:
**76 completed JVM runs**. Environment: macOS 27.0 ARM64, 10 logical CPUs,
OpenJDK 21.0.8, `-Xms128m -Xmx2g`. The [raw snapshot](tuning-results.json)
contains every run and the frozen selection timestamp; [methodology](tuning.md)
describes the acceptance rules, corpus construction, and development iterations.

The selected and promoted default is **64 signature rows / 32 bands**, two rows
per band. Shingle width, hash seed, threshold, and all MCP schemas stay unchanged.
This reduces cost while accepting some additional missed lexical matches.

## Development comparison

Recall columns show the worst of three development corpora. Cost values are
medians of three 10K-issue runs. Selection used candidate counts after recall and
heap eligibility; it did not optimize timing noise or use held-out results.

| Rows / bands | Min. recall | Min. near-threshold recall | Total raw candidate pairs | Heap / baseline | Eligible |
| --- | ---: | ---: | ---: | ---: | --- |
| k128-b64 | 100.00% | 100.00% | 140,685 | 1.000 | yes |
| k128-b32 | 68.60% | 33.75% | 2,112 | 0.669 | no |
| k128-b16 | 31.60% | 0.00% | 954 | 0.476 | no |
| k192-b64 | 93.70% | 84.50% | 2,872 | 1.089 | no |
| k256-b64 | 82.40% | 56.75% | 2,757 | 1.111 | no |
| k384-b128 | 99.50% | 98.75% | 6,842 | 1.945 | no |
| k64-b32 | 99.20% | 98.00% | 114,049 | 0.609 | yes |
| k96-b48 | 99.70% | 99.25% | 140,309 | 0.806 | yes |

The narrower three/four/eight-row bands proposed far fewer candidates, but the
cheaper options missed too many near-threshold pairs. The 384-row setting
preserved recall but almost doubled heap. The selected two-row-band setting
keeps more candidates while staying within both quality gates and reducing heap.

## Evaluation after freezing the choice

Each corpus has 1K issues and 499,500 unordered pairs. Each contains 2,000 pairs
with Jaccard >= 0.30, including 800 pairs in [0.30, 0.40).

| Seed | Configuration | Recovered / oracle pairs | Recall | Near-threshold recovery | Raw candidate pairs |
| --- | --- | ---: | ---: | ---: | ---: |
| 45001 | k128-b64 | 2,000 / 2,000 | 100.00% | 800 / 800 (100.00%) | 221,899 |
| 45001 | k64-b32 | 1,993 / 2,000 | 99.65% | 793 / 800 (99.12%) | 192,448 |
| 45002 | k128-b64 | 2,000 / 2,000 | 100.00% | 800 / 800 (100.00%) | 160,542 |
| 45002 | k64-b32 | 1,984 / 2,000 | 99.20% | 784 / 800 (98.00%) | 123,274 |

The selected configuration passed both gates on each corpus. Across them it
recovered **3,977 / 4,000 lexical-oracle pairs (99.425%)**, missing 23 that the
baseline recovered. This is **not human duplicate recall or precision**.

## Scale and stress costs

Median of three separate JVM runs per row, 200 measured queries per run.
Query p95 is the median of the three per-run p95 values, not a pooled percentile.

| Workload | Issues | Config | Build ms | Query p50 ms | Query p95 ms | Mean candidates | Approx. index heap MiB |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| held-out-cost | 1,000 | k128-b64 | 27.3 | 0.48 | 1.67 | 379.3 | 14.6 |
| held-out-cost | 10,000 | k128-b64 | 290.2 | 3.53 | 6.36 | 3,281.3 | 151.5 |
| held-out-cost | 50,000 | k128-b64 | 1519.8 | 19.07 | 32.41 | 16,465.5 | 760.2 |
| held-out-cost | 1,000 | k64-b32 | 23.9 | 0.50 | 0.62 | 331.4 | 8.8 |
| held-out-cost | 10,000 | k64-b32 | 218.0 | 1.76 | 4.85 | 2,827.2 | 92.2 |
| held-out-cost | 50,000 | k64-b32 | 1123.2 | 7.16 | 26.68 | 13,594.9 | 464.2 |
| stress | 1,000 | k128-b64 | 11.1 | 0.95 | 5.79 | 999.0 | 5.8 |
| stress | 1,000 | k64-b32 | 8.3 | 0.87 | 0.95 | 999.0 | 3.2 |

On the same 50K workload, the selected setting reduced:

- approximate index heap by 38.9%.
- mean candidates per query by 17.4%.
- median per-run p95 latency by 17.7%.
- median index construction time by 26.1%.

These are observed ratios from this machine/workload, not universal improvements.
The index still used about **464 MiB** of incremental retained heap and scored
about **13.6K candidates per sampled 50K query**. The largest visited bucket
contained 25,519 issues in both configurations; removing some bands does not
eliminate the remaining broad buckets. No candidates were truncated.

In the identical-text stress case, both configurations retrieved all 999 other
issues. Querying a whole such backlog can still approach quadratic work.
Heap is an approximation, not peak memory/RSS, and timings exclude HTTP/MCP.

To favor the original recall/cost tradeoff explicitly, keep the original setting:

```sh
java -jar target/jira-triage-agent-0.1.0-SNAPSHOT.jar --triage.duplicates.signature-size=128 --triage.duplicates.bands=64
```

The [earlier benchmark](benchmark-results.md) is retained as historical evidence
on another seed. Use this report's paired baseline for comparisons, rather than
comparing percentages across two different corpus seeds or benchmark runs.
