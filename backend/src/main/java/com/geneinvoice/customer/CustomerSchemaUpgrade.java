package com.geneinvoice.customer;

import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@Slf4j
class CustomerSchemaUpgrade implements InitializingBean {

    static final String INDEX = "uk_customer_email";

    private final DataSource dataSource;

    CustomerSchemaUpgrade(DataSource dataSource, EntityManagerFactory schemaUpToDate) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        try (Connection connection = dataSource.getConnection()) {
            List<String> shared = duplicateEmails(connection);
            if (!shared.isEmpty()) {
                log.warn("{} email address(es) are on more than one customer and cannot be made"
                        + " unique until one of each is changed: {}", shared.size(), shared);
                return;
            }
            addEmailIndex(connection);
        } catch (SQLException e) {
            log.warn("Could not finish the customer email upgrade: {}", e.getMessage());
        }
    }

    static List<String> duplicateEmails(Connection connection) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     select lower(email) from customers
                      where email is not null and email <> ''
                      group by lower(email) having count(*) > 1
                      order by lower(email)
                     """)) {
            while (rows.next()) out.add(rows.getString(1));
        }
        return out;
    }

    private static void addEmailIndex(Connection connection) throws SQLException {
        if (!connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT)
                .contains("postgresql")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("create unique index if not exists " + INDEX
                    + " on customers (lower(email)) where email is not null");
            if (!connection.getAutoCommit()) connection.commit();
        } catch (SQLException e) {
            log.warn("Could not create {}: {}", INDEX, e.getMessage());
        }
    }
}
