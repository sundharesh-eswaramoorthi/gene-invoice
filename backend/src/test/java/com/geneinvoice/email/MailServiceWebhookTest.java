package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.email.RecordingMailTransport.Mode;
import com.geneinvoice.email.RecordingMailTransport.Outcome;
import com.geneinvoice.email.mailservice.MailServiceDtos;
import com.geneinvoice.email.mailservice.MailServiceEventHandler;
import com.geneinvoice.email.connection.GmailConnection;
import com.geneinvoice.email.mailservice.WebhookSignature;
import com.geneinvoice.email.transport.ConnectionStatus;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.notification.Notification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "app.mail.transport=mail-service",
        "spring.datasource.url=jdbc:h2:mem:geneinvoice-mailservice-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000",
        "app.mail.service.webhook-max-bytes=65536"
})
class MailServiceWebhookTest extends EmailTestBase {

    private static final String SECRET = "test-mail-service-webhook-secret";
    private static final String EVENTS = "/api/mail-service/events";

    @Autowired TransactionTemplate transactions;
    @Autowired EmailDispatcher dispatcher;
    @Autowired MailServiceEventHandler handler;

    private Invoice inv;
    private long emailId;
    private EmailRecipient ap;
    private EmailRecipient login;
    private long nextEventId = 1;

    @BeforeEach
    void sendOne() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        inv = invoice(acme, sales);
        emailId = send(collections, email("INVOICE", inv.getId(), List.of(toCustomer()))).get("id").asLong();
        List<EmailRecipient> copies = recipientsOf(emailId);
        ap = copies.get(0);
        login = copies.get(1);
    }

    private Map<String, Object> event(String type, Object data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", nextEventId++);
        event.put("type", type);
        event.put("occurredAt", Instant.now().toString());
        event.put("data", data);
        return event;
    }

    private String batch(List<Map<String, Object>> events) {
        return json(Map.of("events", events));
    }

    private ResultActions deliver(String body) throws Exception {
        return deliver(body, Instant.now(), SECRET);
    }

    private ResultActions deliver(String body, Instant at, String secret) throws Exception {
        String timestamp = String.valueOf(at.getEpochSecond());
        return mockMvc.perform(post(EVENTS).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Mail-Timestamp", timestamp)
                .header("X-Mail-Signature", WebhookSignature.sign(secret, timestamp, body.getBytes(StandardCharsets.UTF_8))));
    }

    @SafeVarargs
    private JsonNode deliverOk(Map<String, Object>... events) throws Exception {
        return read(deliver(batch(List.of(events))).andExpect(status().isOk()));
    }

    private Map<String, Object> copyState(EmailRecipient copy, long seq, String status, Object... more) {
        return copyState(emailId, copy, seq, status, more);
    }

    private Map<String, Object> copyState(long emailId, EmailRecipient copy, long seq, String status, Object... more) {
        Map<String, Object> state = new HashMap<>();
        state.put("externalId", CopyRef.externalId(emailId, copy.getId()));
        state.put("groupRef", String.valueOf(emailId));
        state.put("seq", seq);
        state.put("status", status);
        state.put("attempts", 1);
        state.put("deliveredConfirmed", false);
        for (int i = 0; i < more.length; i += 2) state.put((String) more[i], more[i + 1]);
        return state;
    }

    private EmailRecipient reload(EmailRecipient copy) {
        return emailRecipientRepository.findById(copy.getId()).orElseThrow();
    }

    private Email email() {
        return emailRepository.findById(emailId).orElseThrow();
    }

    @Test
    void anUnsignedStaleOrWronglySignedCallIsRefused() throws Exception {
        String body = batch(List.of(event("message.status", copyState(ap, 2, "SENT"))));

        mockMvc.perform(post(EVENTS).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"message\":\"Invalid signature\"}", true));
        deliver(body, Instant.now(), "some-other-secret-entirely").andExpect(status().isUnauthorized());
        deliver(body, Instant.now().minus(Duration.ofMinutes(6)), SECRET).andExpect(status().isUnauthorized());
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        mockMvc.perform(post(EVENTS).contentType(MediaType.APPLICATION_JSON).content(body.replace("SENT", "READ"))
                        .header("X-Mail-Timestamp", timestamp)
                        .header("X-Mail-Signature", WebhookSignature.sign(SECRET, timestamp, body.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnauthorized());

        assertThat(reload(ap).getDeliveryStatus()).isEqualTo(RecipientDeliveryStatus.QUEUED);
    }

    @Test
    void eachCopysProgressIsRecordedInOrderAndTheEmailRolledUp() throws Exception {
        Instant sentAt = Instant.parse("2026-09-20T10:00:00Z");
        JsonNode first = deliverOk(event("message.status", copyState(ap, 3, "SENT", "sentAt", sentAt.toString(),
                "fromAddress", "cara.collects@gmail.com", "providerMessageId", "gm-1", "providerThreadId", "gt-1",
                "rfcMessageId", "<gm-1@gmail.com>")));
        assertThat(first.get("processed").asInt()).isEqualTo(1);
        assertThat(reload(ap)).satisfies(c -> {
            assertThat(c.getDeliveryStatus()).isEqualTo(RecipientDeliveryStatus.SENT);
            assertThat(c.getDeliverySeq()).isEqualTo(3);
            assertThat(c.getSentAt()).isEqualTo(sentAt);
            assertThat(c.getProviderMessageId()).isEqualTo("gm-1");
            assertThat(c.getProviderThreadId()).isEqualTo("gt-1");
            assertThat(c.getRfcMessageId()).isEqualTo("<gm-1@gmail.com>");
        });
        assertThat(email().getStatus()).isEqualTo(EmailStatus.QUEUED);
        assertThat(email().getDeliveredFrom()).isEqualTo("cara.collects@gmail.com");

        deliverOk(event("message.status", copyState(login, 3, "SENT", "sentAt", sentAt.plusSeconds(1).toString())),
                event("message.status", copyState(ap, 5, "READ", "sentAt", sentAt.toString(),
                        "deliveredAt", sentAt.plusSeconds(60).toString(), "deliveredConfirmed", true,
                        "readAt", sentAt.plusSeconds(120).toString())),
                event("message.status", copyState(ap, 4, "DELIVERED", "sentAt", sentAt.toString())));
        assertThat(reload(ap)).satisfies(c -> {
            assertThat(c.getDeliveryStatus()).isEqualTo(RecipientDeliveryStatus.READ);
            assertThat(c.getDeliverySeq()).isEqualTo(5);
            assertThat(c.isDeliveredConfirmed()).isTrue();
            assertThat(c.getMailReadAt()).isEqualTo(sentAt.plusSeconds(120));
        });
        assertThat(email()).satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(EmailStatus.SENT);
            assertThat(e.getSentAt()).isEqualTo(sentAt);
            assertThat(e.getError()).isNull();
        });

        deliverOk(event("message.status", copyState(login, 4, "BOUNCED", "sentAt", sentAt.plusSeconds(1).toString(),
                "bouncedAt", sentAt.plusSeconds(30).toString(), "error", "5.1.1 The email account does not exist")));
        JsonNode shown = getOk("/api/emails/" + emailId, collections);
        assertThat(shown.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(shown.get("error").asText()).isEqualTo("5.1.1 The email account does not exist");
        assertThat(shown.get("canRetry").asBoolean()).isFalse();
        assertThat(shown.at("/to/0/delivery/status").asText()).isEqualTo("READ");
        assertThat(shown.at("/to/0/delivery/readAt").asText()).isEqualTo("2026-09-20T10:02:00Z");
        assertThat(shown.at("/to/0/delivery/deliveredConfirmed").asBoolean()).isTrue();
        assertThat(shown.at("/to/1/delivery/status").asText()).isEqualTo("BOUNCED");
        assertThat(shown.at("/to/1/delivery/bouncedAt").asText()).isEqualTo("2026-09-20T10:00:30Z");
        assertThat(shown.at("/to/1/delivery/error").asText()).isEqualTo("5.1.1 The email account does not exist");
        JsonNode theirs = getOk("/api/emails/" + emailId, acmeLogin);
        assertThat(theirs.at("/to/1/delivery/status").asText()).isEqualTo("BOUNCED");
        assertThat(theirs.at("/to/1/delivery/error").asText()).isEqualTo("Could not be delivered");
    }

    @Test
    void reportsOnWhatTheAppDoesNotHaveAreIgnoredAndABadOneIsPassedOver() throws Exception {
        long received = emailRepository.save(Email.builder().entityType(EmailEntityType.INVOICE).entityId(inv.getId())
                .entityLabel("Invoice").direction(EmailDirection.INBOUND).status(EmailStatus.RECEIVED).subject("Re")
                .fromName("Acme").fromInternal(false).build()).getId();
        Map<String, Object> notOurs = copyState(ap, 9, "SENT");
        notOurs.put("externalId", "their-own-key");
        Map<String, Object> noSuchEmail = copyState(ap, 9, "SENT");
        noSuchEmail.put("externalId", "gi-999999-" + ap.getId());
        Map<String, Object> notOnThatEmail = copyState(ap, 9, "SENT");
        notOnThatEmail.put("externalId", "gi-" + received + "-" + ap.getId());

        JsonNode result = deliverOk(event("message.status", notOurs), event("message.status", noSuchEmail),
                event("message.status", notOnThatEmail), event("mailbox.renamed", Map.of("x", 1)),
                event("message.status", "not a copy"),
                event("message.status", copyState(login, 2, "SENDING")));

        assertThat(result.get("processed").asInt()).isEqualTo(5);
        assertThat(reload(ap).getDeliveryStatus()).isEqualTo(RecipientDeliveryStatus.QUEUED);
        assertThat(reload(login).getDeliveryStatus()).isEqualTo(RecipientDeliveryStatus.SENDING);
        assertThat(email().getStatus()).isEqualTo(EmailStatus.SENDING);

        deliver("not json").andExpect(status().isBadRequest());
    }

    @Test
    void aReportThatTheServiceHasTheCopiesSettlesAHandOffWhoseAnswerWasLost() throws Exception {
        mailTransport.mode(Mode.TRANSIENT_FAILURE);
        long lost = send(collections, email("INVOICE", inv.getId(), List.of(toCustomer()))).get("id").asLong();
        Email waiting = emailRepository.findById(lost).orElseThrow();
        assertThat(waiting.getHandedOffAt()).isNull();
        assertThat(waiting.getNextAttemptAt()).isNotNull();
        EmailRecipient copy = recipientsOf(lost).get(0);

        Map<String, Object> state = copyState(copy, 2, "SENDING");
        state.put("externalId", CopyRef.externalId(lost, copy.getId()));
        deliverOk(event("message.status", state));

        Email settled = emailRepository.findById(lost).orElseThrow();
        assertThat(settled.getHandedOffAt()).isNotNull();
        assertThat(settled.getNextAttemptAt()).isNull();
        assertThat(settled.getStatus()).isEqualTo(EmailStatus.SENDING);
        int handOffs = mailTransport.submissions().size();
        dispatcher.sweep(Instant.now().plus(Duration.ofMinutes(10)));
        assertThat(mailTransport.submissions()).hasSize(handOffs);
    }

    private long partlySent() throws Exception {
        mailTransport.outcome(copy -> copy.address().equals(acmeLogin.getEmail())
                ? Outcome.notSent("Gmail refused the request (400)") : Outcome.queued());
        long id = send(collections, email("INVOICE", inv.getId(), List.of(toCustomer()))).get("id").asLong();
        mailTransport.outcome(copy -> Outcome.queued());
        deliverOk(event("message.status", copyState(id, recipientsOf(id).get(0), 3, "SENT",
                "sentAt", "2026-09-20T10:00:00Z")));
        assertThat(emailRepository.findById(id).orElseThrow().getStatus()).isEqualTo(EmailStatus.PARTIAL);
        return id;
    }

    private void assertTheRetriedCopyIsHandedOverAgain(long id) {
        EmailRecipient went = recipientsOf(id).get(0);
        EmailRecipient retried = recipientsOf(id).get(1);
        Email waiting = emailRepository.findById(id).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(EmailStatus.QUEUED);
        assertThat(waiting.getHandedOffAt()).isNull();
        assertThat(waiting.getNextAttemptAt()).isNotNull();
        assertThat(retried.getDeliveryStatus()).isEqualTo(RecipientDeliveryStatus.QUEUED);

        mailTransport.mode(Mode.SUCCESS);
        dispatcher.sweep(Instant.now().plus(Duration.ofMinutes(2)));

        assertThat(mailTransport.submissions()).last().satisfies(s -> assertThat(s.copies())
                .extracting(c -> c.externalId()).containsExactly(CopyRef.externalId(id, retried.getId())));
        Email handedOff = emailRepository.findById(id).orElseThrow();
        assertThat(handedOff.getHandedOffAt()).isNotNull();
        assertThat(handedOff.getStatus()).isEqualTo(EmailStatus.QUEUED);
        assertThat(reload(retried).getDeliverySeq()).isEqualTo(2);
        assertThat(reload(went).getDeliveryStatus()).isEqualTo(RecipientDeliveryStatus.DELIVERED);
    }

    @Test
    void aReportOnACopyThatWentOutDoesNotTakeARetriedCopysHandOffAsDone() throws Exception {
        long id = partlySent();
        EmailRecipient went = recipientsOf(id).get(0);

        mailTransport.mode(Mode.TRANSIENT_FAILURE);
        read(mockMvc.perform(post("/api/emails/" + id + "/retry").with(as(collections))).andExpect(status().isOk()));
        deliverOk(event("message.status", copyState(id, went, 4, "DELIVERED", "sentAt", "2026-09-20T10:00:00Z",
                "deliveredAt", "2026-09-20T10:15:00Z")));

        assertTheRetriedCopyIsHandedOverAgain(id);
    }

    @Test
    void aReportArrivingWhileARetryIsHandedOverLeavesItsFailureToTheDispatcher() throws Exception {
        long id = partlySent();
        EmailRecipient went = recipientsOf(id).get(0);
        AtomicBoolean reported = new AtomicBoolean();
        mailTransport.beforeSubmit(submission -> {
            if (reported.getAndSet(true)) return;
            handler.apply(new MailServiceDtos.Event(nextEventId++, "message.status", Instant.now(),
                    objectMapper.valueToTree(copyState(id, went, 4, "DELIVERED", "sentAt", "2026-09-20T10:00:00Z",
                            "deliveredAt", "2026-09-20T10:15:00Z"))));
        });

        mailTransport.mode(Mode.TRANSIENT_FAILURE);
        read(mockMvc.perform(post("/api/emails/" + id + "/retry").with(as(collections))).andExpect(status().isOk()));

        assertThat(reported).isTrue();
        assertThat(emailRepository.findById(id).orElseThrow().getError()).isEqualTo(RecordingMailTransport.UNAVAILABLE);
        assertTheRetriedCopyIsHandedOverAgain(id);
    }

    @Test
    void aBodyOverTheLimitIsRefusedUnreadAndNothingInItApplied() throws Exception {
        Map<String, Object> huge = event("message.status", copyState(ap, 2, "SENT",
                "error", "x".repeat(70_000)));

        deliver(batch(List.of(huge))).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("The events are too large"));
        deliver(batch(List.of(huge)), Instant.now().minus(Duration.ofMinutes(6)), SECRET)
                .andExpect(status().isUnauthorized());

        assertThat(reload(ap).getDeliveryStatus()).isEqualTo(RecipientDeliveryStatus.QUEUED);
    }

    @Test
    void aReplyIsSavedOnTheRecordAndReachesTheSendersInboxOnce() throws Exception {
        deliverOk(event("message.status", copyState(ap, 2, "SENT", "providerThreadId", "gt-1",
                "rfcMessageId", "<gm-1@gmail.com>")));
        Map<String, Object> reply = new HashMap<>();
        reply.put("ownerRef", String.valueOf(collections.getId()));
        reply.put("mailboxAddress", "cara.collects@gmail.com");
        reply.put("repliedToExternalId", CopyRef.externalId(emailId, ap.getId()));
        reply.put("providerMessageId", "gm-reply-1");
        reply.put("providerThreadId", "gt-1");
        reply.put("rfcMessageId", "<reply-1@acme.test>");
        reply.put("inReplyTo", "<gm-1@gmail.com>");
        reply.put("references", List.of("<gm-1@gmail.com>"));
        reply.put("from", Map.of("name", "Acme Accounts", "address", "ap@acme.test"));
        reply.put("to", List.of(Map.of("name", "Cara", "address", "cara.collects@gmail.com")));
        reply.put("cc", List.of());
        reply.put("subject", "Re: About your account");
        reply.put("body", "Paid today.");
        reply.put("receivedAt", "2026-09-20T12:00:00Z");

        deliverOk(event("message.received", reply));
        deliverOk(event("message.received", reply));

        List<Email> replies = emailRepository.findAll().stream()
                .filter(e -> e.getDirection() == EmailDirection.INBOUND).toList();
        assertThat(replies).singleElement().satisfies(e -> {
            assertThat(e.getEntityId()).isEqualTo(inv.getId());
            assertThat(e.getFromCustomerId()).isEqualTo(acme.getId());
            assertThat(e.getBody()).isEqualTo("Paid today.");
            assertThat(e.getOccurredAt()).isEqualTo(Instant.parse("2026-09-20T12:00:00Z"));
        });
        JsonNode inbox = getOk("/api/inbox", collections);
        assertThat(inbox.at("/content/0/emailId").asLong()).isEqualTo(replies.get(0).getId());
        assertThat(inbox.at("/content/0/direction").asText()).isEqualTo("INBOUND");

        reply.put("ownerRef", "someone-else");
        reply.put("providerMessageId", "gm-reply-2");
        deliverOk(event("message.received", reply));
        assertThat(emailRepository.findAll()).filteredOn(e -> e.getDirection() == EmailDirection.INBOUND).hasSize(1);
    }

    private Map<String, Object> connection(String ownerRef, String status, String reason) {
        Map<String, Object> data = new HashMap<>();
        data.put("ownerRef", ownerRef);
        data.put("ownerName", "CARA.COLLECTIONS");
        data.put("status", status);
        data.put("gmailAddress", "Cara.Collects@gmail.com");
        data.put("clientId", "123.apps.googleusercontent.com");
        data.put("scopes", List.of("https://www.googleapis.com/auth/gmail.send"));
        data.put("statusReason", reason);
        data.put("connectedAt", "2026-09-20T10:00:00Z");
        data.put("lastSyncedAt", "2026-09-20T10:05:00Z");
        data.put("lastSyncError", null);
        return data;
    }

    private List<Notification> reconnectNotices() {
        return notificationRepository.findAll().stream()
                .filter(n -> n.getUserId().equals(collections.getId()) && "GMAIL_RECONNECT".equals(n.getType())).toList();
    }

    @Test
    void aConnectionThatStopsWorkingTellsItsOwnerOnce() throws Exception {
        String cara = String.valueOf(collections.getId());
        String reason = "Google no longer accepts this Gmail connection (invalid_grant: Token has been expired or revoked.). Reconnect Gmail.";

        deliverOk(event("connection.status", connection(cara, "CONNECTED", null)));
        GmailConnection copy = gmailConnectionRepository.findById(collections.getId()).orElseThrow();
        assertThat(copy.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(copy.getGmailAddress()).isEqualTo("cara.collects@gmail.com");
        assertThat(copy.getLastSyncedAt()).isEqualTo(Instant.parse("2026-09-20T10:05:00Z"));
        assertThat(reconnectNotices()).isEmpty();

        deliverOk(event("connection.status", connection(cara, "NEEDS_RECONNECT", reason)),
                event("connection.status", connection(cara, "NEEDS_RECONNECT", reason)));
        assertThat(gmailConnectionRepository.findById(collections.getId())).get()
                .satisfies(c -> assertThat(c.getReason()).isEqualTo(reason));
        assertThat(reconnectNotices()).singleElement().satisfies(n -> {
            assertThat(n.getTitle()).isEqualTo("Reconnect your Gmail");
            assertThat(n.getMessage()).isEqualTo(reason);
            assertThat(n.getLink()).isEqualTo("/me/gmail");
        });
        JsonNode seen = getOk("/api/users/" + collections.getId() + "/gmail", admin);
        assertThat(seen.get("status").asText()).isEqualTo("NEEDS_RECONNECT");

        deliverOk(event("connection.status", connection(cara, "CONNECTED", null)),
                event("connection.status", connection(cara, "NEEDS_RECONNECT", reason)));
        assertThat(reconnectNotices()).hasSize(2);

        deliverOk(event("connection.status", connection("not-a-user", "NEEDS_RECONNECT", reason)),
                event("connection.status", connection("999999", "NEEDS_RECONNECT", reason)));
        assertThat(gmailConnectionRepository.findById(999999L)).isEmpty();
        assertThat(notificationRepository.findAll()).filteredOn(n -> "GMAIL_RECONNECT".equals(n.getType())).hasSize(2);
    }

    @Test
    void aDatabaseThatCannotBeUsedStopsTheBatchSoTheServiceSendsItAgain() throws Exception {
        String body = batch(List.of(
                event("connection.status", connection(String.valueOf(collections.getId()), "NEEDS_RECONNECT", "Reconnect Gmail.")),
                event("message.status", copyState(ap, 2, "SENT")),
                event("message.status", copyState(login, 2, "SENT"))));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        Thread holder = new Thread(() -> {
            try {
                transactions.executeWithoutResult(tx -> {
                    emailRepository.findByIdForUpdate(emailId).orElseThrow();
                    locked.countDown();
                    try {
                        release.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Throwable t) {
                failures.add(t);
            }
        }, "holds-the-email");
        holder.start();
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            deliver(body).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value("The database is unavailable; send the events again"));
        } finally {
            release.countDown();
            holder.join(20_000);
        }
        assertThat(failures).isEmpty();
        assertThat(reconnectNotices()).hasSize(1);
        assertThat(reload(login).getDeliveryStatus()).isEqualTo(RecipientDeliveryStatus.QUEUED);

        JsonNode again = read(deliver(body).andExpect(status().isOk()));
        assertThat(again.get("processed").asInt()).isEqualTo(3);
        assertThat(reconnectNotices()).hasSize(1);
        assertThat(reload(ap).getDeliveryStatus()).isEqualTo(RecipientDeliveryStatus.SENT);
        assertThat(reload(login).getDeliveryStatus()).isEqualTo(RecipientDeliveryStatus.SENT);
        assertThat(email().getStatus()).isEqualTo(EmailStatus.SENT);
    }
}
