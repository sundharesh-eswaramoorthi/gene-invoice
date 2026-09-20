package com.geneinvoice.email.connection;

import com.geneinvoice.email.transport.ConnectionStatus;

/** Whether a person can send from their own Gmail, as the app shows it. */
public enum GmailStatus {
    CONNECTED,
    NEEDS_RECONNECT,
    /** Never connected, or disconnected. Always so for customer logins, who do not connect Gmail. */
    NOT_CONNECTED;

    public static GmailStatus of(ConnectionStatus status) {
        if (status == null) return NOT_CONNECTED;
        return switch (status) {
            case CONNECTED -> CONNECTED;
            case NEEDS_RECONNECT -> NEEDS_RECONNECT;
            case DISCONNECTED -> NOT_CONNECTED;
        };
    }
}
