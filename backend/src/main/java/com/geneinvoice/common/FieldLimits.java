package com.geneinvoice.common;

/**
 * Maximum lengths of the text columns, for request validation. Each mirrors the length on its
 * entity's {@code @Column}, so an overlong value is a 400 naming the field rather than a failed
 * insert.
 */
public final class FieldLimits {

    private FieldLimits() {}

    public static final int USERNAME = 80;
    public static final int EMAIL = 120;
    /** Also caps a customer's name, which becomes the full name of its login. */
    public static final int FULL_NAME = 120;
    public static final int ROLE_NAME = 80;
    public static final int ROLE_DESCRIPTION = 255;
    public static final int PHONE = 30;
    public static final int ADDRESS = 500;
    public static final int PRODUCT_NAME = 150;
    public static final int PRODUCT_DESCRIPTION = 500;
    public static final int INVOICE_NOTES = 500;
    public static final int PAYMENT_NOTES = 300;
    public static final int PAYMENT_METHOD = 40;
    public static final int PROMISE_NOTES = 1000;
    /** Override and cancellation reasons, which also land in the audit trail's reason column. */
    public static final int REASON = 500;
    public static final int DISPUTE_TEXT = 2000;
}
