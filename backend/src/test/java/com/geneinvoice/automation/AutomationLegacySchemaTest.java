package com.geneinvoice.automation;

import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.rule.AutomationRule;
import com.geneinvoice.automation.rule.TriggerKind;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A database where an earlier build already made {@code automation_events} and {@code automation_rules}
 * (MIG-R1-1, MIG-R1-2): {@code ddl-auto: update} only added the new columns, and that build's NOT NULL columns and
 * CHECKs refused every outbox row, rolling back the user's save, and every new rule. The earlier build's shape is
 * put on the live schema, the upgrade runs again, and the application works; everything is put back afterwards.
 */
class AutomationLegacySchemaTest extends AutomationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired AutomationSchemaUpgrade upgrade;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired ApplicationContext ctx;

    /** The live schema before the test changed it: the test leaves it exactly so. */
    String shapeBefore;

    @BeforeEach
    void remember() {
        shapeBefore = shape();
    }

    @AfterEach
    void restore() {
        jdbc.execute("alter table automation_events drop constraint if exists automation_events_status_check");
        jdbc.execute("alter table automation_events drop column if exists idempotency_key");
        jdbc.execute("delete from automation_events");
        jdbc.execute("alter table automation_events add column if not exists kind varchar(20)");
        jdbc.execute("alter table automation_events alter column kind set not null");
        jdbc.execute("drop table if exists automation_events_legacy");
        jdbc.execute("alter table automation_rules drop constraint if exists automation_rules_trigger_kind_check");
        jdbc.execute("alter table automation_rules drop column if exists action");
        jdbc.execute("drop table if exists automation_rules_legacy");
        if (!"ENUM".equals(jdbc.queryForObject("""
                select data_type from information_schema.columns
                 where table_schema = 'PUBLIC' and table_name = 'AUTOMATION_RULES' and column_name = 'TRIGGER_KIND'""",
                String.class))) {
            jdbc.execute("alter table automation_rules alter column trigger_kind set data type enum ("
                    + Arrays.stream(TriggerKind.values()).map(k -> "'" + k.name() + "'")
                            .collect(Collectors.joining(", ")) + ")");
        }
        assertThat(shape()).as("the live schema is as the test found it").isEqualTo(shapeBefore);
    }

    @Test
    void theMappingRequiresExactlyTheOutboxColumnsEveryRowHas() {
        Map<String, Boolean> mapped = AutomationSchemaUpgrade.mappedColumns(
                entityManagerFactory, AutomationEvent.class, AutomationSchemaUpgrade.EVENTS);
        // The same list OutboxSchemaTest.onlyTheColumnsEveryRowHasAreRequired holds the live table to.
        assertThat(mapped.entrySet().stream().filter(c -> !c.getValue()).map(Map.Entry::getKey).sorted())
                .containsExactly("attempts", "claims", "depth", "id", "kind", "occurred_at", "status");
        assertThat(mapped).containsKeys("entity_id", "entity_type", "history_tx_id", "record_ids", "region_ids");
    }

    @Test
    void theLegacyRowsAreMovedAsideBeforeHibernatesSchemaUpdate() {
        // MIG-R2-1: ddl-auto cannot add a NOT NULL column to a table that still has the earlier build's rows.
        String[] dependsOn = ((ConfigurableListableBeanFactory) ctx.getAutowireCapableBeanFactory())
                .getBeanDefinition("entityManagerFactory").getDependsOn();
        assertThat(dependsOn).contains("automationLegacyRows");
    }

    @Test
    void everyRequiredColumnTheEarlierTablesLackedIsOneTheArchiveChecksFor() {
        // The columns the earlier build's tables already had as NOT NULL-able; any other required column must be
        // in REQUIRED, or rows of that build would stay and ddl-auto could not add it (MIG-R2-1).
        Set<String> alwaysThere = Set.of("id", "enabled", "name", "created_at", "updated_at");
        Map<String, Class<?>> types = Map.of(
                AutomationSchemaUpgrade.EVENTS, AutomationEvent.class, AutomationSchemaUpgrade.RULES, AutomationRule.class);
        types.forEach((table, type) -> {
            Map<String, Boolean> mapped = AutomationSchemaUpgrade.mappedColumns(entityManagerFactory, type, table);
            assertThat(mapped).as(table).isNotEmpty();
            mapped.forEach((column, nullable) -> {
                if (!nullable) {
                    assertThat(LegacyAutomationRows.REQUIRED.get(table).contains(column)
                            || alwaysThere.contains(column)).as(table + "." + column).isTrue();
                }
            });
            assertThat(mapped).as(table).containsKeys(LegacyAutomationRows.REQUIRED.get(table).toArray(String[]::new));
        });
    }

    @Test
    void savesAndNewRulesWorkOnTablesAnEarlierBuildMade() throws Exception {
        User admin = userRepository.findByUsername("admin").orElseThrow();
        Customer acme = customer("Acme");
        legacyShape();
        assertThat(nullable("AUTOMATION_EVENTS", "IDEMPOTENCY_KEY")).isEqualTo("NO");

        upgrade.afterPropertiesSet();

        send(put("/api/customers/" + acme.getId()), admin, Map.of("name", "Acme Holdings"))
                .andExpect(status().isOk());
        assertThat(customerRepository.findById(acme.getId()).orElseThrow().getName()).isEqualTo("Acme Holdings");
        assertThat(events(AutomationEvent.UPDATED)).anySatisfy(e -> {
            assertThat(e.getEntityId()).isEqualTo(acme.getId());
            assertThat(e.getStatus()).isEqualTo(AutomationEvent.PENDING);
        });

        Long ruleId = saveRule(admin, rule("After the upgrade", "CUSTOMER", "MANUAL", null,
                task("Migrated {{Customer.Name}}")));
        assertThat(automationRuleRepository.findById(ruleId)).isPresent();
        Long longest = saveRule(admin, rule("Longest trigger", "CUSTOMER", "ENTITY_CREATED_OR_UPDATED", null,
                task("Check {{Customer.Name}}")));
        assertThat(automationRuleRepository.findById(longest).orElseThrow().getTriggerKind().name())
                .isEqualTo("ENTITY_CREATED_OR_UPDATED");

        assertThat(nullable("AUTOMATION_EVENTS", "IDEMPOTENCY_KEY")).isEqualTo("YES");
        assertThat(nullable("AUTOMATION_RULES", "ACTION")).isEqualTo("YES");
        assertThat(checks("AUTOMATION_EVENTS")).isEmpty();
    }

    @Test
    void rowsTheMappingCannotReadAreMovedAsideNotLost() {
        legacyShape();
        // An earlier build's row, and one whose status both builds share (the old CHECK allows both).
        jdbc.update("""
                insert into automation_events (kind, entity_type, entity_id, status, attempts, claims, depth,
                                               occurred_at, idempotency_key)
                values ('CREATED', 'CUSTOMER', 42, 'QUEUED', 0, 0, 0, current_timestamp, 'legacy-1')""");
        jdbc.update("""
                insert into automation_events (kind, entity_type, entity_id, status, attempts, claims, depth,
                                               occurred_at, idempotency_key)
                values ('UPDATED', 'CUSTOMER', 43, 'DONE', 0, 0, 0, current_timestamp, 'current-1')""");

        upgrade.afterPropertiesSet();

        assertThat(jdbc.queryForList("select idempotency_key from automation_events", String.class))
                .containsExactly("current-1");
        assertThat(jdbc.queryForList("select idempotency_key || ':' || status from automation_events_legacy",
                String.class)).containsExactly("legacy-1:QUEUED");

        upgrade.afterPropertiesSet();
        assertThat(jdbc.queryForObject("select count(*) from automation_events_legacy", Long.class))
                .as("a second run moves nothing again").isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from automation_events", Long.class)).isEqualTo(1L);

        // The earlier build's table as ddl-auto left it when it could not add kind (MIG-R2-1): no row can be read,
        // the DONE one included, and a row left behind would stop the next boot adding the column.
        jdbc.execute("alter table automation_events drop column kind");
        jdbc.update("""
                insert into automation_events (entity_type, entity_id, status, attempts, claims, depth,
                                               occurred_at, idempotency_key)
                values ('INVOICE', 44, 'DONE', 1, 0, 0, current_timestamp, 'legacy-k2')""");

        upgrade.afterPropertiesSet();

        assertThat(jdbc.queryForObject("select count(*) from automation_events", Long.class)).isZero();
        assertThat(jdbc.queryForList("select idempotency_key || ':' || status from automation_events_legacy",
                String.class)).containsExactlyInAnyOrder("legacy-1:QUEUED", "current-1:DONE", "legacy-k2:DONE");
    }

    @Test
    void onASchemaThisBuildMadeTheUpgradeChangesNothing() {
        String before = shape();

        upgrade.afterPropertiesSet();
        upgrade.afterPropertiesSet();

        assertThat(shape()).isEqualTo(before);
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.tables
                 where table_schema = 'PUBLIC' and table_name like 'AUTOMATION_%_LEGACY'""", Long.class)).isZero();
    }

    /** What the earlier build left, as MIG-A-40/41 found it on the dev database (dev.sql l.112-190). */
    private void legacyShape() {
        jdbc.execute("alter table automation_events add column idempotency_key varchar(200) default 'x' not null");
        jdbc.execute("alter table automation_events alter column idempotency_key drop default");
        jdbc.execute("alter table automation_events add constraint automation_events_status_check"
                + " check (status in ('QUEUED','DONE')) nocheck");
        jdbc.execute("alter table automation_rules add column action varchar(20) default 'CREATE_TASK' not null");
        jdbc.execute("alter table automation_rules alter column action drop default");
        // Postgres keeps the enum as varchar under a CHECK; H2's native ENUM type would refuse the old names.
        // The earlier build's width, too narrow for ENTITY_CREATED_OR_UPDATED.
        jdbc.execute("alter table automation_rules alter column trigger_kind set data type varchar(20)");
        jdbc.execute("alter table automation_rules add constraint automation_rules_trigger_kind_check"
                + " check (trigger_kind in ('CREATED','UPDATED','DAILY','WEEKLY')) nocheck");
    }

    private String nullable(String table, String column) {
        return jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                 where table_schema = 'PUBLIC' and table_name = ? and column_name = ?""", String.class, table, column);
    }

    private List<String> checks(String table) {
        return jdbc.queryForList("""
                select constraint_name from information_schema.table_constraints
                 where table_schema = 'PUBLIC' and table_name = ? and constraint_type = 'CHECK'""",
                String.class, table);
    }

    /** Every automation table's columns, types, nullability and constraints. */
    private String shape() {
        String columns = jdbc.queryForList("""
                select table_name || '.' || column_name || ' ' || data_type || ' ' || is_nullable
                       || ' ' || coalesce(cast(character_maximum_length as varchar), '')
                  from information_schema.columns
                 where table_schema = 'PUBLIC' and table_name like 'AUTOMATION%'
                 order by table_name, column_name""", String.class).stream().collect(Collectors.joining("\n"));
        String constraints = jdbc.queryForList("""
                select tc.table_name || ' ' || tc.constraint_type || ' ' || coalesce(cc.check_clause, '')
                  from information_schema.table_constraints tc
                  left join information_schema.check_constraints cc
                    on cc.constraint_schema = tc.constraint_schema and cc.constraint_name = tc.constraint_name
                 where tc.table_schema = 'PUBLIC' and tc.table_name like 'AUTOMATION%'
                 order by 1""", String.class).stream().collect(Collectors.joining("\n"));
        return columns + "\n" + constraints;
    }
}
