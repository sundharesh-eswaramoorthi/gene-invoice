package com.geneinvoice.task;

import com.geneinvoice.common.SchemaSupport;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * The two enum columns on the two new tables, kept widenable from day one.
 *
 * <p>ddl-auto:update never modifies a check constraint it has already created, so a TaskStatus or
 * TaskEntityType constant added on a later deploy would be rejected at INSERT time by the
 * constraint generated when the column was created — every task save failing at runtime, on a
 * database that started clean. The blueprint's rule is that a new table registers its
 * @Enumerated(STRING) columns here in the same commit that creates them, not in the commit that
 * adds the constant (A6).
 *
 * <p>Catches SQLException, logs at WARN and NEVER throws — the house contract every *SchemaUpgrade
 * runs under. On a first boot it finds nothing stale and does nothing.
 */
@Component
@Slf4j
class TaskSchemaUpgrade implements InitializingBean {

    private final DataSource dataSource;

    // The unused EntityManagerFactory is what orders this bean after Hibernate's schema export,
    // the InvoiceSchemaUpgrade / RegionSchemaUpgrade idiom (A6, B1).
    TaskSchemaUpgrade(DataSource dataSource, EntityManagerFactory schemaUpToDate) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        try (Connection connection = dataSource.getConnection()) {
            SchemaSupport.widen(connection, "tasks", "status", TaskStatus.class);
            SchemaSupport.widen(connection, "tasks", "entity_type", TaskEntityType.class);
        } catch (SQLException e) {
            log.warn("Could not check the task check constraints: {}", e.getMessage());
        }
    }
}
