package com.geneinvoice.automation.action;

import java.util.List;

/** What an action did to one record (§5.10.6): made something, or deliberately left it alone. */
public sealed interface ActionResult permits ActionResult.Done, ActionResult.Skipped {

    /** It made a {@code resultType} ({@code TASK}, {@code PROMISE}, …) with id {@code resultId}. */
    record Done(String resultType, Long resultId, List<String> warnings) implements ActionResult {
        public Done {
            warnings = List.copyOf(warnings);
        }
    }

    /** Nothing to do here, and why: its guard held ("an open task is already there"). */
    record Skipped(String message) implements ActionResult {}
}
