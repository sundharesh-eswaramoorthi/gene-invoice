package com.geneinvoice.common.asof;

import com.geneinvoice.common.bulk.Csv;

import java.util.List;

/**
 * WHAT A DOWNLOADED FILE SAYS ABOUT THE DATE IT WAS TAKEN AS OF (B3).
 *
 * <p>A CSV outlives the banner that framed it. It is opened next week, mailed on, pasted into a
 * board pack — and by then nothing on the page is left to say that the balances in it are January's
 * balances. So the caveat travels INSIDE the file: one leading cell, ahead of the header row,
 * written through the same {@link Csv#of} every other row goes through so a comma in the sentence
 * is quoted by the same rule.
 *
 * <p>Empty string on a live export, which is what makes it safe to concatenate unconditionally:
 * a caller cannot forget it, and a live file is byte-identical to the one this application has
 * always produced.
 *
 * <p>Six exports need exactly this — invoices here, then customers, payments, promises, disputes
 * and tasks at B3-SLICE-REST — which is why the two lines live here and not as a private pair in
 * the first controller that needed them.
 */
public final class AsOfCsv {

    private AsOfCsv() {
    }

    /**
     * The leading one-cell row, or "" when this export is live. It carries the date, the origin
     * when the answer came from the install-time seed rather than from interval rows, and every
     * note the response would have shown in the banner — the drift warning included, which is the
     * one caveat a reader of a file has no other way of learning.
     */
    public static String caveat() {
        AsOfInfo info = AsOfContext.info();
        if (info == null) return "";
        StringBuilder sentence = new StringBuilder("As of ").append(info.date())
                .append(" — this file shows history, not today.");
        if (!info.exact()) {
            sentence.append(" Some values are not exact.");
        }
        for (String note : info.notes()) {
            sentence.append(' ').append(note);
        }
        return Csv.of(List.of(sentence.toString()), List.of());
    }

    /**
     * {@code invoices.csv} live, {@code invoices-as-of-2026-01-31.csv} as of a date — so two
     * downloads of the same list do not land in the same folder under the same name and quietly
     * replace each other (B3).
     */
    public static String filename(String base) {
        java.time.LocalDate date = AsOfContext.date();
        return date == null ? base + ".csv" : base + "-as-of-" + date + ".csv";
    }
}
