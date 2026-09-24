package com.geneinvoice.history;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The raw-JDBC half of the mirror: the one place an {@link Instant} crosses into and out of a
 * history table, and the ordered value sink a {@link HistoryBinding.Projector} fills (B3).
 *
 * <p>Mirror rows are written by JDBC and not by JPA — the writer runs at beforeCommit, on the
 * business transaction's own connection, after Hibernate has flushed — so the timestamp handling
 * that Hibernate does for every other Instant in this application has to be done here by hand.
 * {@link #getInstant} is modelled line for line on {@code InvoiceSchemaUpgrade.raisedAt} /
 * {@code instantOf}, which already handles OffsetDateTime / Timestamp / LocalDateTime across both
 * drivers (B3).
 */
public final class HistoryJdbc {

    private HistoryJdbc() {
    }

    /**
     * ALWAYS an OffsetDateTime at UTC, never {@code Timestamp.from(instant)}.
     *
     * <p>{@code java.sql.Timestamp} carries no zone, so a driver converts it through the JVM's
     * DEFAULT zone; the same Instant would then land as a different wall-clock reading on a
     * machine that is not on UTC, and an interval written on one host would not line up with one
     * written on another. This codebase is UTC-only — {@code InvoiceDates.today()} is
     * {@code LocalDate.now(ZoneOffset.UTC)} — and a previous unit shipped exactly this bug into
     * the region backfill and had to fix it. An OffsetDateTime names its own offset, so neither
     * the JVM's zone nor the database session's can move it (B3).
     */
    public static void setInstant(PreparedStatement statement, int index, Instant value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
            return;
        }
        statement.setObject(index, value.atOffset(ZoneOffset.UTC));
    }

    public static Instant getInstant(ResultSet rows, int column) throws SQLException {
        try {
            OffsetDateTime at = rows.getObject(column, OffsetDateTime.class);
            if (at != null) return at.toInstant();
        } catch (SQLException | RuntimeException e) {
            // Not every driver/column pair answers OffsetDateTime; fall through to the raw object,
            // exactly as InvoiceSchemaUpgrade.raisedAt does (B3).
        }
        return instantOf(rows.getObject(column));
    }

    public static Instant instantOf(Object value) {
        if (value == null) return null;
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof LocalDateTime ldt) return ldt.toInstant(ZoneOffset.UTC);
        throw new IllegalStateException("Unexpected history timestamp: " + value.getClass().getName());
    }

    /** A sink over the business columns of ONE mirror row, in the binding's declared order. */
    public static Row row(List<String> columns) {
        return new Row(columns);
    }

    /**
     * The values a {@link HistoryBinding.Projector} pulls off a managed entity, addressed BY NAME
     * and emitted in the binding's declared column order.
     *
     * <p>By name and not by position on purpose: a projector is hand-written, and two adjacent
     * Long columns filled in the wrong order would be a silent, permanent corruption of the
     * mirror that no type checker and no constraint would catch. Naming a column the binding does
     * not declare throws here, while somebody is looking (B3).
     */
    public static final class Row {

        private final List<String> columns;
        private final Map<String, Object> values = new LinkedHashMap<>();

        private Row(List<String> columns) {
            this.columns = List.copyOf(columns);
        }

        public Row set(String column, Object value) {
            if (!columns.contains(column)) {
                throw new IllegalArgumentException(
                        column + " is not one of this binding's mirror columns " + columns + " (B3)");
            }
            values.put(column, value);
            return this;
        }

        public List<String> columns() {
            return columns;
        }

        public Object get(String column) {
            return values.get(column);
        }

        /** In the declared order; a column the projector never set is null, which is a real value. */
        public List<Object> ordered() {
            return columns.stream().map(values::get).toList();
        }
    }
}
