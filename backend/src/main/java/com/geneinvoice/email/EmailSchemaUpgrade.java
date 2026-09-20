package com.geneinvoice.email;

import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
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

/**
 * Lets {@code emails.status} hold {@link EmailStatus#PARTIAL}. Hibernate writes an enum column's
 * values into a check constraint when it creates the table, and {@code ddl-auto: update} never
 * changes a constraint that exists, so on a database made before PARTIAL the roll-up of a partly
 * sent email would be refused. This replaces such a constraint with one listing every status. It
 * runs once the schema is up to date (it needs the entity manager factory for that) and before the
 * app takes requests; on a current database it changes nothing.
 */
@Component
@Slf4j
class EmailSchemaUpgrade implements InitializingBean {

    private final DataSource dataSource;

    EmailSchemaUpgrade(DataSource dataSource, EntityManagerFactory schemaUpToDate) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        try (Connection connection = dataSource.getConnection()) {
            widen(connection, "emails", "status", EmailStatus.class);
        } catch (SQLException e) {
            log.warn("Could not check the check constraint on emails.status: {}", e.getMessage());
        }
    }

    /**
     * Replaces the check constraints on {@code table.column} that do not list every value of the
     * enum with one that does. Only PostgreSQL and H2 are looked at.
     */
    static void widen(Connection connection, String table, String column, Class<? extends Enum<?>> type)
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
        // The enum's check: on this column, naming its first value. Stale when a value is missing.
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
}
