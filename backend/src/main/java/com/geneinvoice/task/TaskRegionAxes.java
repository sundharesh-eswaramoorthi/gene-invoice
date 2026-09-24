package com.geneinvoice.task;

import com.geneinvoice.region.RegionAxis;
import com.geneinvoice.region.RegionAxisRegistry;

import java.util.Map;

/**
 * Tasks' answer to "which region is this row in?", contributed rather than edited into
 * region/RegionAxes: this class lives in the task package, so the type-safe reference to the two
 * entities goes the permitted direction and region still imports nothing of ours (A6, B1).
 *
 * <p>A Task is VIA_CUSTOMER_ID and not a region column of its own. The blueprint strikes Part A's
 * tasks.region_id: customers.region_id is the only region column on a business table, so a task's
 * branch is read through the flat customer_id it already carries — which is also what makes a
 * customer's move take its tasks with it for free (A6, B1).
 */
public final class TaskRegionAxes {

    private TaskRegionAxes() {}

    public static final RegionAxisRegistry.Contribution CONTRIBUTION =
            new RegionAxisRegistry.Contribution(
                    Map.of(Task.class, RegionAxis.VIA_CUSTOMER_ID,
                            TaskAssignee.class, RegionAxis.NONE),
                    Map.of(TaskAssignee.class,
                            "a seat on a task is reached only through the task, whose region is"
                                    + " its customer's (A6, B1)"));
}
