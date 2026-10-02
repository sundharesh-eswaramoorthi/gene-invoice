package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.approval.ApprovalThresholdService.Limit;
import com.geneinvoice.approval.ApprovalThresholdService.Loosening;
import com.geneinvoice.region.RegionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Applies an approved loosening of a region's limits (§5.11.11) as its maker, who must still set limits there. The
 * region and its limits are locked first (§5.11.5). A limit someone else changed since the request, to anything but
 * what the change sets, fails the approval: nobody's later tightening is undone by an older loosening.
 */
@Component
@RequiredArgsConstructor
public class ThresholdChangeApplier implements ChangeApplier {

    /** Looked up when used: the service shows held changes, whose views need every applier, this one included. */
    private final ObjectProvider<ApprovalThresholdService> thresholdService;
    private final ApprovalThresholdRepository thresholds;
    private final RegionRepository regionRepository;
    private final PayloadCodec codec;

    @Override
    public Set<ApprovalOperation> operations() {
        return EnumSet.of(ApprovalOperation.THRESHOLD_CHANGE);
    }

    @Override
    public void lockDomainRows(PendingChange pc) {
        regionRepository.findByIdForUpdate(pc.getEntityId());
        thresholds.findByRegionIdForUpdate(pc.getEntityId());
    }

    /** A region is its own region: a change of its limits never "moves". */
    @Override
    public Long currentRegionId(PendingChange pc) {
        return pc.getEntityId();
    }

    @Override
    public Outcome<?> apply(PendingChange pc) {
        Loosening change = codec.read(pc.getPayloadJson(), Loosening.class);
        if (change == null || change.thresholds() == null) throw new PayloadException("The stored change is empty");
        JsonNode base = codec.tree(pc.getBaseJson());
        JsonNode proposed = codec.tree(pc.getProposedJson());
        Map<String, Limit> now = service().currentLimits(pc.getEntityId(), keys(proposed));
        for (Iterator<String> names = proposed.fieldNames(); names.hasNext(); ) {
            String key = names.next();
            Limit was = ApprovalThresholdService.limitOf(base == null ? null : base.get(key));
            Limit becomes = ApprovalThresholdService.limitOf(proposed.get(key));
            Limit current = now.get(key);
            if (!ApprovalThresholdService.sameLimit(current, was)
                    && !ApprovalThresholdService.sameLimit(current, becomes)) {
                throw ChangeConflictException.changed(
                        "The " + PendingChangeViews.label(key).toLowerCase(Locale.ROOT) + " limit");
            }
        }
        return Outcome.applied(service().applyLoosening(pc.getEntityId(), change));
    }

    @Override
    public Object currentView(PendingChange pc) {
        JsonNode proposed = codec.tree(pc.getProposedJson());
        return proposed == null ? null : service().currentLimits(pc.getEntityId(), keys(proposed));
    }

    @Override
    public Long resultEntityId(Object applied) {
        return ((ApprovalThresholdService.ThresholdsView) applied).regionId();
    }

    @Override
    public Object resultView(Object applied) {
        return applied;
    }

    private ApprovalThresholdService service() {
        return thresholdService.getObject();
    }

    private static Set<String> keys(JsonNode proposed) {
        Set<String> keys = new LinkedHashSet<>();
        if (proposed != null) proposed.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
}
