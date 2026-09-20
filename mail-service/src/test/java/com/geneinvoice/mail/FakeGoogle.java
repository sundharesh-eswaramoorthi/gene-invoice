package com.geneinvoice.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Google's token, tokeninfo and revoke endpoints and the Gmail API, stood in for by a real HTTP
 * server on a random local port. Accounts are registered by refresh token; the token endpoint hands
 * out {@code tok-1}, {@code tok-2}, … and remembers whose each is, so a Gmail call on {@code users/me}
 * knows its mailbox. Every request is recorded; routes answer by method and decoded path (a mailbox's
 * own route first), and anything without a route gets Gmail's 404.
 */
public final class FakeGoogle implements AutoCloseable {

    public static final ObjectMapper JSON = new ObjectMapper();
    public static final String SEND_AND_READ =
            "https://www.googleapis.com/auth/gmail.send https://www.googleapis.com/auth/gmail.readonly";

    public record Exchange(String method, String path, Map<String, List<String>> query,
                           Map<String, List<String>> headers, String body, String mailbox) {

        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream())
                    .findFirst().orElse(null);
        }

        public String param(String name) {
            List<String> values = query.get(name);
            return values == null ? null : values.get(0);
        }

        public List<String> params(String name) {
            return query.getOrDefault(name, List.of());
        }

        /** An {@code application/x-www-form-urlencoded} body, one value per name. */
        public Map<String, String> form() {
            Map<String, String> form = new LinkedHashMap<>();
            decodePairs(body).forEach((name, values) -> form.put(name, values.get(0)));
            return form;
        }

        public JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public record Reply(int status, String json) {}

    /** Reads the request and closes the connection without answering, like a connection dropped mid-call. */
    public static final Reply HANG_UP = new Reply(-1, null);

    /** A Gmail account the token endpoint knows by its refresh token. */
    private record Account(String email, String scope, String historyId) {}

    private final HttpServer server;
    private final List<Exchange> exchanges = new CopyOnWriteArrayList<>();
    private final Map<String, Function<Exchange, Reply>> routes = new ConcurrentHashMap<>();
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, String> tokenOwners = new ConcurrentHashMap<>();
    private final AtomicInteger issued = new AtomicInteger();

    private FakeGoogle(HttpServer server) {
        this.server = server;
    }

    public static FakeGoogle start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            FakeGoogle fake = new FakeGoogle(server);
            server.createContext("/", fake::handle);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "fake-google");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            fake.defaults();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    public String tokenUrl() {
        return baseUrl() + "/token";
    }

    public String tokeninfoUrl() {
        return baseUrl() + "/tokeninfo";
    }

    public String revokeUrl() {
        return baseUrl() + "/revoke";
    }

    /** A Gmail API path on the caller's own mailbox, e.g. {@code api("/messages/send")}. */
    public static String api(String rest) {
        return "/gmail/v1/users/me" + rest;
    }

    /** An account that grants send and read, whose history stands at 1000. */
    public void account(String email, String refreshToken) {
        account(email, refreshToken, SEND_AND_READ);
    }

    /** With {@code scope} null the token response names no scope, and tokeninfo says {@link #SEND_AND_READ}. */
    public void account(String email, String refreshToken, String scope) {
        accounts.put(refreshToken, new Account(email, scope, "1000"));
    }

    /** Google no longer accepts this refresh token ({@code invalid_grant}), as when a Testing app's token expires. */
    public void expire(String refreshToken) {
        accounts.remove(refreshToken);
    }

    /** Where the account's history stands, as its profile says. */
    public void historyAt(String email, String historyId) {
        accounts.replaceAll((token, a) -> a.email().equals(email) ? new Account(a.email(), a.scope(), historyId) : a);
    }

    public void on(String method, String path, int status, String json) {
        on(method, path, exchange -> new Reply(status, json));
    }

    public void on(String method, String path, Function<Exchange, Reply> answer) {
        routes.put(method + " " + path, answer);
    }

    /** A route for one mailbox only; other mailboxes get the general route. */
    public void onMailbox(String mailbox, String method, String path, Function<Exchange, Reply> answer) {
        routes.put(mailbox + "|" + method + " " + path, answer);
    }

    public void onMailbox(String mailbox, String method, String path, int status, String json) {
        onMailbox(mailbox, method, path, exchange -> new Reply(status, json));
    }

    /** Answers with each reply in turn, then keeps giving the last. */
    public void onSequence(String method, String path, Reply... replies) {
        AtomicInteger next = new AtomicInteger();
        on(method, path, exchange -> replies[Math.min(next.getAndIncrement(), replies.length - 1)]);
    }

    public List<Exchange> exchanges() {
        return List.copyOf(exchanges);
    }

    public List<Exchange> requests(String method, String path) {
        return exchanges.stream().filter(e -> e.method().equals(method) && e.path().equals(path)).toList();
    }

    public List<Exchange> requests(String mailbox, String method, String path) {
        return requests(method, path).stream().filter(e -> mailbox.equals(e.mailbox())).toList();
    }

    /** Forgets the requests so far, keeping accounts, tokens and routes. */
    public void clearRequests() {
        exchanges.clear();
    }

    public void reset() {
        exchanges.clear();
        routes.clear();
        accounts.clear();
        tokenOwners.clear();
        issued.set(0);
        defaults();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static String gmailError(int code, String message) {
        return "{\"error\":{\"code\":" + code + ",\"message\":\"" + message + "\",\"status\":\"ERROR\"}}";
    }

    public static String tokenError(String error, String description) {
        return "{\"error\":\"" + error + "\",\"error_description\":\"" + description + "\"}";
    }

    public static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Google as it answers when all is well. */
    private void defaults() {
        on("POST", "/token", exchange -> {
            Account account = accounts.get(String.valueOf(exchange.form().get("refresh_token")));
            if (account == null) return new Reply(400, tokenError("invalid_grant", "Token has been expired or revoked."));
            String token = "tok-" + issued.incrementAndGet();
            tokenOwners.put(token, account.email());
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("access_token", token);
            answer.put("expires_in", 3599);
            answer.put("token_type", "Bearer");
            if (account.scope() != null) answer.put("scope", account.scope());
            return new Reply(200, json(answer));
        });
        on("GET", "/tokeninfo", exchange -> tokenOwners.containsKey(String.valueOf(exchange.param("access_token")))
                ? new Reply(200, json(Map.of("scope", SEND_AND_READ, "expires_in", 3500)))
                : new Reply(400, tokenError("invalid_token", "Invalid Value")));
        on("POST", "/revoke", 200, "{}");
        on("GET", api("/profile"), exchange -> {
            if (exchange.mailbox() == null) return new Reply(401, gmailError(401, "Invalid Credentials"));
            String historyId = accounts.values().stream().filter(a -> a.email().equals(exchange.mailbox()))
                    .map(Account::historyId).findFirst().orElse("1000");
            return new Reply(200, json(Map.of("emailAddress", exchange.mailbox(), "historyId", historyId)));
        });
    }

    private void handle(HttpExchange http) throws IOException {
        try {
            String body = new String(http.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, List<String>> headers = new LinkedHashMap<>();
            http.getRequestHeaders().forEach((name, values) -> headers.put(name, new ArrayList<>(values)));
            String authorization = http.getRequestHeaders().getFirst("Authorization");
            String mailbox = authorization != null && authorization.startsWith("Bearer ")
                    ? tokenOwners.get(authorization.substring(7)) : null;
            Exchange exchange = new Exchange(http.getRequestMethod(), http.getRequestURI().getPath(),
                    decodePairs(http.getRequestURI().getRawQuery()), headers, body, mailbox);
            exchanges.add(exchange);

            String key = exchange.method() + " " + exchange.path();
            Function<Exchange, Reply> route = mailbox == null ? null : routes.get(mailbox + "|" + key);
            if (route == null) route = routes.get(key);
            Reply reply = route == null ? new Reply(404, gmailError(404, "Requested entity was not found.")) : route.apply(exchange);
            if (reply == HANG_UP) return;
            byte[] out = reply.json() == null ? new byte[0] : reply.json().getBytes(StandardCharsets.UTF_8);
            http.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            http.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = http.getResponseBody()) {
                    os.write(out);
                }
            }
        } finally {
            http.close();
        }
    }

    private static Map<String, List<String>> decodePairs(String encoded) {
        Map<String, List<String>> pairs = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) return pairs;
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            pairs.computeIfAbsent(name, n -> new ArrayList<>()).add(value);
        }
        return pairs;
    }
}
