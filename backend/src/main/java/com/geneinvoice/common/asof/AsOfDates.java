package com.geneinvoice.common.asof;

import com.geneinvoice.common.BadRequestException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turning the ?asOf query parameter into the state a request is answered in, or into a 400.
 *
 * <p>The refusals are all here rather than in the interceptor so the wire contract can be read in
 * one place and asserted without a servlet: what a date means, what a non-date means, and what a
 * date before the history floor means (B3).
 */
public final class AsOfDates {

    /** The property an operator sets to choose what a pre-floor date is answered with. */
    public static final String PRE_FLOOR_PROPERTY = "app.history.pre-floor";

    /** Answer from the seed row and say so on every response: the default (B3). */
    public static final String POLICY_SEEDED = "seeded";

    /**
     * Refuse a pre-floor date outright, for an operator who would rather have no number than a
     * caveated one. Spelled "reject" by B3's design and "none" by the shipped application.yml
     * comment; both are accepted, because a configuration typo that silently means the opposite
     * is worse than a synonym (B3).
     */
    public static final String POLICY_REJECT = "reject";

    static final String NOT_A_DATE = "asOf must be a date in the form yyyy-MM-dd";

    static final String HAS_A_TIME =
            "asOf is a whole day and cannot carry a time of day: two changes on the same day "
                    + "cannot be told apart";

    private static final Pattern ISO_DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private AsOfDates() {
    }

    /**
     * @param raw            the parameter exactly as it arrived
     * @param today          the wall-clock UTC day; a date on or after it is served LIVE
     * @param floorOrNull    the day the mirror started, or null while nothing has installed one
     * @param preFloorPolicy {@link #POLICY_SEEDED} or {@link #POLICY_REJECT}, already normalised
     * @return the state to open, or null to serve live and echo asOf: null
     */
    public static AsOfContext.State parse(String raw, LocalDate today, LocalDate floorOrNull,
                                          String preFloorPolicy) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return null;
        // A time of day is refused rather than truncated, because truncating would answer a
        // question the contract cannot answer: this is one temporal axis at whole-day grain, and
        // silently rounding 14:00 to end-of-day would hand back changes made after it (B3).
        if (value.indexOf('T') >= 0 || value.indexOf(':') >= 0) {
            throw new BadRequestException(HAS_A_TIME);
        }
        if (!ISO_DAY.matcher(value).matches()) {
            throw new BadRequestException(NOT_A_DATE);
        }
        LocalDate date;
        try {
            date = LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(NOT_A_DATE);
        }
        // Today and the future are LIVE, not a reconstruction of today: the live path is the one
        // that is fast, complete and not floored, and a client whose clock is a day ahead of the
        // server's must not drop off it (B3).
        if (!date.isBefore(today)) return null;

        if (floorOrNull != null && date.isBefore(floorOrNull)) {
            if (POLICY_REJECT.equals(preFloorPolicy)) {
                throw new BadRequestException("asOf " + date + " is before the history floor "
                        + floorOrNull + ", and this installation is set to refuse a date before it"
                        + " rather than answer from the seed rows");
            }
            return new AsOfContext.State(date, floorOrNull, AsOfContext.ORIGIN_SEEDED, false,
                    List.of(preFloorNote(date, floorOrNull)), 0);
        }
        return AsOfContext.State.of(date, floorOrNull);
    }

    /**
     * The standing caveat, rendered verbatim in the banner and in the CSV caveat row. It states
     * exactly which half of the answer is trustworthy: existence is exact before the floor,
     * because the seed rows carry each record's OWN creation date, and values are not (B3).
     */
    public static String preFloorNote(LocalDate date, LocalDate floor) {
        return date + " is before the history floor " + floor + ". Which records existed on that "
                + "date is exact, because seed rows carry each record's own creation date. Their "
                + "values are the values as first recorded and are not what was true then. A "
                + "record deleted before " + floor + " is absent entirely.";
    }

    /**
     * Read once at startup and never per request: an unrecognised setting fails the boot naming
     * the variable, the way RegionProperties and InvoiceProperties already do, instead of quietly
     * behaving like the default for a year (B3).
     */
    public static String policy(String configured) {
        String value = configured == null ? "" : configured.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty() || POLICY_SEEDED.equals(value)) return POLICY_SEEDED;
        if (POLICY_REJECT.equals(value) || "none".equals(value)) return POLICY_REJECT;
        throw new IllegalStateException("Set HISTORY_PRE_FLOOR to " + POLICY_SEEDED + " or "
                + POLICY_REJECT + ", not '" + configured + "'");
    }
}
