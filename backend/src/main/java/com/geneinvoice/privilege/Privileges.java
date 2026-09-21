package com.geneinvoice.privilege;

import java.util.List;

public final class Privileges {
    private Privileges() {}

    public static final String USER_VIEW = "USER_VIEW";
    public static final String USER_MANAGE = "USER_MANAGE";

    public static final String ROLE_VIEW = "ROLE_VIEW";
    public static final String ROLE_MANAGE = "ROLE_MANAGE";

    public static final String CUSTOMER_VIEW = "CUSTOMER_VIEW";
    public static final String CUSTOMER_MANAGE = "CUSTOMER_MANAGE";

    public static final String PRODUCT_VIEW = "PRODUCT_VIEW";
    public static final String PRODUCT_MANAGE = "PRODUCT_MANAGE";

    public static final String INVOICE_VIEW = "INVOICE_VIEW";
    public static final String INVOICE_MANAGE = "INVOICE_MANAGE";

    public static final String PAYMENT_VIEW = "PAYMENT_VIEW";
    public static final String PAYMENT_MANAGE = "PAYMENT_MANAGE";

    public static final String DISPUTE_CREATE = "DISPUTE_CREATE";
    public static final String DISPUTE_VIEW = "DISPUTE_VIEW";
    public static final String DISPUTE_MANAGE = "DISPUTE_MANAGE";

    public static final String NOTIFICATION_VIEW = "NOTIFICATION_VIEW";

    public static final String AUDIT_VIEW = "AUDIT_VIEW";

    // ---- Point-of-contact (POC) ------------------------------------------------
    /** May see POC identity fields, columns, filters and dropdowns. */
    public static final String POC_VIEW = "POC_VIEW";
    /** May change POC assignments on invoices, payments and customers. */
    public static final String POC_ASSIGN = "POC_ASSIGN";
    /**
     * Assignability markers. A user is offered in a POC dropdown when their role carries the
     * matching marker — this keeps the existing "one role per user" model intact while letting an
     * admin compose a role that is assignable as several POC kinds at once.
     */
    public static final String POC_ASSIGNABLE_SALES = "POC_ASSIGNABLE_SALES";
    public static final String POC_ASSIGNABLE_SUCCESS = "POC_ASSIGNABLE_SUCCESS";
    public static final String POC_ASSIGNABLE_COLLECTION = "POC_ASSIGNABLE_COLLECTION";

    /**
     * May clear the "my records only" default scope on list pages. Without it the default POC
     * filter is enforced server-side and rendered as a locked chip.
     */
    public static final String SCOPE_OVERRIDE = "SCOPE_OVERRIDE";

    // ---- Payment promises ------------------------------------------------------
    public static final String PROMISE_VIEW = "PROMISE_VIEW";
    public static final String PROMISE_MANAGE = "PROMISE_MANAGE";
    public static final String PROMISE_OVERRIDE = "PROMISE_OVERRIDE";

    // ---- Tasks -----------------------------------------------------------------
    /** May read the Tasks tab of records they can see, and their own tasks. */
    public static final String TASK_VIEW = "TASK_VIEW";
    /** May raise a task, retitle it, move its due date, reassign it and close it. */
    public static final String TASK_MANAGE = "TASK_MANAGE";

    // ---- Automation ------------------------------------------------------------
    /** May read the automation rules and what each one has done. */
    public static final String AUTOMATION_VIEW = "AUTOMATION_VIEW";
    /**
     * May write a rule, and so cause tasks, promises, disputes and email to be made without a
     * person in the loop. Held apart from {@link #AUTOMATION_VIEW} because a rule acts for everyone.
     */
    public static final String AUTOMATION_MANAGE = "AUTOMATION_MANAGE";

    // ---- Email -----------------------------------------------------------------
    /** May read the Email tab of records they can see, and their own Inbox. */
    public static final String EMAIL_VIEW = "EMAIL_VIEW";
    /** May compose and send email about records they can see, one at a time or in bulk. */
    public static final String EMAIL_SEND = "EMAIL_SEND";

    // ---- Documents -------------------------------------------------------------
    /** May see and download the documents on records they can see. */
    public static final String DOCUMENT_VIEW = "DOCUMENT_VIEW";
    /** May attach a document to a record they can change, and edit or delete one. */
    public static final String DOCUMENT_MANAGE = "DOCUMENT_MANAGE";

    // ---- Tables ----------------------------------------------------------------
    /** May export the current selection / filtered set as CSV. */
    public static final String EXPORT_DATA = "EXPORT_DATA";

    public static final List<String> ALL = List.of(
            USER_VIEW, USER_MANAGE,
            ROLE_VIEW, ROLE_MANAGE,
            CUSTOMER_VIEW, CUSTOMER_MANAGE,
            PRODUCT_VIEW, PRODUCT_MANAGE,
            INVOICE_VIEW, INVOICE_MANAGE,
            PAYMENT_VIEW, PAYMENT_MANAGE,
            DISPUTE_CREATE, DISPUTE_VIEW, DISPUTE_MANAGE,
            NOTIFICATION_VIEW,
            AUDIT_VIEW,
            POC_VIEW, POC_ASSIGN,
            POC_ASSIGNABLE_SALES, POC_ASSIGNABLE_SUCCESS, POC_ASSIGNABLE_COLLECTION,
            SCOPE_OVERRIDE,
            PROMISE_VIEW, PROMISE_MANAGE, PROMISE_OVERRIDE,
            TASK_VIEW, TASK_MANAGE,
            AUTOMATION_VIEW, AUTOMATION_MANAGE,
            EMAIL_VIEW, EMAIL_SEND,
            DOCUMENT_VIEW, DOCUMENT_MANAGE,
            EXPORT_DATA
    );
}
