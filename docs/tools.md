# Tool contract

### Validation and results

The official SDK validates JSON Schema inputs before handlers execute:

- Unknown fields, explicit nulls, incorrect types, and invalid enum values
  are rejected. Optional means omitted, not null.
- Keys use uppercase project identifiers and a positive numeric suffix,
  such as `DEMO-1`. Unknown, well-formed keys produce `NOT_FOUND`.
- Statuses: `OPEN`, `IN_PROGRESS`, `DONE`. Priorities: `LOW`, `MEDIUM`, `HIGH`.
- Search limits are 1–100; offsets are 0–1,000,000; queries are at most
  500 characters.
- Updates require at least one field. Summary must be nonblank and at most
  240 characters; description is at most 20,000 characters.
- Labels are unique lowercase letters/digits/hyphens, start with a letter
  or digit, are at most 50 characters each, and number at most 50.

Successful calls return both `structuredContent` and a JSON text content
block containing the same envelope:

```json
{"ok":true,"data":{"issueKey":"DEMO-1","method":"MINHASH_LSH_JACCARD","explanation":"...","candidateKeys":[],"candidates":[],"minimumSimilarity":0.3,"diagnostics":{"queryShingles":19,"retrievedCandidates":0,"largestBucketSize":1,"candidatesTruncated":false}}}
```

Expected domain errors set `isError: true` and return
`{"ok":false,"error":{"code":"NOT_FOUND","message":"Issue not found: DEMO-999"}}`.
Other domain codes include `INVALID_ARGUMENT` and `CONFLICT`. Unexpected
handler failures return `INTERNAL_ERROR`, with diagnostic details on stderr.
SDK schema validation errors use the SDK's text error result; unknown tool
names use a JSON-RPC error. Do not assume every error has structured content.

### Mutations and persistence

Both mutation tools default to **`dryRun: true`**. Set `dryRun: false`
explicitly to apply a change. Results contain:

- `before` and `after`: the existing and proposed values.
- `changed`: whether the proposed values differ from existing state.
- `applied`: whether this call actually wrote a change.
- `dryRun`: whether this was a preview.

Omitted update fields retain their values. An empty description clears it;
`labels: []` clears labels, and a nonempty label array replaces the whole
list. Issue keys cannot be changed. In fixture mode, each preview or write is evaluated
atomically against current in-memory state; a preview does not reserve it
for a later call.

`issueKey` is the duplicate and `duplicateOfKey` is its target. Repeating
that directed link is a no-op (`changed: false`, `applied: false`). Each issue
has at most one outgoing duplicate link. Self-links, conflicting targets,
and cycles are rejected. Linking does not close issues or alter statuses.

In fixture mode, writes are **in memory and scoped to one server process**. The fixture
file is never rewritten. Every restart reloads the original six issues
and discards updates and links. Separate client-launched server processes
have separate state.

## Protocol examples

A client first sends initialization, waits for its response, and sends the
initialized notification. Then it sends discovery or call requests and
waits for each response. The demo and tests perform this exchange; pasting
all lines into a pipe and immediately closing stdin is not equivalent.

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"local-client","version":"1.0"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_issues","arguments":{"query":"password"}}}
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_issue","arguments":{"issueKey":"DEMO-1"}}}
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"triage_issue","arguments":{"issueKey":"DEMO-1"}}}
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"find_duplicates","arguments":{"issueKey":"DEMO-1"}}}
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"update_issue","arguments":{"issueKey":"DEMO-1","fields":{"priority":"LOW"},"dryRun":true}}}
{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"link_duplicate","arguments":{"issueKey":"DEMO-2","duplicateOfKey":"DEMO-1","dryRun":true}}}
```

