package com.geneinvoice.automation;

import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.common.query.ColumnType;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.region.RegionPredicates;

import java.util.Arrays;
import java.util.List;

/**
 * The two automation read surfaces as tables.
 *
 * <p>They live here rather than in TableSchemas because a feature that publishes a new table owns
 * its own schema: registering one is a line in SchemaRegistry and not another edit to the file
 * every other feature is also queueing behind (A1, A5, B1, B2 INTEGRATION).
 *
 * <p>These two are the first schemas in the application whose entity name is not lower case, and
 * that is what the {@code TableSchemas.byEntity} fix is for: the map is keyed by
 * {@code schema.entity()} while the lookup lower-cased its argument, so
 * {@code GET /api/table-schemas/automationRules} would have answered "Unknown table" while
 * TableSchemaController.VIEW_PRIVILEGE resolved perfectly (A1, S3).
 */
public final class AutomationSchemas {

    private AutomationSchemas() {}

    private static List<String> names(Class<? extends Enum<?>> e) {
        return Arrays.stream(e.getEnumConstants()).map(Enum::name).toList();
    }

    /**
     * The rules list. AutomationRule is classified NONE, so the executor adds NO region predicate
     * here: a rule belongs to no branch, and WHICH rules a reader may see is answered by
     * AutomationRuleService's own visibility predicate over automation_rule_regions — which is
     * also where the soft-delete clause lives, so a deleted rule leaves the list without
     * {@code deletedAt} having to be a column anybody can filter on (A1, B1).
     */
    public static final TableSchema RULES = TableSchema.of(
            "automationRules", AutomationRule.class, "name,asc",
            // Mandatory: inScope() and every orderBy tie-break resolve through it (A1).
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("name", "Name", ColumnType.TEXT).build(),
            ColumnDef.of("subjectType", "Subject", ColumnType.ENUM)
                    .enumValues(names(SubjectType.class)).build(),
            ColumnDef.of("triggerKind", "Trigger", ColumnType.ENUM)
                    .enumValues(names(TriggerKind.class)).build(),
            ColumnDef.of("enabled", "Enabled", ColumnType.BOOLEAN).build(),
            ColumnDef.of("lastRunAt", "Last run", ColumnType.DATE).build(),
            ColumnDef.of("nextRunAt", "Next run", ColumnType.DATE).build(),
            ColumnDef.of("createdByUserId", "Author", ColumnType.REFERENCE)
                    .reference("user").notSortable().build(),
            ColumnDef.of("createdAt", "Created", ColumnType.DATE).build());

    /**
     * The run history, which is ONE table for the event path, the schedule path and "run now": a
     * reader asking "what has automation done to this account?" should not have to know which
     * door the work came in through (A5).
     *
     * <p>AutomationStep is classified VIA_CUSTOMER_ID, so TableQueryExecutor ANDs the caller's own
     * regions in at VIEW level before this list has said anything — which is exactly what makes
     * "the run history only shows steps about records the reader may see" a property of the axis
     * and not of anybody remembering (A5, B1).
     */
    public static final TableSchema STEPS = TableSchema.of(
            "automationSteps", AutomationStep.class, "id,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("createdAt", "When", ColumnType.DATE).build(),
            ColumnDef.of("ruleId", "Rule", ColumnType.REFERENCE).reference("rule").build(),
            ColumnDef.of("ruleName", "Rule name", ColumnType.TEXT).build(),
            ColumnDef.of("subjectType", "About", ColumnType.ENUM)
                    .enumValues(names(SubjectType.class)).build(),
            ColumnDef.of("subjectId", "Record id", ColumnType.NUMBER).build(),
            ColumnDef.of("customerId", "Customer", ColumnType.REFERENCE)
                    .reference("customer").notSortable().build(),
            // A step keeps a bare customer_id with no association to walk, so these two are the
            // dispute pair verbatim rather than a third copy of the same two shapes. The path is
            // only ever used for sorting and both columns are notSortable, so regionId resolves to
            // the id it has and regionName is a correlated scalar subquery (A5, B1).
            ColumnDef.of("regionId", "Region", ColumnType.REFERENCE)
                    .reference("region").pocRestricted().notSortable()
                    .path((root, q, cb) -> root.get("customerId"))
                    .filter(RegionPredicates::disputeRegionFilter)
                    .build(),
            ColumnDef.of("regionName", "Region name", ColumnType.TEXT).pocRestricted().notSortable()
                    .path(RegionPredicates::customerRegionName).build(),
            ColumnDef.of("actionKind", "Action", ColumnType.ENUM)
                    .enumValues(names(ActionKind.class)).build(),
            ColumnDef.of("actionIndex", "Step", ColumnType.NUMBER).build(),
            ColumnDef.of("source", "Source", ColumnType.ENUM)
                    .enumValues(names(StepSource.class)).build(),
            ColumnDef.of("status", "Status", ColumnType.ENUM)
                    .enumValues(names(StepStatus.class)).build(),
            ColumnDef.of("attempts", "Attempts", ColumnType.NUMBER).build(),
            ColumnDef.of("runId", "Run", ColumnType.NUMBER).build(),
            ColumnDef.of("occasion", "Occasion", ColumnType.TEXT).notSortable().build(),
            ColumnDef.of("producedType", "Made", ColumnType.ENUM)
                    .enumValues(names(ProducedType.class)).build(),
            ColumnDef.of("producedId", "Made id", ColumnType.NUMBER).notSortable().build(),
            ColumnDef.of("result", "Result", ColumnType.TEXT).notSortable().build(),
            ColumnDef.of("finishedAt", "Finished", ColumnType.DATE).build());
}
