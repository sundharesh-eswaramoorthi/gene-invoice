package com.geneinvoice.approval;

import java.math.BigDecimal;

/**
 * One gated change, as its service measured it (§5.11.2): what it is about, whose customer and region, its
 * amount, and what the approver is shown ({@code base} before, {@code proposed} after). {@code payload} is what
 * the approval re-runs the service method with.
 *
 * @param affectedType the record whose money state changes: the entity itself, or a dispute's target
 * @param checkerStepSatisfied the approver of a dispute is the second pair of eyes already (§5.11.8)
 */
public record Gated(ApprovalOperation op, Long entityId, String entityLabel, String affectedType, Long affectedId,
                    Long disputeId, Long customerId, String customerName, Long regionId, Long recordPocUserId,
                    BigDecimal amount, Object payload, Object base, Object proposed, String summary,
                    boolean checkerStepSatisfied) {

    /** {@code "<ENTITY>:<id>"} for a change to an existing record; a create has none. */
    public String outstandingKey() {
        return outstandingKey(op, entityId);
    }

    /** A region has one outstanding change of its limits, whoever asks: {@code "REGION_THRESHOLDS:<id>"}. */
    static String outstandingKey(ApprovalOperation op, Long entityId) {
        if (op.isCreate() || entityId == null) return null;
        return op == ApprovalOperation.THRESHOLD_CHANGE ? THRESHOLDS_KEY + entityId : op.entityType() + ":" + entityId;
    }

    static final String THRESHOLDS_KEY = "REGION_THRESHOLDS:";

    /**
     * Where the change's audit rows go: the customer for a create, the region for its limits, otherwise the record
     * whose money moves.
     */
    public String auditEntityType() {
        return auditEntityType(op, affectedType);
    }

    public Long auditEntityId() {
        return auditEntityId(op, customerId, affectedId, entityId);
    }

    static String auditEntityType(ApprovalOperation op, String affectedType) {
        if (op.isCreate()) return "CUSTOMER";
        return affectedType != null ? affectedType : op.entityType();
    }

    static Long auditEntityId(ApprovalOperation op, Long customerId, Long affectedId, Long entityId) {
        if (op.isCreate()) return customerId;
        return affectedId != null ? affectedId : entityId;
    }
}
