package com.geneinvoice.common.time;

import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;

import java.time.LocalDate;
import java.time.ZoneOffset;

public final class Today {

    private Today() {}

    /** The day being viewed: the as-of date when one is bound, otherwise today (UTC). */
    public static LocalDate date() {
        AsOfPoint p = AsOf.current();
        return p != null ? p.date() : liveDate();
    }

    /** Always the real business day, never as-of; for writes and schedulers. */
    public static LocalDate liveDate() {
        return LocalDate.ofInstant(AppClock.now(), ZoneOffset.UTC);
    }
}
