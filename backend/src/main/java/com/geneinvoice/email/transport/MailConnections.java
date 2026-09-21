package com.geneinvoice.email.transport;

import java.util.Optional;

/**
 * Each internal user's own Gmail connection, kept by the mail service (M2). The user's secrets pass
 * through on connect and are never read back. Implemented by whatever implements {@link MailTransport}.
 */
public interface MailConnections {

    ConnectionState connect(long userId, String name, String clientId, String clientSecret, String refreshToken);

    Optional<ConnectionState> connection(long userId);

    void disconnect(long userId);

    SyncResult syncNow(long userId);
}
