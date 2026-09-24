package com.geneinvoice.region;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * One user's region rights, parsed once when the principal is built and carried on it, so the
 * per-record question "may I do this here?" costs no SQL at all. The other question — "may that
 * OTHER person work there?" — is a different fact and reads UserRegionGrantRepository (B1).
 *
 * @param byRegion  rights held in a named region; a region absent from the map is a region the
 *                  user holds nothing in, which is the whole point of the model
 * @param wildcard  rights held through a null-region grant, i.e. in every region including ones
 *                  created after the grant was given
 */
public record RegionGrants(Map<Long, Set<RegionRight>> byRegion, Set<RegionRight> wildcard) {

    public RegionGrants {
        byRegion = Map.copyOf(byRegion);
        wildcard = Set.copyOf(wildcard);
    }

    public static RegionGrants of(Collection<UserRegionGrant> grants) {
        if (grants == null || grants.isEmpty()) return none();
        Map<Long, Set<RegionRight>> byRegion = new HashMap<>();
        Set<RegionRight> wildcard = EnumSet.noneOf(RegionRight.class);
        for (UserRegionGrant g : grants) {
            if (g == null || g.getRight() == null) continue;
            if (g.getRegionId() == null) {
                wildcard.add(g.getRight());
            } else {
                byRegion.computeIfAbsent(g.getRegionId(), k -> EnumSet.noneOf(RegionRight.class))
                        .add(g.getRight());
            }
        }
        Map<Long, Set<RegionRight>> frozen = new HashMap<>();
        byRegion.forEach((regionId, rights) -> frozen.put(regionId, Set.copyOf(rights)));
        return new RegionGrants(frozen, Set.copyOf(wildcard));
    }

    /** A user with no grants at all, and the answer whenever there is no principal (B1). */
    public static RegionGrants none() {
        return new RegionGrants(Map.of(), Set.of());
    }

    public boolean isEmpty() {
        return byRegion.isEmpty() && wildcard.isEmpty();
    }

    /** True when a null-region grant makes this right hold everywhere. */
    public boolean allRegions(RegionRight atLeast) {
        return wildcard.stream().anyMatch(r -> r.covers(atLeast));
    }

    /** True when the right holds SOMEWHERE — which is what a global @PreAuthorize now means. */
    public boolean holdsAnywhere(RegionRight atLeast) {
        return allRegions(atLeast)
                || byRegion.values().stream().flatMap(Set::stream).anyMatch(r -> r.covers(atLeast));
    }

    /** "May I do this in region R?" — the per-record question. */
    public boolean may(Long regionId, RegionRight atLeast) {
        if (allRegions(atLeast)) return true;
        // An immutable Map throws on get(null), and an unplaced record is not "every region" (B1).
        if (regionId == null) return false;
        Set<RegionRight> here = byRegion.get(regionId);
        return here != null && here.stream().anyMatch(r -> r.covers(atLeast));
    }

    /**
     * The region ids to put in an IN list. EMPTY never means "in ()": ask {@link #allRegions}
     * first. With the wildcard, empty means add NO predicate at all; without it, empty means no
     * region is permitted and the read is denied with cb.disjunction() rather than an error (B1).
     */
    public Set<Long> with(RegionRight atLeast) {
        if (allRegions(atLeast)) return Set.of();
        Set<Long> ids = new LinkedHashSet<>();
        byRegion.forEach((regionId, rights) -> {
            if (rights.stream().anyMatch(r -> r.covers(atLeast))) ids.add(regionId);
        });
        return Set.copyOf(ids);
    }
}
