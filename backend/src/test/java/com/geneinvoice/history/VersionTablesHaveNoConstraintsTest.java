package com.geneinvoice.history;

import com.geneinvoice.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing in a version table can refuse a row: a version is written just before commit, where a refusal would
 * roll back the user's own save. So no foreign key, no unique constraint, no CHECK and no ENUM column type;
 * only the version id as the primary key (§3.5, D3).
 */
class VersionTablesHaveNoConstraintsTest extends IntegrationTestBase {

    static final List<String> VERSION_TABLES = List.of(
            "CUSTOMER_POC_VERSIONS", "CUSTOMER_VERSIONS", "DISPUTE_VERSIONS", "ENTITY_LINK_VERSIONS",
            "INVOICE_VERSIONS", "PAYMENT_ALLOCATION_VERSIONS", "PAYMENT_VERSIONS", "PRODUCT_VERSIONS",
            "PROMISE_VERSIONS", "TASK_VERSIONS");

    @Autowired JdbcTemplate jdbc;

    @Test
    void theVersionTablesAreTheOnesTheModelNames() {
        assertThat(jdbc.queryForList("""
                select table_name from information_schema.tables
                 where table_schema = 'PUBLIC' and table_name like '%\\_VERSIONS' escape '\\'
                 order by table_name""", String.class))
                .containsExactlyElementsOf(VERSION_TABLES);
        // A count of statements against a live table (CountingStatements) must never also count its versions.
        for (String live : jdbc.queryForList("""
                select table_name from information_schema.tables
                 where table_schema = 'PUBLIC' and table_name not like '%\\_VERSIONS' escape '\\'""", String.class)) {
            assertThat(VERSION_TABLES).as(live).noneMatch(table -> table.contains(live));
        }
    }

    @Test
    void eachVersionTableHasOnlyItsPrimaryKey() {
        for (String table : VERSION_TABLES) {
            List<Map<String, Object>> constraints = jdbc.queryForList("""
                    select constraint_type, constraint_name from information_schema.table_constraints
                     where table_schema = 'PUBLIC' and table_name = ?""", table);
            assertThat(constraints).as(table).extracting(c -> c.get("CONSTRAINT_TYPE")).containsExactly("PRIMARY KEY");
            assertThat(jdbc.queryForList("""
                    select k.column_name from information_schema.key_column_usage k
                      join information_schema.table_constraints c
                        on c.constraint_schema = k.constraint_schema and c.constraint_name = k.constraint_name
                     where c.table_schema = 'PUBLIC' and c.table_name = ? and c.constraint_type = 'PRIMARY KEY'""",
                    String.class, table)).as(table).containsExactly("VERSION_ID");
        }
    }

    @Test
    void noOtherTableHasAForeignKeyIntoAVersionTable() {
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.referential_constraints r
                  join information_schema.table_constraints c
                    on c.constraint_schema = r.unique_constraint_schema and c.constraint_name = r.unique_constraint_name
                 where c.table_name like '%\\_VERSIONS' escape '\\'""", Long.class)).isZero();
    }

    @Test
    void noVersionTableHasAUniqueIndex() {
        assertThat(jdbc.queryForList("""
                select table_name || '.' || index_name from information_schema.indexes
                 where table_schema = 'PUBLIC' and table_name like '%\\_VERSIONS' escape '\\'
                   and index_type_name <> 'PRIMARY KEY' and index_type_name <> 'INDEX'""", String.class))
                .isEmpty();
    }

    @Test
    void enumsAreStoredAsPlainTextAndOnlyTheWindowAndLinkColumnsAreRequired() {
        assertThat(jdbc.queryForList("""
                select table_name || '.' || column_name from information_schema.columns
                 where table_schema = 'PUBLIC' and table_name like '%\\_VERSIONS' escape '\\'
                   and data_type = 'ENUM'""", String.class))
                .isEmpty();
        assertThat(jdbc.queryForList("""
                select table_name || '.' || column_name || ' ' || data_type from information_schema.columns
                 where table_schema = 'PUBLIC' and column_name in ('STATUS', 'PAYMENT_TERM', 'POC_TYPE', 'TARGET_TYPE', 'ENTITY_TYPE')
                   and table_name like '%\\_VERSIONS' escape '\\'
                 order by 1""", String.class))
                .containsExactly(
                        "CUSTOMER_POC_VERSIONS.POC_TYPE CHARACTER VARYING",
                        "CUSTOMER_VERSIONS.PAYMENT_TERM CHARACTER VARYING",
                        "DISPUTE_VERSIONS.STATUS CHARACTER VARYING",
                        "DISPUTE_VERSIONS.TARGET_TYPE CHARACTER VARYING",
                        "INVOICE_VERSIONS.PAYMENT_TERM CHARACTER VARYING",
                        "INVOICE_VERSIONS.STATUS CHARACTER VARYING",
                        "PAYMENT_VERSIONS.STATUS CHARACTER VARYING",
                        "PROMISE_VERSIONS.STATUS CHARACTER VARYING",
                        "TASK_VERSIONS.ENTITY_TYPE CHARACTER VARYING",
                        "TASK_VERSIONS.STATUS CHARACTER VARYING");

        for (String table : VERSION_TABLES) {
            List<String> required = jdbc.queryForList("""
                    select column_name from information_schema.columns
                     where table_schema = 'PUBLIC' and table_name = ? and is_nullable = 'NO'
                     order by column_name""", String.class, table);
            if (table.equals("ENTITY_LINK_VERSIONS")) {
                assertThat(required).as(table).containsExactly(
                        "KIND", "ORIGIN", "OWNER_ID", "TARGET_ID", "TX_ID", "VALID_FROM", "VALID_TO", "VERSION_ID");
            } else {
                assertThat(required).as(table).containsExactly(
                        "ENTITY_ID", "ORIGIN", "TX_ID", "VALID_FROM", "VALID_TO", "VERSION_ID");
            }
        }
    }

    @Test
    void everyVersionTableIsIndexedForItsCurrentRowAndForAPoint() {
        for (String table : VERSION_TABLES) {
            List<String> indexed = jdbc.queryForList("""
                    select i.index_name || ':' || listagg(c.column_name, ',') within group (order by c.ordinal_position)
                      from information_schema.indexes i
                      join information_schema.index_columns c
                        on c.index_schema = i.index_schema and c.index_name = i.index_name
                     where i.table_schema = 'PUBLIC' and i.table_name = ?
                     group by i.index_name""", String.class, table);
            String prefix = "IDX_" + table + "_";
            assertThat(indexed).as(table).contains(prefix + "WINDOW:VALID_TO,VALID_FROM");
            if (table.equals("ENTITY_LINK_VERSIONS")) {
                assertThat(indexed).as(table).contains(prefix + "OWNER:KIND,OWNER_ID,VALID_TO",
                        prefix + "TARGET:KIND,TARGET_ID,VALID_TO");
            } else {
                assertThat(indexed).as(table).contains(prefix + "ENTITY:ENTITY_ID,VALID_TO");
            }
        }
        assertThat(jdbc.queryForList("""
                select index_name from information_schema.indexes
                 where table_schema = 'PUBLIC' and table_name = 'INVOICE_VERSIONS'
                 order by index_name""", String.class))
                .contains("IDX_INVOICE_VERSIONS_CUSTOMER", "IDX_INVOICE_VERSIONS_REGION",
                        "IDX_INVOICE_VERSIONS_SALES_POC");
        assertThat(jdbc.queryForList("""
                select index_name from information_schema.indexes
                 where table_schema = 'PUBLIC' and table_name = 'TASK_VERSIONS'
                 order by index_name""", String.class))
                .contains("IDX_TASK_VERSIONS_CUSTOMER", "IDX_TASK_VERSIONS_REGION", "IDX_TASK_VERSIONS_RECORD");
    }

    @Test
    void historyMetaIsOneRowKeyedBySmallint() {
        assertThat(jdbc.queryForList("""
                select constraint_type from information_schema.table_constraints
                 where table_schema = 'PUBLIC' and table_name = 'HISTORY_META'""", String.class))
                .containsExactly("PRIMARY KEY");
        assertThat(jdbc.queryForList("""
                select column_name || ' ' || data_type || ' ' || is_nullable from information_schema.columns
                 where table_schema = 'PUBLIC' and table_name = 'HISTORY_META' order by column_name""", String.class))
                .containsExactly(
                        "BASELINE_COMPLETED_AT TIMESTAMP WITH TIME ZONE YES",
                        "ID SMALLINT NO",
                        "STARTED_AT TIMESTAMP WITH TIME ZONE YES");
    }
}
