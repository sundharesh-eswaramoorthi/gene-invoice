package com.geneinvoice.history;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfException;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * While a point is bound, loading a live versioned record is refused with {@code AS_OF_UNSUPPORTED} (§5.7.16): a read
 * path never taught to answer as of a date fails, rather than passing today's record off as history. The few reads
 * that knowingly want today's record say so with {@link LiveReadGuard#allow}.
 */
class LiveReadGuardTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired TransactionTemplate tx;

    AsOfPoint yesterday;
    Customer acme;
    Invoice invoice;

    @BeforeEach
    void anInvoice() {
        actAs(userRepository.findByUsername("admin").orElseThrow());
        acme = customer("Acme Ltd");
        Product widget = product("Widget", "10.00");
        invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, seller().getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
        yesterday = AsOfPoint.endOfDay(Today.date().minusDays(1));
    }

    private static boolean refusedAsUnsupported(Throwable e) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = e; t != null && !chain.contains(t); t = t.getCause()) chain.add(t);
        return chain.stream().anyMatch(t -> t instanceof AsOfException a && AsOfException.UNSUPPORTED.equals(a.code));
    }

    @Test
    void aLiveVersionedRecordIsNotLoadedWhileAPointIsBound() {
        assertThatThrownBy(() -> AsOf.with(yesterday, () -> invoiceRepository.findById(invoice.getId())))
                .satisfies(e -> assertThat(refusedAsUnsupported(e)).as(e.toString()).isTrue());
        assertThatThrownBy(() -> AsOf.with(yesterday, () -> customerRepository.findAll()))
                .satisfies(e -> assertThat(refusedAsUnsupported(e)).isTrue());
        assertThatThrownBy(() -> AsOf.with(yesterday, () -> tx.execute(s ->
                invoiceRepository.findById(invoice.getId()).orElseThrow().getCustomer().getName())))
                .satisfies(e -> assertThat(refusedAsUnsupported(e)).isTrue());
    }

    @Test
    void allowReadsTodaysRecordKnowinglyAndOnlyInsideIt() {
        Invoice live = AsOf.with(yesterday, () -> LiveReadGuard.allow(() ->
                LiveReadGuard.allow(() -> invoiceRepository.findById(invoice.getId()).orElseThrow())));
        assertThat(live).isNotInstanceOf(Historical.class);
        assertThat(live.getId()).isEqualTo(invoice.getId());

        assertThatThrownBy(() -> AsOf.with(yesterday, () -> customerRepository.findById(acme.getId())))
                .as("allow ends with its body, nested or not")
                .satisfies(e -> assertThat(refusedAsUnsupported(e)).isTrue());
    }

    @Test
    void recordsWithoutHistoryAndLiveReadsAreNeverRefused() {
        assertThat(AsOf.with(yesterday, () -> userRepository.findByUsername("admin"))).isPresent();
        assertThat(AsOf.with(yesterday, () -> regionRepository.findAll())).isNotEmpty();
        assertThat(invoiceRepository.findById(invoice.getId())).isPresent();
        assertThat(customerRepository.findAll()).hasSize(1);
    }

    /** Someone who may sell in the default region: every invoice has a Sales POC. */
    private User seller() {
        return user("sid.sales", DataSeeder.ROLE_SALES_POC);
    }
}
