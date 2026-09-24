package com.geneinvoice.approval;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.Region;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The gate's own configuration, held by the gate. A threshold change is the one edit in this
 * application that is maker-checked whatever its size, because the alternative is that whoever
 * holds APPROVAL_CONFIGURE raises their own limit, pushes their own payment through and lowers it
 * back, and the audit trail shows three perfectly ordinary acts (B2).
 *
 * <p>The tests that must not be skipped are {@code aThresholdChangeDoesNotTakeEffectUntil-
 * SomebodyElseApprovesIt} and {@code theSamePersonCannotApproveTheThresholdChangeTheyProposed}:
 * between them they are the whole reason APPROVAL_THRESHOLD_SET carries alwaysChecked. The first
 * proves it behaviourally rather than by reading a row — the same payment goes straight through
 * before the approval and is held after it.
 *
 * <p>Every 403 here is asserted on the status and not on the sentence, because
 * GlobalExceptionHandler answers every AccessDeniedException with one fixed message and has done
 * since long before B2.
 */
class ApprovalThresholdTest extends IntegrationTestBase {

    @Autowired AuditService auditService;
    @Autowired PrivilegeRepository privilegeRepository;

    /** The one region every fixture customer is placed in, resolved once per test. */
    Long home;

    User maker;         // APPROVAL_CONFIGURE, and enough to move money, and no approval right
    User checker;       // APPROVAL_APPROVE and nothing else
    User watcher;       // APPROVAL_VIEW only: may read a limit, may not propose one
    User collections;
    Customer acme;

    @BeforeEach
    void setUp() {
        home = defaultRegion().getId();
        maker = user("tina.threshold", makerRole().getName());
        checker = user("carl.checker2", checkerRole().getName());
        watcher = user("wanda.watcher", watcherRole().getName());
        collections = user("cora.collections2", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
    }

    // ------------------------------------------------------------------------ who may propose

    @Test
    void onlyAConfigureHolderMayProposeAThresholdChange() throws Exception {
        // APPROVAL_VIEW is enough to READ the limit and never enough to move it: seeing the
        // number a change was measured against and choosing that number are two different acts.
        mockMvc.perform(get("/api/approvals/thresholds/" + home).with(as(watcher)))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/approvals/thresholds/" + home).with(as(watcher))
                        .contentType(MediaType.APPLICATION_JSON).content(body("1000.00", true)))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/approvals/thresholds/" + home).with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content(body("1000.00", true)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value(ApprovalDtos.Accepted.PENDING_APPROVAL))
                .andExpect(jsonPath("$.action").value("APPROVAL_THRESHOLD_SET"))
                .andExpect(jsonPath("$.targetType").value("REGION"))
                .andExpect(jsonPath("$.targetId").value(home))
                .andExpect(jsonPath("$.regionId").value(home))
                // A threshold belongs to a branch and to nobody's account (B2).
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andExpect(jsonPath("$.path").value("/api/approvals/thresholds/" + home));

        // The watcher's refusal parked nothing: a 403 at the annotation never reaches the gate.
        assertThat(pendingChangeRepository.count()).isEqualTo(1);
        assertThat(approvalThresholdRepository.findByRegionId(home)).isEmpty();
    }

    @Test
    void aConfigureHolderCannotProposeAThresholdForARegionTheyCannotManage() throws Exception {
        Region west = region("WEST");

        // 403 and not 404: the caller NAMED the branch, so no id space is being probed and
        // pretending it does not exist would be a lie about a region they can see on the map.
        // This is the one place in B2 where the contract goes the other way from AUTH-08 (D-46).
        mockMvc.perform(put("/api/approvals/thresholds/" + west.getId()).with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content(body("1000.00", true)))
                .andExpect(status().isForbidden());

        assertThat(pendingChangeRepository.count()).isZero();
        assertThat(approvalThresholdRepository.findByRegionId(west.getId())).isEmpty();

        // The same person, the same request, in the branch they do manage: accepted. Without this
        // arm the assertion above would also pass for a threshold endpoint nobody can ever use.
        mockMvc.perform(put("/api/approvals/thresholds/" + home).with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content(body("1000.00", true)))
                .andExpect(status().isAccepted());
    }

    /**
     * Not one of the unit's eight named tests, and here because the guard it pins is not in the
     * design either: a wildcard grant says yes to every id, including one no region ever had.
     */
    @Test
    void aThresholdForARegionThatDoesNotExistIsNotFoundEvenForAWildcardHolder() throws Exception {
        User admin = userRepository.findByUsername("admin").orElseThrow();
        long nowhere = regionRepository.findAll().stream().mapToLong(Region::getId).max().orElse(0L) + 500;

        // regionOf(REGION, id) answers with the id itself rather than reading a row, so without
        // the guard this parks a change nobody could ever usefully approve (B2).
        mockMvc.perform(put("/api/approvals/thresholds/" + nowhere).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body("1000.00", true)))
                .andExpect(status().isNotFound());
        assertThat(pendingChangeRepository.count()).isZero();
    }

    // ------------------------------------------------------- the point of the whole exercise

    @Test
    void aThresholdChangeDoesNotTakeEffectUntilSomebodyElseApprovesIt() throws Exception {
        MvcResult proposed = mockMvc.perform(put("/api/approvals/thresholds/" + home).with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content(body("1000.00", true)))
                .andExpect(status().isAccepted())
                .andReturn();
        assertThat(approvalThresholdRepository.findByRegionId(home)).isEmpty();

        // Proved behaviourally rather than by reading a row: while the change only waits, the
        // limit it proposes holds nothing, and a payment far above it is recorded on the spot.
        mockMvc.perform(post("/api/payments").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content(json(payment("8000.00"))))
                .andExpect(status().isOk());
        assertThat(paymentRepository.count()).isEqualTo(1);

        mockMvc.perform(post("/api/approvals/" + jsonLong(proposed, "pendingChangeId") + "/approve")
                        .with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.change.status").value("APPROVED"))
                .andExpect(jsonPath("$.result.amount").value(1000.00))
                .andExpect(jsonPath("$.result.enabled").value(true))
                // No longer the deployment default: this branch now has a row of its own (B2).
                .andExpect(jsonPath("$.result.fromDefault").value(false));

        ApprovalThreshold row = approvalThresholdRepository.findByRegionId(home).orElseThrow();
        assertThat(row.getAmount()).isEqualByComparingTo("1000.00");
        assertThat(row.isEnabled()).isTrue();
        // The THRESHOLD_CHANGED row names the maker; the CHANGE_APPROVED row beside it names the
        // approver. The limit is what the maker asked for and the decision is somebody else's.
        assertThat(row.getUpdatedByUserId()).isEqualTo(maker.getId());

        // The same payment, after the approval: held. That is the limit having taken effect.
        mockMvc.perform(post("/api/payments").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content(json(payment("8000.00"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.thresholdApplied").value(1000.00));
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    @Test
    void theSamePersonCannotApproveTheThresholdChangeTheyProposed() throws Exception {
        // Everything one person would need to raise their own gate: the right to configure it AND
        // the right to approve changes in this branch. The refusal below is about identity, not
        // about a missing privilege, which is why this user holds both (B2).
        User both = user("brian.both", configureAndApproveRole().getName());

        MvcResult proposed = mockMvc.perform(put("/api/approvals/thresholds/" + home).with(as(both))
                        .contentType(MediaType.APPLICATION_JSON).content(body("5000000.00", true)))
                .andExpect(status().isAccepted())
                .andReturn();
        Long changeId = jsonLong(proposed, "pendingChangeId");

        // The panel says so before the button is pressed, and the endpoint says so after.
        mockMvc.perform(get("/api/approvals/" + changeId).with(as(both)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.canDecide").value(false))
                .andExpect(jsonPath("$.alwaysChecked").value(true));

        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(both))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertThat(approvalThresholdRepository.findByRegionId(home)).isEmpty();
        assertThat(pendingChangeRepository.findById(changeId).orElseThrow().getStatus())
                .isEqualTo(PendingChangeStatus.PENDING);

        // Somebody else, holding nothing but the approval right, lands the very same change.
        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        assertThat(approvalThresholdRepository.findByRegionId(home).orElseThrow().getAmount())
                .isEqualByComparingTo("5000000.00");
    }

    @Test
    void aThresholdChangeWhoseMakerHasSinceLostManageInThatBranchIsNotApplied() throws Exception {
        Region west = region("WEST");

        // The change that matters most: it switches approval checking OFF in this branch.
        MvcResult proposed = mockMvc.perform(put("/api/approvals/thresholds/" + home).with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content(body("0.00", false)))
                .andExpect(status().isAccepted())
                .andReturn();
        Long changeId = jsonLong(proposed, "pendingChangeId");

        // DEMOTED, not revoked: she keeps MANAGE in another branch, so APPROVAL_CONFIGURE stays in
        // her authority set and the authority-drop rule is not what refuses this. What is left of
        // her here is VIEW, which is enough to get past asMaker's own pre-check (B1, B2).
        revokeRegionGrants(maker);
        grantTo(maker, home, com.geneinvoice.region.RegionRight.VIEW);
        grantTo(maker, west.getId(), com.geneinvoice.region.RegionRight.MANAGE);

        // The replay runs as the maker and meets the region gate the maker-facing half opens with.
        // Every other applier branch is caught by its mutator's own regionAccess.requireManage;
        // this is the one action that configures the gate itself (B2, B1 INTEGRATION).
        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        assertThat(approvalThresholdRepository.findByRegionId(home))
                .as("the limit did not move, and checking is still on in this branch")
                .isEmpty();
        // Refused, never auto-rejected: losing a grant is not a judgement on the change (B2).
        assertThat(pendingChangeRepository.findById(changeId).orElseThrow().getStatus())
                .isEqualTo(PendingChangeStatus.PENDING);

        // The same change, once she manages the branch again: landed. Without this arm the
        // assertion above would also pass for a threshold nobody can ever set.
        grantTo(maker, home, com.geneinvoice.region.RegionRight.MANAGE);
        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        assertThat(approvalThresholdRepository.findByRegionId(home).orElseThrow().isEnabled())
                .isFalse();
    }

    // ------------------------------------------------- what moving the limit does NOT disturb

    @Test
    void changingAThresholdLeavesAChangeAlreadyPendingMeasuredAgainstTheOneItWasRaisedUnder()
            throws Exception {
        seedThreshold(home, "1000.00", true);

        MvcResult heldPayment = mockMvc.perform(post("/api/payments").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content(json(payment("8000.00"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.thresholdApplied").value(1000.00))
                .andReturn();
        Long paymentChange = jsonLong(heldPayment, "pendingChangeId");

        // Raise the limit well above the waiting payment and land it.
        MvcResult raise = mockMvc.perform(put("/api/approvals/thresholds/" + home).with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content(body("100000.00", true)))
                .andExpect(status().isAccepted())
                .andReturn();
        mockMvc.perform(post("/api/approvals/" + jsonLong(raise, "pendingChangeId") + "/approve")
                        .with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        assertThat(approvalThresholdRepository.findByRegionId(home).orElseThrow().getAmount())
                .isEqualByComparingTo("100000.00");

        // The waiting payment is neither auto-approved nor re-measured. threshold_applied is
        // frozen on the row at raise time precisely so the queue can say what the change was
        // judged against, and so that moving a limit is never a way to release money (B2).
        PendingChange waiting = pendingChangeRepository.findById(paymentChange).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(PendingChangeStatus.PENDING);
        assertThat(waiting.getThresholdApplied()).isEqualByComparingTo("1000.00");
        assertThat(paymentRepository.count()).isZero();

        // And it is still decidable in the ordinary way afterwards.
        mockMvc.perform(post("/api/approvals/" + paymentChange + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    @Test
    void aSecondThresholdChangeForOneRegionIsRefusedWhileTheFirstIsWaiting() throws Exception {
        MvcResult first = mockMvc.perform(put("/api/approvals/thresholds/" + home).with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content(body("1000.00", true)))
                .andExpect(status().isAccepted())
                .andReturn();
        Long changeId = jsonLong(first, "pendingChangeId");

        // pending_key is "REGION:<id>", so one threshold change per branch can wait at a time and
        // the second is named rather than merely refused — otherwise the maker goes hunting
        // through a queue for a change they may not even be able to see (B2).
        mockMvc.perform(put("/api/approvals/thresholds/" + home).with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content(body("2000.00", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "A change on this record is already waiting for approval (change #"
                                + changeId + ")"));

        assertThat(pendingChangeRepository.count()).isEqualTo(1);
        assertThat(pendingChangeRepository.findByPendingKey("REGION:" + home))
                .get().extracting(PendingChange::getId).isEqualTo(changeId);

        // Another branch is another key, and is not blocked by this one.
        Region west = region("WEST");
        grantTo(maker, west.getId(), com.geneinvoice.region.RegionRight.MANAGE);
        mockMvc.perform(put("/api/approvals/thresholds/" + west.getId()).with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content(body("2000.00", true)))
                .andExpect(status().isAccepted());

        // Deciding the first releases the key, and the branch can be proposed for again.
        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        assertThat(pendingChangeRepository.findByPendingKey("REGION:" + home)).isEmpty();
        mockMvc.perform(put("/api/approvals/thresholds/" + home).with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content(body("2000.00", true)))
                .andExpect(status().isAccepted());
    }

    // ----------------------------------------------------------------------- the paper trail

    @Test
    void aThresholdChangeIsAudited() throws Exception {
        // audit_logs is deliberately never cleaned between tests and the default region's id is
        // stable across the whole run, so every assertion below reads only the rows THIS test
        // caused: another test in this class raises threshold changes against the same anchor.
        long mark = auditMark();

        MvcResult proposed = mockMvc.perform(put("/api/approvals/thresholds/" + home).with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content(body("250000.00", true)))
                .andExpect(status().isAccepted())
                .andReturn();
        Long changeId = jsonLong(proposed, "pendingChangeId");

        // The ask is recorded even though the business transaction rolled back and took its own
        // audit rows with it, or a maker could probe the gate all day and leave no trace (B2).
        assertThat(newRows("REGION", home, mark))
                .filteredOn(a -> "CHANGE_REQUESTED".equals(a.getAction()))
                .extracting(AuditLog::getChangedByUserId)
                .containsExactly(maker.getId());
        // Nothing has been written against the threshold itself yet: the limit has not moved.
        assertThat(newRows(ApprovalThresholdService.ENTITY, home, mark)).isEmpty();

        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        List<AuditLog> onTheThreshold = newRows(ApprovalThresholdService.ENTITY, home, mark);
        assertThat(onTheThreshold).extracting(AuditLog::getAction).containsExactly("THRESHOLD_CHANGED");
        AuditLog changed = onTheThreshold.get(0);
        // The maker and not the approver: the limit is what this person asked for, and the
        // CHANGE_APPROVED row beside it names who allowed it. Between the two the whole four-eyes
        // story is readable off the trail without joining anything (B2).
        assertThat(changed.getChangedByUserId()).isEqualTo(maker.getId());
        assertThat(changed.getBeforeJson()).contains("\"fromDefault\":true");
        assertThat(changed.getAfterJson()).contains("250000.00").contains("\"fromDefault\":false");

        assertThat(newRows("REGION", home, mark))
                .filteredOn(a -> "CHANGE_APPROVED".equals(a.getAction()))
                .extracting(AuditLog::getChangedByUserId)
                .containsExactly(checker.getId());
    }

    /** Audit ids come off one sequence, so the newest row on either anchor bounds them both. */
    private long auditMark() {
        return Math.max(maxAuditId("REGION", home), maxAuditId(ApprovalThresholdService.ENTITY, home));
    }

    private long maxAuditId(String entityType, Long entityId) {
        return auditService.historyFor(entityType, entityId).stream()
                .mapToLong(AuditLog::getId).max().orElse(0L);
    }

    private List<AuditLog> newRows(String entityType, Long entityId, long mark) {
        return auditService.historyFor(entityType, entityId).stream()
                .filter(a -> a.getId() > mark).toList();
    }

    // ------------------------------------------------------------------------- reading a limit

    @Test
    void aRegionWithNoRowReportsTheDeploymentDefaultAndSaysSo() throws Exception {
        Region west = region("WEST");
        // app.approvals.default-threshold is unset in the test profile, exactly as it ships: the
        // feature is installed and dormant, and "dormant" is a fact the screen has to be able to
        // state rather than a zero the operator has to interpret (B2).
        mockMvc.perform(get("/api/approvals/thresholds/" + home).with(as(watcher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionId").value(home))
                .andExpect(jsonPath("$.regionName").value(defaultRegion().getName()))
                .andExpect(jsonPath("$.fromDefault").value(true))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.amount").value(0.00))
                .andExpect(jsonPath("$.updatedByUserId").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist());

        // Per region and not per deployment: one branch having a row does not give another one.
        seedThreshold(west.getId(), "750000.00", true);
        mockMvc.perform(get("/api/approvals/thresholds/" + west.getId()).with(as(watcher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromDefault").value(false))
                .andExpect(jsonPath("$.amount").value(750000.00))
                .andExpect(jsonPath("$.enabled").value(true));
        mockMvc.perform(get("/api/approvals/thresholds/" + home).with(as(watcher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromDefault").value(true));
    }

    // ------------------------------------------------------------------------------ fixtures

    private String body(String amount, boolean enabled) {
        return "{\"amount\":" + amount + ",\"enabled\":" + enabled + "}";
    }

    private PaymentDtos.CreatePaymentRequest payment(String amount) {
        return new PaymentDtos.CreatePaymentRequest(acme.getId(), new BigDecimal(amount), "NEFT",
                null, List.of(), collections.getId(), null);
    }

    /** A row written straight to the table, which is what a limit already in force looks like. */
    private void seedThreshold(Long regionId, String amount, boolean enabled) {
        ApprovalThreshold row = approvalThresholdRepository.findByRegionId(regionId)
                .orElseGet(() -> ApprovalThreshold.builder().regionId(regionId).build());
        row.setAmount(new BigDecimal(amount));
        row.setEnabled(enabled);
        approvalThresholdRepository.saveAndFlush(row);
    }

    private void grantTo(User u, Long regionId, com.geneinvoice.region.RegionRight right) {
        userRegionGrantRepository.save(com.geneinvoice.region.UserRegionGrant.builder()
                .userId(u.getId()).regionId(regionId).right(right).build());
    }

    /** Configure the limit, and move money, and no right to approve anything. */
    private Role makerRole() {
        return roleWith("THRESHOLD_MAKER",
                Privileges.CUSTOMER_VIEW, Privileges.CUSTOMER_MANAGE,
                Privileges.INVOICE_VIEW, Privileges.INVOICE_MANAGE,
                Privileges.PAYMENT_VIEW, Privileges.PAYMENT_MANAGE,
                Privileges.PRODUCT_VIEW, Privileges.POC_VIEW, Privileges.POC_ASSIGN,
                Privileges.SCOPE_OVERRIDE,
                Privileges.APPROVAL_VIEW, Privileges.APPROVAL_CONFIGURE);
    }

    /** An approver and nothing else: cannot configure a limit and cannot move a rupee. */
    private Role checkerRole() {
        return roleWith("THRESHOLD_CHECKER", Privileges.APPROVAL_VIEW, Privileges.APPROVAL_APPROVE);
    }

    /** May read the queue and the limit, and propose neither. */
    private Role watcherRole() {
        return roleWith("THRESHOLD_WATCHER", Privileges.APPROVAL_VIEW);
    }

    private Role configureAndApproveRole() {
        return roleWith("THRESHOLD_CONFIGURE_AND_APPROVE",
                Privileges.APPROVAL_VIEW, Privileges.APPROVAL_APPROVE, Privileges.APPROVAL_CONFIGURE);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by ApprovalThresholdTest")
                .privileges(Arrays.stream(privileges)
                        .map(p -> privilegeRepository.findByName(p).orElseThrow())
                        .collect(Collectors.toCollection(HashSet<Privilege>::new)))
                .build()));
    }

    private Long jsonLong(MvcResult result, String field) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get(field).asLong();
    }
}
