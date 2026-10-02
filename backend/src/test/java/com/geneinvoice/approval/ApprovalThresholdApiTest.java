package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A region's approval limits (§6.11, §5.11.11): read by those who set them or approve changes there, set by those who
 * set them. Tightening takes effect at once; loosening is itself held for another approver as a THRESHOLD_CHANGE
 * (SC-11), and until it is approved the old limit still holds. Every change is audited and told to the other
 * approvers, and a new region starts with the default region's limits (SC-4).
 */
class ApprovalThresholdApiTest extends ApprovalTestBase {

    Region north;
    User approver;
    User maker;
    Customer acme;

    @BeforeEach
    void setUp() {
        north = workRegion("NORTH");
        approver = approverIn(north);
        maker = makerIn(north);
        acme = customer("Acme Ltd", north);
    }

    @Test
    void theLimitsListEveryOperationWithItsWindow() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CREATE, "100000.00", 24);

        JsonNode view = body(limits(north, admin).andExpect(status().isOk()));

        assertThat(view.get("regionId").asLong()).isEqualTo(north.getId());
        assertThat(view.get("regionName").asText()).isEqualTo("North");
        assertThat(view.get("canEdit").asBoolean()).isTrue();
        assertThat(view.get("activeApprovers").asLong()).as("admin and the approver").isEqualTo(2);
        assertThat(view.get("pendingChange").isNull()).isTrue();
        assertThat(view.has("warnings")).isFalse();
        assertThat(view.get("thresholds")).hasSize(8);
        assertThat(view.get("thresholds")).extracting(r -> r.get("operation").asText()).containsExactly(
                "INVOICE_CREATE", "INVOICE_CANCEL", "INVOICE_TERMS", "PAYMENT_RECORD", "PROMISE_CREATE",
                "PROMISE_UPDATE", "PROMISE_OVERRIDE", "DISPUTE_APPROVE");
        JsonNode create = view.get("thresholds").get(0);
        assertThat(create.get("label").asText()).isEqualTo("Create invoice");
        assertThat(create.get("measure").asText()).isEqualTo("Invoice total");
        assertThat(create.get("amount").decimalValue()).isEqualByComparingTo("100000.00");
        assertThat(create.get("windowHours").asInt()).isEqualTo(24);
        assertThat(create.get("updatedBy").get("id").asLong()).isEqualTo(admin.getId());
        JsonNode cancel = view.get("thresholds").get(1);
        assertThat(cancel.get("amount").isNull()).as("not gated").isTrue();
        assertThat(cancel.get("windowHours").isNull()).isTrue();

        JsonNode approversView = body(limits(north, approver).andExpect(status().isOk()));
        assertThat(approversView.get("thresholds")).hasSize(8);
        mockMvc.perform(get("/api/approvals/operations").with(as(maker))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].operation").value("INVOICE_CREATE"))
                .andExpect(jsonPath("$[0].measure").value("Invoice total"))
                .andExpect(jsonPath("$[8].operation").value("THRESHOLD_CHANGE"))
                .andExpect(jsonPath("$[8].entityType").value("REGION"));
    }

    @Test
    void onlyThoseWhoSetOrApproveInTheRegionMayReadAndOnlySettersMayChange() throws Exception {
        Region south = workRegion("SOUTH");
        User southApprover = approverIn(south);
        limits(north, southApprover).andExpect(status().isForbidden());
        setLimits(north, southApprover, row("INVOICE_CREATE", "10.00", null)).andExpect(status().isForbidden());

        // Resolves things in NORTH but holds no APPROVE grant there: neither sets nor approves.
        User manager = staffIn("manny", "ADMIN", north, AccessLevel.MANAGE);
        limits(north, manager).andExpect(status().isForbidden());
        setLimits(north, manager, row("INVOICE_CREATE", "10.00", null)).andExpect(status().isForbidden());
        setLimits(north, maker, row("INVOICE_CREATE", "10.00", null)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/regions/999999/approval-thresholds").with(as(admin)))
                .andExpect(status().isNotFound());

        assertThat(approvalThresholdRepository.count()).isZero();
        mockMvc.perform(put("/api/regions/" + north.getId() + "/approval-thresholds").with(as(admin))
                .param("asOf", "2020-01-01").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("thresholds", List.of(row("INVOICE_CREATE", "10.00", null))))))
                .andExpect(status().isBadRequest());
        assertThat(approvalThresholdRepository.count()).isZero();
    }

    @Test
    void eachBadRowIsReportedAgainstItsField() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("NO_SUCH_THING", "10.00", null));
        rows.add(row("THRESHOLD_CHANGE", "10.00", null));
        rows.add(row("INVOICE_CREATE", "-1.00", null));
        rows.add(row("PAYMENT_RECORD", "10.001", null));
        rows.add(row("PROMISE_CREATE", "10.00", 721));
        rows.add(row("PROMISE_UPDATE", "10.00", -1));
        rows.add(row("INVOICE_CREATE", "10.00", null));

        JsonNode errors = body(mockMvc.perform(put("/api/regions/" + north.getId() + "/approval-thresholds")
                .with(as(admin)).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("thresholds", rows))))
                .andExpect(status().isBadRequest())).get("fieldErrors");

        assertThat(errors.has("thresholds[0].operation")).isTrue();
        assertThat(errors.has("thresholds[1].operation")).as("a limit's own change has no limit").isTrue();
        assertThat(errors.get("thresholds[2].amount").asText()).isEqualTo("must be 0 or more");
        assertThat(errors.has("thresholds[3].amount")).isTrue();
        assertThat(errors.has("thresholds[4].windowHours")).isTrue();
        assertThat(errors.has("thresholds[5].windowHours")).isTrue();
        assertThat(errors.get("thresholds[6].operation").asText()).isEqualTo("Create invoice is listed twice");
        mockMvc.perform(put("/api/regions/" + north.getId() + "/approval-thresholds").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.thresholds").exists());

        assertThat(approvalThresholdRepository.findAll()).singleElement()
                .satisfies(t -> assertThat(t.getAmount()).isEqualByComparingTo("100.00"));
        assertThat(pendingChangeRepository.count()).isZero();
        assertThat(audit("REGION", north.getId(), ApprovalThresholdService.CHANGED)).isEmpty();
    }

    @Test
    void tighteningTakesEffectAtOnceAndIsAuditedAndTold() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "1000.00");

        JsonNode view = body(setLimits(north, admin, row("INVOICE_CANCEL", "500.00", null),
                row("PAYMENT_RECORD", "2000.00", 48)).andExpect(status().isOk()));

        assertThat(view.get("thresholds").get(1).get("amount").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(view.get("thresholds").get(1).get("windowHours").asInt()).as("an existing row keeps its window")
                .isZero();
        assertThat(view.get("thresholds").get(3).get("amount").decimalValue()).isEqualByComparingTo("2000.00");
        assertThat(view.get("thresholds").get(3).get("windowHours").asInt()).isEqualTo(48);
        assertThat(view.get("pendingChange").isNull()).isTrue();
        assertThat(pendingChangeRepository.count()).isZero();
        assertThat(audit("REGION", north.getId(), ApprovalThresholdService.CHANGED)).singleElement().satisfies(a -> {
            assertThat(a.getChangedByUserId()).isEqualTo(admin.getId());
            assertThat(a.getBeforeJson()).contains("\"invoiceCancel\"").contains("1000.00");
            assertThat(a.getAfterJson()).contains("\"paymentRecord\"").contains("500.00");
        });
        assertThat(noticesTo(approver, ApprovalThresholdService.CHANGED)).hasSize(1);
        assertThat(noticesTo(admin, ApprovalThresholdService.CHANGED)).as("not the one who changed them").isEmpty();

        // A new row with no window gets the default one.
        setLimits(north, admin, row("PROMISE_CREATE", "700.00", null)).andExpect(status().isOk());
        assertThat(approvalThresholdRepository.findByRegionIdAndOperation(north.getId(),
                ApprovalOperation.PROMISE_CREATE).orElseThrow().getWindowHours()).isEqualTo(24);
    }

    /** SC-11: nobody lifts the limit their own change is held by; another approver must agree first. */
    @Test
    void looseningIsHeldForAnotherApproverAndTheOldLimitHoldsMeanwhile() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        threshold(north, ApprovalOperation.PAYMENT_RECORD, "100.00");
        Invoice inv = invoice(acme, "500.00");

        ResultActions held = setLimits(north, admin, row("INVOICE_CANCEL", "1000.00", null),
                row("PAYMENT_RECORD", null, null)).andExpect(status().isAccepted());
        JsonNode view = body(held);
        PendingChange pc = pendingChangeRepository.findByOutstandingKey("REGION_THRESHOLDS:" + north.getId())
                .orElseThrow();
        assertThat(held.andReturn().getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("/api/approvals/" + pc.getId());
        assertThat(view.get("pendingChange").get("id").asLong()).isEqualTo(pc.getId());
        assertThat(view.get("pendingChange").get("operation").asText()).isEqualTo("THRESHOLD_CHANGE");
        assertThat(view.get("pendingChange").get("entityType").asText()).isEqualTo("REGION");
        assertThat(view.get("pendingChange").get("customerId").isNull()).isTrue();
        assertThat(view.get("thresholds").get(1).get("amount").decimalValue()).as("unchanged until approved")
                .isEqualByComparingTo("100.00");
        assertThat(view.get("thresholds").get(3).get("amount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(pc.getSummary()).isEqualTo("Loosen approval limits in North: Cancel invoice ₹100.00 → ₹1,000.00;"
                + " Record payment ₹100.00 → no limit");
        assertThat(pc.getEntityId()).isEqualTo(north.getId());
        assertThat(pc.getAmount()).isEqualByComparingTo("0");
        assertThat(audit("REGION", north.getId(), "CHANGE_REQUESTED")).hasSize(1);
        assertThat(audit("REGION", north.getId(), ApprovalThresholdService.CHANGED)).singleElement()
                .satisfies(a -> assertThat(a.getReason()).isEqualTo("Loosening held for approval as #" + pc.getId()));
        assertThat(noticesTo(approver, ApprovalNotifier.REQUESTED)).hasSize(1);
        assertThat(noticesTo(approver, ApprovalThresholdService.CHANGED)).hasSize(1);

        // The old limit still gates.
        cancel(inv.getId(), maker).andExpect(status().isAccepted());
        withdraw(pendingOf("INVOICE", inv.getId()).getId(), maker).andExpect(status().isOk());

        // The maker cannot approve it; the detail shows what changes.
        approve(pc.getId(), admin).andExpect(status().isBadRequest());
        JsonNode detail = body(mockMvc.perform(get("/api/approvals/" + pc.getId()).with(as(approver)))
                .andExpect(status().isOk()));
        assertThat(detail.get("changes")).extracting(c -> c.get("field").asText())
                .containsExactlyInAnyOrder("invoiceCancel", "paymentRecord");
        assertThat(detail.at("/current/invoiceCancel/amount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(detail.at("/proposed/paymentRecord").isNull()).isTrue();
        assertThat(detail.get("canApprove").asBoolean()).isTrue();

        approve(pc.getId(), approver).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

        // CT-5: what it changed is the region's limits, never this approval again.
        String limitsLink = "/approval-thresholds?regionId=" + north.getId();
        JsonNode approved = body(mockMvc.perform(get("/api/approvals/" + pc.getId()).with(as(approver)))
                .andExpect(status().isOk()));
        assertThat(approved.at("/result/entityType").asText()).isEqualTo("REGION");
        assertThat(approved.at("/result/entityId").asLong()).isEqualTo(north.getId());
        assertThat(approved.at("/result/link").asText()).isEqualTo(limitsLink);
        assertThat(noticesTo(admin, ApprovalNotifier.APPROVED)).singleElement()
                .satisfies(n -> assertThat(n.getLink()).isEqualTo(limitsLink));

        assertThat(approvalThresholdRepository.findByRegionIdAndOperation(north.getId(),
                ApprovalOperation.INVOICE_CANCEL).orElseThrow().getAmount()).isEqualByComparingTo("1000.00");
        assertThat(approvalThresholdRepository.findByRegionIdAndOperation(north.getId(),
                ApprovalOperation.PAYMENT_RECORD)).isEmpty();
        assertThat(audit("REGION", north.getId(), ApprovalThresholdService.CHANGED)).hasSize(2)
                .anySatisfy(a -> {
                    assertThat(a.getChangedByUserId()).as("made by its maker").isEqualTo(admin.getId());
                    assertThat(a.getApprovedByUserId()).isEqualTo(approver.getId());
                    assertThat(a.getPendingChangeId()).isEqualTo(pc.getId());
                });
        cancel(inv.getId(), maker).andExpect(status().isOk());
    }

    @Test
    void aMixedChangeAppliesItsTighteningAndHoldsTheRest() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00", 0);
        threshold(north, ApprovalOperation.INVOICE_CREATE, "5000.00", 24);

        JsonNode view = body(setLimits(north, admin, row("INVOICE_CANCEL", "1000.00", null),
                row("INVOICE_CREATE", "4000.00", 0), row("PROMISE_CREATE", "300.00", null))
                .andExpect(status().isAccepted()));

        JsonNode rows = view.get("thresholds");
        assertThat(rows.get(0).get("amount").decimalValue()).as("the lower amount at once")
                .isEqualByComparingTo("4000.00");
        assertThat(rows.get(0).get("windowHours").asInt()).as("the shorter window waits").isEqualTo(24);
        assertThat(rows.get(1).get("amount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(rows.get(4).get("amount").decimalValue()).as("a new row at once").isEqualByComparingTo("300.00");
        PendingChange pc = pendingChangeRepository.findByOutstandingKey("REGION_THRESHOLDS:" + north.getId())
                .orElseThrow();
        JsonNode proposed = objectMapper.readTree(pc.getProposedJson());
        assertThat(proposed.fieldNames()).toIterable().containsExactlyInAnyOrder("invoiceCancel", "invoiceCreate");
        assertThat(proposed.at("/invoiceCreate/windowHours").asInt()).isZero();

        approve(pc.getId(), approver).andExpect(status().isOk());
        ApprovalThreshold create = approvalThresholdRepository.findByRegionIdAndOperation(north.getId(),
                ApprovalOperation.INVOICE_CREATE).orElseThrow();
        assertThat(create.getAmount()).isEqualByComparingTo("4000.00");
        assertThat(create.getWindowHours()).isZero();
    }

    @Test
    void aSecondLooseningReplacesTheMakersOwnAndConflictsWithAnyoneElses() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        setLimits(north, admin, row("INVOICE_CANCEL", "1000.00", null)).andExpect(status().isAccepted());
        Long first = pendingChangeRepository.findByOutstandingKey("REGION_THRESHOLDS:" + north.getId())
                .orElseThrow().getId();

        setLimits(north, admin, row("INVOICE_CANCEL", "2000.00", null)).andExpect(status().isAccepted());
        assertThat(pendingChangeRepository.findById(first).orElseThrow().getStatus())
                .isEqualTo(PendingStatus.SUPERSEDED);

        setLimits(north, approver, row("INVOICE_CANCEL", "5000.00", null)).andExpect(status().isConflict());
        assertThat(pendingCount()).isEqualTo(1);
    }

    @Test
    void anApprovedLooseningFailsIfTheLimitWasChangedSince() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        setLimits(north, admin, row("INVOICE_CANCEL", "1000.00", null)).andExpect(status().isAccepted());
        PendingChange pc = pendingChangeRepository.findByOutstandingKey("REGION_THRESHOLDS:" + north.getId())
                .orElseThrow();
        setLimits(north, approver, row("INVOICE_CANCEL", "50.00", null)).andExpect(status().isOk());

        approve(pc.getId(), approver).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("The invoice cancel limit was changed by someone else"));
        assertThat(approvalThresholdRepository.findByRegionIdAndOperation(north.getId(),
                ApprovalOperation.INVOICE_CANCEL).orElseThrow().getAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    void aRegionWithOneApproverIsWarnedAbout() throws Exception {
        Region east = workRegion("EAST");

        JsonNode view = body(setLimits(east, admin, row("INVOICE_CANCEL", "100.00", null)).andExpect(status().isOk()));

        assertThat(view.get("activeApprovers").asLong()).isEqualTo(1);
        assertThat(view.get("warnings")).extracting(JsonNode::asText).containsExactly(
                "Only 1 person can approve changes in East, and nobody can approve their own changes");
    }

    /** SC-4: a region made later starts no laxer than the default one. */
    @Test
    void aNewRegionStartsWithTheDefaultRegionsLimits() throws Exception {
        threshold(defaultRegion(), ApprovalOperation.INVOICE_CANCEL, "100.00", 12);
        threshold(defaultRegion(), ApprovalOperation.PAYMENT_RECORD, "5000.00");

        Long west = body(mockMvc.perform(post("/api/regions").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("code", "WEST", "name", "West"))))
                .andExpect(status().isOk())).get("id").asLong();

        JsonNode view = body(mockMvc.perform(get("/api/regions/" + west + "/approval-thresholds").with(as(admin)))
                .andExpect(status().isOk()));
        assertThat(view.get("thresholds").get(1).get("amount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(view.get("thresholds").get(1).get("windowHours").asInt()).isEqualTo(12);
        assertThat(view.get("thresholds").get(3).get("amount").decimalValue()).isEqualByComparingTo("5000.00");
        assertThat(StreamSupport.stream(view.get("thresholds").spliterator(), false)
                .filter(r -> !r.get("amount").isNull())).hasSize(2);
        assertThat(audit("REGION", west, ApprovalThresholdService.CHANGED)).singleElement()
                .satisfies(a -> assertThat(a.getReason()).startsWith("Copied from the default region"));
    }

    /**
     * A region made by mistake still goes, limits and all, once nothing about it is waiting: a change of its limits
     * still waiting is decided first, and a decided one goes with it.
     */
    @Test
    void anEmptyRegionIsDeletedWithItsLimits() throws Exception {
        threshold(defaultRegion(), ApprovalOperation.INVOICE_CANCEL, "100.00");
        Long west = newRegion("WEST", "West");
        send(delete("/api/regions/" + west), admin).andExpect(status().isNoContent());
        assertThat(regionRepository.findById(west)).isEmpty();
        assertThat(approvalThresholdRepository.findByRegionId(west)).isEmpty();

        Long east = newRegion("EAST", "East");
        User other = approverIn(regionRepository.findById(east).orElseThrow());
        Region eastRegion = regionRepository.findById(east).orElseThrow();
        setLimits(eastRegion, admin, row("INVOICE_CANCEL", "1000.00", null)).andExpect(status().isAccepted());
        PendingChange pc = pendingChangeRepository.findByOutstandingKey("REGION_THRESHOLDS:" + east).orElseThrow();
        grantRepository.deleteAllOfUser(other.getId());
        send(delete("/api/regions/" + east), admin).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message")
                .value("Region has a change of its approval limits waiting; decide or withdraw it first"));
        assertThat(regionRepository.findById(east)).isPresent();

        withdraw(pc.getId(), admin).andExpect(status().isOk());
        send(delete("/api/regions/" + east), admin).andExpect(status().isNoContent());
        assertThat(regionRepository.findById(east)).isEmpty();
        assertThat(pendingChangeRepository.findById(pc.getId())).isEmpty();
        assertThat(audit("REGION", east, "CHANGE_REQUESTED")).as("its history outlives it").hasSize(1);
    }

    /** A region some record's change was asked for in keeps that history: it is deactivated, not deleted. */
    @Test
    void aRegionWithHeldChangesOfRecordsIsDeactivatedInstead() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice inv = invoice(acme, "500.00");
        cancel(inv.getId(), maker).andExpect(status().isAccepted());
        withdraw(pendingOf("INVOICE", inv.getId()).getId(), maker).andExpect(status().isOk());
        send(post("/api/customers/" + acme.getId() + "/move-region").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("regionId", defaultRegion().getId()))), admin).andExpect(status().isOk());
        grantRepository.deleteAllInRegion(north.getId());

        send(delete("/api/regions/" + north.getId()), admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Region has changes that were held for approval; deactivate it instead"));
    }

    private Long newRegion(String code, String name) throws Exception {
        return body(mockMvc.perform(post("/api/regions").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("code", code, "name", name))))
                .andExpect(status().isOk())).get("id").asLong();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, User as) throws Exception {
        return mockMvc.perform(request.with(as(as)));
    }

    private static Map<String, Object> row(String operation, String amount, Integer windowHours) {
        Map<String, Object> row = new HashMap<>();
        row.put("operation", operation);
        row.put("amount", amount == null ? null : new BigDecimal(amount));
        if (windowHours != null) row.put("windowHours", windowHours);
        return row;
    }

    private ResultActions limits(Region region, User as) throws Exception {
        return mockMvc.perform(get("/api/regions/" + region.getId() + "/approval-thresholds").with(as(as)));
    }

    @SafeVarargs
    private ResultActions setLimits(Region region, User as, Map<String, Object>... rows) throws Exception {
        return mockMvc.perform(put("/api/regions/" + region.getId() + "/approval-thresholds").with(as(as))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("thresholds", List.of(rows)))));
    }
}
