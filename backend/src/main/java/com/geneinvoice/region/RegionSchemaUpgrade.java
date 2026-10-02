package com.geneinvoice.region;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.common.schema.SchemaSupport;
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
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Brings a database from before regions up to them (B1, §3.11): a default region, every existing customer
 * and its children in it, {@code region_id} made NOT NULL once nothing is missing, and every pre-existing
 * staff user given the grant that means "the role alone decides". Each step is its own; a failed one logs
 * and the next boot tries again.
 */
@Component
@Slf4j
class RegionSchemaUpgrade implements InitializingBean {

    static final long BACKFILL_ENTITY_ID = 0L;
    static final String BACKFILLED = "REGIONS_BACKFILLED";
    static final List<String> REGIONAL_TABLES =
            List.of("customers", "invoices", "payments", "payment_promises", "disputes");

    private static final int WINDOW = 1000;

    private final DataSource dataSource;
    private final AuditService auditService;
    private final Clock clock;

    RegionSchemaUpgrade(DataSource dataSource, AuditService auditService, Clock clock,
                        EntityManagerFactory schemaUpToDate) {
        this.dataSource = dataSource;
        this.auditService = auditService;
        this.clock = clock;
    }

    record Backfill(Long regionId, int customers, int invoices, int payments, int promises, int disputes,
                    int grants) {
        int total() {
            return customers + invoices + payments + promises + disputes + grants;
        }
    }

    @Override
    public void afterPropertiesSet() {
        Backfill done = null;
        try (Connection connection = dataSource.getConnection()) {
            done = upgrade(connection);
        } catch (SQLException e) {
            log.warn("Could not finish the region upgrade: {}", e.getMessage());
        }
        if (done != null && done.total() > 0) {
            Backfill backfill = done;
            WriteOrigins.run(WriteOrigin.schema(), () -> auditService.record(RegionService.ENTITY,
                    BACKFILL_ENTITY_ID, BACKFILLED, null, backfill, null, null,
                    "Put " + backfill.customers() + " customer(s), " + backfill.invoices() + " invoice(s), "
                            + backfill.payments() + " payment(s), " + backfill.promises() + " promise(s) and "
                            + backfill.disputes() + " dispute(s) in the default region, and gave "
                            + backfill.grants() + " existing user(s) access to it"));
        }
    }

    private Backfill upgrade(Connection connection) throws SQLException {
        Long regionId;
        try {
            regionId = ensureDefaultRegion(connection);
        } catch (SQLException e) {
            rollback(connection);
            log.warn("Could not create the default region: {}", e.getMessage());
            return null;
        }
        if (regionId == null) {
            log.warn("There is no default region; the region upgrade waits for the next start");
            return null;
        }
        int customers = step(connection, "customers", () -> fillCustomers(connection, regionId));
        int invoices = step(connection, "invoices", () -> fillFromCustomer(connection, "invoices"));
        int payments = step(connection, "payments", () -> fillFromCustomer(connection, "payments"));
        int promises = step(connection, "payment_promises", () -> fillFromCustomer(connection, "payment_promises"));
        int disputes = step(connection, "disputes", () -> fillFromCustomer(connection, "disputes"))
                + step(connection, "disputes", () -> fillOrphanDisputes(connection, regionId));
        for (String table : REGIONAL_TABLES) {
            try {
                SchemaSupport.enforceNotNull(connection, table, "region_id");
            } catch (SQLException e) {
                rollback(connection);
                log.warn("Could not make {}.region_id not null: {}", table, e.getMessage());
            }
        }
        int grants = step(connection, "user_region_grants", () -> grantLegacyUsers(connection, regionId));
        try {
            SchemaSupport.widen(connection, "user_region_grants", "access_level", AccessLevel.class);
            SchemaSupport.widen(connection, "user_region_grants", "record_scope", RecordScope.class);
        } catch (SQLException e) {
            rollback(connection);
            log.warn("Could not widen the grant enum columns: {}", e.getMessage());
        }
        return new Backfill(regionId, customers, invoices, payments, promises, disputes, grants);
    }

    @FunctionalInterface
    private interface Step {
        int run() throws SQLException;
    }

    private static int step(Connection connection, String table, Step step) {
        try {
            int changed = step.run();
            if (!connection.getAutoCommit()) connection.commit();
            return changed;
        } catch (SQLException e) {
            rollback(connection);
            log.warn("Could not backfill regions on {}: {}", table, e.getMessage());
            return 0;
        }
    }

    private Long ensureDefaultRegion(Connection connection) throws SQLException {
        Long id = firstId(connection, "select id from regions where is_default = true order by id");
        if (id == null) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    insert into regions (code, name, active, is_default, version, created_at)
                    values (?, 'Default region', true, true, 0, ?)
                    """)) {
                insert.setString(1, Region.DEFAULT_CODE);
                insert.setObject(2, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
                insert.executeUpdate();
                if (!connection.getAutoCommit()) connection.commit();
                log.info("Created the default region");
            } catch (SQLException e) {
                // Another node won the race on uk_region_code: make sure the row it made is the default.
                rollback(connection);
                try (PreparedStatement mark = connection.prepareStatement("""
                        update regions set is_default = true where code = ?
                           and not exists (select 1 from regions r where r.is_default = true)
                        """)) {
                    mark.setString(1, Region.DEFAULT_CODE);
                    mark.executeUpdate();
                    if (!connection.getAutoCommit()) connection.commit();
                }
            }
            id = firstId(connection, "select id from regions where is_default = true order by id");
        }
        if (SchemaSupport.product(connection).contains("postgresql")) {
            // The row exists whatever happens here, so a failure (two nodes building the index at once, say)
            // costs only the guard, not the backfill and grants that follow; the next start builds it.
            try (Statement statement = connection.createStatement()) {
                statement.execute("create unique index if not exists uk_regions_default on regions (is_default)"
                        + " where is_default");
                if (!connection.getAutoCommit()) connection.commit();
            } catch (SQLException e) {
                rollback(connection);
                log.warn("Could not create uk_regions_default; it is retried on the next start: {}", e.getMessage());
            }
        }
        return id;
    }

    private static int fillCustomers(Connection connection, Long regionId) throws SQLException {
        return inWindows(connection, "customers",
                "update customers set region_id = ? where region_id is null and id between ? and ?", regionId);
    }

    /** A child takes its customer's region (invariant I1), never simply the default. */
    private static int fillFromCustomer(Connection connection, String table) throws SQLException {
        return inWindows(connection, table, "update " + table + " set region_id = "
                + "(select c.region_id from customers c where c.id = " + table + ".customer_id)"
                + " where region_id is null and id between ? and ?"
                + " and exists (select 1 from customers c where c.id = " + table + ".customer_id"
                + " and c.region_id is not null)", null);
    }

    /** Disputes keep a plain customer id with no foreign key, so one may outlive its customer. */
    private static int fillOrphanDisputes(Connection connection, Long regionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update disputes set region_id = ? where region_id is null")) {
            statement.setLong(1, regionId);
            return statement.executeUpdate();
        }
    }

    private static int inWindows(Connection connection, String table, String sql, Long regionId)
            throws SQLException {
        long low;
        long high;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select min(id), max(id) from " + table + " where region_id is null")) {
            if (!rows.next() || rows.getObject(1) == null) return 0;
            low = rows.getLong(1);
            high = rows.getLong(2);
        }
        int changed = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (long from = low; from <= high; from += WINDOW) {
                int i = 1;
                if (regionId != null) statement.setLong(i++, regionId);
                statement.setLong(i++, from);
                statement.setLong(i, from + WINDOW - 1);
                changed += statement.executeUpdate();
                if (!connection.getAutoCommit()) connection.commit();
            }
        }
        if (changed > 0) log.info("Put {} row(s) of {} in a region", changed, table);
        return changed;
    }

    /**
     * Every staff user from before regions gets the default region at APPROVE/ALL, which reduces every rule to
     * the role alone, as before. The flag is then cleared, so a user later left without grants on purpose is
     * never granted again (F6). Customer logins get nothing: they take their customer's region.
     */
    private int grantLegacyUsers(Connection connection, Long regionId) throws SQLException {
        int granted;
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into user_region_grants (user_id, region_id, access_level, record_scope, granted_at)
                select u.id, ?, 'APPROVE', 'ALL', ? from users u
                 where u.region_backfill_pending = true and u.customer_id is null
                   and not exists (select 1 from user_region_grants g where g.user_id = u.id and g.region_id = ?)
                """)) {
            statement.setLong(1, regionId);
            statement.setObject(2, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            statement.setLong(3, regionId);
            granted = statement.executeUpdate();
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "update users set region_backfill_pending = false where region_backfill_pending = true");
        }
        if (granted > 0) log.info("Gave {} existing user(s) access to the default region", granted);
        return granted;
    }

    private static Long firstId(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : null;
        }
    }

    private static void rollback(Connection connection) {
        try {
            if (!connection.getAutoCommit()) connection.rollback();
        } catch (SQLException ignored) {
        }
    }
}
