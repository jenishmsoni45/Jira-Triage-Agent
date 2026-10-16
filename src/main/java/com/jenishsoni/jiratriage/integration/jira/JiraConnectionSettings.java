package com.jenishsoni.jiratriage.integration.jira;

import com.jenishsoni.jiratriage.domain.IssuePriority;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public record JiraConnectionSettings(
        URI baseUrl, String email, String apiToken, String jql,
        Map<IssuePriority, String> priorityIds, String duplicateLinkTypeId,
        Duration connectTimeout, Duration requestTimeout, int readAttempts,
        Duration maxRetryDelay, int pageSize, int maxPages) {

    public JiraConnectionSettings {
        if (baseUrl == null || !"http".equals(baseUrl.getScheme())
                || !Set.of("127.0.0.1", "[::1]").contains(baseUrl.getHost() == null ? "" : baseUrl.getHost())
                || baseUrl.getPort() < 1 || baseUrl.getPort() > 65535
                || baseUrl.getUserInfo() != null || baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null
                || !(baseUrl.getRawPath().isEmpty() || baseUrl.getRawPath().equals("/"))) {
            throw new IllegalArgumentException("JIRA_BASE_URL must be http://127.0.0.1:port or http://[::1]:port; external endpoints are disabled");
        }
        if (email == null || email.isBlank() || email.contains(":") || apiToken == null || apiToken.isBlank()
                || jql == null || jql.isBlank()) {
            throw new IllegalArgumentException("JIRA_EMAIL, JIRA_API_TOKEN, and JIRA_JQL are required in jira mode");
        }
        priorityIds = Map.copyOf(priorityIds);
        if (!priorityIds.keySet().equals(Set.of(IssuePriority.values()))
                || priorityIds.values().stream().anyMatch(value -> !value.matches("[0-9]+"))
                || Set.copyOf(priorityIds.values()).size() != IssuePriority.values().length
                || duplicateLinkTypeId == null || !duplicateLinkTypeId.matches("[0-9]+")) {
            throw new IllegalArgumentException("Configure distinct numeric priority IDs and a numeric duplicate link type ID");
        }
        if (connectTimeout == null || requestTimeout == null || maxRetryDelay == null
                || connectTimeout.toMillis() < 1 || connectTimeout.toMillis() > 60000
                || requestTimeout.toMillis() < 1 || requestTimeout.toMillis() > 60000
                || maxRetryDelay.toMillis() < 0 || maxRetryDelay.toMillis() > 10000
                || readAttempts < 1 || readAttempts > 5 || pageSize < 1 || pageSize > 100
                || maxPages < 1 || maxPages > 1000) {
            throw new IllegalArgumentException("Invalid Jira timeout, read-attempt, retry-delay, or pagination bounds");
        }
    }

    public static JiraConnectionSettings from(Function<String, String> environment) {
        URI uri;
        try {
            uri = URI.create(required(environment, "JIRA_BASE_URL"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("JIRA_BASE_URL is missing or invalid; value omitted");
        }
        return new JiraConnectionSettings(uri, required(environment, "JIRA_EMAIL"),
                required(environment, "JIRA_API_TOKEN"), required(environment, "JIRA_JQL"),
                Map.of(IssuePriority.LOW, required(environment, "JIRA_PRIORITY_LOW_ID"),
                        IssuePriority.MEDIUM, required(environment, "JIRA_PRIORITY_MEDIUM_ID"),
                        IssuePriority.HIGH, required(environment, "JIRA_PRIORITY_HIGH_ID")),
                required(environment, "JIRA_DUPLICATE_LINK_TYPE_ID"),
                Duration.ofMillis(integer(environment, "JIRA_CONNECT_TIMEOUT_MS", 1000)),
                Duration.ofMillis(integer(environment, "JIRA_REQUEST_TIMEOUT_MS", 2000)),
                integer(environment, "JIRA_READ_ATTEMPTS", 3),
                Duration.ofMillis(integer(environment, "JIRA_MAX_RETRY_DELAY_MS", 1000)),
                integer(environment, "JIRA_PAGE_SIZE", 50), integer(environment, "JIRA_MAX_PAGES", 100));
    }

    private static String required(Function<String, String> environment, String name) {
        String value = environment.apply(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required in jira mode; value omitted");
        }
        return value;
    }

    private static int integer(Function<String, String> environment, String name, int fallback) {
        String value = environment.apply(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer; value omitted");
        }
    }

    @Override
    public String toString() {
        return "JiraConnectionSettings[loopbackOnly=true, credentials=<redacted>, query=<redacted>]";
    }
}
