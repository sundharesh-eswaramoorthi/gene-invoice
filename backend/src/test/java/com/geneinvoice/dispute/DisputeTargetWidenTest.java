package com.geneinvoice.dispute;

import com.geneinvoice.GeneInvoiceApplication;
import com.geneinvoice.MovableClock;
import com.geneinvoice.common.time.AppClock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A database from before disputes on customers and internal disputes, brought up by the schema tool (§3.11, D12):
 * on H2 the target column is a native ENUM of the old constants, which refuses CUSTOMER until the upgrade widens it;
 * the new columns arrive, the live rows are external, and so are the versions written before {@code internal}
 * existed (CF-11).
 */
class DisputeTargetWidenTest {

    @Test
    void anOldDatabaseTakesADisputeOnACustomerAndItsOldVersionsAreNotInternal() throws Exception {
        String url = "jdbc:h2:mem:auto-drift-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try {
            try (ConfigurableApplicationContext first = boot(url)) {
                assertThat(first.isRunning()).isTrue();
            }
            try (Connection c = DriverManager.getConnection(url, "sa", "");
                 Statement st = c.createStatement()) {
                // As the tables were: no internal disputes, and a target of an invoice or a payment only.
                for (String table : new String[]{"disputes", "dispute_versions"}) {
                    st.execute("alter table " + table + " drop column internal");
                    st.execute("alter table " + table + " drop column rule_id");
                }
                st.execute("alter table disputes drop column origin");
                st.execute("alter table dispute_versions drop column dispute_origin");
                st.execute("alter table disputes alter column target_type set data type enum('INVOICE','PAYMENT')");
                long region = single(st, "select id from regions where is_default = true");
                st.execute("insert into disputes (customer_id, opened_by_user_id, target_type, target_id, reason, status,"
                        + " region_id, created_at, updated_at) values (1, 1, 'INVOICE', 5, 'Old', 'PENDING', "
                        + region + ", current_timestamp, current_timestamp)");
                long id = single(st, "select max(id) from disputes");
                st.execute("insert into dispute_versions (entity_id, valid_from, valid_to, tx_id, origin, customer_id,"
                        + " opened_by_user_id, target_type, target_id, reason, status, region_id, created_at, updated_at)"
                        + " values (" + id + ", current_timestamp, timestamp '9999-12-31 00:00:00', 'old-tx', 'USER',"
                        + " 1, 1, 'INVOICE', 5, 'Old', 'PENDING', " + region + ", current_timestamp, current_timestamp)");
                assertThatThrownBy(() -> st.execute(customerDispute(region)))
                        .as("the old ENUM refuses the new target").isInstanceOf(SQLException.class);
            }

            try (ConfigurableApplicationContext second = boot(url)) {
                assertThat(second.isRunning()).isTrue();
            }

            try (Connection c = DriverManager.getConnection(url, "sa", "");
                 Statement st = c.createStatement()) {
                long region = single(st, "select id from regions where is_default = true");
                st.execute(customerDispute(region));
                assertThat(single(st, "select count(*) from disputes where target_type = 'CUSTOMER'")).isEqualTo(1);
                assertThat(single(st, "select count(*) from dispute_versions where internal is null")).isZero();
                assertThat(single(st, "select count(*) from dispute_versions where tx_id = 'old-tx' and internal = false"))
                        .as("the version written before internal disputes").isEqualTo(1);
                assertThat(single(st, "select count(*) from disputes where internal = false")).isEqualTo(2);
                assertThat(single(st, "select count(*) from disputes where internal = true")).isZero();
            }
        } finally {
            // The booted contexts pointed the static clock bridge at their own clocks (D5).
            AppClock.set(MovableClock.SHARED);
        }
    }

    private static String customerDispute(long region) {
        return "insert into disputes (customer_id, opened_by_user_id, target_type, target_id, reason, status, region_id,"
                + " created_at, updated_at) values (1, 1, 'CUSTOMER', 1, 'About the account', 'PENDING', " + region
                + ", current_timestamp, current_timestamp)";
    }

    private static ConfigurableApplicationContext boot(String url) {
        return new SpringApplicationBuilder(GeneInvoiceApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("test")
                .run("--server.port=0",
                        "--spring.datasource.url=" + url,
                        "--spring.jpa.hibernate.ddl-auto=update");
    }

    private static long single(Statement st, String sql) throws SQLException {
        try (ResultSet rows = st.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
