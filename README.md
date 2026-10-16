# Jira Triage Agent

An MCP server for searching, triaging, and deduplicating issues. Built with
Java 21, Spring Boot, and the official MCP Java SDK.

The server runs locally over stdio with six bundled synthetic issues. Triage
uses deterministic rules; duplicate search uses MinHash/LSH followed by exact
Jaccard scoring. No Jira account or language model is needed.

## Run

Requires JDK 21 and Maven 3.9.11. Python 3 is needed only for the demo and benchmarks.

```sh
mvn verify
python3 scripts/demo.py
```

The first Maven build needs dependencies from Maven Central. With a populated
cache, use `mvn -o verify` to build and test offline. The application itself makes
no external requests. The HTTP adapter tests bind an ephemeral loopback port.

The demo launches the packaged server, discovers the tools, searches and triages
issues, edits a near-duplicate, and previews/applies/replays a duplicate link.
Its changes disappear when the child process exits.

To launch the server directly:

```sh
java -jar target/jira-triage-agent-0.1.0-SNAPSHOT.jar
```

The process waits for an MCP client on stdin. Stdout carries JSON-RPC; diagnostics
go to stderr. Keep those streams separate. Closing stdin shuts down the server.
The server does not expose an HTTP endpoint.

## Tools

| Tool | Purpose | Example arguments |
| --- | --- | --- |
| `search_issues` | Substring search with status/label filters | `{"query":"password","limit":20}` |
| `get_issue` | Issue details and duplicate links | `{"issueKey":"DEMO-1"}` |
| `triage_issue` | Explain priority and label suggestions | `{"issueKey":"DEMO-1"}` |
| `find_duplicates` | Ranked candidates with Jaccard scores | `{"issueKey":"DEMO-1"}` |
| `update_issue` | Preview or apply field replacements | `{"issueKey":"DEMO-1","fields":{"priority":"LOW"},"dryRun":true}` |
| `link_duplicate` | Link a duplicate to its original | `{"issueKey":"DEMO-2","duplicateOfKey":"DEMO-1","dryRun":true}` |

Both mutation tools default to `dryRun: true`. Fixture writes are in memory and
reset on restart. Repeating the same link is a no-op; self-links, conflicting
targets, and cycles are rejected. Triage suggestions never apply themselves.

See the [tool contract](docs/tools.md) for validation, result fields, and JSON-RPC
examples. For hosts that accept an `mcpServers` configuration:

```json
{
  "mcpServers": {
    "jira-triage-agent": {
      "command": "/path/to/jdk-21/bin/java",
      "args": ["-jar", "/path/to/jira-triage-agent/target/jira-triage-agent-0.1.0-SNAPSHOT.jar"]
    }
  }
}
```

## Design

```text
MCP client → JiraTools → TriageService → JiraGateway
                                       ├─ FixtureJiraGateway → LshDuplicateIndex
                                       └─ JiraCloudGateway   → loopback HTTP mock
```

Packages under `com.jenishsoni.jiratriage` separate domain records, storage,
services, duplicate detection, MCP transport, and REST integration. The service
and domain layers have no MCP dependency. Fixture edits refresh the index under
the same lock as the issue store.

The default index uses two-token shingles, 64 signature rows, 32 bands, and a
Jaccard threshold of 0.30. Configuration and algorithm details are in
[design notes](docs/architecture.md).

## Benchmarks

A paired benchmark on 50K synthetic issues compared the original 128-row/64-band
index with the selected 64-row/32-band configuration:

| Metric | Original | Selected |
| --- | ---: | ---: |
| Approximate incremental index heap | 760.2 MiB | 464.2 MiB |
| Median per-run p95 query latency | 32.41 ms | 26.68 ms |
| Mean candidates per query | 16,466 | 13,595 |

Measurements use three JVM runs and 200 sampled queries per run on one machine.
They exclude MCP/HTTP overhead. On two separate evaluation corpora, the selected
setting recovered 3,977/4,000 pairs above the Jaccard threshold; the original
recovered all 4,000. This is a cost/recall tradeoff on synthetic text, not measured
human duplicate accuracy.

```sh
python3 scripts/benchmark.py
python3 scripts/tune_lsh.py
```

See [benchmark methodology](docs/benchmarks.md), [configuration selection](docs/tuning.md),
and [paired results](docs/tuning-results.md). Raw measurements and source hashes
are included with the reports.

## Tests

```sh
mvn -o test
mvn -o verify
```

`test` runs 27 unit and integration tests. `verify` also packages the executable
JAR and runs six stdio protocol tests: **33 tests total**. Coverage includes input
errors, previews, idempotency, index updates, pagination, retries, timeouts, and
startup without HTTP requests. Reports are written to `target/surefire-reports`
and `target/failsafe-reports`.

Pinned dependencies: Spring Boot 3.5.7, MCP SDK 2.0.1, Jackson 2.21.1,
JUnit 5.12.2, and Surefire/Failsafe 3.5.4. The Jackson version matches the MCP SDK's
Jackson 2 module requirements.

## Limits

- Matching is lexical. LSH can miss similar issues, and a Jaccard score is not a
  probability that two issues describe the same bug.
- Large shared buckets can make queries expensive. There are no candidate caps;
  querying every issue can approach quadratic work on repetitive text.
- The REST adapter is experimental and accepts loopback HTTP origins only. Its
  [mock contract](docs/jira-mock-contract.md) has not been validated against Jira
  Cloud. Status transitions and general rich-text editing are unsupported.
- Fixture state is not persistent. There is no deployment or production-readiness
  claim, and no real assistant-host or human-label validation.
