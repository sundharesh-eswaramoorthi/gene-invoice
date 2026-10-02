package com.geneinvoice.approval;

import com.geneinvoice.common.BadRequestException;

/**
 * Another change is already waiting on the record, or the change was already decided: 409 with this message
 * (D15). A bad request underneath, so a bulk run reports the row as skipped.
 */
public class ApprovalConflictException extends BadRequestException {
    public ApprovalConflictException(String message) {
        super(message);
    }
}
