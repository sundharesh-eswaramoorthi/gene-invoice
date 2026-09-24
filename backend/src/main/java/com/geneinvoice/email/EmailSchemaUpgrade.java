package com.geneinvoice.email;

import com.geneinvoice.common.SchemaSupport;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Component
@Slf4j
class EmailSchemaUpgrade implements InitializingBean {

    private final DataSource dataSource;

    EmailSchemaUpgrade(DataSource dataSource, EntityManagerFactory schemaUpToDate) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        try (Connection connection = dataSource.getConnection()) {
            SchemaSupport.widen(connection, "emails", "status", EmailStatus.class);
        } catch (SQLException e) {
            log.warn("Could not check the check constraint on emails.status: {}", e.getMessage());
        }
    }
}
