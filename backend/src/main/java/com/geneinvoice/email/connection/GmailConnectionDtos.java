package com.geneinvoice.email.connection;

import java.time.Instant;

public class GmailConnectionDtos {

    public record ConnectGmailRequest(String clientId, String clientSecret, String refreshToken) {}

    public record GmailConnectionDto(
            boolean configured,
            GmailStatus status,
            String gmailAddress,
            String clientId,
            String reason,
            Instant connectedAt,
            Instant lastSyncedAt,
            String lastSyncError,
            String serviceError
    ) {}

    public record UserGmailDto(GmailStatus status, String gmailAddress, String reason, Instant updatedAt) {}

    public record DeliveryGmailDto(GmailStatus status, String gmailAddress, String reason, Instant lastSyncedAt,
                                   String lastSyncError) {}
}
