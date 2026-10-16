package com.jenishsoni.jiratriage.transport.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jenishsoni.jiratriage.domain.IssuePatch;
import com.jenishsoni.jiratriage.domain.IssueStatus;
import com.jenishsoni.jiratriage.domain.OperationException;
import com.jenishsoni.jiratriage.service.TriageService;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JiraTools {

    private static final Logger LOG = LoggerFactory.getLogger(JiraTools.class);
    private static final Map<String, Object> KEY = Map.of(
            "type", "string", "pattern", "^[A-Z][A-Z0-9_]*-[1-9][0-9]*$", "maxLength", 64);
    private static final Map<String, Object> STATUS = Map.of(
            "type", "string", "enum", List.of("OPEN", "IN_PROGRESS", "DONE"));
    private static final Map<String, Object> LABEL = Map.of(
            "type", "string", "pattern", "^[a-z0-9][a-z0-9-]{0,49}$");
    private static final Map<String, Object> DRY_RUN = Map.of(
            "type", "boolean", "default", true,
            "description", "Defaults to true. Set false explicitly to apply through the configured gateway.");
    private static final Map<String, Object> OUTPUT = Map.of(
            "type", "object", "required", List.of("ok"), "additionalProperties", false,
            "properties", Map.of("ok", Map.of("type", "boolean"),
                    "data", Map.of("type", "object"), "error", Map.of("type", "object")));

    private final ObjectMapper mapper;
    private final TriageService service;

    public JiraTools(ObjectMapper mapper, TriageService service) {
        this.mapper = mapper;
        this.service = service;
    }

    public List<SyncToolSpecification> specifications() {
        Map<String, Object> fields = Map.of(
                "type", "object", "minProperties", 1, "additionalProperties", false,
                "properties", Map.of(
                        "summary", Map.of("type", "string", "minLength", 1, "maxLength", 240, "pattern", "\\S"),
                        "description", Map.of("type", "string", "maxLength", 20000),
                        "status", STATUS,
                        "priority", Map.of("type", "string", "enum", List.of("LOW", "MEDIUM", "HIGH")),
                        "labels", Map.of("type", "array", "maxItems", 50, "uniqueItems", true, "items", LABEL)));

        return List.of(
                specification("search_issues",
                        "Search gateway issues by normalized substring in key, summary, or description; optional status and label filters. No tool-supplied JQL. Preserves gateway order.",
                        schema(Map.of(
                                "query", Map.of("type", "string", "maxLength", 500, "default", ""),
                                "status", STATUS, "label", LABEL,
                                "offset", Map.of("type", "integer", "minimum", 0, "maximum", 1000000, "default", 0),
                                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100, "default", 20)), List.of()),
                        true, args -> service.searchIssues(args.path("query").asText(""),
                                args.has("status") ? IssueStatus.valueOf(args.get("status").asText()) : null,
                                args.has("label") ? args.get("label").asText() : null,
                                args.path("offset").asInt(0), args.path("limit").asInt(20))),
                specification("get_issue", "Get a gateway issue and its incoming and outgoing duplicate links within the configured scope.",
                        schema(Map.of("issueKey", KEY), List.of("issueKey")), true,
                        args -> service.getIssue(args.get("issueKey").asText())),
                specification("triage_issue", "Suggest priority and labels with deterministic keyword-rule explanations. Does not modify the issue.",
                        schema(Map.of("issueKey", KEY), List.of("issueKey")), true,
                        args -> service.triageIssue(args.get("issueKey").asText())),
                specification("find_duplicates", "Retrieve local duplicate candidates with a seeded MinHash/LSH index, then filter and rank by exact token-shingle Jaccard similarity. Returns candidateKeys plus scored candidates and reasons. Approximate retrieval may miss matches; scores are not probabilities. No semantic matching.",
                        schema(Map.of("issueKey", KEY), List.of("issueKey")), true,
                        args -> service.findDuplicates(args.get("issueKey").asText())),
                specification("update_issue", "Preview or apply supplied field replacements. Labels replace the list. Fixture writes are in-memory; optional Jira mock mode sends HTTP writes and rejects status changes. dryRun defaults to true.",
                        schema(Map.of("issueKey", KEY, "fields", fields, "dryRun", DRY_RUN), List.of("issueKey", "fields")),
                        false, args -> service.updateIssue(args.get("issueKey").asText(),
                                mapper.convertValue(args.get("fields"), IssuePatch.class), args.path("dryRun").asBoolean(true))),
                specification("link_duplicate", "Mark issueKey as a duplicate of duplicateOfKey through the configured gateway. Preview by default; dryRun:false applies. Repeating a known directed link is a no-op. Rejects self-links, conflicting targets, and detected cycles; does not change status.",
                        schema(Map.of("issueKey", KEY, "duplicateOfKey", KEY, "dryRun", DRY_RUN), List.of("issueKey", "duplicateOfKey")),
                        false, args -> service.linkDuplicate(args.get("issueKey").asText(),
                                args.get("duplicateOfKey").asText(), args.path("dryRun").asBoolean(true))));
    }

    private SyncToolSpecification specification(String name, String description, Map<String, Object> inputSchema,
                                                boolean readOnly, Function<JsonNode, Object> handler) {
        Tool tool = Tool.builder(name, inputSchema)
                .description(description)
                .outputSchema(OUTPUT)
                .annotations(ToolAnnotations.builder().readOnlyHint(readOnly)
                        .destructiveHint(name.equals("update_issue")).idempotentHint(true).openWorldHint(false).build())
                .build();
        return SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            try {
                JsonNode arguments = mapper.valueToTree(request.arguments() == null ? Map.of() : request.arguments());
                return result(false, Map.of("ok", true, "data", handler.apply(arguments)));
            } catch (OperationException exception) {
                return error(exception.code(), exception.getMessage());
            } catch (IllegalArgumentException exception) {
                return error("INVALID_ARGUMENT", "Arguments could not be converted to a valid issue update");
            } catch (RuntimeException exception) {
                LOG.error("Unexpected failure in tool {}", name, exception);
                return error("INTERNAL_ERROR", "The operation failed; see server diagnostics");
            }
        }).build();
    }

    private CallToolResult error(String code, String message) {
        return result(true, Map.of("ok", false, "error", Map.of("code", code, "message", message)));
    }

    private CallToolResult result(boolean isError, Map<String, Object> body) {
        Map<String, Object> content = mapper.convertValue(body, new TypeReference<>() {});
        try {
            return CallToolResult.builder().isError(isError).structuredContent(content)
                    .content(List.of(TextContent.builder(mapper.writeValueAsString(content)).build())).build();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize tool result", exception);
        }
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }
}
