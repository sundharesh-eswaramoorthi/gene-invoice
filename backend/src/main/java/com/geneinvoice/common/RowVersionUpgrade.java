package com.geneinvoice.common;

import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Fills in the row version of the tables that carry money, for the rows that were written before
 * the column existed (PPD-01).
 *
 * <p>{@code Invoice} and {@code Customer} are versioned so that a writer which did not take the
 * money path's row lock fails loudly instead of silently overwriting what another transaction
 * paid. {@code ddl-auto: update} adds that column to an existing table with a null in every row,
 * and Hibernate cannot increment a null version: without this, the first save of any invoice or
 * customer raised before the upgrade would fail. Setting them to zero costs one statement per
 * table and makes the upgrade invisible.
 *
 * <p>It runs once the schema is up to date (which is what the entity manager factory in the
 * constructor is for) and before the app takes requests. On a database that has been through it
 * once it changes nothing, so it is safe to re-run and safe on a populated production database.
 */
@Component
@Slf4j
class RowVersionUpgrade implements InitializingBean {

    /** The versioned tables, in the order their locks are taken. */
    private static final List<String> TABLES = List.of("customers", "invoices");

    private final DataSource dataSource;

    RowVersionUpgrade(DataSource dataSource, EntityManagerFactory schemaUpToDate) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        try (Connection connection = dataSource.getConnection()) {
            for (String table : TABLES) {
                int filled = zeroNullVersions(connection, table);
                if (filled > 0) log.info("Gave {} rows of {} a row version", filled, table);
            }
        } catch (SQLException e) {
            // A deployment whose rows already have versions loses nothing by this failing, and one
            // that does not will say so on the first write it refuses rather than at startup.
            log.warn("Could not finish the row-version upgrade: {}", e.getMessage());
        }
    }

    static int zeroNullVersions(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            int filled = statement.executeUpdate(
                    "update " + table + " set version = 0 where version is null");
            if (!connection.getAutoCommit()) connection.commit();
            return filled;
        } catch (SQLException e) {
            log.warn("Could not give {} its row versions: {}", table, e.getMessage());
            return 0;
        }
    }
}
