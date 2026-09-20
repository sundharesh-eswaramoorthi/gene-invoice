package com.geneinvoice.email.transport;

import java.time.Instant;
import java.util.List;

/**
 * A user's Gmail connection as the mail service reports it: the answer to connect and look-up
 * calls, and the {@code data} of a {@code connection.status} event. It never carries a secret.
 *
 * @param ownerRef     the backend's user id, as a string
 * @param statusReason why it needs renewing, or null
 * @param scopes       as Google granted them
 */
public record ConnectionState(String ownerRef, String ownerName, ConnectionStatus status, String gmailAddress,
                              String clientId, List<String> scopes, String statusReason, Instant connectedAt,
                              Instant lastSyncedAt, String lastSyncError) {}
