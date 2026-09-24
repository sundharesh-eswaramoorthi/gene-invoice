package com.geneinvoice.history;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.asof.AsOf;
import com.geneinvoice.common.asof.AsOfContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Savepoint;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Closes the version that was current and opens the one that is: the only place in this
 * application where a bug corrupts data rather than returning a wrong number (B3).
 *
 * <h2>Why a unique constraint and not a lock</h2>
 * Under READ COMMITTED on Postgres, a second writer's {@code select ... where valid_to = OPEN for
 * update} re-evaluates only the row it blocked on and never sees the first writer's freshly
 * INSERTED open row — so it would insert a SECOND open row, and every as-of count and every
 * as-of total over that record would silently double for ever. A unique index is phantom-proof:
 * the duplicate INSERT blocks until the first writer commits and then fails, and the bounded
 * retry below re-reads a fresh snapshot and appends after it. Nothing is locked here at all.
 *
 * <p>Locking the BUSINESS row instead would be worse than useless: this code runs at
 * beforeCommit, after the transaction has already taken every lock it is going to take, so
 * acquiring another one in an order nobody else follows is an undischargeable lock-ordering
 * hazard against PPD-01's customer-then-record discipline.
 *
 * <p>{@link #MAX_ATTEMPTS} is 5. Exhausting it throws, which rolls the user's transaction back,
 * and that is the right answer: a mirror that disagrees with the record is worse than a failed
 * save, because the record can be saved again and a wrong past cannot be noticed.
 *
 * <p>WHO THE RACE IS ACTUALLY WITH, said out loud because the argument above reads as though it
 * were writer against writer. It is not, or not usually: two transactions can only both be about
 * to mirror record X if they have both written row X, and the database serialised them on that
 * row long before either drain began. The real second party is anything that writes a mirror row
 * WITHOUT holding the business row — the fifteen-minute reconciler B3-UPGRADES adds, a repair run
 * by hand, a second application instance replaying a seed. The retry is defence in depth for
 * those, and {@code HistoryWriteTest} says the same thing about what its tests do and do not
 * prove (B3).
 *
 * <p>A savepoint per record per transaction is the price. On Postgres a savepoint is a
 * subtransaction, and a transaction that touched hundreds of mirrored records would accumulate
 * hundreds of them; the bulk path is one REQUIRES_NEW transaction per row, which keeps the count
 * to a handful, and no other path in this application writes at that scale. Measured on nothing:
 * stated as a known cost rather than as a measurement (B3).
 *
 * <h2>Why the statements are raw JDBC</h2>
 * The drain runs after Hibernate has flushed, on the transaction's OWN connection, and must not
 * put anything back into the persistence context — a managed mirror row would be dirty-checked
 * and flushed again on the way out. It also sidesteps the flush-ordering trap this codebase has
 * already been bitten by: Hibernate orders every INSERT before every UPDATE within one flush, so
 * a close-then-open pair expressed as two JPA saves fails {@code uk_<x>h_open} on Postgres while
 * every H2 test passes. Two JDBC statements execute in the order they are sent (B3).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HistoryWriter {

    /** A concurrent writer can beat us this many times before we give the user their save back. */
    static final int MAX_ATTEMPTS = 5;

    private final DataSource dataSource;
    private final HistoryRegistry registry;
    private final HistoryClock clock;
    private final CurrentUser currentUser;

    @PersistenceContext
    private EntityManager entityManager;

    /** Fires the Hibernate listeners that fill the buffer. Called by the synchronization only. */
    void flush() {
        entityManager.flush();
    }

    /**
     * Snapshot, close, open — once per record this transaction touched, in a deterministic order.
     *
     * <p>THE ORDER IS NOT COSMETIC. Two transactions that both touch the same pair of records
     * must queue the same way, or each can be waiting on the open row the other is about to
     * rewrite and the bounded retry turns into a livelock rather than a delay (B3).
     */
    public void drain(HistoryBuffer buffer) {
        // The third of the three guards on the ambient read-side clock, and the loudest: as-of is
        // a READ mode, and a mirror row dated from a replayed past would make the past disagree
        // with itself. The other two are the interceptor's 400 on every non-GET carrying asOf and
        // InvoiceDates.todayForWrite (B3).
        if (AsOfContext.isActive()) {
            throw new IllegalStateException("as-of is a read mode and writes nothing (B3)");
        }
        if (buffer.isEmpty()) return;
        Long actor = currentUser.idOrNull();
        // ONE INSTANT FOR THE WHOLE TRANSACTION, and this is not an optimisation.
        //
        // A save that touches three records is ONE change to the book, and an as-of read landing
        // between two of them would see a half-applied transaction: a promise covering an invoice
        // it had not yet been linked to, or a payment whose invoice had not yet been paid down.
        // Reading the clock per record made exactly that visible — the link mirror's two rows
        // came out a few microseconds apart and "as of the instant the promise was made it
        // covered two invoices" was true only when the rows happened to be written in one of the
        // two possible orders (B3).
        //
        // Truncated to the grain both databases store (timestamp(6)), so what is compared in Java
        // is what the column will hold and a step of one microsecond is a real step rather than a
        // rounding that disappears on the way in.
        Instant at = clock.now().truncatedTo(ChronoUnit.MICROS);
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            for (HistoryBuffer.Key key : buffer.keysSorted()) {
                writeOne(connection, key, buffer.parts(key), at, actor);
            }
        } catch (SQLException e) {
            // Rolls the user's save back, on purpose. See the class Javadoc (B3).
            throw new IllegalStateException("The history mirror could not be written (B3)", e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private void writeOne(Connection c, HistoryBuffer.Key key, Set<HistoryBuffer.Part> parts,
                          Instant at, Long actor) throws SQLException {
        HistoryBinding binding = registry.forType(key.entityType());
        if (binding == null) return;
        // Managed and already flushed, so every lazy association a projector reads is still
        // reachable through the open session. Null means the row was deleted in this very
        // transaction, and a tombstone is what a deleted record looks like in an interval
        // table (B3).
        Object live = entityManager.find(binding.entityClass(), key.id());

        if (parts.contains(HistoryBuffer.Part.ROW)) {
            writeVersion(c, binding, key.id(), null, live, at, actor);
        }
        // A deleted owner takes its join tables with it whether or not Hibernate raised a
        // collection event for them, and it does not raise one for every mapping. Reconciling
        // the links of a vanished owner finds no live rows and tombstones all of them (B3).
        if (parts.contains(HistoryBuffer.Part.LINKS) || live == null) {
            for (HistoryBinding link : registry.linksOf(key.entityType())) {
                writeLinks(c, link, key.id(), at, actor);
            }
        }
    }

    // ---- one version of one record ------------------------------------------------------------

    /**
     * @param otherId the second half of a link mirror's business key, or null for a primary
     *                mirror. {@code live} is the entity to project, or null for a tombstone.
     */
    private void writeVersion(Connection c, HistoryBinding b, Long id, Long otherId, Object live,
                              Instant at, Long actor) throws SQLException {
        // CARRIED ACROSS THE RETRY, never re-read from the clock, and that is a deliberate
        // departure from the design's sketch. An attempt that fails leaves its close APPLIED —
        // the savepoint is taken after it — so a fresh reading on the next attempt would open the
        // successor later than the predecessor was closed, and an as-of read landing in that gap
        // would find no version at all of a record that never went anywhere. Only ever stepped
        // FORWARD, past whatever the writer that beat us opened (B3).
        Instant t = at;
        for (int attempt = 1; ; attempt++) {
            Open open = openRow(c, b, id, otherId);
            // Two changes inside one microsecond still have to give a strictly increasing,
            // non-overlapping interval, or [validFrom, validTo) is empty and an as-of read at
            // that instant answers with neither version (B3).
            if (open != null && !t.isAfter(open.validFrom())) t = open.validFrom().plusNanos(1_000);

            close(c, b, id, otherId, t);
            // AFTER the close and not before it. Rolling back to a savepoint taken earlier would
            // undo the close as well, leaving the row we closed and the row the other writer
            // opened both open at once — the exact state the unique constraint exists to make
            // impossible, transiently invisible to it because a rollback re-checks nothing (B3).
            Savepoint savepoint = c.setSavepoint();
            try {
                // A tombstone carries the values the record had when it went, copied forward off
                // the row we just closed, so the mirror SERVES a deleted record as of a date
                // before it went instead of merely disclosing that it existed. Every business
                // column on every mirror is nullable, so a row of nulls would also have been
                // accepted — and would have made a tombstone invisible to a region-narrowed
                // caller, whose axis reads customer_id (B3).
                List<Object> values = live != null
                        ? project(b, live)
                        : (open != null ? open.values() : Collections.nCopies(b.mirrorColumns().size(), null));
                insert(c, b, id, otherId, values, t, live == null, actor);
                c.releaseSavepoint(savepoint);
                return;
            } catch (SQLException e) {
                c.rollback(savepoint);
                if (!isUniqueViolation(e) || attempt >= MAX_ATTEMPTS) throw e;
                // uk_<x>h_open: somebody opened a row between our close and our insert. Nothing
                // is locked and nothing is waited on; the index did the serialising, and the next
                // attempt reads that writer's committed interval and appends after it (B3).
                log.debug("History mirror retry {} on {} {} (B3)", attempt, b.mirrorTable(), id);
            }
        }
    }

    /**
     * The values a projector pulls off the managed entity, in the binding's declared order. On a
     * link mirror {@code live} is a {@link HistoryBinding.Link} and not an entity, because the
     * join table has none and there is nothing else to read (B3).
     */
    private List<Object> project(HistoryBinding b, Object live) {
        HistoryJdbc.Row row = HistoryJdbc.row(b.mirrorColumns());
        b.projector().project(live, row);
        return row.ordered();
    }

    // ---- the join tables an owner carries ------------------------------------------------------

    /**
     * Rewrites one link mirror to agree with the join table, which is the only honest way to
     * mirror a {@code @ManyToMany}: a collection event says the collection changed and not WHICH
     * side changed, so the drain reads the flushed join table and diffs it against the rows the
     * mirror currently holds open. Idempotent, so a promise saved with no change to its coverage
     * writes nothing at all (B3).
     */
    private void writeLinks(Connection c, HistoryBinding b, Long ownerId, Instant at, Long actor)
            throws SQLException {
        String otherColumn = b.mirrorColumns().get(0);
        Set<Long> liveIds = liveLinks(c, b, ownerId, otherColumn);
        Map<Long, Boolean> openIds = openLinks(c, b, ownerId, otherColumn);

        for (Long other : liveIds) {
            // Already open and not a tombstone: nothing about this link has changed (B3).
            if (Boolean.FALSE.equals(openIds.get(other))) continue;
            writeVersion(c, b, ownerId, other, new HistoryBinding.Link(ownerId, other), at, actor);
        }
        for (Map.Entry<Long, Boolean> open : openIds.entrySet()) {
            if (Boolean.TRUE.equals(open.getValue())) continue;      // already a tombstone
            if (liveIds.contains(open.getKey())) continue;
            writeVersion(c, b, ownerId, open.getKey(), null, at, actor);
        }
    }

    private Set<Long> liveLinks(Connection c, HistoryBinding b, Long ownerId, String otherColumn)
            throws SQLException {
        Set<Long> ids = new LinkedHashSet<>();
        String sql = "select " + otherColumn + " from " + b.liveTable()
                + " where " + b.businessIdColumn() + " = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, ownerId);
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) ids.add(rows.getLong(1));
            }
        }
        return ids;
    }

    /** The other id of every OPEN row of this link mirror, mapped to whether it is a tombstone. */
    private Map<Long, Boolean> openLinks(Connection c, HistoryBinding b, Long ownerId,
                                         String otherColumn) throws SQLException {
        Map<Long, Boolean> open = new LinkedHashMap<>();
        String sql = "select " + otherColumn + ", deleted from " + b.mirrorTable()
                + " where " + b.businessIdColumn() + " = ? and valid_to = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, ownerId);
            HistoryJdbc.setInstant(ps, 2, AsOf.OPEN);
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) open.put(rows.getLong(1), rows.getBoolean(2));
            }
        }
        return open;
    }

    // ---- the three statements -------------------------------------------------------------------

    /** The open row's own start and its business values, or null when this record has none yet. */
    private Open openRow(Connection c, HistoryBinding b, Long id, Long otherId) throws SQLException {
        List<String> columns = b.mirrorColumns();
        String sql = "select valid_from"
                + (columns.isEmpty() ? "" : ", " + String.join(", ", columns))
                + " from " + b.mirrorTable() + " where " + b.businessIdColumn() + " = ?"
                + (otherId == null ? "" : " and " + columns.get(0) + " = ?")
                + " and valid_to = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int p = 1;
            ps.setLong(p++, id);
            if (otherId != null) ps.setLong(p++, otherId);
            HistoryJdbc.setInstant(ps, p, AsOf.OPEN);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) return null;
                return new Open(HistoryJdbc.getInstant(rows, 1), read(rows, columns.size()));
            }
        }
    }

    private void close(Connection c, HistoryBinding b, Long id, Long otherId, Instant t)
            throws SQLException {
        String sql = "update " + b.mirrorTable() + " set valid_to = ?"
                + " where " + b.businessIdColumn() + " = ?"
                + (otherId == null ? "" : " and " + b.mirrorColumns().get(0) + " = ?")
                + " and valid_to = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int p = 1;
            HistoryJdbc.setInstant(ps, p++, t);
            ps.setLong(p++, id);
            if (otherId != null) ps.setLong(p++, otherId);
            HistoryJdbc.setInstant(ps, p, AsOf.OPEN);
            ps.executeUpdate();
        }
    }

    /**
     * Package-private and overridable ON PURPOSE, and this is the second and last named seam on
     * the write path after {@link HistoryClock}.
     *
     * <p>The window this method opens — between the close and the insert — is the ONLY window the
     * retry above exists for, and it cannot be reached from a test through the business path: two
     * transactions that both mirror record X have both written row X, so the database has already
     * serialised them on that row and the second one's drain begins after the first one's commit.
     * A test that wants to prove the retry works has to put a committed open row in that window
     * itself, and the honest way to let it is to say so here rather than to claim a race the
     * suite has never run (B3).
     */
    void insert(Connection c, HistoryBinding b, Long id, Long otherId, List<Object> values,
                Instant t, boolean deleted, Long actor) throws SQLException {
        List<String> columns = b.mirrorColumns();
        String names = b.businessIdColumn()
                + ", valid_from, valid_to, deleted, drifted, changed_by_user_id"
                + (columns.isEmpty() ? "" : ", " + String.join(", ", columns));
        String sql = "insert into " + b.mirrorTable() + " (" + names + ") values ("
                + "?, ?, ?, ?, ?, ?" + ", ?".repeat(columns.size()) + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int p = 1;
            ps.setLong(p++, id);
            HistoryJdbc.setInstant(ps, p++, t);
            // AsOf.OPEN exactly, never null: it is both the right-hand side of the interval test
            // and the key of uk_<x>h_open, and a second definition of it that drifted by a
            // nanosecond would silently double every as-of count (B3).
            HistoryJdbc.setInstant(ps, p++, AsOf.OPEN);
            ps.setBoolean(p++, deleted);
            // drifted is false here always: this row was observed as it happened. Only the
            // reconciler writes true, and only for a repair it inferred afterwards (B3).
            ps.setBoolean(p++, false);
            if (actor == null) ps.setNull(p++, Types.BIGINT); else ps.setLong(p++, actor);
            for (Object value : values) bind(ps, p++, value);
            ps.executeUpdate();
        }
    }

    // ---- JDBC plumbing ---------------------------------------------------------------------------

    /**
     * An Instant is written through {@link HistoryJdbc#setInstant}, which names UTC explicitly,
     * because {@code java.sql.Timestamp} carries no zone and a driver converts it through the
     * JVM's default one — the bug a previous unit shipped into the region backfill and had to fix.
     * Everything else JDBC already understands (B3).
     */
    private void bind(PreparedStatement ps, int index, Object value) throws SQLException {
        if (value instanceof Instant at) {
            HistoryJdbc.setInstant(ps, index, at);
        } else {
            ps.setObject(index, value);
        }
    }

    /**
     * Reads the open row's business values back in a shape that can be written out again without
     * passing a timestamp through the JVM's zone on the way — which is what a tombstone does when
     * it copies the last known values forward (B3).
     */
    private List<Object> read(ResultSet rows, int count) throws SQLException {
        ResultSetMetaData meta = rows.getMetaData();
        List<Object> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int column = i + 2;                                  // column 1 is valid_from
            int type = meta.getColumnType(column);
            if (type == Types.TIMESTAMP || type == Types.TIMESTAMP_WITH_TIMEZONE) {
                values.add(HistoryJdbc.getInstant(rows, column));
            } else if (type == Types.DATE) {
                values.add(rows.getObject(column, LocalDate.class));
            } else {
                values.add(rows.getObject(column));
            }
        }
        return values;
    }

    /**
     * SQLSTATE class 23 is "integrity constraint violation" in the standard and is what both
     * Postgres (23505) and H2 (23505) report for a duplicate key, so the retry does not depend on
     * a driver-specific error code. A violation that is NOT uk_<x>h_open cannot be produced by
     * these statements — the mirrors hold no foreign key and no other unique constraint at all —
     * so narrowing it further would only add a constraint name to keep in step (B3).
     */
    private boolean isUniqueViolation(SQLException e) {
        for (SQLException next = e; next != null; next = next.getNextException()) {
            if (next instanceof SQLIntegrityConstraintViolationException) return true;
            String state = next.getSQLState();
            if (state != null && state.startsWith("23")) return true;
        }
        return false;
    }

    /** The open row as the writer needs it: when it started, and what it says. */
    private record Open(Instant validFrom, List<Object> values) {
    }
}
