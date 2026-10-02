package com.geneinvoice.invoice;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.time.Today;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

public final class InvoiceDates {

    private InvoiceDates() {}

    public static LocalDate today() {
        return Today.date();
    }

    public static LocalDate dayOf(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC);
    }

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
