package com.geneinvoice.approval;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The cumulative window's memory (§5.11.3, SC-9): what a maker applied, unapproved, to a customer's records in a
 * region, operation by operation. Read only when a threshold with a window exists and the change alone is within
 * it, so a save no limit applies to reads nothing here.
 */
@Component
@RequiredArgsConstructor
public class ApprovalUsageLedger {

    private final ApprovalUsageRepository usage;

    /** The maker's applied changes of this kind to this customer since {@code since}. */
    public BigDecimal recent(Long makerUserId, Long regionId, ApprovalOperation op, Long customerId, Instant since) {
        BigDecimal sum = usage.sumSince(makerUserId, regionId, op, customerId, since);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** A change applied within the limit, remembered for the window. */
    public void record(Gated g, Long makerUserId, Instant at) {
        usage.save(ApprovalUsage.builder()
                .makerUserId(makerUserId)
                .regionId(g.regionId())
                .customerId(g.customerId())
                .operation(g.op())
                .amount(g.amount())
                .usedAt(at)
                .build());
    }
}
