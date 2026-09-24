package com.geneinvoice.common.asof;

import java.util.List;

/**
 * What a response says about the date it was answered as of, carried by every page envelope and
 * later by every dashboard figure. Null on a live read, and never a bare date: a caller who is
 * shown a number from the past has to be able to tell an exact reconstruction from a seeded
 * approximation without reading the release notes (B3).
 *
 * @param date           the UTC day asked for, yyyy-MM-dd
 * @param floor          the day the mirror started, or null while nothing has installed one
 * @param exact          false when any value in the answer is the floor value rather than the
 *                       value of the day
 * @param origin         RECONSTRUCTED from interval rows, or SEEDED from the install-time seed
 * @param appliesTo      always "records": rights, privileges and identity are today's, never
 *                       then's, and the wire says so rather than leaving it to be assumed
 * @param omittedDeleted records deleted before the floor, which have no mirror row and can
 *                       therefore never be shown
 * @param notes          one sentence per caveat, rendered verbatim in the banner and the CSV
 */
public record AsOfInfo(
        String date,
        String floor,
        boolean exact,
        String origin,
        String appliesTo,
        int omittedDeleted,
        List<String> notes
) {
}
