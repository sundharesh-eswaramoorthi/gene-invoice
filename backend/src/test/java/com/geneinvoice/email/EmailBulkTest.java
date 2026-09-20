package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.email.RecordingMailTransport.Mode;
import com.geneinvoice.email.transport.CopyRequest;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A bulk send is a separate email per record, each addressed on its own record (§6 POST /api/emails/bulk). */
class EmailBulkTest extends EmailTestBase {

    @Autowired PaymentService paymentService;
    @Autowired DisputeService disputeService;

    private static Map<String, Object> bulk(Object... kv) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "SEND_EMAIL");
        for (int i = 0; i < kv.length; i += 2) body.put((String) kv[i], kv[i + 1]);
        return body;
    }

    private static Map<String, Object> params(String entityType, List<?> to, Object... kv) {
        Map<String, Object> params = new HashMap<>();
        params.put("entityType", entityType);
        params.put("to", to);
        params.put("subject", "Your invoice");
        params.put("body", "Hello");
        for (int i = 0; i < kv.length; i += 2) params.put((String) kv[i], kv[i + 1]);
        return params;
    }

    private JsonNode run(com.geneinvoice.user.User caller, Map<String, Object> body) throws Exception {
        return read(postJson("/api/emails/bulk", caller, body).andExpect(status().isOk()));
    }

    private List<Email> emailsNewestLast() {
        return emailRepository.findAll().stream().sorted(Comparator.comparing(Email::getId)).toList();
    }

    @Test
    void eachRecordGetsItsOwnEmailToItsOwnRoleHolder() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        Invoice samsInvoice = invoice(acme, sales);
        Invoice sidsInvoice = invoice(acme, otherSales);

        JsonNode result = run(admin, bulk("ids", List.of(samsInvoice.getId(), sidsInvoice.getId()),
                "params", params("INVOICE", List.of(toRole("SALES_POC", "RECORD")))));

        assertThat(result.get("requested").asInt()).isEqualTo(2);
        assertThat(result.get("succeeded")).hasSize(2);
        List<Email> emails = emailsNewestLast();
        assertThat(emails).extracting(Email::getEntityId).containsExactly(samsInvoice.getId(), sidsInvoice.getId());
        assertThat(recipientsOf(emails.get(0).getId())).extracting(EmailRecipient::getUserId).containsExactly(sales.getId());
        assertThat(recipientsOf(emails.get(1).getId())).extracting(EmailRecipient::getUserId).containsExactly(otherSales.getId());
        assertThat(emails).extracting(Email::getBatchId).doesNotContainNull().containsOnly(emails.get(0).getBatchId());
        // Handed over as the request finished (tests switch the background thread off), one hand-off per email.
        assertThat(emails).extracting(Email::getStatus).containsOnly(EmailStatus.QUEUED);
        assertThat(emails).extracting(Email::getHandedOffAt).doesNotContainNull();
        assertThat(mailTransport.submissions()).extracting(s -> s.groupRef())
                .containsExactly(String.valueOf(emails.get(0).getId()), String.valueOf(emails.get(1).getId()));
    }

    @Test
    void eachRecordReachesEveryHolderOfItsOwnCustomersSeat() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        Customer globex = customer("Globex Corp", "ap@globex.test");
        User cora = user("cora.collections", DataSeeder.ROLE_COLLECTION_POC);
        User cody = user("cody.collections", DataSeeder.ROLE_COLLECTION_POC);
        seat(acme, PocType.COLLECTION, collections);
        extraSeat(acme, PocType.COLLECTION, cora);
        seat(globex, PocType.COLLECTION, cody);
        extraSeat(globex, PocType.COLLECTION, collections);
        Invoice acmes = invoice(acme, sales);
        Invoice globexs = invoice(globex, sales);

        JsonNode result = run(admin, bulk("ids", List.of(acmes.getId(), globexs.getId()),
                "params", params("INVOICE", List.of(toRole("COLLECTION_POC")), "from", toRole("COLLECTION_POC"))));

        assertThat(result.get("succeeded")).hasSize(2);
        List<Email> emails = emailsNewestLast();
        assertThat(emails).extracting(Email::getEntityId).containsExactly(acmes.getId(), globexs.getId());
        // Each record's sender is its own customer's primary, and its To every holder there.
        assertThat(emails).extracting(Email::getFromUserId).containsExactly(collections.getId(), cody.getId());
        assertThat(recipientsOf(emails.get(0).getId())).extracting(EmailRecipient::getUserId)
                .containsExactly(collections.getId(), cora.getId());
        assertThat(recipientsOf(emails.get(1).getId())).extracting(EmailRecipient::getUserId)
                .containsExactly(cody.getId(), collections.getId());
        assertThat(mailTransport.submissions()).extracting(s -> s.copies().stream().map(CopyRequest::address).toList())
                .containsExactly(
                        List.of("cara.collections@test.local", "cora.collections@test.local"),
                        List.of("cody.collections@test.local", "cara.collections@test.local"));
    }

    /**
     * With no record yet, a kind offers the union of the record-level roles its records can store
     * (L3), and each row resolves whichever of them it actually has.
     */
    @Test
    void aKindOffersEveryRecordLevelRoleItsRecordsCanStoreAndEachRowResolvesItsOwn() throws Exception {
        Invoice inv = invoice(acme, sales);
        User cole = user("cole.collections", DataSeeder.ROLE_COLLECTION_POC);
        actAs(admin);
        var payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("10.00"), "Cash", null, null, cole.getId(), null));
        actAs(acmeLogin);
        var onInvoice = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.INVOICE, inv.getId(), "Wrong quantity", null));
        var onPayment = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.PAYMENT, payment.getId(), "Not ours", null));

        JsonNode result = run(admin, bulk("ids", List.of(onInvoice.getId(), onPayment.getId()),
                "params", params("DISPUTE",
                        List.of(toRole("SALES_POC", "RECORD"), toRole("COLLECTION_POC", "RECORD")))));

        assertThat(result.get("succeeded")).hasSize(2);
        List<Email> emails = emailsNewestLast();
        assertThat(recipientsOf(emails.get(0).getId())).extracting(EmailRecipient::getUserId)
                .containsExactly(sales.getId());
        assertThat(recipientsOf(emails.get(1).getId())).extracting(EmailRecipient::getUserId)
                .containsExactly(cole.getId());
        // The half each row does not have is unresolved on it, as it is on a single send (L4).
        assertThat(emails).extracting(Email::getUnresolved)
                .containsExactly("ROLE:RECORD:COLLECTION_POC", "ROLE:RECORD:SALES_POC");

        // A kind that cannot store the role at all refuses it up front.
        postJson("/api/emails/bulk", admin, bulk("ids", List.of(payment.getId()),
                        "params", params("PAYMENT", List.of(toRole("SALES_POC", "RECORD")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Sales POC (this payment) is not a role on payments"));
    }

    @Test
    void selectAllSendsToEveryRecordMatchingTheFilterAndNoOthers() throws Exception {
        Customer globex = customer("Globex Corp", "ap@globex.test");
        for (int i = 0; i < 3; i++) invoice(acme, sales);
        Invoice elsewhere = invoice(globex, sales);

        JsonNode result = run(admin, bulk("selectAllMatchingFilter", true,
                "filters", List.of("customerId:eq:" + acme.getId()),
                "params", params("INVOICE", List.of(toCustomer()))));

        assertThat(result.get("succeeded")).hasSize(3);
        assertThat(emailRepository.findAll()).hasSize(3)
                .noneMatch(e -> e.getEntityId().equals(elsewhere.getId()));
        assertThat(emailRecipientRepository.findAll()).extracting(EmailRecipient::getCustomerId).containsOnly(acme.getId());
    }

    @Test
    void aRecordNobodyCanBeReachedOnIsSkippedWithTheReason() throws Exception {
        Invoice reachable = invoice(acme, sales);
        Invoice orphaned = invoice(acme, otherSales);
        deactivate(otherSales);

        JsonNode result = run(admin, bulk("ids", List.of(reachable.getId(), orphaned.getId()),
                "params", params("INVOICE", List.of(toRole("SALES_POC", "RECORD")))));

        assertThat(result.get("succeeded")).extracting(JsonNode::asLong).containsExactly(reachable.getId());
        assertThat(result.at("/skipped/0/id").asLong()).isEqualTo(orphaned.getId());
        assertThat(result.at("/skipped/0/reason").asText())
                .isEqualTo("No recipients: Sales POC (this invoice) is not assigned");
        assertThat(emailRepository.findAll()).hasSize(1);
    }

    @Test
    void aSenderRoleNobodyHoldsSkipsThatRecordOnly() throws Exception {
        Invoice reachable = invoice(acme, sales);
        Invoice orphaned = invoice(acme, otherSales);
        deactivate(otherSales);

        JsonNode result = run(admin, bulk("ids", List.of(reachable.getId(), orphaned.getId()),
                "params", params("INVOICE", List.of(toCustomer()), "from", toRole("SALES_POC", "RECORD"))));

        assertThat(result.get("succeeded")).hasSize(1);
        assertThat(result.at("/skipped/0/reason").asText()).isEqualTo(
                "Nobody holds Sales POC (this invoice) on Invoice " + orphaned.getInvoiceNumber()
                        + ", so it cannot be the sender");
        assertThat(emailRepository.findAll()).singleElement()
                .satisfies(e -> assertThat(e.getFromUserId()).isEqualTo(sales.getId()));
    }

    @Test
    void recordsTheCallerCannotReachAreSkippedNotEmailed() throws Exception {
        Invoice mine = invoice(acme, sales);
        Invoice theirs = invoice(acme, otherSales);

        JsonNode result = run(sales, bulk("ids", List.of(mine.getId(), theirs.getId(), 99999999L),
                "params", params("INVOICE", List.of(toCustomer()))));

        assertThat(result.get("requested").asInt()).isEqualTo(3);
        assertThat(result.get("succeeded")).extracting(JsonNode::asLong).containsExactly(mine.getId());
        assertThat(result.get("skipped")).extracting(s -> s.get("id").asLong())
                .containsExactly(theirs.getId(), 99999999L);
        assertThat(result.get("skipped")).extracting(s -> s.get("reason").asText())
                .containsOnly(BulkExecutor.NOT_REACHABLE);
        assertThat(emailRepository.findAll()).extracting(Email::getEntityId).containsExactly(mine.getId());
    }

    @Test
    void theRequestIsCheckedOnceUpFront() throws Exception {
        Invoice inv = invoice(acme, sales);
        List<Long> ids = List.of(inv.getId());

        postJson("/api/emails/bulk", admin, bulk("ids", ids,
                        "params", params("INVOICE", List.of(toCustomer()), "subject", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.subject").value("must not be blank"));
        postJson("/api/emails/bulk", admin, bulk("ids", ids, "params", params("INVOICE", List.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.to").value("Add at least one recipient"));
        postJson("/api/emails/bulk", admin, bulk("ids", ids, "params", params("INVOICES", List.of(toCustomer()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("entityType must be one of")));
        postJson("/api/emails/bulk", admin, bulk("ids", ids,
                        "params", params("INVOICE", List.of(toUser(acmeLogin)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("acme.login is not an active internal user"));
        postJson("/api/emails/bulk", admin, bulk("ids", ids,
                        "params", params("INVOICE", List.of(toCustomer()), "to", "everyone")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("params.to is not valid"));
        postJson("/api/emails/bulk", admin, bulk("params", params("INVOICE", List.of(toCustomer()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Provide ids or set selectAllMatchingFilter"));
        Map<String, Object> wrongAction = bulk("ids", ids, "params", params("INVOICE", List.of(toCustomer())));
        wrongAction.put("action", "CANCEL");
        postJson("/api/emails/bulk", admin, wrongAction)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Unknown bulk action")));
        postJson("/api/emails/bulk", user("vic.viewer", "VIEWER"),
                        bulk("ids", ids, "params", params("INVOICE", List.of(toCustomer()))))
                .andExpect(status().isForbidden());
        assertThat(emailRepository.count()).isZero();
    }

    @Test
    void customersAndUsersAreEmailedThroughTheirOwnListsScope() throws Exception {
        Customer globex = customer("Globex Corp", "ap@globex.test");

        JsonNode result = run(admin, bulk("ids", List.of(acme.getId(), globex.getId()),
                "params", params("CUSTOMER", List.of(toCustomer()))));
        assertThat(result.get("succeeded")).hasSize(2);

        // A customer login reaches only its own customer.
        JsonNode own = run(acmeLogin, bulk("ids", List.of(acme.getId(), globex.getId()),
                "params", params("CUSTOMER", List.of(toCustomer()))));
        assertThat(own.get("succeeded")).extracting(JsonNode::asLong).containsExactly(acme.getId());

        JsonNode users = run(admin, bulk("ids", List.of(collections.getId(), acmeLogin.getId()),
                "params", params("USER", List.of(toUser(admin)), "subject", "Hello")));
        assertThat(users.get("succeeded")).hasSize(2);
    }

    @Test
    void aRoleSentToUsersReachesCustomerLoginsAndSkipsStaffWithTheReason() throws Exception {
        seat(acme, com.geneinvoice.poc.PocType.COLLECTION, collections);

        // A selection of users may hold customer logins, so the role is accepted for the kind.
        JsonNode result = run(admin, bulk("ids", List.of(sales.getId(), acmeLogin.getId()),
                "params", params("USER", List.of(toRole("COLLECTION_POC")))));

        assertThat(result.get("succeeded")).extracting(JsonNode::asLong).containsExactly(acmeLogin.getId());
        assertThat(result.at("/skipped/0/id").asLong()).isEqualTo(sales.getId());
        assertThat(result.at("/skipped/0/reason").asText())
                .isEqualTo("No recipients: Collection POC (customer) is not assigned");
        assertThat(emailRepository.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getEntityId()).isEqualTo(acmeLogin.getId());
            assertThat(recipientsOf(e.getId())).extracting(EmailRecipient::getUserId).containsExactly(collections.getId());
        });
    }
}
