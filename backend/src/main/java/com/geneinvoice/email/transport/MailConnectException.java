package com.geneinvoice.email.transport;

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
