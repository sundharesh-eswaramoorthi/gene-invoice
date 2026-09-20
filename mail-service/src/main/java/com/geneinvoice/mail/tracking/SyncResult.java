package com.geneinvoice.mail.tracking;

/**
 * How a mailbox read went: {@code fetched} counts messages downloaded, {@code imported} the replies
 * and bounces among them. {@code enabled} is false when the mailbox cannot be read at all.
 */
public record SyncResult(boolean enabled, int fetched, int imported, String error) {}
