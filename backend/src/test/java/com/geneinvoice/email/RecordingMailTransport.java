package com.geneinvoice.email;

import com.geneinvoice.email.transport.ConnectionState;
import com.geneinvoice.email.transport.ConnectionStatus;
import com.geneinvoice.email.transport.CopyRequest;
import com.geneinvoice.email.transport.CopyState;
import com.geneinvoice.email.transport.MailConnectException;
import com.geneinvoice.email.transport.MailConnections;
import com.geneinvoice.email.transport.MailSendException;
import com.geneinvoice.email.transport.MailTransport;
import com.geneinvoice.email.transport.NoopMailTransport;
import com.geneinvoice.email.transport.Submission;
import com.geneinvoice.email.transport.SyncResult;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class RecordingMailTransport implements MailTransport, MailConnections {

    public enum Mode { NOT_CONFIGURED, SUCCESS, TRANSIENT_FAILURE, PERMANENT_FAILURE }

    public static final String UNAVAILABLE = "The mail service is unavailable (503)";
    public static final String REFUSED = "The mail service refused the email: Bad Request";

    public record Outcome(RecipientDeliveryStatus status, String error) {
        public static Outcome queued() {
            return new Outcome(RecipientDeliveryStatus.QUEUED, null);
        }

        public static Outcome notSent(String reason) {
            return new Outcome(RecipientDeliveryStatus.NOT_SENT, reason);
        }
    }

    public record ConnectCall(long userId, String name, String clientId, String clientSecret, String refreshToken) {}

    private volatile Mode mode = Mode.NOT_CONFIGURED;
    private volatile Consumer<Submission> beforeSubmit = s -> {};
    private volatile Function<CopyRequest, Outcome> outcome = copy -> Outcome.queued();
    private volatile Predicate<CopyRequest> reported = copy -> true;
    private final List<Submission> submissions = new CopyOnWriteArrayList<>();
    private final Map<String, CopyState> copies = new ConcurrentHashMap<>();

    private final Map<Long, ConnectionState> connections = new ConcurrentHashMap<>();
    private final List<ConnectCall> connectCalls = new CopyOnWriteArrayList<>();
    private final List<Long> syncCalls = new CopyOnWriteArrayList<>();
    private final List<Long> disconnectCalls = new CopyOnWriteArrayList<>();
    private volatile MailConnectException connectionFailure;
    private volatile Runnable beforeConnect = () -> {};
    private volatile SyncResult syncResult = new SyncResult(true, 2, 1, null);

    public void reset() {
        mode = Mode.NOT_CONFIGURED;
        beforeSubmit = s -> {};
        outcome = copy -> Outcome.queued();
        reported = copy -> true;
        submissions.clear();
        copies.clear();
        connections.clear();
        connectCalls.clear();
        syncCalls.clear();
        disconnectCalls.clear();
        connectionFailure = null;
        beforeConnect = () -> {};
        syncResult = new SyncResult(true, 2, 1, null);
    }

    public void mode(Mode mode) {
        this.mode = mode;
    }

    public void beforeSubmit(Consumer<Submission> hook) {
        this.beforeSubmit = hook;
    }

    public void outcome(Function<CopyRequest, Outcome> decide) {
        this.outcome = decide;
    }

    public void reported(Predicate<CopyRequest> which) {
        this.reported = which;
    }

    public List<Submission> submissions() {
        return List.copyOf(submissions);
    }

    public List<CopyRequest> copiesHandedOver() {
        List<CopyRequest> all = new ArrayList<>();
        submissions.forEach(s -> all.addAll(s.copies()));
        return all;
    }

    public CopyState copy(String externalId) {
        return copies.get(externalId);
    }

    @Override
    public boolean isConfigured() {
        return mode != Mode.NOT_CONFIGURED;
    }

    @Override
    public List<CopyState> submit(Submission submission) {
        submissions.add(submission);
        beforeSubmit.accept(submission);
        return switch (mode) {
            case NOT_CONFIGURED -> throw new IllegalStateException("submit() called while not configured");
            case TRANSIENT_FAILURE -> throw new MailSendException(UNAVAILABLE, true);
            case PERMANENT_FAILURE -> throw new MailSendException(REFUSED, false);
            case SUCCESS -> submission.copies().stream().map(copy -> take(submission, copy))
                    .filter(state -> submission.copies().stream()
                            .anyMatch(c -> c.externalId().equals(state.externalId()) && reported.test(c)))
                    .toList();
        };
    }

    private CopyState take(Submission submission, CopyRequest copy) {
        CopyState known = copies.get(copy.externalId());
        boolean again = known != null && submission.retry()
                && (known.status() == RecipientDeliveryStatus.FAILED || known.status() == RecipientDeliveryStatus.NOT_SENT);
        if (known != null && !again) return known;
        Outcome o = outcome.apply(copy);
        CopyState state = new CopyState(copy.externalId(), submission.groupRef(), known == null ? 1 : known.seq() + 1,
                o.status(), o.error(), 0, null, null, null, false, null, null, null, null, null);
        copies.put(copy.externalId(), state);
        return state;
    }

    public void failConnections(MailConnectException failure) {
        this.connectionFailure = failure;
    }

    public void beforeConnect(Runnable hook) {
        this.beforeConnect = hook;
    }

    public void syncResult(SyncResult result) {
        this.syncResult = result;
    }

    public void hold(ConnectionState state) {
        connections.put(Long.parseLong(state.ownerRef()), state);
    }

    public List<ConnectCall> connectCalls() {
        return List.copyOf(connectCalls);
    }

    public List<Long> syncCalls() {
        return List.copyOf(syncCalls);
    }

    public List<Long> disconnectCalls() {
        return List.copyOf(disconnectCalls);
    }

    public ConnectionState held(long userId) {
        return connections.get(userId);
    }

    public static ConnectionState state(long userId, String name, ConnectionStatus status, String gmail, String reason) {
        return state(userId, name, status, gmail, reason, Instant.parse("2026-09-20T10:05:00Z"), null);
    }

    public static ConnectionState state(long userId, String name, ConnectionStatus status, String gmail, String reason,
                                        Instant lastSyncedAt, String lastSyncError) {
        return new ConnectionState(String.valueOf(userId), name, status, gmail, "client-" + userId,
                List.of("https://www.googleapis.com/auth/gmail.send", "https://www.googleapis.com/auth/gmail.readonly"),
                reason, Instant.parse("2026-09-20T10:00:00Z"), lastSyncedAt, lastSyncError);
    }

    @Override
    public ConnectionState connect(long userId, String name, String clientId, String clientSecret,
                                   String refreshToken) {
        connectCalls.add(new ConnectCall(userId, name, clientId, clientSecret, refreshToken));
        beforeConnect.run();
        if (mode == Mode.NOT_CONFIGURED) throw new MailConnectException(503, NoopMailTransport.NOT_CONFIGURED);
        if (connectionFailure != null) throw connectionFailure;
        ConnectionState state = new ConnectionState(String.valueOf(userId), name, ConnectionStatus.CONNECTED,
                "user" + userId + "@gmail.com", clientId, List.of("https://www.googleapis.com/auth/gmail.send",
                "https://www.googleapis.com/auth/gmail.readonly"), null, Instant.now(), null, null);
        connections.put(userId, state);
        return state;
    }

    @Override
    public Optional<ConnectionState> connection(long userId) {
        if (mode == Mode.NOT_CONFIGURED) return Optional.empty();
        if (connectionFailure != null) throw connectionFailure;
        return Optional.ofNullable(connections.get(userId));
    }

    @Override
    public void disconnect(long userId) {
        disconnectCalls.add(userId);
        if (mode == Mode.NOT_CONFIGURED) throw new MailConnectException(503, NoopMailTransport.NOT_CONFIGURED);
        if (connectionFailure != null) throw connectionFailure;
        connections.computeIfPresent(userId, (id, s) -> new ConnectionState(s.ownerRef(), s.ownerName(),
                ConnectionStatus.DISCONNECTED, s.gmailAddress(), s.clientId(), s.scopes(), null, s.connectedAt(),
                null, null));
    }

    @Override
    public SyncResult syncNow(long userId) {
        syncCalls.add(userId);
        if (mode == Mode.NOT_CONFIGURED) return new SyncResult(false, 0, 0, NoopMailTransport.NOT_CONFIGURED);
        if (connectionFailure != null) return new SyncResult(true, 0, 0, connectionFailure.getMessage());
        ConnectionState state = connections.get(userId);
        if (state == null || state.status() != ConnectionStatus.CONNECTED) {
            return new SyncResult(false, 0, 0, "Gmail is not connected");
        }
        return syncResult;
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        RecordingMailTransport recordingMailTransport() {
            return new RecordingMailTransport();
        }
    }
}
