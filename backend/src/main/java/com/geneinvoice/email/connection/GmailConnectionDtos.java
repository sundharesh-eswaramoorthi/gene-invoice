package com.geneinvoice.email.connection;

import java.time.Instant;

public class GmailConnectionDtos {

    /** The three values the user made with their own Google OAuth client. Checked in the service. */
    public record ConnectGmailRequest(String clientId, String clientSecret, String refreshToken) {}

    /**
     * The caller's own connection. {@code configured} is false without the mail service; {@code
     * serviceError} says why the answer came from the app's copy rather than the service.
     */
    public record GmailConnectionDto(
            boolean configured,
            GmailStatus status,
            String gmailAddress,
            /** Not secret; pre-fills the form for a reconnect. Null when answered from the app's copy. */
            String clientId,
            String reason,
            Instant connectedAt,
            Instant lastSyncedAt,
            String lastSyncError,
            String serviceError
    ) {}

    /** Someone's connection as the app last heard of it. */
    public record UserGmailDto(GmailStatus status, String gmailAddress, String reason, Instant updatedAt) {}

    /** The caller's connection for {@code GET /api/emails/delivery}. */
    public record DeliveryGmailDto(GmailStatus status, String gmailAddress, String reason, Instant lastSyncedAt,
                                   String lastSyncError) {}
}
