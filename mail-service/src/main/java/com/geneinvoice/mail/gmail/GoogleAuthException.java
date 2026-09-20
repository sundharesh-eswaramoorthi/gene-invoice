package com.geneinvoice.mail.gmail;

/**
 * Google refused a connection's credentials ({@code invalid_grant}, {@code invalid_client},
 * {@code unauthorized_client}, {@code deleted_client}, {@code disabled_client}), or the stored ones
 * cannot be read. Trying again changes nothing:
 * the owner has to connect Gmail again. The message is the reason the connection needs renewing.
 */
public class GoogleAuthException extends RuntimeException {

    static final String INVALID_GRANT = "invalid_grant";
    static final String UNREADABLE_SECRETS = "The stored Gmail secrets cannot be read. Reconnect Gmail.";

    /** Google's error code, or null when the stored secrets could not be read. */
    private final String error;
    private final String description;

    public GoogleAuthException(String error, String description) {
        super(error == null ? UNREADABLE_SECRETS
                : "Google no longer accepts this Gmail connection (" + explained(error, description) + "). Reconnect Gmail.");
        this.error = error;
        this.description = description;
    }

    /** The key that sealed the secrets has changed since they were stored. */
    public static GoogleAuthException unreadableSecrets() {
        return new GoogleAuthException(null, null);
    }

    public String error() {
        return error;
    }

    public String description() {
        return description;
    }

    /** Why the connection needs renewing, for {@code status_reason}. */
    public String reconnectReason() {
        return getMessage();
    }

    /** What connecting with these credentials answers (§4.4). */
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
