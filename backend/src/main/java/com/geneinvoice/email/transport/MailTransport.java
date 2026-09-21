package com.geneinvoice.email.transport;

import java.util.List;

public interface MailTransport {

    boolean isConfigured();

    List<CopyState> submit(Submission submission);
}
