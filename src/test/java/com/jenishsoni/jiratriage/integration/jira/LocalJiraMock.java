package com.jenishsoni.jiratriage.integration.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

final class LocalJiraMock implements AutoCloseable {

    final ObjectMapper mapper = new ObjectMapper();
    final List<Request> requests = new CopyOnWriteArrayList<>();
    final Map<String, ObjectNode> issues = new LinkedHashMap<>();
    volatile Function<Request, Reply> responder = this::statefulReply;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    LocalJiraMock() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setExecutor(executor);
        issues.put("MOCK-1", issue("MOCK-1", "Password reset link expires", "Opening the password reset link shows an error"));
        issues.put("MOCK-2", issue("MOCK-2", "Password reset link expires", "Opening the password reset link shows an error"));
        issues.put("MOCK-3", issue("MOCK-3", "Warehouse delivery schedule", "Rotate inventory barcode scanners"));
        server.createContext("/", exchange -> {
            try (exchange) {
                Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                requests.add(request);
                Reply reply = responder.apply(request);
                if (reply.delayMillis() > 0) { Thread.sleep(reply.delayMillis()); }
                reply.headers().forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(reply.status(), reply.status() == 204 ? -1 : bytes.length);
                if (reply.status() != 204) { exchange.getResponseBody().write(bytes); }
            } catch (IOException ignored) {

            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
    }

    URI baseUrl() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }

    long count(String method) { return requests.stream().filter(request -> request.method().equals(method)).count(); }

    synchronized Reply statefulReply(Request request) {
        try {
            if (request.method().equals("GET") && request.uri().getPath().equals("/rest/api/3/search/jql")) {
                Map<String, String> query = request.query();
                int page = query.containsKey("nextPageToken") ? Integer.parseInt(query.get("nextPageToken").substring(5)) : 0;
                List<ObjectNode> all = new ArrayList<>(issues.values());
                ObjectNode root = mapper.createObjectNode().put("isLast", page + 1 >= all.size());
                root.putArray("issues").add(all.get(page));
                if (page + 1 < all.size()) { root.put("nextPageToken", "page-" + (page + 1)); }
                return Reply.json(200, root.toString());
            }
            if (request.uri().getPath().startsWith("/rest/api/3/issue/")) {
                String key = request.uri().getPath().substring("/rest/api/3/issue/".length());
                ObjectNode issue = issues.get(key);
                if (issue == null) { return Reply.json(404, "{}"); }
                if (request.method().equals("GET")) { return Reply.json(200, issue.toString()); }
                if (request.method().equals("PUT")) {
                    var fields = mapper.readTree(request.body()).path("fields");
                    fields.properties().forEach(entry -> ((ObjectNode) issue.path("fields")).set(entry.getKey(), entry.getValue()));
                    return Reply.json(204, "");
                }
            }
            if (request.method().equals("POST") && request.uri().getPath().equals("/rest/api/3/issueLink")) {
                var body = mapper.readTree(request.body());
                String source = body.path("inwardIssue").path("key").asText();
                String target = body.path("outwardIssue").path("key").asText();
                addLink(source, target);
                return Reply.json(201, "{}");
            }
            return Reply.json(404, "{}");
        } catch (Exception exception) {
            return Reply.json(400, "{}");
        }
    }

    void addLink(String source, String target) {
        var outward = mapper.createObjectNode();
        outward.putObject("type").put("id", "10000");
        outward.putObject("outwardIssue").put("key", target);
        ((com.fasterxml.jackson.databind.node.ArrayNode) issues.get(source).path("fields").path("issuelinks")).add(outward);
        if (issues.containsKey(target)) {
            var inward = mapper.createObjectNode();
            inward.putObject("type").put("id", "10000");
            inward.putObject("inwardIssue").put("key", source);
            ((com.fasterxml.jackson.databind.node.ArrayNode) issues.get(target).path("fields").path("issuelinks")).add(inward);
        }
    }

    private ObjectNode issue(String key, String summary, String description) {
        ObjectNode root = mapper.createObjectNode().put("key", key);
        ObjectNode fields = root.putObject("fields").put("summary", summary);
        fields.putObject("description").put("type", "doc").put("version", 1).putArray("content")
                .addObject().put("type", "paragraph").putArray("content").addObject().put("type", "text").put("text", description);
        fields.putObject("status").putObject("statusCategory").put("key", "new");
        fields.putObject("priority").put("id", "2");
        fields.putArray("labels").add("bug");
        fields.putArray("issuelinks");
        return root;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    record Request(String method, URI uri, String authorization, String body) {
        Map<String, String> query() {
            Map<String, String> fields = new LinkedHashMap<>();
            for (String pair : uri.getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                fields.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            return fields;
        }
    }

    record Reply(int status, Map<String, String> headers, String body, long delayMillis) {
        static Reply json(int status, String body) { return new Reply(status, Map.of("Content-Type", "application/json"), body, 0); }
    }
}
