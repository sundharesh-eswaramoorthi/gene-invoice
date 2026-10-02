package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A pending change on the History tab (§5.11.11, §5.13): its rows are on the record whose money it moves (the
 * customer for a create, a dispute's target for a dispute's approval, naming the dispute), each says where the change
 * stands now, and the rows the approval applied name it and who approved it. A customer login's trail never shows a
 * request for a record that does not exist yet, nor who approved or decided anything.
 */
class ApprovalAuditTimelineTest extends ApprovalTestBase {

    @Autowired AuditService auditService;

    Region north;
    User maker;
    User approver;
    Customer acme;
    User acmeLogin;

    @BeforeEach
    void setUp() {
        north = workRegion("NORTH");
        maker = makerIn(north);
        approver = approverIn(north);
        acme = customer("Acme Ltd", north);
        acmeLogin = customerUser("acme.login", acme.getId());
    }

    @Test
    void aChangesRowsAreOnItsRecordAndSayWhereItStands() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice inv = invoice(acme, "500.00");
        cancel(inv.getId(), maker).andExpect(status().isAccepted());
        PendingChange pc = pendingOf("INVOICE", inv.getId());

        assertThat(rows(trail(admin, "INVOICE", inv.getId()), "CHANGE_REQUESTED")).singleElement().satisfies(r -> {
            assertThat(r.get("pendingChangeId").asLong()).isEqualTo(pc.getId());
            assertThat(r.get("pendingStatus").asText()).isEqualTo("PENDING");
            assertThat(r.get("changedByUserId").asLong()).isEqualTo(maker.getId());
        });

        approve(pc.getId(), approver).andExpect(status().isOk());

        JsonNode after = trail(admin, "INVOICE", inv.getId());
        assertThat(rows(after, "CHANGE_REQUESTED")).singleElement()
                .satisfies(r -> assertThat(r.get("pendingStatus").asText()).isEqualTo("APPROVED"));
        assertThat(rows(after, "CHANGE_APPROVED")).singleElement().satisfies(r -> {
            assertThat(r.get("changedByUserId").asLong()).isEqualTo(approver.getId());
            assertThat(r.get("pendingStatus").asText()).isEqualTo("APPROVED");
        });
        assertThat(rows(after, "INVOICE_CANCELLED")).singleElement().satisfies(r -> {
            assertThat(r.get("changedByUserId").asLong()).as("made by the maker").isEqualTo(maker.getId());
            assertThat(r.get("pendingChangeId").asLong()).isEqualTo(pc.getId());
            assertThat(r.get("approvedByUserId").asLong()).isEqualTo(approver.getId());
            assertThat(r.get("approvedByUsername").asText()).isEqualTo(approver.getUsername());
            assertThat(r.get("origin").asText()).isEqualTo("APPROVAL");
            assertThat(r.get("pendingStatus").asText()).isEqualTo("APPROVED");
        });
        assertThat(rows(after, "INVOICE_CREATED")).singleElement()
                .satisfies(r -> assertThat(r.hasNonNull("pendingStatus")).as("about no change").isFalse());
    }

    @Test
    void aHeldCreatesRowsAreOnTheCustomer() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CREATE, "100.00");
        createInvoice(acme, widget, 500, maker).andExpect(status().isAccepted());
        PendingChange create = pendingChangeRepository.findAll().stream()
                .filter(pc -> pc.getOperation() == ApprovalOperation.INVOICE_CREATE).findFirst().orElseThrow();

        reject(create.getId(), approver, "Wrong customer").andExpect(status().isOk());

        JsonNode trail = trail(admin, "CUSTOMER", acme.getId());
        assertThat(rows(trail, "CHANGE_REQUESTED")).singleElement().satisfies(r -> {
            assertThat(r.get("entityType").asText()).isEqualTo("CUSTOMER");
            assertThat(r.get("pendingChangeId").asLong()).isEqualTo(create.getId());
            assertThat(r.get("pendingStatus").asText()).isEqualTo("REJECTED");
        });
        assertThat(rows(trail, "CHANGE_REJECTED")).singleElement()
                .satisfies(r -> assertThat(r.get("reason").asText()).isEqualTo("Wrong customer"));
    }

    @Test
    void aDisputesApprovalIsOnTheRecordItChangesAndNamesTheDispute() throws Exception {
        User collector = collectorIn(north);
        invoice(acme, "500.00");
        actAs(admin);
        Payment payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("500.00"), "Bank", null, null, collector.getId(), null));
        SecurityContextHolder.clearContext();
        threshold(north, ApprovalOperation.DISPUTE_APPROVE, "100.00");
        Map<String, Object> dispute = new HashMap<>();
        dispute.put("targetType", "PAYMENT");
        dispute.put("targetId", payment.getId());
        dispute.put("reason", "Paid twice");
        dispute.put("proposedChangeJson", "{\"action\":\"void\"}");
        Long disputeId = body(mockMvc.perform(post("/api/disputes").with(as(acmeLogin))
                .contentType(MediaType.APPLICATION_JSON).content(json(dispute))).andExpect(status().isOk()))
                .get("id").asLong();
        User resolver = staffIn("resolver", "ADMIN", north, AccessLevel.MANAGE);
        mockMvc.perform(post("/api/disputes/" + disputeId + "/approve").with(as(resolver))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isAccepted());
        PendingChange pc = pendingOf("DISPUTE", disputeId);

        assertThat(rows(trail(admin, "PAYMENT", payment.getId()), "CHANGE_REQUESTED")).singleElement()
                .satisfies(r -> {
                    assertThat(r.get("disputeId").asLong()).isEqualTo(disputeId);
                    assertThat(r.get("pendingChangeId").asLong()).isEqualTo(pc.getId());
                    assertThat(r.get("pendingStatus").asText()).isEqualTo("PENDING");
                });
    }

    @Test
    void aCustomerLoginsTrailHasNoCreateRequestsAndNoApprovers() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice inv = invoice(acme, "500.00");
        threshold(north, ApprovalOperation.INVOICE_CREATE, "100.00");
        createInvoice(acme, widget, 500, maker).andExpect(status().isAccepted());
        cancel(inv.getId(), maker).andExpect(status().isAccepted());
        approve(pendingOf("INVOICE", inv.getId()).getId(), approver).andExpect(status().isOk());
        // A row naming who decided, as some change's row might.
        auditService.record("INVOICE", inv.getId(), "NOTE_ADDED", null,
                Map.of("note", "Called", "decidedByUserId", approver.getId(), "requestedBy", maker.getUsername()),
                approver.getId(), null, null);

        assertThat(rows(trail(admin, "CUSTOMER", acme.getId()), "CHANGE_REQUESTED")).as("staff see it").hasSize(1);
        JsonNode customerTrail = trail(acmeLogin, "CUSTOMER", acme.getId(), true);
        assertThat(StreamSupport.stream(customerTrail.spliterator(), false))
                .filteredOn(r -> r.get("entityType").asText().equals("CUSTOMER"))
                .noneMatch(r -> r.get("action").asText().startsWith("CHANGE_"));

        JsonNode invoiceTrail = trail(acmeLogin, "INVOICE", inv.getId());
        assertThat(rows(invoiceTrail, "INVOICE_CANCELLED")).singleElement().satisfies(r -> {
            assertThat(r.get("pendingChangeId").isNull()).isFalse();
            assertThat(r.get("approvedByUserId").isNull()).isTrue();
            assertThat(r.get("approvedByUsername").isNull()).isTrue();
            assertThat(r.get("changedByUserId").isNull()).as("staff are not named to a customer").isTrue();
        });
        assertThat(rows(invoiceTrail, "CHANGE_REQUESTED")).singleElement()
                .satisfies(r -> assertThat(r.get("pendingStatus").asText()).isEqualTo("APPROVED"));
        assertThat(rows(invoiceTrail, "NOTE_ADDED")).singleElement().satisfies(r -> {
            JsonNode shown = objectMapper.readTree(r.get("afterJson").asText());
            assertThat(shown.get("note").asText()).isEqualTo("Called");
            assertThat(shown.has("decidedByUserId")).isFalse();
            assertThat(shown.has("requestedBy")).isFalse();
        });
    }

    /** §6.11 hides the checker's note from a customer login; the trail, where it is the row's reason, does too. */
    @Test
    void aCustomerLoginDoesNotReadTheCheckersNote() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice rejected = invoice(acme, "500.00");
        Invoice approved = invoice(acme, "600.00");
        cancel(rejected.getId(), maker).andExpect(status().isAccepted());
        reject(pendingOf("INVOICE", rejected.getId()).getId(), approver, "Wrong customer").andExpect(status().isOk());
        cancel(approved.getId(), maker).andExpect(status().isAccepted());
        approve(pendingOf("INVOICE", approved.getId()).getId(), approver, "Checked the ledger")
                .andExpect(status().isOk());

        assertThat(rows(trail(admin, "INVOICE", rejected.getId()), "CHANGE_REJECTED")).singleElement()
                .satisfies(r -> assertThat(r.get("reason").asText()).isEqualTo("Wrong customer"));
        assertThat(rows(trail(acmeLogin, "INVOICE", rejected.getId()), "CHANGE_REJECTED")).singleElement()
                .satisfies(r -> assertThat(r.get("reason").isNull()).isTrue());
        assertThat(rows(trail(admin, "INVOICE", approved.getId()), "CHANGE_APPROVED")).singleElement()
                .satisfies(r -> assertThat(r.get("reason").asText()).isEqualTo("Checked the ledger"));
        assertThat(rows(trail(acmeLogin, "INVOICE", approved.getId()), "CHANGE_APPROVED")).singleElement()
                .satisfies(r -> assertThat(r.get("reason").isNull()).isTrue());
    }

    /**
     * A customer's trail with its related records brings only those the caller could open (SC-2): a sales POC who
     * sees their book sees their own invoice's rows, and none of another POC's invoice, neither its creation nor the
     * change held on it; the customer's own login still sees every invoice of theirs.
     */
    @Test
    void relatedRowsAreOnlyThoseOfRecordsTheCallerCouldOpen() throws Exception {
        User sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        User tom = user("tom.sales", DataSeeder.ROLE_SALES_POC);
        for (User u : List.of(sam, tom)) {
            grantRepository.deleteAllOfUser(u.getId());
            grant(u, north, AccessLevel.READ, RecordScope.BOOK);
        }
        Invoice samsInvoice = invoiceOf(sam, "300.00");
        Invoice tomsInvoice = invoiceOf(tom, "700.00");
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        cancel(tomsInvoice.getId(), maker).andExpect(status().isAccepted());
        assertThat(rows(trail(admin, "CUSTOMER", acme.getId(), true), "CHANGE_REQUESTED"))
                .as("the held change is on tom's invoice").singleElement()
                .satisfies(r -> assertThat(r.get("entityId").asLong()).isEqualTo(tomsInvoice.getId()));

        mockMvc.perform(get("/api/invoices/" + tomsInvoice.getId()).with(as(sam))).andExpect(status().isNotFound());
        JsonNode samsTrail = trail(sam, "CUSTOMER", acme.getId(), true);
        assertThat(StreamSupport.stream(samsTrail.spliterator(), false))
                .as("no row of tom's invoice")
                .noneMatch(r -> r.get("entityType").asText().equals("INVOICE")
                        && r.get("entityId").asLong() == tomsInvoice.getId());
        assertThat(rows(samsTrail, "CHANGE_REQUESTED")).isEmpty();
        assertThat(rows(samsTrail, "INVOICE_CREATED")).singleElement()
                .satisfies(r -> assertThat(r.get("entityId").asLong()).isEqualTo(samsInvoice.getId()));

        JsonNode customersOwn = trail(acmeLogin, "CUSTOMER", acme.getId(), true);
        assertThat(rows(customersOwn, "INVOICE_CREATED")).extracting(r -> r.get("entityId").asLong())
                .containsExactlyInAnyOrder(samsInvoice.getId(), tomsInvoice.getId());
    }

    private Invoice invoiceOf(User salesPoc, String amount) {
        actAs(admin);
        try {
            return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null,
                    salesPoc.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal(amount)))));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private JsonNode trail(User as, String type, Long id) throws Exception {
        return trail(as, type, id, false);
    }

    private JsonNode trail(User as, String type, Long id, boolean includeRelated) throws Exception {
        return body(mockMvc.perform(get("/api/audit").with(as(as)).param("entityType", type)
                .param("entityId", id.toString()).param("includeRelated", String.valueOf(includeRelated)))
                .andExpect(status().isOk()));
    }

    private static List<JsonNode> rows(JsonNode trail, String action) {
        return StreamSupport.stream(trail.spliterator(), false)
                .filter(r -> r.get("action").asText().equals(action)).toList();
    }
}
