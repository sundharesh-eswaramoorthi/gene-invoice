package com.geneinvoice.common.schema;

import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * JDBC helpers for the schema-upgrade beans: the steps {@code ddl-auto: update} cannot take on a populated
 * database. Postgres in production, H2 in tests and in the file-database profile.
 */
@Slf4j
public final class SchemaSupport {

    private SchemaSupport() {}

    /** The database product in lower case, e.g. "postgresql" or "h2". */
    public static String product(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
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

    /**
     * Makes a column NOT NULL once every row has a value. Returns true when the column is (now) NOT NULL;
     * false, with a warning, while any NULL remains or the change fails, so the next boot tries again.
     */
    public static boolean enforceNotNull(Connection connection, String table, String column) throws SQLException {
        String product = product(connection);
        if (!product.contains("postgresql") && !product.contains("h2")) return false;
        if (!isNullable(connection, table, column)) return true;
        long missing;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select count(*) from " + table + " where " + column + " is null")) {
            rows.next();
            missing = rows.getLong(1);
        }
        if (missing > 0) {
            log.warn("{}.{} stays nullable: {} row(s) still have no value", table, column, missing);
            return false;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("alter table " + table + " alter column " + column + " set not null");
            if (!connection.getAutoCommit()) connection.commit();
            log.info("{}.{} is now not null", table, column);
            return true;
        } catch (SQLException e) {
            if (!connection.getAutoCommit()) connection.rollback();
            log.warn("Could not make {}.{} not null: {}", table, column, e.getMessage());
            return false;
        }
    }

    /**
     * Loosens a table an earlier build created under the same name, so that the current mapping can write to it.
     * Every NOT NULL column outside {@code keepNotNull} becomes nullable; every CHECK constraint that mentions a
     * column outside {@code mapped} (a column the mapping never writes) is dropped, and with {@code dropAllChecks}
     * every CHECK constraint is. One transaction; each change is logged. A table that does not exist, or that
     * already fits, is left alone, so running it again changes nothing. Returns the number of changes made.
     *
     * @param mapped the columns the mapping reads and writes, in any case; null: no CHECK is dropped for naming an
     *               unmapped column (only {@code dropAllChecks} drops any)
     */
    public static int relaxColumns(Connection connection, String table, Set<String> mapped, Set<String> keepNotNull,
                                   boolean dropAllChecks) throws SQLException {
        String product = product(connection);
        if (!product.contains("postgresql") && !product.contains("h2")) return 0;
        Map<String, Boolean> columns = columns(connection, table);
        if (columns.isEmpty()) return 0;
        Set<String> keep = lowerCase(keepNotNull);
        Set<String> known = mapped == null ? null : lowerCase(mapped);
        List<String> relax = columns.entrySet().stream()
                .filter(c -> !c.getValue() && !keep.contains(c.getKey()))
                .map(Map.Entry::getKey)
                .toList();
        Set<String> unmapped = known == null ? Set.of() : columns.keySet().stream()
                .filter(c -> !known.contains(c))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> drop = checks(connection, table).entrySet().stream()
                .filter(c -> dropAllChecks || identifiers(c.getValue()).stream().anyMatch(unmapped::contains))
                .map(Map.Entry::getKey)
                .toList();
        if (relax.isEmpty() && drop.isEmpty()) return 0;
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String name : drop) {
                statement.execute("alter table " + table + " drop constraint \"" + name + "\"");
                log.info("Dropped the check constraint {} of {}", name, table);
            }
            for (String column : relax) {
                statement.execute("alter table " + table + " alter column " + column + " drop not null");
                log.info("{}.{} is now nullable", table, column);
            }
            connection.commit();
            return relax.size() + drop.size();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /** {@link #relaxColumns(Connection, String, Set, Set, boolean)} without dropping CHECKs by the columns named. */
    public static int relaxColumns(Connection connection, String table, Set<String> keepNotNull, boolean dropAllChecks)
            throws SQLException {
        return relaxColumns(connection, table, null, keepNotNull, dropAllChecks);
    }

    /**
     * Lengthens each varchar column of {@code table} that is shorter than {@code lengths} says (an earlier build may
     * have made it narrower than the values now written, e.g. an enum that gained a longer name). Never shortens,
     * never touches a column of another type or one not listed. One transaction; returns the columns changed.
     */
    public static int lengthen(Connection connection, String table, Map<String, Long> lengths) throws SQLException {
        String product = product(connection);
        if (!product.contains("postgresql") && !product.contains("h2")) return 0;
        boolean h2 = product.contains("h2");
        Map<String, Long> wanted = new LinkedHashMap<>();
        lengths.forEach((c, n) -> wanted.put(c.toLowerCase(Locale.ROOT), n));
        Map<String, Long> widen = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select column_name, character_maximum_length from information_schema.columns
                 where table_name = ? and table_schema = %s and lower(data_type) = 'character varying'
                """.formatted(h2 ? "schema()" : "current_schema()"))) {
            statement.setString(1, h2 ? table.toUpperCase(Locale.ROOT) : table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String column = rows.getString(1).toLowerCase(Locale.ROOT);
                    long length = rows.getLong(2);
                    Long want = wanted.get(column);
                    if (!rows.wasNull() && want != null && length < want) widen.put(column, want);
                }
            }
        }
        if (widen.isEmpty()) return 0;
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (Map.Entry<String, Long> c : widen.entrySet()) {
                statement.execute("alter table " + table + " alter column " + c.getKey()
                        + " set data type varchar(" + c.getValue() + ")");
                log.info("{}.{} is now varchar({})", table, c.getKey(), c.getValue());
            }
            connection.commit();
            return widen.size();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /** The table's columns in lower case, in order, each with whether it is nullable; empty when there is none. */
    public static Map<String, Boolean> columns(Connection connection, String table) throws SQLException {
        boolean h2 = product(connection).contains("h2");
        Map<String, Boolean> columns = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select column_name, is_nullable from information_schema.columns
                 where table_name = ? and table_schema = %s order by ordinal_position
                """.formatted(h2 ? "schema()" : "current_schema()"))) {
            statement.setString(1, h2 ? table.toUpperCase(Locale.ROOT) : table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.put(rows.getString(1).toLowerCase(Locale.ROOT), !"NO".equalsIgnoreCase(rows.getString(2)));
                }
            }
        }
        return columns;
    }

    /**
     * Lets an enum column accept every constant of {@code type}. Postgres keeps enums as varchar with a CHECK
     * constraint; H2 may have either a CHECK constraint or a native ENUM column type (what Hibernate's H2 dialect
     * creates), and both are handled. A CHECK is the column's enum check when it names this column and nothing
     * else, as an IN list or {@code = ANY (ARRAY[...])} of string literals; it is stale, and is replaced by one
     * listing every constant, when any constant is missing from it, whichever that is. An enum check that already
     * lists every constant is left alone, as is every other CHECK.
     */
    public static void widen(Connection connection, String table, String column, Class<? extends Enum<?>> type)
            throws SQLException {
        String product = product(connection);
        List<String> values = Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
        if (product.contains("h2")) widenH2Enum(connection, table, column, values);
        if (!product.contains("postgresql") && !product.contains("h2")) return;
        List<String> stale = checks(connection, table).entrySet().stream()
                .filter(c -> isEnumCheck(c.getValue(), column))
                .filter(c -> !literals(c.getValue()).containsAll(values))
                .map(Map.Entry::getKey)
                .toList();
        if (stale.isEmpty()) return;
        String listed = listed(values);
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

    /** The table's CHECK constraints: name to definition, as the database prints it. */
    private static Map<String, String> checks(Connection connection, String table) throws SQLException {
        String product = product(connection);
        if (product.contains("postgresql")) {
            return query(connection, """
                    select c.conname, pg_get_constraintdef(c.oid) from pg_constraint c
                      join pg_class t on t.oid = c.conrelid
                      join pg_namespace n on n.oid = t.relnamespace
                     where c.contype = 'c' and t.relname = ? and n.nspname = current_schema()
                    """, table);
        }
        if (product.contains("h2")) {
            return query(connection, """
                    select tc.constraint_name, cc.check_clause from information_schema.table_constraints tc
                      join information_schema.check_constraints cc
                        on cc.constraint_schema = tc.constraint_schema and cc.constraint_name = tc.constraint_name
                     where tc.constraint_type = 'CHECK' and tc.table_name = upper(?) and tc.table_schema = schema()
                    """, table);
        }
        return Map.of();
    }

    private static final Pattern LITERAL = Pattern.compile("'((?:[^']|'')*)'");
    private static final Pattern IDENTIFIER = Pattern.compile("\"((?:[^\"]|\"\")+)\"|([A-Za-z_][A-Za-z0-9_$]*)");
    /** The words an enum check is printed with, besides its column: SQL, and Postgres' casts. */
    private static final Set<String> ENUM_CHECK_WORDS =
            Set.of("check", "in", "any", "array", "text", "character", "varying", "varchar");

    /** The identifiers a CHECK's definition names, in lower case: its columns, and the keywords around them. */
    static Set<String> identifiers(String definition) {
        Matcher m = IDENTIFIER.matcher(LITERAL.matcher(definition).replaceAll(" "));
        Set<String> out = new LinkedHashSet<>();
        while (m.find()) {
            String word = m.group(1) != null ? m.group(1).replace("\"\"", "\"") : m.group(2);
            out.add(word.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** The string literals of a CHECK's definition. */
    static Set<String> literals(String definition) {
        Matcher m = LITERAL.matcher(definition);
        Set<String> out = new LinkedHashSet<>();
        while (m.find()) out.add(m.group(1).replace("''", "'"));
        return out;
    }

    /** An IN list, or {@code = ANY (ARRAY[...])}, of string literals over {@code column} and no other. */
    static boolean isEnumCheck(String definition, String column) {
        Set<String> words = identifiers(definition);
        String name = column.toLowerCase(Locale.ROOT);
        if (!words.contains(name) || literals(definition).isEmpty()) return false;
        if (!words.contains("in") && !(words.contains("any") && words.contains("array"))) return false;
        return words.stream().allMatch(w -> w.equals(name) || ENUM_CHECK_WORDS.contains(w));
    }

    private static Set<String> lowerCase(Set<String> names) {
        return names.stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    private static void widenH2Enum(Connection connection, String table, String column, List<String> values)
            throws SQLException {
        String dtd = null;
        try (PreparedStatement statement = connection.prepareStatement("""
                select data_type, dtd_identifier from information_schema.columns
                 where table_name = ? and column_name = ? and table_schema = schema()
                """)) {
            statement.setString(1, table.toUpperCase(Locale.ROOT));
            statement.setString(2, column.toUpperCase(Locale.ROOT));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !"ENUM".equalsIgnoreCase(rows.getString(1))) return;
                dtd = rows.getString(2);
            }
        }
        List<String> present = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select value_name from information_schema.enum_values
                 where object_schema = schema() and object_name = ? and object_type = 'TABLE'
                   and enum_identifier = ?
                 order by value_ordinal
                """)) {
            statement.setString(1, table.toUpperCase(Locale.ROOT));
            statement.setString(2, dtd);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) present.add(rows.getString(1));
            }
        }
        if (present.containsAll(values)) return;
        List<String> all = new ArrayList<>(present);
        values.stream().filter(v -> !present.contains(v)).forEach(all::add);
        try (Statement statement = connection.createStatement()) {
            statement.execute("alter table " + table + " alter column " + column
                    + " set data type enum (" + listed(all) + ")");
            if (!connection.getAutoCommit()) connection.commit();
            log.info("Widened the enum type of {}.{} to {}", table, column, all);
        }
    }

    private static String listed(List<String> values) {
        return values.stream().map(v -> "'" + v + "'").collect(Collectors.joining(", "));
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
