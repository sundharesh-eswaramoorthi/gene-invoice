package com.geneinvoice.region;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.common.SchemaSupport;
import com.geneinvoice.invoice.InvoiceDates;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Gives every customer that predates regions a region, dates its placement from its own creation,
 * staffs the people who were already working, and only then makes customers.region_id NOT NULL.
 *
 * <p>Raw JDBC and an unused EntityManagerFactory parameter, the InvoiceSchemaUpgrade idiom: the
 * parameter is what orders this bean after Hibernate's schema export, because the column it fills
 * does not exist until ddl-auto has added it. Every step is idempotent and NOTHING here throws —
 * a half-finished migration is reported by RegionCoverageCheck refusing to serve traffic, not by
 * a stack trace during startup that leaves the operator with no application at all (B1).
 */
@Component
@Slf4j
class RegionSchemaUpgrade implements InitializingBean {

    // There is no RegionService yet to own the constant, and the audit row is about the region
    // dimension as a whole rather than about one region, hence the entity id 0 the invoice
    // due-date backfill already uses for exactly this (B1).
    static final String ENTITY = "REGION";
    static final long BACKFILL_ENTITY_ID = 0L;

    private static final int CHUNK = 1000;

    private static final String SEED_REASON = "Backfill: one region before branches existed (B1)";

    /** An account with no created_at predates the column itself, so its placement opens before
     *  anything this company has ever done (B1). */
    private static final LocalDate BEFORE_TIME = LocalDate.of(1970, 1, 1);

    private final DataSource dataSource;
    private final RegionProperties properties;
    private final AuditService auditService;

    RegionSchemaUpgrade(DataSource dataSource, RegionProperties properties,
                        AuditService auditService, EntityManagerFactory schemaUpToDate) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.auditService = auditService;
    }

    @Override
    public void afterPropertiesSet() {
        // Every statement below is raw JDBC and bypasses the query funnel, but the audit row at
        // the end goes through a service, and this runs at boot with no principal at all — where
        // the region predicate denies rather than guesses. The hatch says which caller-free path
        // this is, and SystemReason is enumerable so a reviewer can see all of them (B1).
        RegionScope.asSystem(RegionScope.SystemReason.SCHEMA_UPGRADE, this::upgrade);
    }

    private void upgrade() {
        int filled = 0;
        int opened = 0;
        int granted = 0;
        try (Connection connection = dataSource.getConnection()) {
            long region = seedDefaultRegion(connection, properties.defaultCode(), properties.defaultName());
            filled = backfillCustomerRegions(connection, region);
            opened = openHistoryRows(connection);
            granted = grantExistingStaff(connection, region);
            enforceRegionNotNull(connection);
            addRegionIndexes(connection);
            closeDuplicateOpenIntervals(connection);
            addPartialIndexes(connection);
            // Pre-emptive, on a table whose check constraint is correct today: ddl-auto never
            // rewrites a constraint it has already created, so whoever adds a fourth RegionRight
            // in 2027 would otherwise have every grant insert rejected on the deploy that adds
            // it, with no clue why. The call is already wired, so they inherit a working column.
            SchemaSupport.widen(connection, "user_region_grants", "right_level", RegionRight.class);
        } catch (SQLException e) {
            log.warn("Could not finish the region upgrade: {}", e.getMessage());
        }
        if (filled > 0) {
            auditService.record(ENTITY, BACKFILL_ENTITY_ID, "REGIONS_BACKFILLED",
                    null, new Backfill(filled, opened, granted, properties.defaultCode()), null, null,
                    "Placed " + filled + " customers in the default region (B1)");
        }
    }

    record Backfill(int customers, int placements, int grants, String regionCode) {}

    /**
     * Seeds the default region only when there is no region at all. An operator who has already
     * made their own regions is not given a second, unwanted one; the backfill then finds no
     * default to use and stops, which RegionCoverageCheck reports (B1).
     */
    static long seedDefaultRegion(Connection connection, String code, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into regions (code, name, active, created_at)"
                        + " select ?, ?, true, ? where not exists (select 1 from regions)")) {
            statement.setString(1, code);
            statement.setString(2, name);
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            int seeded = statement.executeUpdate();
            if (!connection.getAutoCommit()) connection.commit();
            if (seeded > 0) log.info("Seeded the default region {} ({})", code, name);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from regions where code = ?")) {
            statement.setString(1, code);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) return rows.getLong(1);
            }
        }
        log.warn("No region has the code {}, so no customer can be placed in one", code);
        return 0L;
    }

    /**
     * Keyset-paged and committed a chunk at a time, the InvoiceSchemaUpgrade.backfill shape: a
     * single UPDATE over a large customers table would hold one lock across the whole migration
     * and a failure halfway would undo all of it (B1).
     */
    static int backfillCustomerRegions(Connection connection, long regionId) throws SQLException {
        if (regionId <= 0) return 0;
        int filled = 0;
        long after = 0;
        while (true) {
            List<Long> chunk = unplaced(connection, after, CHUNK);
            if (chunk.isEmpty()) break;
            filled += place(connection, chunk, regionId);
            if (!connection.getAutoCommit()) connection.commit();
            after = chunk.get(chunk.size() - 1);
            if (chunk.size() == CHUNK) log.info("Placed {} customers so far", filled);
        }
        if (filled > 0) log.info("Placed {} customers in region {}", filled, regionId);
        return filled;
    }

    static List<Long> unplaced(Connection connection, long afterId, int limit) throws SQLException {
        List<Long> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from customers where id > ? and region_id is null order by id limit ?")) {
            statement.setLong(1, afterId);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) out.add(rows.getLong(1));
            }
        }
        return out;
    }

    private static int place(Connection connection, List<Long> chunk, long regionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update customers set region_id = ? where id = ? and region_id is null")) {
            for (Long id : chunk) {
                statement.setLong(1, regionId);
                statement.setLong(2, id);
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
     * One open placement per customer, dated from the customer's OWN created_at rather than from
     * install day. An as-of read of last March must find the account in the region it has always
     * been in; dating every row today would make the whole company look as though it moved on the
     * morning of the upgrade (B1, B3 INTEGRATION).
     */
    static int openHistoryRows(Connection connection) throws SQLException {
        int opened = 0;
        long after = 0;
        while (true) {
            List<Opening> chunk = unopened(connection, after, CHUNK);
            if (chunk.isEmpty()) break;
            opened += open(connection, chunk);
            if (!connection.getAutoCommit()) connection.commit();
            after = chunk.get(chunk.size() - 1).customerId();
        }
        if (opened > 0) log.info("Opened {} customer placement(s), dated from each account's creation", opened);
        return opened;
    }

    /** One account's opening placement, with the date already resolved — in UTC, in Java (B1). */
    record Opening(long customerId, long regionId, LocalDate validFrom) {}

    /**
     * The date is computed HERE and not by the database. {@code cast(created_at as date)} resolves
     * in the SESSION time zone, which both drivers take from the JVM's default zone, while every
     * other date in this application is a UTC day — InvoiceDates.today(), todayForWrite() and
     * dayOf() are all pinned to ZoneOffset.UTC. A migration run at 20:00 in a UTC+5:30 JVM would
     * otherwise open every account created that evening on TOMORROW's date, which
     * RegionCustodyService.move then refuses to move ("a move cannot be dated before the account
     * arrived here") and which B3's as-of read would answer with no placement at all. The two
     * engines agree with each other and disagree with the application, so no amount of SQL is the
     * fix: the ledger is dated by the same clock that reads it (B1, B3 INTEGRATION).
     */
    private static List<Opening> unopened(Connection connection, long afterId, int limit)
            throws SQLException {
        List<Opening> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select c.id, c.region_id, c.created_at from customers c"
                        + " where c.id > ? and c.region_id is not null"
                        + "   and not exists (select 1 from customer_region_history h"
                        + "                    where h.customer_id = c.id and h.valid_to is null)"
                        + " order by c.id limit ?")) {
            statement.setLong(1, afterId);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    OffsetDateTime created = rows.getObject(3, OffsetDateTime.class);
                    // A customer with no created_at predates the column itself; 1970 is before any
                    // invoice this company has ever raised, so the interval covers it all.
                    out.add(new Opening(rows.getLong(1), rows.getLong(2),
                            created == null ? BEFORE_TIME : InvoiceDates.dayOf(created.toInstant())));
                }
            }
        }
        return out;
    }

    private static int open(Connection connection, List<Opening> chunk) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into customer_region_history"
                        + " (customer_id, region_id, valid_from, reason, created_at)"
                        + " values (?, ?, ?, ?, ?)")) {
            Timestamp now = Timestamp.from(Instant.now());
            for (Opening opening : chunk) {
                statement.setLong(1, opening.customerId());
                statement.setLong(2, opening.regionId());
                statement.setObject(3, opening.validFrom());
                statement.setString(4, SEED_REASON);
                statement.setTimestamp(5, now);
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
     * Behaviour-preserving without making the feature a no-op. Administrators — the people whose
     * role holds USER_MANAGE — get the null-region wildcard so nothing can lock the company out of
     * its own data. Everybody else gets the DEFAULT REGION ONLY: identical behaviour while there
     * is one region, and the day an operator opens WEST nobody sees it until they are staffed,
     * which is the discoverable answer rather than a silent widening. Customer logins get nothing:
     * their reach is their own account, which the POC book and customer_id already decide (B1).
     */
    static int grantExistingStaff(Connection connection, long regionId) throws SQLException {
        int granted = wildcardForAdministrators(connection);
        granted += defaultRegionForEveryoneElse(connection, regionId);
        if (!connection.getAutoCommit()) connection.commit();
        if (granted > 0) {
            log.info("Granted {} existing staff user(s) their region rights; review them by hand", granted);
        }
        return granted;
    }

    /**
     * TWO wildcard rows, because the ladder is deliberately not a total order: APPROVE does not
     * cover MANAGE, so an administrator given APPROVE alone could sign changes off in every branch
     * and raise an invoice in none — the authority-drop rule would take every MANAGE-level
     * privilege off them on the deploy that added regions. Each level is guarded on its own so the
     * pair is idempotent; an operator who takes one of the two away keeps it away (B1, R3).
     */
    private static int wildcardForAdministrators(Connection connection) throws SQLException {
        return wildcardForAdministrators(connection, RegionRight.MANAGE)
                + wildcardForAdministrators(connection, RegionRight.APPROVE);
    }

    private static int wildcardForAdministrators(Connection connection, RegionRight right) throws SQLException {
        // The level is an enum constant's own name and never anything a caller typed, so it is a
        // literal: H2 holds right_level as a native ENUM and will not take it as a parameter (B1).
        String level = right.name();
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into user_region_grants (user_id, region_id, right_level, created_at)"
                        + " select u.id, null, '" + level + "', ? from users u"
                        + "  where u.customer_id is null"
                        + "    and exists (select 1 from role_privileges rp"
                        + "                  join privileges p on p.id = rp.privilege_id"
                        + "                 where rp.role_id = u.role_id and p.name = 'USER_MANAGE')"
                        + "    and not exists (select 1 from user_region_grants g"
                        + "                     where g.user_id = u.id and g.region_id is null"
                        + "                       and g.right_level = '" + level + "')")) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            return statement.executeUpdate();
        }
    }

    private static int defaultRegionForEveryoneElse(Connection connection, long regionId) throws SQLException {
        if (regionId <= 0) return 0;
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into user_region_grants (user_id, region_id, right_level, created_at)"
                        // The level their role already implies: anyone who may change a record
                        // keeps being able to, anyone who may only read keeps only reading.
                        + " select u.id, ?, case when exists"
                        + "            (select 1 from role_privileges rp"
                        + "               join privileges p on p.id = rp.privilege_id"
                        + "              where rp.role_id = u.role_id"
                        + "                and p.name like '%\\_MANAGE' escape '\\')"
                        + "        then 'MANAGE' else 'VIEW' end, ?"
                        + "   from users u"
                        + "  where u.customer_id is null"
                        + "    and not exists (select 1 from user_region_grants g where g.user_id = u.id)")) {
            statement.setLong(1, regionId);
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            return statement.executeUpdate();
        }
    }

    /**
     * The whole point of the unit: until this runs, a customer can be saved with no region and a
     * region-scoped read would silently skip it. Guarded inside SchemaSupport.enforceNotNull, and
     * a failure is a WARN rather than a refusal to start, because RegionCoverageCheck is the bean
     * whose job is to stop the application on an unfinished migration (B1).
     */
    static void enforceRegionNotNull(Connection connection) throws SQLException {
        SchemaSupport.enforceNotNull(connection, "customers", "region_id", "bigint");
    }

    /**
     * idx_invoice_customer and idx_payment_customer do not exist today and are load-bearing, not
     * housekeeping: every invoice and payment list joins customers twice per request from R4
     * onwards, once for the page and once for the count (B1).
     */
    static void addRegionIndexes(Connection connection) throws SQLException {
        SchemaSupport.indexIfMissing(connection, "idx_customer_region", "customers", "region_id");
        SchemaSupport.indexIfMissing(connection, "idx_invoice_customer", "invoices", "customer_id");
        SchemaSupport.indexIfMissing(connection, "idx_payment_customer", "payments", "customer_id");
    }

    /**
     * Exactly one open placement per customer, the PocSchemaUpgrade.demoteDuplicatePrimaries
     * shape. The NEWEST open row wins because it is the latest thing anyone said about the
     * account; the older ones are closed at their own valid_from, which makes them zero-length
     * intervals that no as-of read can ever match, rather than deleted rows nobody can audit (B1).
     */
    static int closeDuplicateOpenIntervals(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            int closed = statement.executeUpdate("""
                    update customer_region_history set valid_to = valid_from
                     where valid_to is null
                       and id < (select max(other.id) from customer_region_history other
                                  where other.customer_id = customer_region_history.customer_id
                                    and other.valid_to is null)
                    """);
            if (!connection.getAutoCommit()) connection.commit();
            if (closed > 0) {
                log.warn("Closed {} duplicate open placement(s); the newest of each stayed open", closed);
            }
            return closed;
        } catch (SQLException e) {
            log.warn("Could not close duplicate open placements: {}", e.getMessage());
            return 0;
        }
    }

    /** One open placement per customer — the invariant RegionCustodyService holds under the row lock. */
    static final String UK_CRH_OPEN = "customer_region_history (customer_id) where valid_to is null";

    /**
     * right_level is part of BOTH grant keys, because the invariant is "no DUPLICATE grant row"
     * and not "one row per user per region". The ladder is deliberately not a total order, so
     * anyone who must both work and approve in a branch holds two rows there (RegionRight) — and
     * {@link #wildcardForAdministrators} writes every administrator TWO wildcard rows for exactly
     * that reason. Keyed on (user_id, region_id) alone, uk_grant_all could not be created at all
     * on any database that has an administrator, and uk_grant_region rejected the grant editor's
     * own two-right save with a 409. Neither shows up on H2, which has no partial index (B1).
     */
    static final String UK_GRANT_REGION =
            "user_region_grants (user_id, region_id, right_level) where region_id is not null";

    static final String UK_GRANT_ALL =
            "user_region_grants (user_id, right_level) where region_id is null";

    /**
     * Postgres only, the PocSchemaUpgrade.addPrimaryIndex asymmetry: a partial unique index is the
     * only way to say "one open row" and "no duplicate grant row" in the database itself. On H2
     * those invariants are held by the customer row lock in RegionCustodyService and by the
     * not-exists guards above, which is why no test may assert them through the repository (B1).
     */
    static void addPartialIndexes(Connection connection) throws SQLException {
        if (!SchemaSupport.product(connection).contains("postgresql")) return;
        // Both grant indexes were first written without right_level. A database that took the
        // narrower one keeps it forever otherwise, because 'if not exists' looks only at the name.
        dropIndexPredating(connection, "uk_grant_region", "right_level");
        dropIndexPredating(connection, "uk_grant_all", "right_level");
        partialIndex(connection, "uk_crh_open", UK_CRH_OPEN);
        partialIndex(connection, "uk_grant_region", UK_GRANT_REGION);
        partialIndex(connection, "uk_grant_all", UK_GRANT_ALL);
    }

    private static void partialIndex(Connection connection, String name, String definition) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create unique index if not exists " + name + " on " + definition);
            if (!connection.getAutoCommit()) connection.commit();
        } catch (SQLException e) {
            log.warn("Could not create {}: {}", name, e.getMessage());
        }
    }

    /**
     * Drops an index this upgrade created in an earlier, narrower shape, so that a corrected
     * definition can reach a database that already took the old one. {@code create unique index if
     * not exists} is a no-op against a name that is already taken, however wrong the columns behind
     * it are, and a wrong index that cannot be created is only a WARN nobody reads — which is how
     * uk_grant_all came to be missing from every Postgres database B1 had ever touched (B1).
     */
    private static void dropIndexPredating(Connection connection, String name, String column) {
        boolean stale;
        try (PreparedStatement statement = connection.prepareStatement(
                "select indexdef from pg_indexes where schemaname = current_schema() and indexname = ?")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                stale = rows.next() && !rows.getString(1).contains(column);
            }
        } catch (SQLException e) {
            log.warn("Could not read the definition of {}: {}", name, e.getMessage());
            return;
        }
        if (!stale) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute("drop index " + name);
            if (!connection.getAutoCommit()) connection.commit();
            log.info("Dropped {}; it predates {} being part of the key", name, column);
        } catch (SQLException e) {
            log.warn("Could not drop {}: {}", name, e.getMessage());
        }
    }
}
