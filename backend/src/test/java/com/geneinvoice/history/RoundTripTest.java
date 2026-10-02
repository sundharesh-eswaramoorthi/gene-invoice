package com.geneinvoice.history;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.MovableClock;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.history.types.HistoricalProduct;
import com.geneinvoice.history.versions.PaymentAllocationVersion;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceItem;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.payment.PaymentAllocation;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.task.TaskDtos;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.task.TaskService;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.PluralAttribute;
import org.hibernate.Hibernate;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rebuilding is the inverse of the snapshot: every record of every versioned type, with every column filled, goes
 * through {@code snapshotAll} and back through {@code rehydrate} and comes out equal attribute by attribute, its
 * lines, allocations and links included, each record it points at rebuilt as it was at the same point (§5.7.15).
 */
class RoundTripTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired DisputeService disputeService;
    @Autowired PocService pocService;
    @Autowired TaskService taskService;
    @Autowired VersionStore versionStore;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManagerFactory emf;
    @PersistenceContext EntityManager em;

    Product widget;
    Invoice invoice;

    /**
     * One of everything: two products, an invoice of both, a promise on it, a payment on both, a seat, a dispute, and
     * a task on the invoice.
     */
    @BeforeEach
    void family() {
        User admin = userRepository.findByUsername("admin").orElseThrow();
        User sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        User cara = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        actAs(admin);
        widget = product("Widget", "100");
        Product gadget = product("Gadget", "25.50");
        Customer acme = customerRepository.save(Customer.builder().name("Acme Ltd").phone("555-0100")
                .email("ap@acme.test").address("1 Main St").paymentTerm(PaymentTerm.NET_15).region(defaultRegion())
                .build());
        invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, "First order",
                sam.getId(), List.of(new InvoiceDtos.LineInput(gadget.getId(), 1, null),
                new InvoiceDtos.LineInput(widget.getId(), 2, null))));
        Long promiseId = promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(),
                new BigDecimal("60.00"), Today.date().plusDays(3), cara.getId(), "Friday",
                List.of(invoice.getId()))).id();
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(), new BigDecimal("40.00"),
                "Cash", "Part", List.of(invoice.getId()), cara.getId(), List.of(promiseId)));
        pocService.add(acme.getId(), PocType.COLLECTION, cara.getId(), true);
        actAs(customerUser("acme.login", acme.getId()));
        disputeService.open(new DisputeDtos.CreateDisputeRequest(DisputeTargetType.INVOICE, invoice.getId(),
                "Wrong quantity", null));
        actAs(admin);
        taskService.create(new TaskDtos.CreateTaskRequest(TaskEntityType.INVOICE, invoice.getId(), "Chase it",
                "Call the buyer", Today.date().plusDays(2), List.of()));
    }

    @Test
    void everyRecordOfEveryTypeComesBackFromItsSnapshotEqual() {
        AsOfPoint now = AsOfPoint.at(Instant.now());
        for (TypeHistory<?, ?> history : HistoryModel.types()) {
            rolledBack(s -> {
                List<?> live = all(history.liveType());
                assertThat(live).as(history.code()).isNotEmpty();
                // Every column holds a value, so a column the rebuild forgot shows up as a difference.
                live.forEach(SnapshotTest::fillNulls);
                List<VersionRow> versions = new ArrayList<>(snapshot(history, live, s).values());
                List<?> rebuilt = rehydrate(history, versions, new Rehydration(now, versionStore, em, userRepository));

                assertThat(rebuilt).as(history.code()).hasSameSizeAs(live);
                for (int i = 0; i < live.size(); i++) {
                    assertSameRecord(history.code() + "#" + idOf(live.get(i)), live.get(i), rebuilt.get(i),
                            null, null, 0);
                }
            });
        }
    }

    /** A line names its product as the product was at the point, from the product's own versions. */
    @Test
    void aLineNamesItsProductAsItWasAtThePoint() {
        AsOfPoint beforeRename = AsOfPoint.at(Instant.now());
        MovableClock.SHARED.set(Instant.now().plusSeconds(60));
        tx.executeWithoutResult(status -> em.find(Product.class, widget.getId()).setName("Widget Pro"));

        rolledBack(s -> {
            Invoice live = em.find(Invoice.class, invoice.getId());
            TypeHistory<Invoice, ?> history = HistoryModel.of(Invoice.class);
            List<VersionRow> versions = new ArrayList<>(snapshot(history, List.of(live), s).values());
            for (AsOfPoint point : List.of(beforeRename, AsOfPoint.at(MovableClock.SHARED.instant()))) {
                Invoice rebuilt = (Invoice) rehydrate(history, versions,
                        new Rehydration(point, versionStore, em, userRepository)).get(0);
                InvoiceItem line = rebuilt.getItems().stream()
                        .filter(item -> item.getProduct().getId().equals(widget.getId())).findFirst().orElseThrow();
                assertThat(line.getProduct()).isInstanceOf(HistoricalProduct.class);
                assertThat(line.getProduct().getName())
                        .isEqualTo(point == beforeRename ? "Widget" : "Widget Pro");
            }
        });
    }

    /**
     * An allocation is rebuilt with its payment, so the row it is asked for must be the one valid at the point; a row
     * that says otherwise is refused, not answered with the payment's.
     */
    @Test
    void anAllocationRowThatIsNotTheOneAtThePointIsRefused() {
        AsOfPoint now = AsOfPoint.at(Instant.now());
        rolledBack(s -> {
            TypeHistory<PaymentAllocation, ?> history = HistoryModel.of(PaymentAllocation.class);
            List<VersionRow> versions = new ArrayList<>(snapshot(history, all(PaymentAllocation.class), s).values());
            assertThat(rehydrate(history, versions, new Rehydration(now, versionStore, em, userRepository)))
                    .hasSameSizeAs(versions);

            PaymentAllocationVersion changed = (PaymentAllocationVersion) versions.get(0);
            changed.setAmount(changed.getAmount().add(BigDecimal.ONE));
            assertThatThrownBy(() -> rehydrate(history, versions,
                    new Rehydration(now, versionStore, em, userRepository)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Allocation " + changed.getId());
        });
    }

    /**
     * Every attribute of {@code rebuilt} equals {@code live}'s: values by value (amounts by amount), the row version
     * left out, records pointed at by id and (two levels down) by their own attributes, collections element by
     * element (one level down), and a child's pointer back to its parent is the rebuilt parent itself.
     */
    private void assertSameRecord(String where, Object live, Object rebuilt, Object liveParent, Object rebuiltParent,
                                  int depth) {
        Class<?> type = Hibernate.getClass(live);
        assertThat(rebuilt).as(where).isNotNull().isInstanceOf(Historical.class).isInstanceOf(type);
        EntityType<?> entity = emf.getMetamodel().entity(type);
        for (Attribute<?, ?> attribute : entity.getAttributes()) {
            String name = attribute.getName();
            String at = where + "." + name;
            Object liveValue = SnapshotTest.read(live, name);
            Object rebuiltValue = SnapshotTest.read(rebuilt, name);
            if (name.equals("version")) {
                assertThat(rebuiltValue).as(at + ": a rebuilt record has no row version").isNull();
            } else if (attribute instanceof PluralAttribute<?, ?, ?>) {
                List<?> liveElements = byId((Collection<?>) liveValue);
                List<?> rebuiltElements = byId((Collection<?>) rebuiltValue);
                assertThat(rebuiltElements.stream().map(this::idOf).toList()).as(at)
                        .isNotEmpty().isEqualTo(liveElements.stream().map(this::idOf).toList());
                if (depth > 0) continue;
                for (int i = 0; i < liveElements.size(); i++) {
                    assertSameRecord(at + "[" + idOf(liveElements.get(i)) + "]", Hibernate.unproxy(liveElements.get(i)),
                            rebuiltElements.get(i), live, rebuilt, depth + 1);
                }
            } else if (attribute.isAssociation()) {
                if (liveValue == null) {
                    assertThat(rebuiltValue).as(at).isNull();
                } else if (liveParent != null && Hibernate.unproxy(liveValue) == liveParent) {
                    assertThat(rebuiltValue).as(at + ": the rebuilt parent itself").isSameAs(rebuiltParent);
                } else {
                    assertThat(idOf(rebuiltValue)).as(at).isNotNull().isEqualTo(idOf(liveValue));
                    Class<?> target = Hibernate.getClass(liveValue);
                    if (HistoryModel.isVersioned(target) && depth < 2) {
                        assertSameRecord(at, Hibernate.unproxy(liveValue), rebuiltValue, null, null, depth + 1);
                    }
                }
            } else if (liveValue instanceof BigDecimal amount) {
                assertThat((BigDecimal) rebuiltValue).as(at).isEqualByComparingTo(amount);
            } else {
                assertThat(rebuiltValue).as(at).isEqualTo(liveValue);
            }
        }
    }

    private List<?> byId(Collection<?> elements) {
        List<Object> sorted = new ArrayList<>(elements);
        sorted.sort(Comparator.comparing(this::idOf));
        return sorted;
    }

    private void rolledBack(java.util.function.Consumer<SharedSessionContractImplementor> body) {
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            // The filled-in records stay in memory: flushing them would run their update callbacks and change them.
            em.setFlushMode(FlushModeType.COMMIT);
            body.accept(em.unwrap(SharedSessionContractImplementor.class));
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
    private static List<?> rehydrate(TypeHistory history, List<VersionRow> versions, Rehydration ctx) {
        return history.rehydrate(versions, ctx);
    }

    private Long idOf(Object entity) {
        if (entity instanceof Historical) return (Long) SnapshotTest.read(entity, "id");
        return (Long) emf.getPersistenceUnitUtil().getIdentifier(entity);
    }
}
