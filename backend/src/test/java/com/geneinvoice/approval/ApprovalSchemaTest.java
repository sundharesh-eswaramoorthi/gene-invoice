package com.geneinvoice.approval;

import com.geneinvoice.GeneInvoiceApplication;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.MovableClock;
import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The maker-checker tables (§3.8): their enum columns are plain text, so a constant can be appended without
 * touching the schema (D12); one change at most waits on a record, while creates wait in any number; and a
 * database from before them, with its audit history, starts and gains them.
 */
class ApprovalSchemaTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void theEnumColumnsArePlainTextWithNoCheckOrEnumType() {
        Map<String, List<String>> columns = Map.of(
                "PENDING_CHANGES", List.of("OPERATION", "STATUS", "ORIGIN", "ENTITY_TYPE", "AFFECTED_TYPE"),
                "APPROVAL_THRESHOLDS", List.of("OPERATION"));
        columns.forEach((table, names) -> {
            for (String column : names) {
                assertThat(jdbc.queryForObject("""
                        select data_type from information_schema.columns
                         where table_schema = schema() and table_name = ? and column_name = ?""",
                        String.class, table, column)).as(table + "." + column).isEqualTo("CHARACTER VARYING");
            }
            assertThat(jdbc.queryForObject("""
                    select count(*) from information_schema.table_constraints
                     where table_schema = schema() and table_name = ? and constraint_type = 'CHECK'""",
                    Long.class, table)).as(table + " has no CHECK").isZero();
        });
        assertThat(jdbc.queryForObject("""
                select character_maximum_length from information_schema.columns
                 where table_schema = schema() and table_name = 'PENDING_CHANGES' and column_name = 'OPERATION'""",
                Long.class)).isGreaterThanOrEqualTo(ApprovalOperation.DISPUTE_APPROVE.name().length());
    }

    @Test
    void oneChangeWaitsOnARecordWhileCreatesAndDecidedChangesNeedNoKey() {
        Region region = defaultRegion();
        User maker = user("mia.maker", "CASHIER");
        pendingChangeRepository.save(change(region, maker, ApprovalOperation.INVOICE_CREATE, null));
        pendingChangeRepository.save(change(region, maker, ApprovalOperation.PAYMENT_RECORD, null));
        PendingChange decided = change(region, maker, ApprovalOperation.INVOICE_CANCEL, 7L);
        decided.withdraw(maker.getId(), AppClock.now());
        pendingChangeRepository.save(decided);
        pendingChangeRepository.save(change(region, maker, ApprovalOperation.INVOICE_CANCEL, 7L));
        assertThat(pendingChangeRepository.count()).isEqualTo(4);

        assertThatThrownBy(() -> pendingChangeRepository.saveAndFlush(
                change(region, maker, ApprovalOperation.INVOICE_TERMS, 7L)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(pendingChangeRepository.count()).isEqualTo(4);
        assertThat(pendingChangeRepository.findByOutstandingKey("INVOICE:7").orElseThrow().getOperation())
                .isEqualTo(ApprovalOperation.INVOICE_CANCEL);
    }

    /**
     * A database from before maker-checker, with audit rows, brought up by the schema tool: it starts, gains
     * both tables, and its history is still there.
     */
    @Test
    void aDatabaseFromBeforeMakerCheckerStartsAndKeepsItsHistory() throws Exception {
        String url = "jdbc:h2:mem:drift-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try {
            try (ConfigurableApplicationContext first = boot(url)) {
                assertThat(first.isRunning()).isTrue();
            }
            try (Connection c = DriverManager.getConnection(url, "sa", "");
                 Statement st = c.createStatement()) {
                st.execute("drop table pending_changes");
                st.execute("drop table approval_thresholds");
                st.execute("insert into audit_logs (entity_type, entity_id, action, created_at)"
                        + " values ('INVOICE', 1, 'INVOICE_CANCELLED', current_timestamp)");
            }

            try (ConfigurableApplicationContext second = boot(url)) {
                assertThat(second.isRunning()).isTrue();
            }

            try (Connection c = DriverManager.getConnection(url, "sa", "");
                 Statement st = c.createStatement()) {
                assertThat(single(st, "select count(*) from audit_logs where action = 'INVOICE_CANCELLED'"))
                        .isEqualTo(1);
                assertThat(single(st, "select count(*) from pending_changes")).isZero();
                assertThat(single(st, "select count(*) from approval_thresholds")).isZero();
                assertThat(single(st, """
                        select count(*) from information_schema.indexes
                         where table_schema = schema() and table_name = 'AUDIT_LOGS'
                           and index_name = 'IDX_AUDIT_PENDING'""")).isEqualTo(1);
            }
        } finally {
            // The booted contexts pointed the static clock bridge at their own clocks (D5).
            AppClock.set(MovableClock.SHARED);
        }
    }

    private static PendingChange change(Region region, User maker, ApprovalOperation op, Long entityId) {
        Gated g = new Gated(op, entityId, "INV-1", op.entityType(), entityId, null, 1L, "Acme Ltd", region.getId(),
                null, new BigDecimal("10.00"), Map.of(), null, null, op.label(), false);
        return PendingChange.pending(g, region, BigDecimal.ONE, maker.getId(), ChangeOrigin.USER, null, null, "{}",
                null, null, AppClock.now());
    }

    private static ConfigurableApplicationContext boot(String url) {
        return new SpringApplicationBuilder(GeneInvoiceApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("test")
                .run("--server.port=0",
                        "--spring.datasource.url=" + url,
                        "--spring.jpa.hibernate.ddl-auto=update");
    }

    private static long single(Statement st, String sql) throws Exception {
        try (ResultSet rows = st.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
