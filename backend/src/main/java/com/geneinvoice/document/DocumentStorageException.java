package com.geneinvoice.document;

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
