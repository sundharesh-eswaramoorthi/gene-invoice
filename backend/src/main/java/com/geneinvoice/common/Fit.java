package com.geneinvoice.common;

/** Fits text into a bounded column, marking a cut with an ellipsis. */
public final class Fit {

    private Fit() {}

    public static String to(String text, int max) {
        if (text == null || text.length() <= max) return text;
        if (max <= 0) return "";
        if (max == 1) return "…";
        int end = max - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…";
    }
}
