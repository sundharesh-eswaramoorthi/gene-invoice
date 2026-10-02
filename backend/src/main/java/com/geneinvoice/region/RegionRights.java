package com.geneinvoice.region;

import com.geneinvoice.privilege.Privileges;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * What a principal may do, region by region (§4.2). Immutable plain data built once per request from the
 * role's privileges and the grant rows, so it is safe on a detached principal and costs no SQL to ask.
 *
 * <p>A privilege is effective in region R when the role holds it and it is global, or the grant in R is at
 * least the privilege's level (and, for SCOPE_OVERRIDE, reaches ALL records). Grants only ever narrow the
 * role. A customer login holds its role's privileges in its own customer's region and nowhere else.
 */
public final class RegionRights {

    private static final RegionRights NONE = new RegionRights(false, Set.of(), Map.of(), null);

    private final boolean customer;
    private final Set<String> role;
    private final Map<Long, GrantRow> grants;
    private final Long ownRegionId;

    private RegionRights(boolean customer, Set<String> role, Map<Long, GrantRow> grants, Long ownRegionId) {
        this.customer = customer;
        this.role = role;
        this.grants = grants;
        this.ownRegionId = ownRegionId;
    }

    /** Nothing anywhere: what a thread without a principal is answered with (C-8). */
    public static RegionRights none() {
        return NONE;
    }

    public static RegionRights forStaff(Set<String> rolePrivileges, Collection<GrantRow> grants) {
        Map<Long, GrantRow> byRegion = new LinkedHashMap<>();
        for (GrantRow g : grants) {
            if (g.regionId() != null && g.level() != null && g.scope() != null) byRegion.put(g.regionId(), g);
        }
        return new RegionRights(false, Set.copyOf(rolePrivileges), Collections.unmodifiableMap(byRegion), null);
    }

    public static RegionRights forCustomer(Set<String> rolePrivileges, Long customerRegionId) {
        return new RegionRights(true, Set.copyOf(rolePrivileges), Map.of(), customerRegionId);
    }

    public boolean isCustomer() {
        return customer;
    }

    public boolean has(String privilege, Long regionId) {
        if (!role.contains(privilege)) return false;
        if (!Privileges.isRegional(privilege)) return true;
        if (regionId == null) return false;
        if (customer) return regionId.equals(ownRegionId);
        GrantRow grant = grants.get(regionId);
        return grant != null
                && grant.level().permits(Privileges.requiredLevel(privilege))
                && (!Privileges.SCOPE_OVERRIDE.equals(privilege) || grant.scope() == RecordScope.ALL);
    }

    /** Held somewhere: the meaning of a flat authority and of {@code @PreAuthorize}. */
    public boolean anywhere(String privilege) {
        if (!Privileges.isRegional(privilege)) return role.contains(privilege);
        for (Long regionId : grantedRegions()) {
            if (has(privilege, regionId)) return true;
        }
        return false;
    }

    public Set<Long> regionsWith(String privilege) {
        if (!Privileges.isRegional(privilege)) {
            throw new IllegalArgumentException(privilege + " is global; it is not held per region");
        }
        return grantedRegions().stream().filter(r -> has(privilege, r))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    public Set<Long> grantedRegions() {
        if (customer) return ownRegionId == null ? Set.of() : Set.of(ownRegionId);
        return Collections.unmodifiableSet(new TreeSet<>(grants.keySet()));
    }

    public boolean holdsAnyIn(Long regionId) {
        return regionId != null && grantedRegions().contains(regionId);
    }

    public Optional<GrantRow> grantIn(Long regionId) {
        return regionId == null ? Optional.empty() : Optional.ofNullable(grants.get(regionId));
    }

    /** The union across regions: the flat authorities, and {@code /me.privileges}. */
    public Set<String> effectiveAnywhere() {
        return role.stream().filter(this::anywhere).collect(Collectors.toCollection(TreeSet::new));
    }

    /** The regional privileges effective in one region: {@code /me.regions[].privileges}. */
    public Set<String> effectiveIn(Long regionId) {
        return role.stream().filter(Privileges::isRegional).filter(p -> has(p, regionId))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
