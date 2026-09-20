package com.geneinvoice.email.transport;

/**
 * One recipient's copy of an email.
 *
 * @param externalId the app's key for the copy, {@code gi-{emailId}-{recipientId}}; handing the same
 *                   key over again never sends it twice
 * @param name       the name in the To header
 * @param address    the only address in the To header
 */
public record CopyRequest(String externalId, String name, String address) {}
