package com.geneinvoice.automation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The trigger side of the engine (R1): the two calls an entity service makes as it saves, and the
 * one call the consumer and the scheduler share to queue rule work.
 *
 * <p>What a save does here is one insert into a table with no foreign keys and no joins, inside the
 * caller's own transaction, and then nothing. No rule is read, no filter is evaluated, no queue is
 * touched until the transaction has committed. That is the whole contract: a rule can never slow
 * down the user's save, can never fail it, and can never see a record that the save then rolled
 * back.
 *
 * <p>The insert itself is deliberately not wrapped in a try/catch. Swallowing a failed insert would
 * not save the caller anyway — a failed statement leaves their transaction rollback-only, so they
 * would get a confusing error at commit instead of an honest one here — and the only way this row
 * can fail to go in is the database being gone, which is already failing the save. What IS wrapped
 * is everything after the commit, where a failure genuinely costs nothing because the row is
 * already safe and the sweeper will find it (R8).
 */
@Component
@Slf4j
public class AutomationEvents {

    private final AutomationEventRepository events;
    private final AutomationQueue queue;
    /**
     * For {@link #enqueueForRule}, which inserts one row per transaction so that a key another run
     * already took skips that row instead of poisoning the batch. REQUIRES_NEW rather than the
     * shared template, so it behaves the same whether or not a caller happens to have one open.
     */
    private final TransactionTemplate ownTransaction;

    public AutomationEvents(AutomationEventRepository events, AutomationQueue queue,
                            PlatformTransactionManager transactionManager) {
        this.events = events;
        this.queue = queue;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---- what a save calls -------------------------------------------------------------

    /** A record of this kind has just been created. Called from inside the saving transaction. */
    public void recordCreated(AutomationEntityType type, Long id) {
        record(type, id, TriggerKind.CREATED);
    }

    /** A record of this kind has just been changed. Called from inside the saving transaction. */
    public void recordUpdated(AutomationEntityType type, Long id) {
        record(type, id, TriggerKind.UPDATED);
    }

    private void record(AutomationEntityType type, Long id, TriggerKind trigger) {
        if (type == null || id == null) return;
        Instant now = Instant.now();
        AutomationEvent row = events.save(AutomationEvent.builder()
                // No rule: which rules apply is decided on the consumer, against the rules as they
                // stand when the work is actually done rather than as they stood mid-save (R2).
                .ruleId(null)
                .entityType(type)
                .entityId(id)
                .trigger(trigger)
                .idempotencyKey(AutomationEvent.fanOutKey(type, id, trigger))
                .status(AutomationEventStatus.QUEUED)
                .enqueuedAt(now)
                .build());
        nudgeAfterCommit(row.getId());
    }

    /**
     * Tells the transport about the row once the record it is about actually exists — that is, once
     * the caller's transaction has committed. Publishing before the commit is the classic way to
     * hand a consumer the id of a row nobody else can see yet, and with a single thread and a fast
     * consumer it happens every time, not rarely.
     *
     * <p>With no transaction in progress (a background caller, or a test that is not wrapped in
     * one) there is nothing to wait for and the nudge goes now.
     */
    private void nudgeAfterCommit(Long eventId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            nudge(eventId);
            return;
        }
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    nudge(eventId);
                }
            });
        } catch (RuntimeException e) {
            // Registering can only fail where synchronisation has just been closed. The row is
            // committed either way; the sweeper is what actually guarantees it runs.
            log.debug("Could not register the automation nudge for event {}", eventId, e);
        }
    }

    private void nudge(Long eventId) {
        try {
            queue.enqueue(eventId);
        } catch (RuntimeException e) {
            log.warn("Could not publish automation event {}; the sweeper will pick it up", eventId, e);
        }
    }

    // ---- what the consumer and the scheduler call ---------------------------------------

    /**
     * Queues one rule against each of these records and hands the new rows to the transport. Shared
     * by all three ways rule work arises — a fan-out row being consumed, a daily or weekly run, and
     * Run now — so that all three get the same de-duplication for free.
     *
     * <p>A key the table already holds is skipped, and that is the point (R4): running a rule twice
     * in the same bucket does nothing the first run did not already do. The existing keys are read
     * in one query for the common case, and each surviving insert still gets its own transaction,
     * because two runs starting at the same moment will both find the key absent and only one of
     * them can win the index.
     *
     * <p>Nothing here reads the rule's filters, and it must not: which rules apply is this stage's
     * question, and whether this record matches is the next one's (R2). That is exactly why a row
     * which goes on to match nothing hands its key back as it settles — the key claimed here is a
     * claim on work, and work that turned out not to exist may not keep it for the rest of the day
     * (D-72). See {@link AutomationEvent#releasedKey}.
     *
     * @return the ids of the rows actually written, which is also how many pieces of new work there are
     */
    public List<Long> enqueueForRule(AutomationRule rule, List<Long> entityIds, Instant now) {
        if (entityIds == null || entityIds.isEmpty()) return List.of();
        List<String> keys = entityIds.stream()
                .map(id -> AutomationEvent.ruleKey(rule.getId(), rule.getEntityType(), id,
                        rule.getTrigger(), now))
                .toList();
        Set<String> taken = new HashSet<>(events.findExistingKeys(keys));
        List<Long> written = new ArrayList<>();
        for (int i = 0; i < entityIds.size(); i++) {
            String key = keys.get(i);
            if (!taken.add(key)) continue;
            Long id = insert(rule, entityIds.get(i), key, now);
            if (id != null) written.add(id);
        }
        queue.enqueueAll(written);
        return written;
    }

    private Long insert(AutomationRule rule, Long entityId, String key, Instant now) {
        try {
            return ownTransaction.execute(status -> events.save(AutomationEvent.builder()
                    .ruleId(rule.getId())
                    .entityType(rule.getEntityType())
                    .entityId(entityId)
                    .trigger(rule.getTrigger())
                    .idempotencyKey(key)
                    .status(AutomationEventStatus.QUEUED)
                    .enqueuedAt(now)
                    .build()).getId());
        } catch (DataIntegrityViolationException e) {
            // Somebody else queued this exact piece of work between the read above and this insert.
            // Theirs will run; ours would be a second task for the same record.
            log.debug("Automation work {} was already queued", key);
            return null;
        }
    }
}
