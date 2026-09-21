package com.geneinvoice.mail.tracking;

import com.geneinvoice.mail.FakeGoogle;
import com.geneinvoice.mail.FakeGoogle.Exchange;
import com.geneinvoice.mail.FakeGoogle.Reply;
import com.geneinvoice.mail.IntegrationTestBase;
import com.geneinvoice.mail.connection.ConnectionStatus;
import com.geneinvoice.mail.connection.MailConnection;
import com.geneinvoice.mail.message.MailMessage;
import com.geneinvoice.mail.message.MessageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryTrackerTest extends IntegrationTestBase {

    private static final String SEARCH = FakeGoogle.api("/messages");

    @Autowired DeliveryTracker tracker;

    MailConnection sam;

    @BeforeEach
    void janeWritesToSamAndBob() {
        connect("7", "Jane Doe", "jane@gmail.com");
        sam = connect("8", "Sam Sales", "sam@gmail.com");
        AtomicInteger sent = new AtomicInteger();
        google.on("POST", FakeGoogle.api("/messages/send"), exchange -> new Reply(200,
                "{\"id\":\"gm-" + sent.incrementAndGet() + "\",\"threadId\":\"th-1\"}"));
        submit(submission("7", "Jane Doe", "91", false,
                copy("gi-91-1", "Sam Sales", "Sam@Gmail.com"), copy("gi-91-2", "Bob", "bob@acme.com")));
        work();
        assertThat(message("gi-91-1").getStatus()).isEqualTo(MessageStatus.SENT);
    }

    private void inSamsMailbox(String... labels) {
        google.onMailbox("sam@gmail.com", "GET", SEARCH, 200,
                "{\"messages\":[{\"id\":\"sam-msg-1\",\"threadId\":\"sam-th-1\"}],\"resultSizeEstimate\":1}");
        google.onMailbox("sam@gmail.com", "GET", FakeGoogle.api("/messages/sam-msg-1"), 200, FakeGoogle.json(Map.of(
                "id", "sam-msg-1", "threadId", "sam-th-1", "labelIds", List.of(labels), "sizeEstimate", 2048)));
    }

    @Test
    void aCopyFoundInTheRecipientsOwnGmailIsDeliveredForCertain() {
        inSamsMailbox("INBOX", "UNREAD");
        clock.advance(Duration.ofMinutes(1));

        tracker.track();

        MailMessage copy = message("gi-91-1");
        assertThat(copy.getStatus()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(copy.isDeliveredConfirmed()).isTrue();
        assertThat(copy.getDeliveredAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));
        assertThat(copy.getRecipientConnectionId()).isEqualTo(sam.getId());
        assertThat(copy.getRecipientMessageId()).isEqualTo("sam-msg-1");
        assertThat(copy.getReadAt()).isNull();

        String bare = copy.getRfcMessageId().substring(1, copy.getRfcMessageId().length() - 1);
        Exchange search = google.requests("sam@gmail.com", "GET", SEARCH).get(0);
        assertThat(search.param("q")).isEqualTo("rfc822msgid:" + bare);
        assertThat(search.param("includeSpamTrash")).isEqualTo("true");
        assertThat(google.requests("GET", SEARCH)).hasSize(1);
        assertThat(message("gi-91-2").getStatus()).isEqualTo(MessageStatus.SENT);

        assertThat(statusTrail("gi-91-1")).containsExactly("QUEUED@1", "SENDING@2", "SENT@3", "DELIVERED@4");
        assertThat(payload(events("message.status").get(events("message.status").size() - 1))
                .get("deliveredConfirmed").asBoolean()).isTrue();

        tracker.track();
        assertThat(google.requests("GET", SEARCH)).hasSize(1);
    }

    @Test
    void aCopyAlreadyReadWhenFoundIsRead() {
        inSamsMailbox("INBOX");

        tracker.track();

        MailMessage copy = message("gi-91-1");
        assertThat(copy.getStatus()).isEqualTo(MessageStatus.READ);
        assertThat(copy.getReadAt()).isEqualTo(T0);
        assertThat(copy.getDeliveredAt()).isEqualTo(T0);
        assertThat(copy.isDeliveredConfirmed()).isTrue();
        assertThat(statusTrail("gi-91-1")).containsExactly("QUEUED@1", "SENDING@2", "SENT@3", "READ@4");
    }

    @Test
    void withoutABounceInFifteenMinutesACopyCountsAsDelivered() {
        google.onMailbox("sam@gmail.com", "GET", SEARCH, 200, "{\"resultSizeEstimate\":0}");

        clock.advance(Duration.ofMinutes(15));
        tracker.track();
        assertThat(message("gi-91-2").getStatus()).isEqualTo(MessageStatus.SENT);

        clock.advance(Duration.ofSeconds(1));
        tracker.track();
        MailMessage bob = message("gi-91-2");
        assertThat(bob.getStatus()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(bob.isDeliveredConfirmed()).isFalse();
        assertThat(bob.getDeliveredAt()).isEqualTo(T0.plus(Duration.ofMinutes(15)).plusSeconds(1));

        assertThat(message("gi-91-1").getStatus()).isEqualTo(MessageStatus.DELIVERED);
        inSamsMailbox("INBOX", "UNREAD");
        clock.advance(Duration.ofMinutes(1));
        tracker.track();
        MailMessage samsCopy = message("gi-91-1");
        assertThat(samsCopy.isDeliveredConfirmed()).isTrue();
        assertThat(samsCopy.getDeliveredAt()).isEqualTo(T0.plus(Duration.ofMinutes(15)).plusSeconds(1));
    }

    @Test
    void aCopyIsLookedForOnlyForADay() {
        google.onMailbox("sam@gmail.com", "GET", SEARCH, 200, "{\"resultSizeEstimate\":0}");
        clock.advance(Duration.ofHours(24));
        tracker.track();
        assertThat(google.requests("GET", SEARCH)).hasSize(1);

        clock.advance(Duration.ofSeconds(1));
        tracker.track();
        assertThat(google.requests("GET", SEARCH)).hasSize(1);
    }

    @Test
    void aMailboxThatCannotBeSearchedIsTriedNextTime() {
        google.onMailbox("sam@gmail.com", "GET", SEARCH, 503, FakeGoogle.gmailError(503, "Backend Error"));

        tracker.track();

        assertThat(message("gi-91-1").getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(connection("8").getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
    }

    @Test
    void aRecipientMailboxGoogleNoLongerAcceptsNeedsReconnecting() {
        google.on("POST", "/token", 400, FakeGoogle.tokenError("invalid_grant", "Token has been expired or revoked."));
        clock.advance(Duration.ofHours(1));

        tracker.track();

        assertThat(connection("8").getStatus()).isEqualTo(ConnectionStatus.NEEDS_RECONNECT);
        assertThat(message("gi-91-1").getRecipientMessageId()).isNull();
        assertThat(message("gi-91-1").isDeliveredConfirmed()).isFalse();
        assertThat(events("connection.status")).extracting(e -> payload(e).get("ownerRef").asText() + ":"
                + payload(e).get("status").asText()).containsExactly("7:CONNECTED", "8:CONNECTED", "8:NEEDS_RECONNECT");
    }
}
