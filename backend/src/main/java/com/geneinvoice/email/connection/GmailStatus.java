package com.geneinvoice.email.connection;

import com.geneinvoice.email.transport.ConnectionStatus;

public enum GmailStatus {
    CONNECTED,
    NEEDS_RECONNECT,
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
