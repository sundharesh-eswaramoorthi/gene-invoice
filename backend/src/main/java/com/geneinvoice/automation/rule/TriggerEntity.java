package com.geneinvoice.automation.rule;

import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;

/**
 * The kinds of record a rule can be triggered by, and that saves publish events for (A1). Stored by name: new
 * constants are appended last (D12). What each one is elsewhere is a switch rather than a field, so that nothing
 * here is read while those enums are still being built.
 */
public enum TriggerEntity {
    CUSTOMER, INVOICE, PAYMENT;

    /** The kind of record in the caller's scope: which rows a rule sees is the list's own answer (§5.10.3). */
    public ScopeResolver.Kind kind() {
        return switch (this) {
            case CUSTOMER -> ScopeResolver.Kind.CUSTOMER;
            case INVOICE -> ScopeResolver.Kind.INVOICE;
            case PAYMENT -> ScopeResolver.Kind.PAYMENT;
        };
    }

    public Class<?> type() {
        return kind().type();
    }

    /** The list page's full staff schema: a condition is written in its filters. */
    public TableSchema schema() {
        return kind().schema();
    }

    public EmailEntityType emailType() {
        return switch (this) {
            case CUSTOMER -> EmailEntityType.CUSTOMER;
            case INVOICE -> EmailEntityType.INVOICE;
            case PAYMENT -> EmailEntityType.PAYMENT;
        };
    }

    public String viewPrivilege() {
        return switch (this) {
            case CUSTOMER -> Privileges.CUSTOMER_VIEW;
            case INVOICE -> Privileges.INVOICE_VIEW;
            case PAYMENT -> Privileges.PAYMENT_VIEW;
        };
    }

    /** "invoice", as in "invoice rules". */
    public String noun() {
        return name().toLowerCase();
    }

    /** The list page, as the client routes it. */
    public String listPath() {
        return "/" + noun() + "s";
    }
}
