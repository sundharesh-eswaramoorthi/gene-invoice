package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.CountingStatements;
import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.versions.InvoiceVersion;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceNumberSequenceRepository;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The gates on invoices (B2, §5.11.3): at or under its region's limit, or where the region sets none, a create,
 * an edit or a cancel is made exactly as before; above it, it is held for someone else to approve, answered
 * 202, and the invoice, its number sequence, its history and the outbox are left exactly as they were.
 */
class GateInvoiceTest extends ApprovalTestBase {

    @Autowired InvoiceNumberSequenceRepository sequenceRepository;

    Region north;
    Region south;
    User maker;
    User approver;
    Customer acme;

    @BeforeEach
    void setUp() {
        north = workRegion("NORTH");
        south = workRegion("SOUTH");
        maker = makerIn(north);
        approver = approverIn(north);
        acme = customer("Acme Ltd", north);
    }

    @Test
    void aCancelWithNoLimitOrWithinItIsMadeAsBefore() throws Exception {
        Invoice unlimited = invoice(acme, "500.00");
        cancel(unlimited.getId(), maker).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(unlimited.getId()))
                .andExpect(jsonPath("$.invoiceNumber").value(unlimited.getInvoiceNumber()))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.total").value(500.00))
                .andExpect(jsonPath("$.pendingChange").doesNotExist());

        threshold(north, ApprovalOperation.INVOICE_CANCEL, "500.00");
        Invoice atTheLimit = invoice(acme, "500.00");
        cancel(atTheLimit.getId(), maker).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Another operation's limit does not gate a cancel (the invoice is made before that limit is set).
        approvalThresholdRepository.deleteAll();
        Invoice otherLimit = invoice(acme, "700.00");
        threshold(north, ApprovalOperation.INVOICE_CREATE, "1.00");
        cancel(otherLimit.getId(), maker).andExpect(status().isOk());

        assertThat(pendingChangeRepository.count()).isZero();
        assertThat(audit("INVOICE", atTheLimit.getId(), "INVOICE_CANCELLED")).hasSize(1);
    }

    @Test
    void aCancelAboveTheLimitIsHeldAndLeavesTheInvoiceAsItWas() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice inv = invoice(acme, "500.00");
        Invoice before = invoiceRepository.findById(inv.getId()).orElseThrow();
        long events = automationEventRepository.count();
        int versions = historyTestSupport.versionsOf(InvoiceVersion.class, inv.getId()).size();

        JsonNode held = body(cancel(inv.getId(), maker).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL")));
        PendingChange pc = pendingOf("INVOICE", inv.getId());
        JsonNode change = held.get("pendingChange");
        assertThat(held.get("message").asText()).isEqualTo("Above North's approval limit of ₹100.00 for Cancel"
                + " invoice. It takes effect once someone else approves it.");
        assertThat(change.get("id").asLong()).isEqualTo(pc.getId());
        assertThat(change.get("status").asText()).isEqualTo("PENDING");
        assertThat(change.get("operation").asText()).isEqualTo("INVOICE_CANCEL");
        assertThat(change.get("operationLabel").asText()).isEqualTo("Cancel invoice");
        assertThat(change.get("entityType").asText()).isEqualTo("INVOICE");
        assertThat(change.get("entityId").asLong()).isEqualTo(inv.getId());
        assertThat(change.get("entityLabel").asText()).isEqualTo(inv.getInvoiceNumber());
        assertThat(change.get("customerId").asLong()).isEqualTo(acme.getId());
        assertThat(change.get("customerName").asText()).isEqualTo("Acme Ltd");
        assertThat(change.get("regionId").asLong()).isEqualTo(north.getId());
        assertThat(change.get("regionName").asText()).isEqualTo("North");
        assertThat(change.get("amount").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(change.get("threshold").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(change.get("summary").asText())
                .isEqualTo("Cancel invoice " + inv.getInvoiceNumber() + " for Acme Ltd (₹500.00)");
        assertThat(change.get("requestedBy").get("id").asLong()).isEqualTo(maker.getId());
        assertThat(change.get("origin").asText()).isEqualTo("USER");
        assertThat(change.get("approverCount").asLong()).as("admin and the approver; never the maker").isEqualTo(2);
        assertThat(change.get("supersededId").isNull()).isTrue();
        assertThat(change.get("canWithdraw").asBoolean()).isTrue();

        // The invoice, its history and the outbox are untouched.
        Invoice after = invoiceRepository.findById(inv.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(InvoiceStatus.UNPAID);
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getPaidAmount()).isEqualByComparingTo(before.getPaidAmount());
        assertThat(audit("INVOICE", inv.getId(), "INVOICE_CANCELLED")).isEmpty();
        assertThat(automationEventRepository.count()).as("a held change publishes nothing").isEqualTo(events);
        assertThat(historyTestSupport.versionsOf(InvoiceVersion.class, inv.getId())).hasSize(versions);

        // What was asked for, kept for the approval.
        assertThat(pc.getStatus()).isEqualTo(PendingStatus.PENDING);
        assertThat(pc.getMakerUserId()).isEqualTo(maker.getId());
        assertThat(pc.getAffectedType()).isEqualTo("INVOICE");
        assertThat(pc.getAffectedId()).isEqualTo(inv.getId());
        assertThat(pc.getRecordPocUserId()).isEqualTo(seller.getId());
        assertThat(pc.getPayloadJson()).isEqualTo("{\"invoiceId\":" + inv.getId() + "}");
        assertThat(objectMapper.readTree(pc.getBaseJson()).get("status").asText()).isEqualTo("UNPAID");
        assertThat(objectMapper.readTree(pc.getProposedJson()).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(audit("INVOICE", inv.getId(), "CHANGE_REQUESTED")).singleElement().satisfies(a -> {
            assertThat(a.getChangedByUserId()).isEqualTo(maker.getId());
            assertThat(a.getPendingChangeId()).isEqualTo(pc.getId());
            assertThat(a.getReason()).isEqualTo(pc.getSummary());
        });

        // The region's approvers are asked; the maker is not.
        assertThat(noticesTo(approver, ApprovalNotifier.REQUESTED)).singleElement().satisfies(n -> {
            assertThat(n.getLink()).isEqualTo("/approvals/" + pc.getId());
            assertThat(n.getTitle()).isEqualTo("Approval needed: Cancel invoice");
            assertThat(n.getRegionId()).isEqualTo(north.getId());
        });
        assertThat(noticesTo(admin, ApprovalNotifier.REQUESTED)).hasSize(1);
        assertThat(noticesTo(maker, ApprovalNotifier.REQUESTED)).isEmpty();
    }

    @Test
    void theLocationOfAHeldChangeShowsWhatItWouldDoToWhoeverMayDecideIt() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice inv = invoice(acme, "500.00");
        String location = cancel(inv.getId(), maker).andExpect(status().isAccepted())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        Long id = pendingOf("INVOICE", inv.getId()).getId();
        assertThat(location).isEqualTo("/api/approvals/" + id);

        JsonNode asApprover = body(mockMvc.perform(get("/api/approvals/" + id).with(as(approver)))
                .andExpect(status().isOk()));
        assertThat(asApprover.get("status").asText()).isEqualTo("PENDING");
        assertThat(asApprover.get("statusAt").isNull()).isTrue();
        assertThat(asApprover.get("measureLabel").asText()).isEqualTo("Invoice total");
        assertThat(asApprover.get("changes")).singleElement().satisfies(c -> {
            assertThat(c.get("field").asText()).isEqualTo("status");
            assertThat(c.get("label").asText()).isEqualTo("Status");
            assertThat(c.get("current").asText()).isEqualTo("UNPAID");
            assertThat(c.get("proposed").asText()).isEqualTo("CANCELLED");
            assertThat(c.get("changedSinceRequest").asBoolean()).isFalse();
        });
        assertThat(asApprover.get("current").get("total").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(asApprover.get("canApprove").asBoolean()).isTrue();
        assertThat(asApprover.get("canReject").asBoolean()).isTrue();
        assertThat(asApprover.get("canWithdraw").asBoolean()).isFalse();
        assertThat(asApprover.get("blockedReason").isNull()).isTrue();

        JsonNode asMaker = body(mockMvc.perform(get("/api/approvals/" + id).with(as(maker)))
                .andExpect(status().isOk()));
        assertThat(asMaker.get("canApprove").asBoolean()).isFalse();
        assertThat(asMaker.get("canWithdraw").asBoolean()).isTrue();
        assertThat(asMaker.get("blockedReason").asText()).isEqualTo("You requested this change");

        // Someone who sees the invoice but cannot approve in its region is told why.
        User reader = staffIn("reader", "CASHIER", north, AccessLevel.READ);
        JsonNode asReader = body(mockMvc.perform(get("/api/approvals/" + id).with(as(reader)))
                .andExpect(status().isOk()));
        assertThat(asReader.get("canApprove").asBoolean()).isFalse();
        assertThat(asReader.get("blockedReason").asText()).isEqualTo("You don't hold the approval right in North");

        // Someone with nothing in the region does not learn it exists.
        User outsider = staffIn("outsider", "ADMIN", south, AccessLevel.APPROVE);
        mockMvc.perform(get("/api/approvals/" + id).with(as(outsider))).andExpect(status().isNotFound());
    }

    /** CF-7: a web build on another origin can read where the held change is. */
    @Test
    void aCrossOriginCallerMayReadTheLocationOfAHeldChange() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice inv = invoice(acme, "500.00");
        var response = mockMvc.perform(post("/api/invoices/" + inv.getId() + "/cancel")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5000").with(as(maker)))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        assertThat(response.getHeader(HttpHeaders.LOCATION))
                .isEqualTo("/api/approvals/" + pendingOf("INVOICE", inv.getId()).getId());
        assertThat(response.getHeaders(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS).stream()
                .flatMap(h -> List.of(h.split(",")).stream()).map(String::trim))
                .contains(HttpHeaders.LOCATION);
    }

    /** PC-13: each region's own limit decides, not the strictest anywhere. */
    @Test
    void theLimitIsTheOneOfTheInvoicesOwnRegion() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        threshold(south, ApprovalOperation.INVOICE_CANCEL, "10000.00");
        User both = makerIn(north);
        grant(both, south, AccessLevel.MANAGE, RecordScope.ALL);
        Invoice inNorth = invoice(acme, "500.00");
        Invoice inSouth = invoice(customer("Southwind", south), "500.00");

        cancel(inNorth.getId(), both).andExpect(status().isAccepted());
        cancel(inSouth.getId(), both).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(invoiceRepository.findById(inNorth.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.UNPAID);
        assertThat(pendingChangeRepository.findAll()).singleElement()
                .satisfies(pc -> assertThat(pc.getRegionId()).isEqualTo(north.getId()));
    }

    @Test
    void aBulkCancelHoldsTheRowsAboveTheLimitAndCancelsTheRest() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice small = invoice(acme, "50.00");
        Invoice large = invoice(acme, "500.00");
        Invoice larger = invoice(acme, "600.00");

        JsonNode result = body(mockMvc.perform(post("/api/invoices/bulk").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "CANCEL",
                                "ids", List.of(small.getId(), large.getId(), larger.getId())))))
                .andExpect(status().isOk()));

        assertThat(result.get("succeeded")).extracting(JsonNode::asLong).containsExactly(small.getId());
        assertThat(result.get("failed")).isEmpty();
        assertThat(result.get("skipped")).isEmpty();
        assertThat(result.get("pending")).extracting(p -> p.get("id").asLong())
                .containsExactly(large.getId(), larger.getId());
        assertThat(result.get("pending")).allSatisfy(p -> {
            PendingChange pc = pendingOf("INVOICE", p.get("id").asLong());
            assertThat(p.get("pendingChangeId").asLong()).isEqualTo(pc.getId());
            assertThat(p.get("summary").asText()).isEqualTo(pc.getSummary()).startsWith("Cancel invoice");
        });
        assertThat(invoiceRepository.findById(large.getId()).orElseThrow().getStatus()).isEqualTo(InvoiceStatus.UNPAID);
        assertThat(invoiceRepository.findById(small.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);

        // Anyone else's cancel of an invoice with a change waiting is refused: 409 alone, skipped in bulk.
        User other = makerIn(north);
        cancel(large.getId(), other).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(large.getInvoiceNumber()
                        + " already has a change awaiting approval (#" + pendingOf("INVOICE", large.getId()).getId()
                        + ", Cancel invoice by " + maker.getFullName()
                        + "). It must be approved, rejected or withdrawn first."));
        JsonNode again = body(mockMvc.perform(post("/api/invoices/bulk").with(as(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "CANCEL", "ids", List.of(large.getId(), larger.getId())))))
                .andExpect(status().isOk()));
        assertThat(again.get("skipped")).hasSize(2);
        assertThat(again.get("pending")).isEmpty();
        assertThat(pendingChangeRepository.count()).isEqualTo(2);
    }

    @Test
    void theSameMakerAskingAgainReplacesTheirEarlierRequest() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice inv = invoice(acme, "500.00");
        cancel(inv.getId(), maker).andExpect(status().isAccepted());
        PendingChange first = pendingOf("INVOICE", inv.getId());

        JsonNode second = body(cancel(inv.getId(), maker).andExpect(status().isAccepted()));

        assertThat(second.get("pendingChange").get("supersededId").asLong()).isEqualTo(first.getId());
        PendingChange replaced = reload(first);
        PendingChange now = pendingOf("INVOICE", inv.getId());
        assertThat(replaced.getStatus()).isEqualTo(PendingStatus.SUPERSEDED);
        assertThat(replaced.getOutstandingKey()).isNull();
        assertThat(replaced.getSupersededById()).isEqualTo(now.getId());
        assertThat(replaced.getDecidedAt()).isEqualTo(now.getRequestedAt());
        assertThat(audit("INVOICE", inv.getId(), "CHANGE_SUPERSEDED")).singleElement()
                .satisfies(a -> assertThat(a.getPendingChangeId()).isEqualTo(first.getId()));
    }

    /** SC-20: a customer name as long as its column is kept whole, and nothing held is ever too long to save. */
    @Test
    void aCustomerNameAsLongAsItCanBeIsKeptInTheChange() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        String name = "L".repeat(PendingChange.CUSTOMER_NAME_MAX);
        Invoice inv = invoice(customer(name, north), "500.00");

        JsonNode held = body(cancel(inv.getId(), maker).andExpect(status().isAccepted()));

        PendingChange pc = pendingOf("INVOICE", inv.getId());
        assertThat(pc.getCustomerName()).isEqualTo(name);
        assertThat(pc.getSummary()).contains(name).hasSizeLessThanOrEqualTo(PendingChange.SUMMARY_MAX);
        assertThat(held.get("pendingChange").get("summary").asText()).isEqualTo(pc.getSummary());
    }

    @Test
    void aChangeTooLongForItsColumnsIsFittedRatherThanRefused() {
        Gated g = new Gated(ApprovalOperation.INVOICE_CANCEL, 1L, "N".repeat(200), "INVOICE", 1L, null, 1L,
                "C".repeat(400), north.getId(), null, new BigDecimal("5.00"), Map.of(), null, null,
                "S".repeat(900), false);

        PendingChange pc = PendingChange.pending(g, north, BigDecimal.ONE, maker.getId(), ChangeOrigin.USER, null,
                null, "{}", null, null, AppClock.now());

        assertThat(pc.getEntityLabel()).hasSize(PendingChange.ENTITY_LABEL_MAX).endsWith("…");
        assertThat(pc.getCustomerName()).hasSize(PendingChange.CUSTOMER_NAME_MAX).endsWith("…");
        assertThat(pc.getSummary()).hasSize(PendingChange.SUMMARY_MAX).endsWith("…");
    }

    @Test
    void withNoLimitsAnInvoiceIsMadeAndEditedExactlyAsBefore() throws Exception {
        Product gadget = product("Gadget", "250.00");
        JsonNode made = body(createInvoice(acme, gadget, 2, maker).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNPAID"))
                .andExpect(jsonPath("$.total").value(500.00))
                .andExpect(jsonPath("$.customerId").value(acme.getId()))
                .andExpect(jsonPath("$.pendingChange").doesNotExist()));
        long id = made.get("id").asLong();
        assertThat(made.get("invoiceNumber").asText()).startsWith("INV-");

        mockMvc.perform(patch("/api/invoices/" + id).with(as(maker)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("notes", "Deliver Monday",
                                "dueDate", made.get("dueDate").asText(), "paymentTerm", "CUSTOM"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.notes").value("Deliver Monday"))
                .andExpect(jsonPath("$.paymentTerm").value("CUSTOM"));
        mockMvc.perform(patch("/api/invoices/" + id).with(as(maker)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("dueDate", LocalDate.parse(made.get("dueDate").asText()).plusDays(60)
                                .toString()))))
                .andExpect(status().isOk());

        assertThat(pendingChangeRepository.count()).isZero();
        assertThat(audit("INVOICE", id, "INVOICE_DUE_DATE_CHANGED")).hasSize(2);
    }

    @Test
    void aCreateAboveTheLimitIsHeldAndTakesNoNumber() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CREATE, "100.00");
        Product gadget = product("Gadget", "250.00");
        invoice(acme, "50.00");
        long invoices = invoiceRepository.count();
        long lastNumber = sequenceRepository.findById(1L).orElseThrow().getLastNumber();
        long events = automationEventRepository.count();

        JsonNode held = body(createInvoice(acme, gadget, 2, maker).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.invoiceNumber").doesNotExist()));

        JsonNode change = held.get("pendingChange");
        assertThat(change.get("operation").asText()).isEqualTo("INVOICE_CREATE");
        assertThat(change.get("entityType").asText()).isEqualTo("INVOICE");
        assertThat(change.get("entityId").isNull()).isTrue();
        assertThat(change.get("amount").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(change.get("summary").asText()).isEqualTo("Create invoice for Acme Ltd (₹500.00)");
        // No invoice, no number drawn, nothing published.
        assertThat(invoiceRepository.count()).isEqualTo(invoices);
        assertThat(sequenceRepository.findById(1L).orElseThrow().getLastNumber()).isEqualTo(lastNumber);
        assertThat(automationEventRepository.count()).isEqualTo(events);

        PendingChange pc = pendingChangeRepository.findById(change.get("id").asLong()).orElseThrow();
        assertThat(pc.getOutstandingKey()).as("creates have no key").isNull();
        assertThat(pc.getAffectedId()).isNull();
        assertThat(pc.getRecordPocUserId()).isEqualTo(seller.getId());
        // The line's price is pinned at the catalogue price of the moment it was asked for.
        JsonNode payload = objectMapper.readTree(pc.getPayloadJson());
        assertThat(payload.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(payload.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(pc.getBaseJson()).isNull();
        assertThat(objectMapper.readTree(pc.getProposedJson()).get("total").decimalValue())
                .isEqualByComparingTo("500.00");
        // Recorded on the customer, since there is no invoice yet.
        assertThat(audit("CUSTOMER", acme.getId(), "CHANGE_REQUESTED")).singleElement().satisfies(a -> {
            assertThat(a.getChangedByUserId()).isEqualTo(maker.getId());
            assertThat(a.getPendingChangeId()).isEqualTo(pc.getId());
        });
        assertThat(noticesTo(approver, ApprovalNotifier.REQUESTED)).singleElement()
                .satisfies(n -> assertThat(n.getTitle()).isEqualTo("Approval needed: Create invoice"));
        assertThat(noticesTo(maker, ApprovalNotifier.REQUESTED)).isEmpty();

        // The approver sees what would be made; there is nothing yet to compare it with.
        JsonNode detail = body(mockMvc.perform(get("/api/approvals/" + pc.getId()).with(as(approver)))
                .andExpect(status().isOk()));
        assertThat(detail.get("current").isNull()).isTrue();
        assertThat(detail.get("changes")).extracting(c -> c.get("field").asText()).contains("total", "items");
    }

    @Test
    void aLaterDueDateOnMoneyOwedIsHeldAndAnEarlierOneIsNot() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_TERMS, "100.00");
        Invoice inv = invoice(acme, "500.00");
        LocalDate due = inv.getDueDate();

        JsonNode held = body(mockMvc.perform(patch("/api/invoices/" + inv.getId()).with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("dueDate", due.plusDays(30).toString(), "notes", "Agreed by phone"))))
                .andExpect(status().isAccepted()));

        Invoice after = invoiceRepository.findById(inv.getId()).orElseThrow();
        assertThat(after.getDueDate()).isEqualTo(due);
        assertThat(after.getNotes()).as("the rest of the edit waits too").isNull();
        assertThat(after.getVersion()).isEqualTo(inv.getVersion());
        PendingChange pc = pendingOf("INVOICE", inv.getId());
        assertThat(pc.getOperation()).isEqualTo(ApprovalOperation.INVOICE_TERMS);
        assertThat(pc.getAmount()).isEqualByComparingTo("500.00");
        assertThat(held.get("pendingChange").get("summary").asText()).isEqualTo("Extend due date of "
                + inv.getInvoiceNumber() + " from " + due + " to " + due.plusDays(30) + " on ₹500.00 outstanding");
        assertThat(objectMapper.readTree(pc.getPayloadJson()).get("version").isNull()).isTrue();
        JsonNode proposed = objectMapper.readTree(pc.getProposedJson());
        assertThat(proposed.get("dueDate").asText()).isEqualTo(due.plusDays(30).toString());
        assertThat(proposed.get("paymentTerm").asText()).isEqualTo("CUSTOM");
        assertThat(proposed.get("notes").asText()).isEqualTo("Agreed by phone");

        // Bringing a due date forward costs nothing, so it is made at once.
        Invoice other = invoice(acme, "500.00");
        mockMvc.perform(patch("/api/invoices/" + other.getId()).with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("dueDate", other.getDueDate().minusDays(5).toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate").value(other.getDueDate().minusDays(5).toString()));

        // An edit that leaves the terms alone never asks about a limit.
        long reads = CountingStatements.reads("approval_thresholds", () ->
                mockMvc.perform(patch("/api/invoices/" + other.getId()).with(as(maker))
                                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("notes", "Thanks"))))
                        .andExpect(status().isOk()));
        assertThat(reads).isZero();
        assertThat(pendingChangeRepository.count()).isEqualTo(1);
    }

    /** A caller that cannot take a hold fails closed, and leaves no pending change behind. */
    @Test
    void theOldCancelCannotBeHeldSoItRefusesAndKeepsNothing() {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice inv = invoice(acme, "500.00");
        actAs(maker);

        assertThatThrownBy(() -> invoiceService.cancel(inv.getId())).isInstanceOf(IllegalStateException.class);

        assertThat(pendingChangeRepository.count()).isZero();
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus()).isEqualTo(InvoiceStatus.UNPAID);
    }
}
