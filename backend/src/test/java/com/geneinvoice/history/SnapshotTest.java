package com.geneinvoice.history;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.history.types.InvoiceHistory;
import com.geneinvoice.history.versions.CustomerVersion;
import com.geneinvoice.history.versions.DisputeVersion;
import com.geneinvoice.history.versions.EntityLinkVersion;
import com.geneinvoice.history.versions.InvoiceVersion;
import com.geneinvoice.history.versions.ItemSnapshot;
import com.geneinvoice.history.versions.PromiseVersion;
import com.geneinvoice.history.versions.TaskVersion;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceItem;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.task.TaskDtos;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.task.TaskService;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import org.hibernate.Hibernate;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A version is a faithful copy of its record's columns, and two versions are "the same" exactly when every
 * stored business column is: amounts by value, instants at the microsecond kept, lines by their JSON, and never
 * by the validity window, the provenance or the columns a type leaves out (§5.7.2).
 */
class SnapshotTest extends IntegrationTestBase {

    /** Kept on a version, but a change to them alone is not a new version. */
    static final Map<Class<?>, Set<String>> EXCLUDED = Map.of(
            PromiseVersion.class, Set.of("updatedAt", "brokenNotifiedAt"),
            DisputeVersion.class, Set.of("updatedAt"),
            TaskVersion.class, Set.of("updatedAt"));

    static final Instant T0 = Instant.parse("2026-03-10T09:30:00.123456Z");

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired DisputeService disputeService;
    @Autowired PocService pocService;
    @Autowired TaskService taskService;
    @Autowired VersionStore versionStore;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManagerFactory emf;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager em;

    Customer acme;
    Product widget;
    Product gadget;
    Invoice invoice;
    Payment payment;
    Long promiseId;

    @BeforeEach
    void family() {
        User admin = userRepository.findByUsername("admin").orElseThrow();
        User sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        User cara = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        actAs(admin);
        widget = product("Widget", "100");
        gadget = product("Gadget", "25.50");
        acme = customerRepository.save(Customer.builder().name("Acme Ltd").phone("555-0100").email("ap@acme.test")
                .address("1 Main St").paymentTerm(PaymentTerm.NET_15).region(defaultRegion()).build());
        invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, "First order",
                sam.getId(), List.of(new InvoiceDtos.LineInput(gadget.getId(), 1, null),
                new InvoiceDtos.LineInput(widget.getId(), 2, null))));
        promiseId = promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(), new BigDecimal("60.00"),
                Today.date().plusDays(3), cara.getId(), "Friday", List.of(invoice.getId()))).id();
        payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(), new BigDecimal("40.00"),
                "Cash", "Part", List.of(invoice.getId()), cara.getId(), List.of(promiseId)));
        pocService.add(acme.getId(), PocType.COLLECTION, cara.getId(), true);
        actAs(customerUser("acme.login", acme.getId()));
        disputeService.open(new DisputeDtos.CreateDisputeRequest(DisputeTargetType.INVOICE, invoice.getId(),
                "Wrong quantity", null));
        actAs(admin);
        taskService.create(new TaskDtos.CreateTaskRequest(TaskEntityType.INVOICE, invoice.getId(), "Chase it",
                "Call the buyer", Today.date().plusDays(2), List.of()));
    }

    /**
     * Versions written by hand (and one spoiled with SQL) replaced by history rebuilt from the live rows, as the
     * baseline would write it, so the suite's {@code historyIsInStep} holds after this class too.
     */
    @AfterEach
    void rebuildHistory() {
        historyTestSupport.clearVersions();
        for (TypeHistory<?, ?> history : HistoryModel.types()) {
            List<Long> ids = tx.execute(status -> all(history.liveType()).stream().map(this::idOf).toList());
            historyTestSupport.touch(history.liveType(), ids);
        }
    }

    @Test
    void aSnapshotCopiesEveryColumnOfItsRecordAndNothingOfTheWindow() {
        rolledBack(s -> {
            for (TypeHistory<?, ?> history : HistoryModel.types()) {
                List<?> live = all(history.liveType());
                assertThat(live).as(history.code()).isNotEmpty();
                // Every column holds a value, so a column the snapshot forgot shows up as a null.
                live.forEach(SnapshotTest::fillNulls);
                Map<Long, VersionRow> versions = snapshot(history, live, s);

                assertThat(versions.keySet()).as(history.code())
                        .containsExactlyElementsOf(live.stream().map(this::idOf).toList());
                EntityType<?> liveType = emf.getMetamodel().entity(history.liveType());
                for (Object record : live) {
                    VersionRow v = versions.get(idOf(record));
                    assertThat(v.getId()).isEqualTo(idOf(record));
                    assertThat(v.getVersionId()).isNull();
                    assertThat(v.getValidFrom()).isNull();
                    assertThat(v.getValidTo()).isNull();
                    assertThat(v.getTxId()).isNull();
                    assertThat(v.getOrigin()).isNull();
                    for (Attribute<?, ?> attribute : liveType.getAttributes()) {
                        String name = attribute.getName();
                        String where = history.code() + "." + name;
                        if (name.equals("id") || name.equals("version")) continue;
                        if (attribute instanceof PluralAttribute<?, ?, ?>) continue;
                        Object liveValue = read(record, name);
                        // A column a version row names for itself is kept under another name (a task's origin).
                        String kept = VersionShapeTest.RENAMED.getOrDefault(history.versionType(), Map.of())
                                .getOrDefault(name, List.of(name)).get(0);
                        if (attribute.isAssociation()) {
                            assertThat(read(v, name + "Id")).as(where).isNotNull().isEqualTo(idOf(liveValue));
                        } else if (liveValue instanceof BigDecimal amount) {
                            assertThat((BigDecimal) read(v, kept)).as(where).isEqualByComparingTo(amount);
                        } else {
                            assertThat(read(v, kept)).as(where).isNotNull().isEqualTo(liveValue);
                        }
                    }
                }
            }
        });
    }

    @Test
    void anInvoicesLinesAreItsOwnColumnsInIdOrderWithAmountsAtScaleTwo() {
        List<InvoiceItem> items = tx.execute(status -> em.createQuery(
                "select i from InvoiceItem i join fetch i.product where i.invoice.id = :id order by i.id",
                InvoiceItem.class).setParameter("id", invoice.getId()).getResultList());
        assertThat(items).hasSize(2);
        InvoiceItem first = items.get(0);
        InvoiceItem second = items.get(1);

        String json = inRolledBackSession(s -> ((InvoiceVersion) snapshot(HistoryModel.of(Invoice.class),
                List.of(em.find(Invoice.class, invoice.getId())), s).get(invoice.getId())).getItemsJson());

        assertThat(json).isEqualTo("[{\"id\":" + first.getId() + ",\"productId\":" + gadget.getId()
                + ",\"quantity\":1,\"unitPrice\":\"25.50\",\"lineTotal\":\"25.50\"},{\"id\":" + second.getId()
                + ",\"productId\":" + widget.getId()
                + ",\"quantity\":2,\"unitPrice\":\"100.00\",\"lineTotal\":\"200.00\"}]");
        assertThat(json).doesNotContain("productName").doesNotContain("Widget").doesNotContain("Gadget");
        assertThat(InvoiceHistory.items(json)).containsExactly(
                new ItemSnapshot(first.getId(), gadget.getId(), 1, new BigDecimal("25.50"), new BigDecimal("25.50")),
                new ItemSnapshot(second.getId(), widget.getId(), 2, new BigDecimal("100.00"),
                        new BigDecimal("200.00")));

        // The same lines in any order, at any scale, are the same text.
        assertThat(InvoiceHistory.itemsJson(List.of(
                new ItemSnapshot(9L, 3L, 1, new BigDecimal("7"), new BigDecimal("7.0")),
                new ItemSnapshot(4L, 3L, 2, new BigDecimal("1.5"), new BigDecimal("3.000")))))
                .isEqualTo(InvoiceHistory.itemsJson(List.of(
                        new ItemSnapshot(4L, 3L, 2, new BigDecimal("1.50"), new BigDecimal("3.00")),
                        new ItemSnapshot(9L, 3L, 1, new BigDecimal("7.00"), new BigDecimal("7.00")))))
                .startsWith("[{\"id\":4,");
        assertThat(InvoiceHistory.itemsJson(List.of())).isEqualTo("[]");
    }

    @Test
    void aProductRenameChangesNoInvoiceSnapshot() {
        String before = invoiceItemsJson();
        tx.executeWithoutResult(status -> em.find(Product.class, widget.getId()).setName("Widget Pro"));
        assertThat(invoiceItemsJson()).isEqualTo(before);
    }

    @Test
    void sameStateSeesEveryBusinessColumnAndNothingElse() {
        rolledBack(s -> {
            for (TypeHistory<?, ?> history : HistoryModel.types()) {
                List<?> live = all(history.liveType());
                live.forEach(SnapshotTest::fillNulls);
                Class<?> version = history.versionType();
                Set<String> excluded = EXCLUDED.getOrDefault(version, Set.of());
                for (Object record : live) {
                    Long id = idOf(record);
                    VersionRow open = snapshot(history, live, s).get(id);
                    assertThat(same(history, open, snapshot(history, live, s).get(id))).as(history.code()).isTrue();

                    EntityType<?> versionType = emf.getMetamodel().entity(version);
                    for (SingularAttribute<?, ?> attribute : versionType.getSingularAttributes()) {
                        if (attribute.isAssociation()) continue;
                        String name = attribute.getName();
                        String where = history.code() + "." + name;
                        VersionRow next = snapshot(history, live, s).get(id);
                        write(next, name, changed(read(next, name), attribute.getJavaType()));
                        boolean ignored = VersionShapeTest.WINDOW.contains(name) || excluded.contains(name);
                        assertThat(same(history, open, next)).as(where + " changed").isEqualTo(ignored);

                        Object value = read(open, name);
                        VersionRow rescaled = snapshot(history, live, s).get(id);
                        if (value instanceof BigDecimal amount) {
                            write(rescaled, name, amount.setScale(4));
                            assertThat(same(history, open, rescaled)).as(where + " at another scale").isTrue();
                        } else if (value instanceof Instant instant) {
                            write(open, name, instant.truncatedTo(ChronoUnit.MICROS));
                            write(rescaled, name, instant.truncatedTo(ChronoUnit.MICROS).plusNanos(400));
                            assertThat(same(history, open, rescaled)).as(where + " within its microsecond").isTrue();
                            write(rescaled, name, instant.truncatedTo(ChronoUnit.MICROS).plusNanos(600));
                            assertThat(same(history, open, rescaled)).as(where + " past its microsecond")
                                    .isEqualTo(ignored);
                            write(open, name, instant);
                        }
                    }
                }
            }
        });
    }

    @Test
    void aStoredVersionReadsBackThroughTheStoreAsTheSameState() {
        Instant before = T0.minus(1, ChronoUnit.DAYS);
        // Only the versions this test stores: the ones capture wrote while the family was made are dropped.
        historyTestSupport.clearVersions();
        tx.executeWithoutResult(status -> {
            SharedSessionContractImplementor s = em.unwrap(SharedSessionContractImplementor.class);
            for (TypeHistory<?, ?> history : HistoryModel.types()) {
                for (VersionRow v : snapshot(history, all(history.liveType()), s).values()) {
                    em.persist(stamped(v, T0, VersionRow.OPEN));
                }
            }
            CustomerVersion old = (CustomerVersion) snapshot(HistoryModel.of(Customer.class),
                    List.of(em.find(Customer.class, acme.getId())), s).get(acme.getId());
            old.setName("Acme (old name)");
            em.persist(stamped(old, before, T0));
            for (LinkHistory link : HistoryModel.linksOf(PaymentPromise.class)) {
                link.currentTargets(s, List.of(promiseId)).forEach((owner, targets) -> targets.forEach(target -> {
                    EntityLinkVersion row = new EntityLinkVersion();
                    row.setKind(link.kind());
                    row.setOwnerId(owner);
                    row.setId(target);
                    em.persist(stamped(row, T0, VersionRow.OPEN));
                }));
            }
        });

        rolledBack(s -> {
            for (TypeHistory<?, ?> history : HistoryModel.types()) {
                List<?> live = all(history.liveType());
                List<Long> ids = live.stream().map(this::idOf).toList();
                Map<Long, VersionRow> fresh = snapshot(history, live, s);
                List<? extends VersionRow> open = versionStore.open(history.versionType(), ids);
                assertThat(open).as(history.code()).extracting(VersionRow::getId).containsExactlyElementsOf(ids);
                for (VersionRow v : open) {
                    assertThat(v.getVersionId()).isNotNull();
                    assertThat(v.isOpen()).isTrue();
                    assertThat(same(history, v, fresh.get(v.getId()))).as(history.code() + " " + v.getId()).isTrue();
                }
                assertThat(versionStore.validAt(history.versionType(), ids, T0.plusSeconds(1)))
                        .as(history.code()).extracting(VersionRow::getVersionId)
                        .containsExactlyElementsOf(open.stream().map(VersionRow::getVersionId).toList());
                // The window is half open: a version is valid from its first instant, and no longer at its last.
                assertThat(versionStore.validAt(history.versionType(), ids, T0)).hasSameSizeAs(open);
                if (history.liveType() != Customer.class) {
                    assertThat(versionStore.validAt(history.versionType(), ids, T0.minusNanos(1000))).isEmpty();
                }
            }

            assertThat(versionStore.validAt(CustomerVersion.class, List.of(acme.getId()), T0.minusNanos(1000)))
                    .extracting(CustomerVersion::getName).containsExactly("Acme (old name)");
            assertThat(versionStore.validAt(CustomerVersion.class, List.of(acme.getId()), before.minusNanos(1000)))
                    .isEmpty();
            assertThat(versionStore.open(CustomerVersion.class, List.of(acme.getId())))
                    .extracting(CustomerVersion::getName).containsExactly("Acme Ltd");
            assertThat(versionStore.validAtBy(InvoiceVersion.class, "customerId", List.of(acme.getId()), T0))
                    .extracting(VersionRow::getId).containsExactly(invoice.getId());
            assertThat(versionStore.validAtBy(InvoiceVersion.class, "customerId", List.of(acme.getId() + 1), T0))
                    .isEmpty();
            assertThatThrownBy(() -> versionStore.validAtBy(InvoiceVersion.class, "customer", List.of(1L), T0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(versionStore.open(InvoiceVersion.class, List.of())).isEmpty();

            assertThat(LinkHistory.PROMISE_INVOICES.currentTargets(s, List.of(promiseId, -1L)))
                    .isEqualTo(Map.of(promiseId, Set.of(invoice.getId()), -1L, Set.of()));
            assertThat(LinkHistory.PROMISE_PAYMENTS.currentTargets(s, List.of(promiseId)))
                    .isEqualTo(Map.of(promiseId, Set.of(payment.getId())));
            assertThat(versionStore.openLinks(LinkHistory.PROMISE_INVOICE, List.of(promiseId)))
                    .extracting(EntityLinkVersion::getOwnerId, VersionRow::getId)
                    .containsExactly(tuple(promiseId, invoice.getId()));
            assertThat(versionStore.linksAt(LinkHistory.PROMISE_PAYMENT, List.of(promiseId), T0))
                    .extracting(VersionRow::getId).containsExactly(payment.getId());
            assertThat(versionStore.linksAt(LinkHistory.PROMISE_PAYMENT, List.of(promiseId), before)).isEmpty();
        });

        // A status the enum no longer has reads back as nothing, rather than failing the read.
        jdbc.update("update invoice_versions set status = 'MISLAID' where entity_id = ?", invoice.getId());
        List<InvoiceVersion> lost = tx.execute(status ->
                versionStore.open(InvoiceVersion.class, List.of(invoice.getId())));
        assertThat(lost).singleElement().satisfies(v -> {
            assertThat(v.getStatus()).isNull();
            assertThat(v.getTotal()).isEqualByComparingTo("225.50");
        });
        assertThat(jdbc.queryForObject("select status from invoices where id = ?", String.class, invoice.getId()))
                .isNotEqualTo("MISLAID").isEqualTo(InvoiceStatus.PARTIALLY_PAID.name());
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private String invoiceItemsJson() {
        return inRolledBackSession(s -> ((InvoiceVersion) snapshot(HistoryModel.of(Invoice.class),
                List.of(em.find(Invoice.class, invoice.getId())), s).get(invoice.getId())).getItemsJson());
    }

    private <T> T inRolledBackSession(Function<SharedSessionContractImplementor, T> body) {
        return tx.execute(status -> {
            status.setRollbackOnly();
            return body.apply(em.unwrap(SharedSessionContractImplementor.class));
        });
    }

    private void rolledBack(Consumer<SharedSessionContractImplementor> body) {
        inRolledBackSession(s -> {
            body.accept(s);
            return null;
        });
    }

    private List<?> all(Class<?> live) {
        return em.createQuery("select e from " + emf.getMetamodel().entity(live).getName() + " e order by e.id", live)
                .getResultList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<Long, VersionRow> snapshot(TypeHistory history, Collection<?> live,
                                                  SharedSessionContractImplementor s) {
        return history.snapshotAll(live, s);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean same(TypeHistory history, VersionRow a, VersionRow b) {
        return history.sameState(a, b);
    }

    private static VersionRow stamped(VersionRow v, Instant from, Instant to) {
        v.setValidFrom(from);
        v.setValidTo(to);
        v.setTxId("00000000-0000-0000-0000-000000000006");
        v.setOrigin("USER");
        return v;
    }

    private Long idOf(Object entity) {
        return (Long) emf.getPersistenceUnitUtil().getIdentifier(entity);
    }

    /**
     * Gives every empty column of a live record a value, in memory only (the transaction is rolled back). Each
     * field gets a value of its own, so a snapshot that copies the wrong column of the same type is caught too.
     */
    static void fillNulls(Object record) {
        for (Class<?> c = record.getClass(); c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getName().equals("version")) continue;
                f.setAccessible(true);
                try {
                    Object sample = sample(f.getType(), f.getName());
                    if (f.get(record) == null && sample != null) f.set(record, sample);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    /** A value of the type that no other field name is given (booleans and small enums excepted). */
    private static Object sample(Class<?> type, String fieldName) {
        int n = Math.floorMod(fieldName.hashCode(), 100_000);
        if (type == String.class) return "s-" + fieldName;
        if (type == Long.class) return 700_000L + n;
        if (type == BigDecimal.class) return new BigDecimal(n).movePointLeft(2).add(new BigDecimal("1.23"));
        if (type == Instant.class) return Instant.parse("2026-01-02T03:04:05.678901Z").plusSeconds(n);
        if (type == LocalDate.class) return LocalDate.parse("2026-01-02").plusDays(n);
        if (type == Boolean.class) return Boolean.TRUE;
        if (type == Integer.class) return 3 + n;
        if (type.isEnum()) return type.getEnumConstants()[n % type.getEnumConstants().length];
        return null;
    }

    private static Object sample(Class<?> type) {
        return sample(type, "changed");
    }

    /** A different value of the same type. */
    private static Object changed(Object value, Class<?> type) {
        if (value == null) return sample(type);
        if (value instanceof String text) return text + "!";
        if (value instanceof Long n) return n + 1;
        if (value instanceof Integer n) return n + 1;
        if (value instanceof BigDecimal n) return n.add(BigDecimal.ONE);
        if (value instanceof Instant t) return t.plusSeconds(1);
        if (value instanceof LocalDate d) return d.plusDays(1);
        if (value instanceof Boolean b) return !b;
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            return constants[(((Enum<?>) value).ordinal() + 1) % constants.length];
        }
        throw new IllegalArgumentException("No change for " + type);
    }

    private static Field field(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // declared higher up
            }
        }
        throw new IllegalArgumentException(type.getSimpleName() + " has no field " + name);
    }

    static Object read(Object target, String name) {
        try {
            return field(Hibernate.getClass(target), name).get(Hibernate.unproxy(target));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(Object target, String name, Object value) {
        try {
            field(target.getClass(), name).set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
