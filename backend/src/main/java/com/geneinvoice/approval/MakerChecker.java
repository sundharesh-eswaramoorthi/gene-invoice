package com.geneinvoice.approval;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.Fit;
import com.geneinvoice.common.Money;
import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * The gate every money-moving service method passes after it has authorised, validated and measured a change,
 * and before it touches anything (§5.11.3). Above the region's threshold the change is written as a pending
 * change and returned; the method then returns {@link Outcome#held} and changes nothing else. "Above" counts the
 * same maker's recent unapproved changes to the customer within the threshold's window, and for a while after a
 * move the region left behind's limit too. While an approved change is being applied it only checks the change
 * has not grown since the request.
 */
@Component
@RequiredArgsConstructor
public class MakerChecker {

    private final ApprovalThresholdRepository thresholds;
    private final PendingChangeRepository pending;
    private final RegionRepository regionRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;
    private final PayloadCodec codec;
    private final AuditService auditService;
    private final ApprovalNotifier notifier;
    private final ApprovalUsageLedger usage;
    private final RegionMoveCooldown cooldown;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PendingChange> gate(Gated g) {
        Optional<ApprovalContext.Applying> applying = ApprovalContext.current();
        if (applying.isPresent()) {
            // Applying an approved change: never holds, but the change must be the one that was approved.
            ApprovalContext.Applying a = applying.get();
            boolean same = a.op() == g.op() && (g.outstandingKey() != null
                    ? g.outstandingKey().equals(a.outstandingKey())
                    : Objects.equals(a.customerId(), g.customerId()));
            if (same && g.amount().compareTo(a.approvedAmount()) > 0) {
                throw new ChangeConflictException("The amount grew from " + Money.format(a.approvedAmount())
                        + " to " + Money.format(g.amount()) + " since the request");
            }
            return Optional.empty();
        }
        if (g.amount().signum() <= 0) return Optional.empty();
        Instant now = clock.instant();
        ApprovalThreshold t = effectiveThreshold(g, now);
        if (t == null) return Optional.empty();
        Long makerId = currentUser.idOrNull();
        boolean windowed = t.getWindowHours() > 0 && makerId != null && g.customerId() != null;
        BigDecimal measured = g.amount();
        if (measured.compareTo(t.getAmount()) <= 0 && windowed) {
            // Within the limit alone: the same maker's recent unapproved changes to the customer count too (SC-9),
            // applied ones and those still waiting, but not the one this change would replace.
            measured = measured
                    .add(usage.recent(makerId, g.regionId(), g.op(), g.customerId(),
                            now.minus(t.getWindowHours(), ChronoUnit.HOURS)))
                    .add(pendingBy(makerId, g));
        }
        if (measured.compareTo(t.getAmount()) <= 0) {
            if (windowed) usage.record(g, makerId, now);
            return Optional.empty();
        }
        if (g.checkerStepSatisfied()) return Optional.empty();
        // Never an authentication failure: a system path above a threshold is a bug, not a login problem.
        if (makerId == null) throw new IllegalStateException(g.op() + " above threshold with no principal");
        String summary = measured.compareTo(g.amount()) == 0 ? null
                : g.summary() + "; with your other changes to " + g.customerName() + " in the last "
                        + t.getWindowHours() + " hours this comes to " + Money.format(measured);
        return Optional.of(hold(g, t.getAmount(), makerId, summary, now));
    }

    /**
     * Writes the held change (§5.11.3): the same maker's outstanding change of the same kind on the record is
     * replaced, anyone else's is a conflict (409); audited, and the region's approvers told. Also how a region's
     * loosened limits are held, whatever they are worth (§5.11.11). {@code summary} overrides the gated one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PendingChange hold(Gated g, BigDecimal threshold, Long makerId, String summary, Instant now) {
        PendingChange superseded = null;
        if (g.outstandingKey() != null) {
            PendingChange open = pending.findByOutstandingKey(g.outstandingKey()).orElse(null);
            if (open != null) {
                if (!open.getMakerUserId().equals(makerId) || open.getOperation() != g.op()) {
                    throw new ApprovalConflictException(g.entityLabel() + " already has a change awaiting approval (#"
                            + open.getId() + ", " + open.getOperation().label() + " by "
                            + nameOf(open.getMakerUserId()) + "). It must be approved, rejected or withdrawn first.");
                }
                // The same maker asking again for the same thing replaces the earlier request; flushed at once to
                // free the unique key for the new row.
                open.supersede(makerId, now);
                pending.saveAndFlush(open);
                superseded = open;
            }
        }
        WriteOrigin origin = WriteOrigins.current(currentUser);
        boolean automated = origin.kind() == WriteOrigin.Kind.AUTOMATION;
        PendingChange held = PendingChange.pending(g, regionRepository.getReferenceById(g.regionId()),
                threshold, makerId, automated ? ChangeOrigin.AUTOMATION : ChangeOrigin.USER,
                automated ? origin.ruleId() : null, automated ? origin.eventId() : null,
                codec.toJson(g.payload()), codec.toJson(g.base()), codec.toJson(g.proposed()), now);
        if (summary != null) held.setSummary(Fit.to(summary, PendingChange.SUMMARY_MAX));
        PendingChange pc = pending.save(held);
        if (superseded != null) {
            superseded.setSupersededById(pc.getId());
            auditService.recordLinked(g.auditEntityType(), g.auditEntityId(), "CHANGE_SUPERSEDED", null, null, makerId,
                    g.disputeId(), superseded.getId(), "Replaced by #" + pc.getId());
        }
        auditService.recordLinked(g.auditEntityType(), g.auditEntityId(), "CHANGE_REQUESTED", g.base(), g.proposed(),
                makerId, g.disputeId(), pc.getId(), pc.getSummary());
        notifier.requested(pc);
        return pc;
    }

    /**
     * The limit that applies: the row for the record's region and the operation, or none (not gated). For a while
     * after the customer was moved, the stricter of that and the rows of the regions it left (§5.11.3, SC-4): the
     * lower amount, and on a tie the longer window. A region with no row does not gate, so a move into an ungated
     * region still gates at the region left behind.
     */
    ApprovalThreshold effectiveThreshold(Gated g, Instant now) {
        if (g.regionId() == null) return null;
        ApprovalThreshold strictest = thresholds.findByRegionIdAndOperation(g.regionId(), g.op()).orElse(null);
        for (Long previous : cooldown.previousRegions(g.customerId(), now)) {
            if (previous.equals(g.regionId())) continue;
            ApprovalThreshold other = thresholds.findByRegionIdAndOperation(previous, g.op()).orElse(null);
            if (other != null && (strictest == null || stricter(other, strictest))) strictest = other;
        }
        return strictest;
    }

    private static boolean stricter(ApprovalThreshold a, ApprovalThreshold b) {
        int byAmount = a.getAmount().compareTo(b.getAmount());
        return byAmount < 0 || (byAmount == 0 && a.getWindowHours() > b.getWindowHours());
    }

    /** The maker's changes of this kind to the customer still waiting, less the one this change would replace. */
    private BigDecimal pendingBy(Long makerId, Gated g) {
        // A create has no key to leave out; "-" is no key at all.
        String replaced = g.outstandingKey() == null ? "-" : g.outstandingKey();
        BigDecimal sum = pending.sumPendingBy(makerId, g.regionId(), g.op(), g.customerId(), replaced,
                PendingStatus.PENDING);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private String nameOf(Long userId) {
        return userRepository.findById(userId).map(MakerChecker::displayName).orElse("user #" + userId);
    }

    static String displayName(User u) {
        return u.getFullName() == null || u.getFullName().isBlank() ? u.getUsername() : u.getFullName();
    }
}
