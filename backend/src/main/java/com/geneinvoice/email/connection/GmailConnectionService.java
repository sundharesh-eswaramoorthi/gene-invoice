package com.geneinvoice.email.connection;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.email.connection.GmailConnectionDtos.ConnectGmailRequest;
import com.geneinvoice.email.connection.GmailConnectionDtos.DeliveryGmailDto;
import com.geneinvoice.email.connection.GmailConnectionDtos.GmailConnectionDto;
import com.geneinvoice.email.connection.GmailConnectionDtos.UserGmailDto;
import com.geneinvoice.email.transport.ConnectionState;
import com.geneinvoice.email.transport.ConnectionStatus;
import com.geneinvoice.email.transport.MailConnectException;
import com.geneinvoice.email.transport.MailConnections;
import com.geneinvoice.email.transport.MailTransport;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Each internal user's own Gmail connection (mail-service.md §5.6). The mail service keeps the
 * connection and its secrets; the app keeps a copy of where it stands ({@link GmailConnection}), so
 * it can say who is connected without asking, and still answer when the service is down. Nothing
 * here holds a transaction open while the service is called: connecting waits on Google.
 */
@Service
public class GmailConnectionService {

    static final String ENTER_CLIENT_ID = "Enter the client ID";
    static final String ENTER_CLIENT_SECRET = "Enter the client secret";
    static final String ENTER_REFRESH_TOKEN = "Enter the refresh token";

    private final GmailConnectionRepository repository;
    private final MailTransport transport;
    private final MailConnections connections;
    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    /** The caller's transaction, or a new one; so the copy is written the same way however it is called. */
    private final TransactionTemplate transactions;
    /** Always a transaction of its own, which commits at once whatever the caller is in. */
    private final TransactionTemplate ownTransaction;

    public GmailConnectionService(GmailConnectionRepository repository, MailTransport transport,
                                  MailConnections connections, CurrentUser currentUser, UserRepository userRepository,
                                  PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transport = transport;
        this.connections = connections;
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---- the caller's own ------------------------------------------------------------

    /** As the service knows it, and the app's copy brought up to date; the copy alone when the service cannot say. */
    public GmailConnectionDto mine() {
        User me = requireInternal();
        if (!transport.isConfigured()) return fromCopy(me.getId(), false, null);
        try {
            return connections.connection(me.getId())
                    .map(state -> {
                        mirror(me.getId(), state);
                        return dto(state);
                    })
                    .orElseGet(() -> {
                        forget(me.getId());
                        return new GmailConnectionDto(true, GmailStatus.NOT_CONNECTED,
                                null, null, null, null, null, null, null);
                    });
        } catch (MailConnectException e) {
            return fromCopy(me.getId(), true, e.getMessage());
        }
    }

    /**
     * Connects the caller's Gmail with the three values they made, or replaces their connection.
     * The service checks them with Google at once; its refusal comes back as a
     * {@link MailConnectException} carrying the status and message to show.
     */
    public GmailConnectionDto connect(ConnectGmailRequest req) {
        User me = requireInternal();
        String clientId = trimmed(req == null ? null : req.clientId());
        String clientSecret = trimmed(req == null ? null : req.clientSecret());
        String refreshToken = trimmed(req == null ? null : req.refreshToken());
        Map<String, String> errors = new LinkedHashMap<>();
        // The same checks and words as the service's, so an overlong value is named under its field here.
        check(errors, "clientId", clientId, ENTER_CLIENT_ID, FieldLimits.GMAIL_CLIENT_ID, "The client ID is too long");
        check(errors, "clientSecret", clientSecret, ENTER_CLIENT_SECRET, FieldLimits.GMAIL_CLIENT_SECRET,
                "The client secret is too long");
        check(errors, "refreshToken", refreshToken, ENTER_REFRESH_TOKEN, FieldLimits.GMAIL_REFRESH_TOKEN,
                "The refresh token is too long");
        if (!errors.isEmpty()) throw new GlobalExceptionHandler.InvalidFieldsException(errors);

        ConnectionState state = connections.connect(me.getId(), nameOf(me), clientId, clientSecret, refreshToken);
        mirror(me.getId(), state);
        return dto(state);
    }

    /**
     * Any internal user may disconnect their own Gmail, including one who no longer sends email:
     * their mailbox is theirs to take back.
     */
    public void disconnect() {
        User me = requireInternal();
        connections.disconnect(me.getId());
        forget(me.getId());
    }

    /**
     * Someone else's connection, by those who manage users: the service revokes it at Google and
     * forgets the secrets. A user deleted since still has theirs removed while the app has a copy.
     */
    public void disconnectUser(long userId) {
        if (!userRepository.existsById(userId) && !repository.existsById(userId)) {
            throw new NotFoundException("User not found");
        }
        connections.disconnect(userId);
        forget(userId);
    }

    private User requireInternal() {
        User me = currentUser.require();
        // Customer logins do not connect Gmail: their email is saved in the app, not sent.
        if (me.getCustomerId() != null) throw new AccessDeniedException("Not allowed");
        return me;
    }

    // ---- anyone's, from the app's copy -------------------------------------------------

    @Transactional(readOnly = true)
    public UserGmailDto ofUser(Long userId) {
        if (!userRepository.existsById(userId)) throw new NotFoundException("User not found");
        return repository.findById(userId)
                .map(c -> new UserGmailDto(GmailStatus.of(c.getStatus()), c.getGmailAddress(), c.getReason(),
                        c.getUpdatedAt()))
                .orElse(new UserGmailDto(GmailStatus.NOT_CONNECTED, null, null, null));
    }

    @Transactional(readOnly = true)
    public DeliveryGmailDto deliveryOf(Long userId) {
        return repository.findById(userId)
                .map(c -> new DeliveryGmailDto(GmailStatus.of(c.getStatus()), c.getGmailAddress(), c.getReason(),
                        c.getLastSyncedAt(), c.getLastSyncError()))
                .orElse(new DeliveryGmailDto(GmailStatus.NOT_CONNECTED, null, null, null, null));
    }

    /** Whether each of these users can send from their Gmail; anyone the app has no copy for has not connected. */
    @Transactional(readOnly = true)
    public Map<Long, GmailStatus> statuses(Collection<Long> userIds) {
        Map<Long, GmailStatus> statuses = new HashMap<>();
        userIds.stream().filter(Objects::nonNull).forEach(id -> statuses.put(id, GmailStatus.NOT_CONNECTED));
        if (statuses.isEmpty()) return statuses;
        repository.findAllById(statuses.keySet()).forEach(c -> statuses.put(c.getUserId(), GmailStatus.of(c.getStatus())));
        return statuses;
    }

    // ---- keeping the copy ----------------------------------------------------------------

    /**
     * Brings the app's copy up to what the service said. Returns the status it had before —
     * {@code DISCONNECTED} when the app had no copy, as for one disconnected. The answer to a first
     * connect and the service's report of it can arrive at the same moment, so the copy is never
     * written by reading and then inserting it: see {@link #lockCopy}.
     */
    public ConnectionStatus mirror(long userId, ConnectionState state) {
        ensureCopy(userId);
        return transactions.execute(tx -> {
            GmailConnection copy = repository.findByIdForUpdate(userId).orElseThrow();
            ConnectionStatus before = copy.getStatus();
            copy.setStatus(state.status() == null ? ConnectionStatus.DISCONNECTED : state.status());
            copy.setGmailAddress(fit(state.gmailAddress() == null ? null : state.gmailAddress().toLowerCase(Locale.ROOT),
                    GmailConnection.ADDRESS_MAX));
            copy.setReason(fit(state.statusReason(), GmailConnection.REASON_MAX));
            copy.setConnectedAt(state.connectedAt());
            copy.setLastSyncedAt(state.lastSyncedAt());
            copy.setLastSyncError(fit(state.lastSyncError(), GmailConnection.REASON_MAX));
            repository.save(copy);
            return before;
        });
    }

    /**
     * The service has no connection for the user (any more): there is nothing left to disconnect
     * either, should their removal have been waiting ({@link GmailDisconnects}).
     */
    public void forget(long userId) {
        transactions.executeWithoutResult(tx -> repository.findByIdForUpdate(userId).ifPresent(copy -> {
            cleared(copy);
            repository.save(copy);
        }));
    }

    /**
     * What is left in the copy of a connection that is gone: that there is none. The last check and
     * the error it left belong to the connection, not to the user, so they go with it — they would
     * otherwise be answered beside {@code NOT_CONNECTED} by {@code GET /api/me/gmail} and
     * {@code GET /api/emails/delivery}, telling someone with no connection that their last Gmail
     * check failed (§5.6, and the service clears the same two on DELETE, §4.3).
     */
    static void cleared(GmailConnection copy) {
        copy.setStatus(ConnectionStatus.DISCONNECTED);
        copy.setReason(null);
        copy.setLastSyncedAt(null);
        copy.setLastSyncError(null);
        copy.setDisconnectRequestedAt(null);
    }

    /**
     * The user's copy, made blank first when they have none, and locked until the caller's
     * transaction ends (it must be in one). Two writers of a first copy therefore take turns on the
     * same row rather than both inserting it, which would fail the second with a conflict.
     */
    GmailConnection lockCopy(long userId) {
        ensureCopy(userId);
        return repository.findByIdForUpdate(userId).orElseThrow();
    }

    /**
     * Inserts a blank copy ({@code DISCONNECTED}, as good as none) when the user has none, committed
     * at once so every writer then finds it. One a concurrent writer made first is no error.
     */
    private void ensureCopy(long userId) {
        if (repository.existsById(userId)) return;
        try {
            ownTransaction.executeWithoutResult(tx -> repository.insertBlank(userId, Instant.now()));
        } catch (DataIntegrityViolationException e) {
            // Someone else's insert won; theirs is the row to lock.
        }
    }

    // ---- shapes ----------------------------------------------------------------------

    private static GmailConnectionDto dto(ConnectionState s) {
        return new GmailConnectionDto(true, GmailStatus.of(s.status()), s.gmailAddress(), s.clientId(),
                s.statusReason(), s.connectedAt(), s.lastSyncedAt(), s.lastSyncError(), null);
    }

    private GmailConnectionDto fromCopy(Long userId, boolean configured, String serviceError) {
        return repository.findById(userId)
                .map(c -> new GmailConnectionDto(configured, GmailStatus.of(c.getStatus()), c.getGmailAddress(), null,
                        c.getReason(), c.getConnectedAt(), c.getLastSyncedAt(), c.getLastSyncError(), serviceError))
                .orElse(new GmailConnectionDto(configured, GmailStatus.NOT_CONNECTED, null, null, null, null, null,
                        null, serviceError));
    }

    /** As the From header names them. */
    private static String nameOf(User u) {
        return u.getFullName() == null || u.getFullName().isBlank() ? u.getUsername() : u.getFullName().trim();
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void check(Map<String, String> errors, String field, String value, String missing, int max,
                              String tooLong) {
        if (value == null) errors.put(field, missing);
        else if (value.length() > max) errors.put(field, tooLong);
    }

    /** Shortened to fit its column, never between the two halves of a character outside the BMP. */
    private static String fit(String text, int max) {
        if (text == null || text.length() <= max) return text;
        int end = Character.isHighSurrogate(text.charAt(max - 2)) ? max - 2 : max - 1;
        return text.substring(0, end) + "…";
    }
}
