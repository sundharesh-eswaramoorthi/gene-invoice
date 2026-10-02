package com.geneinvoice.common.scope;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class AsOfException extends CodedBadRequestException {

    public static final String INVALID = "AS_OF_INVALID";
    public static final String FUTURE = "AS_OF_FUTURE";
    public static final String BEFORE_HISTORY = "AS_OF_BEFORE_HISTORY";
    public static final String READ_ONLY = "AS_OF_READ_ONLY";
    public static final String UNSUPPORTED = "AS_OF_UNSUPPORTED";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private AsOfException(String code, String message) {
        super(code, message);
    }

    public static AsOfException invalid() {
        return new AsOfException(INVALID, "asOf must be a date (yyyy-MM-dd)");
    }

    public static AsOfException future() {
        return new AsOfException(FUTURE, "asOf cannot be after today");
    }

    public static AsOfException beforeHistory(LocalDate start) {
        return new AsOfException(BEFORE_HISTORY, "History is available from " + start.format(DAY));
    }

    public static AsOfException readOnly(LocalDate d) {
        return new AsOfException(READ_ONLY,
                "You are viewing " + d.format(DAY) + ". Changes can only be made in the current view.");
    }

    public static AsOfException unsupported() {
        return new AsOfException(UNSUPPORTED, "This page can't be shown as of a date");
    }
}
