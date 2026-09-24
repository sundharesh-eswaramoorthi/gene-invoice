package com.geneinvoice.history;

import com.geneinvoice.common.asof.AsOf;
import com.geneinvoice.history.HistorySeedUpgrade.Source;
import com.geneinvoice.region.RegionScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Detection, because a guarantee by inspection is not a guarantee (B3).
 *
 * <p>The write path claims to be complete by construction: the Hibernate listeners cover every
 * write Hibernate mediates, none of the nine {@code @Modifying} bulk statements targets a mirrored
 * entity, and an architecture test keeps both true. That claim is worth making and it is not worth
 * trusting on its own — the raw-JDBC {@code *SchemaUpgrade} beans write live rows with no listener
 * in sight, a future native query could, and a restore from a backup can leave the two sides of
 * the boundary disagreeing about a record nobody touched. So every fifteen minutes this diffs
 * every live row against its open mirror row, repairs what disagrees, and MARKS the repair.
 *
 * <p>A REPAIR IS NOT A SILENT FIX. It is written with {@code drifted = true} and logged at WARN
 * naming the table and the id, and {@link HistoryDrift} turns that flag into {@code exact: false}
 * on every as-of answer over that table at or before the repaired version's start. The alternative
 * — quietly writing the right value — would make the mirror agree with the record while the dates
 * in between were wrong, which is the one failure this design refuses to hide (B3).
 *
 * <p>Two passes per mirror, both keyset-chunked at 1000. The forward pass is the binding's own
 * {@code driftSql}: a live row whose open mirror row is missing or disagrees on any compared
 * column. The inverse pass turns an open, non-deleted mirror row whose live row has VANISHED into
 * a tombstone — a hard delete through a path Hibernate never saw looks like nothing at all from
 * the forward direction (B3).
 *
 * <p>WHY IT IS THE WRITER'S REAL RACE PARTNER, AND WHAT IT DOES ABOUT IT. Two business
 * transactions cannot race on one mirror row: both must have written the live row, so the database
 * serialised them long before either drain began. This sweep writes mirror rows WITHOUT holding
 * the business row, so it is the first thing that genuinely can arrive inside another writer's
 * close-to-insert window. It does not retry: each record is its own short transaction, a
 * constraint violation rolls that one record back leaving the chain exactly as it was, and the
 * next sweep is fifteen minutes away. Losing a race here costs nothing, because whoever won it
 * wrote the version this sweep was about to infer (B3).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HistoryReconciler {

    static final int CHUNK = 1000;

    private final DataSource dataSource;
    private final HistoryRegistry registry;
    private final HistoryClock clock;

    /**
     * Never waited for by a test — every interval in application-test.yml is an hour and every
     * test calls {@link #sweep()} or {@link #reconcile} directly, the house rule for all four
     * scheduled jobs. The initial delay matches the interval so a restart does not spend its first
     * second diffing every table in the product while the application is still warming up (B3).
     */
    @Scheduled(fixedDelayString = "${app.history.reconcile-interval-ms:900000}",
            initialDelayString = "${app.history.reconcile-interval-ms:900000}")
    public void sweep() {
        // Nobody is signed in on the scheduler's thread, and "nobody" reads as NO regions rather
        // than as every region (the deliberate safe direction in RegionScope). Every statement
        // below is raw JDBC and consults no region predicate at all, so this widening adds not one
        // row to any answer today; it is declared because the sweep reads and repairs the whole
        // company's records, and anything added to it later that DID read through a repository
        // would otherwise silently see nothing and repair nothing (B1, B3).
        RegionScope.asSystem(RegionScope.SystemReason.HISTORY_RECONCILE, () -> {
            int repaired = 0;
            for (HistoryBinding binding : registry.all()) {
                repaired += reconcile(binding);
            }
            if (repaired > 0) {
                log.warn("The history reconciler repaired {} record(s); as-of answers over them"
                        + " report exact: false (B3)", repaired);
            }
        });
    }

    /**
     * One mirror, both passes. Callable directly, and it never throws: a sweep that fell over on
     * the second of eleven tables would leave the other nine unreconciled for ever (B3).
     *
     * @return how many versions it wrote
     */
    public int reconcile(HistoryBinding binding) {
        Source source = HistorySeedUpgrade.SOURCES.get(binding.mirrorTable());
        if (source == null) return 0;
        Instant at = clock.now().truncatedTo(ChronoUnit.MICROS);
        int repaired = 0;
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                repaired += repairDrifted(connection, binding, source, at);
                repaired += tombstoneVanished(connection, binding, source, at);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            log.warn("Could not reconcile {}: {}", binding.mirrorTable(), e.getMessage());
        }
        return repaired;
    }

    // ---- the forward pass: the live row and the open mirror row disagree -------------------------

    private int repairDrifted(Connection c, HistoryBinding b, Source source, Instant at)
            throws SQLException {
        int repaired = 0;
        long after = 0;
        while (true) {
            List<Key> found = drifted(c, b, source, after, CHUNK);
            if (found.isEmpty()) break;
            for (Key key : found) {
                if (write(c, b, repairSql(b, source), key, at, false)) repaired++;
            }
            if (found.size() < CHUNK) break;
            // Keyset on the RECORD's id. On a link mirror that is the promise's id, so a promise
            // with more missing links than one chunk holds finishes on the next sweep rather than
            // in this one — fifteen minutes, for a case that means somebody bypassed Hibernate on
            // a thousand-invoice promise (B3).
            after = found.get(found.size() - 1).id();
        }
        return repaired;
    }

    /** The binding's own diff, run with the three parameters it declares: OPEN, after, size (B3). */
    private List<Key> drifted(Connection c, HistoryBinding b, Source source, long after, int size)
            throws SQLException {
        boolean link = !source.linkKey().isEmpty();
        List<Key> found = new ArrayList<>();
        try (PreparedStatement statement = c.prepareStatement(b.driftSql())) {
            HistoryJdbc.setInstant(statement, 1, AsOf.OPEN);
            statement.setLong(2, after);
            statement.setInt(3, size);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(new Key(rows.getLong(1), link ? rows.getLong(2) : null));
                }
            }
        }
        return found;
    }

    /**
     * The live values, read through the SAME projection the seed writes, so a repaired row and a
     * seeded row of the same record are identical rows — a repair that spelled a denormalised
     * label differently would be a drift the reconciler had introduced itself (B3).
     */
    private static String repairSql(HistoryBinding b, Source source) {
        return HistorySeedUpgrade.insertSql(b, source, "?", "false, true",
                " and " + source.idExpression() + " = ?"
                        + (source.linkKey().isEmpty() ? ""
                        : " and l." + source.linkKey().get(0) + " = ?"));
    }

    // ---- the inverse pass: the live row has gone and nobody said so -----------------------------

    private int tombstoneVanished(Connection c, HistoryBinding b, Source source, Instant at)
            throws SQLException {
        int repaired = 0;
        long after = 0;
        while (true) {
            List<Key> found = vanished(c, b, source, after, CHUNK);
            if (found.isEmpty()) break;
            for (Key key : found) {
                if (write(c, b, tombstoneSql(b, source), key, at, true)) repaired++;
            }
            if (found.size() < CHUNK) break;
            after = found.get(found.size() - 1).id();
        }
        return repaired;
    }

    private List<Key> vanished(Connection c, HistoryBinding b, Source source, long after, int size)
            throws SQLException {
        String id = b.businessIdColumn();
        String other = source.linkKey().isEmpty() ? null : source.linkKey().get(0);
        String sql = "select h." + id + (other == null ? "" : ", h." + other)
                + " from " + b.mirrorTable() + " h"
                + " where h.valid_to = ? and h.deleted = false and h." + id + " > ?"
                + " and not exists (select 1 from " + b.liveTable() + " l where "
                + source.idExpression() + " = h." + id
                + (other == null ? "" : " and l." + other + " = h." + other) + ")"
                + " order by h." + id + " limit ?";
        List<Key> found = new ArrayList<>();
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            HistoryJdbc.setInstant(statement, 1, AsOf.OPEN);
            statement.setLong(2, after);
            statement.setInt(3, size);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(new Key(rows.getLong(1), other == null ? null : rows.getLong(2)));
                }
            }
        }
        return found;
    }

    /**
     * A tombstone carries the values the record had when it went, copied forward off the row this
     * sweep is about to close — so the mirror SERVES a deleted record as of a date before it went
     * rather than merely disclosing that it existed. Every business column on every mirror is
     * nullable, so a row of nulls would also have been accepted, and would have made the tombstone
     * invisible to a region-narrowed caller, whose axis reads customer_id (B3).
     */
    private static String tombstoneSql(HistoryBinding b, Source source) {
        String id = b.businessIdColumn();
        StringBuilder sql = new StringBuilder("insert into ").append(b.mirrorTable())
                .append(" (").append(id)
                .append(", valid_from, valid_to, deleted, drifted, changed_by_user_id");
        for (String column : b.mirrorColumns()) sql.append(", ").append(column);
        sql.append(") select h.").append(id).append(", ?, ?, true, true, null");
        for (String column : b.mirrorColumns()) sql.append(", h.").append(column);
        sql.append(" from ").append(b.mirrorTable()).append(" h where h.").append(id).append(" = ?");
        if (!source.linkKey().isEmpty()) {
            sql.append(" and h.").append(source.linkKey().get(0)).append(" = ?");
        }
        // The row this transaction has just closed, identified by the instant it was closed at —
        // the successor cannot be selected from a row that is still open, because closing it is
        // what makes room for the successor under uk_<x>h_open (B3).
        return sql.append(" and h.valid_to = ?").toString();
    }

    // ---- close, then open ------------------------------------------------------------------------

    /**
     * ONE RECORD, ONE TRANSACTION. Close the version that was current, open the one this sweep
     * inferred, commit. A failure rolls back that record alone and leaves its chain exactly as it
     * was — never half-applied, and never a closed row with no successor, which is the one state
     * an as-of read cannot answer from (B3).
     */
    private boolean write(Connection c, HistoryBinding b, String insertSql, Key key, Instant at,
                          boolean fromMirror) {
        try {
            Instant t = at;
            Instant openedAt = openValidFrom(c, b, key);
            // Two versions inside one microsecond still have to give a strictly increasing,
            // non-overlapping interval, or [validFrom, validTo) is empty and an as-of read at that
            // instant answers with neither version (B3).
            if (openedAt != null && !t.isAfter(openedAt)) t = openedAt.plusNanos(1_000);
            if (fromMirror && openedAt == null) return false;   // nothing open to copy forward

            close(c, b, key, t);
            int rows = insert(c, insertSql, key, t, fromMirror);
            if (rows == 0) {
                // The live row went between the diff and the repair. Rolling back rather than
                // leaving a closed row with no successor: the next sweep's inverse pass will
                // tombstone it properly (B3).
                c.rollback();
                return false;
            }
            c.commit();
            log.warn("History repaired: {} {} ({}) (B3)", b.mirrorTable(), key.id(),
                    fromMirror ? "tombstoned; the record is gone" : "the live row disagreed");
            return true;
        } catch (SQLException e) {
            rollback(c);
            log.warn("Could not repair {} {}: {}", b.mirrorTable(), key.id(), e.getMessage());
            return false;
        }
    }

    private Instant openValidFrom(Connection c, HistoryBinding b, Key key) throws SQLException {
        String sql = "select valid_from from " + b.mirrorTable()
                + " where " + b.businessIdColumn() + " = ?"
                + (key.otherId() == null ? "" : " and " + b.mirrorColumns().get(0) + " = ?")
                + " and valid_to = ?";
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            int p = bindKey(statement, key);
            HistoryJdbc.setInstant(statement, p, AsOf.OPEN);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? HistoryJdbc.getInstant(rows, 1) : null;
            }
        }
    }

    private void close(Connection c, HistoryBinding b, Key key, Instant t) throws SQLException {
        String sql = "update " + b.mirrorTable() + " set valid_to = ?"
                + " where " + b.businessIdColumn() + " = ?"
                + (key.otherId() == null ? "" : " and " + b.mirrorColumns().get(0) + " = ?")
                + " and valid_to = ?";
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            int p = 1;
            HistoryJdbc.setInstant(statement, p++, t);
            statement.setLong(p++, key.id());
            if (key.otherId() != null) statement.setLong(p++, key.otherId());
            HistoryJdbc.setInstant(statement, p, AsOf.OPEN);
            statement.executeUpdate();
        }
    }

    /**
     * Both inserts take their parameters in the same order — valid_from, valid_to, then the key —
     * because both are built from {@code insertSql}'s contract. The tombstone adds the instant it
     * closed with, to pick the predecessor it copies forward (B3).
     */
    private int insert(Connection c, String sql, Key key, Instant t, boolean fromMirror)
            throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            int p = 1;
            HistoryJdbc.setInstant(statement, p++, t);
            HistoryJdbc.setInstant(statement, p++, AsOf.OPEN);
            statement.setLong(p++, key.id());
            if (key.otherId() != null) statement.setLong(p++, key.otherId());
            if (fromMirror) HistoryJdbc.setInstant(statement, p, t);
            return statement.executeUpdate();
        }
    }

    private static int bindKey(PreparedStatement statement, Key key) throws SQLException {
        int p = 1;
        statement.setLong(p++, key.id());
        if (key.otherId() != null) statement.setLong(p++, key.otherId());
        return p;
    }

    private static void rollback(Connection c) {
        try {
            c.rollback();
        } catch (SQLException e) {
            log.warn("Could not roll a failed history repair back: {}", e.getMessage());
        }
    }

    /** One record: its own id, and on a link mirror the other half of its business key (B3). */
    record Key(Long id, Long otherId) {
    }
}
