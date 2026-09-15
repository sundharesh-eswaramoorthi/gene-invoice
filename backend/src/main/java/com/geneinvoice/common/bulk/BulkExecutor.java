package com.geneinvoice.common.bulk;

import com.geneinvoice.common.query.TableQueryExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs a per-record operation for a bulk action. Each record commits or rolls back on its own, so
 * one bad row cannot take the batch with it, and every id is accounted for in the result.
 */
@Component
public class BulkExecutor {

    private final TransactionTemplate perRecord;

    public BulkExecutor(PlatformTransactionManager txManager) {
        this.perRecord = new TransactionTemplate(txManager);
        this.perRecord.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Thrown by an operation when the caller may not act on that record, or it does not qualify. */
    public static class IneligibleException extends RuntimeException {
        public IneligibleException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    public interface RecordOperation {
        void apply(Long id);
    }

    /** One reason for every id the caller cannot reach, so the response never reveals which exist. */
    public static final String NOT_REACHABLE = "Not found, or outside your scope or the current filter";

    /**
     * Runs a bulk action over the ids the request asked for. An id outside {@code permitted} —
     * unknown, outside the caller's scope, or not matching the filter — is reported as skipped,
     * never silently dropped, so every requested id lands in exactly one outcome (AC-D5, AC-D6).
     */
    public BulkDtos.BulkResult run(BulkDtos.BulkRequest req, List<Long> permitted, boolean truncated,
                                   RecordOperation op) {
        List<Long> requested = req.allMatching() || req.ids() == null ? permitted : req.ids();
        Set<Long> reachable = new HashSet<>(permitted);
        List<Long> unique = new ArrayList<>(new LinkedHashSet<>(requested));
        List<Long> succeeded = new ArrayList<>();
        List<BulkDtos.BulkOutcome> failed = new ArrayList<>();
        List<BulkDtos.BulkOutcome> skipped = new ArrayList<>();

        for (Long id : unique) {
            if (!reachable.contains(id)) {
                skipped.add(new BulkDtos.BulkOutcome(id, NOT_REACHABLE));
                continue;
            }
            try {
                perRecord.executeWithoutResult(status -> op.apply(id));
                succeeded.add(id);
            } catch (IneligibleException e) {
                skipped.add(new BulkDtos.BulkOutcome(id, e.getMessage()));
            } catch (RuntimeException e) {
                failed.add(new BulkDtos.BulkOutcome(id, rootMessage(e)));
            }
        }
        return new BulkDtos.BulkResult(req.action(), unique.size(), succeeded, failed, skipped,
                truncated, TableQueryExecutor.BULK_ID_LIMIT);
    }

    private String rootMessage(RuntimeException e) {
        Throwable t = e;
        while (t.getCause() != null && t.getMessage() == null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
