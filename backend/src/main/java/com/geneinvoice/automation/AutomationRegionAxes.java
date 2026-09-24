package com.geneinvoice.automation;

import com.geneinvoice.region.RegionAxis;
import com.geneinvoice.region.RegionAxisRegistry;

import java.util.Map;

/**
 * Automation's answer to "which region is this row in?", contributed rather than edited into
 * region/RegionAxes, so the type-safe reference to our entities goes the permitted direction and
 * region still imports nothing of ours (A5, B1).
 *
 * <p>It is created by the unit that adds the FIRST automation entity, not by the unit that adds
 * the rules, because the blueprint's hard rule is that a new @Entity is classified in the same
 * commit that maps it — {@code RegionCoverageCheck} refuses to start otherwise (A1, B1).
 */
public final class AutomationRegionAxes {

    private AutomationRegionAxes() {}

    public static final RegionAxisRegistry.Contribution CONTRIBUTION =
            new RegionAxisRegistry.Contribution(
                    Map.of(AutomationEvent.class, RegionAxis.NONE,
                            AutomationRule.class, RegionAxis.NONE,
                            AutomationRun.class, RegionAxis.NONE,
                            // The run history IS region-scoped, and for free: a step carries the
                            // account its subject belongs to, so TableQueryExecutor narrows the
                            // list to the caller's branches before AutomationRuleService has said
                            // anything (A5, B1).
                            AutomationStep.class, RegionAxis.VIA_CUSTOMER_ID),
                    Map.of(AutomationEvent.class,
                            "an internal outbox with no read surface: an event is the bare fact"
                                    + " that a record changed, and the branch that matters is"
                                    + " checked when a rule acts on it (A5, B1)",
                            AutomationRule.class,
                            "a rule belongs to no single branch: WHERE it may reach is the set in"
                                    + " automation_rule_regions, applied by AutomationRuleService"
                                    + " as its own predicate and bounded at save time against its"
                                    + " author's manage grants, not by a row axis (A1, B1)",
                            AutomationRun.class,
                            "a run is one firing of a rule over many records in many branches, so"
                                    + " it has no branch of its own; it is scoped through its rule"
                                    + " and read through the steps it planned (A5, B1)"));
}
