package com.geneinvoice.email.transport;

import java.util.List;

/**
 * Hands outbound email over for delivery. Exactly one implementation is active, chosen by
 * {@code app.mail.transport}: the mail service, or nothing.
 */
public interface MailTransport {

    /** False when no mail service is set up; emails are then saved but not sent. */
    boolean isConfigured();

    /**
     * Hands the copies over and returns where each stands, in request order. Idempotent on each
     * copy's {@code externalId}: a copy handed over before is returned as it is, never sent twice,
     * unless {@link Submission#retry()} asks for a failed or unsent one to go again. Throws
     * {@link MailSendException} when the hand-off itself failed. Must not be called when
     * {@link #isConfigured()} is false.
     */
    List<CopyState> submit(Submission submission);
}
