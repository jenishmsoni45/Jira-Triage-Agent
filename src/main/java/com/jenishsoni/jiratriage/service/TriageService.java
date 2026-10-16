package com.jenishsoni.jiratriage.service;

import com.jenishsoni.jiratriage.domain.Change;
import com.jenishsoni.jiratriage.domain.DuplicateLink;
import com.jenishsoni.jiratriage.domain.Issue;
import com.jenishsoni.jiratriage.domain.IssuePatch;
import com.jenishsoni.jiratriage.domain.IssuePriority;
import com.jenishsoni.jiratriage.domain.IssueStatus;
import com.jenishsoni.jiratriage.domain.OperationException;
import com.jenishsoni.jiratriage.duplicate.DuplicateSearchResult;
import com.jenishsoni.jiratriage.duplicate.NormalizedText;
import com.jenishsoni.jiratriage.repository.JiraGateway;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class TriageService {

    private static final Pattern URGENT = Pattern.compile("\\b(outage|data loss|data corruption|security breach)\\b");
    private static final Pattern BUG = Pattern.compile("\\b(bug|crash|error|fails|failure|broken|expired)\\b");
    private static final Pattern DOCS = Pattern.compile("\\b(document|documentation|docs)\\b");

    private final JiraGateway gateway;

    public TriageService(JiraGateway gateway) {
        this.gateway = gateway;
    }

    public SearchResult searchIssues(String query, IssueStatus status, String label, int offset, int limit) {
        String normalizedQuery = NormalizedText.normalize(query);
        List<Issue> matches = gateway.findAll().stream()
                .filter(issue -> status == null || issue.status() == status)
                .filter(issue -> label == null || issue.labels().stream().anyMatch(label::equalsIgnoreCase))
                .filter(issue -> List.of(issue.key(), issue.summary(), issue.description()).stream()
                        .map(NormalizedText::normalize).anyMatch(text -> text.contains(normalizedQuery)))
                .toList();
        int from = Math.min(offset, matches.size());
        int to = (int) Math.min((long) from + limit, matches.size());
        return new SearchResult(matches.size(), offset, limit, matches.subList(from, to));
    }

    public IssueDetails getIssue(String key) {
        return new IssueDetails(requireIssue(key), gateway.findDuplicateLinks().stream()
                .filter(link -> link.issueKey().equals(key) || link.duplicateOfKey().equals(key)).toList());
    }

    public TriageResult triageIssue(String key) {
        Issue issue = requireIssue(key);
        String text = NormalizedText.normalize(issue.summary() + " " + issue.description());
        List<Reason> reasons = new ArrayList<>();
        LinkedHashSet<String> labels = new LinkedHashSet<>(issue.labels());
        IssuePriority priority = issue.priority();

        var urgent = URGENT.matcher(text);
        if (urgent.find()) {
            priority = IssuePriority.HIGH;
            reasons.add(new Reason("URGENT_KEYWORD", "Text contains '" + urgent.group() + "'; suggest HIGH priority."));
        }
        var bug = BUG.matcher(text);
        if (bug.find()) {
            labels.add("bug");
            reasons.add(new Reason("BUG_KEYWORD", "Text contains '" + bug.group() + "'; suggest the bug label."));
        }
        var docs = DOCS.matcher(text);
        if (docs.find()) {
            labels.add("documentation");
            reasons.add(new Reason("DOCUMENTATION_KEYWORD", "Text contains '" + docs.group() + "'; suggest the documentation label."));
        }
        if (reasons.isEmpty()) {
            reasons.add(new Reason("NO_RULE_MATCH", "No keyword rule matched; retain existing priority and labels."));
        }
        List<String> suggestedLabels = List.copyOf(labels);
        return new TriageResult(key, priority, suggestedLabels, List.copyOf(reasons),
                priority != issue.priority() || !suggestedLabels.equals(issue.labels()));
    }

    public DuplicateSearchResult findDuplicates(String key) {
        return gateway.findDuplicates(key);
    }

    public Change<Issue> updateIssue(String key, IssuePatch patch, boolean dryRun) {
        return gateway.update(key, patch, dryRun);
    }

    public Change<DuplicateLink> linkDuplicate(String key, String duplicateOfKey, boolean dryRun) {
        return gateway.linkDuplicate(key, duplicateOfKey, dryRun);
    }

    private Issue requireIssue(String key) {
        return gateway.findByKey(key).orElseThrow(() -> new OperationException("NOT_FOUND", "Issue not found: " + key));
    }

    public record SearchResult(int total, int offset, int limit, List<Issue> issues) {}

    public record IssueDetails(Issue issue, List<DuplicateLink> duplicateLinks) {}

    public record Reason(String rule, String explanation) {}

    public record TriageResult(String issueKey, IssuePriority suggestedPriority, List<String> suggestedLabels,
                               List<Reason> reasons, boolean changesSuggested) {}
}
