package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.email.RecordingMailTransport.Mode;
import com.geneinvoice.email.connection.GmailConnection;
import com.geneinvoice.email.transport.ConnectionStatus;
import com.geneinvoice.email.transport.InboundHint;
import com.geneinvoice.email.transport.IncomingMail;
import com.geneinvoice.email.transport.MailAddress;
import com.geneinvoice.invoice.Invoice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replies the mail service finds in a sender's Gmail are saved on the record they answer, and reach
 * whoever sent the email they answer (M10, E8).
 */
@ExtendWith(OutputCaptureExtension.class)
class EmailInboundTest extends EmailTestBase {

    /** The collector's own Gmail, which the email went out from and the reply came back to. */
    private static final String CARAS_GMAIL = "cara.collects@gmail.com";
    private static final MailAddress MAILBOX = new MailAddress("Cara", CARAS_GMAIL);
    private static final MailAddress ACME_AP = new MailAddress("Acme Accounts", "AP@acme.test");

    @Autowired EmailInboundService inbound;

    Invoice inv;
    Email sent;
    /** The customer's own copy, as the service reported it sent. */
    EmailRecipient copy;

    /** Collections writes to the customer from their own Gmail; the service has sent the customer's copy. */
    @BeforeEach
    void sendOne() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        inv = invoice(acme, sales);
        JsonNode dto = send(collections, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "subject", "Invoice " + inv.getInvoiceNumber()));
        sent = emailRepository.findById(dto.get("id").asLong()).orElseThrow();
        copy = recipientsOf(sent.getId()).get(0);
        copy.setDeliveryStatus(RecipientDeliveryStatus.SENT);
        copy.setProviderMessageId("gm-copy-1");
        copy.setProviderThreadId("thread-copy-1");
        copy.setRfcMessageId("<gm-copy-1@gmail.com>");
        copy = emailRecipientRepository.save(copy);
    }

    private InboundHint hint(String repliedToExternalId) {
        return new InboundHint(collections.getId(), CARAS_GMAIL, repliedToExternalId);
    }

    private String copyRef() {
        return CopyRef.externalId(sent.getId(), copy.getId());
    }

    private static IncomingMail mail(String providerId, String threadId, String inReplyTo, List<String> references,
                                     MailAddress from, List<MailAddress> to, List<MailAddress> cc) {
        return new IncomingMail(providerId, threadId, "<" + providerId + "@mail.acme.test>", inReplyTo, references,
                from, to, cc, "Re: Invoice\r\nquery", "Thanks, we will pay on Friday.", Instant.parse("2026-09-17T08:30:00Z"));
    }

    @Test
    void aReplyToACopyIsSavedOnTheRecordAndLandsInTheSendersInbox() throws Exception {
        Optional<Long> id = inbound.handle(mail("r-1", "thread-copy-1", null, List.of(),
                ACME_AP, List.of(MAILBOX), List.of(new MailAddress(null, "sam.sales@test.local"))), hint(copyRef()));

        assertThat(id).isPresent();
        Email received = emailRepository.findById(id.get()).orElseThrow();
        assertThat(received.getDirection()).isEqualTo(EmailDirection.INBOUND);
        assertThat(received.getStatus()).isEqualTo(EmailStatus.RECEIVED);
        assertThat(received.getEntityType()).isEqualTo(EmailEntityType.INVOICE);
        assertThat(received.getEntityId()).isEqualTo(inv.getId());
        assertThat(received.getSubject()).isEqualTo("Re: Invoice query");
        assertThat(received.getOccurredAt()).isEqualTo(Instant.parse("2026-09-17T08:30:00Z"));
        assertThat(received.getFromName()).isEqualTo("Acme Accounts");
        assertThat(received.getFromCustomerId()).isEqualTo(acme.getId());
        assertThat(received.isFromInternal()).isFalse();
        assertThat(received.getSentByUserId()).isNull();

        List<EmailRecipient> recipients = recipientsOf(id.get());
        assertThat(recipients).hasSize(2);
        assertThat(recipients.get(0)).satisfies(r -> {
            assertThat(r.getField()).isEqualTo(RecipientField.TO);
            assertThat(r.getUserId()).isEqualTo(collections.getId());
            assertThat(r.getAddress()).isEqualTo("cara.collections@test.local");
            assertThat(r.getSources()).isEqualTo("MAILBOX");
            // Received mail has no copies of its own.
            assertThat(r.getDeliveryStatus()).isNull();
        });
        assertThat(recipients.get(1)).satisfies(r -> {
            assertThat(r.getField()).isEqualTo(RecipientField.CC);
            assertThat(r.getUserId()).isEqualTo(sales.getId());
            assertThat(r.getSources()).isEqualTo("HEADER");
        });

        JsonNode inbox = getOk("/api/inbox", collections);
        assertThat(inbox.at("/content/0/emailId").asLong()).isEqualTo(id.get());
        assertThat(inbox.at("/content/0/direction").asText()).isEqualTo("INBOUND");
        assertThat(getOk("/api/inbox", sales).get("totalElements").asLong()).isZero();

        // The customer took part, so they see it too, with the collector as the team.
        JsonNode customerView = getOk("/api/emails/" + id.get(), acmeLogin);
        assertThat(customerView.at("/from/name").asText()).isEqualTo("Acme Accounts");
        assertThat(customerView.at("/to/0/masked").asBoolean()).isTrue();
        assertThat(customerView.at("/to/0/delivery").isNull()).isTrue();
        assertThat(customerView.get("sentBy").isNull()).isTrue();
    }

    @Test
    void aReplyFromAStaffMembersConnectedGmailIsTheirsAndMaskedToTheCustomer() throws Exception {
        // Sam sends from a Gmail that is not his email in Users (M4 allows it).
        gmailConnectionRepository.save(GmailConnection.builder().userId(sales.getId())
                .status(ConnectionStatus.CONNECTED).gmailAddress("sam.personal@gmail.com").build());

        Optional<Long> id = inbound.handle(mail("r-sam", "thread-copy-1", null, List.of(),
                new MailAddress("Sam Sales", "Sam.Personal+acme@Gmail.com"), List.of(MAILBOX, ACME_AP),
                List.of(new MailAddress("Sam at home", "sam.personal@gmail.com"))), hint(copyRef()));

        Email received = emailRepository.findById(id.orElseThrow()).orElseThrow();
        assertThat(received.getFromUserId()).isEqualTo(sales.getId());
        assertThat(received.isFromInternal()).isTrue();
        assertThat(received.getFromName()).isEqualTo("SAM.SALES");
        assertThat(received.getFromCustomerId()).isNull();
        assertThat(recipientsOf(id.get())).filteredOn(r -> r.getField() == RecipientField.CC).singleElement()
                .satisfies(r -> {
                    assertThat(r.getUserId()).isEqualTo(sales.getId());
                    assertThat(r.isInternal()).isTrue();
                });

        JsonNode customerView = getOk("/api/emails/" + id.get(), acmeLogin);
        assertThat(customerView.at("/from/masked").asBoolean()).isTrue();
        assertThat(customerView.toString()).doesNotContainIgnoringCase("sam.personal").doesNotContain("Sam Sales", "SAM.SALES");
    }

    @Test
    void aReplyIsFoundByTheGmailThreadOfOneOfTheCopies() {
        Optional<Long> id = inbound.handle(mail("r-2", "thread-copy-1", null, List.of(),
                ACME_AP, List.of(MAILBOX), List.of()), hint(null));

        assertThat(id).isPresent();
        assertThat(emailRepository.findById(id.get()).orElseThrow().getEntityId()).isEqualTo(inv.getId());
    }

    @Test
    void aReplyIsFoundByInReplyToNamingACopyAndATaggedMailboxIsStillItsOwner() {
        Optional<Long> id = inbound.handle(mail("r-3", "unknown-thread", "<gm-copy-1@gmail.com>", List.of(),
                ACME_AP, List.of(new MailAddress("Cara", "Cara.Collects+acme@Gmail.com")), List.of()), hint(null));

        assertThat(id).isPresent();
        Email received = emailRepository.findById(id.get()).orElseThrow();
        assertThat(received.getEntityId()).isEqualTo(inv.getId());
        assertThat(received.getInReplyTo()).isEqualTo("<gm-copy-1@gmail.com>");
        assertThat(recipientsOf(id.get())).singleElement().satisfies(r -> {
            assertThat(r.getUserId()).isEqualTo(collections.getId());
            assertThat(r.getSources()).isEqualTo("MAILBOX");
        });
    }

    @Test
    void aReplyIsFoundThroughReferencesWithoutBrackets() {
        Optional<Long> id = inbound.handle(mail("r-4", null, null, List.of("<older@elsewhere>", "gm-copy-1@gmail.com"),
                ACME_AP, List.of(MAILBOX), List.of()), hint(null));

        assertThat(id).isPresent();
        assertThat(emailRepository.findById(id.get()).orElseThrow().getEntityId()).isEqualTo(inv.getId());
    }

    @Test
    void anEmailSentBeforeTheMailServiceIsStillFoundByItsOwnIds() {
        Invoice older = invoice(acme, sales);
        Email before = emailRepository.save(Email.builder()
                .entityType(EmailEntityType.INVOICE).entityId(older.getId()).entityLabel("Invoice " + older.getInvoiceNumber())
                .direction(EmailDirection.OUTBOUND).status(EmailStatus.SENT).subject("Old")
                .fromUserId(collections.getId()).fromName("CARA.COLLECTIONS").fromInternal(true)
                .providerMessageId("gm-old").providerThreadId("thread-old").rfcMessageId("<gi-old@company.com>").build());

        Optional<Long> byThread = inbound.handle(mail("r-5", "thread-old", null, List.of(),
                ACME_AP, List.of(MAILBOX), List.of()), hint(null));
        Optional<Long> byInReplyTo = inbound.handle(mail("r-6", "other", "<gi-old@company.com>", List.of(),
                ACME_AP, List.of(MAILBOX), List.of()), hint(null));

        assertThat(List.of(byThread, byInReplyTo)).allSatisfy(id -> assertThat(id).get()
                .satisfies(e -> assertThat(emailRepository.findById(e).orElseThrow().getEntityId()).isEqualTo(before.getEntityId())));
    }

    @Test
    void mailThatAnswersNoneOfTheAppsEmailsIsIgnoredWhoeverWroteIt() {
        // Not even the customer's own address, afresh: only replies to app email are received (M10).
        assertThat(inbound.handle(mail("x-1", "fresh-thread", null, List.of(),
                new MailAddress(null, "acme.login@test.local"), List.of(MAILBOX), List.of()), hint(null))).isEmpty();
        assertThat(inbound.handle(mail("x-2", "fresh-thread", "<not-ours@elsewhere>", List.of(),
                new MailAddress("Stranger", "stranger@elsewhere.test"), List.of(MAILBOX), List.of()), hint(null))).isEmpty();
        // A copy id the app never gave out finds nothing either.
        assertThat(inbound.handle(mail("x-3", "fresh-thread", null, List.of(),
                ACME_AP, List.of(MAILBOX), List.of()), hint("gi-999999-1"))).isEmpty();
        assertThat(emailRepository.findAll()).hasSize(1);
    }

    @Test
    void theMailboxOwnerGetsTheReplyInTheirInboxEvenWhenItReachedThemInBcc() throws Exception {
        Optional<Long> id = inbound.handle(mail("r-7", "thread-copy-1", null, List.of(),
                ACME_AP, List.of(new MailAddress("Someone", "someone@elsewhere.test")), List.of()), hint(copyRef()));

        assertThat(recipientsOf(id.orElseThrow())).satisfiesExactly(
                r -> assertThat(r.getName()).isEqualTo("Someone"),
                r -> {
                    assertThat(r.getField()).isEqualTo(RecipientField.TO);
                    assertThat(r.getUserId()).isEqualTo(collections.getId());
                    assertThat(r.getSources()).isEqualTo("MAILBOX");
                });
        assertThat(getOk("/api/inbox/unread-count", collections).get("count").asLong()).isEqualTo(1);
    }

    @Test
    void importingTheSameMessageTwiceChangesNothing() {
        IncomingMail reply = mail("r-8", "thread-copy-1", null, List.of(), ACME_AP, List.of(MAILBOX), List.of());

        Optional<Long> first = inbound.handle(reply, hint(copyRef()));
        Optional<Long> again = inbound.handle(reply, hint(copyRef()));

        assertThat(again).isEqualTo(first).isPresent();
        assertThat(emailRepository.findAll()).hasSize(2);
        assertThat(emailRecipientRepository.findAll()).hasSize(2 + 1);
    }

    @Test
    void textTheDatabaseCannotStoreIsLeftOutOfReceivedMail() {
        // An "&#0;" in an HTML reply, or a mislabelled charset, decodes to NUL, which Postgres refuses.
        String nul = String.valueOf((char) 0);
        Optional<Long> id = inbound.handle(new IncomingMail("r-nul", "thread-copy-1", "<r-nul@mail.acme.test>",
                null, List.of(), new MailAddress("Acme" + nul + " Accounts", "AP@acme.test"), List.of(MAILBOX),
                List.of(new MailAddress("Some" + nul + "one", "someone@elsewhere.test")),
                "Re: Invoice" + nul, "We will pay" + nul + " on Friday.", Instant.now()), hint(copyRef()));

        Email received = emailRepository.findById(id.orElseThrow()).orElseThrow();
        assertThat(received.getSubject()).isEqualTo("Re: Invoice");
        assertThat(received.getBody()).isEqualTo("We will pay on Friday.");
        assertThat(received.getFromName()).isEqualTo("Acme Accounts");
        assertThat(recipientsOf(id.get())).extracting(EmailRecipient::getName).contains("Someone");
    }

    @Test
    void receivedTextCutToFitNeverEndsInHalfACharacter() {
        // An emoji is two UTF-16 units; each text below has one across its column's last kept unit,
        // whose first half alone Postgres would store as '?'.
        String emoji = "😀";
        Optional<Long> id = inbound.handle(new IncomingMail("r-emoji", "thread-copy-1",
                "<r-emoji@mail.acme.test>", null, List.of(),
                new MailAddress("n".repeat(198) + emoji + " Accounts", "AP@acme.test"), List.of(MAILBOX),
                List.of(new MailAddress("m".repeat(198) + emoji + " Someone", "someone@elsewhere.test")),
                "s".repeat(498) + emoji + " subject", "a".repeat(19_998) + emoji + "b".repeat(100), Instant.now()),
                hint(copyRef()));

        Email received = emailRepository.findById(id.orElseThrow()).orElseThrow();
        assertThat(received.getBody()).isEqualTo("a".repeat(19_998) + "…");
        assertThat(received.getSubject()).isEqualTo("s".repeat(498) + "…");
        assertThat(received.getFromName()).isEqualTo("n".repeat(198) + "…");
        assertThat(recipientsOf(id.get())).extracting(EmailRecipient::getName).contains("m".repeat(198) + "…");
    }

    @Test
    void mailThatCannotBeSavedIsReportedRatherThanTakenForAConcurrentImport(CapturedOutput output) {
        // A thread id longer than its column: the insert fails, and nobody else saved the message.
        IncomingMail unsaveable = mail("r-long", "t".repeat(150), null, List.of(), ACME_AP, List.of(MAILBOX), List.of());

        assertThat(inbound.handle(unsaveable, hint(copyRef()))).isEmpty();
        assertThat(emailRepository.existsByProviderMessageId("r-long")).isFalse();
        assertThat(output).contains("Received mail r-long could not be saved");
    }

    @Test
    void theAppsOwnSentCopyIsNotImportedAsAReply() {
        IncomingMail ownCopy = new IncomingMail("copy-elsewhere", "thread-copy-1", "<gm-copy-1@gmail.com>", null,
                List.of(), MAILBOX, List.of(ACME_AP), List.of(), sent.getSubject(), sent.getBody(), Instant.now());

        assertThat(inbound.handle(ownCopy, hint(copyRef()))).isEmpty();
        assertThat(inbound.handle(new IncomingMail("gm-copy-1", "thread-copy-1", null, null,
                List.of(), ACME_AP, List.of(MAILBOX), List.of(), "x", "y", Instant.now()), hint(copyRef()))).isEmpty();
        assertThat(emailRepository.findAll()).hasSize(1);
    }

    @Test
    void aPersonInBothToAndCcIsOneToRecipient() {
        Optional<Long> id = inbound.handle(mail("r-9", "thread-copy-1", null, List.of(), ACME_AP,
                List.of(new MailAddress(null, "sam.sales@test.local"), MAILBOX),
                List.of(new MailAddress("Sam", "SAM.SALES@test.local"), new MailAddress("Someone", "someone@elsewhere.test"))),
                hint(copyRef()));

        List<EmailRecipient> recipients = recipientsOf(id.orElseThrow());
        assertThat(recipients).extracting(EmailRecipient::getField)
                .containsExactly(RecipientField.TO, RecipientField.TO, RecipientField.CC);
        assertThat(recipients.get(0).getUserId()).isEqualTo(sales.getId());
        assertThat(recipients.get(1).getUserId()).isEqualTo(collections.getId());
        assertThat(recipients.get(2)).satisfies(r -> {
            assertThat(r.getName()).isEqualTo("Someone");
            assertThat(r.isInternal()).isFalse();
            assertThat(r.getSources()).isEqualTo("HEADER");
        });
    }
}
