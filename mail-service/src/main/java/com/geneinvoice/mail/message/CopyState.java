package com.geneinvoice.mail.message;

import java.time.Instant;

/** One copy as the backend sees it (§4.3): the answer to a submit, and the data of a {@code message.status} event. */
public record CopyState(String externalId, String groupRef, long seq, MessageStatus status, String error, int attempts,
                        String fromAddress, Instant sentAt, Instant deliveredAt, boolean deliveredConfirmed,
                        Instant readAt, Instant bouncedAt, String providerMessageId, String providerThreadId,
                        String rfcMessageId) {

    public static CopyState of(MailMessage m) {
        return new CopyState(m.getExternalId(), m.getGroupRef(), m.getSeq(), m.getStatus(), m.getError(),
                m.getAttempts(), m.getFromAddress(), m.getSentAt(), m.getDeliveredAt(), m.isDeliveredConfirmed(),
                m.getReadAt(), m.getBouncedAt(), m.getProviderMessageId(), m.getProviderThreadId(), m.getRfcMessageId());
    }
}
