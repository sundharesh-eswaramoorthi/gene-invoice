package com.geneinvoice.automation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * When a rule is armed: by a change to a record, or by the clock (A1).
 *
 * <p>There is no {@code @Scheduled(cron)} anywhere in this feature and none may be added. The
 * fire time lives in two columns on the rule ({@code schedule_hour_utc},
 * {@code schedule_day_of_week}) and the next firing lives in {@code next_run_at}, because a cron
 * expression on a bean fires on EVERY instance of the application at once and this codebase has
 * no leader election. {@code AutomationRuleRepository.claimSchedule} is the cross-instance lock
 * instead: one conditional UPDATE, one winner (A1).
 */
public enum TriggerKind {

    ON_CREATED,
    ON_UPDATED,
    ON_CREATED_OR_UPDATED,
    SCHEDULE_DAILY,
    SCHEDULE_WEEKLY;

    /** The clock kinds, which are the only ones that read the two schedule columns (A1). */
    public boolean scheduled() {
        return this == SCHEDULE_DAILY || this == SCHEDULE_WEEKLY;
    }

    /** Does a rule armed this way care about this change? Null-safe for a null change (A1). */
    public boolean armedBy(Change change) {
        if (change == null || scheduled()) return false;
        return switch (this) {
            case ON_CREATED -> change == Change.CREATED;
            case ON_UPDATED -> change == Change.UPDATED;
            case ON_CREATED_OR_UPDATED -> true;
            default -> false;
        };
    }

    /**
     * The next moment this rule should fire, STRICTLY AFTER {@code now}.
     *
     * <p>Strictly after is the whole behaviour: a daily rule whose instance was down for three
     * days fires ONCE when it comes back and not three times, because the next slot is computed
     * forward from the clock rather than by adding a day to a missed {@code next_run_at}. That is
     * the right dunning behaviour — a customer who was not chased on Monday is chased today, not
     * chased three times today (A1).
     *
     * <p>Everything is UTC, matching InvoiceDates.today(), and the drift a 60 s sweep introduces
     * (a daily rule fires within a minute of its hour, not on it) is stated in the rule editor's
     * help text rather than pretended away.
     *
     * @return null for a kind that is not on the clock at all, so a caller that reaches here with
     *         an event trigger writes a null next_run_at rather than an invented one
     */
    public Instant nextAfter(Instant now, Integer dayOfWeek, Integer hourUtc) {
        if (!scheduled()) return null;
        int hour = hourUtc == null ? 0 : Math.floorMod(hourUtc, 24);
        LocalDateTime here = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        LocalDate day = here.toLocalDate();
        if (this == SCHEDULE_DAILY) {
            LocalDateTime today = day.atTime(hour, 0);
            // Not isBefore: a rule claimed exactly on its hour must move to tomorrow, or the same
            // slot is eligible twice within the same second (A1).
            return (today.isAfter(here) ? today : day.plusDays(1).atTime(hour, 0)).toInstant(ZoneOffset.UTC);
        }
        int wanted = dayOfWeek == null ? 1 : Math.floorMod(dayOfWeek - 1, 7) + 1;   // 1..7 ISO
        LocalDate candidate = day.plusDays(Math.floorMod(wanted - day.getDayOfWeek().getValue(), 7));
        LocalDateTime at = candidate.atTime(hour, 0);
        // The candidate can be today-but-already-past, in which case the next one is a week on.
        return (at.isAfter(here) ? at : candidate.plusWeeks(1).atTime(hour, 0)).toInstant(ZoneOffset.UTC);
    }
}
