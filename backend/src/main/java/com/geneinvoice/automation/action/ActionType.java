package com.geneinvoice.automation.action;

import com.geneinvoice.privilege.Privileges;

/** What an action does (A3), and the privilege its owner needs in the record's region to do it. */
public enum ActionType {
    CREATE_TASK(Privileges.TASK_MANAGE),
    CREATE_PROMISE(Privileges.PROMISE_MANAGE),
    CREATE_DISPUTE(Privileges.DISPUTE_MANAGE),
    SEND_EMAIL(Privileges.EMAIL_SEND);

    private final String privilege;

    ActionType(String privilege) {
        this.privilege = privilege;
    }

    public String privilege() {
        return privilege;
    }
}
