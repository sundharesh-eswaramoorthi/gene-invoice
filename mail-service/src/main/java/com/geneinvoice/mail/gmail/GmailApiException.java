package com.geneinvoice.mail.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeoutException;

/**
 * A call to Google failed. The message is written for the person who reads it on a failed email or
 * the sync status, so it names the service, the HTTP status and Google's own explanation.
 */
public class GmailApiException extends RuntimeException {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The HTTP status, or 0 when no response arrived. */
    private final int status;
    private final boolean transientFailure;
    private final boolean outcomeUnknown;

    public GmailApiException(String message, int status, boolean transientFailure, Throwable cause) {
        this(message, status, transientFailure, false, cause);
    }

    public GmailApiException(String message, int status, boolean transientFailure, boolean outcomeUnknown, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.transientFailure = transientFailure;
        this.outcomeUnknown = outcomeUnknown;
    }

    public int status() {
        return status;
    }

    /** Rate limits, Google outages and network trouble; any other refusal will not change on a retry. */
    public boolean isTransientFailure() {
        return transientFailure;
    }

    /**
     * Google may have acted on the request although the call failed: it went out, but no usable
     * answer came back. For a send, the message may be on its way.
     */
    public boolean isOutcomeUnknown() {
        return outcomeUnknown;
    }

    public static GmailApiException of(String service, RestClientException e) {
        if (e instanceof RestClientResponseException response) {
            int code = response.getStatusCode().value();
            String detail = detail(response.getResponseBodyAsString());
            String suffix = detail == null ? "" : ": " + detail;
            if (code == 429 || (code == 403 && rateLimited(response.getResponseBodyAsString()))) {
                return new GmailApiException(service + " is rate limiting requests (" + code + ")" + suffix, code, true, e);
            }
            if (code >= 500) {
                return new GmailApiException(service + " is unavailable (" + code + ")" + suffix, code, true, e);
            }
            return new GmailApiException(service + " refused the request (" + code + ")" + suffix, code, false, e);
        }
        if (e instanceof ResourceAccessException) {
            Throwable cause = e.getMostSpecificCause();
            String reason = cause.getMessage() == null ? unexplained(cause) : cause.getMessage();
            if (neverConnected(e)) {
                return new GmailApiException("Could not reach " + service + ": " + reason, 0, true, false, e);
            }
            // A read timeout, or a connection dropped once the request was on its way: worth another
            // try, but Google may have it already. The JDK client does not cancel a timed-out exchange.
            return new GmailApiException(service + " did not answer: " + reason, 0, true, true, e);
        }
        // An answer that could not be read. Not retried: for a send, Google may well have accepted it.
        return new GmailApiException("Unexpected response from " + service + ": " + e.getMessage(), 0, false, true, e);
    }

    /**
     * Words for a network failure the JDK gave no message for. Its class name ("ClosedChannelException")
     * would otherwise be the reason a person reads on the failed email or the sync status.
     */
    static String unexplained(Throwable cause) {
        if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException
                || cause instanceof SocketTimeoutException) {
            return "the request timed out";
        }
        if (cause instanceof ClosedChannelException || cause instanceof EOFException) {
            return "the connection was closed";
        }
        return "a network error";
    }

    /** No connection was made, so Google never saw the request. */
    private static boolean neverConnected(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException
                    || t instanceof UnknownHostException || t instanceof NoRouteToHostException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Google's explanation from an error body: {@code {"error": {"message": …}}} from the Gmail API,
     * {@code {"error": "invalid_grant", "error_description": …}} from the token endpoint.
     */
    private static String detail(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode node = JSON.readTree(body);
            JsonNode error = node.path("error");
            if (error.isObject()) return text(error.path("message"));
            if (error.isTextual()) {
                String description = text(node.path("error_description"));
                return description == null ? error.asText() : error.asText() + " (" + description + ")";
            }
        } catch (Exception ignored) {
            // Not JSON: an HTML error page from a proxy says nothing worth repeating.
        }
        return null;
    }

    /**
     * Gmail reports some per-user sending limits as a 403 rather than a 429, with the reason in
     * {@code error.errors[].reason}. Those pass like a 429 does; any other 403 is a real refusal.
     */
    private static boolean rateLimited(String body) {
        if (body == null || body.isBlank()) return false;
        try {
            for (JsonNode error : JSON.readTree(body).path("error").path("errors")) {
                String reason = error.path("reason").asText("");
                if (reason.equals("rateLimitExceeded") || reason.equals("userRateLimitExceeded")) return true;
            }
        } catch (Exception ignored) {
            // Not JSON, so not Gmail's rate limit answer.
        }
        return false;
    }

    private static String text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }
}
