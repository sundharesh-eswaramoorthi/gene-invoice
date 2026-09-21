package com.geneinvoice.task;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.email.EmailEntityType;

import java.util.Arrays;
import java.util.Locale;

/**
 * The kinds of record a task can hang off (T1), following the shape {@code Document} and
 * {@code Email} already use for the same job. It is deliberately a short list: work is raised
 * against the three things a collections person actually works on, and the next kind is a value
 * here plus a line in {@link #toEmailEntityType()}, nothing else.
 */
public enum TaskEntityType {
    CUSTOMER,
    INVOICE,
    PAYMENT;

    /** What one record is called in a sentence, e.g. "this invoice". */
    public String noun() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Where the record opens in the app, for the link a task carries to its record. */
    public String link(Long id) {
        return "/" + noun() + "s/" + id;
    }

    /**
     * The same kind as the email feature names it. Everything a task needs to know about its
     * record — who may see it, what it is called, which customer it belongs to and who holds each
     * role on it — is already worked out per kind in {@code EmailTargets}, so a task borrows that
     * table rather than growing a second one beside it that would drift (T1, A4). The two enums
     * stay separate because a task can only be raised on these three, while an email can be about
     * a product, a user or a role as well.
     */
    public EmailEntityType toEmailEntityType() {
        return switch (this) {
            case CUSTOMER -> EmailEntityType.CUSTOMER;
            case INVOICE -> EmailEntityType.INVOICE;
            case PAYMENT -> EmailEntityType.PAYMENT;
        };
    }

    /** Reads a type sent as text, in a query parameter or a rule's stored config; an unknown one is a 400. */
    public static TaskEntityType parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (TaskEntityType t : values()) {
            if (t.name().equalsIgnoreCase(wanted)) return t;
        }
        throw new BadRequestException("entityType must be one of " + Arrays.toString(values()));
    }
}
