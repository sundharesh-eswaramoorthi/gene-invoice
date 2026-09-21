package com.geneinvoice.mail.message;

import com.geneinvoice.mail.FakeGoogle;
import com.geneinvoice.mail.FakeGoogle.Exchange;
import com.geneinvoice.mail.FakeGoogle.Reply;
import com.geneinvoice.mail.IntegrationTestBase;
import com.geneinvoice.mail.RecordingSendQueue;
import com.geneinvoice.mail.connection.ConnectionStatus;
import com.geneinvoice.mail.connection.MailConnection;
import com.geneinvoice.mail.gmail.GoogleAuthException;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A worker sending copies (§4.5), asserted on what the stand-in for Gmail receives and on each copy's row. */
class SendWorkerTest extends IntegrationTestBase {

    private static final String SEND = FakeGoogle.api("/messages/send");
    private static final String SEARCH = FakeGoogle.api("/messages");
    private static final String SUBJECT = "Rechnung INV-0042 über ₹1,20,000.00";
    private static final String BODY = "Namaste Ravi,\n\nthe invoice is due on 2026-09-30.\nThanks";

    @Autowired SendSweeper sweeper;
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    void janeIsConnected() {
        connect("7", "Jane Doe", "jane@gmail.com");
        AtomicInteger sent = new AtomicInteger();
        google.on("POST", SEND, exchange -> {
            int n = sent.incrementAndGet();
            return new Reply(200, "{\"id\":\"gm-" + n + "\",\"threadId\":\"th-" + n + "\",\"labelIds\":[\"SENT\"]}");
        });
    }

    private void submitToBob() {
        submit(new SubmitRequest(new SubmitRequest.Sender("7", "Jane Doe"), SUBJECT, BODY, "91", false,
                List.of(copy("gi-91-501", "Bob Smith", "bob@acme.com")), List.of()));
    }

    /** The message Gmail was asked to send, decoded the way Gmail would. */
    private static MimeMessage sentMessage(Exchange request) throws Exception {
        String raw = request.json().get("raw").asText();
        assertThat(raw).doesNotContain("+", "/", "=");
        return new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(Base64.getUrlDecoder().decode(raw)));
    }

    @Test
    void eachRecipientGetsTheirOwnCopyFromTheSendersGmail() throws Exception {
        submit(new SubmitRequest(new SubmitRequest.Sender("7", "Jürgen Müller"), SUBJECT, BODY, "91", false,
                List.of(copy("gi-91-501", "Bob Smith", "bob@acme.com"), copy("gi-91-502", "Zoë O'Brien", "zoe@acme.com")), List.of()));

        work();

        List<Exchange> sends = google.requests("POST", SEND);
        assertThat(sends).hasSize(2);
        assertThat(sends).allSatisfy(send -> {
            assertThat(send.mailbox()).isEqualTo("jane@gmail.com");
            assertThat(send.header("Content-Type")).startsWith("application/json");
        });
        MimeMessage toBob = sentMessage(sends.get(0));
        InternetAddress from = (InternetAddress) toBob.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("jane@gmail.com");
        assertThat(from.getPersonal()).isEqualTo("Jürgen Müller");
        assertThat(toBob.getHeader("From", null)).startsWith("=?UTF-8?");
        assertThat(toBob.getRecipients(Message.RecipientType.TO)).extracting(a -> ((InternetAddress) a).getAddress())
                .containsExactly("bob@acme.com");
        assertThat(toBob.getRecipients(Message.RecipientType.CC)).isNull();
        assertThat(toBob.getRecipients(Message.RecipientType.BCC)).isNull();
        assertThat(toBob.getSubject()).isEqualTo(SUBJECT);
        assertThat(toBob.getHeader("Subject", null)).startsWith("=?UTF-8?");
        assertThat(toBob.getMessageID()).isEqualTo(message("gi-91-501").getRfcMessageId());
        assertThat(toBob.getSentDate().toInstant()).isEqualTo(T0);
        assertThat(toBob.getHeader("MIME-Version", null)).isEqualTo("1.0");
        assertThat(toBob.getContentType()).isEqualToIgnoringCase("text/plain; charset=UTF-8");
        assertThat(toBob.getContent()).isEqualTo(BODY.replace("\n", "\r\n"));
        InternetAddress toZoe = (InternetAddress) sentMessage(sends.get(1)).getRecipients(Message.RecipientType.TO)[0];
        assertThat(toZoe.getAddress()).isEqualTo("zoe@acme.com");
        assertThat(toZoe.getPersonal()).isEqualTo("Zoë O'Brien");

        MailMessage bob = message("gi-91-501");
        assertThat(bob.getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(bob.getSentAt()).isEqualTo(T0);
        assertThat(bob.getFromAddress()).isEqualTo("jane@gmail.com");
        assertThat(bob.getProviderMessageId()).isEqualTo("gm-1");
        assertThat(bob.getProviderThreadId()).isEqualTo("th-1");
        assertThat(bob.getAttempts()).isEqualTo(1);
        assertThat(bob.getError()).isNull();
        assertThat(statusTrail("gi-91-501")).containsExactly("QUEUED@1", "SENDING@2", "SENT@3");
        assertThat(message("gi-91-502").getProviderMessageId()).isEqualTo("gm-2");
    }

    @Test
    void whatGmailSaysItSentIsRecordedAndALookupFailureKeepsWhatTheServiceWrote() {
        google.onSequence("GET", FakeGoogle.api("/messages/gm-1"),
                new Reply(200, "{\"payload\":{\"headers\":[{\"name\":\"Message-Id\",\"value\":\"<CAB12@mail.gmail.com>\"},"
                        + "{\"name\":\"From\",\"value\":\"Jane Doe <Jane.Doe@gmail.com>\"}]}}"),
                new Reply(500, FakeGoogle.gmailError(500, "Backend Error")));
        submitToBob();

        work();

        MailMessage bob = message("gi-91-501");
        assertThat(bob.getRfcMessageId()).isEqualTo("<CAB12@mail.gmail.com>");
        assertThat(bob.getFromAddress()).isEqualTo("Jane.Doe@gmail.com");
        Exchange lookup = google.requests("GET", FakeGoogle.api("/messages/gm-1")).get(0);
        assertThat(lookup.param("format")).isEqualTo("metadata");
        assertThat(lookup.params("metadataHeaders")).containsExactly("Message-ID", "From");

        submit(submission("7", "Jane Doe", "92", false, copy("gi-92-1", "Bob", "bob@acme.com")));
        String ours = message("gi-92-1").getRfcMessageId();
        google.on("GET", FakeGoogle.api("/messages/gm-2"), 500, FakeGoogle.gmailError(500, "Backend Error"));
        work();
        assertThat(message("gi-92-1").getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(message("gi-92-1").getRfcMessageId()).isEqualTo(ours);
        assertThat(message("gi-92-1").getFromAddress()).isEqualTo("jane@gmail.com");
    }

    @Test
    void aQueueMessageDeliveredTwiceSendsOnce() {
        submitToBob();
        long id = message("gi-91-501").getId();

        worker.process(id);
        worker.process(id);

        assertThat(google.requests("POST", SEND)).hasSize(1);
        assertThat(message("gi-91-501").getAttempts()).isEqualTo(1);
    }

    @Test
    void aPassingFailureIsTriedAgainAfterAMinuteThenFiveThenGivenUp() {
        google.on("POST", SEND, 503, FakeGoogle.gmailError(503, "Backend Error"));
        submitToBob();
        long id = message("gi-91-501").getId();

        work();
        MailMessage first = message("gi-91-501");
        assertThat(first.getStatus()).isEqualTo(MessageStatus.QUEUED);
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getError()).isEqualTo("Gmail is unavailable (503): Backend Error");
        assertThat(first.getNextAttemptAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));
        assertThat(first.isDeliveryUncertain()).isFalse();
        assertThat(queue.retries()).containsExactly(new RecordingSendQueue.Retry(id, Duration.ofMinutes(1)));

        // Early: the claim waits for the retry's time.
        worker.process(id);
        assertThat(google.requests("POST", SEND)).hasSize(1);

        clock.advance(Duration.ofMinutes(1));
        worker.process(id);
        MailMessage second = message("gi-91-501");
        assertThat(second.getStatus()).isEqualTo(MessageStatus.QUEUED);
        assertThat(second.getAttempts()).isEqualTo(2);
        assertThat(second.getNextAttemptAt()).isEqualTo(T0.plus(Duration.ofMinutes(6)));
        assertThat(queue.retries()).last().isEqualTo(new RecordingSendQueue.Retry(id, Duration.ofMinutes(5)));

        clock.advance(Duration.ofMinutes(5));
        worker.process(id);
        MailMessage last = message("gi-91-501");
        assertThat(last.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(last.getAttempts()).isEqualTo(3);
        assertThat(last.getNextAttemptAt()).isNull();
        assertThat(queue.retries()).hasSize(2);
        assertThat(google.requests("POST", SEND)).hasSize(3);
        assertThat(statusTrail("gi-91-501")).containsExactly("QUEUED@1", "SENDING@2", "QUEUED@3", "SENDING@4",
                "QUEUED@5", "SENDING@6", "FAILED@7");
    }

    @Test
    void aRefusalFailsAtOnceAndGmailsSendingLimitDoesNot() {
        google.onSequence("POST", SEND,
                new Reply(403, "{\"error\":{\"code\":403,\"message\":\"User-rate limit exceeded\","
                        + "\"errors\":[{\"reason\":\"userRateLimitExceeded\",\"domain\":\"usageLimits\"}]}}"),
                new Reply(400, FakeGoogle.gmailError(400, "Invalid To header")));
        submitToBob();

        work();
        assertThat(message("gi-91-501").getStatus()).isEqualTo(MessageStatus.QUEUED);
        assertThat(message("gi-91-501").getError()).isEqualTo("Gmail is rate limiting requests (403): User-rate limit exceeded");

        clock.advance(Duration.ofMinutes(1));
        worker.process(message("gi-91-501").getId());
        MailMessage failed = message("gi-91-501");
        assertThat(failed.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(failed.getError()).isEqualTo("Gmail refused the request (400): Invalid To header");
        assertThat(failed.isDeliveryUncertain()).isFalse();
        assertThat(queue.retries()).hasSize(1);
    }

    @Test
    void aSendThatGetsNoAnswerIsLookedForInSentMailBeforeTheNextAttempt() {
        google.onSequence("POST", SEND, FakeGoogle.HANG_UP, new Reply(200, "{\"id\":\"gm-9\",\"threadId\":\"th-9\"}"));
        submitToBob();
        MailMessage copy = message("gi-91-501");
        String bare = copy.getRfcMessageId().substring(1, copy.getRfcMessageId().length() - 1);

        work();
        MailMessage uncertain = message("gi-91-501");
        assertThat(uncertain.getStatus()).isEqualTo(MessageStatus.QUEUED);
        assertThat(uncertain.isDeliveryUncertain()).isTrue();
        assertThat(uncertain.getError()).startsWith("Gmail did not answer: ");

        // It did go out: the next attempt finds it and sends nothing.
        google.on("GET", SEARCH, 200, "{\"messages\":[{\"id\":\"gm-0\",\"threadId\":\"th-0\"}],\"resultSizeEstimate\":1}");
        clock.advance(Duration.ofMinutes(1));
        worker.process(copy.getId());

        MailMessage sent = message("gi-91-501");
        assertThat(sent.getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(sent.getProviderMessageId()).isEqualTo("gm-0");
        assertThat(sent.getProviderThreadId()).isEqualTo("th-0");
        assertThat(sent.isDeliveryUncertain()).isFalse();
        assertThat(sent.getError()).isNull();
        assertThat(google.requests("POST", SEND)).hasSize(1);
        assertThat(google.requests("GET", SEARCH)).singleElement().satisfies(search -> {
            assertThat(search.param("q")).isEqualTo("rfc822msgid:" + bare);
            assertThat(search.param("includeSpamTrash")).isEqualTo("true");
        });
    }

    @Test
    void anUncertainCopyNotFoundIsSentAndOneThatCannotBeLookedForIsNotSentBlind() {
        google.onSequence("POST", SEND, FakeGoogle.HANG_UP, new Reply(200, "{\"id\":\"gm-9\",\"threadId\":\"th-9\"}"));
        submitToBob();
        long id = message("gi-91-501").getId();
        work();

        google.on("GET", SEARCH, 503, FakeGoogle.gmailError(503, "Backend Error"));
        clock.advance(Duration.ofMinutes(1));
        worker.process(id);
        MailMessage unchecked = message("gi-91-501");
        assertThat(unchecked.getStatus()).isEqualTo(MessageStatus.QUEUED);
        assertThat(unchecked.getError()).isEqualTo(
                "Could not check whether the email was already sent: Gmail is unavailable (503): Backend Error");
        assertThat(unchecked.isDeliveryUncertain()).isTrue();
        assertThat(google.requests("POST", SEND)).hasSize(1);

        google.on("GET", SEARCH, 200, "{\"resultSizeEstimate\":0}");
        clock.advance(Duration.ofMinutes(5));
        worker.process(id);
        MailMessage sent = message("gi-91-501");
        assertThat(sent.getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(sent.getProviderMessageId()).isEqualTo("gm-9");
        assertThat(sent.isDeliveryUncertain()).isFalse();
        assertThat(google.requests("POST", SEND)).hasSize(2);
    }

    @Test
    void gmailOutOfReachIsTriedAgainAndCannotHaveSentIt() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        submitToBob();
        String apiBase = properties.getGoogle().getApiBaseUrl();
        properties.getGoogle().setApiBaseUrl("http://127.0.0.1:" + closedPort);
        try {
            work();
        } finally {
            properties.getGoogle().setApiBaseUrl(apiBase);
        }

        MailMessage copy = message("gi-91-501");
        assertThat(copy.getStatus()).isEqualTo(MessageStatus.QUEUED);
        assertThat(copy.getError()).startsWith("Could not reach Gmail");
        assertThat(copy.isDeliveryUncertain()).isFalse();
    }

    @Test
    void anAnswerWithoutAnIdIsFinalAndMayHaveGoneOut() {
        google.on("POST", SEND, 200, "{}");
        submitToBob();

        work();

        MailMessage copy = message("gi-91-501");
        assertThat(copy.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(copy.getError()).isEqualTo("Gmail accepted the message but did not return its id");
        assertThat(copy.isDeliveryUncertain()).isTrue();
        assertThat(queue.retries()).isEmpty();
    }

    @Test
    void aTokenGmailRejectsIsRenewedAndTheSendTriedOnceMore() {
        google.onSequence("POST", SEND,
                new Reply(401, FakeGoogle.gmailError(401, "Request had invalid authentication credentials.")),
                new Reply(200, "{\"id\":\"gm-2\",\"threadId\":\"th-2\"}"));
        submitToBob();

        work();

        assertThat(message("gi-91-501").getProviderMessageId()).isEqualTo("gm-2");
        assertThat(google.requests("POST", SEND)).extracting(r -> r.header("Authorization"))
                .containsExactly("Bearer tok-1", "Bearer tok-2");
    }

    @Test
    void aRefreshTokenGoogleNoLongerAcceptsMarksTheConnectionAndTheCopiesNotSent() {
        submit(submission("7", "Jane Doe", "91", false,
                copy("gi-91-501", "Bob", "bob@acme.com"), copy("gi-91-502", "Ravi", "ravi@acme.com")));
        google.on("POST", "/token", 400, FakeGoogle.tokenError("invalid_grant", "Token has been expired or revoked."));
        // The token from connecting has run out.
        clock.advance(Duration.ofHours(1));

        work();

        MailConnection jane = connection("7");
        assertThat(jane.getStatus()).isEqualTo(ConnectionStatus.NEEDS_RECONNECT);
        assertThat(jane.getStatusReason()).isEqualTo("Google no longer accepts this Gmail connection"
                + " (invalid_grant: Token has been expired or revoked.). Reconnect Gmail.");
        for (String externalId : List.of("gi-91-501", "gi-91-502")) {
            MailMessage copy = message(externalId);
            assertThat(copy.getStatus()).isEqualTo(MessageStatus.NOT_SENT);
            assertThat(copy.getError()).isEqualTo("Jane Doe's Gmail connection needs to be renewed");
        }
        assertThat(google.requests("POST", SEND)).isEmpty();
        // The backend hears of the change once.
        assertThat(events("connection.status")).extracting(e -> payload(e).get("status").asText())
                .containsExactly("CONNECTED", "NEEDS_RECONNECT");
        assertThat(payload(events("connection.status").get(1)).get("statusReason").asText())
                .isEqualTo(jane.getStatusReason());
    }

    @Test
    void anOAuthClientGoogleDeletedMarksTheConnectionAndTheCopyNotSentRatherThanFailed() {
        submitToBob();
        google.on("POST", "/token", 401, FakeGoogle.tokenError("deleted_client", "The OAuth client was deleted."));
        clock.advance(Duration.ofHours(1));

        work();

        MailConnection jane = connection("7");
        assertThat(jane.getStatus()).isEqualTo(ConnectionStatus.NEEDS_RECONNECT);
        assertThat(jane.getStatusReason()).isEqualTo("Google no longer accepts this Gmail connection"
                + " (deleted_client: The OAuth client was deleted.). Reconnect Gmail.");
        MailMessage copy = message("gi-91-501");
        assertThat(copy.getStatus()).isEqualTo(MessageStatus.NOT_SENT);
        assertThat(copy.getError()).isEqualTo("Jane Doe's Gmail connection needs to be renewed");
        assertThat(events("connection.status")).extracting(e -> payload(e).get("status").asText())
                .containsExactly("CONNECTED", "NEEDS_RECONNECT");
    }

    @Test
    void storedSecretsThatCannotBeReadNeedAReconnect() {
        submitToBob();
        connectionRepository.findByOwnerRef("7").ifPresent(c -> {
            c.setRefreshTokenEnc("not-sealed-with-this-key");
            connectionRepository.save(c);
        });

        work();

        assertThat(connection("7").getStatus()).isEqualTo(ConnectionStatus.NEEDS_RECONNECT);
        assertThat(connection("7").getStatusReason()).isEqualTo("The stored Gmail secrets cannot be read. Reconnect Gmail.");
        assertThat(message("gi-91-501").getStatus()).isEqualTo(MessageStatus.NOT_SENT);
    }

    @Test
    void aSignInOutageIsTriedAgainAndNeverTakenForASendThatMayHaveGone() {
        submitToBob();
        google.on("POST", "/token", 503, "{\"error\":\"temporarily_unavailable\"}");
        clock.advance(Duration.ofHours(1));

        work();

        MailMessage copy = message("gi-91-501");
        assertThat(copy.getStatus()).isEqualTo(MessageStatus.QUEUED);
        assertThat(copy.getError()).isEqualTo("Google is unavailable (503): temporarily_unavailable");
        assertThat(copy.isDeliveryUncertain()).isFalse();
        assertThat(connection("7").getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
    }

    @Test
    void aSenderWhoDisconnectedOrMustReconnectIsNotSentFor() {
        submitToBob();
        connectionService.disconnect("7");

        work();

        MailMessage copy = message("gi-91-501");
        assertThat(copy.getStatus()).isEqualTo(MessageStatus.NOT_SENT);
        assertThat(copy.getError()).isEqualTo("Jane Doe has not connected Gmail");
        assertThat(google.requests("POST", SEND)).isEmpty();
        assertThat(statusTrail("gi-91-501")).containsExactly("QUEUED@1", "SENDING@2", "NOT_SENT@3");

        MailConnection sam = connect("8", "Sam Sales", "sam@gmail.com");
        submit(submission("8", "Sam Sales", "92", false, copy("gi-92-1", "Bob", "bob@acme.com")));
        connectionService.needsReconnect(sam, new GoogleAuthException("invalid_grant", "Bad Request"));
        work();
        assertThat(message("gi-92-1").getError()).isEqualTo("Sam Sales's Gmail connection needs to be renewed");
    }

    @Test
    void aFailureReportedAfterTheSweeperGaveUpChangesNothing() {
        google.on("POST", SEND, exchange -> {
            // The worker took longer than ten minutes; the sweeper has marked it interrupted meanwhile.
            clock.advance(Duration.ofMinutes(11));
            sweeper.sweep();
            return new Reply(503, FakeGoogle.gmailError(503, "Backend Error"));
        });
        submitToBob();

        work();

        MailMessage copy = message("gi-91-501");
        assertThat(copy.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(copy.getError()).isEqualTo("Sending was interrupted; retry to send again");
        assertThat(copy.isDeliveryUncertain()).isTrue();
        assertThat(queue.retries()).isEmpty();
    }

    @Test
    void aSendThatSucceedsAfterTheSweeperGaveUpIsStillRecordedAsSent() {
        google.on("POST", SEND, exchange -> {
            clock.advance(Duration.ofMinutes(11));
            sweeper.sweep();
            return new Reply(200, "{\"id\":\"gm-late\",\"threadId\":\"th-late\"}");
        });
        submitToBob();

        work();

        MailMessage copy = message("gi-91-501");
        assertThat(copy.getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(copy.getProviderMessageId()).isEqualTo("gm-late");
        assertThat(copy.isDeliveryUncertain()).isFalse();
        assertThat(statusTrail("gi-91-501")).containsExactly("QUEUED@1", "SENDING@2", "FAILED@3", "SENT@4");
    }

    @Test
    void theWorkerRefusesToRunInsideATransaction() {
        submitToBob();
        long id = message("gi-91-501").getId();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> worker.process(id)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("A copy must be sent outside a transaction");
        assertThat(message("gi-91-501").getStatus()).isEqualTo(MessageStatus.QUEUED);
    }
}
