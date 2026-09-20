package com.geneinvoice.email.transport;

import com.geneinvoice.email.RecipientDeliveryStatus;

import java.time.Instant;

/**
 * Where one copy stands at the mail service: the answer to a hand-off, and the {@code data} of a
 * {@code message.status} event.
 *
 * @param seq                goes up by one on every change, so an older report never overwrites a newer one
 * @param deliveredConfirmed true when the copy was seen in the recipient's own Gmail; false when
 *                           {@code DELIVERED} only means no bounce came back in time
 * @param readAt             read in the recipient's Gmail (not the app's Inbox)
 * @param providerMessageId  the sender-side Gmail message id
 * @param providerThreadId   the sender-side Gmail thread id, which replies share
 */
public record CopyState(String externalId, String groupRef, long seq, RecipientDeliveryStatus status,
                        String error, int attempts, String fromAddress,
                        Instant sentAt, Instant deliveredAt, boolean deliveredConfirmed, Instant readAt,
                        Instant bouncedAt, String providerMessageId, String providerThreadId,
                        String rfcMessageId) {}
