package com.geneinvoice.invoice;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.common.SchemaSupport;
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
import java.util.Map;

@Component
@Slf4j
class InvoiceSchemaUpgrade implements InitializingBean {

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
        if (filled > 0) {
            auditService.record(InvoiceService.ENTITY, BACKFILL_ENTITY_ID,
                    "INVOICE_DUE_DATES_BACKFILLED", null, new Backfill(filled, term), null, null,
                    "Backfilled " + filled + " invoice due dates using " + term.label());
        }
    }

    record Backfill(int invoices, PaymentTerm paymentTerm) {}

    private static final int CHUNK = 1000;

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
            left += chunk.size() - dated;
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

    private static long last(Map<Long, Instant> chunk) {
        long id = 0;
        for (Long each : chunk.keySet()) id = Math.max(id, each);
        return id;
    }

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

    private static Instant raisedAt(ResultSet rows, int column) throws SQLException {
        try {
            OffsetDateTime raised = rows.getObject(column, OffsetDateTime.class);
            if (raised != null) return raised.toInstant();
        } catch (SQLException | RuntimeException e) {
        }
        return instantOf(rows.getObject(column));
    }

    static Instant instantOf(Object value) {
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof LocalDateTime ldt) return ldt.toInstant(ZoneOffset.UTC);
        throw new IllegalStateException("Unexpected invoice_date: " + value);
    }

    static void enforceDueDateNotNull(Connection connection) throws SQLException {
        SchemaSupport.enforceNotNull(connection, "invoices", "due_date", "date");
    }
}
