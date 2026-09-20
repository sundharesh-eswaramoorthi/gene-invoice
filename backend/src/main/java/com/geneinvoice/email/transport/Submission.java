package com.geneinvoice.email.transport;

import java.util.List;

/**
 * One email's copies for the mail service to send, one per To recipient (M6).
 *
 * @param senderUserId the sender person's user id; their own connected Gmail sends it (M4)
 * @param senderName   the name in the From header
 * @param subject      a single line
 * @param body         plain text, possibly empty
 * @param groupRef     the email's id, so the service can tell which copies belong together
 * @param retry        a copy the service already failed or did not send is queued again
 * @param copies       at least one
 */
public record Submission(long senderUserId, String senderName, String subject, String body, String groupRef,
                         boolean retry, List<CopyRequest> copies) {}
