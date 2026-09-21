package com.geneinvoice.email.transport;

import java.util.Optional;

public interface IncomingMailHandler {

    Optional<Long> handle(IncomingMail mail, InboundHint hint);
}
