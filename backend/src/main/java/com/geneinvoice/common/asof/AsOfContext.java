package com.geneinvoice.common.asof;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * The READ-SIDE clock: the date this thread is answering as of, or null for "now".
 *
 * <p>Read the way CurrentUser reads SecurityContextHolder — a ThreadLocal consulted at the one
 * existing chokepoint rather than a java.time.Clock bean threaded through 44 call sites. That is
 * deliberate and it is the whole reason this is safe: 44 of the 47 clock reads in the backend are
 * WRITE-side (Instant.now(), 13 of them inside a @PrePersist where no bean can be injected), and
 * as-of must never change what "now" means to a write. So exactly one method moves,
 * {@link com.geneinvoice.invoice.InvoiceDates#today()}, and
 * {@link com.geneinvoice.invoice.InvoiceDates#todayForWrite()} stays on the wall clock (B3).
 *
 * <p>NOT an InheritableThreadLocal, on purpose: a background sweep, a mail dispatch or the history
 * writer started from inside an as-of request must run at the real now, and inheriting the date
 * would let a reader's question silently date a writer's row (B3).
 *
 * <p>The context is opened by AsOfInterceptor for an allowlisted GET and cleared in its
 * afterCompletion, because Tomcat threads are pooled and a date left behind would answer the next
 * person's request from the past.
 */
public final class AsOfContext {

    /** Rebuilt from interval rows: exact, and the normal answer above the floor (B3). */
    public static final String ORIGIN_RECONSTRUCTED = "RECONSTRUCTED";

    /** Answered from the install-time seed row: existence is exact, values are the floor's (B3). */
    public static final String ORIGIN_SEEDED = "SEEDED";

    /**
     * Fixed, and on the wire in every response: what is reconstructed is RECORDS. Privileges,
     * region rights and customer-login identity are always today's — your rights are today's,
     * your book is then's — and AUTH-08 is decided by today's authority (B3).
     */
    public static final String APPLIES_TO = "records";

    private AsOfContext() {
    }

    /**
     * Everything one as-of read needs to describe itself. Immutable: markInexact replaces it
     * rather than mutating, so a state handed to a nested open cannot be edited underneath it.
     */
    public record State(LocalDate date, LocalDate floor, String origin, boolean exact,
                        List<String> notes, int omittedDeleted) {

        public State {
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        public static State of(LocalDate date, LocalDate floor) {
            return new State(date, floor, ORIGIN_RECONSTRUCTED, true, List.of(), 0);
        }
    }

    /** What {@link #open} hands back: closing RESTORES what was there, it does not clear. */
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    private static final ThreadLocal<State> STATE = new ThreadLocal<>();

    public static Handle open(LocalDate date) {
        return open(State.of(date, null));
    }

    /**
     * Restoring rather than removing on close, which is the rule RegionScope.within already
     * follows: a nested open has to give the outer one back, or a backtest that asks two dates
     * in turn leaves the second one answering live (B3).
     */
    public static Handle open(State state) {
        State before = STATE.get();
        STATE.set(state);
        return () -> {
            if (before == null) {
                STATE.remove();
            } else {
                STATE.set(before);
            }
        };
    }

    /** The request boundary: a pooled thread must not carry a date into the next request (B3). */
    public static void clear() {
        STATE.remove();
    }

    /** Null when this thread is answering live, which is every request that carries no ?asOf. */
    public static LocalDate date() {
        State s = STATE.get();
        return s == null ? null : s.date();
    }

    public static boolean isActive() {
        return STATE.get() != null;
    }

    public static State state() {
        return STATE.get();
    }

    /**
     * The boundary instant of the asked-for UTC day, INCLUSIVE — the same whole-day convention
     * FilterPredicates.atMost already uses for a date-only lte on an Instant, so a change
     * committed at 23:59 on the asked-for day is included and one committed a nanosecond later is
     * not (B3).
     *
     * <p>Throws rather than returning null when nothing is open: an Instant of null silently
     * poisons every interval predicate built from it, and a caller that has not checked
     * {@link #isActive()} wants {@link #instantOrNow()}.
     */
    public static Instant instant() {
        LocalDate date = date();
        if (date == null) {
            throw new IllegalStateException("No as-of date is open on this thread");
        }
        return date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
    }

    /** The as-of boundary, or the real now when this thread is answering live. */
    public static Instant instantOrNow() {
        return isActive() ? instant() : Instant.now();
    }

    /**
     * Downgrade this answer and say why, in one sentence the banner and the CSV caveat row render
     * verbatim. Additive and idempotent: two drifted tables in one response leave two notes and
     * never flip exact back (B3).
     */
    public static void markInexact(String note) {
        State s = STATE.get();
        if (s == null) return;
        List<String> notes = new ArrayList<>(s.notes());
        if (note != null && !note.isBlank() && !notes.contains(note)) notes.add(note);
        STATE.set(new State(s.date(), s.floor(), s.origin(), false, notes, s.omittedDeleted()));
    }

    /** Records that were deleted before the floor and therefore have no mirror row to serve (B3). */
    public static void markOmittedDeleted(int count) {
        State s = STATE.get();
        if (s == null || count <= 0) return;
        STATE.set(new State(s.date(), s.floor(), s.origin(), s.exact(), s.notes(),
                s.omittedDeleted() + count));
    }

    /**
     * What the envelope carries. Null on a live read, so PageResponse.of can fill it
     * unconditionally and a list served as of a date cannot forget to say so (B3).
     */
    public static AsOfInfo info() {
        State s = STATE.get();
        if (s == null) return null;
        return new AsOfInfo(
                s.date().toString(),
                s.floor() == null ? null : s.floor().toString(),
                s.exact(),
                s.origin(),
                APPLIES_TO,
                s.omittedDeleted(),
                s.notes());
    }
}
