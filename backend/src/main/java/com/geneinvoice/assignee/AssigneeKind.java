package com.geneinvoice.assignee;

/**
 * Whether an assignee names a person or a seat (A2). The distinction is the whole point of the
 * model: a person is answerable until somebody changes the row, a role is answerable for as long
 * as they hold it, so who a role reaches is read when it is asked for and never stored.
 */
public enum AssigneeKind {
    /** One named internal user. */
    USER,
    /** A role at a level: everyone holding that seat, now. */
    ROLE
}
