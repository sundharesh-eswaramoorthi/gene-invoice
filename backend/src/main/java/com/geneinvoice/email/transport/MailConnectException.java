package com.geneinvoice.email.transport;

/**
 * A connection call failed, with the HTTP status the caller should see: 400 when Google refused
 * the credentials or they are invalid, 502 when Google could not be reached, 503 when the mail
 * service could not be reached or is not configured. The message is the one to show.
 */
public class MailConnectException extends RuntimeException {

    private final int httpStatus;

    public MailConnectException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
