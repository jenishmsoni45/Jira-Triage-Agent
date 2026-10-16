package com.jenishsoni.jiratriage.integration.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jenishsoni.jiratriage.domain.Change;
import com.jenishsoni.jiratriage.domain.DuplicateLink;
import com.jenishsoni.jiratriage.domain.Issue;
import com.jenishsoni.jiratriage.domain.IssuePatch;
import com.jenishsoni.jiratriage.domain.OperationException;
import com.jenishsoni.jiratriage.duplicate.DuplicateSearchResult;
import com.jenishsoni.jiratriage.duplicate.DuplicateSettings;
import com.jenishsoni.jiratriage.duplicate.LshDuplicateIndex;
import com.jenishsoni.jiratriage.repository.JiraGateway;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class JiraCloudGateway implements JiraGateway, AutoCloseable {

    private static final String FIELDS = "summary,description,status,priority,labels,issuelinks";
    private final JiraConnectionSettings settings;
    private final JiraHttpTransport transport;
    private final JiraJsonCodec codec;
    private final DuplicateSettings duplicateSettings;

    public JiraCloudGateway(ObjectMapper mapper, JiraConnectionSettings settings, DuplicateSettings duplicateSettings) {
        this(mapper, settings, duplicateSettings, new JiraHttpTransport(settings));
    }

    JiraCloudGateway(ObjectMapper mapper, JiraConnectionSettings settings, DuplicateSettings duplicateSettings,
                     JiraHttpTransport transport) {
        this.settings = settings;
        this.duplicateSettings = duplicateSettings;
        this.transport = transport;
        codec = new JiraJsonCodec(mapper, settings);
    }

    @Override
    public synchronized List<Issue> findAll() {
        return List.copyOf(snapshot().issues().values());
    }

    @Override
    public synchronized Optional<Issue> findByKey(String key) {
        JiraJsonCodec.validateKey(key);
        var response = transport.request("GET", "/rest/api/3/issue/" + key + "?fields=" + encode(FIELDS), null, 200, true);
        if (response.statusCode() == 404) { return Optional.empty(); }
        Issue issue = codec.issue(codec.parse(response.body()));
        if (!key.equals(issue.key())) { throw JiraJsonCodec.malformed(); }
        return Optional.of(issue);
    }

    @Override
    public synchronized DuplicateSearchResult findDuplicates(String key) {
        JiraJsonCodec.validateKey(key);
        Snapshot snapshot = snapshot();
        requireScoped(snapshot, key);

        var index = new LshDuplicateIndex(duplicateSettings);
        snapshot.issues().values().forEach(index::upsert);
        return index.findDuplicates(key);
    }

    @Override
    public synchronized Change<Issue> update(String key, IssuePatch patch, boolean dryRun) {
        JiraJsonCodec.validateKey(key);
        if (patch.status() != null) {
            throw new OperationException("UNSUPPORTED_FIELD", "Jira status updates require workflow transition mapping, which is not implemented; no request was sent");
        }
        Issue before = findByKey(key).orElseThrow(() -> new OperationException("NOT_FOUND", "Issue not found: " + key));
        Issue after = patch.applyTo(before);
        boolean changed = !before.equals(after);
        if (changed && !dryRun) {
            transport.request("PUT", "/rest/api/3/issue/" + key, codec.update(patch), 204, false);
        }
        return new Change<>(dryRun, changed, changed && !dryRun, before, after);
    }

    @Override
    public synchronized Change<DuplicateLink> linkDuplicate(String issueKey, String duplicateOfKey, boolean dryRun) {
        JiraJsonCodec.validateKey(issueKey);
        JiraJsonCodec.validateKey(duplicateOfKey);
        if (issueKey.equals(duplicateOfKey)) {
            throw new OperationException("INVALID_ARGUMENT", "An issue cannot be a duplicate of itself");
        }
        Snapshot snapshot = snapshot();
        requireScoped(snapshot, issueKey);
        requireScoped(snapshot, duplicateOfKey);
        DuplicateLink after = new DuplicateLink(issueKey, duplicateOfKey);
        List<DuplicateLink> existing = snapshot.links().stream().filter(link -> link.issueKey().equals(issueKey)).toList();
        if (existing.stream().anyMatch(link -> !link.equals(after))) {
            throw new OperationException("CONFLICT", "Issue already has a different outgoing duplicate link");
        }
        if (existing.contains(after)) { return new Change<>(dryRun, false, false, after, after); }

        Set<String> visited = new HashSet<>();
        var pending = new ArrayDeque<String>();
        pending.add(duplicateOfKey);
        while (!pending.isEmpty()) {
            String current = pending.remove();
            if (current.equals(issueKey)) { throw new OperationException("CONFLICT", "The duplicate link would create a cycle"); }
            if (!visited.add(current)) { continue; }
            requireScoped(snapshot, current);
            snapshot.links().stream().filter(link -> link.issueKey().equals(current))
                    .map(DuplicateLink::duplicateOfKey).forEach(pending::add);
        }
        if (!dryRun) {
            transport.request("POST", "/rest/api/3/issueLink", codec.link(issueKey, duplicateOfKey), 201, false);
        }
        return new Change<>(dryRun, true, !dryRun, null, after);
    }

    @Override
    public synchronized List<DuplicateLink> findDuplicateLinks() {
        return snapshot().links();
    }

    private Snapshot snapshot() {
        Map<String, Issue> issues = new LinkedHashMap<>();
        Set<DuplicateLink> links = new LinkedHashSet<>();
        Set<String> tokens = new HashSet<>();
        String token = null;
        for (int page = 0; page < settings.maxPages(); page++) {
            String path = "/rest/api/3/search/jql?jql=" + encode(settings.jql()) + "&fields=" + encode(FIELDS)
                    + "&maxResults=" + settings.pageSize() + (token == null ? "" : "&nextPageToken=" + encode(token));
            JsonNode result = codec.parse(transport.request("GET", path, null, 200, false).body());
            if (!result.path("issues").isArray() || !result.path("isLast").isBoolean()) { throw JiraJsonCodec.malformed(); }
            for (JsonNode node : result.path("issues")) {
                Issue issue = codec.issue(node);
                if (issues.putIfAbsent(issue.key(), issue) != null) {
                    throw new OperationException("PAGINATION_ERROR", "Repeated issue key across search pages; no partial snapshot returned");
                }
                links.addAll(codec.links(node));
            }
            if (result.path("isLast").booleanValue()) { return new Snapshot(issues, List.copyOf(links)); }
            JsonNode next = result.path("nextPageToken");
            if (!next.isTextual() || next.textValue().isBlank() || next.textValue().length() > 4096 || !tokens.add(next.textValue())) {
                throw new OperationException("PAGINATION_ERROR", "Missing or repeated pagination token; no partial snapshot returned");
            }
            token = next.textValue();
        }
        throw new OperationException("PAGINATION_LIMIT", "Configured page limit reached; no partial snapshot returned");
    }

    private static void requireScoped(Snapshot snapshot, String key) {
        if (!snapshot.issues().containsKey(key)) {
            throw new OperationException("OUT_OF_SCOPE", "Issue or duplicate chain is absent from the configured search scope; check JQL and permissions");
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    @Override
    public void close() { transport.close(); }

    private record Snapshot(Map<String, Issue> issues, List<DuplicateLink> links) {}
}
