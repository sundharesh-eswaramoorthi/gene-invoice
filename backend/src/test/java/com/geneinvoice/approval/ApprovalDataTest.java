package com.geneinvoice.approval;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeRepository;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionAxes;
import com.geneinvoice.region.RegionAxis;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The approval data layer, on its own. Nothing here is wired to a mutator yet — no gate, no
 * endpoint, no DTO component on any existing record — so every assertion below is about the two
 * new tables, the resolver that reads them and the seam through which maker-checker asks B1 where
 * a record lives (B2).
 *
 * <p>The one-open-change-per-record rule is asserted against the DATABASE and not against a Java
 * exists(), because the rule's whole value is that it survives two makers who both passed the
 * exists() before either of them wrote. It rides on the pending_key sentinel under a plain
 * &#64;UniqueConstraint precisely so H2 enforces it too and this test means something (B2).
 */
class ApprovalDataTest extends IntegrationTestBase {

    @Autowired private PendingChangeRepository changes;
    @Autowired private ApprovalThresholds thresholds;
    @Autowired private ApprovalProperties approvalProperties;
    @Autowired private ApprovalThresholdService thresholdService;
    @Autowired private RegionLookup regions;
    @Autowired private DisputeRepository disputeRepository;
    @Autowired private AuditService auditService;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager txManager;

    // ---------------------------------------------------------------- the one-open-change rule

    @Test
    void aSecondOpenChangeOnTheSameRecordIsRefusedByTheDatabase() {
        Customer c = customer("Tata Steel");
        Invoice inv = invoice(c, "INV-DATA-1", "500000.00");

        PendingChange first = changes.saveAndFlush(
                on(PendingAction.INVOICE_CANCEL, inv.getId(), c, defaultRegion()));
        assertThat(first.getPendingKey()).isEqualTo("INVOICE:" + inv.getId());

        assertThatThrownBy(() -> changes.saveAndFlush(
                on(PendingAction.INVOICE_CANCEL, inv.getId(), c, defaultRegion())))
                .isInstanceOf(DataIntegrityViolationException.class);

        // And a DIFFERENT action on the same record collides too: the key is the record, not the
        // thing somebody wants to do to it (B2).
        assertThatThrownBy(() -> changes.saveAndFlush(
                on(PendingAction.INVOICE_REPLACE_ITEMS, inv.getId(), c, defaultRegion())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aDecidedChangeReleasesTheRecordForAnotherOne() {
        Customer c = customer("Reliance");
        Invoice inv = invoice(c, "INV-DATA-2", "500000.00");

        PendingChange first = changes.saveAndFlush(
                on(PendingAction.INVOICE_CANCEL, inv.getId(), c, defaultRegion()));

        first.setStatus(PendingChangeStatus.APPROVED);
        first.setDecidedAt(Instant.now());
        PendingChange decided = changes.saveAndFlush(first);
        assertThat(decided.getPendingKey()).isNull();

        PendingChange second = changes.saveAndFlush(
                on(PendingAction.INVOICE_CANCEL, inv.getId(), c, defaultRegion()));
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getPendingKey()).isEqualTo("INVOICE:" + inv.getId());

        // Every terminal status frees the record, not only APPROVED (B2).
        second.setStatus(PendingChangeStatus.WITHDRAWN);
        assertThat(changes.saveAndFlush(second).getPendingKey()).isNull();
        assertThat(changes.saveAndFlush(
                on(PendingAction.INVOICE_CANCEL, inv.getId(), c, defaultRegion())).getPendingKey())
                .isEqualTo("INVOICE:" + inv.getId());
    }

    @Test
    void twoCreatesAgainstOneCustomerBothWaitBecauseACreateHasNoTarget() {
        Customer c = customer("Wipro");

        PendingChange one = changes.saveAndFlush(creating(PendingAction.INVOICE_CREATE, c));
        PendingChange two = changes.saveAndFlush(creating(PendingAction.INVOICE_CREATE, c));

        assertThat(one.getPendingKey()).isNull();
        assertThat(two.getPendingKey()).isNull();
        assertThat(changes.findByCustomerIdAndStatus(c.getId(), PendingChangeStatus.PENDING))
                .hasSize(2);
        assertThat(changes.existsByCustomerIdAndStatus(c.getId(), PendingChangeStatus.PENDING))
                .isTrue();
    }

    /**
     * The trap that broke B1 on Postgres, pinned here on H2 while the table is still empty.
     * Hibernate's action queue runs EVERY insert before EVERY update at flush, so a supersede —
     * close the waiting change, raise its replacement, one transaction — sends the new row's
     * pending_key to the database while the old row is still holding it.
     *
     * <p>B1's equivalent invariant rode on a Postgres-only partial index, so the whole H2 suite
     * passed over a write path that was 100% broken in production. This one rides on a plain
     * &#64;UniqueConstraint over the pending_key sentinel, which is why THIS test can exist at all
     * — and why the close must be saveAndFlush, exactly as RegionCustodyService.move now does (B2).
     */
    @Test
    void theCloseOfASupersededChangeMustReachTheDatabaseBeforeItsReplacementIsInserted() {
        Customer c = customer("Adani Ports");
        Invoice inv = invoice(c, "INV-DATA-7", "700000.00");
        changes.saveAndFlush(on(PendingAction.INVOICE_CANCEL, inv.getId(), c, defaultRegion()));
        String key = PendingChange.keyOf(PendingTargetType.INVOICE, inv.getId());
        TransactionTemplate tx = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> tx.execute(status -> {
            PendingChange open = changes.findByPendingKey(key).orElseThrow();
            open.setStatus(PendingChangeStatus.SUPERSEDED);
            changes.save(open);                       // scheduled, not sent — the defect (B2)
            return changes.save(on(PendingAction.INVOICE_CANCEL, inv.getId(), c, defaultRegion()));
        })).isInstanceOf(DataIntegrityViolationException.class);

        // The same sequence with the close FLUSHED first, which is the shape every superseding
        // caller in B2 has to use.
        Long replacement = tx.execute(status -> {
            PendingChange open = changes.findByPendingKey(key).orElseThrow();
            open.setStatus(PendingChangeStatus.SUPERSEDED);
            changes.saveAndFlush(open);
            return changes.save(on(PendingAction.INVOICE_CANCEL, inv.getId(), c, defaultRegion()))
                    .getId();
        });

        assertThat(changes.findByPendingKey(key).orElseThrow().getId()).isEqualTo(replacement);
        assertThat(changes.findByTargetTypeAndTargetIdAndStatus(
                PendingTargetType.INVOICE, inv.getId(), PendingChangeStatus.SUPERSEDED)).hasSize(1);
    }

    @Test
    void onlyRecordsWithAChangeStillWaitingComeBackFromOpenTargetIds() {
        Customer c = customer("Infosys");
        Invoice held = invoice(c, "INV-DATA-3", "900000.00");
        Invoice decided = invoice(c, "INV-DATA-4", "900000.00");
        Invoice untouched = invoice(c, "INV-DATA-5", "900000.00");

        changes.saveAndFlush(on(PendingAction.INVOICE_CANCEL, held.getId(), c, defaultRegion()));
        PendingChange done = changes.saveAndFlush(
                on(PendingAction.INVOICE_CANCEL, decided.getId(), c, defaultRegion()));
        done.setStatus(PendingChangeStatus.REJECTED);
        changes.saveAndFlush(done);

        assertThat(changes.openTargetIds(PendingTargetType.INVOICE,
                List.of(held.getId(), decided.getId(), untouched.getId())))
                .containsExactly(held.getId());

        // Never "in ()": an empty page asks the database nothing at all (B2).
        assertThat(changes.openTargetIds(PendingTargetType.INVOICE, List.of())).isEmpty();
        assertThat(changes.existsByPendingKey(
                PendingChange.keyOf(PendingTargetType.INVOICE, held.getId()))).isTrue();
        assertThat(changes.existsByPendingKey(
                PendingChange.keyOf(PendingTargetType.INVOICE, decided.getId()))).isFalse();
    }

    // ------------------------------------------------------------------------- the threshold

    @Test
    void aRegionWithNoRowOfItsOwnFallsBackToTheDeploymentDefault() {
        Region west = region("WEST");

        // A deployment that HAS configured a default, built beside the real one rather than by
        // mutating a shared bean, so no other test in this cached context can see it (B2).
        ApprovalProperties configured = new ApprovalProperties();
        configured.setDefaultThreshold(new BigDecimal("100000.00"));
        ApprovalThresholds withDefault =
                new ApprovalThresholds(approvalThresholdRepository, configured);

        assertThat(withDefault.forRegion(west.getId()))
                .isEqualTo(new ApprovalThresholds.Limit(true, new BigDecimal("100000.00")));

        // A row of its own wins over the default, in both directions.
        approvalThresholdRepository.saveAndFlush(ApprovalThreshold.builder()
                .regionId(west.getId()).amount(new BigDecimal("250000.00")).enabled(true).build());
        assertThat(withDefault.forRegion(west.getId()))
                .isEqualTo(new ApprovalThresholds.Limit(true, new BigDecimal("250000.00")));

        ApprovalThreshold row = approvalThresholdRepository.findByRegionId(west.getId()).orElseThrow();
        row.setEnabled(false);
        approvalThresholdRepository.saveAndFlush(row);
        assertThat(withDefault.forRegion(west.getId()).enabled()).isFalse();
    }

    @Test
    void aDeploymentThatConfiguresNoDefaultThresholdHoldsNothing() {
        // The premise, stated rather than assumed: the test profile leaves the key unset, which is
        // what the shipped application.yml does too (B2).
        assertThat(approvalProperties.defaultThreshold()).isNull();

        assertThat(thresholds.forRegion(defaultRegion().getId()))
                .isEqualTo(new ApprovalThresholds.Limit(false, BigDecimal.ZERO));
        assertThat(thresholds.forRegion(region("EAST").getId()).enabled()).isFalse();
    }

    @Test
    void aRegionWithNoRowReportsTheDeploymentDefaultAndSaysSoOnTheWire() {
        Region west = region("WEST");

        ApprovalDtos.ThresholdDto unset = thresholdService.get(west.getId());
        assertThat(unset.fromDefault()).isTrue();
        assertThat(unset.enabled()).isFalse();
        assertThat(unset.regionName()).isEqualTo(west.getName());
        assertThat(unset.updatedAt()).isNull();
    }

    @Test
    void theApplyHalfOfAThresholdChangeWritesTheRowAndNamesTheMakerInTheAudit() {
        Region west = region("WEST");
        User maker = user("thresholdmaker", "ADMIN");
        // As the maker, which is the only way this half ever runs: ApprovalService.asMaker swaps
        // the principal to the person who asked before the applier replays the call, and applySet
        // re-checks MANAGE in that branch against their LIVE grants (B2, B1).
        actAs(maker);

        ApprovalDtos.ThresholdDto after = thresholdService.applySet(west.getId(),
                new ApprovalDtos.ThresholdRequest(new BigDecimal("250000.00"), true),
                maker.getId());

        assertThat(after.amount()).isEqualByComparingTo("250000.00");
        assertThat(after.enabled()).isTrue();
        assertThat(after.fromDefault()).isFalse();
        assertThat(after.updatedByUserId()).isEqualTo(maker.getId());
        assertThat(thresholds.forRegion(west.getId()))
                .isEqualTo(new ApprovalThresholds.Limit(true, new BigDecimal("250000.00")));

        List<AuditLog> trail =
                auditService.historyFor(ApprovalThresholdService.ENTITY, west.getId());
        assertThat(trail).hasSize(1);
        assertThat(trail.get(0).getAction()).isEqualTo("THRESHOLD_CHANGED");
        // The person who ASKED, so the CHANGE_APPROVED row beside it can name the approver (B2).
        assertThat(trail.get(0).getChangedByUserId()).isEqualTo(maker.getId());
        assertThat(trail.get(0).getBeforeJson()).contains("\"fromDefault\":true");
        assertThat(trail.get(0).getAfterJson()).contains("250000.00");
    }

    // ---------------------------------------------------------------------------- the B1 seam

    @Test
    void theRegionOfAnInvoiceIsTheRegionOfItsCustomer() {
        Region west = region("WEST");
        Customer here = customerRepository.save(
                Customer.builder().name("West Motors").region(west).build());
        Customer there = customer("Head Office Motors");

        Invoice inv = invoice(here, "INV-DATA-6", "1000.00");
        Payment pay = paymentRepository.save(Payment.builder()
                .customer(here).amount(new BigDecimal("1000.00")).paidAt(Instant.now()).build());
        PaymentPromise promise = promiseRepository.save(PaymentPromise.builder()
                .customer(here).amount(new BigDecimal("1000.00"))
                .promisedDate(LocalDate.now().plusDays(7))
                .collectionPoc(userRepository.findByUsername("cashier").orElseThrow())
                .build());
        Dispute dispute = disputeRepository.save(Dispute.builder()
                .customerId(here.getId()).openedByUserId(1L)
                .targetType(DisputeTargetType.INVOICE).targetId(inv.getId())
                .reason("Billed twice").build());

        assertThat(regions.regionOf(PendingTargetType.CUSTOMER, here.getId())).isEqualTo(west.getId());
        assertThat(regions.regionOf(PendingTargetType.INVOICE, inv.getId())).isEqualTo(west.getId());
        assertThat(regions.regionOf(PendingTargetType.PAYMENT, pay.getId())).isEqualTo(west.getId());
        assertThat(regions.regionOf(PendingTargetType.PROMISE, promise.getId())).isEqualTo(west.getId());
        assertThat(regions.regionOf(PendingTargetType.DISPUTE, dispute.getId())).isEqualTo(west.getId());

        assertThat(regions.regionOf(PendingTargetType.CUSTOMER, there.getId()))
                .isEqualTo(defaultRegion().getId());

        // A record that is not there is the mutator's own 404, because the gate runs INSIDE the
        // mutator and a caller must not be able to tell the two apart (B2).
        assertThatThrownBy(() -> regions.regionOf(PendingTargetType.INVOICE, 9_999_999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Invoice not found");
        assertThatThrownBy(() -> regions.regionOf(PendingTargetType.CUSTOMER, 9_999_999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Customer not found");

        assertThat(regions.regionName(west.getId())).isEqualTo(west.getName());
        assertThat(regions.regionName(9_999_999L)).isNull();
    }

    @Test
    void theRegionOfAThresholdChangeIsTheRegionItNames() {
        Region west = region("WEST");

        // No record is walked at all: a threshold change NAMES its region, which is also why a
        // region the caller cannot manage is a 403 there and not the usual 404 (B2, D-46).
        assertThat(regions.regionOf(PendingTargetType.REGION, west.getId())).isEqualTo(west.getId());
        assertThat(PendingChange.keyOf(PendingTargetType.REGION, west.getId()))
                .isEqualTo("REGION:" + west.getId());
    }

    @Test
    void onlyTheApproversOfARegionAreOfferedAsItsApprovers() {
        Region west = region("WEST");
        Customer c = customer("Anyone");

        User westApprover = staffedAt("westapprover", west.getId(), RegionRight.APPROVE);
        User hqApprover = staffedAt("hqapprover", defaultRegion().getId(), RegionRight.APPROVE);
        // Holds APPROVAL_APPROVE and works in WEST, but only at MANAGE: the ladder is deliberately
        // not a total order, so managing a branch is not signing changes off in it (B2, B1).
        User westManager = staffedAt("westmanager", west.getId(), RegionRight.MANAGE);
        User retired = staffedAt("retiredapprover", west.getId(), RegionRight.APPROVE);
        retired.setActive(false);
        userRepository.save(retired);
        User customerLogin = userRepository.save(User.builder()
                .username("customerapprover").email("customerapprover@test.local")
                .password(passwordEncoder.encode("password")).role(role("ADMIN"))
                .customerId(c.getId()).active(true).build());
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(customerLogin.getId()).regionId(west.getId())
                .right(RegionRight.APPROVE).build());

        User seededAdmin = userRepository.findByUsername("admin").orElseThrow();
        User seededCashier = userRepository.findByUsername("cashier").orElseThrow();

        List<Long> approvers = regions.usersWith(Privileges.APPROVAL_APPROVE, west.getId());

        // The wildcard holder approves everywhere, including branches opened after the grant (B1).
        assertThat(approvers).contains(westApprover.getId(), seededAdmin.getId());
        assertThat(approvers).doesNotContain(hqApprover.getId(), westManager.getId(),
                retired.getId(), customerLogin.getId(), seededCashier.getId());

        assertThat(regions.usersWith(Privileges.APPROVAL_APPROVE, defaultRegion().getId()))
                .contains(hqApprover.getId(), seededAdmin.getId())
                .doesNotContain(westApprover.getId());

        // The other two seam questions, on the same fixture.
        assertThat(regions.hasAnyRight(westApprover.getId(), west.getId())).isTrue();
        assertThat(regions.hasAnyRight(westApprover.getId(), defaultRegion().getId())).isFalse();
        assertThat(regions.hasAnyRight(seededAdmin.getId(), west.getId())).isTrue();

        assertThat(regions.approvableRegions(westApprover.getId()))
                .containsExactly(west.getId());
        assertThat(regions.approvableRegions(westManager.getId())).isEmpty();
        assertThat(regions.approvableRegions(seededAdmin.getId()))
                .contains(west.getId(), defaultRegion().getId());
    }

    // ------------------------------------------------------------------------- the region axis

    @Test
    void theApplicationStartsWithBothNewTablesClassifiedOnARegionAxis() {
        List<Class<?>> mapped = new ArrayList<>();
        for (EntityType<?> entity : entityManagerFactory.getMetamodel().getEntities()) {
            mapped.add(entity.getJavaType());
        }
        assertThat(mapped).contains(PendingChange.class, ApprovalThreshold.class);

        // OWN_ID exists in B1's enum for exactly this row: a held change carries the branch it was
        // raised in on a flat column, because a create has no record to walk to (B2, B1).
        assertThat(RegionAxes.of(PendingChange.class)).isEqualTo(RegionAxis.OWN_ID);
        assertThat(RegionAxes.reason(PendingChange.class)).isEmpty();

        assertThat(RegionAxes.of(ApprovalThreshold.class)).isEqualTo(RegionAxis.NONE);
        assertThat(RegionAxes.reason(ApprovalThreshold.class))
                .hasSizeGreaterThan(20)
                .contains("region-checked endpoint");

        // The contribution classifies these two and nothing else: a classification left behind for
        // a class that is not mapped covers nothing at all (B1).
        assertThat(ApprovalRegionAxes.CONTRIBUTION.axes().keySet())
                .isEqualTo(Set.of(PendingChange.class, ApprovalThreshold.class));
        assertThat(ApprovalRegionAxes.CONTRIBUTION.unregionedBecause().keySet())
                .isEqualTo(Set.of(ApprovalThreshold.class));
    }

    // ------------------------------------------------------------------------------- fixtures

    private Invoice invoice(Customer c, String number, String total) {
        return invoiceRepository.save(Invoice.builder()
                .invoiceNumber(number)
                .customer(c)
                .invoiceDate(Instant.now())
                .dueDate(LocalDate.now().plusDays(30))
                .total(new BigDecimal(total))
                .build());
    }

    /** A staff account with exactly one grant, so "works here" and "may approve here" are testable
     *  apart from each other (B2, B1). */
    private User staffedAt(String username, Long regionId, RegionRight right) {
        User u = user(username, "ADMIN");
        revokeRegionGrants(u);
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(u.getId()).regionId(regionId).right(right).build());
        return u;
    }

    private PendingChange on(PendingAction action, Long targetId, Customer c, Region region) {
        return base(action, c, region).targetId(targetId).build();
    }

    private PendingChange creating(PendingAction action, Customer c) {
        return base(action, c, defaultRegion()).build();
    }

    private PendingChange.PendingChangeBuilder base(PendingAction action, Customer c, Region region) {
        return PendingChange.builder()
                .action(action)
                .targetType(action.targetType())
                .customerId(c.getId())
                .regionId(region.getId())
                .exposure(new BigDecimal("500000.00"))
                .thresholdApplied(new BigDecimal("100000.00"))
                .alwaysChecked(action.alwaysChecked())
                .payloadJson("{}")
                .summary(action.name() + " for " + c.getName())
                .status(PendingChangeStatus.PENDING);
    }
}
