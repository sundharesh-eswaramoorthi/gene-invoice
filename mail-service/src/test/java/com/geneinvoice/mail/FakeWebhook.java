package com.geneinvoice.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** The backend's webhook receiver, stood in for: records every POST and answers with the status it is told. */
public final class FakeWebhook implements AutoCloseable {

    public record Delivery(String timestamp, String signature, String contentType, String body) {

        public JsonNode json() {
            try {
                return FakeGoogle.JSON.readTree(body);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private final HttpServer server;
    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);

    private FakeWebhook(HttpServer server) {
        this.server = server;
    }

    public static FakeWebhook start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            FakeWebhook fake = new FakeWebhook(server);
            server.createContext("/", fake::handle);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "fake-webhook");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String url() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort()
                + "/api/mail-service/events";
    }

    public void answer(int status) {
        this.status.set(status);
    }

    public List<Delivery> deliveries() {
        return List.copyOf(deliveries);
    }

    public void reset() {
        deliveries.clear();
        status.set(200);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange http) throws IOException {
        try {
            String body = new String(http.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            deliveries.add(new Delivery(http.getRequestHeaders().getFirst("X-Mail-Timestamp"),
                    http.getRequestHeaders().getFirst("X-Mail-Signature"),
                    http.getRequestHeaders().getFirst("Content-Type"), body));
            byte[] out = "{\"processed\":0}".getBytes(StandardCharsets.UTF_8);
            http.getResponseHeaders().set("Content-Type", "application/json");
            http.sendResponseHeaders(status.get(), out.length);
            try (OutputStream os = http.getResponseBody()) {
                os.write(out);
            }
        } finally {
            http.close();
        }
    }
}
