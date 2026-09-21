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
