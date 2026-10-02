package com.geneinvoice.automation.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A date worked out when the action acts: today in the rule's zone, or the invoice's due date, plus some days. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DueSpec(Base base, Integer offsetDays) {

    public enum Base { TODAY, INVOICE_DUE_DATE }

    public int offset() {
        return offsetDays == null ? 0 : offsetDays;
    }
}
