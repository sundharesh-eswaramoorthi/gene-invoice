package com.geneinvoice.history.types;

import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.history.Rehydration;
import com.geneinvoice.history.TypeHistory;
import com.geneinvoice.history.versions.DisputeVersion;
import com.geneinvoice.region.Region;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.geneinvoice.history.TypeHistory.sameInstant;

/** Disputes. {@code updatedAt} is kept on a version but never makes one. */
public class DisputeHistory implements TypeHistory<Dispute, DisputeVersion> {

    public static final String CODE = "DISPUTE";

    @Override public String code() { return CODE; }
    @Override public Class<Dispute> liveType() { return Dispute.class; }
    @Override public Class<DisputeVersion> versionType() { return DisputeVersion.class; }
    @Override public int rank() { return 50; }

    @Override
    public Map<Long, DisputeVersion> snapshotAll(Collection<Dispute> live, SharedSessionContractImplementor s) {
        Map<Long, DisputeVersion> versions = new LinkedHashMap<>();
        for (Dispute d : live) {
            DisputeVersion v = new DisputeVersion();
            v.setId(d.getId());
            v.setCustomerId(d.getCustomerId());
            v.setOpenedByUserId(d.getOpenedByUserId());
            v.setTargetType(d.getTargetType());
            v.setTargetId(d.getTargetId());
            v.setReason(d.getReason());
            v.setProposedChangeJson(d.getProposedChangeJson());
            v.setStatus(d.getStatus());
            v.setAdminNotes(d.getAdminNotes());
            v.setResolvedByUserId(d.getResolvedByUserId());
            v.setResolvedAt(d.getResolvedAt());
            v.setInternal(d.isInternal());
            v.setDisputeOrigin(d.getOrigin());
            v.setRuleId(d.getRuleId());
            v.setRegionId(Region.idOf(d.getRegion()));
            v.setCreatedAt(d.getCreatedAt());
            v.setUpdatedAt(d.getUpdatedAt());
            versions.put(d.getId(), v);
        }
        return versions;
    }

    @Override
    public boolean sameState(DisputeVersion a, DisputeVersion b) {
        return Objects.equals(a.getCustomerId(), b.getCustomerId())
                && Objects.equals(a.getOpenedByUserId(), b.getOpenedByUserId())
                && a.getTargetType() == b.getTargetType()
                && Objects.equals(a.getTargetId(), b.getTargetId())
                && Objects.equals(a.getReason(), b.getReason())
                && Objects.equals(a.getProposedChangeJson(), b.getProposedChangeJson())
                && a.getStatus() == b.getStatus()
                && Objects.equals(a.getAdminNotes(), b.getAdminNotes())
                && Objects.equals(a.getResolvedByUserId(), b.getResolvedByUserId())
                && sameInstant(a.getResolvedAt(), b.getResolvedAt())
                && HistoricalDispute.internal(a.getInternal()) == HistoricalDispute.internal(b.getInternal())
                && Objects.equals(a.getDisputeOrigin(), b.getDisputeOrigin())
                && Objects.equals(a.getRuleId(), b.getRuleId())
                && Objects.equals(a.getRegionId(), b.getRegionId())
                && sameInstant(a.getCreatedAt(), b.getCreatedAt());
    }

    @Override
    public List<Dispute> rehydrate(List<DisputeVersion> versions, Rehydration ctx) {
        Map<Long, Region> regions = ctx.regions(versions.stream().map(DisputeVersion::getRegionId).toList());
        List<Dispute> disputes = new ArrayList<>(versions.size());
        for (DisputeVersion v : versions) {
            HistoricalDispute d = new HistoricalDispute();
            d.setId(v.getId());
            d.setCustomerId(v.getCustomerId());
            d.setOpenedByUserId(v.getOpenedByUserId());
            d.setTargetType(v.getTargetType());
            d.setTargetId(v.getTargetId());
            d.setReason(v.getReason());
            d.setProposedChangeJson(v.getProposedChangeJson());
            d.setStatus(v.getStatus());
            d.setAdminNotes(v.getAdminNotes());
            d.setResolvedByUserId(v.getResolvedByUserId());
            d.setResolvedAt(v.getResolvedAt());
            d.setInternal(HistoricalDispute.internal(v.getInternal()));
            d.setOrigin(v.getDisputeOrigin());
            d.setRuleId(v.getRuleId());
            d.setRegion(v.getRegionId() == null ? null : regions.get(v.getRegionId()));
            d.setCreatedAt(v.getCreatedAt());
            d.setUpdatedAt(v.getUpdatedAt());
            disputes.add(d);
        }
        return disputes;
    }
}
