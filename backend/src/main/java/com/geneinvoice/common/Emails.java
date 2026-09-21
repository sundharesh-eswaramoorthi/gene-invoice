package com.geneinvoice.common;

public final class Emails {

    private Emails() {}

    public static String normalize(String raw) {
        return Strings.blankToNull(raw);
    }
}
