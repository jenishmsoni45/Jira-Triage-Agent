package com.jenishsoni.jiratriage.integration.jira;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jenishsoni.jiratriage.domain.DuplicateLink;
import com.jenishsoni.jiratriage.domain.Issue;
import com.jenishsoni.jiratriage.domain.IssuePatch;
import com.jenishsoni.jiratriage.domain.IssuePriority;
import com.jenishsoni.jiratriage.domain.IssueStatus;
import com.jenishsoni.jiratriage.domain.OperationException;
import java.util.ArrayList;
import java.util.List;

final class JiraJsonCodec {

    private final ObjectMapper mapper;
    private final JiraConnectionSettings settings;

    JiraJsonCodec(ObjectMapper mapper, JiraConnectionSettings settings) {
        this.mapper = mapper;
        this.settings = settings;
    }

    JsonNode parse(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || !node.isObject()) {
                throw malformed();
            }
            return node;
        } catch (JsonProcessingException exception) {
            throw malformed();
        }
    }

    Issue issue(JsonNode node) {
        String key = key(node.path("key"));
        JsonNode fields = node.path("fields");
        String summary = text(fields.path("summary"));
        IssueStatus status = switch (text(fields.path("status").path("statusCategory").path("key"))) {
            case "new" -> IssueStatus.OPEN;
            case "indeterminate" -> IssueStatus.IN_PROGRESS;
            case "done" -> IssueStatus.DONE;
            default -> throw new OperationException("JIRA_MAPPING_ERROR", "Unsupported status category; mapping must be verified for the tenant");
        };
        String priorityId = text(fields.path("priority").path("id"));
        IssuePriority priority = settings.priorityIds().entrySet().stream()
                .filter(entry -> entry.getValue().equals(priorityId)).map(java.util.Map.Entry::getKey)
                .findFirst().orElseThrow(() -> new OperationException("JIRA_MAPPING_ERROR", "Priority ID has no configured local mapping"));
        JsonNode labels = fields.path("labels");
        if (!labels.isArray()) { throw malformed(); }
        List<String> mappedLabels = new ArrayList<>();
        for (JsonNode label : labels) { mappedLabels.add(text(label)); }
        return new Issue(key, summary, description(fields.path("description")), status, priority, mappedLabels);
    }

    List<DuplicateLink> links(JsonNode node) {
        String current = key(node.path("key"));
        JsonNode links = node.path("fields").path("issuelinks");
        if (!links.isArray()) { throw malformed(); }
        List<DuplicateLink> result = new ArrayList<>();
        for (JsonNode link : links) {
            if (!settings.duplicateLinkTypeId().equals(text(link.path("type").path("id")))) { continue; }
            if (link.has("outwardIssue") == link.has("inwardIssue")) { throw malformed(); }
            result.add(link.has("outwardIssue")
                    ? new DuplicateLink(current, key(link.path("outwardIssue").path("key")))
                    : new DuplicateLink(key(link.path("inwardIssue").path("key")), current));
        }
        return List.copyOf(result);
    }

    String update(IssuePatch patch) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode fields = root.putObject("fields");
        if (patch.summary() != null) { fields.put("summary", patch.summary()); }
        if (patch.description() != null) { fields.set("description", adf(patch.description())); }
        if (patch.labels() != null) { fields.set("labels", mapper.valueToTree(patch.labels())); }
        if (patch.priority() != null) { fields.putObject("priority").put("id", settings.priorityIds().get(patch.priority())); }
        return root.toString();
    }

    String link(String duplicate, String original) {
        ObjectNode root = mapper.createObjectNode();
        root.putObject("type").put("id", settings.duplicateLinkTypeId());

        root.putObject("inwardIssue").put("key", duplicate);
        root.putObject("outwardIssue").put("key", original);
        return root.toString();
    }

    private String description(JsonNode node) {
        if (node.isNull()) { return ""; }
        if (!node.isObject() || !node.path("type").asText().equals("doc")
                || !node.path("version").isIntegralNumber() || node.path("version").asInt() != 1
                || !node.path("content").isArray()) { throw unsupportedDescription(); }
        List<String> paragraphs = new ArrayList<>();
        for (JsonNode paragraph : node.path("content")) {
            if (!paragraph.path("type").asText().equals("paragraph") || !paragraph.path("content").isArray()) {
                throw unsupportedDescription();
            }
            StringBuilder line = new StringBuilder();
            for (JsonNode child : paragraph.path("content")) {
                switch (child.path("type").asText()) {
                    case "text" -> {
                        if (!child.path("text").isTextual()) { throw unsupportedDescription(); }
                        line.append(child.path("text").textValue());
                    }
                    case "hardBreak" -> line.append('\n');
                    default -> throw unsupportedDescription();
                }
            }
            paragraphs.add(line.toString());
        }
        return String.join("\n", paragraphs);
    }

    private JsonNode adf(String description) {
        if (description.isEmpty()) { return mapper.nullNode(); }
        ObjectNode document = mapper.createObjectNode().put("type", "doc").put("version", 1);
        var content = document.putArray("content");
        for (String line : description.split("\\n", -1)) {
            var nodes = content.addObject().put("type", "paragraph").putArray("content");
            if (!line.isEmpty()) { nodes.addObject().put("type", "text").put("text", line); }
        }
        return document;
    }

    static String key(JsonNode node) {
        String key = text(node);
        validateKey(key);
        return key;
    }

    static void validateKey(String key) {
        if (key == null || key.length() > 64 || !key.matches("[A-Z][A-Z0-9_]*-[1-9][0-9]*")) {
            throw new OperationException("INVALID_ARGUMENT", "Issue key is invalid");
        }
    }

    static String text(JsonNode node) {
        if (!node.isTextual() || node.textValue().isBlank()) { throw malformed(); }
        return node.textValue();
    }

    static OperationException malformed() {
        return new OperationException("JIRA_INVALID_RESPONSE", "Jira response does not match the provisional contract; raw response omitted");
    }

    private static OperationException unsupportedDescription() {
        return new OperationException("UNSUPPORTED_DESCRIPTION", "Only null or ADF v1 paragraphs containing text/hardBreak nodes are supported");
    }
}
