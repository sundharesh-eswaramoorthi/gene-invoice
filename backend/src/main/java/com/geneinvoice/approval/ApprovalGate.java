package com.geneinvoice.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The one place a money mutator asks whether it is allowed to happen on the strength of one
 * person's save (B2).
 */
@Component
@RequiredArgsConstructor
public class ApprovalGate {

    private final ApprovalThresholds thresholds;
    private final RegionLookup regions;
    private final ApprovalContext context;
    private final PendingChangeRepository repository;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;

    /** pending_changes.summary is varchar(300) NOT NULL, and Postgres refuses rather than
     *  truncating — an items change that names its lines would take the whole save down at the
     *  one moment the save was already being held (B2). */
    static final int SUMMARY_MAX = 300;

    /**
     * Stops a money movement above this region's limit from happening (B2).
     *
     * <p>Called as the first statement after the record this is about to change is in hand. Under
     * the limit it does nothing at all. Above it, it throws, and the caller's transaction rolls
     * back — that rollback is what "does not take effect on save" means, and it is why the pending
     * row is written afterwards by GlobalExceptionHandler or BulkExecutor and not here: a
     * REQUIRES_NEW insert from in here would take a second connection out of a five-connection
     * pool while this transaction still holds the customer's row lock (PPD-01).
     */
    public void check(Proposal p) {
        // Applying an approved change calls this very mutator; without this the applier would
        // hold its own work for approval again, for ever (B2).
        if (context.isApplying()) return;

        Long regionId = regions.regionOf(p.regionSourceType(), p.regionSourceId());
        BigDecimal exposure = Money.scale(p.exposure());
        ApprovalThresholds.Limit limit = thresholds.forRegion(regionId);
        // Strictly above, both sides scaled: a payment for exactly the limit is not above it, and
        // comparing an unscaled 100000 with a scaled 100000.00 through equals() would say they
        // differ (B2).
        boolean over = p.action().alwaysChecked()
                || (limit.enabled() && exposure.compareTo(Money.scale(limit.amount())) > 0);
        if (!over) return;

        // One open change per record, checked here while this transaction still holds the
        // target's row lock. The database backstops it (uq_pending_open), because the lock is
        // released by the rollback before the row is written (B2).
        if (p.targetId() != null) {
            String key = PendingChange.keyOf(p.action().targetType(), p.targetId());
            if (repository.existsByPendingKey(key)) {
                // Named rather than merely refused: "something is waiting" with no number sends
                // the maker hunting through a queue for a change they may not be able to see (B2).
                Long waiting = repository.findByPendingKey(key).map(PendingChange::getId).orElse(null);
                throw new BadRequestException("A change on this record is already waiting for approval"
                        + (waiting == null ? "" : " (change #" + waiting + ")"));
            }
        }

        throw new PendingApprovalException(PendingChange.builder()
                .action(p.action())
                .targetType(p.action().targetType())
                .targetId(p.targetId())
                .customerId(p.customerId())
                .regionId(regionId)
                .exposure(exposure)
                // The limit this was MEASURED against, frozen here: an always-checked change has
                // no limit behind it at all, and zero says so without pretending a number was
                // consulted (B2).
                .thresholdApplied(limit.enabled() ? Money.scale(limit.amount()) : Money.scale(BigDecimal.ZERO))
                .alwaysChecked(p.action().alwaysChecked())
                // payload_json is NOT NULL: a transition that carries no body is an empty object
                // and never a null the applier would have to special-case (B2).
                .payloadJson(p.payload() == null ? "{}" : json(p.payload()))
                .payloadVersion(PendingChange.PAYLOAD_VERSION)
                .beforeJson(json(p.before()))
                .summary(fit(p.summary(), p.action()))
                .targetVersion(p.targetVersion())
                .status(PendingChangeStatus.PENDING)
                // Null when the automation engine (A5) raised it with no person behind it;
                // "someone else" is then everyone. actingAs is the engine's own answer to who is
                // accountable, consulted only when there is nobody signed in (B2, A5 INTEGRATION).
                .requestedByUserId(currentUser.idOrNull() != null
                        ? currentUser.idOrNull()
                        : context.actingAs())
                .requestedAt(Instant.now())
                .build());
    }

    /**
     * One save, described well enough that somebody who was not there can decide it. Two factories
     * rather than one constructor because the difference between "change this record" and "make a
     * new one" is exactly where the region and the row version come from, and a call site that has
     * to work that out is a call site that can get it wrong (B2).
     */
    public record Proposal(PendingAction action, Long targetId, Long customerId,
                           PendingTargetType regionSourceType, Long regionSourceId,
                           BigDecimal exposure, Object payload, Object before,
                           Long targetVersion, String summary) {

        /** A change that creates a record: no target yet, the region comes from the customer. */
        public static Proposal creating(PendingAction action, Long customerId, BigDecimal exposure,
                                        Object payload, String summary) {
            return new Proposal(action, null, customerId, PendingTargetType.CUSTOMER, customerId,
                    exposure, payload, null, null, summary);
        }

        /** A change to a record that exists: the region and the row version come from the record. */
        public static Proposal on(PendingAction action, Long targetId, Long customerId,
                                  BigDecimal exposure, Object payload, Object before,
                                  Long targetVersion, String summary) {
            return new Proposal(action, targetId, customerId, action.targetType(), targetId,
                    exposure, payload, before, targetVersion, summary);
        }
    }

    /** The AuditService.fit shape, against a NOT NULL column: never null, never over the limit. */
    private static String fit(String summary, PendingAction action) {
        if (summary == null || summary.isBlank()) return action.name();
        return summary.length() <= SUMMARY_MAX
                ? summary
                : summary.substring(0, SUMMARY_MAX - 1) + "…";
    }

    // AuditService.toJson's body: a payload that will not serialise must not take the whole save
    // down, because by the time this runs the save has already been decided against (B2).
    private String json(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{\"_serialization_error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }
}
