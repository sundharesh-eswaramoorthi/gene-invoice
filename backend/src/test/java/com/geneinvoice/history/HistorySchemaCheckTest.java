package com.geneinvoice.history;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.asof.AsOfDates;
import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.common.query.ColumnType;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerHistory;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeHistory;
import com.geneinvoice.dispute.DisputeRepository;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceHistory;
import com.geneinvoice.invoice.InvoiceItemHistory;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentAllocationHistory;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentHistory;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.CustomerPocHistory;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.promise.PromiseHistory;
import com.geneinvoice.promise.PromiseInvoiceHistory;
import com.geneinvoice.promise.PromisePaymentHistory;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.task.Task;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.task.TaskHistory;
import com.geneinvoice.task.TaskStatus;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three beans that make the temporal boundary a property of starting up, and the seed that
 * makes an answer from before the floor worth serving (B3).
 *
 * <p>WHAT IS PROVED WHERE. The seed tests are behavioural and drive the static
 * {@code seedChunk} / {@code seed} against a connection holding rows this test inserted itself,
 * the way {@code InvoiceSchemaUpgrade.backfill} is already written to be driven. The enum test has
 * two halves: an enumeration over the registry, which is what stops a twelfth enum column being
 * forgotten, and a genuinely narrowed check constraint that REFUSES an insert until the widener
 * runs — because "we call widen" is not the same claim as "widening works". The check tests drive
 * the boot gate's three package-private arms directly, which is how {@code RegionCoverageTest}
 * exercises {@code RegionCoverageCheck} without standing up a second Spring context.
 */
class HistorySchemaCheckTest extends IntegrationTestBase {

    @Autowired HistoryRegistry registry;
    @Autowired HistoryFloorService floorService;
    @Autowired AsOfSchemaCheck check;
    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired DisputeRepository disputeRepository;
    @Autowired DataSource dataSource;
    @Autowired EntityManagerFactory entityManagerFactory;

    @PersistenceContext EntityManager em;

    private User admin;
    private User collections;
    private Customer acme;
    private Product widget;

    /** The install instant a test seeds with; a fixed one, so "not install day" means something. */
    private static final Instant INSTALLED_AT = Instant.parse("2026-03-04T05:06:07.008009Z");

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        collections = user("cora.seed", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(admin);
    }

    // ---- the seed ---------------------------------------------------------------------------

    /**
     * The seed runs on every boot of every installation for ever, so running it twice has to be
     * indistinguishable from running it once — and the floor especially, because a floor that
     * moved forward on each restart would silently redate every pre-floor caveat in the product
     * and nobody would ever see a date before yesterday (B3).
     */
    @Test
    void theSeedUpgradeIsIdempotentAcrossTwoRunsAndDoesNotMoveTheFloor() throws Exception {
        Invoice invoice = invoiceService.create(request(2));
        clearMirrors();
        historyFloorRepository.deleteAll();

        try (Connection connection = dataSource.getConnection()) {
            int first = seedEverything(connection, INSTALLED_AT);
            assertThat(first).as("the customer, the invoice and its line at least")
                    .isGreaterThanOrEqualTo(3);
            assertThat(HistorySeedUpgrade.installFloor(connection, INSTALLED_AT)).isTrue();

            // Exactly the shape of the second boot of a production database.
            int second = seedEverything(connection, INSTALLED_AT.plusSeconds(86_400));
            assertThat(second).as("a record with a mirror row is never seeded again").isZero();
            assertThat(HistorySeedUpgrade.installFloor(connection, INSTALLED_AT.plusSeconds(86_400)))
                    .as("the floor is installed once, ever").isFalse();
        }

        assertThat(historyFloorRepository.findById(HistoryFloor.SINGLETON).orElseThrow()
                .getInstalledAt()).isEqualTo(INSTALLED_AT);
        assertThat(historyFloorRepository.count()).isEqualTo(1);
        // One row per record and not two: uk_<x>h_open would have refused the second, so this is
        // also the assertion that the second run did not merely fail quietly.
        assertThat(versions(InvoiceHistory.class, invoice.getId())).hasSize(1);
        assertThat(versions(CustomerHistory.class, acme.getId())).hasSize(1);
    }

    /**
     * THE DECISION THE WHOLE PRE-FLOOR CONTRACT RESTS ON. An install-dated seed would report zero
     * invoices for every month before the mirror existed and would place a customer created in
     * March inside a February read. Creation-dated, EXISTENCE is exact for all of history and only
     * the values are the values as first recorded — which is exactly what
     * {@code AsOfDates.preFloorNote} tells the reader (B3).
     */
    @Test
    void aSeedRowIsDatedFromTheRecordsOwnCreationAndNotFromInstallDay() throws Exception {
        Customer old = customer("Founded Ltd");
        Instant born = INSTALLED_AT.minus(200, ChronoUnit.DAYS);
        setCreatedAt("customers", old.getId(), born);
        clearMirrors();

        try (Connection connection = dataSource.getConnection()) {
            HistorySeedUpgrade.seed(connection, registry.forType(Customer.class), INSTALLED_AT);
        }

        CustomerHistory seeded = onlyOpen(CustomerHistory.class, old.getId());
        assertThat(seeded.getValidFrom()).isEqualTo(born);
        assertThat(seeded.getValidFrom()).isNotEqualTo(INSTALLED_AT);
        assertThat(seeded.getValidTo()).isEqualTo(HistoryRow.OPEN);
        assertThat(seeded.isDeleted()).isFalse();
        // A seed row is not a repair: it is the declared floor of what this installation knows,
        // and the pre-floor note is what says its VALUES are not what was true then (B3).
        assertThat(seeded.isDrifted()).isFalse();
        // The projection columns are today's values, which is the other half of the contract.
        assertThat(seeded.getName()).isEqualTo("Founded Ltd");

        // And the consequence, which is the only thing a reader ever sees: it existed a hundred
        // days ago and it did not exist three hundred days ago.
        assertThat(inForce(CustomerHistory.class, INSTALLED_AT.minus(100, ChronoUnit.DAYS)))
                .contains(old.getId());
        assertThat(inForce(CustomerHistory.class, INSTALLED_AT.minus(300, ChronoUnit.DAYS)))
                .doesNotContain(old.getId());
    }

    /**
     * All eleven projections, run for real against a record of every mirrored kind — including the
     * two join tables, which have no entity and no creation date of their own and take the
     * promise's. A seed that silently skipped a mirror would leave that table empty until the
     * first write of each record, and every pre-floor answer over it would be a lie of omission
     * (B3).
     */
    @Test
    void everyMirrorIsSeededFromItsOwnLiveTableAndTheLinksTakeTheirParentsDate() throws Exception {
        // A twelfth mirror added with no projection would be seeded by nobody and would sit empty
        // until that record's first write, which is invisible until somebody reads a pre-floor
        // month and finds it missing. Held to the registry so it cannot be forgotten (B3).
        assertThat(HistorySeedUpgrade.SOURCES.keySet()).containsExactlyInAnyOrderElementsOf(
                registry.all().stream().map(HistoryBinding::mirrorTable).toList());

        Invoice invoice = invoiceService.create(request(1));
        Payment paid = paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("40.00"), "NEFT", null,
                List.of(invoice.getId()), collections.getId(), null));
        PromiseDtos.PromiseDto promise = promiseService.create(new PromiseDtos.CreatePromiseRequest(
                acme.getId(), new BigDecimal("60.00"), LocalDate.now().plusDays(7),
                collections.getId(), null, List.of(invoice.getId())));
        execute("insert into payment_promise_payments (promise_id, payment_id) values ("
                + promise.id() + ", " + paid.getId() + ")");
        CustomerPoc seat = customerPocRepository.save(CustomerPoc.builder()
                .customer(acme).user(collections).pocType(PocType.COLLECTION).primary(true)
                .createdByUserId(admin.getId()).build());
        Task task = taskRepository.save(Task.builder()
                .entityType(TaskEntityType.INVOICE).entityId(invoice.getId())
                .entityLabel(invoice.getInvoiceNumber()).customerId(acme.getId())
                .title("Chase Acme").status(TaskStatus.OPEN).createdByUserId(admin.getId()).build());
        Dispute dispute = disputeRepository.save(Dispute.builder()
                .customerId(acme.getId()).openedByUserId(admin.getId())
                .targetType(DisputeTargetType.INVOICE).targetId(invoice.getId())
                .reason("wrong price").status(DisputeStatus.PENDING).build());

        clearMirrors();
        int seeded;
        try (Connection connection = dataSource.getConnection()) {
            seeded = seedEverything(connection, INSTALLED_AT);
        }
        assertThat(seeded).isGreaterThanOrEqualTo(11);

        assertThat(onlyOpen(CustomerHistory.class, acme.getId()).getName()).isEqualTo("Acme Ltd");
        InvoiceHistory invoiceRow = onlyOpen(InvoiceHistory.class, invoice.getId());
        assertThat(invoiceRow.getTotal()).isEqualByComparingTo("100.00");
        // The denormalised as-of labels come off the JOINED tables, which is the whole reason the
        // projection is not a column-for-column copy (B3).
        assertThat(invoiceRow.getCustomerName()).isEqualTo("Acme Ltd");
        assertThat(invoiceRow.getSalesPocName()).isEqualTo(admin.getFullName());
        assertThat(open(InvoiceItemHistory.class)).isNotEmpty();
        assertThat(onlyOpen(PaymentHistory.class, paid.getId()).getCustomerName()).isEqualTo("Acme Ltd");
        PaymentAllocationHistory allocation = open(PaymentAllocationHistory.class).get(0);
        assertThat(allocation.getPaymentId()).isEqualTo(paid.getId());
        assertThat(allocation.getCustomerId()).isEqualTo(acme.getId());
        assertThat(allocation.getPaidAt()).isNotNull();
        assertThat(onlyOpen(PromiseHistory.class, promise.id()).getCollectionPocName())
                .isEqualTo(collections.getFullName());
        assertThat(onlyOpen(CustomerPocHistory.class, seat.getId()).getUserId())
                .isEqualTo(collections.getId());
        assertThat(onlyOpen(TaskHistory.class, task.getId()).getTitle()).isEqualTo("Chase Acme");
        assertThat(onlyOpen(DisputeHistory.class, dispute.getId()).getReason()).isEqualTo("wrong price");

        // The two join tables: their business id is the PROMISE's, and their valid_from is the
        // promise's creation instant, because a link came into existence with the promise.
        List<PromiseInvoiceHistory> invoiceLinks = open(PromiseInvoiceHistory.class);
        assertThat(invoiceLinks).hasSize(1);
        assertThat(invoiceLinks.get(0).getId()).isEqualTo(promise.id());
        assertThat(invoiceLinks.get(0).getInvoiceId()).isEqualTo(invoice.getId());
        List<PromisePaymentHistory> paymentLinks = open(PromisePaymentHistory.class);
        assertThat(paymentLinks).hasSize(1);
        assertThat(paymentLinks.get(0).getPaymentId()).isEqualTo(paid.getId());
        assertThat(paymentLinks.get(0).getValidFrom())
                .isEqualTo(onlyOpen(PromiseHistory.class, promise.id()).getValidFrom());

        // Clean up the two tables the harness does not: a dispute or a seat left behind is a
        // record a later test's reconciler sweep would find with no mirror row (B3).
        disputeRepository.delete(dispute);
    }

    // ---- the enum widener --------------------------------------------------------------------

    /**
     * TWO HALVES, AND BOTH ARE NEEDED. The enumeration is what makes a twelfth enum column
     * impossible to forget: it is read off the mirror classes the registry names, so nothing has
     * to be added to a list. The narrowed constraint is what proves the widening WORKS — a
     * 2026 database whose constraint was generated before a 2027 constant existed refuses the
     * insert until this bean has run, and that insert happens inside the user's own transaction
     * (B3).
     */
    @Test
    void everyEnumColumnOnEveryMirrorIsWidenedFromTheRegistry() throws Exception {
        List<HistoryEnumUpgrade.EnumColumn> columns = HistoryEnumUpgrade.enumColumns(registry);

        // Every one of them is a real column of a real mirror table, and the set is exactly the
        // eleven: neither direction can be got right by accident, and a twelfth enum column
        // mapped on a mirror next year appears here with no list to edit (B3).
        assertThat(columns).isNotEmpty();
        try (Connection connection = dataSource.getConnection()) {
            for (HistoryEnumUpgrade.EnumColumn column : columns) {
                assertThat(AsOfSchemaCheck.columnsOf(connection, column.table()))
                        .describedAs("%s.%s", column.table(), column.column())
                        .contains(column.column());
            }
        }
        assertThat(columns).extracting(c -> c.table() + "." + c.column())
                .containsExactlyInAnyOrderElementsOf(enumColumnsByHand());
        assertThat(columns).extracting(column -> column.type().getSimpleName())
                .contains(InvoiceStatus.class.getSimpleName(), "PocType", "TaskStatus");

        // NOW THE BEHAVIOUR, AND IT HAS TO BE STAGED ON A DATABASE OF ITS OWN. On H2 Hibernate
        // maps an @Enumerated(STRING) column as a native ENUM type and generates no check
        // constraint at all, so the real mirrors in this context have nothing to widen — the
        // shape that matters is Postgres's varchar under a generated check, which is the shape
        // staged below, and it is the same observation RegionSchemaUpgradeTest records for
        // user_region_grants.right_level (B3).
        DriverManagerDataSource bare = new DriverManagerDataSource(
                "jdbc:h2:mem:b3-history-enum;DB_CLOSE_DELAY=-1", "sa", "");
        bare.setDriverClassName("org.h2.Driver");
        try (Connection connection = bare.getConnection(); Statement statement = connection.createStatement()) {
            // invoice_history as a database created BEFORE CANCELLED existed would hold it.
            statement.execute("create table invoice_history (history_id bigint primary key,"
                    + " status varchar(30), constraint invoice_history_status_old"
                    + " check (status in ('UNPAID', 'PARTIALLY_PAID', 'FULLY_PAID')))");
            assertThatThrownBy(() -> statement.execute(
                    "insert into invoice_history (history_id, status) values (1, 'CANCELLED')"))
                    .isInstanceOf(SQLException.class);

            // The bean, over the real registry, so what is exercised is the enumeration and not a
            // hand-written call: the other ten tables are absent here and are a silent no-op.
            new HistoryEnumUpgrade(bare, registry, null).afterPropertiesSet();

            assertThatCode(() -> statement.execute(
                    "insert into invoice_history (history_id, status) values (2, 'CANCELLED')"))
                    .doesNotThrowAnyException();
            // Still a constraint, not a column that simply lost its check: widening is not
            // dropping, and a mirror that accepted anything would let a typo into the past.
            assertThatThrownBy(() -> statement.execute(
                    "insert into invoice_history (history_id, status) values (3, 'INVENTED_2027')"))
                    .isInstanceOf(SQLException.class);
            // And it runs on every boot, so running it twice has to change nothing.
            assertThatCode(() -> new HistoryEnumUpgrade(bare, registry, null).afterPropertiesSet())
                    .doesNotThrowAnyException();
            assertThatCode(() -> statement.execute(
                    "insert into invoice_history (history_id, status) values (4, 'CANCELLED')"))
                    .doesNotThrowAnyException();
            statement.execute("drop table invoice_history");
        }
    }

    // ---- the boot gate -----------------------------------------------------------------------

    @Test
    void theBootCheckPassesAgainstTheRunningApplicationAndIsWorthRunningTwice() {
        // The context started, so it passed once. Running it again proves it is a check and not a
        // migration: nothing it does depends on having run first (B3).
        assertThatCode(check::afterPropertiesSet).doesNotThrowAnyException();
    }

    /**
     * The failure this bean exists for, and it is silent by nature: a field added to a live entity
     * next quarter with no mirror column breaks no test at all — it just quietly stops being part
     * of the past, and the first person to notice is somebody reading a number off an as-of report
     * a year later (B3).
     */
    @Test
    void aLiveEntityFieldThatIsNeitherMirroredNorDeclaredNotMirroredStopsTheApplication() {
        Metamodel metamodel = entityManagerFactory.getMetamodel();
        HistoryBinding real = registry.forType(Customer.class);
        // The same binding with its ONE declared excuse withdrawn: customers.version is mapped,
        // persisted and deliberately not mirrored, so withdrawing the reason is exactly what
        // adding an unmirrored field looks like to this check.
        HistoryBinding forgetful = new HistoryBinding(real.entityClass(), real.mirrorClass(),
                real.liveTable(), real.mirrorTable(), real.businessIdColumn(), real.mirrorColumns(),
                real.projector(), real.driftSql(), Set.of());

        assertThatThrownBy(() -> AsOfSchemaCheck.everyAttributeIsMirroredOrDeclared(forgetful,
                columns("customers"), columns("customer_history"), metamodel, registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version")
                .hasMessageContaining("notMirrored");

        // And the real binding, which declares it, passes — so the test is about the excuse and
        // not about the column.
        assertThatCode(() -> AsOfSchemaCheck.everyAttributeIsMirroredOrDeclared(real,
                columns("customers"), columns("customer_history"), metamodel, registry))
                .doesNotThrowAnyException();
    }

    /**
     * The column half, which the metamodel half cannot do for itself: a column that exists on the
     * database but is mapped by nothing at all is what a half-finished migration looks like, and
     * it is still a column the past has stopped recording (B3).
     */
    @Test
    void aLiveSchemaColumnWithNoMirrorColumnStopsTheApplicationNamingTheColumn() {
        Metamodel metamodel = entityManagerFactory.getMetamodel();
        HistoryBinding invoices = registry.forType(Invoice.class);
        Set<String> live = new LinkedHashSet<>(columns("invoices"));
        live.add("settlement_currency");     // added by a migration next quarter, mirrored by nobody

        assertThatThrownBy(() -> AsOfSchemaCheck.everyAttributeIsMirroredOrDeclared(invoices, live,
                columns("invoice_history"), metamodel, registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invoices.settlement_currency")
                .hasMessageContaining("invoice_history");

        // The other direction: a binding that names a mirror column its table does not have.
        HistoryBinding inventive = new HistoryBinding(invoices.entityClass(), invoices.mirrorClass(),
                invoices.liveTable(), invoices.mirrorTable(), invoices.businessIdColumn(),
                List.of("settlement_currency"), invoices.projector(), invoices.driftSql(),
                invoices.notMirrored());
        assertThatThrownBy(() -> AsOfSchemaCheck.everyAttributeIsMirroredOrDeclared(inventive,
                columns("invoices"), columns("invoice_history"), metamodel, registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("settlement_currency");
    }

    /**
     * The two arms that are VACUOUS today, exercised against hand-built schemas so that B3-SCHEMAS
     * inherits a check rather than a promise. The pair finder recognises a twin by the one thing
     * that cannot be a matter of taste — its entity is a MIRROR class — so it needs no edit and no
     * naming convention when the twins land (B3).
     */
    @Test
    void anAsOfTableSchemaThatDisagreesWithItsLiveTwinStopsTheApplication() {
        TableSchema live = TableSchema.of("invoices", Invoice.class, "id",
                ColumnDef.of("total", "Total", ColumnType.MONEY).build(),
                ColumnDef.of("status", "Status", ColumnType.ENUM)
                        .enumValues(List.of("UNPAID", "FULLY_PAID")).build());
        TableSchema twin = TableSchema.of("invoicesAsOf", InvoiceHistory.class, "id",
                ColumnDef.of("total", "Total", ColumnType.MONEY).build(),
                ColumnDef.of("status", "Status", ColumnType.ENUM)
                        .enumValues(List.of("UNPAID", "FULLY_PAID")).current().build());

        // asOfMode is the ONE field allowed to differ, and saying "this label is today's" is the
        // whole point of having it.
        assertThatCode(() -> AsOfSchemaCheck.columnSetsAgree(live, twin)).doesNotThrowAnyException();
        assertThat(AsOfSchemaCheck.pairs(List.of(live, twin), registry))
                .extracting(p -> p.live().entity() + " -> " + p.asOf().entity())
                .containsExactly("invoices -> invoicesAsOf");

        TableSchema short_ = TableSchema.of("invoicesAsOf", InvoiceHistory.class, "id",
                ColumnDef.of("total", "Total", ColumnType.MONEY).build());
        assertThatThrownBy(() -> AsOfSchemaCheck.columnSetsAgree(live, short_))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("status");

        TableSchema disagrees = TableSchema.of("invoicesAsOf", InvoiceHistory.class, "id",
                ColumnDef.of("total", "Total", ColumnType.MONEY).notFilterable().build(),
                ColumnDef.of("status", "Status", ColumnType.ENUM)
                        .enumValues(List.of("UNPAID", "FULLY_PAID")).build());
        assertThatThrownBy(() -> AsOfSchemaCheck.columnSetsAgree(live, disagrees))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filterable");

        // A twin over a mirror whose live list is not there to compare with is a mistake too.
        assertThatThrownBy(() -> AsOfSchemaCheck.pairs(List.of(twin), registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invoice_history");
    }

    /**
     * A ColumnDef path is a lambda over attribute NAMES, so nothing about it is checked at compile
     * time and a rename on the mirror shows up as a 500 on the first as-of request. This resolves
     * every one against the mirror's root at boot instead (B3).
     */
    @Test
    void anAsOfColumnThatTheMirrorDoesNotHaveStopsTheApplication() {
        CriteriaBuilder cb = entityManagerFactory.getCriteriaBuilder();
        TableSchema twin = TableSchema.of("invoicesAsOf", InvoiceHistory.class, "id",
                ColumnDef.of("total", "Total", ColumnType.MONEY).build());
        assertThatCode(() -> AsOfSchemaCheck.mirrorHasAttribute(twin, twin.require("total"), cb))
                .doesNotThrowAnyException();
        // The POC association really is on the mirror, read-only, because InvoiceView declares it
        // and InvoiceService.tiles does cb.isNull(root.get("salesPoc")) (B3).
        TableSchema walks = TableSchema.of("invoicesAsOf", InvoiceHistory.class, "id",
                ColumnDef.of("salesPocName", "Sales POC", ColumnType.TEXT)
                        .path(ColumnDef.nested("salesPoc", "fullName")).build());
        assertThatCode(() -> AsOfSchemaCheck.mirrorHasAttribute(walks, walks.require("salesPocName"), cb))
                .doesNotThrowAnyException();

        TableSchema misspelled = TableSchema.of("invoicesAsOf", InvoiceHistory.class, "id",
                ColumnDef.of("customer", "Customer", ColumnType.TEXT)
                        .path(ColumnDef.referenceId("customer")).build());
        assertThatThrownBy(() ->
                AsOfSchemaCheck.mirrorHasAttribute(misspelled, misspelled.require("customer"), cb))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invoicesAsOf.customer")
                .hasMessageContaining("InvoiceHistory");
    }

    /**
     * THE SIX TWINS ARE ACTUALLY IN THE SET THIS BEAN CHECKS, which is not something the arms
     * above can prove for themselves (B3).
     *
     * <p>B3-UPGRADES left a note saying the check would need no edit when the twins landed,
     * because {@link AsOfSchemaCheck#pairs} recognises one by its entityType. That half is true.
     * The half that was not: the list it walks was the REGISTERED tables, and a twin is
     * deliberately not registered — it keeps its live entity() string, so registering it would
     * replace the live schema under that key. Without the one line that hands HistorySchemas'
     * twins to the check, it would go on reporting "0 as-of table schema pair(s)" with six of them
     * sitting in the class next door, and every arm here would stay vacuous for ever.
     */
    @Test
    void theBootGatePairsAllSixAsOfTwinsWithTheirLiveSchemas() {
        // AsOfSchemaCheck.schemas() and not a list assembled here: the set the BEAN walks is the
        // thing that can be wrong, and a test that built its own would have gone on passing while
        // the boot gate checked nothing at all (verified by ablation).
        List<AsOfSchemaCheck.Pair> pairs = AsOfSchemaCheck.pairs(AsOfSchemaCheck.schemas(), registry);
        assertThat(pairs).extracting(p -> p.asOf().entityType().getSimpleName())
                .containsExactlyInAnyOrder("InvoiceHistory", "CustomerHistory", "PaymentHistory",
                        "PromiseHistory", "DisputeHistory", "TaskHistory");
        for (AsOfSchemaCheck.Pair pair : pairs) {
            assertThat(pair.live().entity()).isEqualTo(pair.asOf().entity());
            assertThatCode(() -> AsOfSchemaCheck.columnSetsAgree(pair.live(), pair.asOf()))
                    .doesNotThrowAnyException();
        }
    }

    /**
     * The run order the blueprint fixes, asserted rather than hoped for. Upgrades run first and
     * never throw, checks run last and do — and a check that ran before the upgrade it validates
     * would refuse to start over a schema the next bean was about to finish (B3).
     */
    @Test
    void theFourNewBeansDeclareTheRunOrderTheBlueprintFixes() {
        // lobTextUpgrade is the fourth name and it is load-bearing rather than tidy: it rewrites
        // disputes.proposed_change_json from the large-object OID the old @Lob mapping left there
        // back into text, and the seed copies that column into dispute_history in raw SQL. Seeded
        // first, the mirror is born holding "60798" and is only put right by a later reconcile,
        // which marks the account inexact for as long as it takes (B2, B3 INTEGRATION).
        assertThat(dependsOnOf(HistorySeedUpgrade.class))
                .containsExactlyInAnyOrder("regionSchemaUpgrade", "approvalSchemaUpgrade",
                        "automationSchemaUpgrade", "lobTextUpgrade");
        assertThat(dependsOnOf(HistoryEnumUpgrade.class)).containsExactly("historySeedUpgrade");
        assertThat(dependsOnOf(HistoryFloorService.class)).containsExactly("historySeedUpgrade");
        assertThat(dependsOnOf(AsOfSchemaCheck.class))
                .containsExactlyInAnyOrder("historySeedUpgrade", "historyEnumUpgrade",
                        "regionCoverageCheck");
    }

    /**
     * The floor reaches the read side, and that is the whole of this bean's job: it arms the
     * pre-floor branch of the parser, which until now had never run with a real value (B3).
     */
    @Test
    void theFloorIsPublishedToTheReadSideAndArmsThePreFloorBranch() {
        LocalDate floor = floorService.floorOrNull();
        // Installed by the seed upgrade at boot, on this very context.
        assertThat(floor).isNotNull();
        assertThat(floor).isEqualTo(LocalDate.now(ZoneOffset.UTC));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        AsOfContext.State before = AsOfDates.parse(floor.minusDays(1).toString(), today, floor,
                AsOfDates.POLICY_SEEDED);
        assertThat(before.origin()).isEqualTo(AsOfContext.ORIGIN_SEEDED);
        assertThat(before.exact()).isFalse();
        assertThat(before.notes()).hasSize(1);
        assertThat(before.notes().get(0)).contains("before the history floor " + floor);

        // A date ON the floor is a reconstruction and says nothing.
        AsOfContext.State on = AsOfDates.parse(floor.toString(), today.plusDays(1), floor,
                AsOfDates.POLICY_SEEDED);
        assertThat(on.exact()).isTrue();
        assertThat(on.origin()).isEqualTo(AsOfContext.ORIGIN_RECONSTRUCTED);

        assertThatThrownBy(() -> AsOfDates.parse(floor.minusDays(1).toString(), today, floor,
                AsOfDates.POLICY_REJECT))
                .hasMessageContaining("is before the history floor " + floor);
    }

    // ---- fixtures ------------------------------------------------------------------------------

    /** The eleven, written out by hand so the enumeration is checked against something (B3). */
    private static List<String> enumColumnsByHand() {
        return List.of("customer_history.payment_term", "invoice_history.payment_term",
                "invoice_history.status", "payment_history.status",
                "payment_allocation_history.payment_status", "promise_history.status",
                "dispute_history.target_type", "dispute_history.status",
                "customer_poc_history.poc_type", "task_history.entity_type",
                "task_history.status");
    }

    private static List<String> dependsOnOf(Class<?> bean) {
        DependsOn annotation = bean.getAnnotation(DependsOn.class);
        assertThat(annotation).describedAs("%s declares no @DependsOn", bean.getSimpleName())
                .isNotNull();
        return List.of(annotation.value());
    }

    private int seedEverything(Connection connection, Instant installedAt) throws SQLException {
        int seeded = 0;
        for (HistoryBinding binding : registry.all()) {
            seeded += HistorySeedUpgrade.seed(connection, binding, installedAt);
        }
        return seeded;
    }

    private InvoiceDtos.CreateInvoiceRequest request(int quantity) {
        return new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), quantity, new BigDecimal("100.00"))));
    }

    private Set<String> columns(String table) {
        try (Connection connection = dataSource.getConnection()) {
            return AsOfSchemaCheck.columnsOf(connection, table);
        } catch (SQLException e) {
            throw new IllegalStateException(table, e);
        }
    }

    private void setCreatedAt(String table, Long id, Instant at) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "update " + table + " set created_at = ? where id = ?")) {
            HistoryJdbc.setInstant(statement, 1, at);
            statement.setLong(2, id);
            statement.executeUpdate();
        }
    }

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private void clearMirrors() {
        for (HistoryBinding binding : registry.all()) {
            em.clear();
            execute("delete from " + binding.mirrorTable());
        }
    }

    private <T extends HistoryRow> List<T> versions(Class<T> mirror, Long id) {
        em.clear();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<T> cq = cb.createQuery(mirror);
        Root<T> root = cq.from(mirror);
        cq.where(cb.equal(root.get("id"), id));
        return em.createQuery(cq).getResultList();
    }

    private <T extends HistoryRow> List<T> open(Class<T> mirror) {
        em.clear();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<T> cq = cb.createQuery(mirror);
        Root<T> root = cq.from(mirror);
        cq.where(cb.equal(root.get("validTo"), HistoryRow.OPEN));
        return em.createQuery(cq).getResultList();
    }

    private <T extends HistoryRow> T onlyOpen(Class<T> mirror, Long id) {
        List<T> found = versions(mirror, id).stream()
                .filter(row -> row.getValidTo().equals(HistoryRow.OPEN)).toList();
        assertThat(found).as("exactly one open row of %s %s", mirror.getSimpleName(), id).hasSize(1);
        return found.get(0);
    }

    private List<Long> inForce(Class<? extends HistoryRow> mirror, Instant at) {
        em.clear();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<? extends HistoryRow> root = cq.from(mirror);
        cq.select(root.get("id"))
                .where(com.geneinvoice.common.asof.AsOf.at(at).build(root, cq, cb));
        return em.createQuery(cq).getResultList();
    }
}
