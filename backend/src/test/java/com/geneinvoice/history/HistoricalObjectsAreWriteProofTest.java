package com.geneinvoice.history;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.MovableClock;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.product.Product;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A record rebuilt as it was is an unmapped subclass of its entity (§5.7.15): whatever a caller does with it, no
 * save, merge or persist can ever write it over today's row, or add it as a new one.
 */
class HistoricalObjectsAreWriteProofTest extends IntegrationTestBase {

    @Autowired Records records;
    @Autowired InvoiceService invoiceService;
    @Autowired TransactionTemplate tx;
    @PersistenceContext EntityManager em;

    LocalDate yesterday;
    Customer acme;
    Product widget;
    Invoice invoice;

    @BeforeEach
    void yesterdayAndToday() {
        Instant now = Instant.parse("2026-05-12T09:00:00Z");
        MovableClock.SHARED.set(now.minus(Duration.ofDays(1)));
        yesterday = LocalDate.of(2026, 5, 11);
        actAs(userRepository.findByUsername("admin").orElseThrow());
        acme = customer("Acme Ltd");
        widget = product("Widget", "10.00");
        invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, "Old note", seller().getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 3, null))));

        MovableClock.SHARED.set(now);
        Customer c = customerRepository.findById(acme.getId()).orElseThrow();
        c.setName("Acme Today");
        customerRepository.save(c);
    }

    private <T> T asOfYesterday(Class<T> type, Long id) {
        return AsOf.with(AsOfPoint.endOfDay(yesterday), () -> records.find(type, id).orElseThrow());
    }

    @Test
    void aRebuiltInvoiceCannotBeSavedAndTodaysRowIsUntouched() {
        Invoice then = asOfYesterday(Invoice.class, invoice.getId());
        assertThat(then).isInstanceOf(Historical.class);
        assertThat(then.getCustomer()).isInstanceOf(Historical.class);
        assertThat(then.getItems()).allSatisfy(item -> assertThat(item).isInstanceOf(Historical.class));
        long invoices = invoiceRepository.count();

        then.setNotes("Written from the past");
        then.setTotal(new BigDecimal("1.00"));
        assertThatThrownBy(() -> invoiceRepository.save(then)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> invoiceRepository.saveAndFlush(then)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> em.merge(then))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> em.persist(then))).isInstanceOf(RuntimeException.class);

        assertThat(invoiceRepository.count()).as("nor was it added as a new invoice").isEqualTo(invoices);
        Invoice live = tx.execute(s -> {
            Invoice i = invoiceRepository.findById(invoice.getId()).orElseThrow();
            i.getItems().size();
            return i;
        });
        assertThat(live.getNotes()).isEqualTo("Old note");
        assertThat(live.getTotal()).isEqualByComparingTo("30.00");
        assertThat(live.getItems()).hasSize(1);
    }

    @Test
    void aRebuiltCustomerOrProductCannotBeSavedEither() {
        Customer then = asOfYesterday(Customer.class, acme.getId());
        assertThat(then.getName()).isEqualTo("Acme Ltd");
        then.setName("Back to the past");
        assertThatThrownBy(() -> customerRepository.save(then)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> customerRepository.saveAll(List.of(then))).isInstanceOf(RuntimeException.class);
        assertThat(customerRepository.findById(acme.getId()).orElseThrow().getName()).isEqualTo("Acme Today");

        long products = productRepository.count();
        Product product = asOfYesterday(Product.class, widget.getId());
        product.setPrice(new BigDecimal("99.00"));
        assertThatThrownBy(() -> productRepository.save(product)).isInstanceOf(RuntimeException.class);
        assertThat(productRepository.count()).isEqualTo(products);
        assertThat(productRepository.findById(widget.getId()).orElseThrow().getPrice()).isEqualByComparingTo("10.00");
    }

    @Test
    void aRebuiltCustomerCannotBeWrittenThroughARecordThatPointsAtIt() {
        actAs(userRepository.findByUsername("admin").orElseThrow());
        Customer then = asOfYesterday(Customer.class, acme.getId());
        long seats = customerPocRepository.count();
        assertThatThrownBy(() -> customerPocRepository.save(com.geneinvoice.poc.CustomerPoc.builder()
                .customer(then).user(userRepository.findByUsername("admin").orElseThrow())
                .pocType(com.geneinvoice.poc.PocType.COLLECTION).build()))
                .isInstanceOf(RuntimeException.class);
        assertThat(customerPocRepository.count()).isEqualTo(seats);
    }

    /** Someone who may sell in the default region: every invoice has a Sales POC. */
    private User seller() {
        return user("sid.sales", DataSeeder.ROLE_SALES_POC);
    }
}
