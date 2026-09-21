package com.geneinvoice.mail.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.mail.FakeGoogle;
import com.geneinvoice.mail.IntegrationTestBase;
import com.geneinvoice.mail.connection.MailConnection;
import com.geneinvoice.mail.events.MailEvent;
import com.geneinvoice.mail.gmail.GoogleAuthException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MessageSubmitTest extends IntegrationTestBase {

    private static final String NUL = String.valueOf((char) 0);
    private static final String LONE_SURROGATE = String.valueOf((char) 0xD800);
    private static final String REPLACEMENT = String.valueOf((char) 0xFFFD);

    @Autowired TransactionTemplate transactions;

    private static Map<String, Object> request(Object... copies) {
        return map("sender", map("ownerRef", "7", "name", "Jane Doe"),
                "subject", "Invoice INV-0042", "body", "Please pay.", "groupRef", "91", "retry", false,
                "copies", List.of(copies));
    }

    private static Map<String, Object> copyTo(String externalId, String name, String address) {
        return map("externalId", externalId, "to", map("name", name, "address", address));
    }

    @Test
    void newCopiesAreSavedQueuedAndPublishedOnceCommitted() throws Exception {
        connect("7", "Jane Doe", "jane@gmail.com");

        JsonNode answer = read(call(post("/api/v1/messages"), request(
                copyTo("gi-91-501", "Bob Smith", "bob@acme.com"),
                copyTo("gi-91-502", "Ravi Kumar", "ravi@acme.com"))).andExpect(status().isAccepted()));

        JsonNode copies = answer.get("copies");
        assertThat(copies).extracting(c -> c.get("externalId").asText()).containsExactly("gi-91-501", "gi-91-502");
        JsonNode first = copies.get(0);
        assertThat(first.get("groupRef").asText()).isEqualTo("91");
        assertThat(first.get("seq").asLong()).isEqualTo(1);
        assertThat(first.get("status").asText()).isEqualTo("QUEUED");
        assertThat(first.get("error").isNull()).isTrue();
        assertThat(first.get("attempts").asInt()).isZero();
        assertThat(first.get("fromAddress").isNull()).isTrue();
        assertThat(first.get("deliveredConfirmed").asBoolean()).isFalse();
        assertThat(first.get("rfcMessageId").asText()).matches("<gm-[0-9a-f-]{36}@gmail\\.com>");
        for (String field : List.of("sentAt", "deliveredAt", "readAt", "bouncedAt", "providerMessageId", "providerThreadId")) {
            assertThat(first.get(field).isNull()).as(field).isTrue();
        }
        assertThat(copies.get(1).get("rfcMessageId").asText()).isNotEqualTo(first.get("rfcMessageId").asText());

        MailMessage bob = message("gi-91-501");
        assertThat(bob.getSenderRef()).isEqualTo("7");
        assertThat(bob.getFromName()).isEqualTo("Jane Doe");
        assertThat(bob.getToName()).isEqualTo("Bob Smith");
        assertThat(bob.getToAddressKey()).isEqualTo("bob@acme.com");
        assertThat(bob.getConnectionId()).isEqualTo(connection("7").getId());
        assertThat(bob.getEnqueuedAt()).isEqualTo(T0);
        assertThat(queue.enqueued()).containsExactly(bob.getId(), message("gi-91-502").getId());

        List<MailEvent> events = events("message.status");
        assertThat(events).extracting(MailEvent::getExternalId).containsExactly("gi-91-501", "gi-91-502");
        assertThat(events).allSatisfy(e -> assertThat(e.getOwnerRef()).isEqualTo("7"));
        assertThat(payload(events.get(0))).isEqualTo(first);
    }

    @Test
    void everyFieldIsCheckedAndNothingIsSavedWhenOneIsWrong() throws Exception {
        Map<String, Object> bad = map("sender", map("ownerRef", " ", "name", "Jane"),
                "subject", " \r\n ", "body", "x".repeat(20_001), "groupRef", "g".repeat(101),
                "copies", List.of(copyTo(" ", "Bob", "bob@acme.com"), copyTo("gi-1", "No one", " "),
                        copyTo("gi-1", "Twice", "twice@acme.com"), copyTo("x".repeat(101), "Long", "a".repeat(321))));

        JsonNode error = read(call(post("/api/v1/messages"), bad).andExpect(status().isBadRequest()));

        assertThat(error.get("error").asText()).isEqualTo("Bad Request");
        assertThat(objectMapper.convertValue(error.get("fieldErrors"), Map.class)).isEqualTo(Map.of(
                "sender.ownerRef", "Enter the sender",
                "subject", "Enter a subject",
                "body", "The body is too long",
                "groupRef", "The group reference is too long",
                "copies[0].externalId", "Enter the external id",
                "copies[1].to.address", "Enter the address",
                "copies[2].externalId", "The external id is repeated in this request",
                "copies[3].externalId", "The external id is too long",
                "copies[3].to.address", "The address is too long"));

        assertThat(read(call(post("/api/v1/messages"), map("sender", map("ownerRef", "7"), "subject", "Hi",
                "copies", List.of())).andExpect(status().isBadRequest())).get("fieldErrors").get("copies").asText())
                .isEqualTo("Add at least one copy");
        List<Object> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) tooMany.add(copyTo("gi-" + i, null, "p" + i + "@acme.com"));
        assertThat(read(call(post("/api/v1/messages"), map("sender", map("ownerRef", "7"), "subject", "Hi",
                "copies", tooMany)).andExpect(status().isBadRequest())).get("fieldErrors").get("copies").asText())
                .isEqualTo("At most 500 copies at a time");
        assertThat(read(call(post("/api/v1/messages"), map("sender", map("ownerRef", "x".repeat(65)), "subject", "Hi",
                "copies", List.of(copyTo("gi-1", null, "a@acme.com")))).andExpect(status().isBadRequest()))
                .get("fieldErrors").get("sender.ownerRef").asText()).isEqualTo("The sender reference is too long");
        assertThat(read(call(post("/api/v1/messages"), map("subject", "x".repeat(501)))
                .andExpect(status().isBadRequest())).get("fieldErrors").get("subject").asText())
                .isEqualTo("The subject is too long");
        assertThat(read(call(post("/api/v1/messages"), "not json").andExpect(status().isBadRequest()))
                .get("message").asText()).isEqualTo("The request body is not valid JSON");

        assertThat(messageRepository.findAll()).isEmpty();
        assertThat(eventRepository.findAll()).isEmpty();
    }

    @Test
    void textIsMadeStorableTrimmedAndCut() {
        connect("7", "Jane Doe", "jane@gmail.com");

        submit(new SubmitRequest(new SubmitRequest.Sender(" 7 ", "N".repeat(250)),
                "  Re:\r\nInvoice" + NUL + " INV-0042\n ", null, "91", false,
                List.of(copy("gi-91-501", " ", " bob@acme.com "), copy("gi-91-502", "Zo" + LONE_SURROGATE + "ë", "zoe@acme.com"))));

        MailMessage bob = message("gi-91-501");
        assertThat(bob.getSubject()).isEqualTo("Re: Invoice INV-0042");
        assertThat(bob.getBody()).isEmpty();
        assertThat(bob.getFromName()).isEqualTo("N".repeat(199) + "…");
        assertThat(bob.getSenderRef()).isEqualTo("7");
        assertThat(bob.getToAddress()).isEqualTo("bob@acme.com");
        assertThat(bob.getToName()).isEqualTo("bob@acme.com");
        assertThat(message("gi-91-502").getToName()).isEqualTo("Zo" + REPLACEMENT + "ë");
    }

    @Test
    void aCopyTheServiceHasIsReturnedAsItStandsAndNeverQueuedTwice() {
        connect("7", "Jane Doe", "jane@gmail.com");
        SubmitRequest request = submission("7", "Jane Doe", "91", false, copy("gi-91-501", "Bob", "bob@acme.com"));
        CopyState first = submit(request).get(0);
        google.on("POST", FakeGoogle.api("/messages/send"), 200, "{\"id\":\"gm-1\",\"threadId\":\"th-1\"}");
        work();
        int events = eventRepository.findAll().size();

        List<CopyState> again = submit(submission("7", "Jane Doe", "91", true, copy("gi-91-501", "Bob", "bob@acme.com")));

        assertThat(again).singleElement().satisfies(state -> {
            assertThat(state.status()).isEqualTo(MessageStatus.SENT);
            assertThat(state.seq()).isEqualTo(3);
            assertThat(state.rfcMessageId()).isEqualTo(first.rfcMessageId());
        });
        assertThat(eventRepository.findAll()).hasSize(events);
        assertThat(queue.enqueued()).isEmpty();
        assertThat(messageRepository.findAll()).hasSize(1);
    }

    @Test
    void withoutAWorkingConnectionACopyIsNotSent() {
        submit(submission("7", "Samuel L. Jackson", "90", false, copy("gi-90-1", "Bob", "bob@acme.com")));
        assertThat(message("gi-90-1").getStatus()).isEqualTo(MessageStatus.NOT_SENT);
        assertThat(message("gi-90-1").getError()).isEqualTo("Samuel L. Jackson has not connected Gmail");
        assertThat(message("gi-90-1").getConnectionId()).isNull();
        assertThat(message("gi-90-1").getRfcMessageId()).endsWith("@geneinvoice.local>");

        MailConnection jane = connect("7", "Jane Doe", "jane@gmail.com");
        connectionService.needsReconnect(jane, new GoogleAuthException("invalid_grant", "Token has been expired or revoked."));
        submit(submission("7", "Samuel L. Jackson", "91", false, copy("gi-91-1", "Bob", "bob@acme.com")));
        assertThat(message("gi-91-1").getError()).isEqualTo("Samuel L. Jackson's Gmail connection needs to be renewed");
        assertThat(message("gi-91-1").getConnectionId()).isEqualTo(jane.getId());

        connectionService.disconnect("7");
        submit(submission("7", "Samuel L. Jackson", "92", false, copy("gi-92-1", "Bob", "bob@acme.com")));
        assertThat(message("gi-92-1").getError()).isEqualTo("Samuel L. Jackson has not connected Gmail");

        assertThat(queue.enqueued()).isEmpty();
        assertThat(statusTrail("gi-90-1")).containsExactly("NOT_SENT@1");
    }

    @Test
    void anAddressMailCannotGoToIsNotSentWhileTheOthersGo() {
        connect("7", "Jane Doe", "jane@gmail.com");

        List<CopyState> states = submit(submission("7", "Jane Doe", "91", false,
                copy("gi-91-1", "Broken", "not an address"), copy("gi-91-2", "Bob", "bob@acme.com")));

        assertThat(states).extracting(CopyState::status).containsExactly(MessageStatus.NOT_SENT, MessageStatus.QUEUED);
        assertThat(states.get(0).error()).isEqualTo("Invalid email address: not an address");
        assertThat(queue.enqueued()).containsExactly(message("gi-91-2").getId());
    }

    @Test
    void aRetrySendsAgainOnlyWhatFailedOrWasNotSent() {
        submit(submission("7", "Jane Doe", "91", false, copy("gi-91-1", "Bob", "bob@acme.com")));
        CopyState notSent = messageService.get("gi-91-1");
        transactions.executeWithoutResult(s -> {
            MailMessage m = messageRepository.findByExternalId("gi-91-1").orElseThrow();
            m.setDeliveryUncertain(true);
            messageRepository.save(m);
        });
        connect("7", "Jane Doe", "jane@gmail.com");

        assertThat(submit(submission("7", "Jane Doe", "91", false, copy("gi-91-1", "Bob", "bob@acme.com"))))
                .singleElement().extracting(CopyState::status).isEqualTo(MessageStatus.NOT_SENT);

        SubmitRequest retry = new SubmitRequest(new SubmitRequest.Sender("7", "Jane Doe"), "Invoice INV-0042 (again)",
                "New body", "91", true, List.of(copy("gi-91-1", "Bob Smith", "bob@acme.com")));
        CopyState requeued = submit(retry).get(0);

        assertThat(requeued.status()).isEqualTo(MessageStatus.QUEUED);
        assertThat(requeued.seq()).isEqualTo(2);
        assertThat(requeued.error()).isNull();
        assertThat(requeued.attempts()).isZero();
        assertThat(requeued.rfcMessageId()).isEqualTo(notSent.rfcMessageId());
        MailMessage m = message("gi-91-1");
        assertThat(m.getSubject()).isEqualTo("Invoice INV-0042 (again)");
        assertThat(m.getBody()).isEqualTo("New body");
        assertThat(m.getToName()).isEqualTo("Bob Smith");
        assertThat(m.getConnectionId()).isEqualTo(connection("7").getId());
        assertThat(m.isDeliveryUncertain()).isTrue();
        assertThat(queue.enqueued()).containsExactly(m.getId());
        assertThat(statusTrail("gi-91-1")).containsExactly("NOT_SENT@1", "QUEUED@2");

        google.on("POST", FakeGoogle.api("/messages/send"), 400,
                FakeGoogle.gmailError(400, "Invalid To header"));
        google.on("GET", FakeGoogle.api("/messages"), 200, "{\"resultSizeEstimate\":0}");
        work();
        assertThat(message("gi-91-1").getStatus()).isEqualTo(MessageStatus.FAILED);
        CopyState again = submit(retry).get(0);
        assertThat(again.status()).isEqualTo(MessageStatus.QUEUED);
        assertThat(again.attempts()).isZero();
        assertThat(again.error()).isNull();
    }

    @Test
    void aCopyCanBeLookedUp() throws Exception {
        connect("7", "Jane Doe", "jane@gmail.com");
        CopyState state = submit(submission("7", "Jane Doe", "91", false, copy("gi-91-1", "Bob", "bob@acme.com"))).get(0);

        JsonNode found = read(call(get("/api/v1/messages/gi-91-1")).andExpect(status().isOk()));
        assertThat(objectMapper.treeToValue(found, CopyState.class)).isEqualTo(state);

        JsonNode missing = read(call(get("/api/v1/messages/gi-0-0")).andExpect(status().isNotFound()));
        assertThat(missing.get("message").asText()).isEqualTo("No message gi-0-0");
    }

    @Test
    void aCopyTheQueueCouldNotTakeStaysQueuedForTheSweeper() {
        connect("7", "Jane Doe", "jane@gmail.com");
        queue.failing(true);

        List<CopyState> states = submit(submission("7", "Jane Doe", "91", false, copy("gi-91-1", "Bob", "bob@acme.com")));

        assertThat(states.get(0).status()).isEqualTo(MessageStatus.QUEUED);
        assertThat(message("gi-91-1").getEnqueuedAt()).isNull();
    }
}
