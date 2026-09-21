package com.geneinvoice.mail.gmail;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class GmailApiExceptionTest {

    @Test
    void aNetworkFailureWithoutAMessageIsExplainedInWordsNotAClassName() {
        GmailApiException closed = GmailApiException.of("Gmail",
                new ResourceAccessException("I/O error", new ClosedChannelException()));
        assertThat(closed.getMessage()).isEqualTo("Gmail did not answer: the connection was closed");

        GmailApiException timedOut = GmailApiException.of("Gmail",
                new ResourceAccessException("I/O error", new IOException(new TimeoutException())));
        assertThat(timedOut.getMessage()).isEqualTo("Gmail did not answer: the request timed out");

        GmailApiException unreachable = GmailApiException.of("Gmail",
                new ResourceAccessException("I/O error", new ConnectException()));
        assertThat(unreachable.getMessage()).isEqualTo("Could not reach Gmail: a network error");
        assertThat(unreachable.isTransientFailure()).isTrue();
        assertThat(unreachable.isOutcomeUnknown()).isFalse();

        GmailApiException refused = GmailApiException.of("Gmail",
                new ResourceAccessException("I/O error", new ConnectException("Connection refused")));
        assertThat(refused.getMessage()).isEqualTo("Could not reach Gmail: Connection refused");
    }
}
