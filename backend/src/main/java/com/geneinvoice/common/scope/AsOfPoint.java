package com.geneinvoice.common.scope;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** A moment being viewed: the day it falls on (UTC) and the instant itself, at microsecond precision. */
public record AsOfPoint(LocalDate date, Instant instant) {

    public AsOfPoint {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(instant, "instant");
    }

    /** The last microsecond of day {@code d} (UTC): what the wire date {@code asOf=d} means. */
    public static AsOfPoint endOfDay(LocalDate d) {
        return new AsOfPoint(d, d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minus(1, ChronoUnit.MICROS));
    }

    public static AsOfPoint at(Instant i) {
        Instant t = i.truncatedTo(ChronoUnit.MICROS);
        return new AsOfPoint(LocalDate.ofInstant(t, ZoneOffset.UTC), t);
    }
}
