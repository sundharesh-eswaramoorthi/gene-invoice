package com.geneinvoice.email.transport;

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
