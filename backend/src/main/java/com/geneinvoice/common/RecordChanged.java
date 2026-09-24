package com.geneinvoice.common;

/**
 * Told, inside the saving transaction, that a record changed (A1).
 *
 * <p>It lives in {@code common} and names no automation type on purpose. {@code AuditService} is
 * the one chokepoint every subject mutation in this application already passes through, and it
 * must be able to tell somebody that a record changed without audit acquiring a dependency on the
 * automation engine's bean graph — which is the direction Spring Boot 3 refuses as a circular
 * reference (A1).
 *
 * <p>INSIDE the transaction is the whole contract: an implementation may buffer, and may write
 * rows in {@code beforeCommit}, but it may not act. A save that rolls back must leave nothing
 * behind, and a rule must never be the reason a user's save was slow or failed.
 */
public interface RecordChanged {

    /**
     * @param entityType the audit entity name, e.g. {@code "INVOICE"} — not every one of them is
     *                   a subject an automation rule can be written about
     * @param entityId   the record's id, as the audit row carries it
     * @param auditAction the audit action string, e.g. {@code "INVOICE_CREATED"}
     */
    void changed(String entityType, Long entityId, String auditAction);
}
