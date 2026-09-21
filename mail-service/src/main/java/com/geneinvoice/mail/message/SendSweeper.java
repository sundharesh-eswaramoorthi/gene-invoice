package com.geneinvoice.mail.message;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
@Slf4j
public class SendSweeper {

    static final String INTERRUPTED = "Sending was interrupted; retry to send again";
    static final Duration STALE_SENDING = Duration.ofMinutes(10);
    static final Duration REPUBLISH_AFTER = Duration.ofMinutes(2);
    static final int BATCH = 500;

    private final MailMessageRepository messages;
    private final MessageService messageService;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SendSweeper(MailMessageRepository messages, MessageService messageService, TransactionTemplate transactions,
                       Clock clock) {
        this.messages = messages;
        this.messageService = messageService;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${mail.send.sweep-interval-ms:30000}",
            initialDelayString = "${mail.send.sweep-interval-ms:30000}")
    public void run() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("The send sweep failed", e);
        }
    }

    public void sweep() {
        Instant now = clock.instant();
        List<Long> stale = transactions.execute(status ->
                messages.findStaleSending(now.minus(STALE_SENDING), PageRequest.of(0, BATCH)));
        int interrupted = 0;
        for (Long id : stale == null ? List.<Long>of() : stale) {
            if (Boolean.TRUE.equals(transactions.execute(status -> interrupted(id, now)))) interrupted++;
        }
        if (interrupted > 0) log.warn("Marked {} interrupted send(s) as failed", interrupted);

        List<Long> lost = transactions.execute(status ->
                messages.findUnpublished(now, now.minus(REPUBLISH_AFTER), PageRequest.of(0, BATCH)));
        if (lost != null && !lost.isEmpty()) {
            log.info("Publishing {} queued copy(ies) again", lost.size());
            messageService.publish(lost);
        }
    }

    private boolean interrupted(Long id, Instant now) {
        MailMessage m = messageService.locked(id);
        if (m == null || m.getStatus() != MessageStatus.SENDING || !m.getUpdatedAt().isBefore(now.minus(STALE_SENDING))) {
            return false;
        }
        m.setStatus(MessageStatus.FAILED);
        m.setError(INTERRUPTED);
        m.setDeliveryUncertain(true);
        m.setNextAttemptAt(null);
        messageService.changed(m);
        return true;
    }
}
