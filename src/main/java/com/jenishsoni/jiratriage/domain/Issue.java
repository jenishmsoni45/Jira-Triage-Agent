package com.jenishsoni.jiratriage.domain;

import java.util.List;
import java.util.Objects;

public record Issue(
        String key,
        String summary,
        String description,
        IssueStatus status,
        IssuePriority priority,
        List<String> labels) {

    public Issue {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Issue key must not be blank");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("Issue summary must not be blank");
        }
        description = description == null ? "" : description;
        Objects.requireNonNull(status, "Issue status is required");
        Objects.requireNonNull(priority, "Issue priority is required");
        labels = List.copyOf(labels);
    }
}
