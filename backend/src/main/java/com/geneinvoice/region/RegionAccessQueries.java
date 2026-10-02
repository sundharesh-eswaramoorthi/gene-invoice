package com.geneinvoice.region;

import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Questions about <em>other</em> users' rights in a region, answered from the database: who may be named,
 * notified or asked to approve there. The caller's own rights come from its principal ({@link RegionRights}).
 */
@Component
@RequiredArgsConstructor
public class RegionAccessQueries {

    private final UserRegionGrantRepository grantRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public boolean holds(Long userId, String privilege, Long regionId) {
        if (userId == null) return false;
        if (!Privileges.isRegional(privilege)) return userRepository.hasPrivilege(userId, privilege);
        requireAnswerable(privilege);
        if (regionId == null) return false;
        return grantRepository.hasPrivilegeInRegion(userId, privilege, regionId,
                AccessLevel.atLeast(Privileges.requiredLevel(privilege)));
    }

    /** Those of {@code userIds} holding any grant in the region. */
    @Transactional(readOnly = true)
    public Set<Long> withAccess(Collection<Long> userIds, Long regionId) {
        if (userIds == null || userIds.isEmpty() || regionId == null) return Set.of();
        return new TreeSet<>(grantRepository.usersWithAccess(userIds, regionId, AccessLevel.atLeast(AccessLevel.READ)));
    }

    /** Active staff holding the privilege in the region: approvers, and whom a notice about a record goes to. */
    @Transactional(readOnly = true)
    public List<User> holdersIn(String privilege, Long regionId) {
        requireAnswerable(privilege);
        if (regionId == null) return List.of();
        AccessLevel level = Privileges.requiredLevel(privilege);
        return grantRepository.findHoldersInRegion(privilege, regionId,
                AccessLevel.atLeast(level == null ? AccessLevel.READ : level));
    }

    /** SCOPE_OVERRIDE also depends on the grant's record scope, which these queries do not look at. */
    private static void requireAnswerable(String privilege) {
        if (Privileges.SCOPE_OVERRIDE.equals(privilege)) {
            throw new IllegalArgumentException("SCOPE_OVERRIDE is not answered for other users");
        }
    }
}
