package com.geneinvoice.automation;

import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.automation.rule.TriggerKind;
import com.geneinvoice.common.schema.SchemaSupport;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Moves the rows an earlier build left in {@code automation_events} and {@code automation_rules} that this build
 * cannot read to {@code <table>_legacy}: copied, then deleted, in one transaction. Nothing is ever dropped.
 *
 * <p>Runs twice: before Hibernate's DDL ({@link AutomationPreSchemaConfig}), where it must empty such a table of
 * that build's rows so that {@code ddl-auto: update} can add this build's NOT NULL columns to it (MIG-R2-1), and
 * after it, from {@link AutomationSchemaUpgrade}. Needs only a {@link DataSource}; never throws, only warns.
 *
 * <p>A table is looked at only when it has one of the earlier build's columns ({@link #LEGACY_COLUMNS}). Then every
 * row is unreadable when the table lacks any column this build requires ({@link #REQUIRED}); otherwise the rows
 * whose status, kind, actions, trigger or record kind this build does not know are.
 */
@Slf4j
public class LegacyAutomationRows {

    static final String EVENTS = "automation_events";
    static final String RULES = "automation_rules";

    /** The columns only an earlier build's tables have: their presence says rows may be that build's. */
    static final Map<String, List<String>> LEGACY_COLUMNS = Map.of(
            RULES, List.of("action", "filters_json"),
            EVENTS, List.of("idempotency_key", "trigger_kind"));

    /**
     * The NOT NULL columns this build's mapping has beyond those every earlier table already had (id, enabled,
     * name, created_at, updated_at): a row without one of them cannot be read, and while the table has rows
     * {@code ddl-auto} cannot add it. AutomationLegacySchemaTest holds the mapping to this list.
     */
    static final Map<String, Set<String>> REQUIRED = Map.of(
            EVENTS, Set.of("kind", "occurred_at", "depth", "claims", "status", "attempts"),
            RULES, Set.of("actions_json", "owner_user_id", "schedule_zone", "catch_up", "max_records_per_run",
                    "trigger_kind", "entity_type"));

    private final DataSource dataSource;

    public LegacyAutomationRows(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Archives the events, then the rules. */
    public void archiveAll() {
        archive(EVENTS);
        archive(RULES);
    }

    /** The rows of {@code table} this build cannot read, as a WHERE clause; null when none can be. */
    static String unreadable(String table, Map<String, Boolean> columns) {
        if (columns.isEmpty() || LEGACY_COLUMNS.get(table).stream().noneMatch(columns::containsKey)) return null;
        if (!columns.keySet().containsAll(REQUIRED.get(table))) return "1 = 1";
        if (EVENTS.equals(table)) {
            return "(status is null or status not in " + listed(List.of(AutomationEvent.PENDING,
                    AutomationEvent.PROCESSING, AutomationEvent.DONE, AutomationEvent.DEAD)) + ")"
                    + " or (kind is null)";
        }
        return "(actions_json is null)"
                + " or (trigger_kind is null or trigger_kind not in " + listed(names(TriggerKind.class)) + ")"
                + " or (entity_type is null or entity_type not in " + listed(names(TriggerEntity.class)) + ")";
    }

    /** Moves the unreadable rows of {@code table} (events or rules) aside. */
    public void archive(String table) {
        if (!LEGACY_COLUMNS.containsKey(table)) throw new IllegalArgumentException(table);
        try (Connection connection = dataSource.getConnection()) {
            Map<String, Boolean> columns = SchemaSupport.columns(connection, table);
            String where = unreadable(table, columns);
            if (where == null) return;
            long count;
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("select count(*) from " + table + " where " + where)) {
                rows.next();
                count = rows.getLong(1);
            }
            if (count == 0) return;
            String legacy = table + "_legacy";
            try (Statement statement = connection.createStatement()) {
                statement.execute("create table if not exists " + legacy + " as select * from " + table
                        + " where 1 = 0");
            }
            // An archive refuses nothing: a copy made earlier may have a NOT NULL column the live table now lacks.
            SchemaSupport.relaxColumns(connection, legacy, Set.of(), false);
            // By name: the live table may have gained or lost columns since an earlier run made the copy.
            Set<String> kept = SchemaSupport.columns(connection, legacy).keySet();
            String shared = columns.keySet().stream().filter(kept::contains).collect(Collectors.joining(", "));
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                int copied = statement.executeUpdate("insert into " + legacy + " (" + shared + ") select " + shared
                        + " from " + table + " where " + where);
                statement.executeUpdate("delete from " + table + " where " + where);
                connection.commit();
                log.warn("Moved {} row(s) of {} that this build cannot read to {}", copied, table, legacy);
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            log.warn("Could not move the unreadable rows of {} aside: {}", table, e.getMessage());
        }
    }

    private static List<String> names(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
    }

    private static String listed(List<String> values) {
        return values.stream().map(v -> "'" + v + "'").collect(Collectors.joining(", ", "(", ")"));
    }
}
