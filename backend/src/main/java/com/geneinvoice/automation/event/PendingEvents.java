package com.geneinvoice.automation.event;

import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.common.origin.WriteOrigin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The events one transaction has published so far, written to the outbox just before it commits and dropped when
 * it does not. One per transaction: a {@code REQUIRES_NEW} transaction suspends its caller's synchronizations, so
 * each bulk row gets a buffer of its own.
 *
 * <p>Deduplicated per record: CREATED absorbs UPDATED in either order and the change labels are unioned, so a
 * record created and then changed in one transaction is one CREATED event. CHILDREN events are kept apart, one per
 * customer.
 */
final class PendingEvents implements TransactionSynchronization {

    private static final Logger log = LoggerFactory.getLogger(PendingEvents.class);

    /** One record's event in this transaction; {@code children} keeps a customer's CHILDREN apart from its own. */
    record Key(boolean children, TriggerEntity type, Long id) {}

    /**
     * Who the write is attributed to, as it was when the record's first event was published: a run-as that ends
     * before the transaction commits has restored the caller by the time the rows are written (D4).
     */
    record Provenance(String origin, Long ruleId, int depth, Long actorUserId) {

        static Provenance of(WriteOrigin origin, Long actorUserId) {
            Long ruleId = origin.kind() == WriteOrigin.Kind.AUTOMATION ? origin.ruleId() : null;
            return new Provenance(origin.kind().name(), ruleId, origin.depth(), actorUserId);
        }
    }

    static final class Draft {
        private String kind;
        private final Set<String> changes = new LinkedHashSet<>();
        private final Provenance provenance;

        private Draft(String kind, Provenance provenance) {
            this.kind = kind;
            this.provenance = provenance;
        }

        String kind() {
            return kind;
        }

        Set<String> changes() {
            return Collections.unmodifiableSet(changes);
        }

        Provenance provenance() {
            return provenance;
        }
    }

    private final AutomationEvents events;
    private final Map<Key, Draft> drafts = new LinkedHashMap<>();

    PendingEvents(AutomationEvents events) {
        this.events = events;
    }

    boolean belongsTo(AutomationEvents owner) {
        return events == owner;
    }

    void add(String kind, TriggerEntity type, Long id, String change, Supplier<Provenance> provenance) {
        Key key = new Key(AutomationEvent.CHILDREN.equals(kind), type, id);
        Draft draft = drafts.computeIfAbsent(key, k -> new Draft(kind, provenance.get()));
        if (AutomationEvent.CREATED.equals(kind)) draft.kind = AutomationEvent.CREATED;
        if (change != null && !change.isBlank()) draft.changes.add(change);
    }

    Map<Key, Draft> drafts() {
        return Collections.unmodifiableMap(drafts);
    }

    @Override
    public void beforeCommit(boolean readOnly) {
        if (drafts.isEmpty()) return;
        if (readOnly) {
            log.warn("automation.events.dropped {} event(s) published in a read-only transaction; dropped",
                    drafts.size());
            drafts.clear();
            return;
        }
        events.write(this);
    }

    @Override
    public void afterCompletion(int status) {
        drafts.clear();
    }
}
