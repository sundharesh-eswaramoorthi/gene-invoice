package com.geneinvoice.invoice;

import com.geneinvoice.common.BadRequestException;
import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.invoice")
@Getter
@Setter
public class InvoiceProperties {

    private String defaultPaymentTerm = PaymentTerm.SYSTEM_DEFAULT.name();

    private int dueDateHorizonDays = 365;

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

    public PaymentTerm defaultTerm() {
        return defaultTerm;
    }
}
