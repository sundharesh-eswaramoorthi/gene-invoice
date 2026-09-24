package com.geneinvoice.invoice;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.asof.AsOfContext;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

public final class InvoiceDates {

    private InvoiceDates() {}

    /**
     * The date a READ is answered as of. B3 makes this method as-of aware, so a request carrying
     * ?asOf answers every read as of that date instead of today. A write must never ask what date
     * a reader asked for: use {@link #todayForWrite()} (B3).
     *
     * <p>B3-CONTEXT made it so, and this three-line body is the ENTIRE read-side clock seam. Seven
     * sites become as-of correct with no edit of their own because they already came through here:
     * TableSchemas.overduePredicate, DashboardController.today(), InvoiceService.tiles,
     * InvoiceController.export, CustomerService's ageing, and both InvoiceDtos factories. Nothing
     * is injected and no Clock bean exists, deliberately — 44 of the 47 clock reads in this
     * backend are write-side and 13 of them are inside a @PrePersist where no bean can reach
     * (B3).
     */
    public static LocalDate today() {
        LocalDate asOf = AsOfContext.date();
        return asOf != null ? asOf : LocalDate.now(ZoneOffset.UTC);
    }

    /**
     * The date a WRITE is stamped with: always the wall clock. An exact alias of {@link #today()}
     * today, and deliberately still the wall clock after B3 makes today() as-of aware — a row
     * written while serving a request that asked for the past must still be dated now, or the past
     * starts changing underneath the reader who asked for it (B3).
     */
    public static LocalDate todayForWrite() {
        return LocalDate.now(ZoneOffset.UTC);
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
