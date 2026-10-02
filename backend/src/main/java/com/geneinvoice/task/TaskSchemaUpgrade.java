package com.geneinvoice.task;

import com.geneinvoice.common.schema.SchemaSupport;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** Widens the task enum columns when a constant is appended (§3.9): nothing else to upgrade on a new table. */
@Component
@Slf4j
class TaskSchemaUpgrade implements InitializingBean {

    private final DataSource dataSource;

    TaskSchemaUpgrade(DataSource dataSource, EntityManagerFactory schemaUpToDate) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        widen("status", TaskStatus.class);
        widen("entity_type", TaskEntityType.class);
    }

    private void widen(String column, Class<? extends Enum<?>> type) {
        try (Connection connection = dataSource.getConnection()) {
            SchemaSupport.widen(connection, "tasks", column, type);
        } catch (SQLException e) {
            log.warn("Could not widen tasks.{}: {}", column, e.getMessage());
        }
    }
}
