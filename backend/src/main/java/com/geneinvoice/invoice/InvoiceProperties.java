package com.geneinvoice.invoice;

import com.geneinvoice.common.BadRequestException;
import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code app.invoice.*}. The default term decides the due date of every invoice whose customer has
 * no terms of their own, and is what the one-time backfill uses (D2), so a misspelled value stops
 * startup naming the variable to set rather than quietly dating invoices wrong.
 */
@Component
@ConfigurationProperties(prefix = "app.invoice")
@Getter
@Setter
public class InvoiceProperties {

    /** One of {@link PaymentTerm#SETTABLE}; {@code INVOICE_DEFAULT_TERM}. */
    private String defaultPaymentTerm = PaymentTerm.SYSTEM_DEFAULT.name();

    /**
     * How far ahead a due date may be before the form asks whether it is right. The API accepts
     * anything — some contracts really are Net 365 — so this only drives the warning (AC-A5).
     */
    private int dueDateHorizonDays = 365;

    /** Resolved once at startup; not a setting of its own. */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private PaymentTerm defaultTerm;

    @PostConstruct
    void resolve() {
        try {
            defaultTerm = PaymentTerm.parse(defaultPaymentTerm);
        } catch (BadRequestException e) {
            throw new IllegalStateException(
                    "Set INVOICE_DEFAULT_TERM to one of " + PaymentTerm.SETTABLE, e);
        }
        if (defaultTerm == PaymentTerm.CUSTOM) {
            throw new IllegalStateException("INVOICE_DEFAULT_TERM cannot be CUSTOM: a system "
                    + "default has to be a number of days. Use one of " + PaymentTerm.SETTABLE);
        }
        if (dueDateHorizonDays < 1) {
            throw new IllegalStateException("INVOICE_DUE_DATE_HORIZON_DAYS must be at least 1");
        }
    }

    /** The system default term, already checked. */
    public PaymentTerm defaultTerm() {
        return defaultTerm;
    }
}
