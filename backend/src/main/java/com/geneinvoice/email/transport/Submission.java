package com.geneinvoice.email.transport;

import java.util.List;

public record Submission(long senderUserId, String senderName, String subject, String body, String groupRef,
                         boolean retry, List<CopyRequest> copies) {}
