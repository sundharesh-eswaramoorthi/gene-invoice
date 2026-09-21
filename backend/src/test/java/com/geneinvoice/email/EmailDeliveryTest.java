package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.email.RecordingMailTransport.Mode;
import com.geneinvoice.email.RecordingMailTransport.Outcome;
import com.geneinvoice.email.connection.GmailConnection;
import com.geneinvoice.email.transport.ConnectionStatus;
import com.geneinvoice.email.transport.CopyRequest;
import com.geneinvoice.email.transport.MailSendException;
import com.geneinvoice.email.transport.Submission;
import com.geneinvoice.email.transport.SyncResult;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.user.User;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmailDeliveryTest extends EmailTestBase {

    @Autowired EmailDispatcher dispatcher;
    @Autowired DataSource dataSource;
    @Autowired TransactionTemplate transactions;

    private long queuedEmail(Invoice inv, String subject) {
        return queuedEmail(inv, subject, RecipientDeliveryStatus.QUEUED);
    }

    private long queuedEmail(Invoice inv, String subject, RecipientDeliveryStatus copy) {
        Email queued = emailRepository.save(Email.builder()
                .entityType(EmailEntityType.INVOICE).entityId(inv.getId()).entityLabel("Invoice " + inv.getInvoiceNumber())
                .direction(EmailDirection.OUTBOUND).status(EmailStatus.QUEUED)
                .subject(subject).fromName("System Administrator").fromInternal(true)
                .fromUserId(admin.getId()).sentByUserId(admin.getId()).build());
        emailRecipientRepository.save(EmailRecipient.builder().email(queued).field(RecipientField.TO)
                .name("Acme Ltd").address("ap@acme.test").customerId(acme.getId()).sources("CUSTOMER")
                .deliveryStatus(copy).build());
        return queued.getId();
    }

    private Email stored(long id) {
        return emailRepository.findById(id).orElseThrow();
    }

    private EmailStatus statusOf(long id) {
        return stored(id).getStatus();
    }

    private JsonNode retry(long id, User caller) throws Exception {
        return read(mockMvc.perform(post("/api/emails/" + id + "/retry").with(as(caller))).andExpect(status().isOk()));
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting");
            Thread.sleep(20);
        }
    }

    @Test
    void withoutTheMailServiceTheEmailIsSavedButNotSentAndSaysWhy() throws Exception {
        Invoice inv = invoice(acme, sales);

        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer())));

        assertThat(sent.get("status").asText()).isEqualTo("NOT_SENT");
        assertThat(sent.get("error").asText()).isEqualTo("Email delivery is not configured (mail service)");
        assertThat(sent.get("canRetry").asBoolean()).isTrue();
        assertThat(sent.get("to")).allSatisfy(p -> {
            assertThat(p.at("/delivery/status").asText()).isEqualTo("NOT_SENT");
            assertThat(p.at("/delivery/error").asText()).isEqualTo("Email delivery is not configured (mail service)");
        });
        assertThat(mailTransport.submissions()).isEmpty();
        // Still the recipients' in-app copy (M13).
        assertThat(getOk("/api/inbox/unread-count", acmeLogin).get("count").asLong()).isEqualTo(1);
    }

    @Test
    void eachToRecipientWithAnAddressIsHandedOverAsACopyOfTheirOwn() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        Invoice inv = invoice(acme, sales);
        seat(acme, PocType.COLLECTION, collections);

        JsonNode sent = send(admin, email("INVOICE", inv.getId(),
                List.of(toUser(collections), toRole("COLLECTION_POC"), toCustomer()),
                "subject", "Invoice due", "body", "Please pay"));
        long id = sent.get("id").asLong();

        assertThat(sent.get("status").asText()).isEqualTo("QUEUED");
        assertThat(sent.get("attempts").asInt()).isEqualTo(1);
        assertThat(sent.get("error").isNull()).isTrue();
        assertThat(sent.get("canRetry").asBoolean()).isFalse();
        assertThat(sent.get("to")).hasSize(3).allSatisfy(p -> {
            assertThat(p.at("/delivery/status").asText()).isEqualTo("QUEUED");
            assertThat(p.at("/delivery/deliveredConfirmed").asBoolean()).isFalse();
            assertThat(p.at("/delivery/readInAppAt").isNull()).isTrue();
        });

        Submission handedOver = mailTransport.submissions().get(0);
        assertThat(handedOver.senderUserId()).isEqualTo(admin.getId());
        assertThat(handedOver.senderName()).isEqualTo("System Administrator");
        assertThat(handedOver.subject()).isEqualTo("Invoice due");
        assertThat(handedOver.body()).isEqualTo("Please pay");
        assertThat(handedOver.groupRef()).isEqualTo(String.valueOf(id));
        assertThat(handedOver.retry()).isTrue();
        List<EmailRecipient> recipients = recipientsOf(id);
        assertThat(handedOver.copies()).extracting(CopyRequest::externalId)
                .containsExactlyElementsOf(recipients.stream().map(r -> "gi-" + id + "-" + r.getId()).toList());
        assertThat(handedOver.copies()).extracting(CopyRequest::address)
                .containsExactly("cara.collections@test.local", "ap@acme.test", "acme.login@test.local");
        assertThat(handedOver.copies()).extracting(CopyRequest::name)
                .containsExactly("CARA.COLLECTIONS", "Acme Ltd", "ACME.LOGIN");

        Email email = stored(id);
        assertThat(email.getHandedOffAt()).isNotNull();
        assertThat(email.getNextAttemptAt()).isNull();
        assertThat(email.getRfcMessageId()).isNull();
        assertThat(email.getProviderMessageId()).isNull();
        assertThat(recipients).allSatisfy(r -> {
            assertThat(r.getDeliveryStatus()).isEqualTo(RecipientDeliveryStatus.QUEUED);
            assertThat(r.getDeliverySeq()).isEqualTo(1);
        });
    }

    @Test
    void aRecipientWithoutAnAddressGetsNoCopyAndWhenNobodyHasOneTheEmailStaysInTheApp() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        User noAddress = user("nell.noaddress", "CASHIER");
        noAddress.setEmail(null);
        userRepository.save(noAddress);
        Invoice inv = invoice(acme, sales);

        JsonNode mixed = send(admin, email("INVOICE", inv.getId(), List.of(toUser(noAddress), toUser(sales))));
        assertThat(mailTransport.copiesHandedOver()).extracting(CopyRequest::address).containsExactly("sam.sales@test.local");
        assertThat(mixed.at("/to/0/delivery").isNull()).isTrue();
        assertThat(mixed.at("/to/1/delivery/status").asText()).isEqualTo("QUEUED");
        assertThat(recipientsOf(mixed.get("id").asLong()).get(0).getDeliveryStatus()).isNull();

        JsonNode nobody = send(admin, email("INVOICE", inv.getId(), List.of(toUser(noAddress))));
        assertThat(nobody.get("status").asText()).isEqualTo("NOT_SENT");
        assertThat(nobody.get("error").asText()).isEqualTo("No recipient has an email address");
        assertThat(nobody.at("/to/0/address").isNull()).isTrue();
        assertThat(mailTransport.submissions()).hasSize(1);
        assertThat(getOk("/api/inbox/unread-count", noAddress).get("count").asLong()).isEqualTo(2);
    }

    @Test
    void whatTheServiceMakesOfTheCopiesIsRolledUpOntoTheEmail() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        mailTransport.outcome(copy -> Outcome.notSent("SAM.SALES has not connected Gmail"));
        Invoice inv = invoice(acme, sales);

        JsonNode sent = send(sales, email("INVOICE", inv.getId(), List.of(toCustomer())));

        assertThat(sent.get("status").asText()).isEqualTo("NOT_SENT");
        assertThat(sent.get("error").asText()).isEqualTo("SAM.SALES has not connected Gmail");
        assertThat(sent.get("canRetry").asBoolean()).isTrue();
        assertThat(sent.get("to")).allSatisfy(p ->
                assertThat(p.at("/delivery/error").asText()).isEqualTo("SAM.SALES has not connected Gmail"));
        assertThat(mailTransport.submissions()).singleElement()
                .satisfies(s -> assertThat(s.senderUserId()).isEqualTo(sales.getId()));
    }

    @Test
    void anEmailFromACustomerLoginIsSavedButNotHandedOver() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        Invoice inv = invoice(acme, sales);

        JsonNode sent = send(acmeLogin, email("INVOICE", inv.getId(), List.of(toRole("SALES_POC", "RECORD"))));

        assertThat(sent.get("status").asText()).isEqualTo("NOT_SENT");
        assertThat(sent.get("error").asText()).isEqualTo("Email from a customer login is not sent through Gmail");
        assertThat(mailTransport.submissions()).isEmpty();
        assertThat(getOk("/api/inbox/unread-count", sales).get("count").asLong()).isEqualTo(1);
    }

    @Test
    void aCopyTheServiceDoesNotReportFailsRatherThanWaitingForever() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        mailTransport.reported(copy -> !copy.address().equals("acme.login@test.local"));
        Invoice inv = invoice(acme, sales);

        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer())));

        assertThat(sent.get("to")).extracting(p -> p.at("/delivery/status").asText()).containsExactly("QUEUED", "FAILED");
        assertThat(sent.at("/to/1/delivery/error").asText()).isEqualTo("The mail service did not accept this copy");
        assertThat(sent.get("status").asText()).isEqualTo("QUEUED");
    }

    @Test
    void aTransientFailureIsHandedOverAgainAndGivenUpOnTheThirdAttempt() throws Exception {
        mailTransport.mode(Mode.TRANSIENT_FAILURE);
        Invoice inv = invoice(acme, sales);

        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer())));
        long id = sent.get("id").asLong();

        assertThat(sent.get("status").asText()).isEqualTo("QUEUED");
        assertThat(sent.get("error").asText()).isEqualTo(RecordingMailTransport.UNAVAILABLE);
        assertThat(sent.get("canRetry").asBoolean()).isFalse();
        Email first = stored(id);
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getHandedOffAt()).isNull();
        assertThat(first.getNextAttemptAt()).isBetween(Instant.now().plusSeconds(50), Instant.now().plusSeconds(61));
        assertThat(recipientsOf(id)).extracting(EmailRecipient::getDeliveryStatus)
                .containsOnly(RecipientDeliveryStatus.QUEUED);

        dispatcher.sweep(Instant.now().plusSeconds(45));
        assertThat(stored(id).getAttempts()).isEqualTo(1);

        dispatcher.sweep(Instant.now().plus(Duration.ofMinutes(2)));
        Email second = stored(id);
        assertThat(second.getStatus()).isEqualTo(EmailStatus.QUEUED);
        assertThat(second.getAttempts()).isEqualTo(2);
        assertThat(second.getNextAttemptAt()).isAfter(Instant.now().plus(Duration.ofMinutes(4)));

        dispatcher.sweep(Instant.now().plus(Duration.ofMinutes(10)));
        Email third = stored(id);
        assertThat(third.getStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(third.getAttempts()).isEqualTo(3);
        assertThat(third.getNextAttemptAt()).isNull();
        assertThat(third.getError()).isEqualTo(RecordingMailTransport.UNAVAILABLE);
        assertThat(recipientsOf(id)).allSatisfy(r -> {
            assertThat(r.getDeliveryStatus()).isEqualTo(RecipientDeliveryStatus.FAILED);
            assertThat(r.getDeliveryError()).isEqualTo(RecordingMailTransport.UNAVAILABLE);
        });
        assertThat(mailTransport.submissions()).hasSize(3)
                .extracting(s -> s.copies().stream().map(CopyRequest::externalId).toList())
                .containsOnly(mailTransport.submissions().get(0).copies().stream().map(CopyRequest::externalId).toList());
        assertThat(getOk("/api/emails/" + id, admin).get("canRetry").asBoolean()).isTrue();
    }

    @Test
    void aPermanentFailureIsFinalUntilSomeoneRetries() throws Exception {
        mailTransport.mode(Mode.PERMANENT_FAILURE);
        Invoice inv = invoice(acme, sales);

        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer())));
        long id = sent.get("id").asLong();
        assertThat(sent.get("status").asText()).isEqualTo("FAILED");
        assertThat(sent.get("error").asText()).isEqualTo(RecordingMailTransport.REFUSED);
        assertThat(sent.get("canRetry").asBoolean()).isTrue();
        assertThat(sent.at("/to/0/delivery/status").asText()).isEqualTo("FAILED");

        dispatcher.sweep(Instant.now().plus(Duration.ofHours(1)));
        assertThat(mailTransport.submissions()).hasSize(1);

        mailTransport.mode(Mode.SUCCESS);
        JsonNode retried = retry(id, admin);
        assertThat(retried.get("status").asText()).isEqualTo("QUEUED");
        assertThat(retried.get("attempts").asInt()).isEqualTo(1);
        assertThat(retried.get("error").isNull()).isTrue();
        assertThat(retried.get("to")).allSatisfy(p -> assertThat(p.at("/delivery/status").asText()).isEqualTo("QUEUED"));
        assertThat(mailTransport.submissions()).hasSize(2).last().satisfies(s -> assertThat(s.retry()).isTrue());

        mockMvc.perform(post("/api/emails/" + id + "/retry").with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only failed or unsent email can be retried"));
    }

    @Test
    void aLongFailureIsCutToFitWithoutEndingInHalfACharacter() throws Exception {
        String emoji = "😀";
        mailTransport.beforeSubmit(submission -> {
            throw new MailSendException("e".repeat(Email.ERROR_MAX - 2) + emoji + " and more", false);
        });
        mailTransport.mode(Mode.SUCCESS);
        Invoice inv = invoice(acme, sales);

        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer())));

        assertThat(sent.get("status").asText()).isEqualTo("FAILED");
        assertThat(stored(sent.get("id").asLong()).getError()).isEqualTo("e".repeat(Email.ERROR_MAX - 2) + "…");
    }

    @Test
    void noDatabaseConnectionIsHeldWhileTheServiceIsCalled() throws Exception {
        HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        List<Integer> inUse = new CopyOnWriteArrayList<>();
        mailTransport.beforeSubmit(submission -> inUse.add(pool.getActiveConnections()));
        mailTransport.mode(Mode.PERMANENT_FAILURE);
        Invoice inv = invoice(acme, sales);

        long id = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()))).get("id").asLong();
        mailTransport.mode(Mode.SUCCESS);
        retry(id, admin);

        assertThat(inUse).containsExactly(0, 0);
    }

    @Test
    void noOneTakesARetryBeforeItsWaitIsOver() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        Invoice inv = invoice(acme, sales);
        long id = queuedEmail(inv, "Rate limited");
        Email waiting = stored(id);
        waiting.setNextAttemptAt(Instant.now().plus(Duration.ofMinutes(1)));
        emailRepository.save(waiting);

        dispatcher.dispatch(id);
        assertThat(stored(id)).satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(EmailStatus.QUEUED);
            assertThat(e.getAttempts()).isZero();
            assertThat(e.getHandedOffAt()).isNull();
        });
        assertThat(mailTransport.submissions()).isEmpty();

        dispatcher.sweep(Instant.now().plus(Duration.ofMinutes(2)));
        assertThat(stored(id).getHandedOffAt()).isNotNull();
        assertThat(mailTransport.submissions()).hasSize(1);
    }

    @Test
    void theSweeperLeavesABulkSendToTheBackgroundThreadUntilItHasHandedEachEmailOver() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        Invoice inv = invoice(acme, sales);
        List<Long> bulk = List.of(queuedEmail(inv, "One"), queuedEmail(inv, "Two"), queuedEmail(inv, "Three"));
        EmailDispatcher inBackground = new EmailDispatcher(emailRepository, emailRecipientRepository, mailTransport,
                transactions, true);
        CountDownLatch firstHandingOver = new CountDownLatch(1);
        CountDownLatch carryOn = new CountDownLatch(1);
        List<String> handedOverOn = new CopyOnWriteArrayList<>();
        mailTransport.beforeSubmit(submission -> {
            handedOverOn.add(Thread.currentThread().getName());
            if (firstHandingOver.getCount() == 0) return;
            firstHandingOver.countDown();
            try {
                carryOn.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            inBackground.dispatchAll(bulk);
            assertThat(firstHandingOver.await(10, TimeUnit.SECONDS)).isTrue();

            inBackground.sweep(Instant.now().plusSeconds(31));
            carryOn.countDown();
            waitUntil(() -> bulk.stream().allMatch(id -> stored(id).getHandedOffAt() != null));
        } finally {
            carryOn.countDown();
            inBackground.shutdown();
        }
        assertThat(handedOverOn).containsExactly("email-dispatch", "email-dispatch", "email-dispatch");

        inBackground.sweep(Instant.now().plusSeconds(31));
        assertThat(handedOverOn).hasSize(3);
    }

    @Test
    void aHandOffThatDiedHalfWayIsMarkedFailedAndNeverRepeatedOnItsOwn() throws Exception {
        Invoice inv = invoice(acme, sales);
        long id = queuedEmail(inv, "Cut short");
        Email email = stored(id);
        email.setStatus(EmailStatus.SENDING);
        emailRepository.save(email);
        mailTransport.mode(Mode.SUCCESS);

        dispatcher.sweep(Instant.now().plus(Duration.ofMinutes(5)));
        assertThat(statusOf(id)).isEqualTo(EmailStatus.SENDING);

        dispatcher.sweep(Instant.now().plus(Duration.ofMinutes(11)));
        Email swept = stored(id);
        assertThat(swept.getStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(swept.getError()).isEqualTo("Sending was interrupted; retry to send again");
        assertThat(recipientsOf(id)).singleElement()
                .satisfies(r -> assertThat(r.getDeliveryStatus()).isEqualTo(RecipientDeliveryStatus.FAILED));
        assertThat(mailTransport.submissions()).isEmpty();

        JsonNode retried = retry(id, admin);
        assertThat(retried.get("status").asText()).isEqualTo("QUEUED");
        assertThat(mailTransport.submissions()).singleElement().satisfies(s -> assertThat(s.retry()).isTrue());
    }

    @Test
    void aRetryOfAPartlySentEmailWhoseHandOffDiedStaysPartlySent() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        mailTransport.outcome(copy -> copy.address().equals("ap@acme.test")
                ? new Outcome(RecipientDeliveryStatus.SENT, null)
                : new Outcome(RecipientDeliveryStatus.FAILED, "Gmail refused the request (400)"));
        Invoice inv = invoice(acme, sales);
        long id = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()))).get("id").asLong();
        assertThat(statusOf(id)).isEqualTo(EmailStatus.PARTIAL);
        transactions.executeWithoutResult(tx -> {
            Email email = stored(id);
            EmailDeliveryRollup.requeue(email, recipientsOf(id));
            email.setStatus(EmailStatus.SENDING);
            emailRepository.save(email);
        });
        assertThat(recipientsOf(id).get(1).getDeliveryStatus()).isEqualTo(RecipientDeliveryStatus.QUEUED);

        dispatcher.sweep(Instant.now().plus(Duration.ofMinutes(11)));

        Email swept = stored(id);
        assertThat(swept.getStatus()).isEqualTo(EmailStatus.PARTIAL);
        assertThat(swept.getError()).isEqualTo("Sending was interrupted; retry to send again");
        assertThat(swept.getNextAttemptAt()).isNull();
        assertThat(recipientsOf(id)).extracting(EmailRecipient::getDeliveryStatus)
                .containsExactly(RecipientDeliveryStatus.SENT, RecipientDeliveryStatus.FAILED);
        assertThat(getOk("/api/emails/" + id, admin).get("canRetry").asBoolean()).isTrue();
        assertThat(mailTransport.submissions()).hasSize(1);
    }

    @Test
    void theSweeperHandsOverWhatARestartLeftQueuedButNotWhatWasJustSaved() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        Invoice inv = invoice(acme, sales);
        long queued = queuedEmail(inv, "Left behind");

        dispatcher.sweep(Instant.now());
        assertThat(mailTransport.submissions()).isEmpty();

        dispatcher.sweep(Instant.now().plusSeconds(31));
        assertThat(mailTransport.submissions()).singleElement()
                .satisfies(s -> assertThat(s.groupRef()).isEqualTo(String.valueOf(queued)));
    }

    @Test
    void anEmailSavedBeforeTheMailServiceGetsItsCopiesWhenHandedOver() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        Invoice inv = invoice(acme, sales);
        long older = queuedEmail(inv, "From before", null);

        dispatcher.sweep(Instant.now().plusSeconds(31));

        EmailRecipient customer = recipientsOf(older).get(0);
        assertThat(customer.getDeliveryStatus()).isEqualTo(RecipientDeliveryStatus.QUEUED);
        assertThat(mailTransport.copiesHandedOver()).singleElement()
                .satisfies(c -> assertThat(c.externalId()).isEqualTo("gi-" + older + "-" + customer.getId()));
    }

    @Test
    void aRetrySendsOnlyTheCopiesThatFailedOrWereNotSentAndLeavesBouncesAlone() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        mailTransport.outcome(copy -> switch (copy.address()) {
            case "ap@acme.test" -> new Outcome(RecipientDeliveryStatus.SENT, null);
            case "acme.login@test.local" -> new Outcome(RecipientDeliveryStatus.FAILED, "Gmail refused the request (400)");
            default -> new Outcome(RecipientDeliveryStatus.BOUNCED, "5.1.1 No such user");
        });
        Invoice inv = invoice(acme, sales);

        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer(), toUser(sales))));
        long id = sent.get("id").asLong();
        assertThat(sent.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(sent.get("error").asText()).isEqualTo("2 of 3 not delivered: Gmail refused the request (400)");
        assertThat(sent.get("canRetry").asBoolean()).isTrue();
        assertThat(sent.get("to")).extracting(p -> p.at("/delivery/status").asText())
                .containsExactly("SENT", "FAILED", "BOUNCED");

        mailTransport.outcome(copy -> Outcome.queued());
        JsonNode retried = retry(id, admin);

        assertThat(mailTransport.submissions()).hasSize(2).last().satisfies(s -> {
            assertThat(s.retry()).isTrue();
            assertThat(s.copies()).extracting(CopyRequest::address).containsExactly("acme.login@test.local");
        });
        assertThat(retried.get("status").asText()).isEqualTo("QUEUED");
        assertThat(retried.get("to")).extracting(p -> p.at("/delivery/status").asText())
                .containsExactly("SENT", "QUEUED", "BOUNCED");
        assertThat(recipientsOf(id).get(1).getDeliverySeq()).isEqualTo(2);
    }

    @Test
    void anEmailWhoseCopiesAllBouncedCannotBeRetried() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        mailTransport.outcome(copy -> new Outcome(RecipientDeliveryStatus.BOUNCED, "5.1.1 No such user"));
        Invoice inv = invoice(acme, sales);

        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer())));

        assertThat(sent.get("status").asText()).isEqualTo("FAILED");
        assertThat(sent.get("error").asText()).isEqualTo("5.1.1 No such user");
        assertThat(sent.get("canRetry").asBoolean()).isFalse();
        mockMvc.perform(post("/api/emails/" + sent.get("id").asLong() + "/retry").with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only failed or unsent email can be retried"));
        assertThat(mailTransport.submissions()).hasSize(1);
    }

    @Test
    void anOlderFailedEmailWithoutCopiesIsRetriedToEveryoneItWentTo() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        Invoice inv = invoice(acme, sales);
        long older = queuedEmail(inv, "From before", null);
        Email failed = stored(older);
        failed.setStatus(EmailStatus.FAILED);
        failed.setError("Gmail is unavailable (503)");
        failed.setAttempts(3);
        emailRepository.save(failed);
        assertThat(getOk("/api/emails/" + older, admin).get("canRetry").asBoolean()).isTrue();

        JsonNode retried = retry(older, admin);

        assertThat(retried.get("status").asText()).isEqualTo("QUEUED");
        assertThat(retried.get("attempts").asInt()).isEqualTo(1);
        assertThat(retried.at("/to/0/delivery/status").asText()).isEqualTo("QUEUED");
        assertThat(mailTransport.copiesHandedOver()).extracting(CopyRequest::address).containsExactly("ap@acme.test");
    }

    @Test
    void retryingNeedsEmailSendAndTheRecord() throws Exception {
        Invoice theirs = invoice(acme, otherSales);
        long id = send(admin, email("INVOICE", theirs.getId(), List.of(toUser(sales)))).get("id").asLong();

        mockMvc.perform(post("/api/emails/" + id + "/retry").with(as(user("vic.viewer", "VIEWER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/emails/" + id + "/retry").with(as(sales)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deliveryTellsTheCallerWhetherEmailGoesOutAndHowTheirGmailStands() throws Exception {
        JsonNode off = getOk("/api/emails/delivery", admin);
        assertThat(off.get("configured").asBoolean()).isFalse();
        assertThat(off.at("/gmail/status").asText()).isEqualTo("NOT_CONNECTED");

        mailTransport.mode(Mode.SUCCESS);
        gmailConnectionRepository.save(GmailConnection.builder().userId(admin.getId())
                .status(ConnectionStatus.CONNECTED).gmailAddress("admin@gmail.com")
                .lastSyncedAt(Instant.parse("2026-09-20T10:05:00Z")).lastSyncError("Gmail is unavailable (503)").build());
        JsonNode on = getOk("/api/emails/delivery", admin);
        assertThat(on.get("configured").asBoolean()).isTrue();
        assertThat(on.at("/gmail/status").asText()).isEqualTo("CONNECTED");
        assertThat(on.at("/gmail/gmailAddress").asText()).isEqualTo("admin@gmail.com");
        assertThat(on.at("/gmail/lastSyncedAt").asText()).isEqualTo("2026-09-20T10:05:00Z");
        assertThat(on.at("/gmail/lastSyncError").asText()).isEqualTo("Gmail is unavailable (503)");
        JsonNode customers = getOk("/api/emails/delivery", acmeLogin);
        assertThat(customers.get("configured").asBoolean()).isTrue();
        assertThat(customers.get("gmail").isNull()).isTrue();
    }

    @Test
    void syncReadsTheCallersOwnMailbox() throws Exception {
        JsonNode off = read(mockMvc.perform(post("/api/emails/sync").with(as(admin))).andExpect(status().isOk()));
        assertThat(off.get("enabled").asBoolean()).isFalse();
        assertThat(off.get("error").asText()).isEqualTo("Email delivery is not configured (mail service)");
        assertThat(mailTransport.syncCalls()).isEmpty();

        mailTransport.mode(Mode.SUCCESS);
        JsonNode notConnected = read(mockMvc.perform(post("/api/emails/sync").with(as(admin))).andExpect(status().isOk()));
        assertThat(notConnected.get("enabled").asBoolean()).isFalse();
        assertThat(notConnected.get("error").asText()).isEqualTo("Gmail is not connected");

        mailTransport.hold(RecordingMailTransport.state(admin.getId(), "System Administrator",
                ConnectionStatus.CONNECTED, "admin@gmail.com", null));
        mailTransport.syncResult(new SyncResult(true, 3, 1, null));
        JsonNode synced = read(mockMvc.perform(post("/api/emails/sync").with(as(admin))).andExpect(status().isOk()));
        assertThat(synced.get("enabled").asBoolean()).isTrue();
        assertThat(synced.get("fetched").asInt()).isEqualTo(3);
        assertThat(synced.get("imported").asInt()).isEqualTo(1);
        assertThat(synced.get("error").isNull()).isTrue();
        assertThat(mailTransport.syncCalls()).containsOnly(admin.getId());
        assertThat(gmailConnectionRepository.findById(admin.getId())).get()
                .satisfies(c -> assertThat(c.getLastSyncedAt()).isEqualTo(Instant.parse("2026-09-20T10:05:00Z")));

        mockMvc.perform(post("/api/emails/sync").with(as(acmeLogin))).andExpect(status().isForbidden());
    }

    @Test
    void withoutTheMailServiceThereIsNoWebhook() throws Exception {
        mockMvc.perform(post("/api/mail-service/events").contentType("application/json").content("{\"events\":[]}"))
                .andExpect(status().isNotFound());
    }
}
