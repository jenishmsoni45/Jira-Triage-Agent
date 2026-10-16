package com.jenishsoni.jiratriage.domain;

import java.util.List;

public record IssuePatch(
        String summary,
        String description,
        IssueStatus status,
        IssuePriority priority,
        List<String> labels) {

    public IssuePatch {
        if (labels != null) {
            labels = List.copyOf(labels);
        }
        if (summary == null && description == null && status == null && priority == null && labels == null) {
            throw new OperationException("INVALID_ARGUMENT", "At least one update field is required");
        }
    }

    public Issue applyTo(Issue issue) {
        return new Issue(issue.key(),
                summary == null ? issue.summary() : summary,
                description == null ? issue.description() : description,
                status == null ? issue.status() : status,
                priority == null ? issue.priority() : priority,
                labels == null ? issue.labels() : labels);
    }
}
