package com.geneinvoice.mail.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.mail.FakeGoogle;
import com.geneinvoice.mail.FakeGoogle.Exchange;
import com.geneinvoice.mail.FakeGoogle.Reply;
import com.geneinvoice.mail.IntegrationTestBase;
import com.geneinvoice.mail.connection.ConnectRequest;
import com.geneinvoice.mail.connection.ConnectionStatus;
import com.geneinvoice.mail.connection.MailConnection;
import com.geneinvoice.mail.events.MailEvent;
import com.geneinvoice.mail.message.MailMessage;
import com.geneinvoice.mail.message.MessageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a mailbox (§4.7) over real HTTP to a stand-in for Gmail: only threads a copy was sent from
 * are looked at; a bounce updates its copy, a reply goes to the backend, a read message in a
 * recipient's mailbox makes the copy read.
 */
class MailboxSyncTest extends IntegrationTestBase {

    private static final String JANE = "jane@gmail.com";
    private static final String HISTORY = FakeGoogle.api("/history");
    private static final String MESSAGES = FakeGoogle.api("/messages");

    @Autowired MailboxSync sync;
    @Autowired DeliveryTracker tracker;
    @Autowired TransactionTemplate transactions;

    MailConnection jane;
    MailMessage toBob;

    /** Jane sent Bob a copy at T0: {@code gm-out-1} in thread {@code th-1}. */
    @BeforeEach
    void janeSentBobACopy() {
        jane = connect("7", "Jane Doe", JANE);
        AtomicInteger sent = new AtomicInteger();
        google.on("POST", FakeGoogle.api("/messages/send"), exchange ->
                new Reply(200, "{\"id\":\"gm-out-" + sent.incrementAndGet() + "\",\"threadId\":\"th-1\"}"));
        submit(submission("7", "Jane Doe", "91", false, copy("gi-91-1", "Bob Smith", "bob@acme.com")));
        work();
        toBob = message("gi-91-1");
        assertThat(toBob.getStatus()).isEqualTo(MessageStatus.SENT);
        jane = connection("7");
        google.clearRequests();
    }

    // ---- Gmail as the tests set it up -------------------------------------------------

    static Map<String, Object> added(String id, String threadId, String... labels) {
        return Map.of("message", labels.length == 0 ? Map.of("id", id, "threadId", threadId)
                : Map.of("id", id, "threadId", threadId, "labelIds", List.of(labels)));
    }

    /** A history record's labels taken off a message, which is left with {@code left}. */
    static Map<String, Object> removed(String id, String threadId, List<String> left, String... labels) {
        return Map.of("message", Map.of("id", id, "threadId", threadId, "labelIds", left), "labelIds", List.of(labels));
    }

    /** One history record per change, the mailbox standing at {@code historyId}. */
    private void history(String mailbox, String historyId, Object... changes) {
        List<Map<String, Object>> records = new java.util.ArrayList<>();
        for (int i = 0; i < changes.length; i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> change = (Map<String, Object>) changes[i];
            String kind = change.containsKey("labelIds") ? "labelsRemoved" : "messagesAdded";
            records.add(Map.of("id", String.valueOf(1001 + i), kind, List.of(change)));
        }
        google.onMailbox(mailbox, "GET", HISTORY, 200, FakeGoogle.json(Map.of("history", records, "historyId", historyId)));
    }

    /** A message as Gmail hands it out: labels and size as metadata, the whole of it as raw. */
    private void inMailbox(String mailbox, String id, String threadId, List<String> labels, String mime, Instant receivedAt) {
        String metadata = FakeGoogle.json(Map.of("id", id, "threadId", threadId, "labelIds", labels,
                "sizeEstimate", mime.length()));
        String raw = FakeGoogle.json(Map.of("id", id, "threadId", threadId, "labelIds", labels,
                "internalDate", String.valueOf(receivedAt.toEpochMilli()),
                "raw", Base64.getUrlEncoder().encodeToString(mime.getBytes(StandardCharsets.UTF_8))));
        google.onMailbox(mailbox, "GET", FakeGoogle.api("/messages/" + id),
                exchange -> new Reply(200, "raw".equals(exchange.param("format")) ? raw : metadata));
    }

    private List<String> downloads() {
        return google.exchanges().stream()
                .filter(e -> e.path().startsWith(MESSAGES + "/"))
                .map(e -> e.path().substring(MESSAGES.length() + 1) + "?format=" + e.param("format"))
                .toList();
    }

    private SyncResult syncJane() {
        return sync.sync(jane.getId());
    }

    // ---- bounces -------------------------------------------------------------------------

    @Test
    void aBounceTurnsItsCopyBouncedAndNothingOutsideTheAppsThreadsIsRead() {
        Instant noticeAt = T0.plus(Duration.ofMinutes(2));
        history(JANE, "1005",
                added("gm-out-1", "th-1", "SENT"),
                added("bounce-1", "th-1", "INBOX", "UNREAD"),
                added("private-1", "th-private", "INBOX", "UNREAD"));
        inMailbox(JANE, "bounce-1", "th-1", List.of("INBOX", "UNREAD"),
                Bounces.gmailFailure("bob@acme.com", toBob.getRfcMessageId()), noticeAt);
        clock.advance(Duration.ofMinutes(3));

        assertThat(syncJane()).isEqualTo(new SyncResult(true, 1, 1, null));

        MailMessage bounced = message("gi-91-1");
        assertThat(bounced.getStatus()).isEqualTo(MessageStatus.BOUNCED);
        assertThat(bounced.getBouncedAt()).isEqualTo(noticeAt);
        assertThat(bounced.getError()).isEqualTo(Bounces.GMAIL_FAILURE_ERROR);
        assertThat(statusTrail("gi-91-1")).last().isEqualTo("BOUNCED@4");
        assertThat(inboundRepository.findByConnectionIdOrderByIdAsc(jane.getId())).singleElement().satisfies(in -> {
            assertThat(in.getProviderMessageId()).isEqualTo("bounce-1");
            assertThat(in.getKind()).isEqualTo(MailInbound.Kind.BOUNCE);
            assertThat(in.getMessageId()).isEqualTo(bounced.getId());
        });

        // The mailbox's own sent copy and mail in other threads are never fetched.
        assertThat(downloads()).containsExactly("bounce-1?format=metadata", "bounce-1?format=raw");
        Exchange historyCall = google.requests(JANE, "GET", HISTORY).get(0);
        assertThat(historyCall.param("startHistoryId")).isEqualTo("1000");
        assertThat(historyCall.params("historyTypes")).containsExactly("messageAdded", "labelRemoved");

        MailConnection after = connection("7");
        assertThat(after.getHistoryId()).isEqualTo("1005");
        assertThat(after.getLastSyncedAt()).isEqualTo(T0.plus(Duration.ofMinutes(3)));
        assertThat(after.getLastSyncError()).isNull();
        assertThat(after.getVersion()).isEqualTo(jane.getVersion());
    }

    @Test
    void aBounceStillWinsOverAnEstimatedDeliveryButNotOverOneSeenInTheMailbox() {
        clock.advance(Duration.ofMinutes(16));
        tracker.track();
        assertThat(message("gi-91-1").getStatus()).isEqualTo(MessageStatus.DELIVERED);
        history(JANE, "1002", added("bounce-1", "th-1", "INBOX"));
        inMailbox(JANE, "bounce-1", "th-1", List.of("INBOX"), Bounces.gmailFailure("bob@acme.com", toBob.getRfcMessageId()), T0);

        syncJane();

        assertThat(message("gi-91-1").getStatus()).isEqualTo(MessageStatus.BOUNCED);
        assertThat(message("gi-91-1").isDeliveredConfirmed()).isFalse();

        // A copy its recipient's own Gmail has is delivered, whatever a notice says.
        transactions.executeWithoutResult(status -> {
            MailMessage m = messageRepository.findByExternalId("gi-91-1").orElseThrow();
            m.setStatus(MessageStatus.DELIVERED);
            m.setDeliveredConfirmed(true);
            messageRepository.save(m);
        });
        history(JANE, "1003", added("bounce-2", "th-1", "INBOX"));
        inMailbox(JANE, "bounce-2", "th-1", List.of("INBOX"), Bounces.gmailFailure("bob@acme.com", toBob.getRfcMessageId()), T0);
        assertThat(syncJane().imported()).isEqualTo(1);
        assertThat(message("gi-91-1").getStatus()).isEqualTo(MessageStatus.DELIVERED);
    }

    @Test
    void aNoticeWithoutTheMessageIdFindsTheCopyByTheFailedAddress() {
        // Ravi's copy went out in the same thread, later.
        clock.advance(Duration.ofMinutes(1));
        submit(submission("7", "Jane Doe", "91", false, copy("gi-91-2", "Ravi", "ravi@acme.com")));
        work();
        history(JANE, "1002", added("bounce-1", "th-1", "INBOX"));
        inMailbox(JANE, "bounce-1", "th-1", List.of("INBOX"), Bounces.eximFailure("Ravi@Acme.com"), T0.plusSeconds(90));

        assertThat(syncJane()).isEqualTo(new SyncResult(true, 1, 1, null));

        assertThat(message("gi-91-2").getStatus()).isEqualTo(MessageStatus.BOUNCED);
        assertThat(message("gi-91-2").getError()).isEqualTo("The recipient's mail server rejected the message");
        assertThat(message("gi-91-1").getStatus()).isEqualTo(MessageStatus.SENT);
    }

    @Test
    void aNoticeNamingNoCopyIsLeftAndADelayChangesNothing() {
        clock.advance(Duration.ofMinutes(1));
        submit(submission("7", "Jane Doe", "91", false, copy("gi-91-2", "Ravi", "ravi@acme.com")));
        work();
        history(JANE, "1003", added("bounce-1", "th-1", "INBOX"), added("delay-1", "th-1", "INBOX"));
        inMailbox(JANE, "bounce-1", "th-1", List.of("INBOX"), Bounces.eximFailure("someone@else.test"), T0);
        inMailbox(JANE, "delay-1", "th-1", List.of("INBOX"), Bounces.gmailDelay("bob@acme.com", toBob.getRfcMessageId()), T0);

        assertThat(syncJane()).isEqualTo(new SyncResult(true, 2, 0, null));

        assertThat(message("gi-91-1").getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(message("gi-91-2").getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(inboundRepository.findByConnectionIdOrderByIdAsc(jane.getId()))
                .extracting(MailInbound::getProviderMessageId, MailInbound::getKind, MailInbound::getMessageId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("bounce-1", MailInbound.Kind.SKIPPED, null),
                        org.assertj.core.groups.Tuple.tuple("delay-1", MailInbound.Kind.DELAY, toBob.getId()));
        assertThat(events("message.received")).isEmpty();
    }

    @Test
    void aBounceThatCameBeforeAnUncertainSendWasFoundInSentMailIsStillSeen() {
        // Gmail takes the copy to Ravi, but the answer never arrives: it waits for another attempt.
        google.on("POST", FakeGoogle.api("/messages/send"), exchange -> FakeGoogle.HANG_UP);
        submit(submission("7", "Jane Doe", "91", false, copy("gi-91-2", "Ravi", "nobody@gmail.com")));
        work();
        MailMessage toRavi = message("gi-91-2");
        assertThat(toRavi.getStatus()).isEqualTo(MessageStatus.QUEUED);
        assertThat(toRavi.isDeliveryUncertain()).isTrue();

        // The address does not exist: Gmail's notice is in the new thread within seconds, and the next
        // run passes over it, the thread being nobody's yet as far as the service knows.
        history(JANE, "1002", added("gm-out-9", "th-9", "SENT"), added("bounce-9", "th-9", "INBOX", "UNREAD"));
        inMailbox(JANE, "bounce-9", "th-9", List.of("INBOX", "UNREAD"),
                Bounces.gmailFailure("nobody@gmail.com", toRavi.getRfcMessageId()), T0.plusSeconds(5));
        assertThat(syncJane()).isEqualTo(new SyncResult(true, 0, 0, null));
        assertThat(connection("7").getHistoryId()).isEqualTo("1002");

        // The retry finds the copy in Sent mail, records it, and then looks at its thread once. Gmail put
        // it in an earlier conversation with the same subject, which is none of the service's business.
        google.onMailbox(JANE, "GET", MESSAGES, 200, "{\"messages\":[{\"id\":\"gm-out-9\",\"threadId\":\"th-9\"}]}");
        inMailbox(JANE, "older-9", "th-9", List.of("INBOX"), Bounces.reply("ravi@acme.com", "<older@acme.com>"), T0.minusSeconds(86400));
        google.onMailbox(JANE, "GET", FakeGoogle.api("/threads/th-9"), 200, "{\"id\":\"th-9\",\"messages\":["
                + "{\"id\":\"older-9\",\"threadId\":\"th-9\",\"labelIds\":[\"INBOX\"]},"
                + "{\"id\":\"gm-out-9\",\"threadId\":\"th-9\",\"labelIds\":[\"SENT\"]},"
                + "{\"id\":\"bounce-9\",\"threadId\":\"th-9\",\"labelIds\":[\"INBOX\",\"UNREAD\"]}]}");
        clock.advance(Duration.ofMinutes(1));
        worker.process(toRavi.getId());

        MailMessage bounced = message("gi-91-2");
        assertThat(bounced.getStatus()).isEqualTo(MessageStatus.BOUNCED);
        assertThat(bounced.getProviderThreadId()).isEqualTo("th-9");
        assertThat(bounced.getError()).isEqualTo(Bounces.GMAIL_FAILURE_ERROR);
        assertThat(google.requests(JANE, "GET", FakeGoogle.api("/threads/th-9"))).singleElement()
                .satisfies(call -> assertThat(call.param("format")).isEqualTo("minimal"));
        assertThat(inboundRepository.findByConnectionIdOrderByIdAsc(jane.getId()))
                .extracting(MailInbound::getProviderMessageId, MailInbound::getKind)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("bounce-9", MailInbound.Kind.BOUNCE));
        assertThat(google.requests(JANE, "GET", FakeGoogle.api("/messages/older-9"))).isEmpty();
        assertThat(events("message.received")).isEmpty();
        // Sent once, and not taken for delivered a quarter of an hour on.
        assertThat(google.requests("POST", FakeGoogle.api("/messages/send"))).hasSize(1);
        clock.advance(Duration.ofMinutes(16));
        tracker.track();
        assertThat(message("gi-91-2").getStatus()).isEqualTo(MessageStatus.BOUNCED);
    }

    @Test
    void aBounceARunPassedOverWhileTheCopyWasBeingRecordedIsStillSeen() {
        google.on("POST", FakeGoogle.api("/messages/send"), 200, "{\"id\":\"gm-out-2\",\"threadId\":\"th-2\"}");
        submit(submission("7", "Jane Doe", "91", false, copy("gi-91-2", "Ravi", "nobody@gmail.com")));
        String rfcMessageId = message("gi-91-2").getRfcMessageId();
        history(JANE, "1002", added("bounce-2", "th-2", "INBOX"));
        inMailbox(JANE, "bounce-2", "th-2", List.of("INBOX"), Bounces.gmailFailure("nobody@gmail.com", rfcMessageId),
                T0.plusSeconds(2));
        google.onMailbox(JANE, "GET", FakeGoogle.api("/threads/th-2"), 200, "{\"id\":\"th-2\",\"messages\":["
                + "{\"id\":\"gm-out-2\",\"threadId\":\"th-2\",\"labelIds\":[\"SENT\"]},"
                + "{\"id\":\"bounce-2\",\"threadId\":\"th-2\",\"labelIds\":[\"INBOX\"]}]}");
        // A run reads the mailbox after Gmail took the copy and before the copy is recorded as sent.
        AtomicReference<SyncResult> during = new AtomicReference<>();
        google.onMailbox(JANE, "GET", FakeGoogle.api("/messages/gm-out-2"), exchange -> {
            if (during.get() == null) during.set(syncJane());
            return new Reply(200, "{\"id\":\"gm-out-2\",\"payload\":{\"headers\":[]}}");
        });

        work();

        assertThat(during.get()).isEqualTo(new SyncResult(true, 0, 0, null));
        assertThat(connection("7").getHistoryId()).isEqualTo("1002");
        assertThat(message("gi-91-2").getStatus()).isEqualTo(MessageStatus.BOUNCED);
        assertThat(google.requests(JANE, "GET", FakeGoogle.api("/threads/th-2"))).hasSize(1);

        // With no run in between, a send does not look at its thread.
        google.on("POST", FakeGoogle.api("/messages/send"), 200, "{\"id\":\"gm-out-3\",\"threadId\":\"th-3\"}");
        submit(submission("7", "Jane Doe", "93", false, copy("gi-93-1", "Bob Smith", "bob@acme.com")));
        work();
        assertThat(message("gi-93-1").getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(google.requests("GET", FakeGoogle.api("/threads/th-3"))).isEmpty();
    }

    // ---- replies -------------------------------------------------------------------------

    @Test
    void aReplyGoesToTheBackendNamingTheNewestCopyInItsThread() {
        clock.advance(Duration.ofMinutes(1));
        submit(submission("7", "Jane Doe", "91", false, copy("gi-91-2", "Ravi", "ravi@acme.com")));
        work();
        Instant repliedAt = T0.plus(Duration.ofMinutes(5));
        history(JANE, "1002", added("reply-1", "th-1", "INBOX", "UNREAD"));
        inMailbox(JANE, "reply-1", "th-1", List.of("INBOX", "UNREAD"), Bounces.reply("bob@acme.com", toBob.getRfcMessageId()),
                repliedAt);

        assertThat(syncJane()).isEqualTo(new SyncResult(true, 1, 1, null));

        MailEvent event = events("message.received").get(0);
        assertThat(event.getOwnerRef()).isEqualTo("7");
        assertThat(event.getExternalId()).isEqualTo("gi-91-2");
        JsonNode data = payload(event);
        assertThat(data.get("ownerRef").asText()).isEqualTo("7");
        assertThat(data.get("mailboxAddress").asText()).isEqualTo(JANE);
        assertThat(data.get("repliedToExternalId").asText()).isEqualTo("gi-91-2");
        assertThat(data.get("providerMessageId").asText()).isEqualTo("reply-1");
        assertThat(data.get("providerThreadId").asText()).isEqualTo("th-1");
        assertThat(data.get("rfcMessageId").asText()).isEqualTo("<reply-1@mail.acme.com>");
        assertThat(data.get("inReplyTo").asText()).isEqualTo(toBob.getRfcMessageId());
        assertThat(data.get("references")).extracting(JsonNode::asText).containsExactly(toBob.getRfcMessageId());
        assertThat(data.get("from").get("name").asText()).isEqualTo("Bob Smith");
        assertThat(data.get("from").get("address").asText()).isEqualTo("bob@acme.com");
        assertThat(data.get("to").get(0).get("address").asText()).isEqualTo(JANE);
        assertThat(data.get("cc").get(0).get("address").asText()).isEqualTo("ravi@acme.com");
        assertThat(data.get("cc").get(0).get("name").isNull()).isTrue();
        assertThat(data.get("subject").asText()).isEqualTo("Re: Invoice INV-0042");
        assertThat(data.get("body").asText()).isEqualTo("Thanks, we will pay on Friday.");
        assertThat(data.get("receivedAt").asText()).isEqualTo(repliedAt.toString());

        assertThat(inboundRepository.findByConnectionIdOrderByIdAsc(jane.getId())).singleElement()
                .satisfies(in -> assertThat(in.getKind()).isEqualTo(MailInbound.Kind.REPLY));
        // A reply changes no copy.
        assertThat(message("gi-91-1").getStatus()).isEqualTo(MessageStatus.SENT);
    }

    @Test
    void aMessageDealtWithIsNotDownloadedAgainWhenHistoryRepeatsIt() {
        history(JANE, "1002", added("reply-1", "th-1", "INBOX"));
        inMailbox(JANE, "reply-1", "th-1", List.of("INBOX"), Bounces.reply("bob@acme.com", toBob.getRfcMessageId()), T0);
        syncJane();

        assertThat(syncJane()).isEqualTo(new SyncResult(true, 0, 0, null));

        assertThat(downloads()).containsExactly("reply-1?format=metadata", "reply-1?format=raw");
        assertThat(events("message.received")).hasSize(1);
    }

    @Test
    void mailTakenOutOfSpamIsLookedAtAndSetAsideMailIsNot() {
        history(JANE, "1004",
                added("spam-1", "th-1", "SPAM", "UNREAD"),
                removed("spam-1", "th-1", List.of("INBOX", "UNREAD"), "SPAM"),
                added("draft-1", "th-1"),
                added("binned-1", "th-1"));
        inMailbox(JANE, "spam-1", "th-1", List.of("INBOX", "UNREAD"), Bounces.reply("bob@acme.com", toBob.getRfcMessageId()), T0);
        inMailbox(JANE, "draft-1", "th-1", List.of("DRAFT"), Bounces.reply("jane@gmail.com", toBob.getRfcMessageId()), T0);
        inMailbox(JANE, "binned-1", "th-1", List.of("TRASH"), Bounces.reply("bob@acme.com", toBob.getRfcMessageId()), T0);

        assertThat(syncJane()).isEqualTo(new SyncResult(true, 1, 1, null));

        assertThat(events("message.received")).extracting(e -> payload(e).get("providerMessageId").asText())
                .containsExactly("spam-1");
        // Labels are read before a download, which only a message they allow gets.
        assertThat(downloads()).containsExactly("spam-1?format=metadata", "spam-1?format=raw",
                "draft-1?format=metadata", "binned-1?format=metadata");
    }

    // ---- read in the recipient's mailbox ---------------------------------------------------

    @Test
    void aCopyReadInTheRecipientsOwnGmailIsRead() {
        MailConnection sam = connect("8", "Sam Sales", "sam@gmail.com");
        submit(submission("7", "Jane Doe", "92", false, copy("gi-92-1", "Sam", "sam@gmail.com")));
        work();
        google.onMailbox("sam@gmail.com", "GET", MESSAGES, 200, "{\"messages\":[{\"id\":\"sam-msg-1\",\"threadId\":\"s-th\"}]}");
        google.onMailbox("sam@gmail.com", "GET", FakeGoogle.api("/messages/sam-msg-1"), 200,
                "{\"id\":\"sam-msg-1\",\"labelIds\":[\"INBOX\",\"UNREAD\"]}");
        tracker.track();
        assertThat(message("gi-92-1").getStatus()).isEqualTo(MessageStatus.DELIVERED);

        history("sam@gmail.com", "1003",
                removed("sam-msg-1", "s-th", List.of("INBOX"), "UNREAD"),
                removed("other-1", "o-th", List.of("INBOX"), "UNREAD"));
        clock.advance(Duration.ofMinutes(4));
        assertThat(sync.sync(sam.getId())).isEqualTo(new SyncResult(true, 0, 0, null));

        MailMessage read = message("gi-92-1");
        assertThat(read.getStatus()).isEqualTo(MessageStatus.READ);
        assertThat(read.getReadAt()).isEqualTo(T0.plus(Duration.ofMinutes(4)));
        assertThat(statusTrail("gi-92-1")).last().isEqualTo("READ@5");
        // Sam's mailbox was only asked for its history: nothing in it was downloaded.
        assertThat(google.requests("sam@gmail.com", "GET", FakeGoogle.api("/messages/other-1"))).isEmpty();

        // Read again after being marked unread: once read, it stays read.
        clock.advance(Duration.ofMinutes(1));
        sync.sync(sam.getId());
        assertThat(message("gi-92-1").getReadAt()).isEqualTo(T0.plus(Duration.ofMinutes(4)));
    }

    // ---- where a run starts and how it ends --------------------------------------------------

    @Test
    void historyGmailNoLongerHasFallsBackToTheLastWeek() {
        google.onMailbox(JANE, "GET", HISTORY, 404, FakeGoogle.gmailError(404, "Requested entity was not found."));
        google.historyAt(JANE, "5000");
        google.onMailbox(JANE, "GET", MESSAGES, exchange -> exchange.param("pageToken") == null
                ? new Reply(200, "{\"messages\":[{\"id\":\"new-1\",\"threadId\":\"th-1\"},"
                + "{\"id\":\"private-1\",\"threadId\":\"th-private\"}],\"nextPageToken\":\"older\"}")
                : new Reply(200, "{\"messages\":[{\"id\":\"old-1\",\"threadId\":\"th-1\"}]}"));
        inMailbox(JANE, "new-1", "th-1", List.of("INBOX"), Bounces.reply("bob@acme.com", toBob.getRfcMessageId()), T0);
        inMailbox(JANE, "old-1", "th-1", List.of("INBOX"), Bounces.gmailDelay("bob@acme.com", toBob.getRfcMessageId()), T0);

        assertThat(syncJane()).isEqualTo(new SyncResult(true, 2, 1, null));

        assertThat(connection("7").getHistoryId()).isEqualTo("5000");
        List<Exchange> listings = google.requests(JANE, "GET", MESSAGES);
        assertThat(listings).hasSize(2);
        assertThat(listings.get(0).param("q")).isEqualTo("newer_than:7d");
        assertThat(listings.get(0).param("includeSpamTrash")).isNull();
        assertThat(listings.get(1).param("pageToken")).isEqualTo("older");
        // Where the mailbox stood is read before listing, so mail arriving meanwhile is not skipped.
        List<String> order = google.exchanges().stream().map(Exchange::path).toList();
        assertThat(order.lastIndexOf(FakeGoogle.api("/profile"))).isLessThan(order.indexOf(MESSAGES));
        // Oldest first; only the app's thread.
        assertThat(downloads()).containsExactly("old-1?format=metadata", "old-1?format=raw",
                "new-1?format=metadata", "new-1?format=raw");
    }

    @Test
    void withoutTheHistoryCopiesReadInTheRecipientsGmailMeanwhileAreStillRead() {
        // Sam's own Gmail is connected; Jane sent him three copies, found there unread.
        MailConnection sam = connect("8", "Sam Sales", "sam@gmail.com");
        clock.advance(Duration.ofMinutes(1));
        submit(submission("7", "Jane Doe", "92", false, copy("gi-92-1", "Sam", "sam@gmail.com")));
        submit(submission("7", "Jane Doe", "93", false, copy("gi-93-1", "Sam", "sam@gmail.com")));
        submit(submission("7", "Jane Doe", "94", false, copy("gi-94-1", "Sam", "sam@gmail.com")));
        work();
        Map<String, String> inSamsMailbox = Map.of(
                message("gi-92-1").getRfcMessageId(), "sam-msg-1",
                message("gi-93-1").getRfcMessageId(), "sam-msg-2",
                message("gi-94-1").getRfcMessageId(), "sam-msg-3");
        google.onMailbox("sam@gmail.com", "GET", MESSAGES, exchange -> {
            String q = exchange.param("q");
            if (!q.startsWith("rfc822msgid:")) return new Reply(200, "{\"resultSizeEstimate\":0}");
            String id = inSamsMailbox.get("<" + q.substring("rfc822msgid:".length()) + ">");
            return new Reply(200, "{\"messages\":[{\"id\":\"" + id + "\",\"threadId\":\"s-th\"}]}");
        });
        for (String id : List.of("sam-msg-1", "sam-msg-2", "sam-msg-3")) {
            google.onMailbox("sam@gmail.com", "GET", FakeGoogle.api("/messages/" + id), 200,
                    "{\"id\":\"" + id + "\",\"labelIds\":[\"INBOX\",\"UNREAD\"]}");
        }
        tracker.track();
        assertThat(List.of("gi-92-1", "gi-93-1", "gi-94-1")).allSatisfy(externalId ->
                assertThat(message(externalId).getStatus()).isEqualTo(MessageStatus.DELIVERED));

        // Sam's mailbox goes unread for longer than Gmail keeps its history. Meanwhile he reads the
        // first copy, and reads and archives the second (no labels left); the third is still unread.
        google.onMailbox("sam@gmail.com", "GET", HISTORY, 404, FakeGoogle.gmailError(404, "Requested entity was not found."));
        google.historyAt("sam@gmail.com", "9000");
        google.onMailbox("sam@gmail.com", "GET", FakeGoogle.api("/messages/sam-msg-1"), 200,
                "{\"id\":\"sam-msg-1\",\"labelIds\":[\"INBOX\"]}");
        google.onMailbox("sam@gmail.com", "GET", FakeGoogle.api("/messages/sam-msg-2"), 200, "{\"id\":\"sam-msg-2\"}");
        clock.advance(Duration.ofHours(1));

        assertThat(sync.sync(sam.getId())).isEqualTo(new SyncResult(true, 0, 0, null));

        assertThat(message("gi-92-1").getStatus()).isEqualTo(MessageStatus.READ);
        assertThat(message("gi-92-1").getReadAt()).isEqualTo(T0.plus(Duration.ofMinutes(61)));
        assertThat(message("gi-93-1").getStatus()).isEqualTo(MessageStatus.READ);
        assertThat(message("gi-94-1").getStatus()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(connection("8").getHistoryId()).isEqualTo("9000");

        // A copy older than the week the run looks back over is not asked about.
        google.clearRequests();
        clock.advance(Duration.ofDays(8));
        sync.sync(sam.getId());
        assertThat(google.requests("sam@gmail.com", "GET", FakeGoogle.api("/messages/sam-msg-3"))).isEmpty();
        assertThat(message("gi-94-1").getStatus()).isEqualTo(MessageStatus.DELIVERED);
    }

    @Test
    void theFirstRunOnlyNotesWhereTheMailboxStands() {
        transactions.executeWithoutResult(status -> {
            MailConnection c = connectionRepository.findByOwnerRef("7").orElseThrow();
            c.setHistoryId(null);
            connectionRepository.save(c);
        });
        google.historyAt(JANE, "1234");

        assertThat(syncJane()).isEqualTo(new SyncResult(true, 0, 0, null));

        assertThat(connection("7").getHistoryId()).isEqualTo("1234");
        assertThat(connection("7").getLastSyncedAt()).isEqualTo(T0);
        assertThat(google.requests("GET", HISTORY)).isEmpty();
    }

    @Test
    void aFailedRunKeepsItsPlaceAndSaysWhy() {
        google.onMailbox(JANE, "GET", HISTORY, exchange -> new Reply(500, FakeGoogle.gmailError(500, "Backend Error")));

        assertThat(syncJane()).isEqualTo(new SyncResult(true, 0, 0, "Gmail is unavailable (500): Backend Error"));
        assertThat(connection("7").getHistoryId()).isEqualTo("1000");
        assertThat(connection("7").getLastSyncError()).isEqualTo("Gmail is unavailable (500): Backend Error");
        assertThat(connection("7").getLastSyncedAt()).isNull();

        google.onMailbox(JANE, "GET", HISTORY, 200, "{\"historyId\":\"1001\"}");
        assertThat(syncJane()).isEqualTo(new SyncResult(true, 0, 0, null));
        assertThat(connection("7").getHistoryId()).isEqualTo("1001");
        assertThat(connection("7").getLastSyncError()).isNull();
    }

    @Test
    void theBackendHearsOfASyncErrorWhenItAppearsChangesOrClears() {
        int before = events("connection.status").size();
        google.onMailbox(JANE, "GET", HISTORY, 500, FakeGoogle.gmailError(500, "Backend Error"));

        syncJane();
        // The same error again: nothing new to tell.
        syncJane();
        List<MailEvent> reported = events("connection.status");
        assertThat(reported).hasSize(before + 1);
        JsonNode failing = payload(reported.get(reported.size() - 1));
        assertThat(failing.get("status").asText()).isEqualTo("CONNECTED");
        assertThat(failing.get("lastSyncError").asText()).isEqualTo("Gmail is unavailable (500): Backend Error");

        google.onMailbox(JANE, "GET", HISTORY, 400, FakeGoogle.gmailError(400, "Invalid startHistoryId"));
        syncJane();
        assertThat(events("connection.status")).hasSize(before + 2);

        google.onMailbox(JANE, "GET", HISTORY, 200, "{\"historyId\":\"1001\"}");
        syncJane();
        syncJane();
        reported = events("connection.status");
        assertThat(reported).hasSize(before + 3);
        JsonNode cleared = payload(reported.get(reported.size() - 1));
        assertThat(cleared.get("lastSyncError").isNull()).isTrue();
        assertThat(cleared.get("lastSyncedAt").asText()).isEqualTo(T0.toString());
    }

    @Test
    void aLongErrorIsCutToFitWithoutEndingInHalfACharacter() {
        String said = "Gmail is unavailable (500): ";
        String emoji = new String(Character.toChars(0x1F600));
        // Google's explanation runs past the column, with an emoji across its last kept unit.
        String upToTheEmoji = said + "x".repeat(MailConnection.REASON_MAX - 2 - said.length());
        google.onMailbox(JANE, "GET", HISTORY, 500,
                FakeGoogle.gmailError(500, upToTheEmoji.substring(said.length()) + emoji + " and more"));

        assertThat(syncJane().error()).startsWith(upToTheEmoji + emoji);
        assertThat(connection("7").getLastSyncError()).isEqualTo(upToTheEmoji + "…");
    }

    @Test
    void aMailboxGoogleNoLongerAcceptsNeedsReconnecting() {
        google.on("POST", "/token", 400, FakeGoogle.tokenError("invalid_grant", "Token has been expired or revoked."));
        clock.advance(Duration.ofHours(1));

        SyncResult result = syncJane();

        String reason = "Google no longer accepts this Gmail connection (invalid_grant: Token has been expired or revoked.)."
                + " Reconnect Gmail.";
        assertThat(result).isEqualTo(new SyncResult(true, 0, 0, reason));
        MailConnection after = connection("7");
        assertThat(after.getStatus()).isEqualTo(ConnectionStatus.NEEDS_RECONNECT);
        assertThat(after.getStatusReason()).isEqualTo(reason);
        assertThat(after.getLastSyncError()).isEqualTo(reason);
        assertThat(after.getHistoryId()).isEqualTo("1000");

        // Not connected now: the API says so and the next run leaves it alone.
        assertThat(sync.syncNow("7")).isEqualTo(new SyncResult(false, 0, 0, "Gmail is not connected"));
    }

    @Test
    void mailThatCameWhileTheConnectionNeededRenewingIsReadOnceTheSameMailboxIsReconnected() {
        clock.advance(Duration.ofMinutes(1));
        submit(submission("7", "Jane Doe", "91", false, copy("gi-91-2", "Ravi", "ravi@acme.com")));
        work();
        // Jane's token expires (a Google app in Testing): the next run finds out, and her mailbox is
        // not read until she renews it.
        google.expire(refreshToken("7"));
        clock.advance(Duration.ofHours(1));
        syncJane();
        assertThat(connection("7").getStatus()).isEqualTo(ConnectionStatus.NEEDS_RECONNECT);
        tracker.track();
        assertThat(message("gi-91-2").getStatus()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(message("gi-91-2").isDeliveredConfirmed()).isFalse();

        // Meanwhile Ravi's copy bounced and Bob replied; Gmail's history has both after 1000.
        String gap = FakeGoogle.json(Map.of("historyId", "1005", "history", List.of(
                Map.of("id", "1002", "messagesAdded", List.of(added("bounce-1", "th-1", "INBOX"))),
                Map.of("id", "1004", "messagesAdded", List.of(added("reply-1", "th-1", "INBOX", "UNREAD"))))));
        google.onMailbox(JANE, "GET", HISTORY, exchange -> Long.parseLong(exchange.param("startHistoryId")) < 1002
                ? new Reply(200, gap) : new Reply(200, "{\"historyId\":\"1005\"}"));
        inMailbox(JANE, "bounce-1", "th-1", List.of("INBOX"), Bounces.eximFailure("ravi@acme.com"), T0.plus(Duration.ofMinutes(3)));
        inMailbox(JANE, "reply-1", "th-1", List.of("INBOX", "UNREAD"), Bounces.reply("bob@acme.com", toBob.getRfcMessageId()),
                T0.plus(Duration.ofMinutes(30)));

        // She pastes a new refresh token for the same Gmail, whose profile now stands at 1005.
        google.account(JANE, "1//refresh-7-renewed");
        google.historyAt(JANE, "1005");
        connectionService.connect("7", new ConnectRequest("Jane Doe", "client-7.apps.googleusercontent.com", "secret-7",
                "1//refresh-7-renewed"));
        assertThat(connection("7").getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(connection("7").getHistoryId()).isEqualTo("1000");
        jane = connection("7");

        assertThat(syncJane()).isEqualTo(new SyncResult(true, 2, 2, null));

        assertThat(google.requests(JANE, "GET", HISTORY)).last()
                .satisfies(call -> assertThat(call.param("startHistoryId")).isEqualTo("1000"));
        assertThat(message("gi-91-2").getStatus()).isEqualTo(MessageStatus.BOUNCED);
        assertThat(events("message.received")).extracting(e -> payload(e).get("providerMessageId").asText())
                .containsExactly("reply-1");
        assertThat(connection("7").getHistoryId()).isEqualTo("1005");
    }

    @Test
    void aMessageGmailWillNotHandOverIsPassedOverAndTheRunGoesOn() {
        history(JANE, "1003", added("refused-1", "th-1", "INBOX"), added("garbled-1", "th-1", "INBOX"),
                added("broken-1", "th-1", "INBOX"), added("reply-1", "th-1", "INBOX"));
        google.onMailbox(JANE, "GET", FakeGoogle.api("/messages/refused-1"), exchange -> "raw".equals(exchange.param("format"))
                ? new Reply(400, FakeGoogle.gmailError(400, "Invalid message"))
                : new Reply(200, "{\"id\":\"refused-1\",\"labelIds\":[\"INBOX\"],\"sizeEstimate\":16000000}"));
        google.onMailbox(JANE, "GET", FakeGoogle.api("/messages/garbled-1"), exchange -> "raw".equals(exchange.param("format"))
                ? new Reply(200, "<html>Not what was asked for</html>")
                : new Reply(200, "{\"id\":\"garbled-1\",\"labelIds\":[\"INBOX\"]}"));
        google.onMailbox(JANE, "GET", FakeGoogle.api("/messages/broken-1"), 200,
                "{\"id\":\"broken-1\",\"threadId\":\"th-1\",\"labelIds\":[\"INBOX\"],\"raw\":\"not*base64url!\"}");
        inMailbox(JANE, "reply-1", "th-1", List.of("INBOX"), Bounces.reply("bob@acme.com", toBob.getRfcMessageId()), T0);

        // None of them will download any better next time, so they do not stop receiving.
        assertThat(syncJane()).isEqualTo(new SyncResult(true, 2, 1, null));

        assertThat(events("message.received")).extracting(e -> payload(e).get("providerMessageId").asText())
                .containsExactly("reply-1");
        assertThat(connection("7").getHistoryId()).isEqualTo("1003");
        assertThat(connection("7").getLastSyncError()).isNull();
    }

    @Test
    void troubleThatIsNotTheMessagesOwnStopsTheRunWhichKeepsItsPlace() {
        history(JANE, "1002", added("first-1", "th-1", "INBOX"), added("reply-1", "th-1", "INBOX"));
        inMailbox(JANE, "reply-1", "th-1", List.of("INBOX"), Bounces.reply("bob@acme.com", toBob.getRfcMessageId()), T0);

        // Gmail is down: the next run tries the same message again.
        google.onMailbox(JANE, "GET", FakeGoogle.api("/messages/first-1"), 503, FakeGoogle.gmailError(503, "Backend Error"));
        assertThat(syncJane()).isEqualTo(new SyncResult(true, 0, 0, "Gmail is unavailable (503): Backend Error"));

        // Refused for the mailbox's access, which every other message would be too.
        google.onMailbox(JANE, "GET", FakeGoogle.api("/messages/first-1"), 403,
                FakeGoogle.gmailError(403, "Insufficient Permission"));
        assertThat(syncJane().error()).isEqualTo("Gmail refused the request (403): Insufficient Permission");

        // Refused as if for the message, but Gmail no longer answers for the mailbox either.
        google.onMailbox(JANE, "GET", FakeGoogle.api("/messages/first-1"), 400, FakeGoogle.gmailError(400, "Invalid message"));
        google.onMailbox(JANE, "GET", FakeGoogle.api("/profile"), 500, FakeGoogle.gmailError(500, "Backend Error"));
        assertThat(syncJane().error()).isEqualTo("Gmail refused the request (400): Invalid message");

        assertThat(events("message.received")).isEmpty();
        assertThat(google.requests(JANE, "GET", FakeGoogle.api("/messages/reply-1"))).isEmpty();
        assertThat(connection("7").getHistoryId()).isEqualTo("1000");
    }

    @Test
    void aSyncAskedForWhileOneRunsSaysSoAtOnce() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        google.onMailbox(JANE, "GET", HISTORY, exchange -> {
            arrived.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Reply(200, "{\"historyId\":\"1001\"}");
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<SyncResult> first = pool.submit(this::syncJane);
            assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            assertThat(sync.syncNow("7")).isEqualTo(new SyncResult(true, 0, 0, "A sync is already running"));
            // It does not wait for the run under way: a request thread must not queue behind a slow mailbox.
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));

            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(new SyncResult(true, 0, 0, null));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(google.requests("GET", HISTORY)).hasSize(1);
        assertThat(connection("7").getLastSyncError()).isNull();
    }

    @Test
    void turnedOffNothingIsRead() {
        properties.getSync().setEnabled(false);
        try {
            assertThat(sync.syncNow("7")).isEqualTo(
                    new SyncResult(false, 0, 0, "Receiving email is turned off (MAIL_SYNC_ENABLED)"));
        } finally {
            properties.getSync().setEnabled(true);
        }
        assertThat(google.requests("GET", HISTORY)).isEmpty();
    }
}
