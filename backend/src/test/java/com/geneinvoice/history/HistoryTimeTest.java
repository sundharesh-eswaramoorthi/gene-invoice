package com.geneinvoice.history;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.MovableClock;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.history.versions.CustomerVersion;
import com.geneinvoice.history.versions.EntityLinkVersion;
import com.geneinvoice.history.versions.InvoiceVersion;
import com.geneinvoice.history.versions.PaymentAllocationVersion;
import com.geneinvoice.history.versions.PaymentVersion;
import com.geneinvoice.history.versions.PromiseVersion;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The instant a version starts is the instant of its transaction, bound once when history is written: one
 * instant for all of a transaction's versions, and never an interval that ends before it begins, whatever the
 * clock does. A save that changes nothing writes nothing.
 */
class HistoryTimeTest extends IntegrationTestBase {

    @Autowired CustomerService customerService;
    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired TransactionTemplate tx;

    User admin;
    User sam;
    User cara;
    Product widget;
    Customer acme;

    @BeforeEach
    void family() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        cara = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        actAs(admin);
        widget = product("Widget", "100");
        acme = customer("Acme Ltd");
    }

    @Test
    void everyVersionOfOneTransactionStartsAtTheSameInstant() {
        Invoice first = invoice();
        Invoice second = invoice();
        Instant before = MovableClock.SHARED.instant().truncatedTo(ChronoUnit.MICROS);

        Payment p = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("150.00"), "Cash", null, List.of(first.getId(), second.getId()), cara.getId(), null));

        Instant after = MovableClock.SHARED.instant();
        PaymentVersion payment = open(PaymentVersion.class, p.getId());
        List<VersionRow> written = new ArrayList<>();
        written.add(payment);
        written.add(open(InvoiceVersion.class, first.getId()));
        written.add(open(InvoiceVersion.class, second.getId()));
        written.addAll(historyTestSupport.allVersions(PaymentAllocationVersion.class));
        assertThat(written).hasSize(5).allSatisfy(v -> {
            assertThat(v.getValidFrom()).isEqualTo(payment.getValidFrom());
            assertThat(v.getTxId()).isEqualTo(payment.getTxId());
        });
        assertThat(payment.getValidFrom()).isBetween(before, after);
        assertThat(payment.getValidFrom()).isEqualTo(payment.getValidFrom().truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void aSecondTransactionInTheSameMicrosecondStartsOneMicrosecondLater() {
        Invoice inv = invoice();
        Instant t = later().plusNanos(123_456_000);
        MovableClock.SHARED.set(t);

        rename("Acme One");
        rename("Acme Two");
        invoiceService.update(inv.getId(), new InvoiceDtos.UpdateInvoiceRequest("first", null));
        invoiceService.update(inv.getId(), new InvoiceDtos.UpdateInvoiceRequest("second", null));

        List<CustomerVersion> names = historyTestSupport.versionsOf(CustomerVersion.class, acme.getId());
        assertThat(names).extracting(CustomerVersion::getName).containsExactly("Acme Ltd", "Acme One", "Acme Two");
        assertThat(names.get(1).getValidFrom()).isEqualTo(t);
        assertThat(names.get(1).getValidTo()).isEqualTo(t.plus(1, ChronoUnit.MICROS));
        assertThat(names.get(2).getValidFrom()).isEqualTo(t.plus(1, ChronoUnit.MICROS));
        List<InvoiceVersion> notes = historyTestSupport.versionsOf(InvoiceVersion.class, inv.getId());
        assertThat(notes).extracting(InvoiceVersion::getNotes).containsExactly(null, "first", "second");
        assertThat(notes.get(2).getValidFrom()).isEqualTo(t.plus(1, ChronoUnit.MICROS));
        contiguous(names);
        contiguous(notes);
    }

    @Test
    void aClockThatGoesBackNeverEndsAVersionBeforeItBegan() {
        Invoice first = invoice();
        Invoice second = invoice();
        Instant t = later();
        MovableClock.SHARED.set(t);
        Long promise = promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(),
                new BigDecimal("50.00"), Today.date().plusDays(5), cara.getId(), null, List.of(first.getId()))).id();
        rename("Acme One");

        MovableClock.SHARED.set(t.minus(Duration.ofHours(3)));
        rename("Acme Two");
        promiseService.update(promise, new PromiseDtos.UpdatePromiseRequest(new BigDecimal("60.00"),
                Today.date().plusDays(5), cara.getId(), null, List.of(second.getId())));

        List<CustomerVersion> names = historyTestSupport.versionsOf(CustomerVersion.class, acme.getId());
        assertThat(names).extracting(CustomerVersion::getName).containsExactly("Acme Ltd", "Acme One", "Acme Two");
        assertThat(names.get(2).getValidFrom()).isEqualTo(t.plus(1, ChronoUnit.MICROS));
        contiguous(names);
        contiguous(historyTestSupport.versionsOf(PromiseVersion.class, promise));
        List<EntityLinkVersion> links = historyTestSupport.linksOf(LinkHistory.PROMISE_INVOICE, promise);
        assertThat(links).extracting(VersionRow::getId).containsExactly(first.getId(), second.getId());
        assertThat(links.get(0).getValidTo()).isEqualTo(t.plus(1, ChronoUnit.MICROS)).isAfter(links.get(0).getValidFrom());
        assertThat(links.get(1).getValidFrom()).isEqualTo(links.get(0).getValidTo());
        List<VersionRow> everything = new ArrayList<>();
        for (TypeHistory<?, ?> type : HistoryModel.types()) everything.addAll(historyTestSupport.allVersions(type.versionType()));
        everything.addAll(historyTestSupport.allVersions(EntityLinkVersion.class));
        assertThat(everything).isNotEmpty().allSatisfy(v -> assertThat(v.getValidTo()).isAfter(v.getValidFrom()));
    }

    @Test
    void aSaveThatChangesNothingWritesNothing() {
        Invoice inv = invoice();
        Long promise = promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(),
                new BigDecimal("50.00"), Today.date().plusDays(5), cara.getId(), null, List.of(inv.getId()))).id();
        long before = versionCount();

        customerService.update(acme.getId(), new CustomerDtos.CustomerUpdateRequest("Acme Ltd", null, null, null,
                null, null));
        invoiceService.update(inv.getId(), new InvoiceDtos.UpdateInvoiceRequest(null, sam.getId()));
        tx.executeWithoutResult(status -> {
            Customer c = customerRepository.findById(acme.getId()).orElseThrow();
            customerRepository.saveAndFlush(c);
            // A promise's own bookkeeping moves, but nothing a version is compared on.
            PaymentPromise p = promiseRepository.findById(promise).orElseThrow();
            p.setBrokenNotifiedAt(MovableClock.SHARED.instant());
            promiseRepository.saveAndFlush(p);
        });

        assertThat(promiseRepository.findById(promise).orElseThrow().getBrokenNotifiedAt()).isNotNull();
        assertThat(versionCount()).isEqualTo(before);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    /** A whole second a day ahead of everything the fixtures wrote. */
    private static Instant later() {
        return MovableClock.SHARED.instant().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
    }

    private Invoice invoice() {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, sam.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
    }

    private void rename(String name) {
        customerService.update(acme.getId(), new CustomerDtos.CustomerUpdateRequest(name, null, null, null, null,
                null));
    }

    private <V extends VersionRow> V open(Class<V> type, Long id) {
        List<V> open = historyTestSupport.versionsOf(type, id).stream().filter(VersionRow::isOpen).toList();
        assertThat(open).hasSize(1);
        return open.get(0);
    }

    private long versionCount() {
        long n = historyTestSupport.allVersions(EntityLinkVersion.class).size();
        for (TypeHistory<?, ?> type : HistoryModel.types()) n += historyTestSupport.allVersions(type.versionType()).size();
        return n;
    }

    private static void contiguous(List<? extends VersionRow> versions) {
        for (int i = 0; i < versions.size(); i++) {
            VersionRow v = versions.get(i);
            assertThat(v.getValidTo()).isAfter(v.getValidFrom());
            if (i > 0) assertThat(versions.get(i - 1).getValidTo()).isEqualTo(v.getValidFrom());
        }
        assertThat(versions.get(versions.size() - 1).isOpen()).isTrue();
    }
}
