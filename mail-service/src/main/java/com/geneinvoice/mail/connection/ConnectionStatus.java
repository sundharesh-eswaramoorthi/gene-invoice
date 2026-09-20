package com.geneinvoice.mail.connection;

public enum ConnectionStatus {
    /** Checked with Google when made; sends and reads the mailbox. */
    CONNECTED,
    /** Google stopped accepting the credentials, or they cannot be read: the owner connects again. */
    NEEDS_RECONNECT,
    /** The owner took the connection away; the secrets are gone. */
    DISCONNECTED
}
