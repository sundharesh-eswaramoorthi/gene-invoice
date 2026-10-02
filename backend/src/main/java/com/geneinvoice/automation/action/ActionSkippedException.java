package com.geneinvoice.automation.action;

/** There is nothing for the action to act on, and nothing to retry: the record no longer exists, say. */
public class ActionSkippedException extends RuntimeException {
    public ActionSkippedException(String message) {
        super(message);
    }
}
