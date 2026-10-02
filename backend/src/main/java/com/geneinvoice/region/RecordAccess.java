package com.geneinvoice.region;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.TreeSet;

/**
 * What the caller may do to one record, worked out from the record's region (D14, R16): the flags record DTOs
 * carry so the client never re-derives region rights. Every flag is false in a historical view, where nothing can
 * be changed. Audit snapshots are built without these flags, so a caller's rights never land in audit JSON.
 */
@Component
@RequiredArgsConstructor
public class RecordAccess {

    private final CurrentUser currentUser;
    private final RegionRepository regionRepository;

    public Boolean canManage(ScopeResolver.Kind kind, Long regionId) {
        return can(kind.manage(), regionId);
    }

    public Boolean can(String privilege, Long regionId) {
        return !AsOf.active() && currentUser.hasIn(privilege, regionId);
    }

    public Boolean canMoveRegion(Long regionId) {
        return canMoveRegion(regionId, moveTargets());
    }

    /** With the caller's {@link #moveTargets()} read once, for a whole page of customers. */
    public Boolean canMoveRegion(Long regionId, Set<Long> moveTargets) {
        if (currentUser.idOrNull() == null || currentUser.isCustomer()) return false;
        if (!can(Privileges.CUSTOMER_MANAGE, regionId)) return false;
        return moveTargets.stream().anyMatch(r -> !r.equals(regionId));
    }

    /** The active regions the caller could move a customer into: those where they manage customers. */
    public Set<Long> moveTargets() {
        if (AsOf.active() || currentUser.idOrNull() == null || currentUser.isCustomer()) return Set.of();
        Set<Long> managed = currentUser.regionsWith(Privileges.CUSTOMER_MANAGE);
        if (managed.size() < 2) return managed;
        Set<Long> active = new TreeSet<>();
        for (Region r : regionRepository.findAllById(managed)) {
            if (r.isActive()) active.add(r.getId());
        }
        return active;
    }

    public Boolean canResolve(Dispute d) {
        if (currentUser.idOrNull() == null || currentUser.isCustomer()) return false;
        return d.getStatus() == DisputeStatus.PENDING && can(Privileges.DISPUTE_MANAGE, Region.idOf(d.getRegion()));
    }
}
