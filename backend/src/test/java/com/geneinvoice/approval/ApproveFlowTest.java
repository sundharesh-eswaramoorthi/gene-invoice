package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.MovableClock;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.versions.InvoiceVersion;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deciding a held cancel (§5.11.6): someone else with the approval right in the invoice's region approves it and
 * it is made as the maker made it, every trace of it saying who asked and who approved (CF-1, SC-6); a rejection
 * needs a reason; only the maker withdraws; a decided change stays decided; an approval the rules now refuse is
 * recorded as failed, never answered with an authentication error.
 */
class ApproveFlowTest extends ApprovalTestBase {

    @Autowired PrivilegeRepository privilegeRepository;

    Region north;
    Region south;
    User maker;
    User approver;
    Customer acme;
    Invoice inv;
    PendingChange pc;

    @BeforeEach
    void aHeldCancel() throws Exception {
        north = workRegion("NORTH");
        south = workRegion("SOUTH");
        maker = makerIn(north);
        approver = approverIn(north);
        acme = customer("Acme Ltd", north);
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        inv = invoice(acme, "500.00");
        cancel(inv.getId(), maker).andExpect(status().isAccepted());
        pc = pendingOf("INVOICE", inv.getId());
    }

    @Test
    void anApprovedCancelIsMadeAsTheMakerAndEveryTraceNamesBothOfThem() throws Exception {
        long lastEvent = automationEventRepository.findAll().stream().mapToLong(AutomationEvent::getId).max().orElse(0);

        JsonNode detail = body(approve(pc.getId(), approver, "Checked with the customer").andExpect(status().isOk()));

        assertThat(detail.get("status").asText()).isEqualTo("APPROVED");
        assertThat(detail.get("decidedBy").get("id").asLong()).isEqualTo(approver.getId());
        assertThat(detail.get("decisionNote").asText()).isEqualTo("Checked with the customer");
        assertThat(detail.get("result").get("entityType").asText()).isEqualTo("INVOICE");
        assertThat(detail.get("result").get("entityId").asLong()).isEqualTo(inv.getId());
        assertThat(detail.get("result").get("link").asText()).isEqualTo("/invoices/" + inv.getId());
        assertThat(detail.get("canApprove").asBoolean()).isFalse();
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus()).isEqualTo(InvoiceStatus.CANCELLED);

        PendingChange decided = reload(pc);
        assertThat(decided.getStatus()).isEqualTo(PendingStatus.APPROVED);
        assertThat(decided.getDecidedByUserId()).isEqualTo(approver.getId());
        assertThat(decided.getDecidedAt()).isNotNull();
        assertThat(decided.getOutstandingKey()).isNull();
        assertThat(decided.getResultEntityId()).isEqualTo(inv.getId());
        assertThat(objectMapper.readTree(decided.getResultJson()).get("status").asText()).isEqualTo("CANCELLED");

        // The cancel is the maker's, approved by the checker; the approval is the checker's.
        assertThat(audit("INVOICE", inv.getId(), "INVOICE_CANCELLED")).singleElement().satisfies(a -> {
            assertThat(a.getChangedByUserId()).isEqualTo(maker.getId());
            assertThat(a.getApprovedByUserId()).isEqualTo(approver.getId());
            assertThat(a.getPendingChangeId()).isEqualTo(pc.getId());
            assertThat(a.getOrigin()).isEqualTo("APPROVAL");
        });
        assertThat(audit("INVOICE", inv.getId(), "CHANGE_APPROVED")).singleElement().satisfies(a -> {
            assertThat(a.getChangedByUserId()).isEqualTo(approver.getId());
            assertThat(a.getPendingChangeId()).isEqualTo(pc.getId());
            assertThat(a.getReason()).isEqualTo("Checked with the customer");
        });

        // The outbox and history say APPROVAL and name the maker, because the approval was still bound at commit.
        List<AutomationEvent> published = automationEventRepository.findAll().stream()
                .filter(e -> e.getId() > lastEvent).toList();
        assertThat(published).extracting(AutomationEvent::getEntityType).contains("INVOICE", "CUSTOMER");
        assertThat(published).allSatisfy(e -> {
            assertThat(e.getOrigin()).isEqualTo("APPROVAL");
            assertThat(e.getActorUserId()).isEqualTo(maker.getId());
        });
        InvoiceVersion cancelled = historyTestSupport.versionsOf(InvoiceVersion.class, inv.getId()).stream()
                .max(Comparator.comparing(InvoiceVersion::getValidFrom)).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(cancelled.getOrigin()).isEqualTo("APPROVAL");
        assertThat(cancelled.getOriginRefId()).isEqualTo(pc.getId());
        assertThat(cancelled.getActorUserId()).isEqualTo(maker.getId());

        // The maker hears, with a link to the invoice.
        assertThat(noticesTo(maker, ApprovalNotifier.APPROVED)).singleElement().satisfies(n -> {
            assertThat(n.getLink()).isEqualTo("/invoices/" + inv.getId());
            assertThat(n.getRegionId()).isEqualTo(north.getId());
        });
    }

    @Test
    void onlySomeoneElseWithTheApprovalRightInTheInvoicesRegionMayApprove() throws Exception {
        // A maker who could approve elsewhere, or even here, cannot approve their own change.
        User approvingMaker = approverIn(north);
        Invoice own = invoice(acme, "300.00");
        cancel(own.getId(), approvingMaker).andExpect(status().isAccepted());
        PendingChange ownChange = pendingOf("INVOICE", own.getId());
        approve(ownChange.getId(), approvingMaker).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You cannot approve a change you requested"));

        // Sees the invoice, but approves only in SOUTH.
        User southApprover = approverIn(south);
        grant(southApprover, north, AccessLevel.READ, RecordScope.ALL);
        approve(pc.getId(), southApprover).andExpect(status().isForbidden());

        // Has nothing in NORTH: the change is not there for them.
        approve(pc.getId(), approverIn(south)).andExpect(status().isNotFound());

        // A customer login never decides.
        User acmeLogin = customerUser("acme.login", acme.getId());
        approve(pc.getId(), acmeLogin).andExpect(status().isForbidden());

        // Not while a past date is viewed.
        mockMvc.perform(post("/api/approvals/" + pc.getId() + "/approve").with(as(approver))
                        .param("asOf", Today.date().minusDays(1).toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("AS_OF_READ_ONLY"));

        assertThat(reload(pc).getStatus()).isEqualTo(PendingStatus.PENDING);
        assertThat(reload(ownChange).getStatus()).isEqualTo(PendingStatus.PENDING);
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus()).isEqualTo(InvoiceStatus.UNPAID);
        assertThat(audit("INVOICE", inv.getId(), "CHANGE_APPROVED")).isEmpty();
    }

    /**
     * CF-17 (check 4): a customer login never decides, even one whose role holds the approval right, which it holds
     * in its own customer's region; the change stays pending.
     */
    @Test
    void aCustomerLoginHoldingTheApprovalRightStillCannotDecide() throws Exception {
        Role approving = roleRepository.findByName("CUSTOMER_APPROVER").orElseGet(() -> Role.builder()
                .name("CUSTOMER_APPROVER").description("Customer login that holds the approval right").build());
        approving.setPrivileges(new HashSet<>(Stream.concat(role("CUSTOMER").getPrivileges().stream(),
                Stream.of(privilegeRepository.findByName(Privileges.CHANGE_APPROVE).orElseThrow())).toList()));
        roleRepository.save(approving);
        User acmeLogin = customerUser("acme.login", acme.getId());
        acmeLogin.setRole(approving);
        userRepository.save(acmeLogin);

        approve(pc.getId(), acmeLogin).andExpect(status().isForbidden());
        reject(pc.getId(), acmeLogin, "Not ours").andExpect(status().isForbidden());

        assertThat(reload(pc).getStatus()).isEqualTo(PendingStatus.PENDING);
        assertThat(reload(pc).getDecidedByUserId()).isNull();
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus()).isEqualTo(InvoiceStatus.UNPAID);
        assertThat(audit("INVOICE", inv.getId(), "CHANGE_APPROVED")).isEmpty();
        assertThat(audit("INVOICE", inv.getId(), "CHANGE_REJECTED")).isEmpty();
    }

    /**
     * CF-8: a field the cancel carries unchanged is shown once it has moved, so the approver sees why approving would
     * fail; one that has not moved stays out, whatever scale its amount is written in.
     */
    @Test
    void aStrictFieldThatMovedSinceTheRequestIsShownAsChanged() throws Exception {
        User collector = collectorIn(north);
        pay(acme, "100.00", collector, admin).andExpect(status().isOk());

        JsonNode detail = body(mockMvc.perform(get("/api/approvals/" + pc.getId()).with(as(approver)))
                .andExpect(status().isOk()));

        Map<String, JsonNode> changes = new HashMap<>();
        detail.get("changes").forEach(c -> changes.put(c.get("field").asText(), c));
        assertThat(changes).containsOnlyKeys("status", "paidAmount");
        JsonNode paid = changes.get("paidAmount");
        assertThat(paid.get("changedSinceRequest").asBoolean()).isTrue();
        assertThat(paid.get("current").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(paid.get("proposed").decimalValue()).isEqualByComparingTo("0.00");
        // Part paid, the invoice is no longer UNPAID either.
        JsonNode status = changes.get("status");
        assertThat(status.get("changedSinceRequest").asBoolean()).isTrue();
        assertThat(status.get("current").asText()).isNotEqualTo("UNPAID");
        assertThat(status.get("proposed").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void aDecidedChangeStaysDecided() throws Exception {
        approve(pc.getId(), approver).andExpect(status().isOk());

        approve(pc.getId(), admin).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith(
                        "Already approved by " + approver.getFullName() + " at ")));
        reject(pc.getId(), admin, "Too late").andExpect(status().isConflict());
        withdraw(pc.getId(), maker).andExpect(status().isConflict());

        assertThat(audit("INVOICE", inv.getId(), "INVOICE_CANCELLED")).hasSize(1);
        assertThat(audit("INVOICE", inv.getId(), "CHANGE_APPROVED")).hasSize(1);
    }

    @Test
    void aRejectionSaysWhyAndTellsTheMaker() throws Exception {
        reject(pc.getId(), approver, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.note").exists());
        reject(pc.getId(), approver, "   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.note").exists());
        reject(pc.getId(), approver, "x".repeat(501)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.note").exists());
        assertThat(reload(pc).getStatus()).isEqualTo(PendingStatus.PENDING);

        JsonNode detail = body(reject(pc.getId(), approver, "The customer still owes it")
                .andExpect(status().isOk()));

        assertThat(detail.get("status").asText()).isEqualTo("REJECTED");
        assertThat(detail.get("decisionNote").asText()).isEqualTo("The customer still owes it");
        assertThat(reload(pc).getDecidedByUserId()).isEqualTo(approver.getId());
        assertThat(reload(pc).getOutstandingKey()).isNull();
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus()).isEqualTo(InvoiceStatus.UNPAID);
        assertThat(audit("INVOICE", inv.getId(), "CHANGE_REJECTED")).singleElement().satisfies(a -> {
            assertThat(a.getChangedByUserId()).isEqualTo(approver.getId());
            assertThat(a.getPendingChangeId()).isEqualTo(pc.getId());
        });
        assertThat(noticesTo(maker, ApprovalNotifier.REJECTED)).singleElement().satisfies(n -> {
            assertThat(n.getLink()).isEqualTo("/approvals/" + pc.getId());
            assertThat(n.getMessage()).endsWith(": The customer still owes it");
        });
    }

    @Test
    void theMakerWithdrawsTheirOwnRequestAndCannotRejectIt() throws Exception {
        User approvingMaker = approverIn(north);
        Invoice own = invoice(acme, "300.00");
        cancel(own.getId(), approvingMaker).andExpect(status().isAccepted());
        reject(pendingOf("INVOICE", own.getId()).getId(), approvingMaker, "Changed my mind")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Use Withdraw for your own request"));

        withdraw(pc.getId(), approver).andExpect(status().isForbidden());
        assertThat(reload(pc).getStatus()).isEqualTo(PendingStatus.PENDING);

        JsonNode detail = body(withdraw(pc.getId(), maker).andExpect(status().isOk()));

        assertThat(detail.get("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(detail.get("canWithdraw").asBoolean()).isFalse();
        PendingChange withdrawn = reload(pc);
        assertThat(withdrawn.getDecidedByUserId()).isEqualTo(maker.getId());
        assertThat(withdrawn.getOutstandingKey()).isNull();
        assertThat(audit("INVOICE", inv.getId(), "CHANGE_WITHDRAWN")).singleElement()
                .satisfies(a -> assertThat(a.getChangedByUserId()).isEqualTo(maker.getId()));
        assertThat(notificationRepository.findAll()).noneMatch(n -> n.getUserId().equals(maker.getId()));

        // With nothing waiting any more, the invoice can be asked about again.
        cancel(inv.getId(), maker).andExpect(status().isAccepted());
    }

    /**
     * A held create draws its number only when it is approved, so it carries the approval day's prefix, a create
     * without a date is dated when it is made, and a rejected create leaves no gap in the numbers.
     */
    @Test
    void aCreateApprovedADayLaterIsNumberedAndDatedThatDay() throws Exception {
        Instant asked = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        MovableClock.SHARED.set(asked);
        threshold(north, ApprovalOperation.INVOICE_CREATE, "100.00");
        Product gadget = product("Gadget", "250.00");
        String before = invoice(acme, "50.00").getInvoiceNumber();

        Long kept = body(createInvoice(acme, gadget, 2, maker).andExpect(status().isAccepted()))
                .get("pendingChange").get("id").asLong();
        Long refused = body(createInvoice(acme, gadget, 3, maker).andExpect(status().isAccepted()))
                .get("pendingChange").get("id").asLong();
        reject(refused, approver, "Wrong quantity").andExpect(status().isOk());
        String after = invoice(acme, "60.00").getInvoiceNumber();
        assertThat(sequenceOf(after)).as("held and rejected creates took no number").isEqualTo(sequenceOf(before) + 1);

        MovableClock.SHARED.advance(Duration.ofDays(1));
        Instant approvedAt = MovableClock.SHARED.instant();
        JsonNode detail = body(approve(kept, approver).andExpect(status().isOk()));

        assertThat(detail.get("status").asText()).isEqualTo("APPROVED");
        Long invoiceId = detail.get("result").get("entityId").asLong();
        assertThat(detail.get("result").get("link").asText()).isEqualTo("/invoices/" + invoiceId);
        Invoice made = invoiceRepository.findById(invoiceId).orElseThrow();
        String day = LocalDate.ofInstant(approvedAt, ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        assertThat(made.getInvoiceNumber()).startsWith("INV-" + day + "-");
        assertThat(made.getInvoiceDate()).isEqualTo(approvedAt);
        assertThat(made.getTotal()).isEqualByComparingTo("500.00");
        assertThat(audit("INVOICE", invoiceId, "INVOICE_CREATED")).singleElement().satisfies(a -> {
            assertThat(a.getChangedByUserId()).isEqualTo(maker.getId());
            assertThat(a.getApprovedByUserId()).isEqualTo(approver.getId());
            assertThat(a.getPendingChangeId()).isEqualTo(kept);
        });
        assertThat(audit("CUSTOMER", acme.getId(), "CHANGE_APPROVED")).singleElement()
                .satisfies(a -> assertThat(a.getPendingChangeId()).isEqualTo(kept));
    }

    /** The money arrived when the payment was recorded, not when it was approved (§10 Q4). */
    @Test
    void aPaymentApprovedLaterKeepsTheTimeItWasAskedFor() throws Exception {
        Instant asked = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        MovableClock.SHARED.set(asked);
        threshold(north, ApprovalOperation.PAYMENT_RECORD, "100.00");
        User collector = collectorIn(north);
        Long id = body(pay(acme, "300.00", collector, maker).andExpect(status().isAccepted()))
                .get("pendingChange").get("id").asLong();

        MovableClock.SHARED.advance(Duration.ofHours(3));
        JsonNode detail = body(approve(id, approver).andExpect(status().isOk()));

        Payment payment = paymentRepository.findById(detail.get("result").get("entityId").asLong()).orElseThrow();
        assertThat(payment.getPaidAt()).isEqualTo(asked);
        assertThat(payment.getAmount()).isEqualByComparingTo("300.00");
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getPaidAmount())
                .isEqualByComparingTo("300.00");
        assertThat(noticesTo(maker, ApprovalNotifier.APPROVED)).singleElement()
                .satisfies(n -> assertThat(n.getLink()).isEqualTo("/payments/" + payment.getId()));
    }

    /** A held promise is made as the maker asked, with the POC it resolved to when it was asked for. */
    @Test
    void aHeldPromiseIsMadeWhenApproved() throws Exception {
        threshold(north, ApprovalOperation.PROMISE_CREATE, "100.00");
        User collector = collectorIn(north);
        Map<String, Object> req = new HashMap<>();
        req.put("customerId", acme.getId());
        req.put("amount", 500);
        req.put("promisedDate", Today.date().plusDays(5).toString());
        req.put("collectionPocUserId", collector.getId());
        req.put("notes", "Will pay on Friday");
        long promises = promiseRepository.count();
        Long id = body(mockMvc.perform(post("/api/promises").with(as(collector))
                        .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isAccepted())).get("pendingChange").get("id").asLong();
        assertThat(promiseRepository.count()).isEqualTo(promises);

        JsonNode detail = body(approve(id, approver).andExpect(status().isOk()));

        assertThat(detail.get("status").asText()).isEqualTo("APPROVED");
        Long promiseId = detail.get("result").get("entityId").asLong();
        PaymentPromise made = promiseRepository.findById(promiseId).orElseThrow();
        assertThat(made.getAmount()).isEqualByComparingTo("500.00");
        assertThat(made.getPromisedDate()).isEqualTo(Today.date().plusDays(5));
        assertThat(made.getCollectionPoc().getId()).isEqualTo(collector.getId());
        assertThat(made.getNotes()).isEqualTo("Will pay on Friday");
        assertThat(made.getStatus()).isEqualTo(PromiseStatus.OPEN);
        assertThat(audit("PROMISE", promiseId, "PROMISE_CREATED")).singleElement().satisfies(a -> {
            assertThat(a.getChangedByUserId()).isEqualTo(collector.getId());
            assertThat(a.getApprovedByUserId()).isEqualTo(approver.getId());
            assertThat(a.getPendingChangeId()).isEqualTo(id);
        });
    }

    /**
     * A held override is made when approved. One whose promise had its status set by someone else meanwhile fails:
     * the override is checked strictly, since it would otherwise replace their decision unseen.
     */
    @Test
    void aHeldOverrideIsMadeWhenApprovedAndFailsIfSomeoneElseSetTheStatusFirst() throws Exception {
        User collector = collectorIn(north);
        PaymentPromise kept = promise(acme, "800.00", collector);
        PaymentPromise contested = promise(acme, "600.00", collector);
        threshold(north, ApprovalOperation.PROMISE_OVERRIDE, "100.00");
        override(kept.getId(), "KEPT", "Paid in cash", collector).andExpect(status().isAccepted());
        override(contested.getId(), "KEPT", "Paid in cash", collector).andExpect(status().isAccepted());
        PendingChange keptChange = pendingOf("PROMISE", kept.getId());
        PendingChange contestedChange = pendingOf("PROMISE", contested.getId());

        assertThat(body(approve(keptChange.getId(), approver).andExpect(status().isOk())).get("status").asText())
                .isEqualTo("APPROVED");
        PaymentPromise overridden = promiseRepository.findById(kept.getId()).orElseThrow();
        assertThat(overridden.getStatus()).isEqualTo(PromiseStatus.KEPT);
        assertThat(overridden.isStatusOverridden()).isTrue();
        assertThat(overridden.getOverrideReason()).isEqualTo("Paid in cash");
        assertThat(audit("PROMISE", kept.getId(), "PROMISE_STATUS_OVERRIDDEN")).singleElement().satisfies(a -> {
            assertThat(a.getChangedByUserId()).isEqualTo(collector.getId());
            assertThat(a.getApprovedByUserId()).isEqualTo(approver.getId());
        });

        // Calling it broken writes nothing off, so it is made at once.
        override(contested.getId(), "BROKEN", "Customer went quiet", admin).andExpect(status().isOk());
        JsonNode failed = body(approve(contestedChange.getId(), approver).andExpect(status().isOk()));

        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("failureReason").asText()).isEqualTo("Status override was changed by someone else");
        PaymentPromise after = promiseRepository.findById(contested.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PromiseStatus.BROKEN);
        assertThat(after.getOverrideReason()).isEqualTo("Customer went quiet");
    }

    /**
     * A held change of terms is made when approved, keeping an edit of a field it leaves alone. One whose due
     * date someone else moved meanwhile fails rather than undo them (three-way).
     */
    @Test
    void aHeldChangeOfTermsIsMadeWhenApprovedAndFailsIfTheDueDateMovedMeanwhile() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_TERMS, "100.00");
        User seller2 = staffIn("seller", "SALES_POC", north, AccessLevel.MANAGE);
        Invoice extended = invoice(acme, "500.00");
        Invoice contested = invoice(acme, "400.00");
        LocalDate due = extended.getDueDate();
        patchInvoice(extended.getId(), Map.of("dueDate", due.plusDays(30).toString(), "notes", "Agreed by phone"),
                maker).andExpect(status().isAccepted());
        patchInvoice(contested.getId(), Map.of("dueDate", contested.getDueDate().plusDays(30).toString()), maker)
                .andExpect(status().isAccepted());
        PendingChange extendedChange = pendingOf("INVOICE", extended.getId());
        PendingChange contestedChange = pendingOf("INVOICE", contested.getId());
        // Meanwhile the invoice is handed to another seller, which the change leaves alone.
        patchInvoice(extended.getId(), Map.of("salesPocUserId", seller2.getId()), admin).andExpect(status().isOk());

        assertThat(body(approve(extendedChange.getId(), approver).andExpect(status().isOk())).get("status").asText())
                .isEqualTo("APPROVED");
        Invoice after = invoiceRepository.findById(extended.getId()).orElseThrow();
        assertThat(after.getDueDate()).isEqualTo(due.plusDays(30));
        assertThat(after.getNotes()).isEqualTo("Agreed by phone");
        assertThat(after.getSalesPoc().getId()).as("the other edit is kept").isEqualTo(seller2.getId());

        // Bringing the due date forward costs nothing, so it is made at once, and the extension now meets it.
        LocalDate earlier = contested.getDueDate().minusDays(5);
        patchInvoice(contested.getId(), Map.of("dueDate", earlier.toString()), admin).andExpect(status().isOk());
        JsonNode failed = body(approve(contestedChange.getId(), approver).andExpect(status().isOk()));

        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("failureReason").asText()).isEqualTo("Due date was changed by someone else");
        assertThat(invoiceRepository.findById(contested.getId()).orElseThrow().getDueDate()).isEqualTo(earlier);
    }

    /** A SOUTH invoice of 2500, notes as given, whose terms move later only with approval above 1000. */
    private record SouthTerms(Invoice invoice, User maker, User approver) {}

    private SouthTerms southInvoiceForTerms(String notes) throws Exception {
        threshold(south, ApprovalOperation.INVOICE_TERMS, "1000.00");
        Customer southCo = customer("South Co", south);
        Invoice invoice = invoice(southCo, "2500.00");
        if (notes != null) {
            patchInvoice(invoice.getId(), Map.of("notes", notes), admin).andExpect(status().isOk());
        }
        return new SouthTerms(invoice, makerIn(south), approverIn(south));
    }

    /**
     * APP-R1-1: the invoice page sends the notes with every save, so a held change of terms carries them
     * unchanged; approving it applies the terms only, and the note someone else wrote meanwhile is kept.
     */
    @Test
    void anApprovedChangeOfTermsKeepsNotesSomeoneElseWroteMeanwhile() throws Exception {
        SouthTerms t = southInvoiceForTerms("first");
        Long id = t.invoice().getId();
        patchInvoice(id, Map.of("notes", "first", "paymentTerm", "NET_90"), t.maker())
                .andExpect(status().isAccepted());
        PendingChange held = pendingOf("INVOICE", id);
        patchInvoice(id, Map.of("notes", "second"), admin).andExpect(status().isOk());

        JsonNode decided = body(approve(held.getId(), t.approver()).andExpect(status().isOk()));

        assertThat(decided.get("status").asText()).isEqualTo("APPROVED");
        Invoice after = invoiceRepository.findById(id).orElseThrow();
        assertThat(after.getPaymentTerm()).isEqualTo(PaymentTerm.NET_90);
        assertThat(after.getNotes()).as("the later note is kept").isEqualTo("second");
    }

    /** APP-R1-1: a Sales POC the held change carried unchanged is not put back over a reassignment made since. */
    @Test
    void anApprovedChangeOfTermsKeepsASalesPocReassignedMeanwhile() throws Exception {
        SouthTerms t = southInvoiceForTerms(null);
        Long id = t.invoice().getId();
        User seller2 = staffIn("seller", "SALES_POC", south, AccessLevel.MANAGE);
        patchInvoice(id, Map.of("salesPocUserId", seller.getId(), "paymentTerm", "NET_90"), t.maker())
                .andExpect(status().isAccepted());
        PendingChange held = pendingOf("INVOICE", id);
        patchInvoice(id, Map.of("salesPocUserId", seller2.getId()), admin).andExpect(status().isOk());

        JsonNode decided = body(approve(held.getId(), t.approver()).andExpect(status().isOk()));

        assertThat(decided.get("status").asText()).isEqualTo("APPROVED");
        Invoice after = invoiceRepository.findById(id).orElseThrow();
        assertThat(after.getPaymentTerm()).isEqualTo(PaymentTerm.NET_90);
        assertThat(after.getSalesPoc().getId()).as("the reassignment is kept").isEqualTo(seller2.getId());
    }

    /**
     * APP-R1-2: blank notes are no notes. A change of terms sent with an empty notes field (as the invoice page
     * sends it) shows no Notes row, and a note added meanwhile is neither a conflict nor wiped by the approval.
     */
    @Test
    void blankNotesOnAHeldChangeOfTermsAreNoNotes() throws Exception {
        SouthTerms t = southInvoiceForTerms(null);
        Long id = t.invoice().getId();
        patchInvoice(id, Map.of("notes", "", "paymentTerm", "NET_90"), t.maker()).andExpect(status().isAccepted());
        PendingChange held = pendingOf("INVOICE", id);

        JsonNode detail = body(mockMvc.perform(get("/api/approvals/" + held.getId()).with(as(t.approver())))
                .andExpect(status().isOk()));
        List<String> fields = new java.util.ArrayList<>();
        detail.get("changes").forEach(c -> fields.add(c.get("field").asText()));
        assertThat(fields).contains("paymentTerm").doesNotContain("notes");

        patchInvoice(id, Map.of("notes", "call Friday"), admin).andExpect(status().isOk());
        JsonNode decided = body(approve(held.getId(), t.approver()).andExpect(status().isOk()));

        assertThat(decided.get("status").asText()).isEqualTo("APPROVED");
        Invoice after = invoiceRepository.findById(id).orElseThrow();
        assertThat(after.getPaymentTerm()).isEqualTo(PaymentTerm.NET_90);
        assertThat(after.getNotes()).isEqualTo("call Friday");
    }

    /**
     * APP-R1-4: the changes an approver reads name people and terms, not ids and codes, under labels people use.
     */
    @Test
    void heldChangesReadWithNamesAndPlainLabels() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CREATE, "100.00");
        Product gadget = product("Gadget", "250.00");
        Map<String, Object> req = new HashMap<>();
        req.put("customerId", acme.getId());
        req.put("salesPocUserId", seller.getId());
        req.put("paymentTerm", "CUSTOM");
        req.put("dueDate", Today.date().plusDays(20).toString());
        req.put("items", List.of(Map.of("productId", gadget.getId(), "quantity", 2)));
        Long createId = body(mockMvc.perform(post("/api/invoices").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isAccepted())).get("pendingChange").get("id").asLong();

        Map<String, JsonNode> create = changesOf(createId);
        assertThat(create.get("salesPocUserId").get("label").asText()).isEqualTo("Sales POC");
        assertThat(create.get("salesPocUserId").get("proposedText").asText())
                .isEqualTo(MakerChecker.displayName(seller));
        assertThat(create.get("paymentTerm").get("label").asText()).isEqualTo("Payment term");
        assertThat(create.get("paymentTerm").get("proposedText").asText()).isEqualTo(PaymentTerm.CUSTOM.label());
        assertThat(create.get("invoiceDate").get("label").asText()).isEqualTo("Invoice date");
        assertThat(create.get("dueDate").get("label").asText()).isEqualTo("Due date");
        assertThat(create.get("dueDate").get("proposedText").isNull()).as("the client formats dates").isTrue();

        threshold(north, ApprovalOperation.PAYMENT_RECORD, "100.00");
        User collector = collectorIn(north);
        Long payId = body(pay(acme, "300.00", collector, maker).andExpect(status().isAccepted()))
                .get("pendingChange").get("id").asLong();
        Map<String, JsonNode> payment = changesOf(payId);
        assertThat(payment.get("collectionPocUserId").get("label").asText()).isEqualTo("Collection POC");
        assertThat(payment.get("collectionPocUserId").get("proposedText").asText())
                .isEqualTo(MakerChecker.displayName(collector));

        assertThat(PendingChangeViews.label("customerSuccessPocUserId")).isEqualTo("Customer Success POC");
        assertThat(PendingChangeViews.label("customerId")).isEqualTo("Customer");
        assertThat(PendingChangeViews.label("paidAt")).isEqualTo("Paid on");
        assertThat(PendingChangeViews.label("backupPocUserId")).isEqualTo("Backup POC");
        assertThat(PendingChangeViews.label("ruleId")).isEqualTo("Rule");
        assertThat(PendingChangeViews.label("paidAmount")).isEqualTo("Paid amount");
        assertThat(PendingChangeViews.text("method", objectMapper.getNodeFactory().textNode("BANK_TRANSFER"),
                Map.of(), Map.of())).isEqualTo("Bank transfer");
        assertThat(PendingChangeViews.text("paymentTerm", objectMapper.getNodeFactory().textNode("WEEKLY"),
                Map.of(), Map.of())).as("not a payment term").isNull();
        assertThat(PendingChangeViews.text("notes", objectMapper.getNodeFactory().textNode("OK"),
                Map.of(), Map.of())).as("free text is the user's own").isNull();
        assertThat(PendingChangeViews.text("salesPocUserId", objectMapper.getNodeFactory().numberNode(987654L),
                Map.of(), Map.of())).isEqualTo("user #987654");
    }

    /**
     * APP-R2-1: a held payment names the invoices it pays by their numbers, and a held invoice names each line's
     * product; a create's empty list of ids is no row at all.
     */
    @Test
    void heldPaymentAndInvoiceNameTheirInvoicesAndProducts() throws Exception {
        Invoice paid = invoice(acme, "800.00");
        threshold(north, ApprovalOperation.PAYMENT_RECORD, "100.00");
        User collector = collectorIn(north);
        Map<String, Object> payReq = new HashMap<>();
        payReq.put("customerId", acme.getId());
        payReq.put("amount", new java.math.BigDecimal("700.00"));
        payReq.put("method", "Bank");
        payReq.put("collectionPocUserId", collector.getId());
        payReq.put("invoiceIds", List.of(paid.getId()));
        Long payId = body(mockMvc.perform(post("/api/payments").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content(json(payReq)))
                .andExpect(status().isAccepted())).get("pendingChange").get("id").asLong();
        Map<String, JsonNode> payment = changesOf(payId);
        assertThat(payment.get("invoiceIds").get("label").asText()).isEqualTo("Invoices");
        assertThat(payment.get("invoiceIds").get("proposedText").asText()).isEqualTo(paid.getInvoiceNumber());
        assertThat(payment).as("no promises named, nothing set").doesNotContainKey("promiseIds");

        threshold(north, ApprovalOperation.INVOICE_CREATE, "100.00");
        Product gadget = product("Gadget", "250.00");
        Map<String, Object> req = new HashMap<>();
        req.put("customerId", acme.getId());
        req.put("salesPocUserId", seller.getId());
        req.put("items", List.of(
                Map.of("productId", gadget.getId(), "quantity", 150, "unitPrice", 100),
                Map.of("productId", gadget.getId(), "quantity", 2)));
        Long createId = body(mockMvc.perform(post("/api/invoices").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isAccepted())).get("pendingChange").get("id").asLong();
        Map<String, JsonNode> create = changesOf(createId);
        assertThat(create.get("items").get("label").asText()).isEqualTo("Items");
        assertThat(create.get("items").get("proposedText").asText())
                .as("the held create keeps the catalogue price it resolved for a line sent without one")
                .isEqualTo("150 × Gadget at " + com.geneinvoice.common.Money.format(new java.math.BigDecimal("100"))
                        + "\n2 × Gadget at " + com.geneinvoice.common.Money.format(new java.math.BigDecimal("250")));
        assertThat(create.get("items").get("proposed").isArray()).as("the raw value stays").isTrue();

        var nodes = objectMapper.getNodeFactory();
        assertThat(PendingChangeViews.text("invoiceIds", nodes.arrayNode().add(987654L),
                PendingChangeViews.Names.NONE)).isEqualTo("invoice #987654");
        assertThat(PendingChangeViews.text("promiseIds", nodes.arrayNode().add(987654L),
                PendingChangeViews.Names.NONE)).isEqualTo("promise #987654");
        assertThat(PendingChangeViews.text("invoiceIds", nodes.arrayNode(), PendingChangeViews.Names.NONE))
                .isEqualTo("None");
        assertThat(PendingChangeViews.text("invoiceIds", nodes.nullNode(), PendingChangeViews.Names.NONE))
                .isNull();
        assertThat(PendingChangeViews.text("items",
                nodes.arrayNode().add(nodes.objectNode().put("productId", 987654L).put("quantity", 1)),
                PendingChangeViews.Names.NONE)).isEqualTo("1 × product #987654 at list price");
        assertThat(PendingChangeViews.text("items", nodes.arrayNode().add(nodes.objectNode()
                        .put("productId", 987654L).put("quantity", 1).putNull("unitPrice")),
                PendingChangeViews.Names.NONE)).isEqualTo("1 × product #987654 at list price");
        assertThat(PendingChangeViews.label("invoiceIds")).isEqualTo("Invoices");
        assertThat(PendingChangeViews.label("promiseIds")).isEqualTo("Promises");
    }

    private Map<String, JsonNode> changesOf(Long pendingChangeId) throws Exception {
        JsonNode detail = body(mockMvc.perform(get("/api/approvals/" + pendingChangeId).with(as(approver)))
                .andExpect(status().isOk()));
        Map<String, JsonNode> changes = new HashMap<>();
        detail.get("changes").forEach(c -> changes.put(c.get("field").asText(), c));
        return changes;
    }

    private ResultActions patchInvoice(Long invoiceId, Map<String, Object> body, User as) throws Exception {
        return mockMvc.perform(patch("/api/invoices/" + invoiceId).with(as(as))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private static int sequenceOf(String invoiceNumber) {
        return Integer.parseInt(invoiceNumber.substring(invoiceNumber.lastIndexOf('-') + 1));
    }

    /** G26: a maker who can no longer act makes the approval fail with the reason, never a 401. */
    @Test
    void anApprovalTheMakerCanNoLongerMakeFailsWithTheReason() throws Exception {
        User gone = userRepository.findById(maker.getId()).orElseThrow();
        gone.setActive(false);
        userRepository.save(gone);

        JsonNode detail = body(approve(pc.getId(), approver).andExpect(status().isOk()));

        assertThat(detail.get("status").asText()).isEqualTo("FAILED");
        assertThat(detail.get("failureReason").asText()).isEqualTo("The requester's account is no longer active");
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus()).isEqualTo(InvoiceStatus.UNPAID);
        PendingChange failed = reload(pc);
        assertThat(failed.getStatus()).isEqualTo(PendingStatus.FAILED);
        assertThat(failed.getDecidedByUserId()).isEqualTo(approver.getId());
        assertThat(failed.getOutstandingKey()).isNull();
        assertThat(audit("INVOICE", inv.getId(), "CHANGE_APPLY_FAILED")).singleElement()
                .satisfies(a -> assertThat(a.getChangedByUserId()).isEqualTo(approver.getId()));
        assertThat(audit("INVOICE", inv.getId(), "INVOICE_CANCELLED")).isEmpty();
        assertThat(noticesTo(maker, ApprovalNotifier.FAILED)).hasSize(1);
    }

    @Test
    void anApprovalTheMakerNoLongerHasTheRightToMakeFails() throws Exception {
        // The maker lost the right to manage invoices in NORTH meanwhile.
        grantRepository.deleteAllOfUser(maker.getId());
        grant(maker, north, AccessLevel.READ, RecordScope.ALL);

        JsonNode detail = body(approve(pc.getId(), approver).andExpect(status().isOk()));

        assertThat(detail.get("status").asText()).isEqualTo("FAILED");
        assertThat(detail.get("failureReason").asText())
                .isEqualTo("The requester no longer has the right to make this change");
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus()).isEqualTo(InvoiceStatus.UNPAID);
    }
}
