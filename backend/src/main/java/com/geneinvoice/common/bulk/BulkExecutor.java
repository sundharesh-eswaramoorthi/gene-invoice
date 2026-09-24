package com.geneinvoice.common.bulk;

import com.geneinvoice.approval.ApprovalProperties;
import com.geneinvoice.approval.ApprovalService;
import com.geneinvoice.approval.PendingApprovalException;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.query.TableQueryExecutor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class BulkExecutor {

    private final TransactionTemplate perRecord;
    // Not a package edge anybody would choose, and the one the design mandates: a bulk run is the
    // second place in the application (GlobalExceptionHandler is the first) where a mutator's
    // transaction has already rolled back and the held change still has to be written down. The
    // permitted direction is downward into approval, and approval never names common.bulk (B2).
    private final ApprovalService approvals;
    private final ApprovalProperties approvalProperties;

    public BulkExecutor(PlatformTransactionManager txManager, ApprovalService approvals,
                        ApprovalProperties approvalProperties) {
        this.perRecord = new TransactionTemplate(txManager);
        this.perRecord.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.approvals = approvals;
        this.approvalProperties = approvalProperties;
    }

    public static class IneligibleException extends RuntimeException {
        public IneligibleException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    public interface RecordOperation {
        void apply(Long id);
    }

    public static RecordOperation eligibility(RecordOperation op) {
        return id -> {
            try {
                op.apply(id);
            } catch (BadRequestException e) {
                throw new IneligibleException(e.getMessage());
            }
        };
    }

    public static final String NOT_REACHABLE = "Not found, or outside your scope or the current filter";

    /** Why a row was left alone when a concurrent writer got to it first (TBL-07). */
    public static final String CHANGED_WHILE_RUNNING = "This record changed while the action was running";

    /** One click over a whole filter must not become 5000 approvals somebody now has to decide one
     *  at a time, so the run stops QUEUEING at app.approvals.bulk-pending-limit and says so rather
     *  than quietly acting on the rest (B2). */
    public static final String PENDING_LIMIT_REACHED =
            "Not sent: this run already raised the most approvals allowed at once";

    /** The database's answer, not Java's: the gate's in-transaction exists() catches the ordinary
     *  case, and uq_pending_open catches the two runs that both passed it. A record already spoken
     *  for did not qualify for this one, which is what skipped means (B2). */
    public static final String ALREADY_WAITING =
            "A change on this record is already waiting for approval";

    /** pending_changes.batch_id is varchar(40); a UUID is 36, so this never fires today and is
     *  here because Postgres refuses an over-long value rather than truncating it (B2). */
    private static final int BATCH_ID_MAX = 40;

    public BulkDtos.BulkResult run(BulkDtos.BulkRequest req, List<Long> permitted, boolean truncated,
                                   RecordOperation op) {
        List<Long> requested = req.allMatching() || req.ids() == null ? permitted : req.ids();
        Set<Long> reachable = new HashSet<>(permitted);
        List<Long> unique = new ArrayList<>(new LinkedHashSet<>(requested));
        List<Long> succeeded = new ArrayList<>();
        List<BulkDtos.BulkOutcome> failed = new ArrayList<>();
        List<BulkDtos.BulkOutcome> skipped = new ArrayList<>();
        List<BulkDtos.BulkOutcome> pending = new ArrayList<>();
        // Allocated before the loop and not on the first held row: it is what makes "decide
        // everything that one click raised" one act (POST /api/approvals/batches/{id}/decide), and
        // a run that holds nothing simply spends an id nobody ever reads, which is free. The
        // Email.batchId precedent, EmailService:249 (B2).
        String batchId = newBatchId();
        boolean pendingLimitReached = false;

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
            } catch (ConcurrencyFailureException e) {
                // Another writer reached this row first and this transaction lost on commit.
                // Nothing of it was written, and the row is simply one this run did not act on,
                // so it belongs with the other rows that did not qualify rather than in the
                // errors — and never in succeeded, which is what it used to be reported as when
                // two bulk runs overlapped (TBL-07).
                skipped.add(new BulkDtos.BulkOutcome(id, CHANGED_WHILE_RUNNING));
            } catch (PendingApprovalException e) {
                // Above the region's limit this row did not change; it is waiting for a second
                // pair of eyes, which is neither a success nor a failure nor a row that did not
                // qualify — skipped already means "did not qualify" (TBL-05, B2).
                //
                // This catch MUST stay above the generic one below. PendingApprovalException is
                // deliberately not a BadRequestException, so eligibility(...) lets it through
                // untouched; without this clause it lands in `failed` with the gate's message and
                // nothing is ever parked, which is exactly the wrong answer for the caller AND
                // for the money (B2).
                if (pending.size() >= approvalProperties.bulkPendingLimit()) {
                    pendingLimitReached = true;
                    skipped.add(new BulkDtos.BulkOutcome(id, PENDING_LIMIT_REACHED));
                } else {
                    e.change().setBatchId(batchId);
                    try {
                        // Through perRecord because ApprovalService.park is MANDATORY and run() is
                        // not transactional. The row's own REQUIRES_NEW transaction has already
                        // rolled back by the time we get here, so this takes a connection out of
                        // the pool rather than holding a second one against the first (PPD-01, B2).
                        Long parked = perRecord.execute(s -> approvals.park(e.change(), null))
                                .pendingChangeId();
                        pending.add(new BulkDtos.BulkOutcome(id,
                                "Sent for approval as change #" + parked));
                    } catch (DataIntegrityViolationException dup) {
                        // uq_pending_open, caught on this template and outside park(): another run
                        // wrote the one change this record is allowed to have waiting (B2).
                        skipped.add(new BulkDtos.BulkOutcome(id, ALREADY_WAITING));
                    }
                }
            } catch (RuntimeException e) {
                failed.add(new BulkDtos.BulkOutcome(id, rootMessage(e)));
            }
        }
        return new BulkDtos.BulkResult(req.action(), unique.size(), succeeded, failed, skipped,
                truncated, TableQueryExecutor.BULK_ID_LIMIT, pending, pendingLimitReached);
    }

    private static String newBatchId() {
        String id = UUID.randomUUID().toString();
        return id.length() <= BATCH_ID_MAX ? id : id.substring(0, BATCH_ID_MAX);
    }

    private String rootMessage(RuntimeException e) {
        Throwable t = e;
        while (t.getCause() != null && t.getMessage() == null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
