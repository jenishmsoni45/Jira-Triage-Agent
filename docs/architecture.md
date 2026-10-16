# Design

## Triage and duplicate matching

Triage examines normalized summary/description text using whole-word or
phrase matches, in this order:

1. `outage`, `data loss`, `data corruption`, or `security breach`: suggest HIGH
   priority.
2. `bug`, `crash`, `error`, `fails`, `failure`, `broken`, or `expired`: suggest
   the `bug` label.
3. `document`, `documentation`, or `docs`: suggest the `documentation` label.

Existing labels are retained. Priority is retained unless the urgency rule
matches. Each matched rule reports its identifier and matched text; no
matches produces an explicit no-change explanation. Rules do not understand
negation or context, and can produce false positives. Suggestions never
apply themselves.

### MinHash/LSH duplicate index

The duplicate path is implemented locally without additional dependencies:

1. Normalize summary and description with Unicode NFKC, `Locale.ROOT`
   lowercasing, and whitespace normalization. Extract Unicode letter/number
   tokens (including following combining marks); punctuation separates tokens.
2. Form distinct consecutive token shingles **within each field**, then take
   their set union. No shingle crosses the summary/description boundary.
   The default is two-token shingles. Repeated shingles count once; there is
   no field weighting, stemming, stop-word removal, or semantic embedding.
3. Build a deterministic MinHash signature from those shingles. UTF-8 FNV-1a
   hashes are reduced modulo the prime 2,147,483,647; seeded SplitMix64-style
   mixing supplies nonzero multipliers and offsets for affine permutations.
   For each permutation, retain the minimum value across the shingle set.
4. Split the signature into bands. Bucket keys include the band number and
   its complete row tuple. A query retrieves the union of matching buckets,
   excludes itself, and deduplicates issue IDs across bands.
5. Compare each retrieved candidate's **original string shingle set** with
   the query using exact Jaccard: intersection size divided by union size.
   Keep scores at or above the configured threshold. Sort by descending
   score, then lexicographic issue key for deterministic ties.

MinHash is used for retrieval, not the returned score. `similarity` is exact
lexical shingle overlap in [0, 1], **not a calibrated probability that two
issues describe the same bug**. Hash collisions can introduce extra
candidates; exact string-set reranking avoids treating hash equality as
text equality. LSH can also miss pairs above the Jaccard threshold.

An empty description contributes nothing. A nonempty field shorter than the
configured shingle width contributes its entire token sequence as one
shorter shingle. A punctuation-only issue has no shingles: it is excluded
from LSH buckets and returns no matches. Empty signatures never share a
sentinel bucket. Short texts can be brittle: a one-token shingle does not
overlap a two-token shingle containing that token.

The original fixture pairs are paraphrases; neither lexical overlap nor
LSH guarantees they will be returned. The demo edits `DEMO-2` into a near
copy with an added sentence to show retrieval at a score below 1. Tests
also cover identical text, unrelated issues, empty descriptions, short
issues, punctuation, and Unicode normalization.

### Configuration and results

Settings in `src/main/resources/application.properties` are fixed for the
life of the process. In fixture mode, restarting reloads fixtures and rebuilds the index.

| Property under `triage.duplicates` | Default | Constraint / effect |
| --- | --- | --- |
| `shingle-size` | 2 | 1–10 tokens; larger shingles require longer exact phrases |
| `signature-size` | 64 | 1–1024 rows; more rows require more hashing and storage |
| `bands` | 32 | Positive and must divide signature size; defaults to 2 rows per band |
| `seed` | 20261007 | Signed 64-bit seed; keep fixed for reproducible retrieval |
| `minimum-similarity` | 0.30 | Finite value in (0, 1]; applied to exact Jaccard after retrieval |

At fixed signature size, more bands/fewer rows generally broaden candidate
retrieval at the cost of more scoring; fewer bands/more rows make retrieval
stricter and can miss more matches. The Jaccard threshold cannot recover
pairs that never shared a bucket. Changing the seed can change retrieval.

Example startup with an alternative configuration:

```sh
java -jar target/jira-triage-agent-0.1.0-SNAPSHOT.jar --triage.duplicates.signature-size=256 --triage.duplicates.bands=128 --triage.duplicates.minimum-similarity=0.4
```

`find_duplicates` takes `issueKey` and returns `method: MINHASH_LSH_JACCARD`.
`candidateKeys` contains the threshold-qualified results in ranked order.
The response also includes:

- `candidates`: objects with `issueKey`, `similarity`, `sharedShingles`,
  `unionShingles`, `matchingBands`, and an explanatory `reason`.
- `minimumSimilarity`: the threshold used for this process.
- `diagnostics`: query shingle count, distinct `retrievedCandidates` before
  thresholding, `largestBucketSize` among visited buckets (including the
  query itself), and `candidatesTruncated`.

### Edits, large buckets, and runtime limits

The fixture gateway owns index consistency. It seeds the index at startup
and refreshes only the edited issue's bucket memberships when an applied
summary/description change alters its shingles. Old memberships and empty
buckets are removed. Updates and duplicate queries share the gateway lock,
so tool queries cannot observe a partially updated issue/index pair.
Dry runs, rejected edits, and status/priority/label-only updates do not alter
the index. Search and triage use the shared text normalizer.

There are **no bucket, candidate, or result caps** in this implementation;
`candidatesTruncated` is always false. This avoids additional recall loss
from truncation, but does not eliminate LSH misses. Introducing a cap later
would trade recall for bounded work and would need explicit reporting.

For K signature rows and S distinct shingles in an issue, signature
construction takes O(K × S) hashing work after tokenization. The index
stores shingle sets, band tuples, and bucket memberships. Queries visit
matching buckets instead of scanning every issue. Their work depends on
the sum of visited bucket sizes, exact intersections for distinct retrieved
candidates, and sorting the retained matches.

Boilerplate or many identical issues can put most of the backlog in the
same buckets. A single query can then score almost every issue, return a
large response, and hold the local gateway lock for longer. Querying all
issues in this case can approach quadratic pairwise work. There is **no
guaranteed near-linear runtime**. See the synthetic measurements below for
observed costs and their limits.

### Small brute-force retrieval assessment

`CandidateRetrievalQualityTest` builds a fixed synthetic corpus of 12
issues: three groups of lexical variants and three unrelated issues. It
exhaustively computes exact shingle Jaccard for all 66 unordered pairs and
uses the same 0.30 threshold as a lexical oracle. This evaluates candidate
retrieval, not whether humans consider issues duplicates.

With the documented defaults, the local test found 9 above-threshold
oracle pairs. LSH retrieved all 9 while proposing 9 pairs before reranking;
there were no misses on **this small corpus**. The regression test requires
at least 90% recall against this oracle. It is not a general recall guarantee,
a held-out evaluation, human-labeled precision, or a scalability benchmark.

Regenerate the machine-readable result with:

```sh
mvn -o -Dtest=CandidateRetrievalQualityTest test
```

The report is `target/duplicate-retrieval-quality.json` and records settings,
pair counts, lexical-oracle candidate recall, and missed pairs. It is a
generated build artifact and is not intended to be committed.


## Implementation constraints

MinHash reduces shingle hashes modulo 2,147,483,647. Multipliers and values are
below 2^31, so their product plus offset fits in a signed long. Coefficient
generation uses intentional 64-bit wraparound. Band keys compare full row arrays;
a Java hash-code collision does not merge unequal signatures.

Issue patches treat null as unchanged; empty descriptions and label lists clear
those fields. `changed` describes the proposal and `applied` records an actual
write. A duplicate link points from `issueKey` to `duplicateOfKey`. Fixture
mutations and index refreshes share a lock. The HTTP adapter uses read-before-write
and cannot provide the same atomicity across remote clients.
