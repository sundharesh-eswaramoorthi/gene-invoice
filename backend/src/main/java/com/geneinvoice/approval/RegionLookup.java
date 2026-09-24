package com.geneinvoice.approval;

import java.util.List;

/**
 * Everything maker-checker needs to know about regions, expressed without naming one. Named
 * RegionLookup rather than RegionRights so it cannot be read as a second opinion about the
 * privilege/right partition, which lives in {@code com.geneinvoice.region.RegionRights} and
 * nowhere else (B2, B1 INTEGRATION).
 *
 * <p>{@link RegionLookupImpl} is the ONLY class in this package that imports
 * com.geneinvoice.region: approval -&gt; region is the permitted direction and region must never
 * import approval, so the whole edge is one file a reviewer can read (B2, B1).
 */
public interface RegionLookup {

    /** Which branch is this record in? Throws NotFoundException for a record that is not there. */
    Long regionOf(PendingTargetType type, Long id);

    /** Does this person work in this branch at all — the "may they even see it" question. */
    boolean hasAnyRight(Long userId, Long regionId);

    /** The people who hold {@code privilege} AND the region right it needs, here or everywhere. */
    List<Long> usersWith(String privilege, Long regionId);

    /** The regions the caller may decide in; the approvals list scopes on this (B1, B2). */
    List<Long> approvableRegions(Long userId);

    /**
     * The branch's name, for a sentence a person reads: "above the South approval limit". Not one
     * of B2's original four methods and added here because RegionLookup is the package's only
     * doorway to region, and the park message, the Accepted body and the approvals queue all need
     * the name rather than the id (B2, B1 INTEGRATION).
     */
    String regionName(Long regionId);
}
