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

    public static final String POC_VIEW = "POC_VIEW";
    public static final String POC_ASSIGN = "POC_ASSIGN";
    public static final String POC_ASSIGNABLE_SALES = "POC_ASSIGNABLE_SALES";
    public static final String POC_ASSIGNABLE_SUCCESS = "POC_ASSIGNABLE_SUCCESS";
    public static final String POC_ASSIGNABLE_COLLECTION = "POC_ASSIGNABLE_COLLECTION";

    public static final String SCOPE_OVERRIDE = "SCOPE_OVERRIDE";

    public static final String PROMISE_VIEW = "PROMISE_VIEW";
    public static final String PROMISE_MANAGE = "PROMISE_MANAGE";
    public static final String PROMISE_OVERRIDE = "PROMISE_OVERRIDE";

    public static final String EMAIL_VIEW = "EMAIL_VIEW";
    public static final String EMAIL_SEND = "EMAIL_SEND";

    public static final String DOCUMENT_VIEW = "DOCUMENT_VIEW";
    public static final String DOCUMENT_MANAGE = "DOCUMENT_MANAGE";

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
            EMAIL_VIEW, EMAIL_SEND,
            DOCUMENT_VIEW, DOCUMENT_MANAGE,
            EXPORT_DATA
    );
}
