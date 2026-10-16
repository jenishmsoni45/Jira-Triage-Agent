# Provisional Jira REST mock contract

The adapter implements the local contract below. Endpoints, payloads, success
codes, pagination, field mappings, and link direction have been tested against
the mock server only. They have not been checked against an official Jira API
contract or a real tenant.

## Activation and connection boundaries

Fixture mode is the default. Select `JIRA_GATEWAY_MODE=jira` (or
`--triage.gateway=jira`) to construct the optional adapter. Construction and
application startup perform no HTTP requests. A tool call initiates reads or
writes. Only `http://127.0.0.1:port` and `http://[::1]:port` are accepted; DNS names,
external addresses, URL credentials, paths, query strings, and fragments are
rejected. Redirects and proxies are disabled. There is no external-mode override.
Use fake credentials only. A separately running loopback service must implement
this contract; the included mock is test-only and starts/stops automatically.

These environment settings are ignored in fixture mode and required in jira mode:

| Variable | Meaning / local mock example |
| --- | --- |
| `JIRA_BASE_URL` | Literal loopback HTTP origin, e.g. `http://127.0.0.1:8089` |
| `JIRA_EMAIL` | Fake Basic-auth username, e.g. `mock@example.invalid` |
| `JIRA_API_TOKEN` | Fake Basic-auth password, e.g. `local-test-token` |
| `JIRA_JQL` | Search scope, e.g. `project = MOCK ORDER BY key ASC` |
| `JIRA_PRIORITY_LOW_ID` | `1` in the mock |
| `JIRA_PRIORITY_MEDIUM_ID` | `2` in the mock |
| `JIRA_PRIORITY_HIGH_ID` | `3` in the mock |
| `JIRA_DUPLICATE_LINK_TYPE_ID` | `10000` in the mock |

Priority IDs must be distinct numeric strings. Type IDs and priority IDs are
configuration, never inferred from names. Optional limits:

| Variable | Default | Bounds |
| --- | --- | --- |
| `JIRA_CONNECT_TIMEOUT_MS` | 1000 | 1–60000 |
| `JIRA_REQUEST_TIMEOUT_MS` | 2000 | 1–60000 per attempt |
| `JIRA_READ_ATTEMPTS` | 3 | 1–5 including the first attempt |
| `JIRA_MAX_RETRY_DELAY_MS` | 1000 | 0–10000 per wait |
| `JIRA_PAGE_SIZE` | 50 | 1–100 requested items |
| `JIRA_MAX_PAGES` | 100 | 1–1000; exceeding it fails the whole snapshot |

Settings' string representation redacts credentials, origin, and JQL. Transport
and parser errors omit raw bodies, headers, URLs, and exception causes. Ordinary
logging never logs settings or requests. Do not enable JVM HTTP wire logging or
put real credentials in command-line arguments. No credentials are stored in the
repository. The adapter uses JDK HTTP APIs.

## Assumed HTTP operations

All requests use Basic authentication, Accept/Content-Type `application/json`.
All issue keys follow the existing MCP key grammar.

| Operation | Request | Expected result |
| --- | --- | --- |
| Read one issue | `GET /rest/api/3/issue/{key}?fields=...` | 200 issue; 404 absent |
| Read scoped snapshot | `GET /rest/api/3/search/jql?jql=...&fields=...&maxResults=...&nextPageToken=...` | 200 page |
| Update supplied fields | `PUT /rest/api/3/issue/{key}` | 204 |
| Create duplicate link | `POST /rest/api/3/issueLink` | 201 |

The requested fields are `summary,description,status,priority,labels,issuelinks`.
Every query value is URL encoded; a pagination token is never treated as a URL.
The first search omits `nextPageToken`. A page has `issues` (array), `isLast`
(boolean), and a nonblank `nextPageToken` when not last. Missing/repeated tokens,
tokens over 4096 characters, repeated issue keys across pages, and page-limit
exhaustion fail without returning partial data. A failed later page also fails
the complete read. Page size is a request, not a guarantee of server page size.

Example issue shape:

```json
{"key":"MOCK-1","fields":{"summary":"Password reset link expires","description":{"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"Opening the link shows an error"}]}]},"status":{"statusCategory":{"key":"new"}},"priority":{"id":"2"},"labels":["bug"],"issuelinks":[]}}
```

Status-category keys map `new` → OPEN, `indeterminate` → IN_PROGRESS, `done` → DONE.
Unmapped priorities or categories fail with `JIRA_MAPPING_ERROR`. Descriptions
support null (empty) or ADF v1 documents with paragraphs containing text and
hardBreak nodes. Unsupported rich structures fail with `UNSUPPORTED_DESCRIPTION`.
Text formatting marks are flattened; this is not a lossless rich-text editor.

`search_issues` collects all pages then applies the existing local substring,
status, label, offset, and limit filters. It does not translate user input to
JQL. `triage_issue` reads an issue then runs the existing local rules.
`find_duplicates` fetches a complete snapshot and builds a fresh local MinHash/LSH
index on every call. There is no persistent cache to become stale after edits,
but remote pagination is not a transactional snapshot and a server's search can
lag writes. This path includes full fetch/index cost, unlike fixture index queries.

`get_issue` reads the issue directly and collects links from the scoped snapshot.
Direct get/update calls are **not restricted by JQL**. Link and duplicate searches
require their issue keys in the JQL scope. JQL is not an authorization boundary.

## Updates, direction, and consistency

Supported updates are summary, plain-text description, labels, and configured
priority IDs. Omitted fields are retained; empty description emits JSON null;
labels replace the entire list. Newline-separated description text is encoded as
ADF paragraphs, preserving empty lines. Example PUT:

```json
{"fields":{"summary":"Password reset fails","priority":{"id":"3"},"labels":["bug"]}}
```

Status updates return `UNSUPPORTED_FIELD` before any request, including previews.
Workflow transition discovery/mapping is not implemented. Existing MCP schemas
remain unchanged so fixture mode still supports status changes.

Both mutation tools default to dry run. A preview reads current state but sends
no PUT/POST. An unchanged update/link sends no mutation. `after` in a successful
update is the intended local result, not a confirmed server readback. A success
status means the mock acknowledged the request. Read-before-write is not atomic
with another client; previews do not reserve state or detect concurrent edits.

The assumed direction is **duplicate/source → original/target**:

```json
{"type":{"id":"10000"},"inwardIssue":{"key":"MOCK-2"},"outwardIssue":{"key":"MOCK-1"}}
```

In this mock, MOCK-2 then contains an outwardIssue MOCK-1 link; MOCK-1 contains an
inwardIssue MOCK-2 link. This direction and type semantics need official/tenant
verification. Matching links are deduplicated when collecting pages. Repeating a
visible directed link is a no-op. Self-links, conflicting outgoing targets, and
cycles visible in the snapshot are rejected. A chain leaving the known scope is
rejected. Hidden links, stale search results, or concurrent clients can defeat
these prechecks: this is not globally atomic idempotency or cycle prevention.

HTTP writes live in the receiving server. Restarting the MCP process does not
undo them. The test server stores writes in memory until that test server closes.
Fixture mode remains independent, resetting its own in-memory writes on restart.

## Retry and error policy

Only GETs enter the adapter retry loop, for I/O errors/timeouts and HTTP
429/500/502/503/504. The delay is bounded exponential backoff (100ms, 200ms, ...),
or numeric/date `Retry-After`. If Retry-After exceeds the allowed wait, the call
fails instead of retrying early. Invalid headers use backoff. Interrupted waits
preserve the thread interruption and stop. Each page has its own retry budget;
there is no separate total deadline for a multi-page operation.

Mutations are sent once by the adapter, with no application retry. A write I/O
failure, interruption, 5xx, or unexpected 2xx returns `MUTATION_OUTCOME_UNKNOWN`;
re-read state before a manual replay. HTTP 429 mutations return `RATE_LIMITED`
without retry. The transport is the JDK HttpClient (whose connection recovery is
implementation-specific); no exactly-once delivery guarantee is made.

Other safe codes include `JIRA_TIMEOUT`, `JIRA_CONNECTION_ERROR`,
`JIRA_AUTHENTICATION_FAILED` (401), `JIRA_PERMISSION_DENIED` (403),
`JIRA_INVALID_REQUEST` (400/422), `NOT_FOUND`, `CONFLICT` (409),
`REDIRECT_REJECTED`, `JIRA_UNAVAILABLE`, `JIRA_INVALID_RESPONSE`,
`PAGINATION_ERROR`, `PAGINATION_LIMIT`, and `OUT_OF_SCOPE`.

## Local verification and remaining work

Run with JDK 21 and the already populated Maven cache:

```sh
mvn -o -Dtest=JiraCloudGatewayTest test
mvn -o clean verify
```

The mock binds an ephemeral port on 127.0.0.1 and uses fake credentials. Tests
exercise the service/gateway paths for all six tools, pagination and late-page
failure, URL-encoded opaque tokens, mapping and ADF round trips, previews,
updates, index refresh, link direction/replay/conflict/cycles, bounded read
retries, rate limits, timeouts, redacted errors, redirects, startup without HTTP,
and rejection of external origins. The packaged stdio suite separately exercises
all six tools against fixture mode. There is no packaged stdio-to-HTTP end-to-end
test yet. Sandboxes that forbid listening sockets must allow loopback binding
before mock tests can run; failures must not be reported as passing tests.

Before enabling a real tenant, verify official API documentation for
these assumptions, authentication and scopes, ADF and nullable fields, enhanced
search pagination/consistency, rate-limit semantics, edit permissions, workflow
transitions, and tenant-specific priority/link IDs and direction. Then validate against a tenant. None of that compatibility is established
by a mock that implements this contract. No real Jira connection is enabled.
