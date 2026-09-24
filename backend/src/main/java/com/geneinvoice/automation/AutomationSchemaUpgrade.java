package com.geneinvoice.automation;

import com.geneinvoice.common.SchemaSupport;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.task.TaskStatus;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Pre-emptive check-constraint maintenance for every enum column Part A introduces, and a loud
 * look at the two unique indexes the no-double-action guarantee rests on (A1, A5).
 *
 * <p>NOTHING HERE IS STALE TODAY. All four tables are born empty on every database, so ddl-auto
 * generates their check constraints from today's enums and they are correct. These eleven calls
 * are for the deploy in 2027 that adds a sixth {@link StepStatus} or a fifth {@link ActionKind}:
 * {@code update} never modifies a check constraint it has already created, so without this the
 * new constant is refused at INSERT time — and for {@link EventStatus} that insert happens inside
 * the USER'S OWN SAVE, so it would not surface as a bland automation error but as a customer
 * failing to save an invoice (A1, A5).
 *
 * <p>It widens {@code tasks.status} and {@code tasks.entity_type} although A6 owns that table.
 * That is deliberate: one bean that knows about every enum this programme added is better than
 * two beans that each know about half, and a second widener for tasks alone is exactly the thing
 * somebody forgets to write (A6, A5 INTEGRATION).
 *
 * <p>Raw JDBC, an unused EntityManagerFactory parameter to order this bean after Hibernate's
 * schema export (the InvoiceSchemaUpgrade / RowVersionUpgrade idiom), and NEVER throws: upgrades
 * run first and carry on, checks run last and refuse (A5, B1).
 */
@Component
@DependsOn("regionSchemaUpgrade")
@Slf4j
class AutomationSchemaUpgrade implements InitializingBean {

    private final DataSource dataSource;

    AutomationSchemaUpgrade(DataSource dataSource, EntityManagerFactory schemaUpToDate) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        try (Connection connection = dataSource.getConnection()) {
            // A new enum constant makes every insert fail until the generated check constraint is
            // widened; widening is idempotent, so it runs on every boot (A5).
            SchemaSupport.widen(connection, "automation_rules", "subject_type", SubjectType.class);
            SchemaSupport.widen(connection, "automation_rules", "trigger_kind", TriggerKind.class);
            // These three are the load-bearing ones: an automation_events INSERT happens inside
            // the user's transaction, so a too-narrow constraint here takes their save down with
            // it rather than just breaking automation (A1).
            SchemaSupport.widen(connection, "automation_events", "subject_type", SubjectType.class);
            SchemaSupport.widen(connection, "automation_events", "change", Change.class);
            SchemaSupport.widen(connection, "automation_events", "status", EventStatus.class);
            SchemaSupport.widen(connection, "automation_steps", "status", StepStatus.class);
            SchemaSupport.widen(connection, "automation_steps", "action_kind", ActionKind.class);
            SchemaSupport.widen(connection, "automation_steps", "source", StepSource.class);
            // The three A-RULES left out, and produced_type is the one that matters: A-CONSUMER
            // is the first code that ever WRITES it, so a sixth ProducedType added on a later
            // deploy would fail the settle — inside the very transaction the domain write shares,
            // which would roll the Task back with it and retry for ever (A5).
            SchemaSupport.widen(connection, "automation_steps", "subject_type", SubjectType.class);
            SchemaSupport.widen(connection, "automation_steps", "produced_type", ProducedType.class);
            SchemaSupport.widen(connection, "automation_runs", "status", RunStatus.class);
            SchemaSupport.widen(connection, "automation_runs", "source", StepSource.class);
            SchemaSupport.widen(connection, "tasks", "status", TaskStatus.class);
            SchemaSupport.widen(connection, "tasks", "entity_type", TaskEntityType.class);

            // THE TWO INDEXES THE WHOLE GUARANTEE RESTS ON. ddl-auto:update failing to create an
            // index is only a WARN when halt_on_error is off, and a missing uk_step_occasion does
            // not break anything visibly — it silently turns "an event cannot fire an action
            // twice" from a database constraint into a hope. So it is said out loud, at ERROR,
            // every boot it is not there (A5).
            requireUnique(connection, "automation_steps", "uk_step_occasion",
                    "an event, a schedule slot or a double-clicked run could fire the same action twice");
            requireUnique(connection, "automation_runs", "uk_run_occasion",
                    "two instances claiming the same schedule slot could both start a run");
        } catch (SQLException e) {
            log.warn("Could not finish the automation schema upgrade: {}", e.getMessage());
        }
    }

    private static void requireUnique(Connection connection, String table, String name,
                                      String consequence) {
        try {
            if (exists(connection, table, name)) return;
            log.error("The unique constraint {} on {} is MISSING: {}. ddl-auto reports a failed"
                    + " index creation as a warning, so this has to be checked rather than"
                    + " assumed (A5).", name, table, consequence);
        } catch (SQLException e) {
            // Not being able to LOOK is not the same as it not being there, and it is certainly
            // not a reason to refuse to start (A5).
            log.warn("Could not check {} on {}: {}", name, table, e.getMessage());
        }
    }

    /**
     * Named constraint or named index — both, because Hibernate emits a @UniqueConstraint as a
     * table constraint on one dialect and an operator may well have created it by hand as a
     * unique index on the other, and either one enforces the rule (A5).
     */
    private static boolean exists(Connection connection, String table, String name) throws SQLException {
        String product = SchemaSupport.product(connection);
        String sql;
        boolean upper;
        if (product.contains("postgresql")) {
            sql = """
                    select count(*) from (
                        select c.conname as n from pg_constraint c
                          join pg_class t on t.oid = c.conrelid
                          join pg_namespace ns on ns.oid = t.relnamespace
                         where c.contype = 'u' and t.relname = ? and ns.nspname = current_schema()
                        union all
                        select i.indexname as n from pg_indexes i
                         where i.tablename = ? and i.schemaname = current_schema()
                    ) found where found.n = ?
                    """;
            upper = false;
        } else if (product.contains("h2")) {
            sql = """
                    select count(*) from (
                        select constraint_name as n from information_schema.table_constraints
                         where table_name = ? and table_schema = schema()
                           and constraint_type in ('UNIQUE', 'PRIMARY KEY')
                        union all
                        select index_name as n from information_schema.indexes
                         where table_name = ? and table_schema = schema()
                    ) found where found.n = ?
                    """;
            upper = true;
        } else {
            // An engine nobody here knows how to ask reads as "cannot tell", not as "missing":
            // shouting about an index that is probably there helps nobody (A5).
            return true;
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, upper ? table.toUpperCase(Locale.ROOT) : table);
            statement.setString(2, upper ? table.toUpperCase(Locale.ROOT) : table);
            statement.setString(3, upper ? name.toUpperCase(Locale.ROOT) : name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) > 0;
            }
        }
    }
}
