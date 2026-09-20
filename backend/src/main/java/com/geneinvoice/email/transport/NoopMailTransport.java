package com.geneinvoice.email.transport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The transport when no mail service is configured: emails are saved in the app but never sent,
 * and nobody can connect Gmail (M13).
 */
@Component
@ConditionalOnProperty(name = "app.mail.transport", havingValue = "none", matchIfMissing = true)
public class NoopMailTransport implements MailTransport, MailConnections {

    public static final String NOT_CONFIGURED = "Email delivery is not configured (mail service)";

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public List<CopyState> submit(Submission submission) {
        throw new MailSendException(NOT_CONFIGURED, false);
    }

    @Override
    public ConnectionState connect(long userId, String name, String clientId, String clientSecret,
                                   String refreshToken) {
        throw new MailConnectException(503, NOT_CONFIGURED);
    }

    @Override
    public Optional<ConnectionState> connection(long userId) {
        return Optional.empty();
    }

    @Override
    public void disconnect(long userId) {
        throw new MailConnectException(503, NOT_CONFIGURED);
    }

    @Override
    public SyncResult syncNow(long userId) {
        return new SyncResult(false, 0, 0, NOT_CONFIGURED);
    }
}
