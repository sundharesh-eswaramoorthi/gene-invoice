package com.geneinvoice.invoice;

import com.geneinvoice.audit.AuditService;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Gives every invoice a due date, including the rows raised before the column existed (D2, AC-A1).
 * {@link Invoice} maps {@code due_date} nullable so {@code ddl-auto: update} can add the column to
 * a table that already has rows; this fills those rows in from the system default term and then
 * makes the column not null. It runs once the schema is up to date (it needs the entity manager
 * factory for that) and before the app takes requests. On a database that has been through it
 * once it changes nothing, so it is safe to re-run and safe on a populated production database.
 */
@Component
@Slf4j
class InvoiceSchemaUpgrade implements InitializingBean {

    /**
     * The backfill is one act on the whole table rather than on one invoice, but
     * {@code audit_logs.entity_id} is not null. Zero is that entry's id; it belongs to no invoice,
     * so no invoice's History tab shows it.
     */
    static final long BACKFILL_ENTITY_ID = 0L;

    private final DataSource dataSource;
    private final InvoiceProperties invoiceProperties;
    private final AuditService auditService;

    InvoiceSchemaUpgrade(DataSource dataSource, InvoiceProperties invoiceProperties,
                         AuditService auditService, EntityManagerFactory schemaUpToDate) {
        this.dataSource = dataSource;
        this.invoiceProperties = invoiceProperties;
        this.auditService = auditService;
    }

    @Override
    public void afterPropertiesSet() {
        PaymentTerm term = invoiceProperties.defaultTerm();
        int filled = 0;
        try (Connection connection = dataSource.getConnection()) {
            filled = backfill(connection, term);
            enforceDueDateNotNull(connection);
        } catch (SQLException e) {
            log.warn("Could not finish the invoice due-date upgrade: {}", e.getMessage());
        }
        // Only when it actually moved rows, so a restart does not file the same entry again.
        if (filled > 0) {
            auditService.record(InvoiceService.ENTITY, BACKFILL_ENTITY_ID,
                    "INVOICE_DUE_DATES_BACKFILLED", null, new Backfill(filled, term), null, null,
                    "Backfilled " + filled + " invoice due dates using " + term.label());
        }
    }

    /** What the backfill did, for the audit entry's "after" snapshot. */
    record Backfill(int invoices, PaymentTerm paymentTerm) {}

    /** How many rows are read and written at a time, so a huge table is neither held in memory
     * nor sent one row per round trip. */
    private static final int CHUNK = 1000;

    /**
     * Every invoice still missing a due date gets its invoice date plus the default term's days,
     * and the term that produced it. Nothing else on the row is touched — least of all
     * {@code status} or {@code paid_amount} (§9 of the PRD). Returns how many rows were filled,
     * which is zero on a database that has already been through it.
     *
     * <p>The date is worked out here rather than in the SQL: the calendar day an instant falls on
     * is UTC everywhere in this app ({@link InvoiceDates#dayOf}), but H2's date arithmetic reads a
     * stored instant in the session's own zone, which dates a late-evening invoice a day out. One
     * prepared statement carries the whole thing, a chunk of rows per batch.
     */
    static int backfill(Connection connection, PaymentTerm term) throws SQLException {
        if (term.days() == null) {
            throw new IllegalArgumentException("The backfill needs a term with a number of days");
        }
        int filled = 0;
        int left = 0;
        long after = 0;
        while (true) {
            Map<Long, Instant> chunk = undated(connection, after, CHUNK);
            if (chunk.isEmpty()) break;
            int dated = date(connection, chunk, term);
            if (!connection.getAutoCommit()) connection.commit();
            filled += dated;
            // Rows another writer dated first, or that this could not reach: counted once at the
            // end rather than a warning per chunk.
            left += chunk.size() - dated;
            // The next chunk starts where this one stopped, so a big table is one pass of the
            // primary key rather than a fresh scan past everything already done (§2.5).
            after = last(chunk);
            if (chunk.size() == CHUNK) {
                log.info("Backfilled {} invoice due dates so far", filled);
            }
        }
        if (left > 0) {
            log.warn("{} invoices could not be given a due date", left);
        }
        if (filled > 0) {
            log.info("Backfilled {} invoice due dates using {}", filled, term.label());
        }
        return filled;
    }

    /** The last id of a chunk, which the next one carries on after. */
    private static long last(Map<Long, Instant> chunk) {
        long id = 0;
        for (Long each : chunk.keySet()) id = Math.max(id, each);
        return id;
    }

    /** The next invoices after {@code afterId} with no due date, as id → when each was raised. */
    static Map<Long, Instant> undated(Connection connection, long afterId, int limit)
            throws SQLException {
        Map<Long, Instant> out = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select id, invoice_date from invoices"
                        + " where id > ? and due_date is null order by id limit ?")) {
            statement.setLong(1, afterId);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) out.put(rows.getLong(1), raisedAt(rows, 2));
            }
        }
        return out;
    }

    /** Dates one chunk. The {@code due_date is null} guard makes a second run a no-op. */
    private static int date(Connection connection, Map<Long, Instant> chunk, PaymentTerm term)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update invoices set due_date = ?, payment_term = ? where id = ? and due_date is null")) {
            for (Map.Entry<Long, Instant> invoice : chunk.entrySet()) {
                statement.setObject(1, term.due(InvoiceDates.dayOf(invoice.getValue())));
                statement.setString(2, term.name());
                statement.setLong(3, invoice.getKey());
                statement.addBatch();
            }
            int done = 0;
            for (int rows : statement.executeBatch()) {
                if (rows > 0) done += rows;
                else if (rows == Statement.SUCCESS_NO_INFO) done++;
            }
            return done;
        }
    }

    /**
     * When a row was raised — asked for as the instant it is, not as a wall-clock reading of it.
     * Hibernate stores the instant with its offset, so the column is a zoned one and every driver
     * can hand it over as an {@link OffsetDateTime}; only a driver that cannot is fallen back on.
     */
    private static Instant raisedAt(ResultSet rows, int column) throws SQLException {
        try {
            OffsetDateTime raised = rows.getObject(column, OffsetDateTime.class);
            if (raised != null) return raised.toInstant();
        } catch (SQLException | RuntimeException e) {
            // A driver that will not make one of this column: read whatever it does give instead.
        }
        return instantOf(rows.getObject(column));
    }

    /**
     * The instant a driver's own value stands for. A {@link Timestamp} is already that instant, so
     * it is taken as one: reading its wall time back as UTC would move it by the machine's offset
     * and date a late-evening invoice a day out — the very thing this class works in Java to
     * avoid (§2.5). Only a genuinely zoneless value is the UTC the app wrote into it.
     */
    static Instant instantOf(Object value) {
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof LocalDateTime ldt) return ldt.toInstant(ZoneOffset.UTC);
        throw new IllegalStateException("Unexpected invoice_date: " + value);
    }

    /**
     * The column becomes not null, now that no row is missing one. Skipped when it already is.
     * A failure — a row this could not reach, a permission the app has not got — is a warning and
     * not fatal: the service sets a due date on every invoice it writes and {@link Invoice} has a
     * last resort of its own, so the invariant holds either way (AC-A1).
     */
    static void enforceDueDateNotNull(Connection connection) throws SQLException {
        String product = product(connection);
        String alter;
        if (product.contains("postgresql")) {
            alter = "alter table invoices alter column due_date set not null";
        } else if (product.contains("h2")) {
            alter = "alter table invoices alter column due_date date not null";
        } else {
            return;
        }
        if (!isNullable(connection, "invoices", "due_date")) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute(alter);
            if (!connection.getAutoCommit()) connection.commit();
            log.info("invoices.due_date is now not null");
        } catch (SQLException e) {
            log.warn("Could not make invoices.due_date not null: {}", e.getMessage());
        }
    }

    /** Whether the column still takes nulls; true for a column that cannot be found, so that the
     * alter runs and says why it could not rather than this quietly deciding there is nothing to do. */
    static boolean isNullable(Connection connection, String table, String column) throws SQLException {
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

    private static String product(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    }
}
