package com.geneinvoice.email.connection;

import com.geneinvoice.email.transport.MailConnectException;
import com.geneinvoice.email.transport.MailConnections;
import com.geneinvoice.email.transport.MailTransport;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Removes the Gmail connection of someone who may no longer send from it (mail-service.md §5.6):
 * a user deleted, deactivated, or left without EMAIL_SEND by a change of role or of the role's
 * privileges. Their connection lives at the mail service, which keeps sending and reading with it
 * until told otherwise — a retry of one of their emails would still go out from their Gmail, and
 * their mailbox would still be read every minute.
 * <p>
 * The removal is marked on the app's copy first, then asked of the service, which revokes the token
 * at Google and forgets the secrets. When the service cannot be reached, the mark stays and the
 * email sweeper asks again. A user who may send again by then (reactivated, say) keeps their
 * connection.
 */
@Component
@Slf4j
public class GmailDisconnects {

    private static final int SWEEP_BATCH = 50;

    private final GmailConnectionRepository repository;
    private final GmailConnectionService copies;
    private final MailTransport transport;
    private final MailConnections connections;
    private final UserRepository userRepository;
    private final TransactionTemplate transactions;
    private final boolean async;
    /** One thread, so a bulk deactivation reaches the service one user at a time. */
    private final ExecutorService background = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "gmail-disconnect");
        t.setDaemon(true);
        return t;
    });

    public GmailDisconnects(GmailConnectionRepository repository, GmailConnectionService copies,
                            MailTransport transport, MailConnections connections, UserRepository userRepository,
                            TransactionTemplate transactions,
                            @Value("${app.mail.dispatch.async:true}") boolean async) {
        this.repository = repository;
        this.copies = copies;
        this.transport = transport;
        this.connections = connections;
        this.userRepository = userRepository;
        this.transactions = transactions;
        this.async = async;
    }

    @PreDestroy
    void shutdown() {
        // What is still marked stays marked, and the sweeper asks after a restart.
        background.shutdownNow();
    }

    /** Whether someone may have a Gmail connection: an active internal user who sends email. */
    public static boolean mayConnect(User user) {
        return user != null && user.isActive() && user.getCustomerId() == null && user.getRole() != null
                && user.getRole().getPrivileges().stream().anyMatch(p -> Privileges.EMAIL_SEND.equals(p.getName()));
    }

    /** {@link #mark} then {@link #process}, for a change made outside a transaction. */
    public void request(Collection<Long> userIds) {
        mark(userIds);
        process(userIds);
    }

    /**
     * Marks the users' connections for removal, in the caller's transaction when there is one (so the
     * mark stands or falls with the change that called for it). A user the app has no copy for gets
     * one, as the app's copy can lag the service's — except without the mail service, when the app
     * never heard of a connection there and could not ask for one to go.
     */
    public void mark(Collection<Long> userIds) {
        List<Long> ids = distinct(userIds);
        if (ids.isEmpty()) return;
        Instant now = Instant.now();
        boolean configured = transport.isConfigured();
        transactions.executeWithoutResult(tx -> ids.forEach(id -> {
            if (!configured && !repository.existsById(id)) return;
            GmailConnection copy = copies.lockCopy(id);
            if (copy.getDisconnectRequestedAt() == null) {
                copy.setDisconnectRequestedAt(now);
                repository.save(copy);
            }
        }));
    }

    /**
     * Asks the service to remove what is marked for these users, on a background thread — or right
     * here when async dispatch is off (tests). The service is never called with a transaction open.
     */
    public void process(Collection<Long> userIds) {
        List<Long> ids = distinct(userIds);
        if (ids.isEmpty()) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Gmail connections must be removed outside a transaction");
        }
        if (!async) {
            disconnectAll(ids);
            return;
        }
        try {
            background.execute(() -> disconnectAll(ids));
        } catch (RejectedExecutionException e) {
            log.warn("Shutting down; {} Gmail disconnection(s) stay marked for the sweeper", ids.size());
        }
    }

    /** The sweeper's turn: whatever is still marked, longest waiting first. */
    public void sweep() {
        if (!transport.isConfigured()) return;
        List<Long> due = transactions.execute(tx -> repository.findDisconnectsDue(PageRequest.of(0, SWEEP_BATCH)));
        if (due != null) disconnectAll(due);
    }

    private void disconnectAll(List<Long> ids) {
        for (Long id : ids) {
            try {
                // The service cannot be reached: the rest wait for the sweeper too.
                if (!disconnect(id)) return;
            } catch (RuntimeException e) {
                log.warn("Removing the Gmail connection of user {} failed; the sweeper tries again", id, e);
            }
        }
    }

    /**
     * Removes one user's connection if it is still marked and they still may not have one. Returns
     * false only when the service could not be asked.
     */
    boolean disconnect(long userId) {
        if (!transport.isConfigured()) return false;
        Boolean due = transactions.execute(tx -> {
            GmailConnection copy = repository.findByIdForUpdate(userId).orElse(null);
            if (copy == null || copy.getDisconnectRequestedAt() == null) return false;
            if (mayConnect(userRepository.findById(userId).orElse(null))) {
                // Back again, reactivated or given EMAIL_SEND: the connection is theirs to keep.
                copy.setDisconnectRequestedAt(null);
                repository.save(copy);
                return false;
            }
            return true;
        });
        if (!Boolean.TRUE.equals(due)) return true;
        try {
            connections.disconnect(userId);
        } catch (MailConnectException e) {
            log.warn("Could not remove the Gmail connection of user {}, who may no longer send email: {}."
                    + " The sweeper tries again.", userId, e.getMessage());
            return false;
        }
        transactions.executeWithoutResult(tx -> repository.findByIdForUpdate(userId).ifPresent(copy -> {
            if (!userRepository.existsById(userId)) {
                // Nobody's copy any more.
                repository.delete(copy);
                return;
            }
            GmailConnectionService.cleared(copy);
            repository.save(copy);
        }));
        log.info("Removed the Gmail connection of user {}, who may no longer send email", userId);
        return true;
    }

    private static List<Long> distinct(Collection<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
    }
}
