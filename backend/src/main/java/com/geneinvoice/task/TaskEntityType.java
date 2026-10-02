package com.geneinvoice.task;

import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;

/** The records a task can be on (A6). New constants are appended last (D12). */
public enum TaskEntityType {
    CUSTOMER(EmailEntityType.CUSTOMER, Privileges.CUSTOMER_VIEW),
    INVOICE(EmailEntityType.INVOICE, Privileges.INVOICE_VIEW),
    PAYMENT(EmailEntityType.PAYMENT, Privileges.PAYMENT_VIEW);

    private final EmailEntityType emailType;
    private final String viewPrivilege;

    TaskEntityType(EmailEntityType emailType, String viewPrivilege) {
        this.emailType = emailType;
        this.viewPrivilege = viewPrivilege;
    }

    /** The same record as an email target: its roles, holders and label come from there. */
    public EmailEntityType emailType() {
        return emailType;
    }

    /**
     * The record's kind of scope. A switch rather than a field: {@code ScopeResolver.Kind} names the task schema,
     * which lists these constants, so a field would read the kinds while they are still being built.
     */
    public ScopeResolver.Kind kind() {
        return switch (this) {
            case CUSTOMER -> ScopeResolver.Kind.CUSTOMER;
            case INVOICE -> ScopeResolver.Kind.INVOICE;
            case PAYMENT -> ScopeResolver.Kind.PAYMENT;
        };
    }

    public String viewPrivilege() {
        return viewPrivilege;
    }
}
