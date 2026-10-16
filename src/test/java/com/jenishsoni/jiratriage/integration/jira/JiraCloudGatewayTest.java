package com.jenishsoni.jiratriage.integration.jira;

import com.jenishsoni.jiratriage.support.TestDuplicateSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jenishsoni.jiratriage.JiraTriageApplication;
import com.jenishsoni.jiratriage.domain.DuplicateLink;
import com.jenishsoni.jiratriage.domain.IssuePatch;
import com.jenishsoni.jiratriage.domain.IssuePriority;
import com.jenishsoni.jiratriage.domain.IssueStatus;
import com.jenishsoni.jiratriage.domain.OperationException;
import com.jenishsoni.jiratriage.duplicate.DuplicateSettings;
import com.jenishsoni.jiratriage.repository.FixtureJiraGateway;
import com.jenishsoni.jiratriage.repository.JiraGateway;
import com.jenishsoni.jiratriage.service.TriageService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringApplication;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class JiraCloudGatewayTest {

    private static final String EMAIL = "mock-user@example.invalid";
    private static final String SECRET = "mock-token-never-print";
    private static final DuplicateSettings DUPLICATES = TestDuplicateSettings.defaults();
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Duration> waits = new ArrayList<>();
    private LocalJiraMock mock;
    private JiraCloudGateway gateway;

    @BeforeEach
    void start() throws Exception {
        mock = new LocalJiraMock();
        gateway = gateway(settings(3, 100, 2000));
    }

    @AfterEach
    void close() {
        if (gateway != null) { gateway.close(); }
        if (mock != null) { mock.close(); }
    }

    @Test
    void paginatedReadsSupportSearchGetTriageAndIndexedDuplicates() {
        assertTrue(mock.requests.isEmpty(), "Construction must not perform HTTP");
        var service = new TriageService(gateway);
        var search = service.searchIssues("password", IssueStatus.OPEN, "bug", 0, 20);
        assertEquals(2, search.total());
        assertEquals(3, mock.requests.size());
        assertEquals("project = MOCK ORDER BY key ASC", mock.requests.get(0).query().get("jql"));
        assertEquals("page-1", mock.requests.get(1).query().get("nextPageToken"));
        assertTrue(mock.requests.get(0).query().get("fields").contains("issuelinks"));
        String encoded = Base64.getEncoder().encodeToString((EMAIL + ":" + SECRET).getBytes(StandardCharsets.UTF_8));
        assertEquals("Basic " + encoded, mock.requests.get(0).authorization());
        assertEquals("MOCK-1", service.getIssue("MOCK-1").issue().key());
        assertEquals("BUG_KEYWORD", service.triageIssue("MOCK-1").reasons().get(0).rule());
        assertEquals(List.of("MOCK-2"), service.findDuplicates("MOCK-1").candidateKeys());
        assertTrue(gateway.findByKey("MOCK-999").isEmpty());
    }

    @Test
    void updatePreviewsThenWritesOnlySupportedFieldsAndRefreshesDuplicateSnapshot() throws Exception {
        var patch = new IssuePatch("Warehouse barcode scanner", "first line\nsecond line", null, IssuePriority.LOW, List.of());
        var preview = gateway.update("MOCK-2", patch, true);
        assertTrue(preview.changed());
        assertFalse(preview.applied());
        assertEquals(0, mock.count("PUT"));
        assertEquals(List.of("MOCK-2"), gateway.findDuplicates("MOCK-1").candidateKeys());
        assertTrue(gateway.update("MOCK-2", patch, false).applied());
        var sent = mapper.readTree(mock.requests.stream().filter(request -> request.method().equals("PUT")).findFirst().orElseThrow().body());
        assertEquals("1", sent.path("fields").path("priority").path("id").asText());
        assertEquals(2, sent.path("fields").path("description").path("content").size());
        assertFalse(sent.path("fields").has("status"));
        assertEquals(preview.after(), gateway.findByKey("MOCK-2").orElseThrow());
        assertTrue(gateway.findDuplicates("MOCK-1").candidates().isEmpty());
        assertFalse(gateway.update("MOCK-2", patch, false).applied());
        assertEquals(1, mock.count("PUT"));
        gateway.update("MOCK-2", new IssuePatch(null, "", null, null, null), false);
        assertEquals("", gateway.findByKey("MOCK-2").orElseThrow().description());
        var last = mapper.readTree(mock.requests.stream().filter(request -> request.method().equals("PUT")).toList().getLast().body());
        assertEquals(1, last.path("fields").size());
        assertTrue(last.path("fields").path("description").isNull());
    }

    @Test
    void rejectsStatusAndSelfLinksBeforeMakingRequests() {
        assertCode("UNSUPPORTED_FIELD", () -> gateway.update("MOCK-1", new IssuePatch(null, null, IssueStatus.DONE, null, null), false));
        assertCode("INVALID_ARGUMENT", () -> gateway.linkDuplicate("MOCK-1", "MOCK-1", false));
        assertCode("INVALID_ARGUMENT", () -> gateway.findByKey("../remote"));
        assertTrue(mock.requests.isEmpty());
    }

    @Test
    void plainDescriptionsRoundTripWhitespaceAndEmptyLines() {
        for (String description : List.of(" ", "first\n\nlast\n", "")) {
            gateway.update("MOCK-1", new IssuePatch(null, description, null, null, null), false);
            assertEquals(description, gateway.findByKey("MOCK-1").orElseThrow().description());
        }
    }

    @Test
    void opaqueTokensStayInQueryAndLateErrorsDoNotReturnPartialSnapshots() {
        String token = "http://example.invalid/path?x=1&y=#fragment";
        AtomicInteger pages = new AtomicInteger();
        mock.responder = request -> {
            if (pages.incrementAndGet() == 1) {
                var page = mapper.createObjectNode().put("isLast", false).put("nextPageToken", token);
                page.putArray("issues").add(mock.issues.get("MOCK-1"));
                return LocalJiraMock.Reply.json(200, page.toString());
            }
            assertEquals("/rest/api/3/search/jql", request.uri().getPath());
            assertEquals(token, request.query().get("nextPageToken"));
            return LocalJiraMock.Reply.json(403, "{}");
        };
        assertCode("JIRA_PERMISSION_DENIED", gateway::findAll);
        assertEquals(2, pages.get());
    }

    @Test
    void lostLinkResponseCanBeReconciledByReadingBeforeManualReplay() {
        mock.responder = request -> {
            var reply = mock.statefulReply(request);
            return request.method().equals("POST") ? LocalJiraMock.Reply.json(503, "{}") : reply;
        };
        assertCode("MUTATION_OUTCOME_UNKNOWN", () -> gateway.linkDuplicate("MOCK-2", "MOCK-1", false));
        assertFalse(gateway.linkDuplicate("MOCK-2", "MOCK-1", false).applied());
        assertEquals(1, mock.count("POST"));
    }

    @Test
    void duplicateDirectionPreviewAndIdempotencyFollowProvisionalContract() throws Exception {
        assertFalse(gateway.linkDuplicate("MOCK-2", "MOCK-1", true).applied());
        assertEquals(0, mock.count("POST"));
        assertTrue(gateway.linkDuplicate("MOCK-2", "MOCK-1", false).applied());
        var post = mapper.readTree(mock.requests.stream().filter(request -> request.method().equals("POST")).findFirst().orElseThrow().body());
        assertEquals("10000", post.path("type").path("id").asText());
        assertEquals("MOCK-2", post.path("inwardIssue").path("key").asText());
        assertEquals("MOCK-1", post.path("outwardIssue").path("key").asText());
        assertEquals(List.of(new DuplicateLink("MOCK-2", "MOCK-1")), gateway.findDuplicateLinks());
        assertFalse(gateway.linkDuplicate("MOCK-2", "MOCK-1", false).applied());
        assertEquals(1, mock.count("POST"));
        assertCode("CONFLICT", () -> gateway.linkDuplicate("MOCK-2", "MOCK-3", false));
        assertCode("CONFLICT", () -> gateway.linkDuplicate("MOCK-1", "MOCK-2", false));
        assertEquals(1, mock.count("POST"));
    }

    @Test
    void linksOutsideKnownSearchGraphAreRejectedWithoutWriting() {
        mock.addLink("MOCK-1", "MOCK-999");
        assertCode("OUT_OF_SCOPE", () -> gateway.linkDuplicate("MOCK-2", "MOCK-1", false));
        assertEquals(0, mock.count("POST"));
    }

    @Test
    void retriesRateLimitedReadsAndHonorsNumericAndDateRetryAfter() {
        AtomicInteger count = new AtomicInteger();
        mock.responder = request -> switch (count.incrementAndGet()) {
            case 1 -> new LocalJiraMock.Reply(429, Map.of("Retry-After", "1"), SECRET, 0);
            case 2 -> new LocalJiraMock.Reply(503, Map.of("Retry-After", "Wed, 07 Oct 2026 12:00:01 GMT"), SECRET, 0);
            default -> mock.statefulReply(request);
        };
        assertTrue(gateway.findByKey("MOCK-1").isPresent());
        assertEquals(3, count.get());
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(1)), waits);
    }

    @Test
    void retriesAreBoundedAndLongRetryAfterIsNotShortened() {
        mock.responder = request -> LocalJiraMock.Reply.json(503, SECRET);
        assertCode("JIRA_UNAVAILABLE", () -> gateway.findByKey("MOCK-1"));
        assertEquals(3, mock.requests.size());
        assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200)), waits);
        mock.requests.clear();
        waits.clear();
        mock.responder = request -> new LocalJiraMock.Reply(429, Map.of("Retry-After", "60"), SECRET, 0);
        assertCode("RATE_LIMITED", () -> gateway.findByKey("MOCK-1"));
        assertEquals(1, mock.requests.size());
        assertTrue(waits.isEmpty());
    }

    @Test
    void mutationFailuresAreNeverRetriedAndAmbiguousOutcomesAreExplicit() {
        mock.responder = request -> request.method().equals("GET") ? mock.statefulReply(request) : LocalJiraMock.Reply.json(503, SECRET);
        assertCode("MUTATION_OUTCOME_UNKNOWN", () -> gateway.update("MOCK-1", new IssuePatch("Changed", null, null, null, null), false));
        assertEquals(1, mock.count("PUT"));
        assertCode("MUTATION_OUTCOME_UNKNOWN", () -> gateway.linkDuplicate("MOCK-2", "MOCK-1", false));
        assertEquals(1, mock.count("POST"));
        mock.responder = request -> request.method().equals("GET") ? mock.statefulReply(request) : LocalJiraMock.Reply.json(429, SECRET);
        assertCode("RATE_LIMITED", () -> gateway.linkDuplicate("MOCK-2", "MOCK-1", false));
        assertEquals(2, mock.count("POST"));
        assertTrue(waits.isEmpty());
    }

    @Test
    void readAndMutationTimeoutsAreBoundedAndNotReportedAsSuccess() {
        gateway.close();
        gateway = gateway(settings(2, 100, 150));
        mock.responder = request -> new LocalJiraMock.Reply(200, Map.of(), "{}", 500);
        assertCode("JIRA_TIMEOUT", () -> gateway.findByKey("MOCK-1"));
        assertEquals(2, mock.count("GET"));
        mock.responder = request -> request.method().equals("GET") ? mock.statefulReply(request)
                : new LocalJiraMock.Reply(204, Map.of(), "", 500);
        assertCode("MUTATION_OUTCOME_UNKNOWN", () -> gateway.update("MOCK-1", new IssuePatch("Changed", null, null, null, null), false));
        assertEquals(1, mock.count("PUT"));
    }

    @Test
    void nonRetryableErrorsAndMalformedBodiesDoNotLeakSecrets() {
        Map<Integer, String> codes = Map.of(400, "JIRA_INVALID_REQUEST", 401, "JIRA_AUTHENTICATION_FAILED",
                403, "JIRA_PERMISSION_DENIED", 409, "CONFLICT", 302, "REDIRECT_REJECTED");
        for (var item : codes.entrySet()) {
            mock.requests.clear();
            mock.responder = request -> new LocalJiraMock.Reply(item.getKey(), Map.of("Location", mock.baseUrl() + "/redirect"), SECRET + EMAIL, 0);
            assertCode(item.getValue(), () -> gateway.findByKey("MOCK-1"));
            assertEquals(1, mock.requests.size());
        }
        mock.responder = request -> LocalJiraMock.Reply.json(200, "invalid-json-" + SECRET);
        assertCode("JIRA_INVALID_RESPONSE", () -> gateway.findByKey("MOCK-1"));
        assertFalse(settings(3, 100, 2000).toString().contains(SECRET));
        assertFalse(settings(3, 100, 2000).toString().contains(EMAIL));
    }

    @Test
    void paginationErrorsFailWithoutReturningPartialData() {
        mock.responder = request -> LocalJiraMock.Reply.json(200, "{\"issues\":[],\"isLast\":false,\"nextPageToken\":\"repeat\"}");
        assertCode("PAGINATION_ERROR", gateway::findAll);
        assertEquals(2, mock.requests.size());
        mock.requests.clear();
        gateway.close();
        gateway = gateway(settings(3, 1, 2000));
        assertCode("PAGINATION_LIMIT", gateway::findAll);
        assertEquals(1, mock.requests.size());
        mock.responder = request -> LocalJiraMock.Reply.json(200, "{\"issues\":[],\"isLast\":false}");
        assertCode("PAGINATION_ERROR", gateway::findAll);
    }

    @Test
    void rejectsUnknownMappingsAndUnsupportedRichDescriptions() {
        ((com.fasterxml.jackson.databind.node.ObjectNode) mock.issues.get("MOCK-1").path("fields").path("priority")).put("id", "unknown");
        assertCode("JIRA_MAPPING_ERROR", () -> gateway.findByKey("MOCK-1"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) mock.issues.get("MOCK-1").path("fields").path("priority")).put("id", "2");
        ((com.fasterxml.jackson.databind.node.ObjectNode) mock.issues.get("MOCK-1").path("fields").path("description")).put("type", "table");
        assertCode("UNSUPPORTED_DESCRIPTION", () -> gateway.findByKey("MOCK-1"));
    }

    @Test
    void loopbackGuardRejectsExternalUrlsCredentialsAndDnsNamesWithoutConnecting() {
        for (String url : List.of("https://example.invalid", "http://localhost:8080", "http://127.0.0.1:8080/path",
                "http://user:secret@127.0.0.1:8080", "http://127.0.0.1:8080?token=secret")) {
            var exception = assertThrows(IllegalArgumentException.class, () -> replaceBaseUrl(URI.create(url)));
            assertFalse(exception.getMessage().contains("secret"));
        }
        assertNotNull(replaceBaseUrl(URI.create("http://[::1]:8080")));
        assertTrue(mock.requests.isEmpty());
    }

    @Test
    void optionalSpringWiringMakesNoStartupRequestsAndFixtureModeIgnoresConnectionSettings() {
        String[] args = {"--triage.mcp.enabled=false", "--triage.gateway=jira",
                "--JIRA_BASE_URL=" + mock.baseUrl(), "--JIRA_EMAIL=" + EMAIL, "--JIRA_API_TOKEN=" + SECRET,
                "--JIRA_JQL=project = MOCK", "--JIRA_PRIORITY_LOW_ID=1", "--JIRA_PRIORITY_MEDIUM_ID=2",
                "--JIRA_PRIORITY_HIGH_ID=3", "--JIRA_DUPLICATE_LINK_TYPE_ID=10000"};
        try (var context = SpringApplication.run(JiraTriageApplication.class, args)) {
            assertInstanceOf(JiraCloudGateway.class, context.getBean(JiraGateway.class));
            assertEquals(1, context.getBeansOfType(JiraGateway.class).size());
            assertTrue(mock.requests.isEmpty());
        }
        try (var context = SpringApplication.run(JiraTriageApplication.class, "--triage.mcp.enabled=false", "--triage.gateway=fixture",
                "--JIRA_BASE_URL=https://never-contact.invalid", "--JIRA_API_TOKEN=" + SECRET)) {
            assertInstanceOf(FixtureJiraGateway.class, context.getBean(JiraGateway.class));
            assertTrue(context.getBeansOfType(JiraCloudGateway.class).isEmpty());
            assertTrue(mock.requests.isEmpty());
        }
    }

    private JiraCloudGateway gateway(JiraConnectionSettings settings) {
        return new JiraCloudGateway(mapper, settings, DUPLICATES,
                new JiraHttpTransport(settings, waits::add, Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC)));
    }

    private JiraConnectionSettings settings(int attempts, int pages, int timeoutMillis) {
        return new JiraConnectionSettings(mock.baseUrl(), EMAIL, SECRET, "project = MOCK ORDER BY key ASC",
                Map.of(IssuePriority.LOW, "1", IssuePriority.MEDIUM, "2", IssuePriority.HIGH, "3"), "10000",
                Duration.ofMillis(1000), Duration.ofMillis(timeoutMillis), attempts, Duration.ofMillis(1000), 50, pages);
    }

    private JiraConnectionSettings replaceBaseUrl(URI baseUrl) {
        var settings = settings(3, 100, 2000);
        return new JiraConnectionSettings(baseUrl, settings.email(), settings.apiToken(), settings.jql(), settings.priorityIds(),
                settings.duplicateLinkTypeId(), settings.connectTimeout(), settings.requestTimeout(), settings.readAttempts(),
                settings.maxRetryDelay(), settings.pageSize(), settings.maxPages());
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable call) {
        var exception = assertThrows(OperationException.class, call);
        assertEquals(code, exception.code(), exception::getMessage);
        assertFalse(exception.toString().contains(SECRET));
        assertFalse(exception.toString().contains(EMAIL));
        assertNull(exception.getCause(), "Transport/parser causes may contain sensitive raw input");
    }
}
