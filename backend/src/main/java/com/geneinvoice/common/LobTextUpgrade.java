package com.geneinvoice.common;

import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;

/**
 * READS THE LARGE OBJECTS THE OLD &#64;Lob MAPPING LEFT BEHIND BACK INTO THEIR OWN COLUMNS (B2).
 *
 * <p>Three text columns in this product were mapped {@code @Lob} for years: audit_logs.before_json,
 * audit_logs.after_json and disputes.proposed_change_json. On Postgres that makes pgjdbc write a
 * LARGE OBJECT and store its OID in the text column, so each of those rows holds a short run of
 * digits instead of the JSON somebody wrote. The mapping is fixed — see the Javadoc on
 * {@code AuditLog.beforeJson} — but a deployment that has been running for a year is full of OIDs,
 * and with the fix in and nothing else done its whole audit trail would read back as the literal
 * string "60798". This bean is what makes dropping &#64;Lob safe there rather than only on the new
 * table beside them: it lo_gets each object, writes the text into the column it belongs in, and
 * lo_unlinks the object so it stops leaking.
 *
 * <p>SAFE TO RUN AGAINST A DATABASE WHOSE ROWS ARE ALREADY PLAIN TEXT, which is the normal case on
 * H2, the case on a Postgres deployment born after the fix, and the case on every boot after the
 * first. A JSON value does not match the all-digits candidate filter, and a value that does still
 * has to name a large object that really exists before anything is rewritten. After one successful
 * pass the product owns no large objects at all, so the first query below — "does this database
 * contain a single large object?" — short-circuits the whole bean and every later boot costs one
 * indexed lookup rather than a scan.
 *
 * <p>Idempotent, chunked by keyset on id, committed per chunk, and it NEVER throws: an upgrade
 * runs first and carries on, a check runs last and refuses (the RowVersionUpgrade idiom). Raw JDBC
 * and an unused EntityManagerFactory parameter to order this after Hibernate's schema export (the
 * InvoiceSchemaUpgrade:34 / RowVersionUpgrade:22 idiom). H2 has no large objects of this kind at
 * all, so everything below is Postgres-only behind an explicit dialect branch.
 *
 * <p>OPERATIONAL NOTE FOR A LARGE DEPLOYMENT: the one migrating boot walks audit_logs in chunks of
 * {@value #CHUNK} rows and rewrites each one, which on a table of millions is minutes of work
 * before the application answers anything. It is a one-off, and it is deliberately done with
 * nobody writing rather than under traffic; a deployment that cannot afford the startup window can
 * run the same statements by hand first, after which this finds nothing left to do.
 */
@Component
@Slf4j
class LobTextUpgrade implements InitializingBean {

    static final int CHUNK = 500;

    /** An oid is a 32-bit unsigned integer, so nothing above this can be one (B2). */
    private static final long MAX_OID = 4294967295L;

    private record LobColumn(String table, String column) {}

    /** One row that MIGHT still be an OID: its id, and the digits the column holds today (B2). */
    private record Candidate(long id, String text) {}

    /** One page of the walk: what is worth rewriting, how far the query got, and how much of the
     *  page the query itself returned — the last two are what the keyset pages on, so a page whose
     *  every row was discarded in Java cannot stop the walk short (B2). */
    private record Chunk(List<Candidate> rows, long lastId, int scanned) {}

    private static final List<LobColumn> COLUMNS = List.of(
            new LobColumn("audit_logs", "before_json"),
            new LobColumn("audit_logs", "after_json"),
            new LobColumn("disputes", "proposed_change_json"));

    private final DataSource dataSource;

    LobTextUpgrade(DataSource dataSource, EntityManagerFactory schemaUpToDate) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        try (Connection connection = dataSource.getConnection()) {
            if (!SchemaSupport.product(connection).contains("postgresql")) return;
            if (!hasLargeObjects(connection)) return;
            for (LobColumn column : COLUMNS) {
                int rewritten = rewrite(connection, column);
                if (rewritten > 0) {
                    log.info("Read {} large object(s) back into {}.{} as text",
                            rewritten, column.table(), column.column());
                }
            }
        } catch (SQLException e) {
            log.warn("Could not finish the large-object upgrade: {}", e.getMessage());
        }
    }

    /**
     * The whole bean's guard. No large object anywhere means no column can hold the OID of one,
     * whether because this database was always written by the fixed mapping or because an earlier
     * boot already migrated it (B2).
     */
    private boolean hasLargeObjects(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select exists(select 1 from pg_largeobject_metadata)");
             ResultSet found = statement.executeQuery()) {
            return found.next() && found.getBoolean(1);
        }
    }

    private int rewrite(Connection connection, LobColumn column) {
        int rewritten = 0;
        long after = 0;
        boolean autoCommit = true;
        try {
            autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            while (true) {
                Chunk chunk = candidates(connection, column, after);
                if (chunk.scanned() == 0) break;
                for (Candidate candidate : chunk.rows()) {
                    if (rewriteOne(connection, column, candidate)) rewritten++;
                }
                connection.commit();
                // Keyset on the LAST ROW THE QUERY SAW and not on the last one kept, so a chunk
                // whose every row was filtered out in Java still moves the walk forward; and the
                // end is "the query returned less than a chunk", not "it kept nothing" (B2).
                after = chunk.lastId();
                if (chunk.scanned() < CHUNK) break;
            }
        } catch (SQLException e) {
            log.warn("Could not rewrite the large objects in {}.{}: {}",
                    column.table(), column.column(), e.getMessage());
            rollback(connection);
        } finally {
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException e) {
                log.warn("Could not restore auto-commit after the large-object upgrade: {}",
                        e.getMessage());
            }
        }
        return rewritten;
    }

    /**
     * The rows whose column might be an OID: all digits, and short enough to be one. The value
     * comes back as a String and is range-checked in Java rather than cast in SQL, because a cast
     * that Postgres chose to evaluate before the filter would fail the whole statement on the
     * first row holding a number too big for an oid (B2).
     */
    private Chunk candidates(Connection connection, LobColumn column, long after)
            throws SQLException {
        List<Candidate> found = new ArrayList<>();
        int scanned = 0;
        long lastId = after;
        String sql = "select id, " + column.column() + " from " + column.table()
                + " where id > ? and " + column.column() + " ~ '^[0-9]{1,10}$'"
                + " order by id limit " + CHUNK;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, after);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    scanned++;
                    lastId = rows.getLong(1);
                    String text = rows.getString(2);
                    long oid = Long.parseLong(text);
                    if (oid > 0 && oid <= MAX_OID) found.add(new Candidate(lastId, text));
                }
            }
        }
        return new Chunk(found, lastId, scanned);
    }

    /**
     * One row, inside a savepoint. lo_get on an object that has gone raises, and an unguarded
     * raise would abort the chunk's transaction and take every row already rewritten in it down
     * with the failure — so a row that cannot be recovered is skipped, loudly, and the rest go on.
     *
     * <p>The update names the digits it read, so a row somebody changed between the two statements
     * is left alone rather than overwritten; and it is the SAME transaction as the lo_unlink
     * beside it, so the object cannot be dropped by a pass whose write is later rolled back (B2).
     */
    private boolean rewriteOne(Connection connection, LobColumn column, Candidate candidate)
            throws SQLException {
        long oid = Long.parseLong(candidate.text());
        Savepoint savepoint = connection.setSavepoint("lob_" + candidate.id());
        try {
            if (!objectExists(connection, oid)) {
                // A column that reads as a number but names nothing: either real data that
                // happens to be digits, or an object somebody has already removed. Neither is
                // this bean's to touch (B2).
                connection.releaseSavepoint(savepoint);
                return false;
            }
            String sql = "update " + column.table() + " set " + column.column()
                    + " = convert_from(lo_get(cast(? as oid)), 'UTF8')"
                    + " where id = ? and " + column.column() + " = ?";
            int updated;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, oid);
                statement.setLong(2, candidate.id());
                statement.setString(3, candidate.text());
                updated = statement.executeUpdate();
            }
            if (updated == 0) {
                connection.releaseSavepoint(savepoint);
                return false;
            }
            unlink(connection, oid);
            connection.releaseSavepoint(savepoint);
            return true;
        } catch (SQLException e) {
            log.warn("Left {}.{} of row {} alone: {}",
                    column.table(), column.column(), candidate.id(), e.getMessage());
            connection.rollback(savepoint);
            return false;
        }
    }

    private boolean objectExists(Connection connection, long oid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select 1 from pg_largeobject_metadata where oid = cast(? as oid)")) {
            statement.setLong(1, oid);
            try (ResultSet found = statement.executeQuery()) {
                return found.next();
            }
        }
    }

    private void unlink(Connection connection, long oid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select lo_unlink(cast(? as oid))")) {
            statement.setLong(1, oid);
            statement.execute();
        }
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("Could not roll the large-object upgrade back: {}", e.getMessage());
        }
    }
}
