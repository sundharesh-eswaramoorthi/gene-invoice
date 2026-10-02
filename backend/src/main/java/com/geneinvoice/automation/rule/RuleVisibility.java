package com.geneinvoice.automation.rule;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.GrantRow;
import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.region.UserRegionGrantRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Who may see a rule (B1, PC-8; §5.10.10). Besides AUTOMATION_VIEW, the caller is its owner, holds AUTOMATION_ADMIN,
 * or holds a grant in a region where the rule acts: one of its regions, or for a rule with none, a region its owner
 * holds a grant in. Sets (the list, the summary) also answer "which regions am I asking about", for everyone,
 * administrators too. A rule the caller may not see is not found.
 */
@Component
@RequiredArgsConstructor
public class RuleVisibility {

    private final CurrentUser currentUser;
    private final UserRegionGrantRepository grantRepository;

    public boolean isAdmin() {
        return currentUser.has(Privileges.AUTOMATION_ADMIN);
    }

    /** One rule, read by id: the regions asked about do not narrow it (I3). */
    public boolean canSee(Long ownerUserId, Collection<Long> ruleRegions) {
        if (ownerUserId != null && ownerUserId.equals(currentUser.idOrNull())) return true;
        if (isAdmin()) return true;
        return meets(ownerUserId, ruleRegions, currentUser.rights().grantedRegions());
    }

    /** One rule in a set: seen, and acting in a region asked about when the request asks. */
    public boolean inSet(Long ownerUserId, Collection<Long> ruleRegions) {
        if (!canSee(ownerUserId, ruleRegions)) return false;
        Set<Long> asked = asked();
        return asked == null || meets(ownerUserId, ruleRegions, asked);
    }

    /** The regions a set answers for: those asked about that the caller holds, or every one they hold. */
    public Set<Long> answered() {
        return ViewScopeHolder.current().narrow(currentUser.rights().grantedRegions());
    }

    /** The regions asked about, as they narrow a set: an administrator's as asked, anyone else's as held; null: all. */
    private Set<Long> asked() {
        Set<Long> asked = ViewScopeHolder.current().regionIds();
        if (asked == null) return null;
        return isAdmin() ? asked : answered();
    }

    /** {@link #inSet} as a query over rules, for the live list and summary. */
    public PredicateFactory predicate() {
        Long me = currentUser.idOrNull();
        boolean admin = isAdmin();
        List<Long> granted = List.copyOf(currentUser.rights().grantedRegions());
        Set<Long> narrowed = asked();
        List<Long> asked = narrowed == null ? null : List.copyOf(narrowed);
        return (root, q, cb) -> {
            Predicate seen = admin ? cb.conjunction()
                    : cb.or(cb.equal(root.get("ownerUserId"), me), meets(root, q, cb, granted));
            return asked == null ? seen : cb.and(seen, meets(root, q, cb, asked));
        };
    }

    /** Whether a rule acts in one of {@code regions}: its own, or with none, its owner's. */
    private boolean meets(Long ownerUserId, Collection<Long> ruleRegions, Set<Long> regions) {
        if (regions.isEmpty()) return false;
        if (ruleRegions != null && !ruleRegions.isEmpty()) {
            return ruleRegions.stream().anyMatch(regions::contains);
        }
        return ownerUserId != null && ownerRegions(ownerUserId).stream().anyMatch(regions::contains);
    }

    private Set<Long> ownerRegions(Long ownerUserId) {
        Set<Long> out = new TreeSet<>();
        for (GrantRow row : grantRepository.rowsFor(ownerUserId)) out.add(row.regionId());
        return out;
    }

    private static Predicate meets(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb, List<Long> regions) {
        if (regions.isEmpty()) return cb.disjunction();
        Subquery<Long> listed = q.subquery(Long.class);
        Root<AutomationRule> r = listed.from(AutomationRule.class);
        Join<AutomationRule, Long> region = r.join("regionIds");
        listed.select(r.get("id")).where(cb.equal(r.get("id"), root.get("id")), region.in(regions));

        Subquery<Long> owners = q.subquery(Long.class);
        Root<UserRegionGrant> g = owners.from(UserRegionGrant.class);
        owners.select(g.get("id")).where(cb.equal(g.get("user").get("id"), root.get("ownerUserId")),
                g.get("region").get("id").in(regions));
        return cb.or(cb.exists(listed), cb.and(cb.isEmpty(root.get("regionIds")), cb.exists(owners)));
    }
}
