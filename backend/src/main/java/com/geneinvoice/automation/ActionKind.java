package com.geneinvoice.automation;

/**
 * The four things a rule can do. The vocabulary is CLOSED on purpose: every constant here is a
 * write somebody already had a screen for, performed by the service that already owns it, so the
 * engine adds no fifth way of creating a task, a promise, a dispute or an email (A3).
 */
public enum ActionKind {

    CREATE_TASK,
    CREATE_PROMISE,
    CREATE_DISPUTE,
    SEND_EMAIL
}
