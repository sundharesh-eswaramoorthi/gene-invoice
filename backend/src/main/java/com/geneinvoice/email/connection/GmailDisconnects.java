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
        background.shutdownNow();
    }

    public static boolean mayConnect(User user) {
        return user != null && user.isActive() && user.getCustomerId() == null && user.getRole() != null
                && user.getRole().getPrivileges().stream().anyMatch(p -> Privileges.EMAIL_SEND.equals(p.getName()));
    }

    public void request(Collection<Long> userIds) {
        mark(userIds);
        process(userIds);
    }

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

    public void sweep() {
        if (!transport.isConfigured()) return;
        List<Long> due = transactions.execute(tx -> repository.findDisconnectsDue(PageRequest.of(0, SWEEP_BATCH)));
        if (due != null) disconnectAll(due);
    }

    private void disconnectAll(List<Long> ids) {
        for (Long id : ids) {
            try {
                if (!disconnect(id)) return;
            } catch (RuntimeException e) {
                log.warn("Removing the Gmail connection of user {} failed; the sweeper tries again", id, e);
            }
        }
    }

    boolean disconnect(long userId) {
        if (!transport.isConfigured()) return false;
        Boolean due = transactions.execute(tx -> {
            GmailConnection copy = repository.findByIdForUpdate(userId).orElse(null);
            if (copy == null || copy.getDisconnectRequestedAt() == null) return false;
            if (mayConnect(userRepository.findById(userId).orElse(null))) {
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
