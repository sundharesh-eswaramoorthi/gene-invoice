package com.geneinvoice.region;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.automation.rule.RuleService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RegionGrantService {

    public static final String USER_ENTITY = "USER";
    public static final String GRANTS_CHANGED = "USER_REGION_GRANTS_CHANGED";
    public static final String APPROVER_ADDED = "APPROVER_ADDED";

    private final UserRegionGrantRepository grantRepository;
    private final RegionRepository regionRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final NotificationService notificationService;
    private final RegionAccessQueries regionAccess;
    /** Looked up when used: the rules reach the services that the region code serves. */
    private final ObjectProvider<RuleService> ruleService;
    private final Clock clock;

    /**
     * Makes the user hold exactly this level and scope in the region, creating the grant or changing the one
     * there. For the seeder, fixtures and a region's creator; it is not bounded by the granter's own rights.
     */
    @Transactional
    public UserRegionGrant ensure(User user, Region region, AccessLevel level, RecordScope scope) {
        UserRegionGrant grant = grantRepository.findByUserIdAndRegionId(user.getId(), region.getId())
                .orElseGet(() -> UserRegionGrant.builder()
                        .user(user)
                        .region(region)
                        .grantedByUserId(currentUser.idOrNull())
                        .grantedAt(clock.instant())
                        .build());
        if (grant.getId() != null && grant.getAccessLevel() == level && grant.getRecordScope() == scope) {
            return grant;
        }
        grant.setAccessLevel(level);
        grant.setRecordScope(scope);
        return grantRepository.save(grant);
    }

    @Transactional(readOnly = true)
    public List<RegionDtos.UserGrantDto> grantsOf(Long userId) {
        if (!userRepository.existsById(userId)) throw new NotFoundException("User not found");
        return grantRepository.findWithRegionByUserId(userId).stream().map(RegionDtos.UserGrantDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<RegionDtos.RegionGrantHolderDto> holdersOf(Long regionId) {
        if (!regionRepository.existsById(regionId)) throw new NotFoundException("Region not found");
        return grantRepository.findWithUserByRegionId(regionId).stream()
                .map(RegionDtos.RegionGrantHolderDto::from).toList();
    }

    /**
     * Sets a user's region access to exactly {@code wanted}. The granter may add, change or remove a row only
     * where their own grant covers both the old and the new row (SC-5); every other row must come back as it
     * is. Nobody changes their own access, so an administrator cannot lock themselves out (AUTH-03).
     */
    @Transactional
    public List<RegionDtos.UserGrantDto> replace(Long userId, List<RegionDtos.GrantInput> wanted) {
        ViewScopeHolder.requireLive();
        // Locked, so two editors of one user's access take turns rather than merge into a mix of both.
        User target = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        User granter = currentUser.require();
        if (target.getId().equals(granter.getId())) {
            throw new BadRequestException("You cannot change your own region access");
        }
        if (target.getCustomerId() != null) {
            throw new BadRequestException("Customer logins take their region from their customer");
        }
        List<UserRegionGrant> current = grantRepository.findWithRegionByUserId(userId);
        Map<Long, UserRegionGrant> before = current.stream()
                .collect(Collectors.toMap(g -> g.getRegion().getId(), Function.identity()));
        Map<Long, RegionDtos.GrantInput> after = byRegion(wanted, "grants");
        requireWithinReach(before, after);
        if (sameRows(before, after)) {
            return current.stream().map(RegionDtos.UserGrantDto::from).toList();
        }

        List<RegionDtos.UserGrantDto> was = current.stream().map(RegionDtos.UserGrantDto::from).toList();
        // Executed at once, so the inserts below cannot collide with the rows they replace (F7).
        grantRepository.deleteAllOfUser(userId);
        List<RegionDtos.UserGrantDto> now = save(target, after, before, granter);
        auditService.record(USER_ENTITY, userId, GRANTS_CHANGED, was, now, granter.getId(), null, null);
        announceNewApprovers(target, before, after);
        // Their rules run as them: one that needs what they no longer hold anywhere is suspended now (§5.10.7).
        ruleService.getObject().recheckOwner(userId);
        return now;
    }

    /** The grants a new account starts with: none unless asked for, each bounded like a change (SC-5). */
    @Transactional
    public List<RegionDtos.UserGrantDto> grantNewUser(User saved, List<RegionDtos.GrantInput> wanted) {
        if (wanted == null || wanted.isEmpty()) return List.of();
        if (saved.getCustomerId() != null) {
            throw new BadRequestException("Customer logins take their region from their customer");
        }
        Map<Long, RegionDtos.GrantInput> after = byRegion(wanted, "regionGrants");
        requireWithinReach(Map.of(), after);
        User granter = currentUser.require();
        List<RegionDtos.UserGrantDto> now = save(saved, after, Map.of(), granter);
        auditService.record(USER_ENTITY, saved.getId(), GRANTS_CHANGED, List.of(), now, granter.getId(), null, null);
        announceNewApprovers(saved, Map.of(), after);
        return now;
    }

    /**
     * Where the user now holds APPROVE and did not before, and so can approve changes there, the region's other
     * approvers are told: who can approve their changes is theirs to know (§4.6).
     */
    private void announceNewApprovers(User user, Map<Long, UserRegionGrant> before,
                                      Map<Long, RegionDtos.GrantInput> after) {
        after.forEach((regionId, g) -> {
            UserRegionGrant was = before.get(regionId);
            if (g.level() != AccessLevel.APPROVE || (was != null && was.getAccessLevel() == AccessLevel.APPROVE)) {
                return;
            }
            if (!regionAccess.holds(user.getId(), Privileges.CHANGE_APPROVE, regionId)) return;
            String region = regionRepository.findById(regionId).map(Region::getName).orElse("region " + regionId);
            String name = user.getFullName() == null || user.getFullName().isBlank()
                    ? user.getUsername() : user.getFullName();
            notificationService.notifyHolders(Privileges.CHANGE_APPROVE, regionId, user.getId(), APPROVER_ADDED,
                    "New approver in " + region, name + " can now approve changes in " + region, null);
        });
    }

    /** One row per region, each naming a region that exists; reported against {@code field}. */
    private Map<Long, RegionDtos.GrantInput> byRegion(List<RegionDtos.GrantInput> wanted, String field) {
        Map<Long, RegionDtos.GrantInput> out = new LinkedHashMap<>();
        if (wanted == null) return out;
        Set<Long> ids = wanted.stream().map(RegionDtos.GrantInput::regionId).collect(Collectors.toSet());
        Map<Long, Region> known = ids.isEmpty() ? Map.of()
                : regionRepository.findAllById(ids).stream().collect(Collectors.toMap(Region::getId, r -> r));
        for (RegionDtos.GrantInput g : wanted) {
            Region region = known.get(g.regionId());
            if (region == null) {
                throw new GlobalExceptionHandler.InvalidFieldsException(Map.of(field,
                        "Region " + g.regionId() + " does not exist"));
            }
            if (out.putIfAbsent(g.regionId(), g) != null) {
                throw new GlobalExceptionHandler.InvalidFieldsException(Map.of(field,
                        "Region " + region.getCode() + " is listed more than once"));
            }
        }
        return out;
    }

    /**
     * Every row that changes (added, altered or removed) must lie within the granter's own grant in its
     * region: at least the old and the new level, and scope ALL when either row reaches all records. A row that
     * gives APPROVE also needs the granter to hold CHANGE_APPROVE there.
     */
    private void requireWithinReach(Map<Long, UserRegionGrant> before, Map<Long, RegionDtos.GrantInput> after) {
        RegionRights mine = currentUser.rights();
        Set<Long> touched = new TreeSet<>(before.keySet());
        touched.addAll(after.keySet());
        for (Long regionId : touched) {
            UserRegionGrant was = before.get(regionId);
            RegionDtos.GrantInput now = after.get(regionId);
            if (same(was, now)) continue;
            GrantRow own = mine.grantIn(regionId).orElseThrow(() -> new AccessDeniedException("Not allowed"));
            boolean coversLevel = (was == null || own.level().permits(was.getAccessLevel()))
                    && (now == null || own.level().permits(now.level()));
            boolean reachesAll = (was != null && was.getRecordScope() == RecordScope.ALL)
                    || (now != null && now.scope() == RecordScope.ALL);
            if (!coversLevel || (reachesAll && own.scope() != RecordScope.ALL)) {
                throw new AccessDeniedException("Not allowed");
            }
            // APPROVE makes an approver: only someone who may approve there may make one.
            if (now != null && now.level() == AccessLevel.APPROVE && !mine.has(Privileges.CHANGE_APPROVE, regionId)) {
                throw new AccessDeniedException("Not allowed");
            }
        }
    }

    private static boolean same(UserRegionGrant was, RegionDtos.GrantInput now) {
        if (was == null || now == null) return was == null && now == null;
        return was.getAccessLevel() == now.level() && was.getRecordScope() == now.scope();
    }

    private static boolean sameRows(Map<Long, UserRegionGrant> before, Map<Long, RegionDtos.GrantInput> after) {
        return before.keySet().equals(after.keySet())
                && before.keySet().stream().allMatch(r -> same(before.get(r), after.get(r)));
    }

    /** A row that is not changing keeps who granted it and when. */
    private List<RegionDtos.UserGrantDto> save(User user, Map<Long, RegionDtos.GrantInput> rows,
                                               Map<Long, UserRegionGrant> before, User granter) {
        Map<Long, Region> regions = regionRepository.findAllById(rows.keySet()).stream()
                .collect(Collectors.toMap(Region::getId, r -> r));
        List<UserRegionGrant> grants = new ArrayList<>();
        rows.forEach((regionId, g) -> {
            UserRegionGrant kept = same(before.get(regionId), g) ? before.get(regionId) : null;
            grants.add(UserRegionGrant.builder()
                    .user(user)
                    .region(Objects.requireNonNull(regions.get(regionId)))
                    .accessLevel(g.level())
                    .recordScope(g.scope())
                    .grantedByUserId(kept != null ? kept.getGrantedByUserId() : granter.getId())
                    .grantedAt(kept != null ? kept.getGrantedAt() : clock.instant())
                    .build());
        });
        return grantRepository.saveAll(grants).stream()
                .sorted(Comparator.comparing(g -> g.getRegion().getCode()))
                .map(RegionDtos.UserGrantDto::from).toList();
    }
}
