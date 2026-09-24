package com.geneinvoice.approval;

import com.geneinvoice.region.RegionAxis;
import com.geneinvoice.region.RegionAxisRegistry;

import java.util.Map;

/**
 * Maker-checker's answer to "which region is this row in?", contributed rather than edited into
 * region/RegionAxes: this class lives in the approval package, so the type-safe reference to the
 * two entities goes the permitted direction and region still imports nothing of ours (B2, B1).
 */
public final class ApprovalRegionAxes {

    private ApprovalRegionAxes() {}

    public static final RegionAxisRegistry.Contribution CONTRIBUTION =
            new RegionAxisRegistry.Contribution(
                    // OWN_ID exists for exactly this row: a held change records the branch it was
                    // raised in on a flat column, because a create has no record to walk to and
                    // because the branch must not move when the account does (B2, B1).
                    Map.of(PendingChange.class, RegionAxis.OWN_ID,
                            ApprovalThreshold.class, RegionAxis.NONE),
                    Map.of(ApprovalThreshold.class,
                            "a per-region limit is configuration reached only through its own"
                                    + " region-checked endpoint, never listed as rows (B2, B1)"));
}
