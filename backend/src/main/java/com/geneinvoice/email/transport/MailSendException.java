package com.geneinvoice.email.transport;

/**
 * Handing email over failed. A transient failure (the service unreachable or unavailable) may
 * succeed on a later attempt, and repeating a hand-off never sends a copy twice; any other failure
 * will not succeed.
 */
public class MailSendException extends RuntimeException {

    private final boolean transientFailure;

    public MailSendException(String message, boolean transientFailure) {
        this(message, transientFailure, null);
    }

    public MailSendException(String message, boolean transientFailure, Throwable cause) {
        super(message, cause);
        this.transientFailure = transientFailure;
    }

    public boolean isTransientFailure() {
        return transientFailure;
    }
}
