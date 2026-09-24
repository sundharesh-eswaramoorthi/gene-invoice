package com.geneinvoice.common.query;

import com.geneinvoice.approval.ApprovalSchemas;
import com.geneinvoice.automation.AutomationSchemas;
import com.geneinvoice.region.RegionSchemas;
import com.geneinvoice.task.TaskSchemas;

import java.util.List;

/**
 * The single appendable list of top-level table schemas that live outside this package.
 *
 * <p>A feature that publishes a new table owns its own {@code *Schemas} class (region, approval,
 * task, automation) and registers it here with ONE line, so that adding a table is not another
 * edit to TableSchemas.java (B1, B2, A6, A1, INTEGRATION).
 */
final class SchemaRegistry {

    private SchemaRegistry() {}

    static List<TableSchema> extras() {
        return List.of(RegionSchemas.REGIONS, ApprovalSchemas.APPROVALS, TaskSchemas.TASKS,
                AutomationSchemas.RULES, AutomationSchemas.STEPS);
    }
}
