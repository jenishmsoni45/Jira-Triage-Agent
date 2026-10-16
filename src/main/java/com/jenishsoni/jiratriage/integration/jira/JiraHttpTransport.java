package com.jenishsoni.jiratriage.integration.jira;

import com.jenishsoni.jiratriage.domain.OperationException;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Set;

final class JiraHttpTransport implements AutoCloseable {

    private static final Set<Integer> RETRYABLE = Set.of(429, 500, 502, 503, 504);
    private final JiraConnectionSettings settings;
    private final HttpClient client;
    private final Sleeper sleeper;
    private final Clock clock;

    JiraHttpTransport(JiraConnectionSettings settings) {
        this(settings, duration -> Thread.sleep(duration), Clock.systemUTC());
    }

    JiraHttpTransport(JiraConnectionSettings settings, Sleeper sleeper, Clock clock) {
        this.settings = settings;
        this.sleeper = sleeper;
        this.clock = clock;
        client = HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override
                    public void connectFailed(URI uri, SocketAddress address, IOException exception) {}
                }).build();
    }

    HttpResponse<String> request(String method, String relativePath, String body, int expectedStatus, boolean allowNotFound) {
        if (!relativePath.startsWith("/rest/api/3/") || relativePath.contains("#")) {
            throw new IllegalArgumentException("Only fixed relative Jira API paths are permitted");
        }
        boolean read = method.equals("GET");
        int attempts = read ? settings.readAttempts() : 1;
        String authorization = Base64.getEncoder().encodeToString(
                (settings.email() + ":" + settings.apiToken()).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(settings.baseUrl().resolve(relativePath))
                .timeout(settings.requestTimeout()).header("Authorization", "Basic " + authorization)
                .header("Accept", "application/json").header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            HttpResponse<String> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new OperationException(read ? "INTERRUPTED" : "MUTATION_OUTCOME_UNKNOWN",
                        read ? "Jira read interrupted" : "Write interrupted; outcome unknown. Re-read state before retrying; no automatic retry occurred.");
            } catch (IOException exception) {
                if (read && attempt < attempts) {
                    pause(backoff(attempt));
                    continue;
                }
                if (!read) {
                    throw uncertainWrite();
                }
                throw new OperationException(exception instanceof HttpTimeoutException ? "JIRA_TIMEOUT" : "JIRA_CONNECTION_ERROR",
                        "Jira read failed within the configured timeout/retry budget; transport details omitted");
            }
            int status = response.statusCode();
            if (status == expectedStatus || (allowNotFound && status == 404)) {
                return response;
            }
            if (read && attempt < attempts && RETRYABLE.contains(status)) {
                Duration wait = retryDelay(response, attempt);
                if (wait.compareTo(settings.maxRetryDelay()) <= 0) {
                    pause(wait);
                    continue;
                }
                throw new OperationException(status == 429 ? "RATE_LIMITED" : "JIRA_UNAVAILABLE",
                        "Server Retry-After exceeds the configured wait budget; request was not retried early");
            }
            if (!read && (status >= 500 || (status >= 200 && status < 300))) {
                throw uncertainWrite();
            }
            throw statusError(status);
        }
        throw new IllegalStateException("Read retry loop exhausted unexpectedly");
    }

    private Duration retryDelay(HttpResponse<String> response, int attempt) {
        String header = response.headers().firstValue("Retry-After").orElse("").trim();
        if (header.matches("[0-9]+")) {
            try {
                return Duration.ofSeconds(Long.parseLong(header));
            } catch (ArithmeticException | NumberFormatException exception) {
                return settings.maxRetryDelay().plusSeconds(1);
            }
        }
        if (!header.isEmpty()) {
            try {
                Duration remaining = Duration.between(clock.instant(), ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                return remaining.isNegative() ? Duration.ZERO : remaining;
            } catch (RuntimeException ignored) {

            }
        }
        return backoff(attempt);
    }

    private Duration backoff(int attempt) {
        return Duration.ofMillis(Math.min(settings.maxRetryDelay().toMillis(), 100L << (attempt - 1)));
    }

    private void pause(Duration wait) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OperationException("INTERRUPTED", "Jira read retry interrupted");
        }
    }

    private static OperationException uncertainWrite() {
        return new OperationException("MUTATION_OUTCOME_UNKNOWN",
                "Write outcome is unknown. No automatic retry occurred; re-read server state before retrying.");
    }

    private static OperationException statusError(int status) {
        return switch (status) {
            case 400, 422 -> new OperationException("JIRA_INVALID_REQUEST", "Jira rejected the request (HTTP " + status + "); inspect the provisional contract and field mappings");
            case 401 -> new OperationException("JIRA_AUTHENTICATION_FAILED", "Jira authentication failed (HTTP 401); check configured credentials");
            case 403 -> new OperationException("JIRA_PERMISSION_DENIED", "Jira denied the operation (HTTP 403)");
            case 404 -> new OperationException("NOT_FOUND", "Jira issue or endpoint was not found (HTTP 404)");
            case 409 -> new OperationException("CONFLICT", "Jira rejected a conflicting change (HTTP 409)");
            case 429 -> new OperationException("RATE_LIMITED", "Jira rate limited the request (HTTP 429); no further automatic retry will occur");
            default -> new OperationException(status >= 300 && status < 400 ? "REDIRECT_REJECTED" : "JIRA_UNAVAILABLE",
                    "Unexpected Jira HTTP status " + status + "; response body and headers omitted");
        };
    }

    @Override
    public void close() { client.close(); }

    @FunctionalInterface
    interface Sleeper { void sleep(Duration duration) throws InterruptedException; }
}
