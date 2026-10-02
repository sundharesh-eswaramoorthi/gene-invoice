package com.geneinvoice.automation.schedule;

import com.geneinvoice.automation.rule.AutomationRule;
import com.geneinvoice.automation.rule.TriggerKind;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * When a scheduled rule is due (§5.10.8): every day, or every week on one day, at a time of day in the rule's zone.
 * Pure: it works by date in the zone, so a slot is always at its wall-clock time. A time that does not exist that day
 * (the clocks go forward) moves forward by the gap; a time that happens twice (the clocks go back) is the earlier.
 */
public final class ScheduleCalculator {

    private ScheduleCalculator() {}

    /** A schedule: daily, or weekly on {@code day}, at {@code time} in {@code zone}. */
    public record Spec(TriggerKind kind, LocalTime time, DayOfWeek day, ZoneId zone) {

        public Spec {
            if (!kind.isSchedule()) throw new IllegalArgumentException(kind + " is not a schedule");
            if (kind == TriggerKind.SCHEDULE_WEEKLY && day == null) {
                throw new IllegalArgumentException("A weekly schedule needs a day");
            }
        }

        public static Spec of(TriggerKind kind, String time, String day, ZoneId zone) {
            return new Spec(kind, LocalTime.parse(time), day == null ? null : DayOfWeek.valueOf(day), zone);
        }

        /** The rule's schedule; null for a rule that is not on one. */
        public static Spec of(AutomationRule r) {
            if (!r.getTriggerKind().isSchedule()) return null;
            return of(r.getTriggerKind(), r.getScheduleTime(), r.getScheduleDay(), ZoneId.of(r.getScheduleZone()));
        }

        boolean runsOn(LocalDate date) {
            return kind == TriggerKind.SCHEDULE_DAILY || date.getDayOfWeek() == day;
        }

        Instant slotOn(LocalDate date) {
            return ZonedDateTime.of(date, time, zone).toInstant();
        }

        /** How far apart two runs of the schedule can be, in days, plus a day for a clock change. */
        int span() {
            return kind == TriggerKind.SCHEDULE_DAILY ? 2 : 8;
        }
    }

    /**
     * Slots missed in {@code (fromExclusive, toInclusive]}: at most {@code max} of them, the latest, oldest first.
     * {@code dropped} counts the older ones left out, {@code firstDropped} and {@code lastDropped} say when they were.
     */
    public record Missed(List<Instant> slots, int dropped, Instant firstDropped, Instant lastDropped) {}

    /** The first slot strictly after {@code after}. */
    public static Instant nextAfter(Spec spec, Instant after) {
        LocalDate date = LocalDate.ofInstant(after, spec.zone()).minusDays(1);
        for (int i = 0; i <= spec.span() + 1; i++, date = date.plusDays(1)) {
            if (!spec.runsOn(date)) continue;
            Instant slot = spec.slotOn(date);
            if (slot.isAfter(after)) return slot;
        }
        throw new IllegalStateException("No slot found after " + after);
    }

    /** The latest slot at or before {@code t}. */
    public static Instant latestAtOrBefore(Spec spec, Instant t) {
        LocalDate date = LocalDate.ofInstant(t, spec.zone()).plusDays(1);
        for (int i = 0; i <= spec.span() + 1; i++, date = date.minusDays(1)) {
            if (!spec.runsOn(date)) continue;
            Instant slot = spec.slotOn(date);
            if (!slot.isAfter(t)) return slot;
        }
        throw new IllegalStateException("No slot found before " + t);
    }

    /** The slots in {@code (fromExclusive, toInclusive]}, the latest {@code max} of them, oldest first. */
    public static Missed missedSlots(Spec spec, Instant fromExclusive, Instant toInclusive, int max) {
        Deque<Instant> kept = new ArrayDeque<>();
        int dropped = 0;
        Instant firstDropped = null;
        Instant lastDropped = null;
        for (Instant slot = nextAfter(spec, fromExclusive); !slot.isAfter(toInclusive); slot = nextAfter(spec, slot)) {
            kept.addLast(slot);
            if (kept.size() > Math.max(max, 1)) {
                Instant old = kept.removeFirst();
                if (firstDropped == null) firstDropped = old;
                lastDropped = old;
                dropped++;
            }
        }
        return new Missed(List.copyOf(kept), dropped, firstDropped, lastDropped);
    }
}
