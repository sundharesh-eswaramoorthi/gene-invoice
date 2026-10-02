package com.geneinvoice.automation.rule;

/**
 * What sets a rule off (A1). {@code MANUAL} never fires by itself: the rule runs only from "Run now" (PC-6). Stored
 * by name and widened by {@code AutomationSchemaUpgrade}: new constants are appended last (D12).
 */
public enum TriggerKind {
    ENTITY_CREATED, ENTITY_UPDATED, ENTITY_CREATED_OR_UPDATED, SCHEDULE_DAILY, SCHEDULE_WEEKLY, MANUAL;

    public boolean isSchedule() {
        return this == SCHEDULE_DAILY || this == SCHEDULE_WEEKLY;
    }

    /** Fired by a change to a record, where "becoming overdue is not an update" matters (§5.8.3). */
    public boolean firesOnUpdate() {
        return this == ENTITY_UPDATED || this == ENTITY_CREATED_OR_UPDATED;
    }
}
