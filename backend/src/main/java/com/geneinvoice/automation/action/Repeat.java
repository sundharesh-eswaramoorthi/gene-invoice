package com.geneinvoice.automation.action;

/**
 * How often an action acts on one record: once for as long as the rule exists, or once per event (every change,
 * every slot, every run by hand; events are never merged, D10).
 */
public enum Repeat {
    ONCE_PER_RECORD, EVERY_TIME
}
