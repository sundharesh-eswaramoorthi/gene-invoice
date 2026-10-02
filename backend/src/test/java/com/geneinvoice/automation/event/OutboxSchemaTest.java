package com.geneinvoice.automation.event;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.common.origin.WriteOrigin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing in the outbox can refuse a row (P2, R5): its rows are inserted just before the user's save commits, where
 * a refusal would roll the save back. So no foreign key, no unique constraint, no CHECK and no ENUM column type;
 * every text the application fits is cut to no more than its column holds; identifier lists are unbounded
 * (critic finding PC-12). The same holds for the history instants the outbox points at.
 */
class OutboxSchemaTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void theOutboxHasOnlyItsPrimaryKey() {
        for (String table : List.of("AUTOMATION_EVENTS", "HISTORY_TRANSACTIONS")) {
            assertThat(jdbc.queryForList("""
                    select constraint_type from information_schema.table_constraints
                     where table_schema = 'PUBLIC' and table_name = ?""", String.class, table))
                    .as(table).containsExactly("PRIMARY KEY");
            assertThat(jdbc.queryForList("""
                    select k.column_name from information_schema.key_column_usage k
                      join information_schema.table_constraints c
                        on c.constraint_schema = k.constraint_schema and c.constraint_name = k.constraint_name
                     where c.table_schema = 'PUBLIC' and c.table_name = ? and c.constraint_type = 'PRIMARY KEY'""",
                    String.class, table)).as(table).containsExactly("ID");
            assertThat(jdbc.queryForList("""
                    select index_name from information_schema.indexes
                     where table_schema = 'PUBLIC' and table_name = ?
                       and index_type_name <> 'PRIMARY KEY' and index_type_name <> 'INDEX'""", String.class, table))
                    .as(table + ": no unique index").isEmpty();
            assertThat(jdbc.queryForObject("""
                    select count(*) from information_schema.referential_constraints r
                      join information_schema.table_constraints c
                        on c.constraint_schema = r.unique_constraint_schema
                       and c.constraint_name = r.unique_constraint_name
                     where c.table_name = ?""", Long.class, table)).as(table + ": nothing points at it").isZero();
        }
    }

    @Test
    void noColumnIsAnEnumType() {
        assertThat(jdbc.queryForList("""
                select column_name || ' ' || data_type from information_schema.columns
                 where table_schema = 'PUBLIC' and table_name = 'AUTOMATION_EVENTS'
                   and column_name in ('KIND', 'ENTITY_TYPE', 'ORIGIN', 'STATUS')
                 order by column_name""", String.class))
                .containsExactly(
                        "ENTITY_TYPE CHARACTER VARYING",
                        "KIND CHARACTER VARYING",
                        "ORIGIN CHARACTER VARYING",
                        "STATUS CHARACTER VARYING");
        assertThat(jdbc.queryForList("""
                select column_name from information_schema.columns
                 where table_schema = 'PUBLIC' and table_name in ('AUTOMATION_EVENTS', 'HISTORY_TRANSACTIONS')
                   and data_type = 'ENUM'""", String.class)).isEmpty();
    }

    @Test
    void everyTextTheApplicationWritesFitsItsColumn() {
        Map<String, Integer> fitted = Map.of(
                "CHANGES", AutomationEvent.CHANGES_LENGTH,
                "CLAIMED_BY", AutomationEvent.CLAIMED_BY_LENGTH,
                "LAST_ERROR", AutomationEvent.LAST_ERROR_LENGTH);
        fitted.forEach((column, fitTo) ->
                assertThat(length("AUTOMATION_EVENTS", column)).as(column).isGreaterThanOrEqualTo(fitTo));

        // The vocabularies written unfitted are all shorter than their columns.
        assertThat(length("AUTOMATION_EVENTS", "KIND")).isGreaterThanOrEqualTo(longest(Stream.of(
                AutomationEvent.CREATED, AutomationEvent.UPDATED, AutomationEvent.CHILDREN, AutomationEvent.SCHEDULED,
                AutomationEvent.MANUAL, AutomationEvent.REPLAY)));
        assertThat(length("AUTOMATION_EVENTS", "STATUS")).isGreaterThanOrEqualTo(longest(Stream.of(
                AutomationEvent.PENDING, AutomationEvent.PROCESSING, AutomationEvent.DONE, AutomationEvent.DEAD)));
        assertThat(length("AUTOMATION_EVENTS", "ENTITY_TYPE"))
                .isGreaterThanOrEqualTo(longest(Arrays.stream(TriggerEntity.values()).map(Enum::name)));
        assertThat(length("AUTOMATION_EVENTS", "ORIGIN"))
                .isGreaterThanOrEqualTo(longest(Arrays.stream(WriteOrigin.Kind.values()).map(Enum::name)));
        // A UUID, never fitted.
        assertThat(length("AUTOMATION_EVENTS", "HISTORY_TX_ID")).isEqualTo(36);
        assertThat(length("HISTORY_TRANSACTIONS", "TX_ID")).isEqualTo(36);
    }

    @Test
    void identifierListsAreUnboundedText() {
        // The same physical type as the audit trail's TEXT columns, which hold whole records.
        Map<String, Object> text = column("AUDIT_LOGS", "AFTER_JSON");
        for (String column : List.of("RECORD_IDS", "REGION_IDS")) {
            Map<String, Object> ids = column("AUTOMATION_EVENTS", column);
            assertThat(ids.get("DATA_TYPE")).as(column).isEqualTo(text.get("DATA_TYPE"));
            assertThat(ids.get("CHARACTER_MAXIMUM_LENGTH")).as(column).isEqualTo(text.get("CHARACTER_MAXIMUM_LENGTH"));
            assertThat(((Number) ids.get("CHARACTER_MAXIMUM_LENGTH")).longValue()).as(column)
                    .isGreaterThan(1_000_000L);
        }
    }

    @Test
    void onlyTheColumnsEveryRowHasAreRequired() {
        assertThat(jdbc.queryForList("""
                select column_name from information_schema.columns
                 where table_schema = 'PUBLIC' and table_name = 'AUTOMATION_EVENTS' and is_nullable = 'NO'
                 order by column_name""", String.class))
                .containsExactly("ATTEMPTS", "CLAIMS", "DEPTH", "ID", "KIND", "OCCURRED_AT", "STATUS");
        assertThat(jdbc.queryForList("""
                select column_name || ' ' || data_type || ' ' || is_nullable from information_schema.columns
                 where table_schema = 'PUBLIC' and table_name = 'HISTORY_TRANSACTIONS' order by column_name""",
                String.class))
                .containsExactly(
                        "ID BIGINT NO",
                        "INSTANT TIMESTAMP WITH TIME ZONE NO",
                        "TX_ID CHARACTER VARYING NO");
    }

    @Test
    void theConsumersLookupsAreIndexed() {
        List<String> indexed = jdbc.queryForList("""
                select i.index_name || ':' || listagg(c.column_name, ',') within group (order by c.ordinal_position)
                  from information_schema.indexes i
                  join information_schema.index_columns c
                    on c.index_schema = i.index_schema and c.index_name = i.index_name
                 where i.table_schema = 'PUBLIC' and i.table_name in ('AUTOMATION_EVENTS', 'HISTORY_TRANSACTIONS')
                 group by i.index_name""", String.class);
        assertThat(indexed).contains(
                "IDX_AUTOMATION_EVENT_DUE:STATUS,NEXT_ATTEMPT_AT",
                "IDX_AUTOMATION_EVENT_ENTITY:ENTITY_TYPE,ENTITY_ID,STATUS",
                "IDX_AUTOMATION_EVENT_RULE:RULE_ID,STATUS",
                "IDX_AUTOMATION_EVENT_OCCURRED:ENTITY_TYPE,OCCURRED_AT",
                "IDX_HISTORY_TX:TX_ID");
    }

    private int length(String table, String column) {
        return ((Number) column(table, column).get("CHARACTER_MAXIMUM_LENGTH")).intValue();
    }

    private Map<String, Object> column(String table, String column) {
        return jdbc.queryForMap("""
                select data_type, character_maximum_length from information_schema.columns
                 where table_schema = 'PUBLIC' and table_name = ? and column_name = ?""", table, column);
    }

    private static int longest(Stream<String> words) {
        return words.mapToInt(String::length).max().orElseThrow();
    }
}
