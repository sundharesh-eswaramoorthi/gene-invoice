package com.geneinvoice.approval;

import com.geneinvoice.common.SchemaSupport;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Pre-emptive check-constraint maintenance for the two new approval tables.
 *
 * <p>Nothing here is stale today: both tables are born empty on every database, so ddl-auto
 * generates the constraints from today's enums and they are correct. The calls are here for the
 * deploy in 2027 that adds a fifteenth {@link PendingAction} or a sixth
 * {@link PendingChangeStatus} — {@code update} never modifies a check constraint it has already
 * created, so without this the new constant would be refused at INSERT time and surface as a
 * bland 409 that nobody can trace back to a schema decision made years earlier (B2).
 *
 * <p>There is deliberately NO Postgres-only partial index: the one-open-change-per-record rule
 * rides on the pending_key sentinel column under a plain {@code @UniqueConstraint}, which behaves
 * identically on H2 and is therefore exercised by the test suite rather than only in production.
 * And no threshold seeding: {@link ApprovalThresholds#forRegion} resolves lazily (B2).
 *
 * <p>It also creates idx_audit_pending on audit_logs.pending_change_id — a PLAIN index on both
 * engines, not a partial one, and it lives here rather than with the two tables above because the
 * column it indexes does not exist until the audit unit adds it (B2).
 *
 * <p>Raw JDBC, an unused EntityManagerFactory parameter to order this bean after Hibernate's
 * schema export (the InvoiceSchemaUpgrade:34 / RowVersionUpgrade:22 idiom), and NEVER throws:
 * upgrades run first and carry on, checks run last and refuse (B2, B1).
 */
@Component
@DependsOn("regionSchemaUpgrade")
@Slf4j
class ApprovalSchemaUpgrade implements InitializingBean {

    private final DataSource dataSource;

    ApprovalSchemaUpgrade(DataSource dataSource, EntityManagerFactory schemaUpToDate) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        try (Connection connection = dataSource.getConnection()) {
            SchemaSupport.widen(connection, "pending_changes", "status", PendingChangeStatus.class);
            SchemaSupport.widen(connection, "pending_changes", "action", PendingAction.class);
            SchemaSupport.widen(connection, "pending_changes", "target_type", PendingTargetType.class);
            // Belt and braces for audit_logs.pending_change_id, which is mapped nullable and so
            // is added by ddl-auto on any database: index creation is the least reliable part of
            // update, and a missing index here is a table scan of the largest table in the
            // product every time somebody opens a change. Added in THIS unit and not with the two
            // tables above because the column does not exist until the audit unit lands (B2).
            //
            // OPERATIONAL NOTE FOR A LARGE DEPLOYMENT: on Postgres a plain
            // 'create index if not exists' takes a lock that blocks writes to audit_logs for as
            // long as it runs. The column is almost entirely NULL so the index is small, but on a
            // very large table the deploy should run 'create index concurrently' by hand first
            // and let this find it already there (B2).
            SchemaSupport.indexIfMissing(connection, "idx_audit_pending", "audit_logs", "pending_change_id");
        } catch (SQLException e) {
            log.warn("Could not finish the approval schema upgrade: {}", e.getMessage());
        }
    }
}
