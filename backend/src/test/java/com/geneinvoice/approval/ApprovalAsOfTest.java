package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.common.Money;
import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.history.AsOfScenario;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.RegionMoveDtos;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Approvals as of a date (B3, §5.11.10): a change is outstanding from its request until it is decided (D8), so the
 * list, the summary and count, a record's waiting changes, the list rows' flag and filter, a change by id and the
 * History tab all say what was waiting that day, and a change decided since reads {@code statusAt: PENDING}. Rights
 * are today's, applied to the region the change was asked in, which never changes.
 */
class ApprovalAsOfTest extends AsOfScenario {

    @Test
    void aChangeIsOutstandingFromItsRequestUntilItIsDecided() throws Exception {
        Long[] ids = xExtendedWithApproval();
        Long first = ids[0];
        Long second = ids[1];

        assertThat(rows(read("/api/approvals", D4))).isEmpty();

        JsonNode d5 = read("/api/approvals", D5);
        assertThat(d5.get("asOf").asText()).isEqualTo(D5.toString());
        assertThat(rows(d5)).singleElement().satisfies(r -> {
            assertThat(r.get("id").asLong()).isEqualTo(first);
            assertThat(r.get("status").asText()).as("where it stands today").isEqualTo("SUPERSEDED");
            assertThat(r.get("statusAt").asText()).as("where it stood then").isEqualTo("PENDING");
            assertThat(r.get("regionId").asLong()).isEqualTo(south.getId());
        });
        assertThat(rows(read("/api/approvals", D6))).singleElement().satisfies(r -> {
            assertThat(r.get("id").asLong()).as("the first was replaced on D6").isEqualTo(second);
            assertThat(r.get("status").asText()).isEqualTo("APPROVED");
            assertThat(r.get("statusAt").asText()).isEqualTo("PENDING");
        });
        assertThat(rows(read("/api/approvals", D7))).as("approved on D7").isEmpty();

        // The status filter asks where a change stood that day, as statusAt answers it.
        assertThat(rows(read("/api/approvals", D5, "status:eq:PENDING"))).extracting(r -> r.get("id").asLong())
                .containsExactly(first);
        assertThat(rows(read("/api/approvals", D5, "status:in:PENDING,APPROVED"))).hasSize(1);
        assertThat(rows(read("/api/approvals", D5, "status:eq:SUPERSEDED"))).as("not superseded until D6").isEmpty();
        assertThat(rows(read("/api/approvals", D5, "status:neq:PENDING"))).isEmpty();
        assertThat(rows(read("/api/approvals", D5, "status:notIn:SUPERSEDED"))).hasSize(1);
        call(get("/api/approvals").param("filter", "status:eq:NOPE"), admin, D5).andExpect(status().isBadRequest());
        assertThat(rows(read("/api/approvals", null, "status:eq:SUPERSEDED"))).extracting(r -> r.get("id").asLong())
                .as("live, the status now").containsExactly(first);

        JsonNode live = read("/api/approvals", null);
        assertThat(rows(live)).extracting(r -> r.get("id").asLong()).containsExactlyInAnyOrder(first, second);
        assertThat(rows(live)).allSatisfy(r -> assertThat(r.get("statusAt").isNull()).isTrue());

        // By id: the second did not exist yet on D5; the first was waiting then, and cannot be decided from there.
        JsonNode firstThen = body(call(get("/api/approvals/" + first), admin, D5).andExpect(status().isOk()));
        assertThat(firstThen.get("statusAt").asText()).isEqualTo("PENDING");
        assertThat(firstThen.get("canApprove").asBoolean()).isFalse();
        assertThat(firstThen.get("blockedReason").asText()).isEqualTo("Read-only in the as-of view");
        call(get("/api/approvals/" + second), admin, D5).andExpect(status().isNotFound());
        assertThat(body(call(get("/api/approvals/" + first), admin, null)).get("statusAt").isNull()).isTrue();
    }

    @Test
    void theSummaryCountAndARecordsWaitingChangesAreAnsweredAsOfTheDay() throws Exception {
        Long[] ids = xExtendedWithApproval();

        JsonNode d5 = read("/api/approvals/summary", D5);
        assertThat(d5.get("pendingCount").asLong()).isEqualTo(1);
        assertThat(d5.get("pendingAmount").decimalValue()).as("X's unpaid balance").isEqualByComparingTo("1000.00");
        assertThat(d5.get("awaitingMeCount").asLong()).isEqualTo(1);
        assertThat(read("/api/approvals/summary", D7).get("pendingCount").asLong()).isZero();
        assertThat(read("/api/approvals/summary", null).get("pendingCount").asLong()).isZero();
        assertThat(read("/api/approvals/awaiting-me/count", D6).get("count").asLong()).isEqualTo(1);
        assertThat(read("/api/approvals/awaiting-me/count", null).get("count").asLong()).isZero();

        String onX = "/api/approvals/for-record?entityType=INVOICE&entityId=" + x;
        assertThat(rows(read(onX, D5))).extracting(r -> r.get("id").asLong()).containsExactly(ids[0]);
        assertThat(rows(read(onX, D6))).extracting(r -> r.get("id").asLong()).containsExactly(ids[1]);
        assertThat(rows(read(onX, D7))).isEmpty();
        assertThat(rows(read(onX, null))).isEmpty();
    }

    @Test
    void theListFlagAndFilterSayWhatWasWaitingThatDay() throws Exception {
        xExtendedWithApproval();

        assertThat(flagOnX(D5)).isTrue();
        assertThat(flagOnX(D6)).isTrue();
        assertThat(flagOnX(D7)).isFalse();
        assertThat(flagOnX(null)).isFalse();
        assertThat(ids(read("/api/invoices", D6, "pendingApproval:eq:true"))).containsExactly(x);
        assertThat(ids(read("/api/invoices", D7, "pendingApproval:eq:true"))).isEmpty();
        assertThat(ids(read("/api/invoices", null, "pendingApproval:eq:true"))).isEmpty();
        assertThat(ids(read("/api/invoices", D6, "pendingApproval:eq:false"))).doesNotContain(x).contains(b);
    }

    @Test
    void theHistoryTabSaysWhereEachChangeStoodThatDay() throws Exception {
        Long[] ids = xExtendedWithApproval();
        String trail = "/api/audit?entityType=INVOICE&entityId=" + x;

        JsonNode d5 = read(trail, D5);
        assertThat(rows(d5)).filteredOn(r -> r.get("action").asText().equals("CHANGE_REQUESTED"))
                .singleElement().satisfies(r -> {
                    assertThat(r.get("pendingChangeId").asLong()).isEqualTo(ids[0]);
                    assertThat(r.get("pendingStatus").asText()).isEqualTo("PENDING");
                });
        JsonNode live = read(trail, null);
        assertThat(rows(live)).filteredOn(r -> r.get("action").asText().equals("CHANGE_REQUESTED"))
                .extracting(r -> r.get("pendingStatus").asText()).containsExactlyInAnyOrder("SUPERSEDED", "APPROVED");
        assertThat(rows(live)).filteredOn(r -> r.get("action").asText().equals("INVOICE_DUE_DATE_CHANGED"))
                .singleElement().satisfies(r -> {
                    assertThat(r.get("pendingChangeId").asLong()).isEqualTo(ids[1]);
                    assertThat(r.get("pendingStatus").asText()).isEqualTo("APPROVED");
                    assertThat(r.get("approvedByUsername").asText()).isEqualTo("admin");
                });
    }

    /**
     * PC-16: a change closed by a move (the mover approves changes where the customer was) was waiting, before the
     * move, in the region it was asked in; asked about that region then it is there, asked about the new one it is not.
     */
    @Test
    void aChangeClosedByAMoveWasWaitingBeforeItInTheOldRegion() throws Exception {
        approvalThresholdRepository.save(ApprovalThreshold.builder().region(north)
                .operation(ApprovalOperation.INVOICE_CANCEL).amount(new BigDecimal("50.00")).windowHours(0)
                .updatedByUserId(admin.getId()).updatedAt(AppClock.now()).build());
        User nina = user("nina.cashier", "CASHIER");
        grant(nina, north, AccessLevel.MANAGE, RecordScope.ALL);
        on(D5);
        actAs(nina);
        Long held = invoiceService.cancelOrHold(b).heldChange().orElseThrow().getId();
        on(D6);
        actAs(admin);
        RegionMoveDtos.MoveResult moved = moveService.move(beta.getId(),
                new RegionMoveDtos.MoveRequest(south.getId(), null, "Beta joins the South team"));
        SecurityContextHolder.clearContext();
        on(D7);

        assertThat(moved.moved().other().get("PendingChangeMoveParticipant")).isEqualTo(1);
        PendingChange closed = pendingChangeRepository.findById(held).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(PendingStatus.CLOSED);
        assertThat(closed.getRegionId()).as("a change keeps the region it was asked in").isEqualTo(north.getId());

        assertThat(rows(read(narrowed("/api/approvals", north.getId()), D5))).singleElement().satisfies(r -> {
            assertThat(r.get("id").asLong()).isEqualTo(held);
            assertThat(r.get("regionId").asLong()).isEqualTo(north.getId());
            assertThat(r.get("statusAt").asText()).isEqualTo("PENDING");
        });
        assertThat(rows(read(narrowed("/api/approvals", south.getId()), D5))).isEmpty();
        assertThat(rows(read(narrowed("/api/approvals", north.getId()), D6))).as("closed by the move on D6").isEmpty();
        assertThat(rows(read(narrowed("/api/approvals", north.getId()), null))).singleElement()
                .satisfies(r -> assertThat(r.get("status").asText()).isEqualTo("CLOSED"));
        // Beta's invoice was in NORTH on D5, with a change waiting on it.
        assertThat(ids(read("/api/invoices", D5, "pendingApproval:eq:true", "regionId:eq:" + north.getId())))
                .containsExactly(b);
    }

    @Test
    void aDecisionCannotBeMadeWhileAPastDateIsViewed() throws Exception {
        approvalThresholdRepository.save(ApprovalThreshold.builder().region(north)
                .operation(ApprovalOperation.INVOICE_TERMS).amount(BigDecimal.ONE).windowHours(0)
                .updatedByUserId(admin.getId()).updatedAt(AppClock.now()).build());
        User nina = user("nina.cashier", "CASHIER");
        grant(nina, north, AccessLevel.MANAGE, RecordScope.ALL);
        actAs(nina);
        Long held = invoiceService.updateOrHold(b, new InvoiceDtos.UpdateInvoiceRequest(null, null,
                TODAY.plusDays(90), null)).heldChange().orElseThrow().getId();
        SecurityContextHolder.clearContext();

        JsonNode refused = body(call(post("/api/approvals/" + held + "/approve")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), admin, D4).andExpect(status().isBadRequest()));
        assertThat(refused.get("error").asText()).isEqualTo("AS_OF_READ_ONLY");
        assertThat(pendingChangeRepository.findById(held).orElseThrow().getStatus()).isEqualTo(PendingStatus.PENDING);
    }

    /**
     * ASO-R1-2: asked as of a day it was still waiting, a change decided since shows no decision: not who decided,
     * when, the note or what it made, in the detail or the list row. Its status today is still given; live, the
     * decision is all there.
     */
    @Test
    void aChangeReadAsOfADayBeforeItsDecisionShowsNoDecision() throws Exception {
        Long approved = xExtendedWithApproval()[1];

        JsonNode then = body(call(get("/api/approvals/" + approved), admin, D6).andExpect(status().isOk()));
        assertThat(then.get("statusAt").asText()).isEqualTo("PENDING");
        assertThat(then.get("status").asText()).as("where it stands today").isEqualTo("APPROVED");
        for (String field : List.of("decidedBy", "decidedAt", "decisionNote", "failureReason", "supersededById",
                "result")) {
            assertThat(then.path(field).isNull() || then.path(field).isMissingNode()).as(field).isTrue();
        }
        JsonNode row = rows(read("/api/approvals", D6)).stream()
                .filter(r -> r.get("id").asLong() == approved).findFirst().orElseThrow();
        assertThat(row.path("decidedAt").isNull() || row.path("decidedAt").isMissingNode()).isTrue();

        JsonNode live = body(call(get("/api/approvals/" + approved), admin, null).andExpect(status().isOk()));
        assertThat(live.get("decidedBy").get("id").asLong()).isEqualTo(admin.getId());
        assertThat(live.get("decidedAt").isTextual()).isTrue();
        assertThat(live.get("decisionNote").asText()).isEqualTo("Agreed with the customer");
        assertThat(live.get("result").get("link").asText()).isEqualTo("/invoices/" + x);
        JsonNode liveRow = rows(read("/api/approvals", null)).stream()
                .filter(r -> r.get("id").asLong() == approved).findFirst().orElseThrow();
        assertThat(liveRow.get("decidedAt").isTextual()).isTrue();
    }

    /**
     * ASO-R3-1 (and APP-R2-1 as of a date): a held change whose rows name invoices, promises, products or the
     * customer opens as of a day, with each named as it was that day, not a 400 AS_OF_UNSUPPORTED. <b>D5</b>: Mia, a
     * SOUTH maker, holds an invoice create on Gadget, a payment on X that keeps the promise, a change of the promise's
     * invoices, and an invoice create admin approves the same day. <b>D7</b>: Gadget and the customer are renamed.
     * Asked about D6 every change reads the old names; live, the new.
     */
    @Test
    void aHeldChangeNamingInvoicesPromisesAndProductsReadsAsOfADay() throws Exception {
        on(D5);
        actAs(admin);
        Long z = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, D5.plusDays(30),
                null, null, sam.getId(), List.of(new InvoiceDtos.LineInput(gadget.getId(), 1, null)))).getId();
        for (ApprovalOperation op : List.of(ApprovalOperation.INVOICE_CREATE, ApprovalOperation.PAYMENT_RECORD,
                ApprovalOperation.PROMISE_UPDATE)) {
            approvalThresholdRepository.save(ApprovalThreshold.builder().region(south).operation(op)
                    .amount(new BigDecimal("100.00")).windowHours(0)
                    .updatedByUserId(admin.getId()).updatedAt(AppClock.now()).build());
        }
        User mia = user("mia.maker", "ADMIN");
        grantRepository.deleteAllOfUser(mia.getId());
        grant(mia, south, AccessLevel.MANAGE, RecordScope.ALL);
        actAs(mia);
        Long create = invoiceService.createOrHold(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null,
                D5.plusDays(30), null, null, sam.getId(), List.of(new InvoiceDtos.LineInput(gadget.getId(), 2,
                        new BigDecimal("300.00"))))).heldChange().orElseThrow().getId();
        Long payment = paymentService.recordOrHold(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("700.00"), "Bank", null, List.of(x), colin.getId(), List.of(promiseId)))
                .heldChange().orElseThrow().getId();
        Long promise = promiseService.updateOrHold(promiseId, new PromiseDtos.UpdatePromiseRequest(
                new BigDecimal("450.00"), D2.plusDays(7), colin.getId(), null, List.of(x, z)))
                .heldChange().orElseThrow().getId();
        Long approved = invoiceService.createOrHold(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null,
                D5.plusDays(30), null, null, sam.getId(), List.of(new InvoiceDtos.LineInput(gadget.getId(), 1,
                        new BigDecimal("150.00"))))).heldChange().orElseThrow().getId();
        actAs(admin);
        approvalService.approve(approved, "Fine");

        on(D7);
        Product renamed = productRepository.findById(gadget.getId()).orElseThrow();
        renamed.setName("Gadget Max");
        productRepository.save(renamed);
        customerService.update(acme.getId(),
                new CustomerDtos.CustomerUpdateRequest("Omega Corp", null, null, null, null, null));
        SecurityContextHolder.clearContext();
        on(AFTER_D7);

        String xNumber = invoiceRepository.findById(x).orElseThrow().getInvoiceNumber();
        String zNumber = invoiceRepository.findById(z).orElseThrow().getInvoiceNumber();
        String promised = "Promise of " + Money.format(new BigDecimal("600.00")) + " by " + D2.plusDays(7);
        String twoGadgets = "2 × %s at " + Money.format(new BigDecimal("300.00"));
        String oneGadget = "1 × %s at " + Money.format(new BigDecimal("150.00"));

        for (Long id : List.of(create, payment, promise, approved)) {
            JsonNode then = body(call(get("/api/approvals/" + id), admin, D6).andExpect(status().isOk()));
            assertThat(then.get("statusAt").asText()).as("change %d", id)
                    .isEqualTo(id.equals(approved) ? "APPROVED" : "PENDING");
            assertThat(then.get("canApprove").asBoolean()).isFalse();
            assertThat(then.get("canReject").asBoolean()).isFalse();
            assertThat(then.get("canWithdraw").asBoolean()).isFalse();
            assertThat(then.get("blockedReason").asText()).isEqualTo("Read-only in the as-of view");
            assertThat(then.get("current").isNull()).isTrue();
            assertThat(then.get("customerName").asText()).as("the customer as it was asked about")
                    .isEqualTo("Zeta Holdings");
        }

        Map<String, JsonNode> createThen = changes(create, D6);
        assertThat(createThen.get("items").get("label").asText()).isEqualTo("Items");
        assertThat(createThen.get("items").get("proposedText").asText()).isEqualTo(twoGadgets.formatted("Gadget"));
        assertThat(changes(approved, D6).get("items").get("proposedText").asText())
                .isEqualTo(oneGadget.formatted("Gadget"));

        Map<String, JsonNode> paymentThen = changes(payment, D6);
        assertThat(paymentThen.get("invoiceIds").get("label").asText()).isEqualTo("Invoices");
        assertThat(paymentThen.get("invoiceIds").get("proposedText").asText()).isEqualTo(xNumber);
        assertThat(paymentThen.get("promiseIds").get("label").asText()).isEqualTo("Promises");
        assertThat(paymentThen.get("promiseIds").get("proposedText").asText()).isEqualTo(promised);

        Map<String, JsonNode> promiseThen = changes(promise, D6);
        assertThat(promiseThen.get("invoiceIds").get("proposedText").asText()).as("in the list's order")
                .isEqualTo(xNumber + ", " + zNumber);
        assertThat(promiseThen.get("invoiceIds").get("currentText").isNull()).as("no 'now' as of a date").isTrue();

        Map<String, JsonNode> createNow = changes(create, null);
        assertThat(createNow.get("items").get("proposedText").asText()).isEqualTo(twoGadgets.formatted("Gadget Max"));
        assertThat(changes(approved, null).get("items").get("proposedText").asText())
                .isEqualTo(oneGadget.formatted("Gadget Max"));
        Map<String, JsonNode> paymentNow = changes(payment, null);
        assertThat(paymentNow.get("invoiceIds").get("proposedText").asText()).isEqualTo(xNumber);
        assertThat(paymentNow.get("promiseIds").get("proposedText").asText()).isEqualTo(promised);
        assertThat(changes(promise, null).get("invoiceIds").get("proposedText").asText())
                .isEqualTo(xNumber + ", " + zNumber);
    }

    /** The detail's changes by field, as admin on {@code day} (null: live). */
    private Map<String, JsonNode> changes(Long id, LocalDate day) throws Exception {
        JsonNode detail = body(call(get("/api/approvals/" + id), admin, day).andExpect(status().isOk()));
        Map<String, JsonNode> out = new HashMap<>();
        detail.get("changes").forEach(c -> out.put(c.get("field").asText(), c));
        return out;
    }

    private boolean flagOnX(LocalDate day) throws Exception {
        return StreamSupport.stream(read("/api/invoices", day).get("content").spliterator(), false)
                .filter(r -> r.get("id").asLong() == x).findFirst().orElseThrow()
                .get("pendingApproval").asBoolean();
    }

    private static String narrowed(String url, Long regionId) {
        return url + "?regionIds=" + regionId;
    }

    private static List<JsonNode> rows(JsonNode node) {
        JsonNode rows = node.isArray() ? node : node.get("content");
        return StreamSupport.stream(rows.spliterator(), false).toList();
    }

    private static List<Long> ids(JsonNode page) {
        return rows(page).stream().map(r -> r.get("id").asLong()).toList();
    }
}
