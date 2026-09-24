package com.geneinvoice.history;

import com.geneinvoice.common.asof.AsOf;

import java.time.Instant;

/**
 * What every interval-versioned mirror row is, and the ONLY shape {@code AsOf.at(T)} needs to
 * resolve against: {@code validFrom <= T < validTo} over a row that is not a tombstone (B3).
 *
 * <p>THE THREE NAMES ARE A CONTRACT, NOT A CONVENIENCE. {@code AsOf.at(Instant)} names
 * {@code validFrom}, {@code validTo} and {@code deleted} on the root it is handed. A mirror that
 * spelled one of them differently would not fail to compile — a criteria path is a string — it
 * would throw at the first as-of request. Implementing this interface is what makes the compiler
 * hold the three names in place (B3).
 *
 * <p>A mirror row is written by raw JDBC inside the business transaction and is NEVER updated
 * except to close it, so nothing here has a setter in the contract: the entities carry Lombok
 * setters because the reconciler and the writer's tests build rows, not because a reader may
 * edit one (B3).
 */
public interface HistoryRow {

    /**
     * Never NULL, so the in-force test is one indexed two-sided range and "exactly one open row
     * per record" is expressible as a PLAIN unique constraint on {@code (<x>_id, valid_to)} —
     * H2 has no partial indexes, and the repository already lost {@code uk_customer_poc_primary}
     * to that. An ALIAS of {@link AsOf#OPEN} and never a second copy: the sentinel is both the
     * right-hand side of the interval test and the key of the uniqueness that makes the writer
     * race-free, and two definitions of it that drifted by a nanosecond would silently double
     * every as-of count (B3).
     */
    Instant OPEN = AsOf.OPEN;

    /** The surrogate key of this VERSION. Deliberately not called id (B3). */
    Long getHistoryId();

    /**
     * The BUSINESS id — the id of the record this is a version of, mapped to {@code <x>_id} and
     * named {@code id} so that {@code TableQueryExecutor.ids}' {@code root.get("id")} and
     * {@code orderBy}'s id tiebreak both mean the record and not the version, with no change to
     * the executor at all. On the two link mirrors it is the promise id (B3).
     */
    Long getId();

    Instant getValidFrom();

    Instant getValidTo();

    /** A tombstone: the record was hard-deleted at {@code validFrom} and is absent from T on (B3). */
    boolean isDeleted();

    /** Repaired by the reconciler rather than observed live; it makes an as-of answer inexact (B3). */
    boolean isDrifted();
}
