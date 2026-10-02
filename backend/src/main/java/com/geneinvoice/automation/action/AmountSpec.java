package com.geneinvoice.automation.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/** A promise's amount: a fixed sum, or a figure of the record read when the action acts. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AmountSpec(Source source, BigDecimal value, String field) {

    public enum Source { FIXED, FIELD }

    public static final List<String> FIELDS = List.of(
            "Customer.Outstanding", "Customer.OverdueAmount", "Invoice.Balance", "Invoice.Total", "Payment.Amount");
}
