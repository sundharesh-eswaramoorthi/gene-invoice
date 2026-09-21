package com.geneinvoice.email;

import com.geneinvoice.email.connection.GmailDisconnects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailSweepScheduler {

    private final EmailDispatcher dispatcher;
    private final GmailDisconnects gmailDisconnects;

    @Scheduled(fixedDelayString = "${app.mail.dispatch.sweep-interval-ms:60000}",
            initialDelayString = "${app.mail.dispatch.sweep-interval-ms:60000}")
    public void sweep() {
        try {
            dispatcher.sweep();
        } catch (RuntimeException e) {
            log.warn("Email sweep failed: {}", e.getMessage());
        }
        try {
            gmailDisconnects.sweep();
        } catch (RuntimeException e) {
            log.warn("Gmail disconnection sweep failed: {}", e.getMessage());
        }
    }
}
