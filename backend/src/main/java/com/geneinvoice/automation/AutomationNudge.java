package com.geneinvoice.automation;

import java.util.List;

/**
 * Told, AFTER the user's transaction committed, which event ids have just appeared (A5).
 *
 * <p>It is an OPTIMISATION and never the guarantee. The guarantee is that the event row committed
 * with the change and that the sweeper finds it; the nudge only means a rule fires in a
 * moment rather than within the sweep interval. There is therefore no contract that a nudge is
 * ever delivered, and an implementation is free to drop the ids on the floor when it is busy.
 *
 * <p>DEVIATION, FLAGGED (A1, A5): the settled text has {@code ChangeFeed} hold an
 * {@code ObjectProvider<AutomationDispatcher>} directly. The dispatcher does not exist until the
 * consumer lands two waves later, so the feed depends on this one-method interface instead and
 * resolves it with {@code getIfAvailable()}. Until something implements it the nudge is a no-op,
 * which is exactly the documented steady state anyway — and it is also the state in which
 * "nothing is lost if the consumer is down" is provable, because that is literally the
 * configuration.
 */
public interface AutomationNudge {

    /** @param eventIds the ids just committed, in insertion order; never null and never empty */
    void nudge(List<Long> eventIds);
}
