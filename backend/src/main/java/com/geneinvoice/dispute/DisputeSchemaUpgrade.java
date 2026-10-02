package com.geneinvoice.dispute;

import com.geneinvoice.common.schema.SchemaSupport;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Brings a database from before disputes on customers and internal disputes up to date (§3.11): the target column
 * accepts CUSTOMER (a CHECK on Postgres, an ENUM on H2; D12), and versions written before {@code internal} existed
 * say false, as the live rows' column default does (the versioned-column backfill contract). Each step is its own
 * try: a failure is logged and tried again on the next start.
 */
@Component
@Slf4j
class DisputeSchemaUpgrade implements InitializingBean {

    private final DataSource dataSource;

    DisputeSchemaUpgrade(DataSource dataSource, EntityManagerFactory schemaUpToDate) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        try (Connection connection = dataSource.getConnection()) {
            SchemaSupport.widen(connection, "disputes", "target_type", DisputeTargetType.class);
        } catch (SQLException e) {
            log.warn("Could not widen disputes.target_type: {}", e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            int filled = statement.executeUpdate("update dispute_versions set internal = false where internal is null");
            if (!connection.getAutoCommit()) connection.commit();
            if (filled > 0) log.info("Marked {} dispute version(s) from before internal disputes as not internal", filled);
        } catch (SQLException e) {
            log.warn("Could not fill dispute_versions.internal: {}", e.getMessage());
        }
    }
}
