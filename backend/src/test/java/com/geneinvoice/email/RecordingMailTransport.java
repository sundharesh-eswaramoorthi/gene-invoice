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

/**
 * The mail service every integration test runs against: one bean in the shared context, switched
 * between behaviours by the test rather than mocked per class, which would fork the context. It
 * records every hand-off and answers the way the service does — a copy it has is returned as it is,
 * and only a retry sends a failed or unsent one again, with its {@code seq} one up. Starts, and is
 * reset to, "not configured": what the app does without the mail service.
 */
public class RecordingMailTransport implements MailTransport, MailConnections {

    /** {@code SUCCESS}: the service takes the copies. The failures are of the hand-off itself. */
    public enum Mode { NOT_CONFIGURED, SUCCESS, TRANSIENT_FAILURE, PERMANENT_FAILURE }

    public static final String UNAVAILABLE = "The mail service is unavailable (503)";
    public static final String REFUSED = "The mail service refused the email: Bad Request";

    /** What the service makes of a new or retried copy. */
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

    /** Runs as each hand-off arrives, while the dispatcher waits on the service. */
    public void beforeSubmit(Consumer<Submission> hook) {
        this.beforeSubmit = hook;
    }

    public void outcome(Function<CopyRequest, Outcome> decide) {
        this.outcome = decide;
    }

    /** Which copies the answer to a hand-off mentions: all of them, as the service's contract says, unless a test says otherwise. */
    public void reported(Predicate<CopyRequest> which) {
        this.reported = which;
    }

    /** Every hand-off, accepted or not. */
    public List<Submission> submissions() {
        return List.copyOf(submissions);
    }

    /** Every copy handed over, in order, across all hand-offs. */
    public List<CopyRequest> copiesHandedOver() {
        List<CopyRequest> all = new ArrayList<>();
        submissions.forEach(s -> all.addAll(s.copies()));
        return all;
    }

    /** The service's state of a copy, as the last hand-off left it. */
    public CopyState copy(String externalId) {
        return copies.get(externalId);
    }

    // ---- MailTransport -----------------------------------------------------------------

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

    // ---- MailConnections ---------------------------------------------------------------

    /** Every connection call fails this way until cleared, as when the service is down or Google refuses. */
    public void failConnections(MailConnectException failure) {
        this.connectionFailure = failure;
    }

    /** Runs as a connect arrives, while the caller waits on the service (and the service on Google). */
    public void beforeConnect(Runnable hook) {
        this.beforeConnect = hook;
    }

    public void syncResult(SyncResult result) {
        this.syncResult = result;
    }

    /** The service holds this connection for the user, as if they had connected. */
    public void hold(ConnectionState state) {
        connections.put(Long.parseLong(state.ownerRef()), state);
    }

    public List<ConnectCall> connectCalls() {
        return List.copyOf(connectCalls);
    }

    public List<Long> syncCalls() {
        return List.copyOf(syncCalls);
    }

    /** Every disconnect asked for, including those that failed. */
    public List<Long> disconnectCalls() {
        return List.copyOf(disconnectCalls);
    }

    /** The connection the service holds for the user, as it stands; null when it never had one. */
    public ConnectionState held(long userId) {
        return connections.get(userId);
    }

    public static ConnectionState state(long userId, String name, ConnectionStatus status, String gmail, String reason) {
        return state(userId, name, status, gmail, reason, Instant.parse("2026-09-20T10:05:00Z"), null);
    }

    /** The same, with a say over the last read of the mailbox and what it left behind. */
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
        // As the service does (mail-service.md §4.3): the secrets go, the status is DISCONNECTED and
        // the reason, the last check and its error — all of the connection that is gone — are cleared.
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
