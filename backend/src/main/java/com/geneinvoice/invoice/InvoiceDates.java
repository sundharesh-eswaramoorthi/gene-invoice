package com.geneinvoice.invoice;

import com.geneinvoice.common.BadRequestException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Which calendar day it is, for due dates and for overdue. The zone is the UTC the rest of the app
 * already decides dates in — the promise sweeper judges a promised date by it, and every date
 * filter reads by it — so an invoice due today is not overdue, and becomes overdue at the start of
 * the next day (AC-A9).
 */
public final class InvoiceDates {

    private InvoiceDates() {}

    public static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /** The calendar day an invoice's timestamp falls on, which is what terms are counted from. */
    public static LocalDate dayOf(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * An invoice date off a query string: a full ISO instant, or a bare {@code yyyy-MM-dd} read as
     * the start of that UTC day, the way every date filter reads one. Blank means "not given".
     */
    public static Instant parse(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) return null;
        try {
            return v.length() == 10
                    ? LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant()
                    : Instant.parse(v);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Invalid invoiceDate: " + raw);
        }
    }
}
