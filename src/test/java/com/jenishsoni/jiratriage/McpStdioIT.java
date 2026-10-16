package com.jenishsoni.jiratriage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(45)
class McpStdioIT {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BlockingQueue<String> stdout = new LinkedBlockingQueue<>();
    private final AtomicReference<Exception> readerFailure = new AtomicReference<>();
    @TempDir
    Path temporaryDirectory;
    private Process server;
    private BufferedWriter stdin;
    private Thread reader;
    private Path stderr;
    private int nextId;

    @BeforeEach
    void startServer() throws Exception {
        stdout.clear();
        readerFailure.set(null);
        stderr = temporaryDirectory.resolve("server-stderr.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        server = new ProcessBuilder(java, "-jar", System.getProperty("triage.test.jar"), "--triage.gateway=fixture")
                .redirectError(stderr.toFile()).start();
        stdin = new BufferedWriter(new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8));
        reader = Thread.ofVirtual().start(() -> {
            try (var input = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    stdout.add(line);
                }
            } catch (Exception exception) {
                readerFailure.set(exception);
            }
        });
        JsonNode initialization = request("initialize", Map.of(
                "protocolVersion", "2025-11-25", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "local-protocol-test", "version", "1.0")));
        assertEquals("jira-triage-agent", initialization.path("result").path("serverInfo").path("name").asText());
        assertEquals("2025-11-25", initialization.path("result").path("protocolVersion").asText());
        assertTrue(initialization.path("result").path("capabilities").has("tools"));
        send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
    }

    @AfterEach
    void stopServer() throws Exception {
        if (server == null) {
            return;
        }
        try {
            stdin.close();
            assertTrue(server.waitFor(10, TimeUnit.SECONDS), "Server must exit when stdin closes");
            reader.join(2000);
            assertFalse(reader.isAlive(), "stdout reader should finish after process exit");
            assertNull(readerFailure.get());
            assertEquals(0, server.exitValue(), () -> readDiagnostics());
            String line;
            while ((line = stdout.poll()) != null) {
                assertProtocolLine(line);
            }
            assertTrue(readDiagnostics().contains("Loaded 6 synthetic issues"), "Startup logs belong on stderr");
        } finally {
            if (server.isAlive()) {
                server.destroyForcibly();
                server.waitFor(5, TimeUnit.SECONDS);
            }
            server = null;
        }
    }

    @Test
    void discoversExactlySixToolsAndSearchesWithFiltersAndPagination() throws Exception {
        JsonNode tools = request("tools/list", Map.of()).path("result").path("tools");
        assertEquals(6, tools.size());
        Set<String> names = StreamSupport.stream(tools.spliterator(), false)
                .map(tool -> tool.path("name").asText()).collect(Collectors.toSet());
        assertEquals(Set.of("search_issues", "get_issue", "triage_issue", "find_duplicates", "update_issue", "link_duplicate"), names);
        for (JsonNode tool : tools) {
            assertEquals("object", tool.path("inputSchema").path("type").asText());
            assertFalse(tool.path("inputSchema").path("additionalProperties").asBoolean(true));
            assertFalse(tool.path("annotations").path("openWorldHint").asBoolean(true));
        }

        JsonNode search = data("search_issues", Map.of("query", "  BOARD  ", "limit", 1, "offset", 1));

        assertEquals(3, search.path("total").asInt());
        assertEquals("DEMO-4", search.path("issues").get(0).path("key").asText());
        assertEquals(1, data("search_issues", Map.of("query", "board", "status", "OPEN", "label", "board")).path("total").asInt());
        assertEquals("DEMO-6", data("search_issues", Map.of("label", "documentation")).path("issues").get(0).path("key").asText());
        assertEquals(6, data("search_issues", Map.of()).path("total").asInt());
        assertEquals(0, data("search_issues", Map.of("offset", 100)).path("issues").size());
    }

    @Test
    void triageExplainsItsRulesAndNeverAppliesSuggestions() throws Exception {
        JsonNode before = data("get_issue", Map.of("issueKey", "DEMO-1"));
        JsonNode triage = data("triage_issue", Map.of("issueKey", "DEMO-1"));
        assertEquals(triage, data("triage_issue", Map.of("issueKey", "DEMO-1")));
        assertEquals("BUG_KEYWORD", triage.path("reasons").get(0).path("rule").asText());
        assertTrue(triage.path("reasons").get(0).path("explanation").asText().contains("expired"));
        assertEquals(before, data("get_issue", Map.of("issueKey", "DEMO-1")));

        data("update_issue", Map.of("issueKey", "DEMO-5", "fields", Map.of("description", "Production outage causes data loss"), "dryRun", false));
        JsonNode urgent = data("triage_issue", Map.of("issueKey", "DEMO-5"));
        assertEquals("HIGH", urgent.path("suggestedPriority").asText());
        assertTrue(urgent.path("changesSuggested").asBoolean());
        assertEquals("LOW", data("get_issue", Map.of("issueKey", "DEMO-5")).path("issue").path("priority").asText());
        assertEquals("DOCUMENTATION_KEYWORD", data("triage_issue", Map.of("issueKey", "DEMO-6")).path("reasons").get(0).path("rule").asText());
    }

    @Test
    void updateDefaultsToPreviewThenAppliesAndResetsOnRestart() throws Exception {
        JsonNode original = data("get_issue", Map.of("issueKey", "DEMO-1")).path("issue");
        Map<String, Object> fields = Map.of("priority", "LOW", "description", "", "labels", List.of());
        JsonNode preview = data("update_issue", Map.of("issueKey", "DEMO-1", "fields", fields));
        assertTrue(preview.path("dryRun").asBoolean());
        assertTrue(preview.path("changed").asBoolean());
        assertFalse(preview.path("applied").asBoolean());
        assertEquals(original, preview.path("before"));
        assertEquals(original, data("get_issue", Map.of("issueKey", "DEMO-1")).path("issue"));

        Map<String, Object> apply = Map.of("issueKey", "DEMO-1", "fields", fields, "dryRun", false);
        JsonNode applied = data("update_issue", apply);
        assertTrue(applied.path("applied").asBoolean());
        assertEquals(preview.path("after"), applied.path("after"));
        assertEquals("", applied.path("after").path("description").asText());
        assertEquals(original.path("summary"), applied.path("after").path("summary"));
        JsonNode repeated = data("update_issue", apply);
        assertFalse(repeated.path("changed").asBoolean());
        assertFalse(repeated.path("applied").asBoolean());
        data("link_duplicate", Map.of("issueKey", "DEMO-2", "duplicateOfKey", "DEMO-1", "dryRun", false));

        stopServer();
        startServer();
        JsonNode reset = data("get_issue", Map.of("issueKey", "DEMO-1"));
        assertEquals(original, reset.path("issue"));
        assertEquals(0, reset.path("duplicateLinks").size());
    }

    @Test
    void duplicateLinksAreDirectedIdempotentAndRejectConflicts() throws Exception {
        JsonNode preview = data("link_duplicate", Map.of("issueKey", "DEMO-2", "duplicateOfKey", "DEMO-1"));
        assertTrue(preview.path("dryRun").asBoolean());
        assertTrue(preview.path("changed").asBoolean());
        assertFalse(preview.path("applied").asBoolean());
        assertEquals(0, data("get_issue", Map.of("issueKey", "DEMO-2")).path("duplicateLinks").size());

        Map<String, Object> link = Map.of("issueKey", "DEMO-2", "duplicateOfKey", "DEMO-1", "dryRun", false);
        assertTrue(data("link_duplicate", link).path("applied").asBoolean());
        JsonNode repeated = data("link_duplicate", link);
        assertFalse(repeated.path("changed").asBoolean());
        assertFalse(repeated.path("applied").asBoolean());
        JsonNode issue = data("get_issue", Map.of("issueKey", "DEMO-2"));
        assertEquals(1, issue.path("duplicateLinks").size());
        assertEquals("DEMO-1", issue.path("duplicateLinks").get(0).path("duplicateOfKey").asText());
        assertEquals("OPEN", issue.path("issue").path("status").asText());
        assertEquals(1, data("get_issue", Map.of("issueKey", "DEMO-1")).path("duplicateLinks").size());

        assertErrorCode("CONFLICT", "link_duplicate", Map.of("issueKey", "DEMO-2", "duplicateOfKey", "DEMO-3", "dryRun", false));
        assertErrorCode("CONFLICT", "link_duplicate", Map.of("issueKey", "DEMO-1", "duplicateOfKey", "DEMO-2"));
        assertErrorCode("INVALID_ARGUMENT", "link_duplicate", Map.of("issueKey", "DEMO-3", "duplicateOfKey", "DEMO-3"));
        assertErrorCode("NOT_FOUND", "link_duplicate", Map.of("issueKey", "DEMO-3", "duplicateOfKey", "DEMO-999"));

        data("link_duplicate", Map.of("issueKey", "DEMO-1", "duplicateOfKey", "DEMO-3", "dryRun", false));
        assertErrorCode("CONFLICT", "link_duplicate", Map.of("issueKey", "DEMO-3", "duplicateOfKey", "DEMO-2", "dryRun", false));
    }

    @Test
    void duplicatesReturnJaccardScoresAndRefreshAfterAppliedEdits() throws Exception {
        JsonNode initial = data("find_duplicates", Map.of("issueKey", "DEMO-1"));
        assertEquals("MINHASH_LSH_JACCARD", initial.path("method").asText());
        JsonNode original = data("get_issue", Map.of("issueKey", "DEMO-1")).path("issue");
        String summary = "  " + original.path("summary").asText().toUpperCase(Locale.ROOT).replace(" ", "\u00a0\t") + "  ";
        String description = original.path("description").asText().toUpperCase(Locale.ROOT).replace(" ", "\n  ");
        Map<String, Object> fields = Map.of("summary", summary, "description", description);
        data("update_issue", Map.of("issueKey", "DEMO-2", "fields", fields));
        assertEquals(initial, data("find_duplicates", Map.of("issueKey", "DEMO-1")));
        data("update_issue", Map.of("issueKey", "DEMO-2", "fields", fields, "dryRun", false));
        JsonNode matches = data("find_duplicates", Map.of("issueKey", "DEMO-1"));
        assertEquals(mapper.valueToTree(List.of("DEMO-2")), matches.path("candidateKeys"));
        assertEquals("DEMO-2", matches.path("candidates").get(0).path("issueKey").asText());
        assertEquals(1.0, matches.path("candidates").get(0).path("similarity").asDouble());
        assertTrue(matches.path("candidates").get(0).path("reason").asText().contains("not a probability"));
        assertFalse(matches.path("diagnostics").path("candidatesTruncated").asBoolean(true));

        data("update_issue", Map.of("issueKey", "DEMO-2", "fields", Map.of("description", description + "!"), "dryRun", false));
        assertEquals(matches, data("find_duplicates", Map.of("issueKey", "DEMO-1")));
        data("update_issue", Map.of("issueKey", "DEMO-2", "fields", Map.of("description", description + " additional diagnostic details"), "dryRun", false));
        JsonNode near = data("find_duplicates", Map.of("issueKey", "DEMO-1")).path("candidates").get(0);
        assertEquals("DEMO-2", near.path("issueKey").asText());
        assertTrue(near.path("similarity").asDouble() < 1.0);
        assertTrue(near.path("similarity").asDouble() >= 0.3);
        data("update_issue", Map.of("issueKey", "DEMO-2", "fields", Map.of(
                "summary", "Warehouse inventory delivery schedule", "description", "Rotate barcode scanners between loading docks"), "dryRun", false));
        assertEquals(0, data("find_duplicates", Map.of("issueKey", "DEMO-1")).path("candidateKeys").size());
    }

    @Test
    void invalidArgumentsAreRejectedWithoutChangingState() throws Exception {
        JsonNode original = data("get_issue", Map.of("issueKey", "DEMO-1"));
        assertTrue(call("get_issue", Map.of()).path("isError").asBoolean());
        assertTrue(call("get_issue", Map.of("issueKey", 1)).path("isError").asBoolean());
        assertTrue(call("get_issue", Map.of("issueKey", "demo-1")).path("isError").asBoolean());
        assertTrue(call("search_issues", Map.of("limit", 101)).path("isError").asBoolean());
        assertTrue(call("search_issues", Map.of("offset", -1)).path("isError").asBoolean());
        assertTrue(call("search_issues", Map.of("offset", 1.5)).path("isError").asBoolean());
        assertTrue(call("search_issues", Map.of("jql", "project=DEMO")).path("isError").asBoolean());
        assertTrue(call("search_issues", Map.of("status", "UNKNOWN")).path("isError").asBoolean());
        assertTrue(call("update_issue", Map.of("issueKey", "DEMO-1", "fields", Map.of())).path("isError").asBoolean());
        assertTrue(call("update_issue", Map.of("issueKey", "DEMO-1", "fields", Map.of("summary", "  "), "dryRun", false)).path("isError").asBoolean());
        assertTrue(call("update_issue", Map.of("issueKey", "DEMO-1", "fields", Map.of("priority", "LOW", "status", "UNKNOWN"), "dryRun", false)).path("isError").asBoolean());
        assertTrue(call("update_issue", Map.of("issueKey", "DEMO-1", "fields", Map.of("priority", "LOW"), "dryRun", "false")).path("isError").asBoolean());
        assertTrue(call("update_issue", Map.of("issueKey", "DEMO-1", "fields", Map.of("labels", List.of("bug", "bug")))).path("isError").asBoolean());
        ObjectNode nullField = mapper.createObjectNode().put("issueKey", "DEMO-1");
        nullField.putObject("fields").putNull("description");
        assertTrue(call("update_issue", nullField).path("isError").asBoolean());
        assertErrorCode("NOT_FOUND", "get_issue", Map.of("issueKey", "DEMO-999"));
        assertErrorCode("NOT_FOUND", "triage_issue", Map.of("issueKey", "DEMO-999"));
        assertErrorCode("NOT_FOUND", "find_duplicates", Map.of("issueKey", "DEMO-999"));
        assertEquals(original, data("get_issue", Map.of("issueKey", "DEMO-1")));
        assertTrue(request("tools/call", Map.of("name", "not_a_tool", "arguments", Map.of())).has("error"));
    }

    private JsonNode data(String tool, Object arguments) throws Exception {
        JsonNode result = call(tool, arguments);
        assertFalse(result.path("isError").asBoolean(), result::toString);
        JsonNode structured = result.path("structuredContent");
        assertTrue(structured.path("ok").asBoolean(), result::toString);
        assertEquals(structured, mapper.readTree(result.path("content").get(0).path("text").asText()));
        return structured.path("data");
    }

    private JsonNode call(String tool, Object arguments) throws Exception {
        JsonNode response = request("tools/call", Map.of("name", tool, "arguments", arguments));
        assertFalse(response.has("error"), response::toString);
        return response.path("result");
    }

    private void assertErrorCode(String code, String tool, Object arguments) throws Exception {
        JsonNode result = call(tool, arguments);
        assertTrue(result.path("isError").asBoolean());
        assertFalse(result.path("structuredContent").path("ok").asBoolean(true));
        assertEquals(code, result.path("structuredContent").path("error").path("code").asText());
    }

    private JsonNode request(String method, Object params) throws Exception {
        int id = ++nextId;
        send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            String line = stdout.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertNotNull(line, () -> "No protocol response to " + method + "; stderr: " + readDiagnostics());
            JsonNode message = assertProtocolLine(line);
            if (message.has("id")) {
                assertEquals(id, message.path("id").asInt());
                return message;
            }
        }
    }

    private JsonNode assertProtocolLine(String line) throws Exception {
        JsonNode message = mapper.readTree(line);
        assertNotNull(message, "stdout cannot contain empty lines");
        assertEquals("2.0", message.path("jsonrpc").asText(), "stdout must contain only JSON-RPC messages");
        return message;
    }

    private void send(Object message) throws Exception {
        stdin.write(mapper.writeValueAsString(message));
        stdin.newLine();
        stdin.flush();
    }

    private String readDiagnostics() {
        try {
            return Files.readString(stderr);
        } catch (Exception exception) {
            return exception.toString();
        }
    }
}
