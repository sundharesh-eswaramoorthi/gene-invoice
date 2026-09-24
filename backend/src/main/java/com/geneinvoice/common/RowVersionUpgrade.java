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

@Component
@Slf4j
class RowVersionUpgrade implements InitializingBean {

    // payments and payment_promises joined the list the day they gained @Version: ddl-auto adds
    // the column to the rows already there as NULL, and Hibernate reads a null version as "this
    // row was never saved", so the first edit of an old payment or promise would fail its
    // optimistic lock. Zeroing them here, before any traffic, is what stops that (B2).
    private static final List<String> TABLES =
            List.of("customers", "invoices", "payments", "payment_promises");

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
