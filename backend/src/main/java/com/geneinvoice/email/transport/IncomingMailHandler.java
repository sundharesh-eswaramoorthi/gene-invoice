package com.geneinvoice.email.transport;

import java.util.Optional;

/** Saves received mail against the record it belongs to. Implemented by the email core. */
public interface IncomingMailHandler {

    /**
     * Saves the message when it belongs to a record and returns the saved email's id; returns empty
     * when the message was ignored. Calling it again for the same provider message id changes nothing
     * and returns the id saved the first time.
     */
    Optional<Long> handle(IncomingMail mail, InboundHint hint);
}
