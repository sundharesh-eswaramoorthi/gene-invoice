package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Inbox: the emails a user is a To recipient of, read and unread (§6 Inbox). */
class InboxTest extends EmailTestBase {

    @Autowired InboxService inboxService;
    @Autowired TransactionTemplate transactions;
    @Autowired PlatformTransactionManager transactionManager;

    private final Instant base = Instant.parse("2026-09-01T09:00:00Z");

    /** An email received at a known time, with one row per recipient. */
    private Email emailTo(String subject, int minutesAfterBase, Map<User, RecipientField> recipients) {
        Email email = emailRepository.save(Email.builder()
                .entityType(EmailEntityType.CUSTOMER).entityId(acme.getId()).entityLabel("Customer Acme Ltd")
                .direction(EmailDirection.OUTBOUND).status(EmailStatus.NOT_SENT)
                .subject(subject).body("Body of " + subject + " " + "word ".repeat(60))
                .fromUserId(admin.getId()).fromName("System Administrator").fromAddress("admin@geneinvoice.local")
                .fromInternal(true).sentByUserId(admin.getId())
                .occurredAt(base.plusSeconds(60L * minutesAfterBase)).build());
        recipients.forEach((u, field) -> emailRecipientRepository.save(EmailRecipient.builder()
                .email(email).field(field).userId(u.getId()).name(u.getUsername()).address(u.getEmail())
                .internal(u.getCustomerId() == null).customerId(u.getCustomerId()).sources("USER").build()));
        return email;
    }

    private Long inboxRowOf(User u, Email email) {
        return emailRecipientRepository.findByEmailIdOrderByIdAsc(email.getId()).stream()
                .filter(r -> u.getId().equals(r.getUserId())).findFirst().orElseThrow().getId();
    }

    @Test
    void theInboxListsOnlyTheCallersOwnToRowsNewestFirst() throws Exception {
        emailTo("First", 1, Map.of(collections, RecipientField.TO));
        emailTo("Third", 3, Map.of(collections, RecipientField.TO, sales, RecipientField.TO));
        emailTo("Second", 2, Map.of(collections, RecipientField.TO));
        emailTo("Copied", 4, Map.of(collections, RecipientField.CC, sales, RecipientField.TO));

        JsonNode page = getOk("/api/inbox", collections);
        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page.get("sort").asText()).isEqualTo("occurredAt,desc");
        assertThat(page.get("content")).extracting(i -> i.get("subject").asText())
                .containsExactly("Third", "Second", "First");

        JsonNode item = page.at("/content/0");
        assertThat(item.get("entityType").asText()).isEqualTo("CUSTOMER");
        assertThat(item.get("entityLink").asText()).isEqualTo("/customers/" + acme.getId());
        assertThat(item.get("snippet").asText()).hasSize(160).startsWith("Body of Third word");
        assertThat(item.at("/from/name").asText()).isEqualTo("System Administrator");
        assertThat(item.get("read").asBoolean()).isFalse();
        assertThat(item.get("status").asText()).isEqualTo("NOT_SENT");

        assertThat(getOk("/api/inbox", sales).get("content")).extracting(i -> i.get("subject").asText())
                .containsExactly("Copied", "Third");
    }

    @Test
    void aSnippetNeverEndsInHalfACharacter() throws Exception {
        Email email = emailTo("Emoji", 1, Map.of(collections, RecipientField.TO));
        // An emoji is two UTF-16 units, and this one straddles the 160th.
        email.setBody("a".repeat(159) + "\uD83D\uDE00" + " and more");
        emailRepository.save(email);

        assertThat(getOk("/api/inbox", collections).at("/content/0/snippet").asText()).isEqualTo("a".repeat(159));
    }

    @Test
    void theInboxPagesFiltersAndCounts() throws Exception {
        for (int i = 1; i <= 12; i++) emailTo("Email " + i, i, Map.of(collections, RecipientField.TO));

        JsonNode second = getOk("/api/inbox", collections, "page", "1", "size", "10");
        assertThat(second.get("totalElements").asLong()).isEqualTo(12);
        assertThat(second.get("totalPages").asInt()).isEqualTo(2);
        assertThat(second.get("content")).extracting(i -> i.get("subject").asText())
                .containsExactly("Email 2", "Email 1");

        assertThat(getOk("/api/inbox", collections, "filter", "subject:contains:email 1")
                .get("totalElements").asLong()).isEqualTo(4);
        assertThat(getOk("/api/inbox", collections, "filter", "entityType:eq:INVOICE")
                .get("totalElements").asLong()).isZero();
        // A partly sent email is one of the statuses to filter on.
        Email partly = emailRepository.findAll().get(0);
        partly.setStatus(EmailStatus.PARTIAL);
        emailRepository.save(partly);
        assertThat(getOk("/api/inbox", collections, "filter", "status:eq:PARTIAL").at("/content/0/status").asText())
                .isEqualTo("PARTIAL");
        assertThat(getOk("/api/inbox", collections, "sort", "occurredAt,asc").at("/content/0/subject").asText())
                .isEqualTo("Email 1");
        mockMvc.perform(get("/api/inbox").with(as(collections)).param("size", "25"))
                .andExpect(status().isBadRequest());

        assertThat(getOk("/api/inbox/unread-count", collections).get("count").asLong()).isEqualTo(12);
    }

    @Test
    void aCustomerLoginCannotFilterOnWhoSentIt() throws Exception {
        emailTo("Hello", 1, Map.of(acmeLogin, RecipientField.TO));

        mockMvc.perform(get("/api/inbox").with(as(acmeLogin)).param("filter", "fromName:contains:admin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown column: fromName"));
        getOk("/api/inbox", collections, "filter", "fromName:contains:admin");

        assertThat(getOk("/api/table-schemas/inbox", acmeLogin).get("columns"))
                .extracting(c -> c.get("name").asText()).doesNotContain("fromName").contains("subject", "read");
        assertThat(getOk("/api/table-schemas/inbox", admin).get("columns"))
                .extracting(c -> c.get("name").asText()).contains("fromName");
    }

    @Test
    void markingReadAndUnreadWorksOnlyOnTheCallersOwnRows() throws Exception {
        Email mine = emailTo("Mine", 1, Map.of(collections, RecipientField.TO, sales, RecipientField.TO));
        Email copied = emailTo("Copied", 2, Map.of(collections, RecipientField.CC));
        Long row = inboxRowOf(collections, mine);

        mockMvc.perform(post("/api/inbox/" + row + "/read").with(as(collections))).andExpect(status().isOk());
        EmailRecipient read = emailRecipientRepository.findById(row).orElseThrow();
        assertThat(read.isRead()).isTrue();
        assertThat(read.getReadAt()).isNotNull();
        assertThat(getOk("/api/inbox/unread-count", collections).get("count").asLong()).isZero();
        assertThat(getOk("/api/emails/" + mine.getId(), collections).get("readByMe").asBoolean()).isTrue();

        mockMvc.perform(post("/api/inbox/" + row + "/unread").with(as(collections))).andExpect(status().isOk());
        assertThat(emailRecipientRepository.findById(row).orElseThrow().getReadAt()).isNull();
        assertThat(getOk("/api/inbox/unread-count", collections).get("count").asLong()).isEqualTo(1);

        mockMvc.perform(post("/api/inbox/" + row + "/read").with(as(sales))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/inbox/" + inboxRowOf(collections, copied) + "/read").with(as(collections)))
                .andExpect(status().isNotFound());
        assertThat(emailRecipientRepository.findById(row).orElseThrow().isRead()).isFalse();
    }

    @Test
    void readingAnEmailAndAReportOnItsCopyNeverUndoEachOther() {
        Email email = emailTo("Just sent", 1, Map.of(collections, RecipientField.TO));
        Long row = inboxRowOf(collections, email);
        EmailRecipient copy = emailRecipientRepository.findById(row).orElseThrow();
        copy.setDeliveryStatus(RecipientDeliveryStatus.QUEUED);
        copy.setDeliverySeq(1);
        emailRecipientRepository.save(copy);
        TransactionTemplate meanwhile = new TransactionTemplate(transactionManager);
        meanwhile.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // A report on the copy has read the row when the recipient opens the email.
        transactions.executeWithoutResult(tx -> {
            EmailRecipient reported = emailRecipientRepository.findById(row).orElseThrow();
            meanwhile.executeWithoutResult(inner -> inboxService.setRead(row, collections.getId(), true));
            reported.setDeliveryStatus(RecipientDeliveryStatus.SENT);
            reported.setDeliverySeq(3);
        });
        assertThat(emailRecipientRepository.findById(row).orElseThrow()).satisfies(r -> {
            assertThat(r.isRead()).isTrue();
            assertThat(r.getReadAt()).isNotNull();
            assertThat(r.getDeliveryStatus()).isEqualTo(RecipientDeliveryStatus.SENT);
        });

        // The other way round: whoever holds the row loaded writes only what they changed.
        transactions.executeWithoutResult(tx -> {
            EmailRecipient loaded = emailRecipientRepository.findById(row).orElseThrow();
            meanwhile.executeWithoutResult(inner -> {
                EmailRecipient reported = emailRecipientRepository.findById(row).orElseThrow();
                reported.setDeliveryStatus(RecipientDeliveryStatus.READ);
                reported.setDeliverySeq(5);
            });
            loaded.setRead(false);
            loaded.setReadAt(null);
        });
        assertThat(emailRecipientRepository.findById(row).orElseThrow()).satisfies(r -> {
            assertThat(r.isRead()).isFalse();
            assertThat(r.getDeliveryStatus()).isEqualTo(RecipientDeliveryStatus.READ);
            assertThat(r.getDeliverySeq()).isEqualTo(5);
        });
    }

    @Test
    void markAllReadAndBulkTouchOnlyTheCallersInbox() throws Exception {
        Email a = emailTo("A", 1, Map.of(collections, RecipientField.TO, sales, RecipientField.TO));
        Email b = emailTo("B", 2, Map.of(collections, RecipientField.TO));
        Email c = emailTo("C", 3, Map.of(collections, RecipientField.TO));

        Map<String, Object> markRead = new HashMap<>();
        markRead.put("action", "MARK_READ");
        markRead.put("ids", List.of(inboxRowOf(collections, a), inboxRowOf(sales, a)));
        JsonNode result = read(postJson("/api/inbox/bulk", collections, markRead).andExpect(status().isOk()));
        assertThat(result.get("succeeded")).extracting(JsonNode::asLong).containsExactly(inboxRowOf(collections, a));
        assertThat(result.at("/skipped/0/id").asLong()).isEqualTo(inboxRowOf(sales, a));
        assertThat(emailRecipientRepository.findById(inboxRowOf(sales, a)).orElseThrow().isRead()).isFalse();

        Map<String, Object> unreadOnes = new HashMap<>();
        unreadOnes.put("action", "MARK_READ");
        unreadOnes.put("selectAllMatchingFilter", true);
        unreadOnes.put("filters", List.of("read:eq:false"));
        JsonNode all = read(postJson("/api/inbox/bulk", collections, unreadOnes).andExpect(status().isOk()));
        assertThat(all.get("succeeded")).hasSize(2);

        Map<String, Object> unread = new HashMap<>();
        unread.put("action", "MARK_UNREAD");
        unread.put("ids", List.of(inboxRowOf(collections, b), inboxRowOf(collections, c)));
        read(postJson("/api/inbox/bulk", collections, unread).andExpect(status().isOk()));
        assertThat(getOk("/api/inbox/unread-count", collections).get("count").asLong()).isEqualTo(2);

        JsonNode marked = read(mockMvc.perform(post("/api/inbox/mark-all-read").with(as(collections)))
                .andExpect(status().isOk()));
        assertThat(marked.get("updated").asInt()).isEqualTo(2);
        assertThat(getOk("/api/inbox/unread-count", collections).get("count").asLong()).isZero();
        assertThat(getOk("/api/inbox/unread-count", sales).get("count").asLong()).isEqualTo(1);

        unread.put("action", "ARCHIVE");
        postJson("/api/inbox/bulk", collections, unread).andExpect(status().isBadRequest());
    }
}
