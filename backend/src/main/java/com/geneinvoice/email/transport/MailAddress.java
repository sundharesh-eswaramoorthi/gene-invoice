package com.geneinvoice.email.transport;

/** A display name and an email address, either of which may be absent. */
public record MailAddress(String name, String address) {}
