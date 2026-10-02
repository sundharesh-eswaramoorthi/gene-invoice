package com.geneinvoice.automation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The earlier build's automation tables with rows in them (MIG-R2-1), as suites/migration/data/legacy-automation.sql
 * makes them: before Hibernate's DDL, every row this build cannot read, and every row of a table that lacks a
 * column this build requires, is moved to {@code <table>_legacy}, so that the NOT NULL columns can then be added.
 */
class LegacyAutomationRowsTest {

    private final DriverManagerDataSource dataSource =
            new DriverManagerDataSource("jdbc:h2:mem:legacyrows;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    @BeforeEach
    void emptyDatabase() {
        jdbc.execute("drop all objects");
    }

    @Test
    void everyRowOfTablesMissingARequiredColumnIsMovedAsideSoTheColumnsCanBeAdded() {
        legacyTables();

        new LegacyAutomationRows(dataSource).archiveAll();

        assertCounts();
        assertThat(jdbc.queryForList("select idempotency_key from automation_events_legacy order by idempotency_key",
                String.class)).containsExactly("legacy-k1", "legacy-k2", "legacy-k3");
        assertThat(jdbc.queryForList("select status from automation_events_legacy where idempotency_key = 'legacy-k2'",
                String.class)).containsExactly("DONE");
        assertThat(jdbc.queryForList("select name from automation_rules_legacy order by name", String.class))
                .containsExactly("Legacy customer follow-up", "Legacy daily reminder");

        // What ddl-auto: update then does, which Postgres refuses on a table that still has rows.
        assertThatCode(() -> jdbc.execute("alter table automation_events add column claims integer not null"))
                .doesNotThrowAnyException();
        assertThatCode(() -> jdbc.execute("alter table automation_rules add column actions_json text not null"))
                .doesNotThrowAnyException();

        new LegacyAutomationRows(dataSource).archiveAll();
        assertCounts();
    }

    @Test
    void aRunCutShortEarlierIsFinished() {
        legacyTables();
        jdbc.execute("create table automation_events_legacy as select * from automation_events where 1 = 0");
        jdbc.update("insert into automation_events_legacy select * from automation_events where status <> 'DONE'");
        jdbc.update("delete from automation_events where status <> 'DONE'");
        assertThat(jdbc.queryForObject("select count(*) from automation_events_legacy", Long.class)).isEqualTo(2L);

        new LegacyAutomationRows(dataSource).archive(LegacyAutomationRows.EVENTS);

        assertThat(jdbc.queryForObject("select count(*) from automation_events", Long.class)).isZero();
        assertThat(jdbc.queryForList("select idempotency_key from automation_events_legacy order by idempotency_key",
                String.class)).containsExactly("legacy-k1", "legacy-k2", "legacy-k3");
    }

    @Test
    void anArchiveMadeWhenTheLiveTableHadMoreColumnsStillTakesRows() {
        legacyTables();
        jdbc.execute("alter table automation_events add column kind varchar(20) not null default 'CREATED'");
        jdbc.execute("create table automation_events_legacy as select * from automation_events where 1 = 0");
        jdbc.execute("alter table automation_events_legacy alter column kind set not null");
        jdbc.execute("alter table automation_events drop column kind");

        new LegacyAutomationRows(dataSource).archive(LegacyAutomationRows.EVENTS);

        assertThat(jdbc.queryForObject("select count(*) from automation_events", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from automation_events_legacy", Long.class)).isEqualTo(3L);
    }

    @Test
    void withAllRequiredColumnsOnlyTheRowsThisBuildCannotReadAreMoved() {
        legacyTables();
        for (String column : List.of("kind varchar(20)", "occurred_at timestamp with time zone", "depth integer",
                "claims integer")) {
            jdbc.execute("alter table automation_events add column " + column);
        }
        jdbc.execute("alter table automation_events drop constraint automation_events_status_check");
        jdbc.update("update automation_events set kind = 'CREATED', occurred_at = current_timestamp, depth = 0,"
                + " claims = 0");
        jdbc.update("insert into automation_events (attempts, entity_id, entity_type, idempotency_key, status,"
                + " trigger_kind, kind, occurred_at, depth, claims) values (0, 9, 'CUSTOMER', 'current', 'PENDING',"
                + " 'CREATED', 'UPDATED', current_timestamp, 0, 0)");
        jdbc.update("insert into automation_events (attempts, entity_id, entity_type, idempotency_key, status,"
                + " trigger_kind, kind, occurred_at, depth, claims) values (0, 9, 'CUSTOMER', 'no-kind', 'PENDING',"
                + " 'CREATED', null, current_timestamp, 0, 0)");

        new LegacyAutomationRows(dataSource).archive(LegacyAutomationRows.EVENTS);

        // QUEUED and FAILED are not this build's statuses; DONE is, and has a kind now.
        assertThat(jdbc.queryForList("select idempotency_key from automation_events order by idempotency_key",
                String.class)).containsExactly("current", "legacy-k2");
        assertThat(jdbc.queryForList("select idempotency_key from automation_events_legacy order by idempotency_key",
                String.class)).containsExactly("legacy-k1", "legacy-k3", "no-kind");
    }

    @Test
    void tablesWithoutAnEarlierBuildsColumnAreLeftAlone() {
        jdbc.execute("""
                create table automation_events (id bigint generated by default as identity primary key,
                    kind varchar(20), status varchar(12) not null, attempts integer not null)""");
        jdbc.update("insert into automation_events (kind, status, attempts) values (null, 'QUEUED', 0)");
        jdbc.execute("""
                create table automation_rules (id bigint generated by default as identity primary key,
                    name varchar(120) not null, trigger_kind varchar(30))""");
        jdbc.update("insert into automation_rules (name, trigger_kind) values ('Kept', 'CREATED')");

        assertThatCode(() -> new LegacyAutomationRows(dataSource).archiveAll()).doesNotThrowAnyException();

        assertThat(jdbc.queryForObject("select count(*) from automation_events", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from automation_rules", Long.class)).isEqualTo(1L);
        assertThat(legacyTables("%_LEGACY")).isZero();
    }

    @Test
    void missingTablesAreLeftAlone() {
        assertThatCode(() -> new LegacyAutomationRows(dataSource).archiveAll()).doesNotThrowAnyException();
        assertThat(legacyTables("%")).isZero();
    }

    private long legacyTables(String like) {
        return jdbc.queryForObject("""
                select count(*) from information_schema.tables where table_schema = 'PUBLIC' and table_name like ?""",
                Long.class, like);
    }

    private void assertCounts() {
        assertThat(jdbc.queryForObject("select count(*) from automation_events", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from automation_events_legacy", Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("select count(*) from automation_rules", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from automation_rules_legacy", Long.class)).isEqualTo(2L);
    }

    /** suites/migration/data/legacy-automation.sql, in H2's words. */
    private void legacyTables() {
        jdbc.execute("""
                create table automation_events (
                    id bigint generated by default as identity primary key,
                    attempts integer not null,
                    created_at timestamp(6) with time zone,
                    enqueued_at timestamp(6) with time zone,
                    entity_id bigint not null,
                    entity_type varchar(20) not null,
                    idempotency_key varchar(200) not null,
                    last_error varchar(2000),
                    next_attempt_at timestamp(6) with time zone,
                    rule_id bigint,
                    status varchar(20) not null,
                    trigger_kind varchar(20) not null,
                    updated_at timestamp(6) with time zone,
                    constraint automation_events_entity_type_check
                        check (entity_type in ('CUSTOMER', 'INVOICE', 'PAYMENT')),
                    constraint automation_events_status_check
                        check (status in ('QUEUED', 'RUNNING', 'DONE', 'SKIPPED', 'FAILED')),
                    constraint automation_events_trigger_kind_check
                        check (trigger_kind in ('CREATED', 'UPDATED', 'DAILY', 'WEEKLY')))""");
        jdbc.execute("""
                create table automation_rules (
                    id bigint generated by default as identity primary key,
                    action varchar(20) not null,
                    action_json text,
                    created_at timestamp(6) with time zone,
                    created_by_user_id bigint,
                    description varchar(1000),
                    enabled boolean not null,
                    entity_type varchar(20) not null,
                    filters_json text,
                    last_run_at timestamp(6) with time zone,
                    name varchar(200) not null,
                    run_count bigint not null,
                    trigger_kind varchar(20) not null,
                    updated_at timestamp(6) with time zone,
                    constraint automation_rules_action_check
                        check (action in ('CREATE_TASK', 'CREATE_PROMISE', 'CREATE_DISPUTE', 'SEND_EMAIL')),
                    constraint automation_rules_entity_type_check
                        check (entity_type in ('CUSTOMER', 'INVOICE', 'PAYMENT')),
                    constraint automation_rules_trigger_kind_check
                        check (trigger_kind in ('CREATED', 'UPDATED', 'DAILY', 'WEEKLY')))""");
        jdbc.update("""
                insert into automation_rules (action, action_json, created_at, created_by_user_id, description,
                    enabled, entity_type, filters_json, last_run_at, name, run_count, trigger_kind, updated_at) values
                 ('CREATE_TASK', '{"title":"Legacy follow-up"}', current_timestamp, 1, 'legacy rule', true,
                  'CUSTOMER', '[]', current_timestamp, 'Legacy customer follow-up', 3, 'CREATED', current_timestamp),
                 ('SEND_EMAIL', '{"subject":"Legacy reminder"}', current_timestamp, 1, null, false, 'INVOICE',
                  '[{"field":"status","op":"eq","value":"UNPAID"}]', null, 'Legacy daily reminder', 0, 'DAILY',
                  current_timestamp)""");
        jdbc.update("""
                insert into automation_events (attempts, created_at, enqueued_at, entity_id, entity_type,
                    idempotency_key, last_error, next_attempt_at, rule_id, status, trigger_kind, updated_at) values
                 (0, current_timestamp, current_timestamp, 1, 'CUSTOMER', 'legacy-k1', null, current_timestamp, 1,
                  'QUEUED', 'CREATED', current_timestamp),
                 (1, current_timestamp, current_timestamp, 1, 'INVOICE', 'legacy-k2', null, null, 2, 'DONE', 'DAILY',
                  current_timestamp),
                 (3, current_timestamp, current_timestamp, 2, 'CUSTOMER', 'legacy-k3', 'boom', null, 1, 'FAILED',
                  'UPDATED', current_timestamp)""");
    }
}
