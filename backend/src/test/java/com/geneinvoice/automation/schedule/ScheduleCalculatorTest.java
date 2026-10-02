package com.geneinvoice.automation.schedule;

import com.geneinvoice.automation.rule.TriggerKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a scheduled rule is due (§5.10.8): at its time of day in its own zone, every day or on its weekday; through
 * the clocks going forward and back; and the slots a gap left behind, the latest of them when there are too many.
 */
class ScheduleCalculatorTest {

    private static final ZoneId INDIA = ZoneId.of("+05:30");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static ScheduleCalculator.Spec daily(String time, ZoneId zone) {
        return ScheduleCalculator.Spec.of(TriggerKind.SCHEDULE_DAILY, time, null, zone);
    }

    private static ScheduleCalculator.Spec weekly(String day, String time, ZoneId zone) {
        return ScheduleCalculator.Spec.of(TriggerKind.SCHEDULE_WEEKLY, time, day, zone);
    }

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    @Test
    void aDailySlotIsAtItsTimeOfDayInTheRulesZone() {
        ScheduleCalculator.Spec spec = daily("09:00", INDIA);

        // 09:00 in India is 03:30 UTC; just before it, today's slot is next; at it, tomorrow's.
        assertThat(ScheduleCalculator.nextAfter(spec, at("2026-09-27T03:29:59Z")))
                .isEqualTo(at("2026-09-27T03:30:00Z"));
        assertThat(ScheduleCalculator.nextAfter(spec, at("2026-09-27T03:30:00Z")))
                .isEqualTo(at("2026-09-28T03:30:00Z"));
        // Late on the 27th in UTC is already the 28th in India, after that day's slot.
        assertThat(ScheduleCalculator.nextAfter(spec, at("2026-09-27T20:00:00Z")))
                .isEqualTo(at("2026-09-28T03:30:00Z"));
        assertThat(ScheduleCalculator.nextAfter(daily("23:45", INDIA), at("2026-09-27T12:00:00Z")))
                .as("23:45 in India is 18:15 UTC the same day").isEqualTo(at("2026-09-27T18:15:00Z"));
    }

    @Test
    void aWeeklySlotIsOnlyOnItsDay() {
        ScheduleCalculator.Spec spec = weekly("MONDAY", "09:00", INDIA);

        // Sunday 27 September 2026: the next slot is Monday morning in India.
        assertThat(ScheduleCalculator.nextAfter(spec, at("2026-09-27T10:00:00Z")))
                .isEqualTo(at("2026-09-28T03:30:00Z"));
        // From Monday after the slot, a week on.
        assertThat(ScheduleCalculator.nextAfter(spec, at("2026-09-28T04:00:00Z")))
                .isEqualTo(at("2026-10-05T03:30:00Z"));
        assertThat(ScheduleCalculator.latestAtOrBefore(spec, at("2026-10-04T23:00:00Z")))
                .as("still Sunday's view: last Monday's").isEqualTo(at("2026-09-28T03:30:00Z"));
    }

    @Test
    void whenTheClocksGoForwardAMissingTimeMovesOnByTheGap() {
        // 29 March 2026: in London 01:00 becomes 02:00, so 01:30 does not exist that day.
        ScheduleCalculator.Spec spec = daily("01:30", LONDON);

        Instant slot = ScheduleCalculator.nextAfter(spec, at("2026-03-28T12:00:00Z"));
        assertThat(slot).as("02:30 summer time, 01:30 UTC").isEqualTo(at("2026-03-29T01:30:00Z"));
        assertThat(ScheduleCalculator.nextAfter(spec, slot)).as("01:30 summer time on the next day")
                .isEqualTo(at("2026-03-30T00:30:00Z"));
        assertThat(ScheduleCalculator.nextAfter(spec, at("2026-03-27T12:00:00Z")))
                .as("01:30 winter time the day before").isEqualTo(at("2026-03-28T01:30:00Z"));
    }

    @Test
    void whenTheClocksGoBackATimeThatHappensTwiceIsTheEarlier() {
        // 25 October 2026: in London 02:00 becomes 01:00, so 01:30 happens twice.
        ScheduleCalculator.Spec spec = daily("01:30", LONDON);

        Instant slot = ScheduleCalculator.nextAfter(spec, at("2026-10-24T12:00:00Z"));
        assertThat(slot).as("the first 01:30, still summer time").isEqualTo(at("2026-10-25T00:30:00Z"));
        assertThat(ScheduleCalculator.nextAfter(spec, slot)).as("the second 01:30 is not a slot of its own")
                .isEqualTo(at("2026-10-26T01:30:00Z"));
        assertThat(ScheduleCalculator.latestAtOrBefore(spec, at("2026-10-25T01:45:00Z")))
                .isEqualTo(at("2026-10-25T00:30:00Z"));
    }

    @Test
    void theLatestSlotAtOrBeforeAMomentIncludesASlotExactlyThen() {
        ScheduleCalculator.Spec spec = daily("09:00", ZoneId.of("UTC"));

        assertThat(ScheduleCalculator.latestAtOrBefore(spec, at("2026-09-27T09:00:00Z")))
                .isEqualTo(at("2026-09-27T09:00:00Z"));
        assertThat(ScheduleCalculator.latestAtOrBefore(spec, at("2026-09-27T08:59:59.999999Z")))
                .isEqualTo(at("2026-09-26T09:00:00Z"));
        assertThat(ScheduleCalculator.latestAtOrBefore(weekly("FRIDAY", "09:00", ZoneId.of("UTC")),
                at("2026-09-27T09:00:00Z"))).as("the Friday before that Sunday").isEqualTo(at("2026-09-25T09:00:00Z"));
    }

    @Test
    void missedSlotsAreListedOldestFirstAndOnlyTheLatestAreKeptPastTheLimit() {
        ScheduleCalculator.Spec spec = daily("09:00", ZoneId.of("UTC"));

        ScheduleCalculator.Missed all = ScheduleCalculator.missedSlots(spec, at("2026-09-20T09:00:00Z"),
                at("2026-09-24T09:00:00Z"), 31);
        assertThat(all.slots()).as("from after the 20th's slot up to and including the 24th's").containsExactly(
                at("2026-09-21T09:00:00Z"), at("2026-09-22T09:00:00Z"), at("2026-09-23T09:00:00Z"),
                at("2026-09-24T09:00:00Z"));
        assertThat(all.dropped()).isZero();

        ScheduleCalculator.Missed capped = ScheduleCalculator.missedSlots(spec, at("2026-09-20T09:00:00Z"),
                at("2026-09-24T09:00:00Z"), 2);
        assertThat(capped.slots()).containsExactly(at("2026-09-23T09:00:00Z"), at("2026-09-24T09:00:00Z"));
        assertThat(capped.dropped()).isEqualTo(2);
        assertThat(capped.firstDropped()).isEqualTo(at("2026-09-21T09:00:00Z"));
        assertThat(capped.lastDropped()).isEqualTo(at("2026-09-22T09:00:00Z"));

        assertThat(ScheduleCalculator.missedSlots(spec, at("2026-09-24T09:00:00Z"), at("2026-09-24T12:00:00Z"), 31)
                .slots()).as("nothing missed since the last slot").isEmpty();
        assertThat(ScheduleCalculator.missedSlots(weekly("MONDAY", "09:00", INDIA), at("2026-09-01T00:00:00Z"),
                at("2026-09-30T00:00:00Z"), 31).slots()).containsExactly(at("2026-09-07T03:30:00Z"),
                at("2026-09-14T03:30:00Z"), at("2026-09-21T03:30:00Z"), at("2026-09-28T03:30:00Z"));
    }
}
