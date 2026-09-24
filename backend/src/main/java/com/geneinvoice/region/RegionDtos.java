package com.geneinvoice.region;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The region wire shapes. RegionGrantDto is what the sign-in payload and GET /api/auth/me carry,
 * so the client can hide an edit button on a row in a region the person only reads instead of
 * offering it and collecting a 403 (B1).
 */
public class RegionDtos {

    /**
     * One region this caller holds rights in. A region where they hold nothing is simply absent,
     * which is why an empty list beside allRegions:false is a real, renderable state. rights is in
     * the ladder's own order (VIEW, MANAGE, APPROVE) so the payload is stable.
     */
    public record RegionGrantDto(Long id, String code, String name, List<String> rights) {}

    /**
     * The named grants only: the wildcard is reported separately as allRegions, because listing
     * every region a wildcard holder can reach would be a query per sign-in and would go stale the
     * moment a branch is opened (B1).
     *
     * @param grants  the caller's parsed rights, off the principal
     * @param regions the region repository, for the code and name a client renders
     */
    public static List<RegionGrantDto> heldBy(RegionGrants grants, RegionRepository regions) {
        Set<Long> ids = grants.byRegion().keySet();
        if (ids.isEmpty()) return List.of();
        Map<Long, Region> named = regions.findAllById(ids).stream()
                .collect(Collectors.toMap(Region::getId, Function.identity()));
        return ids.stream()
                // A grant naming a region that has since been deleted is skipped rather than
                // rendered as a nameless row: the grant is spent, not broken (B1).
                .filter(named::containsKey)
                .map(named::get)
                .sorted(Comparator.comparing(Region::getCode))
                .map(region -> new RegionGrantDto(region.getId(), region.getCode(), region.getName(),
                        rightsOf(grants.byRegion().get(region.getId()))))
                .toList();
    }

    private static List<String> rightsOf(Set<RegionRight> held) {
        return Arrays.stream(RegionRight.values())
                .filter(held::contains)
                .map(Enum::name)
                .toList();
    }
}
