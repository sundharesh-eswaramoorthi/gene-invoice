package com.geneinvoice.mail.gmail;

/** A name and an address as a mail header carries them; either may be null. */
public record MailAddress(String name, String address) {}
