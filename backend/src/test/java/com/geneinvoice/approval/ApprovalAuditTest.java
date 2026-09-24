package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The trail a held change leaves, and the people it wakes up (B2).
 *
 * <p>Two separate claims are under test here and they fail differently. The AUDIT half is about
 * correlation: a change that was held rolled its own business transaction back and took every
 * audit row with it, so the only evidence it ever happened is the row park writes afterwards, and
 * pending_change_id is what ties that row to the CHANGE_APPROVED row three days later and to the
 * money the replay finally moved. The NOTIFICATION half is about address: notifyAdmins resolves
 * the literal role name "ADMIN" and would tell the wrong people in the wrong branches, so park
 * asks the region side who may decide here and leaves the maker out of that list.
 *
 * <p>Every assertion about pending_change_id is read back off the stored AuditLog or off the
 * /api/audit wire, never off the object that was passed in.
 */
class ApprovalAuditTest extends IntegrationTestBase {

    @Autowired AuditService auditService;
    @Autowired ApprovalService approvalService;
    @Autowired CustomerService customerService;
    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired PlatformTransactionManager txManager;

    static final String THOUSAND = "1000.00";
    static final String APPROVAL_REQUESTED = "APPROVAL_REQUESTED";

    User admin;
    User maker;
    User checker;
    User elsewhere;
    User collections;
    Customer acme;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        // The maker holds APPROVAL_APPROVE as well, so "the maker is not notified" is a statement
        // about the exclusion and not about the privilege they happen to lack (B2).
        maker = user("nina.maker", makerRole().getName());
        checker = user("victor.checker", checkerRole().getName());
        elsewhere = approverIn(region("WEST"), "vera.west");
        collections = user("cora.collections5", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
    }

    // ------------------------------------------------------------------------------- the trail

    @Test
    void aChangeThatWasHeldLeavesAChangeRequestedRowAlthoughTheSaveRolledBack() throws Exception {
        threshold(defaultRegion().getId(), THOUSAND, true);

        Long changeId = heldPayment("8000.00");

        // Nothing of the save survived: no payment, and none of the audit rows PaymentService
        // would have written. Without park's own row a maker could probe the gate all day and
        // leave nothing behind at all (B2).
        assertThat(paymentRepository.count()).isZero();
        List<AuditLog> onTheAccount = auditService.historyFor("CUSTOMER", acme.getId());
        assertThat(onTheAccount).extracting(AuditLog::getAction)
                .contains("CHANGE_REQUESTED")
                .doesNotContain("PAYMENT_RECORDED");

        AuditLog requested = only(onTheAccount, "CHANGE_REQUESTED");
        assertThat(requested.getPendingChangeId()).isEqualTo(changeId);
        assertThat(requested.getChangedByUserId()).isEqualTo(maker.getId());
        assertThat(requested.getAfterJson()).contains("\"status\":\"PENDING\"");
    }

    @Test
    void submittingAndWithdrawingAChangeLeavesATrailOfBothActs() throws Exception {
        threshold(defaultRegion().getId(), THOUSAND, true);
        Long changeId = heldPayment("8000.00");

        mockMvc.perform(post("/api/approvals/" + changeId + "/withdraw").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionNotes\":\"Wrong account\"}"))
                .andExpect(status().isOk());

        List<AuditLog> onTheAccount = auditService.historyFor("CUSTOMER", acme.getId());
        assertThat(onTheAccount).extracting(AuditLog::getAction)
                .contains("CHANGE_REQUESTED", "CHANGE_WITHDRAWN");
        // Both acts, and both of them findable from the change rather than by matching
        // timestamps: that is the whole of what the column buys (B2).
        assertThat(onTheAccount).filteredOn(a -> a.getAction().startsWith("CHANGE_"))
                .extracting(AuditLog::getPendingChangeId)
                .containsOnly(changeId);
        assertThat(only(onTheAccount, "CHANGE_WITHDRAWN").getReason()).isEqualTo("Wrong account");
    }

    @Test
    void theChangeApprovedRowNamesTheApproverAndCarriesThePendingChangeId() throws Exception {
        threshold(defaultRegion().getId(), THOUSAND, true);
        Long changeId = heldPayment("8000.00");

        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        Payment paid = paymentRepository.findAll().get(0);
        List<AuditLog> onThePayment = auditService.historyFor("PAYMENT", paid.getId());

        AuditLog approved = only(onThePayment, "CHANGE_APPROVED");
        assertThat(approved.getChangedByUserId()).isEqualTo(checker.getId());
        assertThat(approved.getPendingChangeId()).isEqualTo(changeId);

        // The money is the maker's doing and carries no change id: the mutator wrote it through
        // the eight-arg call, exactly as it does when nobody had to approve anything. The two
        // rows side by side are the four-eyes story (B2).
        AuditLog recorded = only(onThePayment, "PAYMENT_RECORDED");
        assertThat(recorded.getChangedByUserId()).isEqualTo(maker.getId());
        assertThat(recorded.getPendingChangeId()).isNull();

        // And the row park wrote before any of it names the same change, from the other end.
        assertThat(only(auditService.historyFor("CUSTOMER", acme.getId()), "CHANGE_REQUESTED")
                .getPendingChangeId()).isEqualTo(changeId);
    }

    @Test
    void theTimelineOfTheRecordShowsTheChangeAndTheMutatorsOwnRowsAgainstOneApproval() throws Exception {
        threshold(defaultRegion().getId(), THOUSAND, true);
        Long changeId = heldPayment("8000.00");
        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        Payment paid = paymentRepository.findAll().get(0);

        JsonNode timeline = timeline("PAYMENT", paid.getId());

        assertThat(actions(timeline)).contains("CHANGE_APPROVED", "PAYMENT_RECORDED");
        assertThat(pendingChangeIdOf(timeline, "CHANGE_APPROVED")).isEqualTo(changeId);
        assertThat(pendingChangeIdOf(timeline, "PAYMENT_RECORDED")).isNull();
        // ONE approval: everything on this record that belongs to a change belongs to the same
        // one, which is what makes "via approval #N" a sentence a reader can trust (B2).
        assertThat(distinctPendingChangeIds(timeline)).containsExactly(changeId);
        // A derived entry was inferred from the record as it stands, not read from a row somebody
        // wrote, so it can never have been approved (B2).
        for (JsonNode e : timeline) {
            if (e.path("derived").asBoolean()) {
                assertThat(e.path("pendingChangeId").isNull()).isTrue();
            }
        }

        // The other half of the story is on the account, because a create has no target row to
        // anchor to until the replay makes one (B2).
        assertThat(pendingChangeIdOf(timeline("CUSTOMER", acme.getId()), "CHANGE_REQUESTED"))
                .isEqualTo(changeId);
    }

    @Test
    void theEightArgAuditRecordStillWritesANullPendingChangeId() throws Exception {
        // The delegate itself: ~40 hand-placed sites in this codebase call it and none of them
        // was touched, so the one thing that must stay true is that they still compile and still
        // write a row that says "nobody had to approve this" (B2).
        AuditLog direct = auditService.record("CUSTOMER", acme.getId(), "CUSTOMER_UPDATED",
                null, null, admin.getId(), null, "Eight arguments, as before");
        assertThat(direct.getPendingChangeId()).isNull();

        // And a real one, through a save nothing held.
        threshold(defaultRegion().getId(), THOUSAND, true);
        mockMvc.perform(post("/api/payments").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payment(acme, "10.00", List.of()))))
                .andExpect(status().isOk());

        Payment small = paymentRepository.findAll().get(0);
        assertThat(auditService.historyFor("PAYMENT", small.getId()))
                .isNotEmpty()
                .allSatisfy(a -> assertThat(a.getPendingChangeId()).isNull());
        assertThat(pendingChangeRepository.count()).isZero();
    }

    // ----------------------------------------------------------------- and who gets told about it

    @Test
    void theApproversOfThatRegionAreNotifiedAndTheMakerIsNot() throws Exception {
        threshold(defaultRegion().getId(), THOUSAND, true);

        Long changeId = heldPayment("8000.00");

        List<Notification> raised = ofType(APPROVAL_REQUESTED);
        assertThat(raised).extracting(Notification::getUserId)
                // admin holds a wildcard APPROVE grant, victor holds one in this branch
                .contains(admin.getId(), checker.getId())
                // the maker cannot decide their own change, so telling them would be noise; vera
                // may approve in WEST and this account is not there (B2, B1).
                .doesNotContain(maker.getId(), elsewhere.getId());
        assertThat(raised).allSatisfy(n -> {
            assertThat(n.getTitle()).isEqualTo("A change needs approval");
            assertThat(n.getLink()).isEqualTo("/approvals/" + changeId);
            assertThat(n.getMessage()).contains("Record a payment of ₹8,000.00 for Acme Ltd");
        });
        // The cashier reads the queue and may not decide in it, so APPROVAL_VIEW alone is not an
        // address (B2).
        assertThat(raised).extracting(Notification::getUserId)
                .doesNotContain(userRepository.findByUsername("cashier").orElseThrow().getId());
    }

    @Test
    void theMakerIsNotifiedWhenTheirChangeIsApprovedRejectedOrSuperseded() throws Exception {
        threshold(defaultRegion().getId(), THOUSAND, true);

        Long approvedChange = heldPayment("8000.00");
        Long rejectedChange = heldPayment("9000.00");
        mockMvc.perform(post("/api/approvals/" + approvedChange + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/approvals/" + rejectedChange + "/reject").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionNotes\":\"Not this month\"}"))
                .andExpect(status().isOk());

        // The third act needs an account that can actually be deleted, and the deletion is itself
        // always-checked, so it is the APPROVAL of the deletion that supersedes the change
        // waiting beside it (B2, CP-04).
        Customer doomed = opened("Doomed Ltd", "doomed.audit.login");
        Long stranded = heldPaymentFor(doomed, "8000.00");
        MvcResult removal = mockMvc.perform(delete("/api/customers/" + doomed.getId())
                        .with(as(maker)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.action").value("CUSTOMER_DELETE"))
                .andReturn();
        mockMvc.perform(post("/api/approvals/" + jsonLong(removal, "pendingChangeId") + "/approve")
                        .with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        assertThat(pendingChangeRepository.findById(stranded).orElseThrow().getStatus())
                .isEqualTo(PendingChangeStatus.SUPERSEDED);
        // Nobody decided it, so the row names nobody — but it still names the change, which is
        // the only way anyone will ever find out what happened to it (B2).
        AuditLog superseded = only(auditService.historyFor("CUSTOMER", doomed.getId()),
                "CHANGE_SUPERSEDED");
        assertThat(superseded.getPendingChangeId()).isEqualTo(stranded);
        assertThat(superseded.getChangedByUserId()).isNull();
        assertThat(superseded.getReason()).isEqualTo("The account was deleted");

        assertThat(mine(maker)).extracting(Notification::getType)
                .contains("CHANGE_APPROVED", "CHANGE_REJECTED", "CHANGE_SUPERSEDED");
        assertThat(mine(maker)).extracting(Notification::getType)
                .doesNotContain(APPROVAL_REQUESTED);
        // Every one of them is a link the maker can follow back to the change itself.
        assertThat(mine(maker)).allSatisfy(n -> assertThat(n.getLink()).startsWith("/approvals/"));
        // The approver decided them; nobody tells the approver about their own decision (B2).
        assertThat(mine(checker)).extracting(Notification::getType)
                .doesNotContain("CHANGE_APPROVED", "CHANGE_REJECTED", "CHANGE_SUPERSEDED");
    }

    /**
     * The unit's eighth test is listed as "an engine-raised change with no maker notifies nobody
     * on the way in". It does not, and the name is the only part of the design this unit did not
     * follow: {@code notifyEach}'s own rule is "everyone in the list except that one", a change
     * the engine raised has no maker to leave out, and a change nobody asked for personally still
     * has to be decided by somebody. What IS true, and is what the null maker could break, is
     * that nothing tries to address a notification to nobody — notifications.user_id is NOT NULL
     * and park would refuse the whole 202 rather than hold the change (B2, A5 INTEGRATION).
     */
    @Test
    void aChangeRaisedByTheEngineWithNoMakerTellsEveryApproverAndNotifiesNoMaker() {
        PendingChange raisedByNobody = PendingChange.builder()
                .action(PendingAction.PAYMENT_RECORD)
                .targetType(PendingTargetType.PAYMENT)
                .targetId(null)
                .customerId(acme.getId())
                .regionId(defaultRegion().getId())
                .exposure(new BigDecimal("8000.00"))
                .thresholdApplied(new BigDecimal(THOUSAND))
                .payloadJson("{}")
                .summary("Record a payment of ₹8,000.00 for Acme Ltd")
                .status(PendingChangeStatus.PENDING)
                .requestedByUserId(null)
                .build();

        // park is MANDATORY, so the caller brings the transaction: GlobalExceptionHandler and
        // BulkExecutor each wrap it in a template of their own and A5 will do the same (B2).
        ApprovalDtos.Accepted accepted = new TransactionTemplate(txManager)
                .execute(status -> approvalService.park(raisedByNobody, "/api/payments"));

        assertThat(accepted).isNotNull();
        List<Notification> raised = ofType(APPROVAL_REQUESTED);
        assertThat(raised).extracting(Notification::getUserId)
                .contains(admin.getId(), checker.getId())
                .doesNotContain(elsewhere.getId())
                .doesNotContainNull();

        AuditLog requested = only(auditService.historyFor("CUSTOMER", acme.getId()), "CHANGE_REQUESTED");
        // Nobody raised it, so nobody is named. A fabricated actor id would be a lie in the trail
        // and would also make "approval from someone else" trivially satisfiable (B2, A5).
        assertThat(requested.getChangedByUserId()).isNull();
        assertThat(requested.getPendingChangeId()).isEqualTo(accepted.pendingChangeId());
    }

    // ------------------------------------------------------------------------------ fixtures

    private Long heldPayment(String amount) throws Exception {
        return heldPaymentFor(acme, amount);
    }

    private Long heldPaymentFor(Customer c, String amount) throws Exception {
        MvcResult held = mockMvc.perform(post("/api/payments").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payment(c, amount, List.of()))))
                .andExpect(status().isAccepted())
                .andReturn();
        return jsonLong(held, "pendingChangeId");
    }

    private PaymentDtos.CreatePaymentRequest payment(Customer c, String amount, List<Long> invoiceIds) {
        return new PaymentDtos.CreatePaymentRequest(c.getId(), new BigDecimal(amount), "NEFT",
                null, invoiceIds, collections.getId(), null);
    }

    private Customer opened(String name, String username) {
        actAs(admin);
        return customerService.create(new CustomerDtos.CustomerCreateRequest(
                name, null, null, null, null, defaultRegion().getId(), username, "Password1!"));
    }

    private void threshold(Long regionId, String amount, boolean enabled) {
        ApprovalThreshold row = approvalThresholdRepository.findByRegionId(regionId)
                .orElseGet(() -> ApprovalThreshold.builder().regionId(regionId).build());
        row.setAmount(new BigDecimal(amount));
        row.setEnabled(enabled);
        approvalThresholdRepository.saveAndFlush(row);
    }

    private JsonNode timeline(String entityType, Long entityId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/audit").with(as(admin))
                        .param("entityType", entityType)
                        .param("entityId", String.valueOf(entityId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> actions(JsonNode timeline) {
        List<String> out = new ArrayList<>();
        timeline.forEach(e -> out.add(e.path("action").asText()));
        return out;
    }

    private static Long pendingChangeIdOf(JsonNode timeline, String action) {
        for (JsonNode e : timeline) {
            if (action.equals(e.path("action").asText())) {
                return e.path("pendingChangeId").isNull() ? null : e.path("pendingChangeId").asLong();
            }
        }
        throw new AssertionError("No " + action + " entry in the timeline: " + actions(timeline));
    }

    private static List<Long> distinctPendingChangeIds(JsonNode timeline) {
        List<Long> out = new ArrayList<>();
        for (JsonNode e : timeline) {
            if (!e.path("pendingChangeId").isNull() && !out.contains(e.path("pendingChangeId").asLong())) {
                out.add(e.path("pendingChangeId").asLong());
            }
        }
        return out;
    }

    private static AuditLog only(List<AuditLog> rows, String action) {
        List<AuditLog> matching = rows.stream().filter(a -> action.equals(a.getAction())).toList();
        assertThat(matching).as("audit rows with action " + action).hasSize(1);
        return matching.get(0);
    }

    private List<Notification> ofType(String type) {
        return notificationRepository.findAll().stream()
                .filter(n -> type.equals(n.getType()))
                .toList();
    }

    private List<Notification> mine(User u) {
        return notificationRepository.findAll().stream()
                .filter(n -> Objects.equals(u.getId(), n.getUserId()))
                .toList();
    }

    /** An approver whose only grants are in another branch, so the address rule has to bite. */
    private User approverIn(Region where, String username) {
        User u = user(username, checkerRole().getName());
        revokeRegionGrants(u);
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(u.getId()).regionId(where.getId()).right(RegionRight.VIEW).build());
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(u.getId()).regionId(where.getId()).right(RegionRight.APPROVE).build());
        return u;
    }

    /** Everything a person needs to move money on an account, AND the right to approve: the maker
     *  is excluded from the approver notification by being the maker, not by lacking anything. */
    private Role makerRole() {
        return roleWith("AUDIT_MAKER",
                Privileges.CUSTOMER_VIEW, Privileges.CUSTOMER_MANAGE,
                Privileges.PAYMENT_VIEW, Privileges.PAYMENT_MANAGE,
                Privileges.POC_VIEW, Privileges.POC_ASSIGN, Privileges.SCOPE_OVERRIDE,
                Privileges.APPROVAL_VIEW, Privileges.APPROVAL_APPROVE);
    }

    private Role checkerRole() {
        return roleWith("AUDIT_CHECKER", Privileges.APPROVAL_VIEW, Privileges.APPROVAL_APPROVE);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by ApprovalAuditTest")
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
