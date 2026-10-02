package com.geneinvoice.automation.eval;

import com.geneinvoice.common.BadRequestException;

/** A rule whose condition can no longer be built (a column or value the list no longer takes): its definition is at fault. */
public class RuleDefinitionException extends BadRequestException {
    public RuleDefinitionException(String message) {
        super(message);
    }
}
