package com.geneinvoice.document;

/**
 * Storage could not do what was asked, with the HTTP status the caller should see: 503 when there
 * is no storage configured or it cannot be reached, 500 when a write or a read failed. The message
 * is the one to show — it names no path and no internal detail.
 */
public class DocumentStorageException extends RuntimeException {

    private final int httpStatus;

    public DocumentStorageException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public DocumentStorageException(int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
