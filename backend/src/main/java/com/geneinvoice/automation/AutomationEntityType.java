package com.geneinvoice.automation;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.Payment;

import java.util.Arrays;
import java.util.Locale;

/**
 * The kinds of record a rule can watch (R1). Deliberately narrower than {@link EmailEntityType}:
 * these three are the ones a user creates and edits by hand and whose list pages they already
 * filter, and the WHERE half of a rule is exactly those list filters (R9). Adding a fourth is a
 * value here plus its {@link TableSchema} — nothing else in this package knows the difference.
 */
public enum AutomationEntityType {
    CUSTOMER,
    INVOICE,
    PAYMENT;

    /** What one record is called in a sentence, e.g. "this invoice". */
    public String noun() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The same kind as the rest of the app calls it. Tasks, assignees and email all key off
     * {@link EmailEntityType}, so a rule hands them that rather than teaching them a second enum.
     */
    public EmailEntityType toEmailEntityType() {
        return switch (this) {
            case CUSTOMER -> EmailEntityType.CUSTOMER;
            case INVOICE -> EmailEntityType.INVOICE;
            case PAYMENT -> EmailEntityType.PAYMENT;
        };
    }

    /**
     * The columns the rule's WHERE may filter by — the very table the list page is built from, so a
     * rule can say nothing the user could not have typed into the filter bar, and says it in the
     * same wire form (R9).
     */
    public TableSchema tableSchema() {
        return switch (this) {
            case CUSTOMER -> TableSchemas.CUSTOMERS;
            case INVOICE -> TableSchemas.INVOICES;
            case PAYMENT -> TableSchemas.PAYMENTS;
        };
    }

    /** The entity the WHERE is counted against; see {@link AutomationMatcher}. */
    public Class<?> entityClass() {
        return switch (this) {
            case CUSTOMER -> Customer.class;
            case INVOICE -> Invoice.class;
            case PAYMENT -> Payment.class;
        };
    }

    /** Reads a type sent as text, in a rule's body or a query parameter; an unknown one is a 400. */
    public static AutomationEntityType parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (AutomationEntityType t : values()) {
            if (t.name().equalsIgnoreCase(wanted)) return t;
        }
        throw new BadRequestException("entityType must be one of " + Arrays.toString(values()));
    }
}
