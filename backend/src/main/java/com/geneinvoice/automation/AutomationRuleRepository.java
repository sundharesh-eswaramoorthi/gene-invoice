package com.geneinvoice.automation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AutomationRuleRepository extends JpaRepository<AutomationRule, Long> {

    /**
     * The rules a fan-out row has to consider: enabled, watching this kind of record, listening for
     * this trigger. Read on the consumer thread and never on the save, which is the whole point of
     * the outbox (R2).
     */
    List<AutomationRule> findByEnabledTrueAndEntityTypeAndTriggerOrderByIdAsc(
            AutomationEntityType entityType, TriggerKind trigger);

    /** Every rule a daily or weekly run has to walk, in the order they were written. */
    List<AutomationRule> findByEnabledTrueAndTriggerOrderByIdAsc(TriggerKind trigger);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
