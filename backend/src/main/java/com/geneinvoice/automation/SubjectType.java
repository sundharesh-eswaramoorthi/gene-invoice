package com.geneinvoice.automation;

import com.geneinvoice.email.EmailEntityType;

import java.util.Locale;

/**
 * The three kinds of record an automation rule can be written about (A1).
 *
 * <p>Deliberately NOT {@link EmailEntityType}, which also carries PRODUCT, USER and ROLE: a rule's
 * subject has to be something with a customer behind it, because that is what gives a rule its
 * branch, its POC book and its placeholders. The mapping to the email enum is one method, so the
 * addressing and rendering layers can be reached without either side widening (A1, A4).
 */
public enum SubjectType {

    CUSTOMER,
    INVOICE,
    PAYMENT;

    /**
     * The audit entity name a rule subject is written down under, or null for an entity no rule
     * can be about.
     *
     * <p>Null for PRODUCT, USER, ROLE, PROMISE, DISPUTE, REGION, PENDING_CHANGE, APPROVAL_THRESHOLD
     * and TASK. TASK is the interesting omission and it is an omission on purpose: a rule whose
     * action is "create a task" writes a task, a task write audits, and a subject type for tasks
     * would be the loop (A1, A6).
     */
    public static SubjectType forAuditName(String entityType) {
        if (entityType == null) return null;
        for (SubjectType t : values()) {
            if (t.name().equals(entityType)) return t;
        }
        return null;
    }

    /** The same record, named the way the email addressing and target layers name it (A3, A4). */
    public EmailEntityType emailType() {
        return switch (this) {
            case CUSTOMER -> EmailEntityType.CUSTOMER;
            case INVOICE -> EmailEntityType.INVOICE;
            case PAYMENT -> EmailEntityType.PAYMENT;
        };
    }

    public String noun() {
        return name().toLowerCase(Locale.ROOT);
    }
}
