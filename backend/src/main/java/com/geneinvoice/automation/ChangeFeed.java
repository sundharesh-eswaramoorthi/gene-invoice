package com.geneinvoice.automation;

import com.geneinvoice.common.RecordChanged;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The trigger half of the engine: what changed, coalesced per transaction, written to the outbox
 * inside the user's own transaction (A1, A5).
 *
 * <p>TWO WAYS IN, and they exist for different reasons. The {@link RecordChanged} override is
 * called by {@code AuditService.record} — the one chokepoint every subject mutation already passes
 * through — and classifies what it is told by an explicit CREATE allow-list. The typed
 * {@link #changed(SubjectType, Long, Change)} is called by hand at the eight writes that move a
 * subject without auditing anything against it; four of those are the only source for the change
 * they describe and the other four are free, because coalescing collapses them into the row the
 * audit hook already published.
 *
 * <p>NOTHING HERE ACTS. A rule can never slow down or break a user's save, so the work of
 * deciding which rules match and performing them happens on another thread, after the commit,
 * driven by a table. All this class does is write down a fact.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChangeFeed implements RecordChanged {

    private final AutomationEventRepository events;

    /**
     * Resolved per call and never injected. Nothing implements {@link AutomationNudge} until the
     * consumer lands, and when it does the implementation reaches back through the action layer
     * into the services that write audit rows — so a hard dependency here would be the
     * construction-time cycle Spring Boot 3 refuses (A1, A5).
     */
    private final ObjectProvider<AutomationNudge> nudge;

    /**
     * The audit actions that mean a record came into existence, written down rather than inferred.
     *
     * <p>{@code before == null} IS NOT A CREATE, and the counter-examples are in this repository
     * today: {@code PocService.add} passes {@code before = null} for a CUSTOMER update and
     * {@code PocService.remove} passes {@code after = null} for a seat removal, which is also a
     * customer update. A design keyed on nullness fires "created" on a POC assignment and drops
     * the deletion it thought it had found (A1).
     */
    static final Set<String> CREATES = Set.of(
            "INVOICE_CREATED",     // InvoiceService.create
            "CUSTOMER_CREATED",    // CustomerService.create
            "PAYMENT_RECORDED");   // PaymentService.record

    /** The one HARD delete among the three subjects. The row is gone; a rule cannot act on it. */
    static final Set<String> DELETES = Set.of("CUSTOMER_DELETED");  // CustomerService.delete

    /**
     * THE ONE CUT THAT STOPS THE ENGINE TRIGGERING ITSELF (A5, B2 INTEGRATION).
     *
     * <p>A rule action that trips the approval gate is parked, and the park AUDITS — it must, or a
     * maker could probe the gate all day and leave no trace. But a create-shaped change has no
     * target yet, so {@code ApprovalService.anchorType} anchors that audit row on the ACCOUNT, and
     * the audit chokepoint above turns it into a CUSTOMER/UPDATED fact. That fact re-arms the very
     * rule that raised it: the rule matches again, the gate holds again, the park audits again,
     * and the installation grows a pending change, a step, an audit row and a notification to
     * every approver in the branch per sweep, for ever. With {@code async: true} it is not even
     * per sweep — {@code afterCompletion} nudges the single automation thread, which picks its own
     * event straight back up and starves every other rule in the product.
     *
     * <p>SUPPRESSING THE AUDIT WOULD BE THE WRONG CUT: the approval trail is the whole point of
     * the row. The right cut is that a change the engine itself raised does not RE-ENTER the
     * change feed as a trigger — the record did not change, a request about it was written down.
     * A change a PERSON parks still publishes, because the record they were working on is now
     * locked and a rule may legitimately be about that; only the engine's own parks are cut, and
     * only for the moment the park is being written.
     *
     * <p>Static because the value has to survive the journey into {@code ApprovalService} and out
     * through {@code AuditService} — the ApprovalContext shape, and for the same reason.
     */
    private static final ThreadLocal<Boolean> SUPPRESSED = new ThreadLocal<>();

    /**
     * Inside this, nothing is noted and so nothing is published (A5, B2 INTEGRATION).
     *
     * <p>Scoped around the WRITE and not around the transaction, which is enough: the pending map
     * is filled by {@code note} while the work runs and only drained at {@code beforeCommit}, so a
     * fact that was never noted is a row that is never written.
     */
    public <T> T withoutTriggers(Supplier<T> work) {
        Boolean previous = SUPPRESSED.get();
        SUPPRESSED.set(Boolean.TRUE);
        try {
            return work.get();
        } finally {
            // Restored and never blindly cleared, so a caller nested inside another suppression
            // cannot switch the feed back on halfway through it (A5).
            if (previous == null) SUPPRESSED.remove(); else SUPPRESSED.set(previous);
        }
    }

    /**
     * The explicit call sites' way in (A1).
     *
     * <p>It takes the answer rather than an action string, because these callers KNOW what they
     * did: they are writes that move a subject and audit nothing against it, so there is no action
     * string to classify.
     */
    public void changed(SubjectType type, Long id, Change change) {
        note(type, id, change);
    }

    /**
     * The audit chokepoint's way in (A1).
     *
     * <p>Only two things are filtered by action string — the creates and the one hard delete —
     * because filtering further would couple this engine to three dozen free-text literals spread
     * across a dozen services, and a literal renamed in 2027 would silently stop a rule.
     */
    @Override
    public void changed(String entityType, Long entityId, String auditAction) {
        // InvoiceSchemaUpgrade and RegionSchemaUpgrade both audit their backfill against id 0, and
        // there is no record 0 for a rule to be about (A1).
        if (entityId == null || entityId <= 0) return;
        SubjectType type = SubjectType.forAuditName(entityType);
        // PRODUCT, USER, ROLE, PROMISE, DISPUTE, REGION, PENDING_CHANGE, TASK: nothing a rule can
        // be written about, so nothing to publish (A1).
        if (type == null) return;
        if (DELETES.contains(auditAction)) return;
        note(type, entityId, CREATES.contains(auditAction) ? Change.CREATED : Change.UPDATED);
    }

    private void note(SubjectType type, Long id, Change change) {
        // Belt and braces for the typed callers: a null or zero id would become an event row with
        // a null subject and fail the user's save at commit, which is the one thing this class
        // must never do (A1).
        if (type == null || id == null || id <= 0 || change == null) return;
        // The engine is writing down a change it raised itself; that is not a change to this
        // record and must not come back round as a trigger (A5, B2 INTEGRATION).
        if (Boolean.TRUE.equals(SUPPRESSED.get())) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Nothing on a request path reaches here — every writer in this application is
            // @Transactional. Loud, so a future caller that is not finds out; never fatal to the
            // save, because a lost trigger is not worth a failed invoice (A1).
            log.warn("A {} change on {} was noted outside a transaction and cannot be published",
                    type, id);
            return;
        }
        publication().pending.merge(new Key(type, id), change, Change::strongest);
    }

    /**
     * THE PENDING MAP LIVES ON THE SYNCHRONIZATION, not on a bound resource, and that is not a
     * style choice (A5).
     *
     * <p>{@code getSynchronizations()} is per-transaction and is suspended and resumed WITH the
     * transaction, so a {@code BulkExecutor} row running under PROPAGATION_REQUIRES_NEW gets its
     * own map and publishes its own event atomically with its own row. A reviewer who "simplifies"
     * this into a {@code TransactionSynchronizationManager.bindResource} keyed on this bean
     * changes that — a hand-bound resource is NOT suspended, so the inner transaction would
     * commit the outer one's facts and then clear them. The one test that notices is
     * {@code AutomationTriggerTest#aNestedTransactionPublishesItsOwnFactsAndNotItsCallers}, which
     * is why it is marked load-bearing rather than incidental.
     */
    private Publication publication() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            if (s instanceof Publication p) return p;
        }
        Publication fresh = new Publication();
        TransactionSynchronizationManager.registerSynchronization(fresh);
        return fresh;
    }

    /** One (subject, id) pair. The map's key, so two changes to one record are one event (A1). */
    private record Key(SubjectType type, Long id) {}

    private final class Publication implements TransactionSynchronization {

        private final Map<Key, Change> pending = new LinkedHashMap<>();
        private List<Long> ids = List.of();

        /**
         * Strictly ahead of B3's history drain, which sits at LOWEST_PRECEDENCE, so that the
         * mirror writer always runs last over a fully flushed context and can never buffer a key
         * it will then not write. Ordering between the two is decided here rather than left to
         * registration order (A5, B3 INTEGRATION).
         */
        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE - 100;
        }

        /**
         * Inside the user's transaction, so the change and the fact about the change commit
         * together — or neither does (A5).
         *
         * <p>DELIBERATELY NOT IN A try/catch. A failed statement has already marked the
         * transaction rollback-only, so swallowing it would not save the caller anything; and it
         * is exactly the exposure the audit insert one line above it already carries. Publishing
         * after the commit instead would lose events on a crash, and "nothing is lost" is the
         * stronger requirement.
         */
        @Override
        public void beforeCommit(boolean readOnly) {
            if (readOnly || pending.isEmpty()) return;
            ids = events.saveAll(rows()).stream().map(AutomationEvent::getId).toList();
        }

        /**
         * afterCompletion and NOT afterCommit, and that is the mechanism of "a rule can never
         * break the user's save" (A5).
         *
         * <p>Spring's {@code TransactionSynchronizationUtils.invokeAfterCompletion} logs and
         * SWALLOWS what a callback throws; {@code afterCommit} rethrows into the caller, which
         * would mean a broken consumer turning a perfectly good save into a 500 AFTER the money
         * had already moved.
         *
         * <p>After the commit and not before it for the other half of the same rule: the consumer
         * must never be handed an id that nobody else can see yet, which is the discipline
         * {@code EmailService.send} already follows when it dispatches.
         */
        @Override
        public void afterCompletion(int status) {
            if (status == STATUS_COMMITTED && !ids.isEmpty()) {
                AutomationNudge target = nudge.getIfAvailable();
                // Null until the consumer lands, and null again whenever it is switched off. The
                // sweeper is the guarantee; this is only the hurry (A5).
                if (target != null) target.nudge(ids);
            }
            pending.clear();
            ids = List.of();
        }

        private List<AutomationEvent> rows() {
            List<AutomationEvent> rows = new ArrayList<>(pending.size());
            for (Map.Entry<Key, Change> entry : pending.entrySet()) {
                rows.add(AutomationEvent.builder()
                        .subjectType(entry.getKey().type())
                        .subjectId(entry.getKey().id())
                        .change(entry.getValue())
                        .build());
            }
            return rows;
        }
    }
}
