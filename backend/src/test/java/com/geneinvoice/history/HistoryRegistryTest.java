package com.geneinvoice.history;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.asof.AsOf;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerHistory;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeHistory;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceHistory;
import com.geneinvoice.invoice.InvoiceItem;
import com.geneinvoice.invoice.InvoiceItemHistory;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentAllocation;
import com.geneinvoice.payment.PaymentAllocationHistory;
import com.geneinvoice.payment.PaymentHistory;
import com.geneinvoice.payment.PaymentStatus;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.CustomerPocHistory;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PromiseHistory;
import com.geneinvoice.promise.PromiseInvoiceHistory;
import com.geneinvoice.promise.PromisePaymentHistory;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionAxes;
import com.geneinvoice.region.RegionAxis;
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
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The eleven interval mirrors, the floor table and the registry every later B3 bean is driven off.
 *
 * <p>WHAT IS ACTUALLY PROVED HERE, AND WHAT IS ONLY PINNED. Nothing in the application reads a
 * mirror yet — that is this unit's whole scope — so there is no user-visible behaviour to test.
 * What CAN be wrong, and what these tests are for, is structural and all of it is load-bearing for
 * units that follow: a live column with no mirror column (the past silently stops recording it), a
 * mirror attribute spelled differently from its live twin (every ColumnDef and every scope
 * predicate stops resolving), a business id that is not called {@code id} (TableQueryExecutor
 * returns version ids instead of record ids), a projector that forgets a column (the mirror is
 * quietly wrong for ever), an open-row constraint that does not constrain (every as-of count
 * silently doubles), and a mirror that can walk back to a LIVE row (an as-of read renders today's
 * values).
 *
 * <p>It also runs {@code AsOf.at(Instant)} for the first time in this codebase. B3-CONTEXT shipped
 * it with the honest note that no entity mapped {@code validFrom}/{@code validTo}/{@code deleted},
 * so it had never executed against any root at all (B3).
 */
class HistoryRegistryTest extends IntegrationTestBase {

    @Autowired HistoryRegistry registry;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired DataSource dataSource;
    @Autowired HistoryClock clock;

    @PersistenceContext EntityManager em;

    /** The twelve tables this unit creates, and nothing else is expected to appear (B3). */
    private static final List<String> MIRROR_TABLES = List.of(
            "customer_history", "invoice_history", "invoice_item_history", "payment_history",
            "payment_allocation_history", "promise_history", "promise_invoice_history",
            "promise_payment_history", "dispute_history", "customer_poc_history", "task_history");

    private static final String FLOOR_TABLE = "history_floor";

    /** The five columns every mirror row carries whatever it mirrors (B3). */
    private static final List<String> INTERVAL_COLUMNS =
            List.of("valid_from", "valid_to", "deleted", "drifted", "changed_by_user_id");

    // ---- the mirror is complete, and says so where it is not ---------------------------------

    /**
     * Every persisted COLUMN of every mirrored live table has a mirror column of the same name,
     * or the attribute that owns it is named in the binding's {@code notMirrored} — and every
     * column the binding DECLARES really exists on the mirror, which is the half a metamodel check
     * cannot do for itself. Then, by name, every basic attribute of the live entity exists on the
     * mirror with the same Java type, which is what keeps every {@code ColumnDef} path resolving
     * and what keeps {@code ValueCoercion} taking the enum branch on both roots (B3).
     */
    @Test
    void everyMirroredEntityHasAMirrorClassForEveryPersistedAttributeOrADeclaredReason() throws Exception {
        Metamodel metamodel = entityManagerFactory.getMetamodel();
        try (Connection connection = dataSource.getConnection()) {
            for (HistoryBinding binding : registry.all()) {
                Set<String> live = columnsOf(connection, binding.liveTable());
                Set<String> mirror = columnsOf(connection, binding.mirrorTable());
                assertThat(live).describedAs("%s has no columns", binding.liveTable()).isNotEmpty();
                assertThat(mirror).describedAs("%s has no columns", binding.mirrorTable()).isNotEmpty();

                assertThat(mirror)
                        .describedAs("%s declares mirror columns its table does not have", binding.mirrorTable())
                        .containsAll(binding.mirrorColumns());
                assertThat(mirror).contains(binding.businessIdColumn());
                assertThat(mirror).containsAll(INTERVAL_COLUMNS);

                Set<String> excused = binding.notMirrored().stream()
                        .map(HistoryRegistryTest::column).collect(LinkedHashSet::new, Set::add, Set::addAll);
                assertThat(live)
                        .describedAs("%s excuses a column it does not have", binding.liveTable())
                        .containsAll(excused);

                for (String column : live) {
                    if (column.equals("id")) continue;      // mapped to businessIdColumn, asserted above
                    if (excused.contains(column)) continue;
                    assertThat(mirror)
                            .describedAs("%s.%s has no mirror column; mirror it or name it in"
                                    + " notMirrored", binding.liveTable(), column)
                            .contains(column);
                }

                if (isLink(binding)) continue;   // a join table has no entity to read attributes off
                EntityType<?> liveType = metamodel.entity(binding.entityClass());
                Map<String, Class<?>> mirrorTypes = singularTypes(metamodel.entity(binding.mirrorClass()));
                for (SingularAttribute<?, ?> attribute : liveType.getSingularAttributes()) {
                    if (binding.notMirrored().contains(attribute.getName())) continue;
                    if (attribute.isAssociation()) {
                        // A to-one whose target is NOT mirrored is kept as a read-only association
                        // under the same name (the POC, the product, the region — contract clause
                        // a.3); one whose target IS mirrored is kept as a flat Long, and the column
                        // loop above has already proved its foreign key is there (B3).
                        Class<?> onMirror = mirrorTypes.get(attribute.getName());
                        if (onMirror != null) {
                            assertThat(onMirror).isEqualTo(attribute.getJavaType());
                        }
                        continue;
                    }
                    assertThat(mirrorTypes)
                            .describedAs("%s does not declare %s, which %s maps as a plain column",
                                    binding.mirrorClass().getSimpleName(), attribute.getName(),
                                    binding.entityClass().getSimpleName())
                            .containsKey(attribute.getName());
                    assertThat(boxed(mirrorTypes.get(attribute.getName())))
                            .describedAs("%s.%s is a different Java type from the live attribute",
                                    binding.mirrorClass().getSimpleName(), attribute.getName())
                            .isEqualTo(boxed(attribute.getJavaType()));
                }
            }
        }
    }

    /**
     * The one attribute in the programme that is deliberately not mirrored, on all FOUR entities
     * that have it — customers and invoices had a counter already and S5-ROW-VERSION added one to
     * payments and payment_promises. Miss one and the W18 startup check refuses to boot (B3, B2).
     */
    @Test
    void versionIsDeclaredNotMirroredOnAllFourVersionedEntities() {
        Metamodel metamodel = entityManagerFactory.getMetamodel();
        List<Class<?>> versioned =
                List.of(Customer.class, Invoice.class, Payment.class, PaymentPromise.class);

        for (Class<?> entity : versioned) {
            assertThat(metamodel.entity(entity).hasVersionAttribute())
                    .describedAs("%s is expected to carry @Version", entity.getSimpleName())
                    .isTrue();
            assertThat(registry.forType(entity).notMirrored())
                    .describedAs("%s carries @Version and must declare it not mirrored",
                            entity.getSimpleName())
                    .contains("version");
        }

        for (HistoryBinding binding : registry.all()) {
            // Nothing else is ever excused: notMirrored is not a place to put a column somebody
            // did not feel like mirroring, and a green build that got there by widening this set
            // is exactly how the temporal boundary becomes a lie (B3).
            assertThat(binding.notMirrored()).isSubsetOf(Set.of("version"));
            assertThat(singularTypes(entityManagerFactory.getMetamodel().entity(binding.mirrorClass())))
                    .describedAs("%s must not carry a lock counter of its own",
                            binding.mirrorClass().getSimpleName())
                    .doesNotContainKey("version");
        }

        assertThat(HistoryBinding.VERSION_NOT_A_FACT).contains("optimistic-lock counter");
    }

    /**
     * The business id is named {@code id} and is NOT the primary key, which is the single trick
     * that lets {@code TableQueryExecutor.ids}' {@code root.get("id")} and {@code orderBy}'s
     * tiebreak mean the RECORD on a mirror root with no change to the executor at all (B3).
     */
    @Test
    void everyMirrorMapsItsBusinessIdUnderTheAttributeNameId() {
        Metamodel metamodel = entityManagerFactory.getMetamodel();
        for (HistoryBinding binding : registry.all()) {
            EntityType<?> mirror = metamodel.entity(binding.mirrorClass());
            SingularAttribute<?, ?> id = mirror.getSingularAttribute("id");
            assertThat(id.getJavaType()).isEqualTo(Long.class);
            assertThat(id.isId())
                    .describedAs("%s.id must NOT be the primary key", binding.mirrorClass().getSimpleName())
                    .isFalse();
            assertThat(mirror.getId(Long.class).getName())
                    .describedAs("%s's key must be historyId", binding.mirrorClass().getSimpleName())
                    .isEqualTo("historyId");
        }

        // And it means the RECORD and not the version when a query actually asks for it: two
        // versions of one account answer root.get("id") with the same 77 while their keys differ.
        CustomerHistory closed = (CustomerHistory) openRow(CustomerHistory.class, 77L, hoursAgo(5));
        closed.setValidTo(hoursAgo(1));
        closed = save(closed);
        CustomerHistory open = (CustomerHistory) save(openRow(CustomerHistory.class, 77L, hoursAgo(1)));
        assertThat(closed.getHistoryId()).isNotEqualTo(open.getHistoryId());
        assertThat(businessIds(CustomerHistory.class)).containsExactly(77L, 77L);
    }

    /**
     * Twelve new @Entity classes, so {@code RegionCoverageCheck} refuses to start until all twelve
     * are classified — which is why the axis contribution is inside this unit and not the next
     * one. Every mirror that is listed on its own is VIA_CUSTOMER_ID over a flat customer_id,
     * INCLUDING the customer mirror, and the deviation from the blueprint's OWN is deliberate:
     * OWN is {@code root.get("region").get("id")}, which would read the region from the mirror and
     * lose the now-clause of the leak guard (B1, B3).
     */
    @Test
    void everyMirrorDeclaresARegionAxisAndEveryNoneGivesAReason() {
        Map<Class<?>, RegionAxis> expected = new LinkedHashMap<>();
        expected.put(CustomerHistory.class, RegionAxis.VIA_CUSTOMER_ID);
        expected.put(InvoiceHistory.class, RegionAxis.VIA_CUSTOMER_ID);
        expected.put(PaymentHistory.class, RegionAxis.VIA_CUSTOMER_ID);
        expected.put(PromiseHistory.class, RegionAxis.VIA_CUSTOMER_ID);
        expected.put(DisputeHistory.class, RegionAxis.VIA_CUSTOMER_ID);
        expected.put(TaskHistory.class, RegionAxis.VIA_CUSTOMER_ID);
        expected.put(InvoiceItemHistory.class, RegionAxis.NONE);
        expected.put(PaymentAllocationHistory.class, RegionAxis.NONE);
        expected.put(CustomerPocHistory.class, RegionAxis.NONE);
        expected.put(PromiseInvoiceHistory.class, RegionAxis.NONE);
        expected.put(PromisePaymentHistory.class, RegionAxis.NONE);
        expected.put(HistoryFloor.class, RegionAxis.NONE);

        assertThat(HistoryAxes.CONTRIBUTION.axes()).containsExactlyInAnyOrderEntriesOf(expected);

        Metamodel metamodel = entityManagerFactory.getMetamodel();
        for (Map.Entry<Class<?>, RegionAxis> entry : expected.entrySet()) {
            assertThat(metamodel.entity(entry.getKey())).isNotNull();
            assertThat(RegionAxes.of(entry.getKey()))
                    .describedAs("%s", entry.getKey().getSimpleName())
                    .isEqualTo(entry.getValue());
            if (entry.getValue() == RegionAxis.NONE) {
                assertThat(RegionAxes.reason(entry.getKey()))
                        .describedAs("%s is unregioned; the reason must be a sentence",
                                entry.getKey().getSimpleName())
                        .isNotBlank().hasSizeGreaterThan(20);
            } else {
                // VIA_CUSTOMER_ID is RegionScope.clause reading root.get("customerId"), so a
                // mirror that declared the axis without the column would throw on its first
                // region-narrowed request rather than here (B1, B3).
                assertThat(singularTypes(metamodel.entity(entry.getKey())))
                        .describedAs("%s is VIA_CUSTOMER_ID and must carry a flat customerId",
                                entry.getKey().getSimpleName())
                        .containsEntry("customerId", Long.class);
            }
        }
    }

    /**
     * Twelve tables appear and NOTHING is added to a table that already has rows, which is the
     * property that makes this unit safe to deploy: the mirrors are new and empty, so ddl-auto
     * creates them whole and cannot trip {@code hbm2ddl.halt_on_error} (B3).
     */
    @Test
    void theApplicationStartsWithTwelveNewTablesAndNoColumnAddedToAnyExistingTable() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            for (String table : MIRROR_TABLES) {
                Set<String> columns = columnsOf(connection, table);
                assertThat(columns).describedAs("%s was not created", table).isNotEmpty();
                assertThat(columns).describedAs("%s", table).contains("history_id");
                assertThat(columns).describedAs("%s", table).containsAll(INTERVAL_COLUMNS);
            }
            assertThat(columnsOf(connection, FLOOR_TABLE)).containsExactlyInAnyOrder("id", "installed_at");
            assertThat(MIRROR_TABLES).hasSize(11);

            // The other direction: not one of the interval columns turns up on a live table, so
            // nothing in this unit widened a populated one (B3).
            Set<String> liveTables = new LinkedHashSet<>();
            for (HistoryBinding binding : registry.all()) liveTables.add(binding.liveTable());
            for (String table : liveTables) {
                Set<String> columns = columnsOf(connection, table);
                assertThat(columns).describedAs("%s gained a history column", table)
                        .doesNotContain("history_id", "valid_from", "valid_to", "drifted");
            }
        }
    }

    /** One definition of the sentinel, because two that drifted would double every as-of count. */
    @Test
    void theOpenSentinelIsTheSameInstantInTheInterfaceAndInTheAsOfPredicate() {
        assertThat(HistoryRow.OPEN).isSameAs(AsOf.OPEN);
        assertThat(HistoryRow.OPEN)
                .isEqualTo(LocalDate.of(9999, 12, 31).atStartOfDay(ZoneOffset.UTC).toInstant());
        // And a mirror row opens on it without anybody remembering to say so.
        assertThat(CustomerHistory.builder().build().getValidTo()).isEqualTo(HistoryRow.OPEN);
        assertThat(PromiseInvoiceHistory.builder().build().getValidTo()).isEqualTo(HistoryRow.OPEN);
    }

    // ---- the constraint that makes the writer race-free ---------------------------------------

    /**
     * {@code uk_<x>h_open} is the whole of B3's concurrency story: the writer takes no lock at all
     * and relies on a duplicate INSERT being refused. It is a PLAIN unique constraint on
     * (record id, valid_to) rather than a partial index precisely so that H2 can express it and
     * this test can exist — a Postgres-only index would be invisible to the whole suite (B3).
     */
    @Test
    void theOpenRowConstraintPermitsExactlyOneOpenVersionPerRecord() {
        for (HistoryBinding binding : registry.all()) {
            Class<? extends HistoryRow> type = binding.mirrorClass();
            save(openRow(type, 41L, hoursAgo(3)));

            assertThatThrownBy(() -> save(openRow(type, 41L, hoursAgo(1))))
                    .describedAs("%s let a second open row in", binding.mirrorTable())
                    .isInstanceOf(DataIntegrityViolationException.class);

            // A CLOSED row for the same record is fine — that is the whole point of the sentinel:
            // the constraint says "one open version", not "one version" (B3).
            HistoryRow closed = openRow(type, 41L, hoursAgo(5));
            setValidTo(closed, hoursAgo(3));
            assertThatCode(() -> save(closed)).doesNotThrowAnyException();

            // And a different record may of course be open at the same time.
            assertThatCode(() -> save(openRow(type, 42L, hoursAgo(1)))).doesNotThrowAnyException();
        }
    }

    // ---- AsOf.at, executed for the first time -------------------------------------------------

    /**
     * {@code AsOf.at(T)} names {@code validFrom}, {@code validTo} and {@code deleted} on whatever
     * root it is handed, and B3-CONTEXT shipped it with no root in existence that mapped them, so
     * it had never run. It now runs against all eleven, picks exactly the version in force and
     * drops a tombstone, which is what makes a count over a mirror count RECORDS (B3).
     */
    @Test
    void theAsOfPredicateResolvesAgainstEveryMirrorRootAndPicksTheVersionInForce() {
        Instant old = hoursAgo(6);
        Instant changed = hoursAgo(3);
        for (HistoryBinding binding : registry.all()) {
            Class<? extends HistoryRow> type = binding.mirrorClass();
            HistoryRow before = openRow(type, 5L, old);
            setValidTo(before, changed);
            save(before);
            save(openRow(type, 5L, changed));

            HistoryRow gone = openRow(type, 6L, old);
            setDeleted(gone);
            save(gone);

            assertThat(inForce(type, hoursAgo(5)))
                    .describedAs("%s as of five hours ago", binding.mirrorTable())
                    .containsExactly(before.getHistoryId());
            assertThat(inForce(type, hoursAgo(1)))
                    .describedAs("%s as of an hour ago", binding.mirrorTable())
                    .hasSize(1)
                    .doesNotContain(before.getHistoryId());
            // Exactly one row per record at any instant: a count over the mirror is a count of
            // records and not of versions, which is why TableQueryExecutor.count needs no change.
            assertThat(inForce(type, hoursAgo(5))).hasSize(1);
            // The tombstone is never in force: the record is absent from the moment it went.
            assertThat(inForce(type, hoursAgo(5))).doesNotContain(gone.getHistoryId());
            assertThat(inForce(type, hoursAgo(1))).doesNotContain(gone.getHistoryId());
        }
    }

    // ---- the projectors ------------------------------------------------------------------------

    /**
     * THE TEST THAT CATCHES A FORGOTTEN COLUMN. A projector is hand-written on purpose — so a
     * renamed field is a compile error and so the startup check has something to cross-check —
     * but hand-written also means a column can simply be left out, and a mirror that silently
     * wrote null for {@code status} for a year would be worse than no mirror at all. Every
     * declared column of every binding is filled from a fully populated live row (B3).
     */
    @Test
    void everyProjectorFillsEveryColumnItsBindingDeclares() {
        for (HistoryBinding binding : registry.all()) {
            HistoryJdbc.Row row = HistoryJdbc.row(binding.mirrorColumns());
            binding.projector().project(sampleFor(binding), row);
            for (String column : binding.mirrorColumns()) {
                assertThat(row.get(column))
                        .describedAs("%s's projector left %s null for a fully populated row",
                                binding.mirrorTable(), column)
                        .isNotNull();
            }
            assertThat(row.ordered()).hasSameSizeAs(binding.mirrorColumns());
            // A column nobody declared cannot be smuggled in, so the INSERT's column list and the
            // values the projector produced can never fall out of step (B3).
            assertThatThrownBy(() -> row.set("not_a_column", 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---- the drift query -------------------------------------------------------------------

    /**
     * Eleven hand-built SQL strings that nothing executes yet are eleven chances to ship a typo
     * into a job that runs every fifteen minutes and logs its failures at WARN. They are run here,
     * against the real database, and the customer one is driven through both answers: a live row
     * with no mirror IS reported, and the same row with a matching open mirror is NOT (B3).
     */
    @Test
    void everyBindingsDriftQueryRunsAndReportsALiveRowWithNoMirror() throws Exception {
        Customer only = customer("Drift Ltd");
        // B3-WRITER landed after this test was written, so saving that customer ALSO wrote its
        // mirror row and there is no longer any drift to find. Stripping the mirrors back asks
        // the reconciler's question the way it will really be asked — of a row some path wrote
        // without going through Hibernate — rather than of a mirror that was never filled (B3).
        clearMirrors();

        try (Connection connection = dataSource.getConnection()) {
            for (HistoryBinding binding : registry.all()) {
                // No mirror row exists for anything, so EVERY live row is drift. Asserting the
                // exact set rather than "it did not throw" is what makes this catch a join
                // condition that silently matches nothing (B3).
                assertThat(runDrift(connection, binding))
                        .describedAs("%s did not report the live rows that have no mirror row",
                                binding.mirrorTable())
                        .containsExactlyInAnyOrderElementsOf(liveIds(connection, binding));
            }
            assertThat(runDrift(connection, registry.forType(Customer.class))).contains(only.getId());

            save(CustomerHistory.builder()
                    .id(only.getId())
                    .validFrom(hoursAgo(1))
                    .name(only.getName())
                    .phone(only.getPhone())
                    .email(only.getEmail())
                    .address(only.getAddress())
                    .creditBalance(only.getCreditBalance())
                    .paymentTerm(only.getPaymentTerm())
                    .region(only.getRegion())
                    .createdAt(only.getCreatedAt())
                    .build());

            assertThat(runDrift(connection, registry.forType(Customer.class)))
                    .describedAs("a customer whose open mirror row agrees with it is not drift")
                    .isEmpty();
        }
    }

    /**
     * The mirror is WRITTEN by raw JDBC and READ by JPA, so the two have to agree about what an
     * Instant is. They would not if {@link HistoryJdbc#setInstant} used a zoneless
     * {@code java.sql.Timestamp}: the driver converts one through the JVM's default zone, and a
     * region backfill in this very build shipped exactly that bug and had to be fixed (B3).
     */
    @Test
    void anInstantWrittenByJdbcIsTheSameInstantWhenHibernateReadsItBack() throws Exception {
        Instant at = Instant.parse("2026-01-31T23:17:05.123456Z");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into customer_history (customer_id, valid_from, valid_to, deleted,"
                             + " drifted) values (?, ?, ?, false, false)")) {
            statement.setLong(1, 909L);
            HistoryJdbc.setInstant(statement, 2, at);
            HistoryJdbc.setInstant(statement, 3, HistoryRow.OPEN);
            statement.executeUpdate();
        }

        CustomerHistory read = customerHistoryRepository.findAll().stream()
                .filter(r -> r.getId() == 909L).findFirst().orElseThrow();
        assertThat(read.getValidFrom()).isEqualTo(at);
        assertThat(read.getValidTo()).isEqualTo(HistoryRow.OPEN);

        // And back out through the JDBC reader, which is what the reconciler uses.
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select valid_from, valid_to from customer_history where customer_id = 909");
             ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(HistoryJdbc.getInstant(rows, 1)).isEqualTo(at);
            assertThat(HistoryJdbc.getInstant(rows, 2)).isEqualTo(HistoryRow.OPEN);
        }
    }

    /**
     * A mirror maps a foreign key that points at ANOTHER MIRRORED entity as a plain Long, with no
     * association to walk. This is not tidiness: joining a past invoice to the live customer would
     * render today's name, today's terms and today's region on a row that is supposed to be a
     * snapshot, which is the exact leak as-of exists to close. The POC, the product and the region
     * are the deliberate exceptions and they are not mirrored, so they render as they are today by
     * contract clause a.3 (B3).
     */
    @Test
    void noMirrorCanWalkToALiveRowOfAMirroredEntity() {
        Metamodel metamodel = entityManagerFactory.getMetamodel();
        Set<Class<?>> mirrored = registry.mirroredTypes();
        assertThat(mirrored).contains(Customer.class, Invoice.class, Payment.class);

        for (HistoryBinding binding : registry.all()) {
            for (Attribute<?, ?> attribute : metamodel.entity(binding.mirrorClass()).getAttributes()) {
                if (!attribute.isAssociation()) continue;
                Class<?> target = attribute instanceof PluralAttribute<?, ?, ?> plural
                        ? plural.getElementType().getJavaType()
                        : attribute.getJavaType();
                assertThat(mirrored)
                        .describedAs("%s.%s can walk to the live %s",
                                binding.mirrorClass().getSimpleName(), attribute.getName(),
                                target.getSimpleName())
                        .doesNotContain(target);
            }
        }
    }

    /** The clock is a named bean so a test can build a real timeline, and it is the wall clock. */
    @Test
    void theWriteSideClockIsTheWallClockAndNotTheAsOfDate() {
        Instant before = Instant.now();
        Instant now = clock.now();
        assertThat(now).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static boolean isLink(HistoryBinding binding) {
        return binding.mirrorClass() == PromiseInvoiceHistory.class
                || binding.mirrorClass() == PromisePaymentHistory.class;
    }

    /** The physical name Spring Boot's CamelCaseToUnderscores strategy gives an attribute. */
    private static String column(String attribute) {
        return attribute.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static Set<String> columnsOf(Connection connection, String table) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        Set<String> columns = new LinkedHashSet<>();
        for (String spelling : List.of(table, table.toUpperCase(Locale.ROOT))) {
            try (ResultSet rows = metaData.getColumns(null, null, spelling, null)) {
                while (rows.next()) columns.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
            if (!columns.isEmpty()) return columns;
        }
        return columns;
    }

    private static Map<String, Class<?>> singularTypes(EntityType<?> type) {
        Map<String, Class<?>> out = new LinkedHashMap<>();
        for (SingularAttribute<?, ?> a : type.getSingularAttributes()) out.put(a.getName(), a.getJavaType());
        return out;
    }

    private static Class<?> boxed(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        return type;
    }

    private static Instant hoursAgo(int hours) {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(hours, ChronoUnit.HOURS);
    }

    private List<Long> inForce(Class<? extends HistoryRow> type, Instant at) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<? extends HistoryRow> root = cq.from(type);
        cq.select(root.get("historyId")).where(AsOf.at(at).build(root, cq, cb));
        return em.createQuery(cq).getResultList();
    }

    private List<Long> businessIds(Class<? extends HistoryRow> type) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<? extends HistoryRow> root = cq.from(type);
        cq.select(root.get("id"));
        return em.createQuery(cq).getResultList();
    }

    /** Every live row of a binding's table, by the id its drift query selects. */
    private List<Long> liveIds(Connection connection, HistoryBinding binding) throws Exception {
        String idColumn = isLink(binding) ? "promise_id" : "id";
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select " + idColumn + " from " + binding.liveTable());
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) ids.add(rows.getLong(1));
        }
        return ids;
    }

    private List<Long> runDrift(Connection connection, HistoryBinding binding) throws Exception {
        List<Long> found = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(binding.driftSql())) {
            HistoryJdbc.setInstant(statement, 1, HistoryRow.OPEN);
            statement.setLong(2, 0L);
            statement.setInt(3, 1000);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) found.add(rows.getLong(1));
            }
        }
        return found;
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private static HistoryRow openRow(Class<? extends HistoryRow> type, long id, Instant from) {
        if (type == CustomerHistory.class) return CustomerHistory.builder().id(id).validFrom(from).build();
        if (type == InvoiceHistory.class) return InvoiceHistory.builder().id(id).validFrom(from).build();
        if (type == InvoiceItemHistory.class) return InvoiceItemHistory.builder().id(id).validFrom(from).build();
        if (type == PaymentHistory.class) return PaymentHistory.builder().id(id).validFrom(from).build();
        if (type == PaymentAllocationHistory.class) {
            return PaymentAllocationHistory.builder().id(id).validFrom(from).build();
        }
        if (type == PromiseHistory.class) return PromiseHistory.builder().id(id).validFrom(from).build();
        if (type == PromiseInvoiceHistory.class) {
            return PromiseInvoiceHistory.builder().id(id).invoiceId(1L).validFrom(from).build();
        }
        if (type == PromisePaymentHistory.class) {
            return PromisePaymentHistory.builder().id(id).paymentId(1L).validFrom(from).build();
        }
        if (type == DisputeHistory.class) return DisputeHistory.builder().id(id).validFrom(from).build();
        if (type == CustomerPocHistory.class) return CustomerPocHistory.builder().id(id).validFrom(from).build();
        if (type == TaskHistory.class) return TaskHistory.builder().id(id).validFrom(from).build();
        throw new IllegalArgumentException("No fixture for " + type);
    }

    private static void setValidTo(HistoryRow row, Instant to) {
        if (row instanceof CustomerHistory r) r.setValidTo(to);
        else if (row instanceof InvoiceHistory r) r.setValidTo(to);
        else if (row instanceof InvoiceItemHistory r) r.setValidTo(to);
        else if (row instanceof PaymentHistory r) r.setValidTo(to);
        else if (row instanceof PaymentAllocationHistory r) r.setValidTo(to);
        else if (row instanceof PromiseHistory r) r.setValidTo(to);
        else if (row instanceof PromiseInvoiceHistory r) r.setValidTo(to);
        else if (row instanceof PromisePaymentHistory r) r.setValidTo(to);
        else if (row instanceof DisputeHistory r) r.setValidTo(to);
        else if (row instanceof CustomerPocHistory r) r.setValidTo(to);
        else if (row instanceof TaskHistory r) r.setValidTo(to);
        else throw new IllegalArgumentException("No fixture for " + row.getClass());
    }

    private static void setDeleted(HistoryRow row) {
        if (row instanceof CustomerHistory r) r.setDeleted(true);
        else if (row instanceof InvoiceHistory r) r.setDeleted(true);
        else if (row instanceof InvoiceItemHistory r) r.setDeleted(true);
        else if (row instanceof PaymentHistory r) r.setDeleted(true);
        else if (row instanceof PaymentAllocationHistory r) r.setDeleted(true);
        else if (row instanceof PromiseHistory r) r.setDeleted(true);
        else if (row instanceof PromiseInvoiceHistory r) r.setDeleted(true);
        else if (row instanceof PromisePaymentHistory r) r.setDeleted(true);
        else if (row instanceof DisputeHistory r) r.setDeleted(true);
        else if (row instanceof CustomerPocHistory r) r.setDeleted(true);
        else if (row instanceof TaskHistory r) r.setDeleted(true);
        else throw new IllegalArgumentException("No fixture for " + row.getClass());
    }

    @SuppressWarnings("unchecked")
    private <T extends HistoryRow> T save(T row) {
        JpaRepository<HistoryRow, Long> repository =
                (JpaRepository<HistoryRow, Long>) (JpaRepository<?, ?>) repositoryFor(row.getClass());
        // saveAndFlush and never save: on Postgres Hibernate flushes every INSERT before every
        // UPDATE, so a close-then-open pair batched into one flush fails the open-row constraint
        // while every H2 test passes. B3 closes and opens rows constantly, and this is the shape
        // every writer and every test of one has to use (B3).
        return (T) repository.saveAndFlush(row);
    }

    /** Every mirror row the live writer has already produced for this test's own fixtures (B3). */
    private void clearMirrors() {
        for (HistoryBinding binding : registry.all()) {
            repositoryFor(binding.mirrorClass()).deleteAll();
        }
    }

    private JpaRepository<?, Long> repositoryFor(Class<?> type) {
        if (type == CustomerHistory.class) return customerHistoryRepository;
        if (type == InvoiceHistory.class) return invoiceHistoryRepository;
        if (type == InvoiceItemHistory.class) return invoiceItemHistoryRepository;
        if (type == PaymentHistory.class) return paymentHistoryRepository;
        if (type == PaymentAllocationHistory.class) return paymentAllocationHistoryRepository;
        if (type == PromiseHistory.class) return promiseHistoryRepository;
        if (type == PromiseInvoiceHistory.class) return promiseInvoiceHistoryRepository;
        if (type == PromisePaymentHistory.class) return promisePaymentHistoryRepository;
        if (type == DisputeHistory.class) return disputeHistoryRepository;
        if (type == CustomerPocHistory.class) return customerPocHistoryRepository;
        if (type == TaskHistory.class) return taskHistoryRepository;
        throw new IllegalArgumentException("No repository for " + type);
    }

    /** A live row with EVERY mirrored field populated, so a forgotten projector line shows up. */
    private static Object sampleFor(HistoryBinding binding) {
        if (isLink(binding)) return new HistoryBinding.Link(1L, 2L);

        Instant now = Instant.now();
        Region region = Region.builder().id(3L).code("HQ").name("HQ Branch").active(true).build();
        User person = User.builder().id(4L).username("poc").fullName("P O C").build();
        Product product = Product.builder().id(5L).name("Widget").price(new BigDecimal("9.00")).build();
        Customer customer = Customer.builder().id(6L).name("Acme").phone("1").email("a@b.c")
                .address("Street").creditBalance(new BigDecimal("1.00")).paymentTerm(PaymentTerm.NET_30)
                .region(region).build();
        customer.setCreatedAt(now);

        Invoice invoice = Invoice.builder().id(7L).invoiceNumber("INV-1").customer(customer)
                .invoiceDate(now).dueDate(LocalDate.now()).paymentTerm(PaymentTerm.NET_30)
                .total(new BigDecimal("10.00")).paidAmount(new BigDecimal("1.00"))
                .status(InvoiceStatus.UNPAID).notes("note").salesPoc(person).createdAt(now).build();

        Payment payment = Payment.builder().id(8L).customer(customer).amount(new BigDecimal("5.00"))
                .creditApplied(new BigDecimal("0.50")).method("CASH").notes("paid").paidAt(now)
                .collectionPoc(person).status(PaymentStatus.ACTIVE).build();

        Class<?> mirror = binding.mirrorClass();
        if (mirror == CustomerHistory.class) return customer;
        if (mirror == InvoiceHistory.class) return invoice;
        if (mirror == InvoiceItemHistory.class) {
            return InvoiceItem.builder().id(9L).invoice(invoice).product(product).quantity(2)
                    .unitPrice(new BigDecimal("5.00")).lineTotal(new BigDecimal("10.00")).build();
        }
        if (mirror == PaymentHistory.class) return payment;
        if (mirror == PaymentAllocationHistory.class) {
            return PaymentAllocation.builder().id(10L).payment(payment).invoice(invoice)
                    .amount(new BigDecimal("5.00")).build();
        }
        if (mirror == PromiseHistory.class) {
            PaymentPromise promise = PaymentPromise.builder().id(11L).customer(customer)
                    .amount(new BigDecimal("20.00")).promisedDate(LocalDate.now())
                    .collectionPoc(person).notes("promise").status(PromiseStatus.OPEN)
                    .fulfilledAmount(new BigDecimal("2.00")).statusOverridden(true)
                    .overrideReason("because").overriddenByUserId(4L).overriddenAt(now)
                    .brokenNotifiedAt(now).createdByUserId(4L).build();
            promise.setCreatedAt(now);
            promise.setUpdatedAt(now);
            return promise;
        }
        if (mirror == DisputeHistory.class) {
            Dispute dispute = Dispute.builder().id(12L).customerId(6L).openedByUserId(4L)
                    .targetType(DisputeTargetType.INVOICE).targetId(7L).reason("wrong")
                    .proposedChangeJson("{}").status(DisputeStatus.PENDING).adminNotes("noted")
                    .resolvedByUserId(4L).resolvedAt(now).build();
            dispute.setCreatedAt(now);
            dispute.setUpdatedAt(now);
            return dispute;
        }
        if (mirror == CustomerPocHistory.class) {
            CustomerPoc seat = CustomerPoc.builder().id(13L).customer(customer).user(person)
                    .pocType(PocType.SALES).primary(true).createdByUserId(4L).build();
            seat.setCreatedAt(now);
            return seat;
        }
        if (mirror == TaskHistory.class) {
            return Task.builder().id(14L).entityType(TaskEntityType.INVOICE).entityId(7L)
                    .entityLabel("INV-1").customerId(6L).title("Chase").notes("soon")
                    .dueDate(LocalDate.now()).status(TaskStatus.OPEN).createdByUserId(4L)
                    .createdByRuleId(15L).createdByStepId(16L).completedByUserId(4L)
                    .completedAt(now).createdAt(now).updatedAt(now).build();
        }
        throw new IllegalArgumentException("No sample for " + mirror);
    }
}
