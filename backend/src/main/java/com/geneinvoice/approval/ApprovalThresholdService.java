package com.geneinvoice.approval;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.Money;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.region.RegionAccess;
import com.geneinvoice.region.RegionRight;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and writing one region's approval limit.
 *
 * <p>This class deliberately ships its APPLY half first. {@code applySet} is what the approver's
 * decision replays, and it exists before the maker-facing {@code set(...)} because a threshold
 * change is itself always-checked: the only way a limit ever moves is through somebody else's
 * approval, so the applier is the path that must work (B2).
 */
@Service
@RequiredArgsConstructor
public class ApprovalThresholdService {

    public static final String ENTITY = "APPROVAL_THRESHOLD";

    private static final String THRESHOLD_CHANGED = "THRESHOLD_CHANGED";

    private final ApprovalThresholdRepository repository;
    private final ApprovalThresholds thresholds;
    private final RegionLookup regions;
    private final AuditService auditService;
    // The gate this service is on the wrong side of: every other gate site is a money mutator,
    // and this one is the configuration behind them, held for the same reason (B2).
    private final ApprovalGate approvalGate;
    private final CurrentUser currentUser;
    // The third file in com.geneinvoice.approval to name com.geneinvoice.region, after
    // RegionLookupImpl and PendingChangeRegionSync. RegionLookup is deliberately not widened to
    // carry a refusal: "may this caller work here" is B1's write-side gate and there must go on
    // being exactly one of it (B2, B1 INTEGRATION).
    private final RegionAccess regionAccess;

    /**
     * The maker-facing half: what a PUT to /api/approvals/thresholds/{regionId} runs.
     *
     * <p>It does not set anything. {@code APPROVAL_THRESHOLD_SET.alwaysChecked()} is true, so the
     * gate below throws whatever the amount and however small the change, and the limit moves only
     * when somebody else approves the parked row and the applier calls {@link #applySet}. That is
     * what closes the loop where a configure-holder raises their own gate, pushes their own payment
     * through and lowers it back: the raise itself needs a second pair of eyes. Bootstrap is not a
     * problem, because the shipped default comes from yaml and not from a row (B2).
     */
    @Transactional
    public ApprovalDtos.ThresholdDto set(Long regionId, ApprovalDtos.ThresholdRequest req) {
        // The caller NAMED the region, so a branch they cannot manage is 403 and not 404: no id
        // space is being probed. This is the one place in B2 where the contract goes the other way
        // from AUTH-08, and it is D-46's rule, not an exception to it (B2, B1, D-46).
        regionAccess.require(regionId, RegionRight.MANAGE);
        Money.requireCents(req.amount(), "Amount");
        // Also the proposal's "before", so the approver reads what the limit is now beside what it
        // would become, without the queue having to resolve it at decision time (B2).
        ApprovalDtos.ThresholdDto before = get(regionId);
        // A wildcard grant says yes to every id including one no region ever had, and regionOf
        // (REGION, id) answers with the id itself rather than reading a row, so nothing else on
        // this path would notice. Refused here rather than parked, because a change nobody can
        // ever usefully approve should not reach the queue at all (B2).
        if (before.regionName() == null) throw new NotFoundException("Region not found");
        approvalGate.check(ApprovalGate.Proposal.on(
                PendingAction.APPROVAL_THRESHOLD_SET, regionId,
                // No customer: a threshold belongs to a branch and to nobody's account. The
                // pending row's region_id comes from regionOf(REGION, regionId), which is the id
                // itself, and its pending_key is "REGION:<id>", so one threshold change per region
                // can wait at a time and the second is refused by the gate (B2).
                null,
                req.amount(), req, before, null, summary(before.regionName(), req)));
        // Only reachable from inside ApprovalContext.applying(...). The applier calls applySet
        // directly today, so this is unreached; it is here so set() is a total function rather
        // than a method that falls off the end if the applier is ever repointed at it (B2).
        return applySet(regionId, req, currentUser.idOrNull());
    }

    /** What the approver reads in the queue before they have opened anything (B2). */
    private static String summary(String regionName, ApprovalDtos.ThresholdRequest req) {
        String sentence = "Set the " + regionName + " approval limit to " + Money.format(req.amount());
        // Switching the gate off is the change that matters most and the amount does not show it:
        // a summary reading "set the limit to ₹2,50,000.00" on a request that turns maker-checker
        // off in that branch would be true and misleading at once (B2).
        return Boolean.TRUE.equals(req.enabled())
                ? sentence
                : sentence + " and switch approval checking off there";
    }

    /**
     * The approved change, landed. {@code actorUserId} is the person who ASKED — the applier
     * passes {@code pc.getRequestedByUserId()} — so the THRESHOLD_CHANGED row names the maker and
     * the CHANGE_APPROVED row beside it names the approver; between them the whole four-eyes story
     * is readable off the audit trail without joining anything (B2).
     */
    @Transactional
    public ApprovalDtos.ThresholdDto applySet(Long regionId, ApprovalDtos.ThresholdRequest req,
                                              Long actorUserId) {
        // The same gate set(...) opens with, re-run on the replay against LIVE grants. This is the
        // arm ApprovalService.asMaker means by "a maker downgraded from MANAGE to VIEW still gets
        // past this and is caught below by regionAccess": every other applier branch lands in a
        // mutator that calls regionAccess.requireManage itself, and without this line the one
        // action that configures the money gate — including the one that switches checking off in
        // a branch — would be the single action where that invariant is false (B2, B1, D-46).
        regionAccess.require(regionId, RegionRight.MANAGE);
        Money.requireCents(req.amount(), "Amount");
        // Read before the row is touched: after the lock takes it, "before" is gone (B2).
        ApprovalDtos.ThresholdDto before = get(regionId);
        ApprovalThreshold row = repository.findByRegionIdForUpdate(regionId)
                .orElseGet(() -> ApprovalThreshold.builder().regionId(regionId).build());
        row.setAmount(Money.scale(req.amount()));
        row.setEnabled(Boolean.TRUE.equals(req.enabled()));
        row.setUpdatedByUserId(actorUserId);
        ApprovalDtos.ThresholdDto after = fromRow(repository.save(row));
        auditService.record(ENTITY, regionId, THRESHOLD_CHANGED, before, after, actorUserId,
                null, null);
        return after;
    }

    /**
     * What this region's limit is right now, and whether it is the region's own. A region with no
     * row reports the deployment default with {@code fromDefault=true} rather than a zero nobody
     * configured, so the screen can say where the number came from (B2).
     */
    @Transactional(readOnly = true)
    public ApprovalDtos.ThresholdDto get(Long regionId) {
        return repository.findByRegionId(regionId)
                .map(this::fromRow)
                .orElseGet(() -> {
                    // The same resolver every gate site asks, so the screen and the gate can never
                    // disagree about what this region's limit is (B2).
                    ApprovalThresholds.Limit limit = thresholds.forRegion(regionId);
                    return new ApprovalDtos.ThresholdDto(regionId, regions.regionName(regionId),
                            Money.scale(limit.amount()), limit.enabled(), null, null, true);
                });
    }

    private ApprovalDtos.ThresholdDto fromRow(ApprovalThreshold row) {
        return new ApprovalDtos.ThresholdDto(row.getRegionId(), regions.regionName(row.getRegionId()),
                row.getAmount(), row.isEnabled(), row.getUpdatedByUserId(), row.getUpdatedAt(),
                false);
    }
}
