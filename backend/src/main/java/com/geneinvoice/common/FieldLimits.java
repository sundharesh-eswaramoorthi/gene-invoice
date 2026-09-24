package com.geneinvoice.common;

public final class FieldLimits {

    private FieldLimits() {}

    public static final int USERNAME = 80;
    public static final int EMAIL = 120;
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
    public static final int REASON = 500;
    public static final int DISPUTE_TEXT = 2000;
    public static final int EMAIL_SUBJECT = 500;
    public static final int EMAIL_BODY = 20000;
    public static final int DOCUMENT_FILENAME = 260;
    public static final int DOCUMENT_DESCRIPTION = 500;
    public static final int GMAIL_CLIENT_ID = 300;
    public static final int GMAIL_CLIENT_SECRET = 300;
    public static final int GMAIL_REFRESH_TOKEN = 2000;

    // A task title is a line somebody reads in a list, and the notes are the paragraph
    // behind it; both are varchar and never @Lob, which on Postgres would store an OID
    // instead of readable text (A6).
    public static final int TASK_TITLE = 200;
    public static final int TASK_NOTES = 2000;

    // A rule's own name and the sentence under it. Both are plain varchar; the TEMPLATE fields a
    // rule carries (a task title, an email subject, an email body) are bounded at save time by
    // TASK_TITLE, TASK_NOTES, EMAIL_SUBJECT and EMAIL_BODY above, because a template and the text
    // it renders to land in the same columns and must not have two different ceilings (A1, A3).
    public static final int RULE_NAME = 200;
    public static final int RULE_DESCRIPTION = 1000;
}
