package com.geneinvoice.automation.event;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.common.Fit;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.history.HistoryTransactions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Publishes what a save did to Customers, Invoices and Payments, for the automation rules (A1, §5.8). A call only
 * buffers the event in the transaction; the rows are inserted just before it commits, so a save that rolls back
 * publishes nothing, and one that commits cannot lose its events. Each row carries the transaction's history id,
 * whose instant is the moment the rules are later evaluated at (D10).
 *
 * <p>Publishing never reads a rule or any other automation table: the save stays as fast as it was, and a broken
 * rule can never fail it. It must be called inside the save's transaction.
 */
@Component
public class AutomationEvents {

    public static final String OUTSTANDING = "OUTSTANDING";

    @PersistenceContext
    private EntityManager em;

    private final AutomationEventRepository repository;
    private final Clock clock;
    private final CurrentUser currentUser;
    private final HistoryTransactions historyTransactions;

    public AutomationEvents(AutomationEventRepository repository, Clock clock, CurrentUser currentUser,
                            HistoryTransactions historyTransactions) {
        this.repository = repository;
        this.clock = clock;
        this.currentUser = currentUser;
        this.historyTransactions = historyTransactions;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void created(TriggerEntity type, Long id) {
        publish(AutomationEvent.CREATED, type, id, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void updated(TriggerEntity type, Long id, String change) {
        publish(AutomationEvent.UPDATED, type, id, change);
    }

    /**
     * An invoice's total, paid amount or status changed: the invoice, and its customer's outstanding balance,
     * which is a column of the customers list (§5.8.3).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void invoiceMoney(Long invoiceId, Long customerId, String change) {
        publish(AutomationEvent.UPDATED, TriggerEntity.INVOICE, invoiceId, change);
        publish(AutomationEvent.UPDATED, TriggerEntity.CUSTOMER, customerId, OUTSTANDING);
    }

    /**
     * Something every invoice and payment of the customer shows changed (its name, its region): one event that the
     * consumer expands, however many records the customer has (M1).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void children(Long customerId, String change) {
        publish(AutomationEvent.CHILDREN, TriggerEntity.CUSTOMER, customerId, change);
    }

    private void publish(String kind, TriggerEntity type, Long id, String change) {
        // A record not saved yet has no id; the path that saves it publishes it (S7).
        if (id == null || id <= 0) return;
        buffer().add(kind, type, id, change,
                () -> PendingEvents.Provenance.of(WriteOrigins.current(currentUser), WriteOrigins.actor(currentUser)));
    }

    private PendingEvents buffer() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            if (s instanceof PendingEvents pending && pending.belongsTo(this)) return pending;
        }
        PendingEvents pending = new PendingEvents(this);
        TransactionSynchronizationManager.registerSynchronization(pending);
        return pending;
    }

    /** Called just before the transaction commits, inside it: the buffered events become outbox rows. */
    void write(PendingEvents pending) {
        // Asks history to record this transaction's instant; nothing is read and nothing is bound here.
        String txId = historyTransactions.txId(em);
        Instant now = clock.instant();
        List<AutomationEvent> rows = new ArrayList<>();
        for (Map.Entry<PendingEvents.Key, PendingEvents.Draft> e : pending.drafts().entrySet()) {
            PendingEvents.Draft draft = e.getValue();
            PendingEvents.Provenance p = draft.provenance();
            rows.add(AutomationEvent.builder()
                    .kind(draft.kind())
                    .entityType(e.getKey().type().name())
                    .entityId(e.getKey().id())
                    .historyTxId(txId)
                    .changes(draft.changes().isEmpty()
                            ? null
                            : Fit.to(String.join(",", draft.changes()), AutomationEvent.CHANGES_LENGTH))
                    .origin(p.origin())
                    .causedByRuleId(p.ruleId())
                    .depth(p.depth())
                    .actorUserId(p.actorUserId())
                    .occurredAt(now)
                    .status(AutomationEvent.PENDING)
                    .attempts(0)
                    .claims(0)
                    .build());
        }
        repository.saveAll(rows);
    }
}
