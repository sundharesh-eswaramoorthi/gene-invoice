package com.geneinvoice.automation.run;

import com.geneinvoice.automation.rule.SuspendReason;

/** The rule's owner cannot act now (§5.10.7): the rule is suspended for {@link #reason()}, and the run skipped. */
public class OwnerUnavailableException extends RuntimeException {

    private final SuspendReason reason;

    public OwnerUnavailableException(SuspendReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SuspendReason reason() {
        return reason;
    }
}
