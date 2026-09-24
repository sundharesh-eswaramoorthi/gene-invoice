package com.geneinvoice.approval;

import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.Money;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.invoice.InvoiceDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Every shape maker-checker puts on the wire, in one file. Complete on day one deliberately: the
 * dispute, threshold and read units all land in later waves and would otherwise each be appending
 * to this class at the same time (B2).
 */
public final class ApprovalDtos {

    private ApprovalDtos() {}

    /**
     * The 202 body. Never 200 (nothing happened), never 409 (nothing conflicted) and never 403
     * (the maker was entitled to ask) — the error contract fixes this and it is not negotiable,
     * because a client that reads 200 as "done" will tell somebody their payment was recorded (B2).
     */
    public record Accepted(String outcome,
                           Long pendingChangeId,
                           PendingAction action,
                           PendingTargetType targetType,
                           Long targetId,
                           Long customerId,
                           Long regionId,
                           String regionName,
                           String summary,
                           BigDecimal exposure,
                           BigDecimal thresholdApplied,
                           Instant requestedAt,
                           String path,
                           String link,
                           String message) {

        /** The one spelling of the outcome string; a client matches on this and nothing else. */
        public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    }

    public record PendingChangeDto(Long id,
                                   PendingAction action,
                                   PendingTargetType targetType,
                                   Long targetId,
                                   Long customerId,
                                   String customerName,
                                   Long regionId,
                                   String regionName,
                                   String summary,
                                   BigDecimal exposure,
                                   BigDecimal thresholdApplied,
                                   boolean alwaysChecked,
                                   PendingChangeStatus status,
                                   Long requestedByUserId,
                                   String requestedByName,
                                   Instant requestedAt,
                                   Long decidedByUserId,
                                   String decidedByName,
                                   Instant decidedAt,
                                   String decisionNotes,
                                   String payloadJson,
                                   String beforeJson,
                                   Long targetVersion,
                                   String batchId,
                                   Boolean mine,
                                   Boolean canDecide,
                                   String cannotDecideReason) {

        /**
         * The reader-independent snapshot: mine/canDecide/cannotDecideReason stay null, the
         * pocMissing "not asked" convention, so an audit before/after blob never varies with who
         * happened to be looking (B2).
         */
        public static PendingChangeDto of(PendingChange c) {
            return new PendingChangeDto(c.getId(), c.getAction(), c.getTargetType(), c.getTargetId(),
                    c.getCustomerId(), null, c.getRegionId(), null, c.getSummary(),
                    c.getExposure(), c.getThresholdApplied(), c.isAlwaysChecked(), c.getStatus(),
                    c.getRequestedByUserId(), null, c.getRequestedAt(),
                    c.getDecidedByUserId(), null, c.getDecidedAt(), c.getDecisionNotes(),
                    c.getPayloadJson(), c.getBeforeJson(), c.getTargetVersion(), c.getBatchId(),
                    null, null, null);
        }

        /**
         * The same row answered FOR somebody. A maker is never their own checker, so "I raised it"
         * and "I may decide it here" are two separate facts and the UI needs both to say why a
         * button is not offered (B2).
         */
        public static PendingChangeDto of(PendingChange c, Long meId, boolean rightInRegion) {
            boolean mine = meId != null && meId.equals(c.getRequestedByUserId());
            boolean waiting = c.getStatus() == PendingChangeStatus.PENDING;
            String because = !waiting ? "This change has already been decided"
                    : mine ? "You raised this change, so somebody else has to decide it"
                    : !rightInRegion ? "You cannot approve changes in this branch"
                    : null;
            PendingChangeDto base = of(c);
            return new PendingChangeDto(base.id(), base.action(), base.targetType(), base.targetId(),
                    base.customerId(), base.customerName(), base.regionId(), base.regionName(),
                    base.summary(), base.exposure(), base.thresholdApplied(), base.alwaysChecked(),
                    base.status(), base.requestedByUserId(), base.requestedByName(),
                    base.requestedAt(), base.decidedByUserId(), base.decidedByName(),
                    base.decidedAt(), base.decisionNotes(), base.payloadJson(), base.beforeJson(),
                    base.targetVersion(), base.batchId(), mine, because == null, because);
        }
    }

    /** The decided change, plus whatever the replayed call returned (an InvoiceDto, a PaymentDto…). */
    public record Decision(PendingChangeDto change, Object result) {}

    public record DecisionRequest(@Size(max = FieldLimits.REASON) String decisionNotes) {}

    // A rejection without a reason is a rejection nobody can act on, which is why this one record
    // asks for what DecisionRequest leaves optional (B2).
    public record RejectRequest(@NotBlank @Size(max = FieldLimits.REASON) String decisionNotes) {}

    /** A transition that carries no request body at all — a void, a cancel. */
    public record NoPayload() {}

    public record AmountChange(@NotNull @Positive
                               @Digits(integer = 12, fraction = 2, message = Money.CENTS_MESSAGE)
                               BigDecimal amount,
                               @Size(max = FieldLimits.PAYMENT_METHOD) String method,
                               @Size(max = FieldLimits.PAYMENT_NOTES) String notes) {}

    public record ItemsChange(@NotEmpty @Valid List<InvoiceDtos.LineInput> items,
                              @Size(max = FieldLimits.INVOICE_NOTES) String notes) {}

    public record ReasonOnly(@Size(max = FieldLimits.REASON) String reason) {}

    public record DisputeApproval(String changeJson, DisputeDtos.ResolveDisputeRequest resolve) {}

    public record ThresholdRequest(@NotNull @PositiveOrZero
                                   @Digits(integer = 12, fraction = 2, message = Money.CENTS_MESSAGE)
                                   BigDecimal amount,
                                   @NotNull Boolean enabled) {}

    /**
     * The queue's tiles, all nine from ONE aggregate over the same filtered set the list reads.
     *
     * <p>{@code pendingExposure} is the money that is WAITING and is never netted into any
     * figure on any other screen: nothing pending has taken effect, so an amount that mixed
     * applied and unapplied money would be wrong everywhere it appeared (B2).
     *
     * <p>{@code awaitingMyDecisionCount} is the only reader-dependent tile — PENDING, in a branch
     * this caller may decide in, and not raised by this caller — because "how much is waiting"
     * and "how much is waiting for ME" are the two different questions a queue screen asks (B2).
     */
    public record ApprovalSummaryTiles(long count,
                                       long pendingCount,
                                       BigDecimal pendingExposure,
                                       long mineCount,
                                       long awaitingMyDecisionCount,
                                       long approvedCount,
                                       long rejectedCount,
                                       long withdrawnCount,
                                       long supersededCount) {}

    /** {@code fromDefault} is what lets the UI say "the deployment default" rather than showing a
     *  number the operator never typed and cannot find a row for (B2). */
    public record ThresholdDto(Long regionId,
                               String regionName,
                               BigDecimal amount,
                               boolean enabled,
                               Long updatedByUserId,
                               Instant updatedAt,
                               boolean fromDefault) {}
}
