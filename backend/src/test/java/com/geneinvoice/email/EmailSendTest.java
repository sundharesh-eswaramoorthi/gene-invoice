package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.email.RecordingMailTransport.Mode;
import com.geneinvoice.email.transport.CopyRequest;
import com.geneinvoice.email.transport.Submission;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sending one email: who it reaches, how they got there, and who may send what (§5, §6). */
class EmailSendTest extends EmailTestBase {

    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired DisputeService disputeService;

    // ---- recipients --------------------------------------------------------------

    @Test
    void aPersonARoleAndTheCustomerAreStoredWithHowEachWasAdded() throws Exception {
        Invoice inv = invoice(acme, sales);
        seat(acme, PocType.COLLECTION, collections);

        JsonNode sent = send(admin, email("INVOICE", inv.getId(),
                List.of(toUser(success), toRole("COLLECTION_POC"), toCustomer()),
                "subject", "Invoice " + inv.getInvoiceNumber()));

        assertThat(sent.get("entityLabel").asText()).isEqualTo("Invoice " + inv.getInvoiceNumber());
        assertThat(sent.get("entityLink").asText()).isEqualTo("/invoices/" + inv.getId());
        assertThat(sent.get("direction").asText()).isEqualTo("OUTBOUND");
        assertThat(sent.at("/from/userId").asLong()).isEqualTo(admin.getId());
        assertThat(sent.at("/sentBy/userId").asLong()).isEqualTo(admin.getId());
        assertThat(sent.get("to")).hasSize(4);
        assertThat(sent.at("/to/1/name").asText()).isEqualTo("CARA.COLLECTIONS");
        assertThat(sent.at("/to/1/sources/0/type").asText()).isEqualTo("ROLE");
        assertThat(sent.at("/to/1/sources/0/level").asText()).isEqualTo("CUSTOMER");
        assertThat(sent.at("/to/1/sources/0/label").asText()).isEqualTo("Collection POC (customer)");
        assertThat(sent.get("unresolved")).isEmpty();

        List<EmailRecipient> stored = recipientsOf(sent.get("id").asLong());
        assertThat(stored).extracting(EmailRecipient::getAddress).containsExactly(
                "sue.success@test.local", "cara.collections@test.local", "ap@acme.test", "acme.login@test.local");
        assertThat(stored).extracting(EmailRecipient::getSources)
                .containsExactly("USER", "ROLE:CUSTOMER:COLLECTION_POC", "CUSTOMER", "CUSTOMER");
        assertThat(stored).extracting(EmailRecipient::getUserId)
                .containsExactly(success.getId(), collections.getId(), null, acmeLogin.getId());
        assertThat(stored).extracting(EmailRecipient::isInternal).containsExactly(true, true, false, false);
        assertThat(stored).extracting(EmailRecipient::getCustomerId)
                .containsExactly(null, null, acme.getId(), acme.getId());
        assertThat(stored).allSatisfy(r -> assertThat(r.getField()).isEqualTo(RecipientField.TO));
    }

    @Test
    void thePersonAddedDirectlyAndThroughARoleGetsItOnceWithBothReasons() throws Exception {
        Invoice inv = invoice(acme, sales);
        seat(acme, PocType.COLLECTION, collections);

        JsonNode sent = send(admin, email("INVOICE", inv.getId(),
                List.of(toUser(collections), toRole("COLLECTION_POC"), toRole("COLLECTION_POC"))));

        assertThat(sent.get("to")).hasSize(1);
        assertThat(recipientsOf(sent.get("id").asLong())).singleElement()
                .satisfies(r -> assertThat(r.getSources()).isEqualTo("USER,ROLE:CUSTOMER:COLLECTION_POC"));
    }

    @Test
    void aCustomerAddressThatIsAlsoALoginsAddressIsOneRecipientInThatLoginsInbox() throws Exception {
        Customer globex = customer("Globex Corp", "ap@globex.test");
        User globexLogin = userRepository.save(User.builder()
                .username("globex.login").email("AP@globex.test").fullName("Globex Corp")
                .password(passwordEncoder.encode("password")).role(role("CUSTOMER"))
                .customerId(globex.getId()).active(true).build());
        Invoice inv = invoice(globex, sales);

        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer())));

        assertThat(recipientsOf(sent.get("id").asLong())).singleElement().satisfies(r -> {
            assertThat(r.getUserId()).isEqualTo(globexLogin.getId());
            assertThat(r.getAddress()).isEqualTo("ap@globex.test");
            assertThat(r.getCustomerId()).isEqualTo(globex.getId());
        });
        assertThat(getOk("/api/inbox/unread-count", globexLogin).get("count").asLong()).isEqualTo(1);
    }

    @Test
    void aRoleIsLookedUpWhenSentSoWhoeverTakesItOverLaterNeverSeesTheOldEmail() throws Exception {
        Invoice inv = invoice(acme, sales);
        seat(acme, PocType.COLLECTION, collections);
        send(admin, email("INVOICE", inv.getId(), List.of(toRole("COLLECTION_POC"))));

        User cora = user("cora.collections", "COLLECTION_POC");
        seat(acme, PocType.COLLECTION, cora);

        assertThat(getOk("/api/inbox", cora).get("totalElements").asLong()).isZero();
        assertThat(getOk("/api/inbox", collections).get("totalElements").asLong()).isEqualTo(1);

        send(admin, email("INVOICE", inv.getId(), List.of(toRole("COLLECTION_POC"))));
        assertThat(getOk("/api/inbox", cora).get("totalElements").asLong()).isEqualTo(1);
        // Cara still holds a seat beside the new primary, so the role reaches her too (E3).
        assertThat(getOk("/api/inbox", collections).get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void anInactiveOrMissingRoleHolderIsRecordedAsUnresolved() throws Exception {
        Invoice inv = invoice(acme, sales);
        deactivate(sales);

        JsonNode sent = send(admin, email("INVOICE", inv.getId(),
                List.of(toRole("SALES_POC", "RECORD"), toRole("CUSTOMER_SUCCESS_POC"), toCustomer())));

        assertThat(sent.get("to")).hasSize(2);
        assertThat(sent.at("/unresolved/0/token").asText()).isEqualTo("ROLE:RECORD:SALES_POC");
        assertThat(sent.at("/unresolved/0/label").asText()).isEqualTo("Sales POC (this invoice)");
        assertThat(sent.at("/unresolved/0/reason").asText())
                .isEqualTo("Nobody holds Sales POC (this invoice) on Invoice " + inv.getInvoiceNumber());
        assertThat(sent.at("/unresolved/1/token").asText()).isEqualTo("ROLE:CUSTOMER:CUSTOMER_SUCCESS_POC");
        assertThat(sent.at("/unresolved/1/label").asText()).isEqualTo("Customer Success POC (customer)");
        assertThat(emailRepository.findById(sent.get("id").asLong()).orElseThrow().getUnresolved())
                .isEqualTo("ROLE:RECORD:SALES_POC,ROLE:CUSTOMER:CUSTOMER_SUCCESS_POC");
    }

    @Test
    void aPaymentsCollectionPocIsItsOwnFieldNotTheCustomersSeat() throws Exception {
        seat(acme, PocType.COLLECTION, collections);
        User cora = user("cora.collections", "COLLECTION_POC");
        actAs(admin);
        var payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("10.00"), "Cash", null, null, cora.getId(), null));

        JsonNode sent = send(admin, email("PAYMENT", payment.getId(),
                List.of(toRole("COLLECTION_POC", "RECORD"))));

        assertThat(sent.at("/to/0/userId").asLong()).isEqualTo(cora.getId());
        assertThat(sent.get("entityLabel").asText()).isEqualTo("Payment #" + payment.getId());
        assertThat(sent.at("/to/0/sources/0/label").asText()).isEqualTo("Collection POC (this payment)");
    }

    // ---- a role reaches every holder (E3) ------------------------------------------------

    @Test
    void aRoleInToReachesEveryActiveHolderOfTheSeatPrimaryFirst() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        Invoice inv = invoice(acme, sales);
        List<User> others = threeCollectionSeats(acme);
        User cora = others.get(0);
        User cody = others.get(1);

        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toRole("COLLECTION_POC"))));

        List<EmailRecipient> stored = recipientsOf(sent.get("id").asLong());
        assertThat(stored).extracting(EmailRecipient::getUserId).containsExactly(collections.getId(), cora.getId());
        assertThat(stored).extracting(EmailRecipient::getSources).containsOnly("ROLE:CUSTOMER:COLLECTION_POC");
        assertThat(sent.get("to")).extracting(p -> p.get("name").asText())
                .containsExactly("CARA.COLLECTIONS", "CORA.COLLECTIONS");
        assertThat(sent.get("unresolved")).isEmpty();
        // Each holder gets a copy of their own, each address once (M6).
        Submission staffOnly = mailTransport.submissions().get(0);
        assertThat(staffOnly.copies()).extracting(CopyRequest::address)
                .containsExactly("cara.collections@test.local", "cora.collections@test.local");
        assertThat(getOk("/api/inbox/unread-count", cora).get("count").asLong()).isEqualTo(1);

        // With the customer on it too, every copy still names only the one it goes to, so no
        // customer's copy shows a holder (the old E13 Bcc rule is not needed).
        send(admin, email("INVOICE", inv.getId(), List.of(toCustomer(), toRole("COLLECTION_POC"))));
        Submission withCustomer = mailTransport.submissions().get(1);
        assertThat(withCustomer.copies()).extracting(CopyRequest::address).containsExactly(
                "ap@acme.test", "acme.login@test.local", "cara.collections@test.local", "cora.collections@test.local");

        // An inactive holder holds nothing.
        assertThat(emailRecipientRepository.findAll()).extracting(EmailRecipient::getUserId)
                .doesNotContain(cody.getId());
    }

    @Test
    void aHolderAlsoAddedDirectlyIsOneRecipientWithBothReasons() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        Invoice inv = invoice(acme, sales);
        User cora = threeCollectionSeats(acme).get(0);

        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toUser(cora), toRole("COLLECTION_POC"))));

        List<EmailRecipient> stored = recipientsOf(sent.get("id").asLong());
        assertThat(stored).extracting(EmailRecipient::getUserId).containsExactly(cora.getId(), collections.getId());
        assertThat(stored).extracting(EmailRecipient::getSources)
                .containsExactly("USER,ROLE:CUSTOMER:COLLECTION_POC", "ROLE:CUSTOMER:COLLECTION_POC");
        assertThat(mailTransport.copiesHandedOver()).extracting(CopyRequest::address)
                .containsExactly("cora.collections@test.local", "cara.collections@test.local");
    }

    @Test
    void aSenderRoleSendsAsItsFirstHolderWhileToReachesThemAll() throws Exception {
        Invoice inv = invoice(acme, sales);
        List<User> others = threeCollectionSeats(acme);
        User cora = others.get(0);
        User cody = others.get(1);

        JsonNode fromPrimary = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "from", toRole("COLLECTION_POC")));
        assertThat(fromPrimary.at("/from/userId").asLong()).isEqualTo(collections.getId());
        assertThat(fromPrimary.get("fromRole").asText()).isEqualTo("COLLECTION_POC");

        // With the primary gone the next active holder sends — whom new records default to as well —
        // while To still reaches every active holder.
        reactivate(cody);
        deactivate(collections);
        assertThat(pocService.defaultAssignee(acme.getId(), PocType.COLLECTION)).get()
                .extracting(User::getId).isEqualTo(cora.getId());
        JsonNode fromNext = send(admin, email("INVOICE", inv.getId(), List.of(toRole("COLLECTION_POC")),
                "from", toRole("COLLECTION_POC")));
        assertThat(fromNext.at("/from/userId").asLong()).isEqualTo(cora.getId());
        assertThat(recipientsOf(fromNext.get("id").asLong())).extracting(EmailRecipient::getUserId)
                .containsExactly(cora.getId(), cody.getId());
    }

    @Test
    void whenNobodyActiveHoldsTheSeatTheRoleIsUnresolvedAsBefore() throws Exception {
        Invoice inv = invoice(acme, sales);
        User cora = threeCollectionSeats(acme).get(0);
        deactivate(collections);
        deactivate(cora);

        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toRole("COLLECTION_POC"), toCustomer())));
        assertThat(recipientsOf(sent.get("id").asLong())).extracting(EmailRecipient::getSources).containsOnly("CUSTOMER");
        assertThat(sent.at("/unresolved/0/token").asText()).isEqualTo("ROLE:CUSTOMER:COLLECTION_POC");
        assertThat(emailRepository.findById(sent.get("id").asLong()).orElseThrow().getUnresolved())
                .isEqualTo("ROLE:CUSTOMER:COLLECTION_POC");

        postJson("/api/emails", admin, email("INVOICE", inv.getId(), List.of(toRole("COLLECTION_POC"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No recipients: Collection POC (customer) is not assigned"));
        postJson("/api/emails", admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                        "from", toRole("COLLECTION_POC")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Nobody holds Collection POC (customer) on Invoice "
                        + inv.getInvoiceNumber() + ", so it cannot be the sender"));
        assertThat(emailRepository.count()).isEqualTo(1);
    }

    // ---- the two levels (L2, L3) -----------------------------------------------------

    @Test
    void aRecordsOwnPocStillReachesOnlyThatPerson() throws Exception {
        Invoice samsInvoice = invoice(acme, sales);
        invoice(acme, otherSales);
        threeCollectionSeats(acme);
        User cole = user("cole.collections", "COLLECTION_POC");
        actAs(admin);
        var payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("10.00"), "Cash", null, null, cole.getId(), null));
        PromiseDtos.PromiseDto promise = promiseService.create(new PromiseDtos.CreatePromiseRequest(
                acme.getId(), new BigDecimal("500.00"), LocalDate.of(2026, 10, 1), cole.getId(), null, null, null));

        // Not every Sales POC on the customer's invoices: this invoice's.
        JsonNode aboutInvoice = send(admin, email("INVOICE", samsInvoice.getId(),
                List.of(toRole("SALES_POC", "RECORD"))));
        assertThat(recipientsOf(aboutInvoice.get("id").asLong())).extracting(EmailRecipient::getUserId)
                .containsExactly(sales.getId());

        // The payment's and the promise's own Collection POC, not the customer's seats.
        JsonNode aboutPayment = send(admin, email("PAYMENT", payment.getId(),
                List.of(toRole("COLLECTION_POC", "RECORD")), "from", toRole("COLLECTION_POC", "RECORD")));
        assertThat(recipientsOf(aboutPayment.get("id").asLong())).extracting(EmailRecipient::getUserId)
                .containsExactly(cole.getId());
        assertThat(aboutPayment.at("/from/userId").asLong()).isEqualTo(cole.getId());
        JsonNode aboutPromise = send(admin, email("PROMISE", promise.id(),
                List.of(toRole("COLLECTION_POC", "RECORD"))));
        assertThat(recipientsOf(aboutPromise.get("id").asLong())).extracting(EmailRecipient::getUserId)
                .containsExactly(cole.getId());
    }

    @Test
    void bothLevelsAreOfferedOnEveryKindThatHasThem() throws Exception {
        Invoice inv = invoice(acme, sales);
        User cora = threeCollectionSeats(acme).get(0);
        User cole = user("cole.collections", "COLLECTION_POC");
        actAs(admin);
        var payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("10.00"), "Cash", null, null, cole.getId(), null));
        PromiseDtos.PromiseDto promise = promiseService.create(new PromiseDtos.CreatePromiseRequest(
                acme.getId(), new BigDecimal("500.00"), LocalDate.of(2026, 10, 1), cole.getId(), null, null, null));

        // An invoice offers the customer's two seats and its own Sales POC. The Sales POC is the
        // record's alone: a customer cannot hold that seat, so the customer level never had one
        // to answer with and no longer claims it (CP-01).
        JsonNode aboutInvoice = send(admin, email("INVOICE", inv.getId(),
                List.of(toRole("COLLECTION_POC", "CUSTOMER"), toRole("SALES_POC", "RECORD"))));
        assertThat(recipientsOf(aboutInvoice.get("id").asLong())).extracting(EmailRecipient::getUserId)
                .containsExactly(collections.getId(), cora.getId(), sales.getId());
        assertThat(recipientsOf(aboutInvoice.get("id").asLong())).extracting(EmailRecipient::getSources)
                .containsExactly("ROLE:CUSTOMER:COLLECTION_POC", "ROLE:CUSTOMER:COLLECTION_POC",
                        "ROLE:RECORD:SALES_POC");
        postJson("/api/emails", admin, email("INVOICE", inv.getId(), List.of(toRole("SALES_POC", "CUSTOMER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Sales POC (customer) is not a role on invoices"));

        JsonNode aboutPayment = send(admin, email("PAYMENT", payment.getId(),
                List.of(toRole("COLLECTION_POC", "CUSTOMER"), toRole("COLLECTION_POC", "RECORD"))));
        assertThat(recipientsOf(aboutPayment.get("id").asLong())).extracting(EmailRecipient::getUserId)
                .containsExactly(collections.getId(), cora.getId(), cole.getId());

        JsonNode aboutPromise = send(admin, email("PROMISE", promise.id(),
                List.of(toRole("COLLECTION_POC", "CUSTOMER"), toRole("COLLECTION_POC", "RECORD"))));
        assertThat(recipientsOf(aboutPromise.get("id").asLong())).extracting(EmailRecipient::getUserId)
                .containsExactly(collections.getId(), cora.getId(), cole.getId());

        // A customer's own record has the book but no POC field of its own, so record level is refused.
        JsonNode aboutCustomer = send(admin, email("CUSTOMER", acme.getId(),
                List.of(toRole("COLLECTION_POC", "CUSTOMER"))));
        assertThat(recipientsOf(aboutCustomer.get("id").asLong())).extracting(EmailRecipient::getUserId)
                .containsExactly(collections.getId(), cora.getId());
        postJson("/api/emails", admin, email("CUSTOMER", acme.getId(), List.of(toRole("SALES_POC", "RECORD"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Sales POC (this customer) is not a role on customers"));
    }

    @Test
    void someoneAtBothLevelsIsOneRecipientThatKeepsBothWaysIn() throws Exception {
        // The customer's Collection seat holder is this payment's own Collection POC as well.
        seat(acme, PocType.COLLECTION, collections);
        actAs(admin);
        var payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("10.00"), "Cash", null, null, collections.getId(), null));

        JsonNode sent = send(admin, email("PAYMENT", payment.getId(),
                List.of(toRole("COLLECTION_POC", "CUSTOMER"), toRole("COLLECTION_POC", "RECORD"))));

        assertThat(sent.get("to")).hasSize(1);
        assertThat(sent.at("/to/0/sources")).extracting(s -> s.get("label").asText())
                .containsExactly("Collection POC (customer)", "Collection POC (this payment)");
        assertThat(recipientsOf(sent.get("id").asLong())).singleElement().satisfies(r -> {
            assertThat(r.getUserId()).isEqualTo(collections.getId());
            assertThat(r.getSources()).isEqualTo("ROLE:CUSTOMER:COLLECTION_POC,ROLE:RECORD:COLLECTION_POC");
        });
    }

    @Test
    void aSenderRoleTakesOnePersonFromWhicheverLevelItNames() throws Exception {
        Invoice inv = invoice(acme, sales);
        User cora = threeCollectionSeats(acme).get(0);

        assertThat(send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "from", toRole("SALES_POC", "RECORD"))).at("/from/userId").asLong()).isEqualTo(sales.getId());
        // At customer level the primary sends, and once they are gone the next active holder does.
        assertThat(send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "from", toRole("COLLECTION_POC", "CUSTOMER"))).at("/from/userId").asLong())
                .isEqualTo(collections.getId());
        deactivate(collections);
        JsonNode next = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "from", toRole("COLLECTION_POC", "CUSTOMER")));
        assertThat(next.at("/from/userId").asLong()).isEqualTo(cora.getId());
        assertThat(next.get("fromRoleLevel").asText()).isEqualTo("CUSTOMER");
        assertThat(next.get("fromRoleLabel").asText()).isEqualTo("Collection POC (customer)");
    }

    @Test
    void aTokenWithoutALevelMeansTheCustomersBookWhereTheKindHasOneAndTheRecordOtherwise() throws Exception {
        Invoice inv = invoice(acme, sales);
        seat(acme, PocType.COLLECTION, collections);

        // The Collection POC is a seat the customer holds, so a level-less token is that seat; the
        // Sales POC is not, so a level-less one is the invoice's own field (L7, CP-01).
        JsonNode levelless = send(admin, email("INVOICE", inv.getId(),
                List.of(toRole("SALES_POC"), toRole("COLLECTION_POC"))));
        assertThat(recipientsOf(levelless.get("id").asLong())).extracting(EmailRecipient::getUserId)
                .containsExactly(sales.getId(), collections.getId());
        assertThat(recipientsOf(levelless.get("id").asLong())).extracting(EmailRecipient::getSources)
                .containsExactly("ROLE:RECORD:SALES_POC", "ROLE:CUSTOMER:COLLECTION_POC");
        assertThat(send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()), "from", toRole("SALES_POC")))
                .at("/from/userId").asLong()).isEqualTo(sales.getId());

        // Anything else is a 400, whatever else the token says.
        postJson("/api/emails", admin, email("INVOICE", inv.getId(),
                        List.of(Map.of("type", "ROLE", "role", "SALES_POC", "level", "INVOICE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("level must be CUSTOMER or RECORD"));
    }

    @Test
    void emailWrittenBeforeLevelsExistedNamesItsRolesWithoutOne() throws Exception {
        Invoice inv = invoice(acme, sales);
        Email old = emailRepository.save(Email.builder()
                .entityType(EmailEntityType.INVOICE)
                .entityId(inv.getId())
                .entityLabel("Invoice " + inv.getInvoiceNumber())
                .direction(EmailDirection.OUTBOUND)
                .status(EmailStatus.SENT)
                .subject("Before levels")
                // A sender role and an unresolved role, both written without one.
                .fromUserId(sales.getId())
                .fromRole(EmailRole.SALES_POC)
                .fromName("SAM.SALES")
                .fromAddress("sam.sales@test.local")
                .fromInternal(true)
                .sentByUserId(admin.getId())
                .unresolved("ROLE:COLLECTION_POC")
                .build());
        emailRecipientRepository.save(EmailRecipient.builder()
                .email(old)
                .field(RecipientField.TO)
                .userId(sales.getId())
                .name("SAM.SALES")
                .address("sam.sales@test.local")
                .internal(true)
                // What it meant then: the invoice's own Sales POC, not a seat in the customer's book.
                .sources("ROLE:SALES_POC")
                .build());
        // The customer was on it too, so they can open it and see how the staff recipient reads.
        emailRecipientRepository.save(EmailRecipient.builder()
                .email(old)
                .field(RecipientField.TO)
                .customerId(acme.getId())
                .name("Acme Ltd")
                .address("ap@acme.test")
                .sources("CUSTOMER")
                .build());

        JsonNode shown = getOk("/api/emails/" + old.getId(), admin);

        // No level was stored, so none is claimed: the role reads as it read when it was written (L7).
        assertThat(shown.get("fromRoleLevel").isNull()).isTrue();
        assertThat(shown.get("fromRoleLabel").asText()).isEqualTo("Sales POC");
        assertThat(shown.at("/from/sources/0").has("level")).isFalse();
        assertThat(shown.at("/to/0/sources/0").has("level")).isFalse();
        assertThat(shown.at("/to/0/sources/0/label").asText()).isEqualTo("Sales POC");
        assertThat(shown.at("/unresolved/0/token").asText()).isEqualTo("ROLE:COLLECTION_POC");
        assertThat(shown.at("/unresolved/0/label").asText()).isEqualTo("Collection POC");
        assertThat(shown.at("/unresolved/0/reason").asText())
                .isEqualTo("Nobody holds Collection POC on Invoice " + inv.getInvoiceNumber());

        // The customer sees staff as the role they were reached by (E13); that names no level either.
        assertThat(getOk("/api/emails/" + old.getId(), acmeLogin).at("/to/0/name").asText())
                .isEqualTo("Sales POC");
    }

    @Test
    void aDisputeTakesItsRecordLevelFromItsTargetAndOffersTheOtherRoleUnheld() throws Exception {
        Invoice inv = invoice(acme, sales);
        User cora = threeCollectionSeats(acme).get(0);
        User cole = user("cole.collections", "COLLECTION_POC");
        seat(acme, PocType.SUCCESS, success);
        actAs(admin);
        var payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("10.00"), "Cash", null, null, cole.getId(), null));
        actAs(acmeLogin);
        Dispute onInvoice = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.INVOICE, inv.getId(), "Wrong quantity", null));
        Dispute onPayment = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.PAYMENT, payment.getId(), "Not ours", null));
        actAs(admin);

        // An invoice target names a Sales POC and no Collection POC, so record level answers for one
        // of the two and the customer's seats for the rest.
        JsonNode invoiceDispute = send(admin, email("DISPUTE", onInvoice.getId(),
                List.of(toRole("SALES_POC", "RECORD"), toRole("COLLECTION_POC", "RECORD"),
                        toRole("COLLECTION_POC", "CUSTOMER"), toRole("CUSTOMER_SUCCESS_POC", "CUSTOMER"))));
        assertThat(recipientsOf(invoiceDispute.get("id").asLong())).extracting(EmailRecipient::getUserId)
                .containsExactly(sales.getId(), collections.getId(), cora.getId(), success.getId());
        assertThat(invoiceDispute.get("unresolved")).singleElement().satisfies(u -> {
            assertThat(u.get("token").asText()).isEqualTo("ROLE:RECORD:COLLECTION_POC");
            assertThat(u.get("label").asText()).isEqualTo("Collection POC (this dispute)");
        });

        // A payment target is the other way round.
        JsonNode paymentDispute = send(admin, email("DISPUTE", onPayment.getId(),
                List.of(toRole("COLLECTION_POC", "RECORD"), toRole("SALES_POC", "RECORD"), toCustomer())));
        assertThat(recipientsOf(paymentDispute.get("id").asLong())).extracting(EmailRecipient::getUserId)
                .containsExactly(cole.getId(), null, acmeLogin.getId());
        assertThat(paymentDispute.at("/unresolved/0/token").asText()).isEqualTo("ROLE:RECORD:SALES_POC");
    }

    // ---- sender ------------------------------------------------------------------

    @Test
    void aSenderRoleSendsAsWhoeverHoldsIt() throws Exception {
        Invoice inv = invoice(acme, sales);

        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "from", toRole("SALES_POC", "RECORD")));

        assertThat(sent.at("/from/userId").asLong()).isEqualTo(sales.getId());
        assertThat(sent.at("/from/name").asText()).isEqualTo("SAM.SALES");
        assertThat(sent.get("fromRole").asText()).isEqualTo("SALES_POC");
        assertThat(sent.get("fromRoleLevel").asText()).isEqualTo("RECORD");
        assertThat(sent.get("fromRoleLabel").asText()).isEqualTo("Sales POC (this invoice)");
        assertThat(sent.at("/sentBy/userId").asLong()).isEqualTo(admin.getId());
    }

    @Test
    void aSenderRoleNobodyHoldsIsRefused() throws Exception {
        Invoice inv = invoice(acme, sales);

        postJson("/api/emails", admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                        "from", toRole("COLLECTION_POC")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Nobody holds Collection POC (customer) on Invoice "
                        + inv.getInvoiceNumber() + ", so it cannot be the sender"));
        assertThat(emailRepository.count()).isZero();
    }

    @Test
    void aNamedSenderMustBeAnActiveInternalUser() throws Exception {
        Invoice inv = invoice(acme, sales);
        deactivate(success);

        postJson("/api/emails", admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                        "from", toUser(success)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("sue.success is not an active internal user"));
        postJson("/api/emails", admin, email("INVOICE", inv.getId(), List.of(toUser(acmeLogin))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("acme.login is not an active internal user"));
    }

    // ---- content and validation ------------------------------------------------------

    @Test
    void theSubjectIsRequiredAndTheBodyIsOptional() throws Exception {
        Invoice inv = invoice(acme, sales);

        postJson("/api/emails", admin, email("INVOICE", inv.getId(), List.of(toCustomer()), "subject", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.subject").value("must not be blank"));
        postJson("/api/emails", admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                        "subject", "x".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.subject").value("must be at most 500 characters"));

        Map<String, Object> noBody = email("INVOICE", inv.getId(), List.of(toCustomer()),
                "subject", "  Your invoice\r\nis ready  ");
        noBody.remove("body");
        JsonNode sent = send(admin, noBody);
        assertThat(sent.get("subject").asText()).isEqualTo("Your invoice is ready");
        assertThat(sent.get("body").asText()).isEmpty();
    }

    @Test
    void aNulPostgresCannotStoreIsLeftOutOfTheSubjectAndBody() throws Exception {
        Invoice inv = invoice(acme, sales);
        String nul = String.valueOf((char) 0);

        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "subject", "Invoice" + nul + " due", "body", "Please" + nul + " pay"));

        Email stored = emailRepository.findById(sent.get("id").asLong()).orElseThrow();
        assertThat(stored.getSubject()).isEqualTo("Invoice due");
        assertThat(stored.getBody()).isEqualTo("Please pay");
    }

    @Test
    void anEmptyToIsAFieldErrorAndToNobodyAfterResolutionIsRefused() throws Exception {
        Customer bare = customer("Bare Ltd");
        Invoice inv = invoice(bare, sales);

        postJson("/api/emails", admin, email("INVOICE", inv.getId(), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.to").value("Add at least one recipient"));
        postJson("/api/emails", admin, email("INVOICE", inv.getId(),
                        List.of(toRole("COLLECTION_POC"), toCustomer())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "No recipients: Collection POC (customer) is not assigned and the customer has no email address"));
        assertThat(emailRepository.count()).isZero();
    }

    @Test
    void tokensThatCannotApplyToTheRecordKindAreRefused() throws Exception {
        actAs(admin);
        var payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("10.00"), "Cash", null, null, collections.getId(), null));

        postJson("/api/emails", admin, email("PAYMENT", payment.getId(), List.of(toRole("SALES_POC", "RECORD"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Sales POC (this payment) is not a role on payments"));
        postJson("/api/emails", admin, email("PRODUCT", widget.getId(), List.of(toCustomer())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Customer emails do not apply to products"));
        postJson("/api/emails", admin, email("PAYMENT", payment.getId(), List.of(Map.of("type", "TEAM"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("to type must be one of")));
        postJson("/api/emails", admin, email("NOTIFICATION", 1L, List.of(toCustomer())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("entityType must be one of")));
    }

    @Test
    void aRoleIsRefusedOnAUserWhoIsNotACustomerLogin() throws Exception {
        seat(acme, PocType.COLLECTION, collections);

        // No seat applies to someone who belongs to no customer, so the role is not offered on them at all.
        for (String path : List.of("/api/emails", "/api/emails/preview")) {
            postJson(path, admin, email("USER", sales.getId(), List.of(toRole("COLLECTION_POC"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Collection POC (customer) is not a role on users"));
            postJson(path, admin, email("USER", sales.getId(), List.of(toUser(collections)),
                            "from", toRole("CUSTOMER_SUCCESS_POC")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Customer Success POC (customer) is not a role on users"));
            // A user's record stores no POC of its own, so there is no record level to ask for either.
            postJson(path, admin, email("USER", acmeLogin.getId(), List.of(toRole("COLLECTION_POC", "RECORD"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Collection POC (this user) is not a role on users"));
        }
        assertThat(emailRepository.count()).isZero();

        // A customer login's customer has seats.
        JsonNode sent = send(admin, email("USER", acmeLogin.getId(), List.of(toRole("COLLECTION_POC"))));
        assertThat(recipientsOf(sent.get("id").asLong())).extracting(EmailRecipient::getUserId)
                .containsExactly(collections.getId());
    }

    // ---- privileges ------------------------------------------------------------------

    @Test
    void sendingNeedsEmailSend() throws Exception {
        Invoice inv = invoice(acme, sales);
        User viewer = user("vic.viewer", "VIEWER");

        postJson("/api/emails", viewer, email("INVOICE", inv.getId(), List.of(toCustomer())))
                .andExpect(status().isForbidden());
    }

    @Test
    void sendingNeedsTheRecordsViewPrivilege() throws Exception {
        User cashier = userRepository.findByUsername("cashier").orElseThrow();
        Long viewerRole = role("VIEWER").getId();

        postJson("/api/emails", cashier, email("ROLE", viewerRole, List.of(toUser(collections))))
                .andExpect(status().isForbidden());
        send(admin, email("ROLE", viewerRole, List.of(toUser(collections))));
    }

    @Test
    void aSalesPocCannotEmailAboutAnInvoiceOutsideTheirBook() throws Exception {
        Invoice theirs = invoice(acme, otherSales);

        postJson("/api/emails", sales, email("INVOICE", theirs.getId(), List.of(toCustomer())))
                .andExpect(status().isNotFound());
        send(otherSales, email("INVOICE", theirs.getId(), List.of(toCustomer())));
    }

    @Test
    void aCustomerLoginCannotEmailAboutKindsOfRecordItCannotRead() throws Exception {
        postJson("/api/emails", acmeLogin, email("PRODUCT", widget.getId(), List.of(toRole("SALES_POC"))))
                .andExpect(status().isForbidden());
    }
}
