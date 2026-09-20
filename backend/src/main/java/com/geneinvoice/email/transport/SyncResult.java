package com.geneinvoice.email.transport;

/**
 * What reading one mailbox now came to.
 *
 * @param enabled  false when there is nothing to read: delivery not set up, or Gmail not connected
 * @param fetched  messages downloaded
 * @param imported replies and bounces handled
 * @param error    why the run did not finish, or null
 */
public record SyncResult(boolean enabled, int fetched, int imported, String error) {}
