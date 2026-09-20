package com.geneinvoice.poc;

import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Makes "at most one primary seat per customer and kind" the database's own rule (CP-02).
 *
 * <p>The service serialises every change to who is primary behind the customer's row lock, which
 * is what stops a second one appearing. This is the invariant underneath: a partial unique index
 * on {@code (customer_id, poc_type) where is_primary}, so no path — a future one, a script, a
 * hand-written statement — can leave two behind. It is preceded by a one-off repair that demotes
 * all but the oldest primary of each group, so a database that already holds a pair is mended
 * rather than left unable to build the index.
 *
 * <p>The index is Postgres's; H2 has no filtered index, so there the repair runs and the rule
 * lives only in the service. It runs once the schema is up to date (which is what the entity
 * manager factory in the constructor is for) and before the app takes requests, changes nothing
 * on a database that has been through it, and never fails startup.
 */
@Component
@Slf4j
class PocSchemaUpgrade implements InitializingBean {

    static final String INDEX = "uk_customer_poc_primary";

    private final DataSource dataSource;

    PocSchemaUpgrade(DataSource dataSource, EntityManagerFactory schemaUpToDate) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        try (Connection connection = dataSource.getConnection()) {
            int demoted = demoteDuplicatePrimaries(connection);
            if (demoted > 0) {
                log.warn("Demoted {} duplicate primary POC seat(s); the oldest of each kept it", demoted);
            }
            addPrimaryIndex(connection);
        } catch (SQLException e) {
            log.warn("Could not finish the POC primary-seat upgrade: {}", e.getMessage());
        }
    }

    /**
     * Leaves at most one primary seat per customer and kind: the oldest, which is the one
     * {@code remove()} would have promoted anyway. Returns how many it demoted, which is zero on
     * a database that never had the race.
     */
    static int demoteDuplicatePrimaries(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            int demoted = statement.executeUpdate("""
                    update customer_pocs set is_primary = false
                     where is_primary
                       and id > (select min(other.id) from customer_pocs other
                                  where other.customer_id = customer_pocs.customer_id
                                    and other.poc_type = customer_pocs.poc_type
                                    and other.is_primary)
                    """);
            if (!connection.getAutoCommit()) connection.commit();
            return demoted;
        } catch (SQLException e) {
            log.warn("Could not demote duplicate primary POC seats: {}", e.getMessage());
            return 0;
        }
    }

    /** The partial unique index, on the one database that has them. */
    private static void addPrimaryIndex(Connection connection) throws SQLException {
        if (!connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT)
                .contains("postgresql")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("create unique index if not exists " + INDEX
                    + " on customer_pocs (customer_id, poc_type) where is_primary");
            if (!connection.getAutoCommit()) connection.commit();
        } catch (SQLException e) {
            log.warn("Could not create {}: {}", INDEX, e.getMessage());
        }
    }
}
