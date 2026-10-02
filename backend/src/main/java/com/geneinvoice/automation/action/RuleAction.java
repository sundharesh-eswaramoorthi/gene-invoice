package com.geneinvoice.automation.action;

/**
 * One thing a rule does to each record it matches (A3; §6.14 gives the shapes). Every action has a stable
 * {@code key}, which names it in effects and dedupe keys, and says how often it acts on one record.
 */
public sealed interface RuleAction
        permits CreateTaskAction, CreatePromiseAction, CreateDisputeAction, SendEmailAction {

    String key();

    Repeat repeat();

    ActionType type();

    RuleAction withKey(String key, Repeat repeat);
}
