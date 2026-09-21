package com.geneinvoice.email;

/**
 * Where an offered document was found: on the record the email is about, or on that record's
 * customer (E17). It is only ever written out — the compose form sends back document ids, never a
 * source — so unlike the enums a request carries it has no {@code parse}.
 */
public enum AttachmentSource {
    RECORD,
    CUSTOMER;

    /** The heading the compose form groups the documents under: "This invoice", "Customer". */
    public String label(EmailEntityType type) {
        return this == CUSTOMER ? "Customer" : "This " + type.noun();
    }
}
