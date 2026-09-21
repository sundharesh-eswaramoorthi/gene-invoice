package com.geneinvoice.email.transport;

import java.time.Instant;
import java.util.List;

public record ConnectionState(String ownerRef, String ownerName, ConnectionStatus status, String gmailAddress,
                              String clientId, List<String> scopes, String statusReason, Instant connectedAt,
                              Instant lastSyncedAt, String lastSyncError) {}
