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
    private final TransactionTemplate transactions;
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

    public GmailConnectionDto connect(ConnectGmailRequest req) {
        User me = requireInternal();
        String clientId = trimmed(req == null ? null : req.clientId());
        String clientSecret = trimmed(req == null ? null : req.clientSecret());
        String refreshToken = trimmed(req == null ? null : req.refreshToken());
        Map<String, String> errors = new LinkedHashMap<>();
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

    public void disconnect() {
        User me = requireInternal();
        connections.disconnect(me.getId());
        forget(me.getId());
    }

    public void disconnectUser(long userId) {
        if (!userRepository.existsById(userId) && !repository.existsById(userId)) {
            throw new NotFoundException("User not found");
        }
        connections.disconnect(userId);
        forget(userId);
    }

    private User requireInternal() {
        User me = currentUser.require();
        if (me.getCustomerId() != null) throw new AccessDeniedException("Not allowed");
        return me;
    }

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

    @Transactional(readOnly = true)
    public Map<Long, GmailStatus> statuses(Collection<Long> userIds) {
        Map<Long, GmailStatus> statuses = new HashMap<>();
        userIds.stream().filter(Objects::nonNull).forEach(id -> statuses.put(id, GmailStatus.NOT_CONNECTED));
        if (statuses.isEmpty()) return statuses;
        repository.findAllById(statuses.keySet()).forEach(c -> statuses.put(c.getUserId(), GmailStatus.of(c.getStatus())));
        return statuses;
    }

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

    public void forget(long userId) {
        transactions.executeWithoutResult(tx -> repository.findByIdForUpdate(userId).ifPresent(copy -> {
            cleared(copy);
            repository.save(copy);
        }));
    }

    static void cleared(GmailConnection copy) {
        copy.setStatus(ConnectionStatus.DISCONNECTED);
        copy.setReason(null);
        copy.setLastSyncedAt(null);
        copy.setLastSyncError(null);
        copy.setDisconnectRequestedAt(null);
    }

    GmailConnection lockCopy(long userId) {
        ensureCopy(userId);
        return repository.findByIdForUpdate(userId).orElseThrow();
    }

    private void ensureCopy(long userId) {
        if (repository.existsById(userId)) return;
        try {
            ownTransaction.executeWithoutResult(tx -> repository.insertBlank(userId, Instant.now()));
        } catch (DataIntegrityViolationException e) {
        }
    }

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

    private static String fit(String text, int max) {
        if (text == null || text.length() <= max) return text;
        int end = Character.isHighSurrogate(text.charAt(max - 2)) ? max - 2 : max - 1;
        return text.substring(0, end) + "…";
    }
}
