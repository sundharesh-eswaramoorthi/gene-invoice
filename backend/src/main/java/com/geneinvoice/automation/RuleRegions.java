package com.geneinvoice.automation;

import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.region.UserRegionGrantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * WHERE a rule may reach, resolved at run time (A1, B1).
 *
 * <p>Two answers and only two. A rule that NAMES regions reaches exactly those — each of them
 * checked against its author's MANAGE grants when the rule was saved, so a named region is
 * already one its author could work in. A rule that names NONE reaches every region its author
 * may manage AS OF NOW, which is what makes "my rule covers the branches I look after" keep
 * meaning what the author meant when a branch opens or closes (A1, B1).
 *
 * <p>AN EMPTY ANSWER DENIES AND NEVER WIDENS. {@code RegionScope.asRegions} with an empty set
 * produces {@code cb.disjunction()} for every read and refuses every write, so an author who has
 * been deactivated, demoted or unstaffed since writing the rule reaches NOTHING rather than
 * everything. That asymmetry is the whole point: the failure mode of a region model has to be
 * "too little", never "too much" (A1, B1).
 *
 * <p>Resolved LIVE rather than frozen onto the rule, for the same reason {@code enabled} is read
 * live: taking somebody's branch away has to mean their rules stop reaching it, today, without
 * anybody remembering to re-save every rule they ever wrote (A1, B1).
 */
@Component
@RequiredArgsConstructor
public class RuleRegions {

    private final UserRegionGrantRepository grants;
    private final RegionRepository regions;

    /**
     * The region ids this rule may act in.
     *
     * @return a set that is never null and may be EMPTY, which means "nothing", not "everything"
     */
    @Transactional(readOnly = true)
    public Set<Long> reachOf(AutomationRule rule) {
        if (rule == null) return Set.of();
        Set<Long> named = rule.getRegionIds();
        if (named != null && !named.isEmpty()) {
            return Set.copyOf(named);
        }
        return manageableBy(rule.getCreatedByUserId());
    }

    /**
     * Every region this person may MANAGE, expanding the wildcard grant to the ACTIVE regions
     * only.
     *
     * <p>A wildcard holder is entitled to every region including ones created after the grant was
     * given, which is exactly why it has to be expanded here and now rather than stored: a
     * branch that opened last week is in this answer without anybody re-saving anything. Retired
     * regions are left out, because a rule reaching into a branch nobody works in any more would
     * act on records nobody is watching (B1).
     *
     * <p>MANAGE and not VIEW: every action a rule performs is a WRITE, and RegionAccess.requireManage
     * inside each service is the bound that is actually enforced. Handing back a VIEW-only region
     * here would produce a rule that matches records and then fails on every one of them (A1, B1).
     */
    @Transactional(readOnly = true)
    public Set<Long> manageableBy(Long userId) {
        if (userId == null) return Set.of();
        Set<Long> ids = new LinkedHashSet<>();
        for (UserRegionGrant grant : grants.findByUserId(userId)) {
            if (grant.getRight() == null || !grant.getRight().covers(RegionRight.MANAGE)) continue;
            if (grant.getRegionId() == null) {
                regions.findAll().stream().filter(Region::isActive).map(Region::getId).forEach(ids::add);
            } else {
                ids.add(grant.getRegionId());
            }
        }
        return Set.copyOf(ids);
    }
}
