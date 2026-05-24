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

    public static final List<String> ALL = List.of(
            USER_VIEW, USER_MANAGE,
            ROLE_VIEW, ROLE_MANAGE,
            CUSTOMER_VIEW, CUSTOMER_MANAGE,
            PRODUCT_VIEW, PRODUCT_MANAGE,
            INVOICE_VIEW, INVOICE_MANAGE,
            PAYMENT_VIEW, PAYMENT_MANAGE,
            DISPUTE_CREATE, DISPUTE_VIEW, DISPUTE_MANAGE,
            NOTIFICATION_VIEW,
            AUDIT_VIEW
    );
}
