package com.geneinvoice.region;

import com.geneinvoice.GeneInvoiceApplication;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.MovableClock;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.common.schema.SchemaSupport;
import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A database from before regions is brought up to them in place: records land in their customer's region,
 * {@code region_id} becomes NOT NULL, and every existing staff account keeps what it could do (§3.11).
 */
class RegionSchemaUpgradeTest extends IntegrationTestBase {

    @Autowired RegionSchemaUpgrade upgrade;
    @Autowired AuditService auditService;
    @Autowired DataSource dataSource;
    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired DisputeService disputeService;

    User admin;
    User sales;
    User collections;
    Product widget;
    Region north;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        widget = product("Widget", "100.00");
        north = region("NORTH");
        grant(admin, north, AccessLevel.APPROVE, RecordScope.ALL);
        // A POC is assignable only where they hold a grant (§5.6.7).
        grant(sales, north, AccessLevel.READ, RecordScope.BOOK);
        grant(collections, north, AccessLevel.READ, RecordScope.BOOK);
        actAs(admin);
    }

    record Family(Customer customer, Invoice invoice, Payment payment, Long promiseId, Dispute dispute) {}

    private Family family(Customer customer, String loginName) {
        actAs(admin);
        Invoice inv = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(customer.getId(), null, null,
                sales.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
        Payment payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(customer.getId(),
                new BigDecimal("40.00"), "Cash", null, List.of(inv.getId()), collections.getId(), null));
        Long promiseId = promiseService.create(new PromiseDtos.CreatePromiseRequest(customer.getId(),
                new BigDecimal("60.00"), Today.date().plusDays(3), collections.getId(), null, List.of())).id();
        actAs(customerUser(loginName, customer.getId()));
        Dispute dispute = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.INVOICE, inv.getId(), "Wrong quantity", null));
        actAs(admin);
        return new Family(customer, inv, payment, promiseId, dispute);
    }

    /** What a database from before regions looks like: no region anywhere, and no grants. */
    private void asBeforeRegions(Connection c, String... tables) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String table : tables) {
                st.execute("alter table " + table + " alter column region_id set null");
                st.execute("update " + table + " set region_id = null");
            }
            st.execute("delete from user_region_grants");
        }
    }

    private void pending(User u, boolean pending) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("update users set region_backfill_pending = " + pending + " where id = " + u.getId());
        }
    }

    private Long regionIdIn(String table, Long id) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rows = st.executeQuery("select region_id from " + table + " where id = " + id)) {
            rows.next();
            long value = rows.getLong(1);
            return rows.wasNull() ? null : value;
        }
    }

    private List<AuditLog> backfillEntries() {
        return auditService.historyFor(RegionService.ENTITY, RegionSchemaUpgrade.BACKFILL_ENTITY_ID).stream()
                .filter(e -> e.getAction().equals(RegionSchemaUpgrade.BACKFILLED)).toList();
    }

    @Test
    void recordsLandInTheirCustomersRegionAndTheColumnBecomesNotNull() throws Exception {
        Region home = defaultRegion();
        Family legacy = family(customer("Legacy Ltd"), "legacy.login");
        Family northern = family(customer("North Co", north), "north.login");
        Dispute orphan = disputeRepository.save(Dispute.builder()
                .customerId(999_999L).openedByUserId(admin.getId()).targetType(DisputeTargetType.INVOICE)
                .targetId(legacy.invoice().getId()).reason("Customer long gone").region(north).build());
        int entriesBefore = backfillEntries().size();

        try (Connection c = dataSource.getConnection()) {
            try {
                asBeforeRegions(c, "customers", "invoices", "payments", "payment_promises", "disputes");
                // The northern customer keeps its region, as a customer moved after the upgrade would.
                try (Statement st = c.createStatement()) {
                    st.execute("update customers set region_id = " + north.getId()
                            + " where id = " + northern.customer().getId());
                }
                for (String table : RegionSchemaUpgrade.REGIONAL_TABLES) {
                    assertThat(SchemaSupport.isNullable(c, table, "region_id")).isTrue();
                }

                upgrade.afterPropertiesSet();
            } finally {
                upgrade.afterPropertiesSet();
            }
            for (String table : RegionSchemaUpgrade.REGIONAL_TABLES) {
                assertThat(SchemaSupport.isNullable(c, table, "region_id")).as(table).isFalse();
            }
        }

        assertThat(regionIdIn("customers", legacy.customer().getId())).isEqualTo(home.getId());
        assertThat(regionIdIn("invoices", legacy.invoice().getId())).isEqualTo(home.getId());
        assertThat(regionIdIn("payments", legacy.payment().getId())).isEqualTo(home.getId());
        assertThat(regionIdIn("payment_promises", legacy.promiseId())).isEqualTo(home.getId());
        assertThat(regionIdIn("disputes", legacy.dispute().getId())).isEqualTo(home.getId());
        assertThat(regionIdIn("customers", northern.customer().getId())).isEqualTo(north.getId());
        assertThat(regionIdIn("invoices", northern.invoice().getId()))
                .as("a child takes its customer's region, not the default").isEqualTo(north.getId());
        assertThat(regionIdIn("payments", northern.payment().getId())).isEqualTo(north.getId());
        assertThat(regionIdIn("payment_promises", northern.promiseId())).isEqualTo(north.getId());
        assertThat(regionIdIn("disputes", northern.dispute().getId())).isEqualTo(north.getId());
        assertThat(regionIdIn("disputes", orphan.getId()))
                .as("a dispute whose customer is gone goes to the default region").isEqualTo(home.getId());

        assertThat(backfillEntries()).hasSize(entriesBefore + 1);
        AuditLog entry = backfillEntries().get(0);
        // The upgrade writes with JDBC before history exists on a real database; here the rows had versions, and
        // the orphan really moved, so its history is caught up as the reconciler would.
        historyTestSupport.touch(Dispute.class, List.of(orphan.getId()));
        assertThat(entry.getOrigin()).isEqualTo("SCHEMA");
        assertThat(entry.getAfterJson()).contains("\"customers\":1", "\"invoices\":2", "\"disputes\":3");
        assertThat(entry.getReason()).startsWith("Put 1 customer(s), 2 invoice(s), 2 payment(s)");
    }

    @Test
    void everyStaffAccountFromBeforeRegionsKeepsWhatItCouldDoAndOnlyOnce() throws Exception {
        Customer acme = customer("Acme Ltd");
        User acmeLogin = customerUser("acme.login", acme.getId());
        User later = user("lou.later", DataSeeder.ROLE_SALES_POC);
        pending(admin, true);
        pending(sales, true);
        pending(acmeLogin, true);
        pending(later, false);
        int entriesBefore = backfillEntries().size();

        try (Connection c = dataSource.getConnection()) {
            try {
                asBeforeRegions(c);
                upgrade.afterPropertiesSet();
                upgrade.afterPropertiesSet();
            } finally {
                upgrade.afterPropertiesSet();
            }
        }

        for (User u : List.of(admin, sales)) {
            assertThat(grantRepository.findWithRegionByUserId(u.getId())).as(u.getUsername()).singleElement()
                    .satisfies(g -> {
                        assertThat(g.getRegion().getId()).isEqualTo(defaultRegion().getId());
                        assertThat(g.getAccessLevel()).isEqualTo(AccessLevel.APPROVE);
                        assertThat(g.getRecordScope()).isEqualTo(RecordScope.ALL);
                    });
            assertThat(userRepository.findById(u.getId()).orElseThrow().isRegionBackfillPending()).isFalse();
        }
        assertThat(grantRepository.findWithRegionByUserId(acmeLogin.getId()))
                .as("a customer login takes its region from its customer").isEmpty();
        assertThat(grantRepository.findWithRegionByUserId(later.getId()))
                .as("an account left without grants on purpose is never granted again").isEmpty();
        assertThat(backfillEntries()).as("only the run that changed something files a row")
                .hasSize(entriesBefore + 1);
        assertThat(backfillEntries().get(0).getAfterJson()).contains("\"grants\":2");
    }

    @Test
    void aSecondRunFindsNothingToDoAndFilesNothing() throws Exception {
        family(customer("Acme Ltd"), "acme.login");
        int entriesBefore = backfillEntries().size();
        long grantsBefore = grantRepository.count();

        upgrade.afterPropertiesSet();

        assertThat(backfillEntries()).hasSize(entriesBefore);
        assertThat(grantRepository.count()).isEqualTo(grantsBefore);
        assertThat(regionRepository.findAll().stream().filter(Region::isDefaultRegion)).hasSize(1);
    }

    /**
     * A database that predates regions, brought up by the schema tool and this bean at startup: the new NOT
     * NULL flag is filled by its default, the default region is made, rows are placed, and the administrator
     * who existed before keeps full access.
     */
    @Test
    void aDatabaseFromBeforeRegionsStartsAndItsAdministratorKeepsAccess() throws Exception {
        String url = "jdbc:h2:mem:drift-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try {
            try (ConfigurableApplicationContext first = boot(url)) {
                assertThat(first.isRunning()).isTrue();
            }
            try (Connection c = DriverManager.getConnection(url, "sa", "");
                 Statement st = c.createStatement()) {
                for (String table : RegionSchemaUpgrade.REGIONAL_TABLES) {
                    for (String constraint : foreignKeysOnRegion(c, table)) {
                        st.execute("alter table " + table + " drop constraint " + constraint);
                    }
                    st.execute("alter table " + table + " drop column region_id");
                }
                st.execute("drop table user_region_grants");
                // Tasks came after regions: a database from before regions has none.
                st.execute("drop table tasks");
                // So did maker-checker, whose tables point at a region too.
                st.execute("drop table pending_changes");
                st.execute("drop table approval_thresholds");
                st.execute("drop table regions");
                st.execute("alter table users drop column region_backfill_pending");
                st.execute("insert into customers (name, credit_balance, version, created_at)"
                        + " values ('Legacy Ltd', 0, 0, current_timestamp)");
            }

            try (ConfigurableApplicationContext second = boot(url)) {
                assertThat(second.isRunning()).isTrue();
            }

            try (Connection c = DriverManager.getConnection(url, "sa", "");
                 Statement st = c.createStatement()) {
                assertThat(single(st, "select count(*) from regions where is_default = true")).isEqualTo(1);
                long defaultId = single(st, "select id from regions where is_default = true");
                assertThat(single(st, "select count(*) from customers where region_id = " + defaultId))
                        .isEqualTo(1);
                assertThat(SchemaSupport.isNullable(c, "customers", "region_id")).isFalse();
                assertThat(single(st, """
                        select count(*) from user_region_grants g join users u on u.id = g.user_id
                         where u.username = 'admin' and g.region_id = %d
                           and g.access_level = 'APPROVE' and g.record_scope = 'ALL'
                        """.formatted(defaultId))).isEqualTo(1);
                assertThat(single(st, "select count(*) from users where region_backfill_pending = true"))
                        .isZero();
            }
        } finally {
            // The booted contexts pointed the static clock bridge at their own clocks (D5).
            AppClock.set(MovableClock.SHARED);
        }
    }

    private static ConfigurableApplicationContext boot(String url) {
        return new SpringApplicationBuilder(GeneInvoiceApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("test")
                .run("--server.port=0",
                        "--spring.datasource.url=" + url,
                        "--spring.jpa.hibernate.ddl-auto=update");
    }

    private static List<String> foreignKeysOnRegion(Connection c, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rows = st.executeQuery("""
                select tc.constraint_name from information_schema.table_constraints tc
                  join information_schema.key_column_usage k
                    on k.constraint_schema = tc.constraint_schema and k.constraint_name = tc.constraint_name
                 where tc.table_schema = schema() and tc.table_name = '%s' and tc.constraint_type = 'FOREIGN KEY'
                   and k.column_name = 'REGION_ID'
                """.formatted(table.toUpperCase()))) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return names;
    }

    private static long single(Statement st, String sql) throws SQLException {
        try (ResultSet rows = st.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
