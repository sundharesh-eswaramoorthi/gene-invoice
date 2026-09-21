package com.geneinvoice.email.transport;

import com.geneinvoice.email.RecipientDeliveryStatus;

import java.time.Instant;

public record CopyState(String externalId, String groupRef, long seq, RecipientDeliveryStatus status,
                        String error, int attempts, String fromAddress,
                        Instant sentAt, Instant deliveredAt, boolean deliveredConfirmed, Instant readAt,
                        Instant bouncedAt, String providerMessageId, String providerThreadId,
                        String rfcMessageId) {}
