package com.geneinvoice.mail.gmail;

public class GoogleAuthException extends RuntimeException {

    static final String INVALID_GRANT = "invalid_grant";
    static final String UNREADABLE_SECRETS = "The stored Gmail secrets cannot be read. Reconnect Gmail.";

    private final String error;
    private final String description;

    public GoogleAuthException(String error, String description) {
        super(error == null ? UNREADABLE_SECRETS
                : "Google no longer accepts this Gmail connection (" + explained(error, description) + "). Reconnect Gmail.");
        this.error = error;
        this.description = description;
    }

    public static GoogleAuthException unreadableSecrets() {
        return new GoogleAuthException(null, null);
    }

    public String error() {
        return error;
    }

    public String description() {
        return description;
    }

    public String reconnectReason() {
        return getMessage();
    }

    public String connectMessage() {
        if (INVALID_GRANT.equals(error)) {
            return "Google did not accept the refresh token (" + explained(error, description) + "). Make sure it was"
                    + " made with this client ID and secret, and has not expired or been revoked.";
        }
        return "Google did not accept the client ID and secret (" + explained(error, description) + ").";
    }

    private static String explained(String error, String description) {
        return description == null || description.isBlank() ? error : error + ": " + description;
    }
}
