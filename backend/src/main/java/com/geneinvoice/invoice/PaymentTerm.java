package com.geneinvoice.invoice;

import com.geneinvoice.common.BadRequestException;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

public enum PaymentTerm {
    DUE_ON_RECEIPT(0, "Due on receipt"),
    NET_15(15, "Net 15"),
    NET_30(30, "Net 30"),
    NET_45(45, "Net 45"),
    NET_60(60, "Net 60"),
    NET_90(90, "Net 90"),
    CUSTOM(null, "Custom");

    public static final List<PaymentTerm> SETTABLE =
            Arrays.stream(values()).filter(t -> t != CUSTOM).toList();

    public static final PaymentTerm SYSTEM_DEFAULT = NET_30;

    private final Integer days;
    private final String label;

    PaymentTerm(Integer days, String label) {
        this.days = days;
        this.label = label;
    }

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

    public static PaymentTerm parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (PaymentTerm t : values()) {
            if (t.name().equalsIgnoreCase(wanted)) return t;
        }
        throw new BadRequestException("paymentTerm must be one of " + Arrays.toString(values()));
    }
}
