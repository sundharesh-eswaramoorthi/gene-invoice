package com.geneinvoice.common;

import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

// One home for the raw-JDBC schema helpers every *SchemaUpgrade needs, so the region, approval,
// automation and history upgrades all call the same tested code instead of keeping four copies
// that drift apart the first time a dialect branch changes (B1, B2, A1, B3 INTEGRATION).
@Slf4j
public final class SchemaSupport {

    private SchemaSupport() {}

    // ddl-auto:update never modifies a check constraint it has already created, so an enum constant
    // added on a later deploy is rejected at insert time by the constraint generated when the column
    // was created. Moved from EmailSchemaUpgrade (B1, B2, A1, B3 INTEGRATION).
    public static void widen(Connection connection, String table, String column, Class<? extends Enum<?>> type)
            throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        List<String> values = Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
        Map<String, String> checks;
        String columnInCheck;
        if (product.contains("postgresql")) {
            checks = query(connection, """
                    select c.conname, pg_get_constraintdef(c.oid) from pg_constraint c
                      join pg_class t on t.oid = c.conrelid
                      join pg_namespace n on n.oid = t.relnamespace
                     where c.contype = 'c' and t.relname = ? and n.nspname = current_schema()
                    """, table);
            columnInCheck = "(" + column + ")";
        } else if (product.contains("h2")) {
            checks = query(connection, """
                    select tc.constraint_name, cc.check_clause from information_schema.table_constraints tc
                      join information_schema.check_constraints cc
                        on cc.constraint_schema = tc.constraint_schema and cc.constraint_name = tc.constraint_name
                     where tc.constraint_type = 'CHECK' and tc.table_name = upper(?) and tc.table_schema = schema()
                    """, table);
            columnInCheck = "\"" + column.toUpperCase(Locale.ROOT) + "\"";
        } else {
            return;
        }
        List<String> stale = checks.entrySet().stream()
                .filter(c -> c.getValue().contains(columnInCheck) && c.getValue().contains("'" + values.get(0) + "'"))
                .filter(c -> values.stream().anyMatch(v -> !c.getValue().contains("'" + v + "'")))
                .map(Map.Entry::getKey)
                .toList();
        if (stale.isEmpty()) return;
        String listed = values.stream().map(v -> "'" + v + "'").collect(Collectors.joining(", "));
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String name : stale) {
                statement.execute("alter table " + table + " drop constraint \"" + name + "\"");
            }
            statement.execute("alter table " + table + " add constraint " + table + "_" + column + "_check"
                    + " check (" + column + " in (" + listed + "))");
            connection.commit();
            log.info("Widened the check constraint on {}.{} to {}", table, column, values);
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static Map<String, String> query(Connection connection, String sql, String table) throws SQLException {
        Map<String, String> checks = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) checks.put(rows.getString(1), rows.getString(2));
            }
        }
        return checks;
    }

    // A NOT NULL column can only be added to a populated table in three steps: map it nullable,
    // chunk-backfill it, then tighten it here. H2 wants the column type repeated on the alter and
    // Postgres does not, so the caller passes the H2 spelling (B1, A1 INTEGRATION).
    public static void enforceNotNull(Connection connection, String table, String column, String h2Type)
            throws SQLException {
        String product = product(connection);
        String alter;
        if (product.contains("postgresql")) {
            alter = "alter table " + table + " alter column " + column + " set not null";
        } else if (product.contains("h2")) {
            alter = "alter table " + table + " alter column " + column + " " + h2Type + " not null";
        } else {
            return;
        }
        if (!isNullable(connection, table, column)) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute(alter);
            if (!connection.getAutoCommit()) connection.commit();
            log.info("{}.{} is now not null", table, column);
        } catch (SQLException e) {
            log.warn("Could not make {}.{} not null: {}", table, column, e.getMessage());
        }
    }

    // 'create index if not exists' is understood by both Postgres and H2 (the PocSchemaUpgrade:63
    // idiom), and a missing index is a slow query, never a reason to refuse to start (B1, B2 INTEGRATION).
    public static void indexIfMissing(Connection connection, String name, String table, String columns)
            throws SQLException {
        String product = product(connection);
        if (!product.contains("postgresql") && !product.contains("h2")) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute("create index if not exists " + name + " on " + table + " (" + columns + ")");
            if (!connection.getAutoCommit()) connection.commit();
        } catch (SQLException e) {
            log.warn("Could not create {}: {}", name, e.getMessage());
        }
    }

    public static boolean isNullable(Connection connection, String table, String column) throws SQLException {
        boolean h2 = product(connection).contains("h2");
        try (PreparedStatement statement = connection.prepareStatement("""
                select is_nullable from information_schema.columns
                 where table_name = ? and column_name = ? and table_schema = %s
                """.formatted(h2 ? "schema()" : "current_schema()"))) {
            statement.setString(1, h2 ? table.toUpperCase(Locale.ROOT) : table);
            statement.setString(2, h2 ? column.toUpperCase(Locale.ROOT) : column);
            try (ResultSet rows = statement.executeQuery()) {
                return !rows.next() || !"NO".equalsIgnoreCase(rows.getString(1));
            }
        }
    }

    public static String product(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    }
}
