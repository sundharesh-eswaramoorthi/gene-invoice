package com.geneinvoice.email.transport;

/** A Gmail connection's standing at the mail service. */
public enum ConnectionStatus {
    CONNECTED,
    /** Google stopped accepting its refresh token, or the stored secrets cannot be read. */
    NEEDS_RECONNECT,
    /** The user disconnected; the secrets are gone. */
    DISCONNECTED
}
