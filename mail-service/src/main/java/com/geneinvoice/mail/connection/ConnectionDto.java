package com.geneinvoice.mail.connection;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** A connection as the backend sees it (§4.3): never the client secret or the refresh token. */
public record ConnectionDto(String ownerRef, String ownerName, ConnectionStatus status, String gmailAddress,
                            String clientId, List<String> scopes, String statusReason, Instant connectedAt,
                            Instant lastSyncedAt, String lastSyncError) {

    public static ConnectionDto of(MailConnection c) {
        List<String> scopes = c.getScopes() == null || c.getScopes().isBlank() ? List.of()
                : Arrays.stream(c.getScopes().trim().split("\\s+")).toList();
        return new ConnectionDto(c.getOwnerRef(), c.getOwnerName(), c.getStatus(), c.getGmailAddress(),
                c.getClientId(), scopes, c.getStatusReason(), c.getConnectedAt(), c.getLastSyncedAt(),
                c.getLastSyncError());
    }
}
