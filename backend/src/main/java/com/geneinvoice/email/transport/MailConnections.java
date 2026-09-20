package com.geneinvoice.email.transport;

import java.util.Optional;

/**
 * Each internal user's own Gmail connection, kept by the mail service (M2). The user's secrets pass
 * through on connect and are never read back. Implemented by whatever implements {@link MailTransport}.
 */
public interface MailConnections {

    /**
     * Connects the user's Gmail, or replaces their connection. The service checks the credentials
     * with Google at once, so a refusal comes back here as a 400 with Google's reason.
     */
    ConnectionState connect(long userId, String name, String clientId, String clientSecret, String refreshToken);

    /** The user's connection as the service knows it; empty when they never connected. */
    Optional<ConnectionState> connection(long userId);

    /** Revokes and forgets the user's credentials. Nothing to disconnect is not an error. */
    void disconnect(long userId);

    /** Reads the user's mailbox now. Never throws: a failure is the result's {@code error}. */
    SyncResult syncNow(long userId);
}
