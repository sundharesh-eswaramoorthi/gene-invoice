package com.geneinvoice.invoice;

import com.geneinvoice.common.BadRequestException;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * How long a customer has to pay. Terms are counted in calendar days, with no weekend or holiday
 * rolling, so "Net 30" on the 1st is always due on the 31st.
 *
 * <p>{@link #CUSTOM} is the odd one out: it carries no number of days because it means "somebody
 * typed the date themselves". It belongs on an invoice, never on a customer.
 */
public enum PaymentTerm {
    DUE_ON_RECEIPT(0, "Due on receipt"),
    NET_15(15, "Net 15"),
    NET_30(30, "Net 30"),
    NET_45(45, "Net 45"),
    NET_60(60, "Net 60"),
    NET_90(90, "Net 90"),
    CUSTOM(null, "Custom");

    /** The terms a customer, or the system default, may be set to — everything but CUSTOM. */
    public static final List<PaymentTerm> SETTABLE =
            Arrays.stream(values()).filter(t -> t != CUSTOM).toList();

    /**
     * What a due date falls back to when nothing else says otherwise. Deployments override it with
     * {@code app.invoice.default-payment-term}; this is that setting's own default.
     */
    public static final PaymentTerm SYSTEM_DEFAULT = NET_30;

    private final Integer days;
    private final String label;

    PaymentTerm(Integer days, String label) {
        this.days = days;
        this.label = label;
    }

    /** Null only for {@link #CUSTOM}. */
    public Integer days() {
        return days;
    }

    public String label() {
        return label;
    }

    public LocalDate due(LocalDate invoiceDate) {
        if (days == null) {
            throw new IllegalStateException("Custom terms have no due date of their own");
        }
        return invoiceDate.plusDays(days);
    }

    /** Reads a term sent as text, e.g. a configured default; an unknown one is a 400. */
    public static PaymentTerm parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (PaymentTerm t : values()) {
            if (t.name().equalsIgnoreCase(wanted)) return t;
        }
        throw new BadRequestException("paymentTerm must be one of " + Arrays.toString(values()));
    }
}
