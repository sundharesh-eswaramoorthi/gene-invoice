package com.geneinvoice.email.transport;

public record InboundHint(long mailboxOwnerUserId, String mailboxAddress, String repliedToExternalId) {}
