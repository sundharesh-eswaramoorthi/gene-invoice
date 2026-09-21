package com.geneinvoice.mail.tracking;

import com.geneinvoice.mail.config.MailProperties;
import com.geneinvoice.mail.connection.ConnectionStatus;
import com.geneinvoice.mail.connection.MailConnectionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Component
@Slf4j
public class SyncScheduler {

    private final MailProperties properties;
    private final MailConnectionRepository connections;
    private final MailboxSync sync;
    private final TransactionTemplate transactions;

    public SyncScheduler(MailProperties properties, MailConnectionRepository connections, MailboxSync sync,
                         TransactionTemplate transactions) {
        this.properties = properties;
        this.connections = connections;
        this.sync = sync;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${mail.sync.interval-ms:60000}", initialDelayString = "${mail.sync.interval-ms:60000}")
    public void run() {
        if (!properties.getSync().isEnabled()) return;
        List<Long> ids;
        try {
            ids = transactions.execute(status -> connections.findIdsByStatus(ConnectionStatus.CONNECTED));
        } catch (RuntimeException e) {
            log.warn("Could not list the Gmail connections to read: {}", e.getMessage());
            return;
        }
        for (Long id : ids == null ? List.<Long>of() : ids) {
            try {
                SyncResult result = sync.sync(id);
                if (MailboxSync.ALREADY_RUNNING.equals(result.error())) {
                    log.debug("Gmail sync of connection {} skipped: one asked for by hand is still running", id);
                } else if (result.error() != null) {
                    log.warn("Gmail sync of connection {} failed: {}", id, result.error());
                } else if (result.imported() > 0) {
                    log.info("Gmail sync of connection {}: {} bounce(s) and repl(ies)", id, result.imported());
                }
            } catch (RuntimeException e) {
                log.warn("Gmail sync of connection {} failed", id, e);
            }
        }
    }
}
