package com.jenishsoni.jiratriage.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jenishsoni.jiratriage.domain.Issue;
import com.jenishsoni.jiratriage.domain.Change;
import com.jenishsoni.jiratriage.domain.DuplicateLink;
import com.jenishsoni.jiratriage.domain.IssuePatch;
import com.jenishsoni.jiratriage.domain.OperationException;
import com.jenishsoni.jiratriage.duplicate.DuplicateSearchResult;
import com.jenishsoni.jiratriage.duplicate.LshDuplicateIndex;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Repository
@ConditionalOnProperty(name = "triage.gateway", havingValue = "fixture", matchIfMissing = true)
public class FixtureJiraGateway implements JiraGateway {

    private final Map<String, Issue> issues = new LinkedHashMap<>();
    private final Map<String, DuplicateLink> links = new LinkedHashMap<>();
    private final LshDuplicateIndex duplicateIndex;

    public FixtureJiraGateway(
            ObjectMapper objectMapper,
            @Value("${triage.fixture-path}") String fixturePath,
            LshDuplicateIndex duplicateIndex) {
        this.duplicateIndex = duplicateIndex;
        try (InputStream input = new ClassPathResource(fixturePath).getInputStream()) {
            List<Issue> fixtures = objectMapper.readValue(input, new TypeReference<List<Issue>>() {});
            for (Issue issue : fixtures) {
                if (issues.putIfAbsent(issue.key(), issue) != null) {
                    throw new IllegalStateException("Duplicate issue key in fixture: " + issue.key());
                }
                duplicateIndex.upsert(issue);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load issue fixture from classpath: " + fixturePath, exception);
        }
    }

    @Override
    public synchronized List<Issue> findAll() {
        return List.copyOf(issues.values());
    }

    @Override
    public synchronized Optional<Issue> findByKey(String key) {
        return Optional.ofNullable(issues.get(key));
    }

    @Override
    public synchronized DuplicateSearchResult findDuplicates(String key) {
        requireIssue(key);
        return duplicateIndex.findDuplicates(key);
    }

    @Override
    public synchronized Change<Issue> update(String key, IssuePatch patch, boolean dryRun) {
        Issue before = requireIssue(key);
        Issue after = patch.applyTo(before);
        boolean changed = !before.equals(after);
        if (!dryRun && changed) {
            if (!before.summary().equals(after.summary()) || !before.description().equals(after.description())) {
                duplicateIndex.upsert(after);
            }
            issues.put(key, after);
        }
        return new Change<>(dryRun, changed, !dryRun && changed, before, after);
    }

    @Override
    public synchronized Change<DuplicateLink> linkDuplicate(String issueKey, String duplicateOfKey, boolean dryRun) {
        requireIssue(issueKey);
        requireIssue(duplicateOfKey);
        if (issueKey.equals(duplicateOfKey)) {
            throw new OperationException("INVALID_ARGUMENT", "An issue cannot be a duplicate of itself");
        }

        DuplicateLink before = links.get(issueKey);
        DuplicateLink after = new DuplicateLink(issueKey, duplicateOfKey);
        if (before != null && !before.equals(after)) {
            throw new OperationException("CONFLICT", issueKey + " is already linked to " + before.duplicateOfKey());
        }
        String cursor = duplicateOfKey;
        while (cursor != null) {
            if (cursor.equals(issueKey)) {
                throw new OperationException("CONFLICT", "The duplicate link would create a cycle");
            }
            DuplicateLink next = links.get(cursor);
            cursor = next == null ? null : next.duplicateOfKey();
        }

        boolean changed = before == null;
        if (!dryRun && changed) {
            links.put(issueKey, after);
        }
        return new Change<>(dryRun, changed, !dryRun && changed, before, after);
    }

    @Override
    public synchronized List<DuplicateLink> findDuplicateLinks() {
        return List.copyOf(links.values());
    }

    private Issue requireIssue(String key) {
        return findByKey(key).orElseThrow(() -> new OperationException("NOT_FOUND", "Issue not found: " + key));
    }
}
