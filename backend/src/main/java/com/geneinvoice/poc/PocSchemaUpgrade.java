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
