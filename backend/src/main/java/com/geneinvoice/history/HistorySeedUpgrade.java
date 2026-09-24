package com.geneinvoice.history;

import com.geneinvoice.common.asof.AsOf;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The first row of every record's history, and the day this installation started keeping it (B3).
 *
 * <p>SEED ROWS ARE DATED FROM THE RECORD'S OWN CREATION AND NOT FROM INSTALL DAY, and that one
 * decision is the whole reason a pre-floor answer is worth serving at all. An install-dated seed
 * would report zero invoices for every month before the mirror existed — a customer created in
 * March would be present in a February read and a report spanning the floor would be nonsense.
 * Dated from {@code created_at} (falling back to {@code invoice_date} / {@code paid_at}, and for
 * the two child tables and the two join tables the PARENT's), EXISTENCE and creation dates are
 * exact for all of history: a count of invoices raised per month before the floor is exactly
 * right, and only the VALUES are the values as first recorded. That is what
 * {@code AsOfDates.preFloorNote} promises the reader, in those words (B3).
 *
 * <p>Then {@code history_floor(1, installedAt)} IF ABSENT — once, ever, so a restart never moves
 * the floor forward and a record's pre-floor caveat does not change under it. The id is fixed at
 * {@link HistoryFloor#SINGLETON} rather than generated, so a second instance racing this one on
 * boot cannot write a second floor.
 *
 * <p>Raw JDBC and not JPA, for two reasons. The first is the house contract every
 * {@code *SchemaUpgrade} runs under — an unused {@link EntityManagerFactory} parameter to order
 * this bean after Hibernate's schema export (the InvoiceSchemaUpgrade:34 idiom), catch
 * SQLException, log WARN, NEVER throw. The second is specific and load-bearing: seeding through
 * JPA inside a transaction would make the write listener see its own writes, so every seeded row
 * would get a SECOND mirror row dated now on top of the creation-dated one, and
 * {@code uk_<x>h_open} would reject the pair (B3).
 *
 * <p>Chunked at 1000 and keyset-paged over the live id, committing per chunk, and idempotent by
 * {@code not exists}: a record that already has ANY mirror row is skipped entirely, because a
 * record with a version chain must not be handed a second open row dated at its creation.
 */
@Component
// lobTextUpgrade is named here for one reason: it rewrites disputes.proposed_change_json
// from the large-object OID the old @Lob mapping left there back into text, and the seed
// below copies that column into dispute_history in raw SQL. Seeded first, the mirror would
// be born holding "60798" and would only be put right by a later reconcile, which records
// the account as inexact for as long as it takes (B2, B3 INTEGRATION).
@DependsOn({"regionSchemaUpgrade", "approvalSchemaUpgrade", "automationSchemaUpgrade",
        "lobTextUpgrade"})
@Slf4j
class HistorySeedUpgrade implements InitializingBean {

    static final int CHUNK = 1000;

    private final DataSource dataSource;
    private final HistoryRegistry registry;
    private final HistoryClock clock;

    HistorySeedUpgrade(DataSource dataSource, HistoryRegistry registry, HistoryClock clock,
                       EntityManagerFactory schemaUpToDate) {
        this.dataSource = dataSource;
        this.registry = registry;
        this.clock = clock;
    }

    @Override
    public void afterPropertiesSet() {
        // Truncated to the grain both databases store, so what a test compares in Java is what the
        // column holds (B3).
        Instant installedAt = clock.now().truncatedTo(ChronoUnit.MICROS);
        try (Connection connection = dataSource.getConnection()) {
            int seeded = 0;
            for (HistoryBinding binding : registry.all()) {
                try {
                    seeded += seed(connection, binding, installedAt);
                } catch (SQLException e) {
                    // One mirror's seed failing must not cost the other ten theirs: what it leaves
                    // behind is a record with no mirror row, which is exactly the shape the
                    // reconciler repairs (with drifted = true) fifteen minutes later (B3).
                    log.warn("Could not seed {}: {}", binding.mirrorTable(), e.getMessage());
                }
            }
            // AFTER the seeds, so a crash half way through re-seeds on the next boot and only then
            // claims a floor. The seeds are idempotent; the floor is once, ever (B3).
            installFloor(connection, installedAt);
            if (seeded > 0) {
                log.info("Seeded {} history rows from the records' own creation dates", seeded);
            }
        } catch (SQLException e) {
            log.warn("Could not finish the history seed: {}", e.getMessage());
        }
    }

    /** One seed row per existing row of one mirrored table, chunked and idempotent (B3). */
    static int seed(Connection connection, HistoryBinding binding, Instant installedAt)
            throws SQLException {
        Source source = SOURCES.get(binding.mirrorTable());
        if (source == null) {
            // Unreachable while HistorySchemaCheckTest holds SOURCES to the registry: every
            // binding has a projection, and that assertion is what keeps a twelfth mirror from
            // being seeded by nobody and silently empty until its first write (B3).
            log.warn("No seed projection for {}; it will be filled by the reconciler instead (B3)",
                    binding.mirrorTable());
            return 0;
        }
        int total = 0;
        long after = 0;
        while (true) {
            Seeded chunk = seedChunk(connection, binding, after, CHUNK, installedAt);
            total += chunk.rows();
            if (chunk.lastId() == 0) break;
            after = chunk.lastId();
        }
        return total;
    }

    /**
     * ONE chunk, exposed as a static the way {@code InvoiceSchemaUpgrade.backfill} is, so a test
     * drives it against a connection holding rows it inserted itself rather than against whatever
     * a boot happened to find.
     *
     * <p>DEVIATION FROM THE DECLARED SIGNATURE, STATED. The unit names
     * {@code static int seedChunk(Connection, HistoryBinding, long afterId, int size)}. Two things
     * cannot be derived from those four parameters: the instant a row with no creation column at
     * all falls back to, and where the next chunk starts — so it takes the fallback and answers
     * with both numbers. Everything else is as specified (B3).
     *
     * @return how many rows this chunk seeded, and the last live id it looked at — 0 when the
     *         table is exhausted, which is the loop's only stop condition
     */
    static Seeded seedChunk(Connection connection, HistoryBinding binding, long afterId, int size,
                            Instant installedAt) throws SQLException {
        Source source = SOURCES.get(binding.mirrorTable());
        if (source == null) return new Seeded(0, 0);
        long last = windowEnd(connection, source, afterId, size);
        if (last == 0) return new Seeded(0, 0);

        // valid_from is the record's OWN creation instant. The bound fallback is only reached by a
        // row whose creation column is null, which no @PrePersist in this application permits and
        // which a database restored from somewhere else might still hold; an install-dated seed
        // row is a worse answer than a creation-dated one, and a null valid_from is not a row the
        // column accepts at all (B3).
        //
        // deleted false and drifted FALSE: a seed row was never watched happening either, but it
        // is the declared floor of what this installation knows rather than a repair to something
        // it got wrong, and AsOfDates.preFloorNote is what tells the reader so (B3).
        String sql = insertSql(binding, source, "coalesce(" + source.createdAt() + ", ?)",
                "false, false",
                " and " + source.idExpression() + " > ? and " + source.idExpression() + " <= ?"
                        + notExists(binding, source));
        int rows;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int p = 1;
            HistoryJdbc.setInstant(statement, p++, installedAt);
            // AsOf.OPEN exactly: the same constant is the right-hand side of the interval test and
            // the key of uk_<x>h_open, and a second copy that drifted by a nanosecond would
            // silently double every as-of count (B3).
            HistoryJdbc.setInstant(statement, p++, AsOf.OPEN);
            statement.setLong(p++, afterId);
            statement.setLong(p, last);
            rows = statement.executeUpdate();
        }
        if (!connection.getAutoCommit()) connection.commit();
        return new Seeded(rows, last);
    }

    /** What one chunk did: how many rows it wrote, and the id the next chunk reads after. */
    record Seeded(int rows, long lastId) {
    }

    /**
     * The floor, once and never again. {@code where not exists} rather than a count-then-insert,
     * so two instances booting together cannot both decide the table is empty — and if they race
     * anyway the fixed primary key refuses the second (B3).
     */
    static boolean installFloor(Connection connection, Instant installedAt) throws SQLException {
        String sql = "insert into history_floor (id, installed_at) select ?, ?"
                + " where not exists (select 1 from history_floor)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, HistoryFloor.SINGLETON);
            HistoryJdbc.setInstant(statement, 2, installedAt);
            int rows = statement.executeUpdate();
            if (!connection.getAutoCommit()) connection.commit();
            if (rows > 0) log.info("History floor installed at {}", installedAt);
            return rows > 0;
        }
    }

    // ---- the projection, shared with the reconciler ---------------------------------------------

    /**
     * How ONE mirror's columns are read out of the live tables in SQL.
     *
     * <p>The seed and the reconciler's repair write the same row from the same place, so they
     * share this rather than keeping two spellings of "what a mirror row of an invoice is" that
     * could disagree — a repaired row that differed from a seeded one would be a drift the
     * reconciler itself had introduced (B3).
     *
     * @param from         the live table aliased {@code l}, plus whatever it is joined to for a
     *                     denormalised label; every join is LEFT, so a record with no customer or
     *                     no POC still gets its seed row
     * @param idExpression the record's own id: {@code l.id}, or {@code l.promise_id} on a join
     *                     table, which has none of its own
     * @param createdAt    the record's own creation instant, or the parent's where it has none
     * @param denormalised mirror column -> expression, for the columns that are NOT a column of
     *                     the live table; everything else defaults to {@code l.<column>}
     * @param linkKey      the second half of a join table's business key, empty for a primary
     */
    record Source(String from, String idExpression, String createdAt,
                  Map<String, String> denormalised, List<String> linkKey) {

        Source {
            denormalised = Map.copyOf(denormalised);
            linkKey = List.copyOf(linkKey);
        }

        static Source of(String from, String createdAt) {
            return new Source(from, "l.id", createdAt, Map.of(), List.of());
        }

        static Source of(String from, String createdAt, Map<String, String> denormalised) {
            return new Source(from, "l.id", createdAt, denormalised, List.of());
        }

        static Source link(String from, String createdAt, String otherColumn) {
            return new Source(from, "l.promise_id", createdAt, Map.of(), List.of(otherColumn));
        }

        String expression(String mirrorColumn) {
            return denormalised.getOrDefault(mirrorColumn, "l." + mirrorColumn);
        }
    }

    /** By mirror table, so a binding and its projection cannot be paired up wrongly (B3). */
    static final Map<String, Source> SOURCES = sources();

    private static Map<String, Source> sources() {
        Map<String, Source> out = new LinkedHashMap<>();
        out.put("customer_history", Source.of("customers l", "l.created_at"));
        out.put("invoice_history", Source.of(
                "invoices l left join customers c on c.id = l.customer_id"
                        + " left join users u on u.id = l.sales_poc_user_id",
                // An invoice raised before created_at existed still has its invoice_date, which is
                // what Invoice.onCreate copies created_at into anyway (B3).
                "coalesce(l.created_at, l.invoice_date)",
                Map.of("customer_name", "c.name", "sales_poc_name", "u.full_name")));
        out.put("invoice_item_history", Source.of(
                "invoice_items l left join invoices p on p.id = l.invoice_id",
                // A line has no creation date of its own: it came into existence with its invoice.
                "coalesce(p.created_at, p.invoice_date)"));
        out.put("payment_history", Source.of(
                "payments l left join customers c on c.id = l.customer_id"
                        + " left join users u on u.id = l.collection_poc_user_id",
                // payments has no created_at at all; paid_at is NOT NULL and is when it happened.
                "l.paid_at",
                Map.of("customer_name", "c.name", "collection_poc_name", "u.full_name")));
        out.put("payment_allocation_history", Source.of(
                "payment_allocations l left join payments p on p.id = l.payment_id"
                        + " left join customers c on c.id = p.customer_id",
                "p.paid_at",
                Map.of("payment_status", "p.status", "paid_at", "p.paid_at",
                        "customer_id", "p.customer_id", "customer_name", "c.name")));
        out.put("promise_history", Source.of(
                "payment_promises l left join customers c on c.id = l.customer_id"
                        + " left join users u on u.id = l.collection_poc_user_id",
                "l.created_at",
                Map.of("customer_name", "c.name", "collection_poc_name", "u.full_name")));
        out.put("promise_invoice_history", Source.link(
                "payment_promise_invoices l left join payment_promises p on p.id = l.promise_id",
                "p.created_at", "invoice_id"));
        out.put("promise_payment_history", Source.link(
                "payment_promise_payments l left join payment_promises p on p.id = l.promise_id",
                "p.created_at", "payment_id"));
        out.put("dispute_history", Source.of("disputes l", "l.created_at"));
        out.put("customer_poc_history", Source.of("customer_pocs l", "l.created_at"));
        out.put("task_history", Source.of("tasks l", "l.created_at"));
        return Map.copyOf(out);
    }

    /**
     * {@code insert into <mirror> (...) select ...} over the live tables. The column list is the
     * one every binding declares — {@code (businessIdColumn, valid_from, valid_to, deleted,
     * drifted, changed_by_user_id, mirrorColumns...)} — so the writer, the seed and the repair all
     * write the same shape (B3).
     *
     * @param validFromSql the expression that dates the row, with its own bound parameter first
     * @param flags        the literals for {@code deleted, drifted}: a seed is "false, false" and
     *                     a reconciler repair is "false, true", because a repaired row was
     *                     inferred afterwards and an as-of answer over it is not exact
     * @param where        appended to {@code where 1 = 1}, with its parameters after valid_to's
     */
    static String insertSql(HistoryBinding binding, Source source, String validFromSql, String flags,
                            String where) {
        List<String> columns = binding.mirrorColumns();
        StringBuilder sql = new StringBuilder("insert into ").append(binding.mirrorTable())
                .append(" (").append(binding.businessIdColumn())
                .append(", valid_from, valid_to, deleted, drifted, changed_by_user_id");
        for (String column : columns) sql.append(", ").append(column);
        sql.append(") select ").append(source.idExpression()).append(", ").append(validFromSql)
                .append(", ?, ").append(flags)
                // changed_by_user_id: nobody. No person made this version; it is the state the
                // record was found in, and naming a person for it would be a lie in an audit
                // column (B3).
                .append(", null");
        for (String column : columns) sql.append(", ").append(source.expression(column));
        return sql.append(" from ").append(source.from()).append(" where 1 = 1").append(where)
                .toString();
    }

    /** A record that already has ANY mirror row is not seeded: it has a chain already (B3). */
    static String notExists(HistoryBinding binding, Source source) {
        StringBuilder sql = new StringBuilder(" and not exists (select 1 from ")
                .append(binding.mirrorTable()).append(" h where h.")
                .append(binding.businessIdColumn()).append(" = ").append(source.idExpression());
        for (String column : source.linkKey()) {
            sql.append(" and h.").append(column).append(" = l.").append(column);
        }
        return sql.append(")").toString();
    }

    /**
     * The last live id in the next window of at most {@code size} records, or 0 when there are
     * none left. Read before the insert rather than after it, because an insert cannot say which
     * rows it read and a cursor that guessed would either skip records or never terminate. On a
     * join table the key is the OWNER's id, so every row of one promise falls inside one window
     * and a chunk boundary cannot split a promise's coverage in half (B3).
     */
    private static long windowEnd(Connection connection, Source source, long afterId, int size)
            throws SQLException {
        String id = source.idExpression();
        String sql = "select " + (source.linkKey().isEmpty() ? "" : "distinct ") + id
                + " from " + source.from() + " where " + id + " > ? order by " + id + " limit ?";
        long last = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, afterId);
            statement.setInt(2, size);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) last = rows.getLong(1);
            }
        }
        return last;
    }
}
