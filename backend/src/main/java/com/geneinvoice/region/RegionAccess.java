package com.geneinvoice.region;

import com.geneinvoice.auth.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * The write-side gate: the one place that turns "may I work in the region this write NAMES?" into
 * a refusal. Reads never come here — a read the caller's regions exclude is emptied by the query
 * predicate and reads as nonexistent, which is AUTH-08 and is never relaxed (B1).
 */
@Component
@RequiredArgsConstructor
public class RegionAccess {

    private final CurrentUser currentUser;

    /**
     * 403 and not 404, because the caller NAMED the region: no id space is being probed, so
     * refusing plainly tells them the true thing rather than pretending the branch does not
     * exist (B1, D-46).
     *
     * <p>A customer login returns early: its reach is its own account, pinned by customer_id, and
     * it deliberately holds no grants — gating it here would refuse every dispute and every
     * document a customer raises. A headless caller has no principal at all and would be refused
     * by CurrentUser rather than waved through, so it says which caller-free path it is instead:
     * asSystem lets the schema upgrade, the mail webhook and the sweepers past, and asRegions
     * bounds the automation consumer by its own rule's regions rather than by nobody's (B1).
     */
    public void require(Long regionId, RegionRight atLeast) {
        if (RegionScope.systemReason() != null) {
            Set<Long> narrowed = RegionScope.ambientRegions();
            // asSystem widens to every region; asRegions replaces the grants with an explicit set,
            // and a write outside it is still a refusal — the hatch is a bound, not a bypass (B1).
            if (narrowed != null && !narrowed.contains(regionId)) {
                throw new AccessDeniedException("That region is outside this run's reach");
            }
            return;
        }
        if (currentUser.isCustomer()) return;
        if (!currentUser.grants().may(regionId, atLeast)) {
            throw new AccessDeniedException("You have no " + atLeast.name().toLowerCase()
                    + " access in that region");
        }
    }

    /** The level every create-here and move-here needs: making a record is MANAGE (B1). */
    public void requireManage(Long regionId) {
        require(regionId, RegionRight.MANAGE);
    }
}
