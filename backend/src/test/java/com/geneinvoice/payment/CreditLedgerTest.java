package com.geneinvoice.payment;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CreditLedgerTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentAllocationRepository allocationRepository;

    User sales;
    User collector;
    Customer acme;
    Product widget;

    @BeforeEach
    void setUp() {
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        collector = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(userRepository.findByUsername("admin").orElseThrow());
    }

    private Invoice invoice(int quantity, String unitPrice) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null,
                sales.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), quantity,
                        unitPrice == null ? null : new BigDecimal(unitPrice)))));
    }

    private Payment pay(String amount, Long... invoiceIds) {
        return paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal(amount), "Cash", null,
                invoiceIds.length == 0 ? null : Arrays.asList(invoiceIds), collector.getId(), null));
    }

    private BigDecimal credit() {
        return customerRepository.findById(acme.getId()).orElseThrow().getCreditBalance();
    }

    private Invoice reload(Invoice i) {
        return invoiceRepository.findById(i.getId()).orElseThrow();
    }

    private void assertBooksBalance() {
        BigDecimal onInvoices = invoiceRepository.findByCustomerIdOrderByInvoiceDateDesc(acme.getId()).stream()
                .filter(i -> i.getStatus() != InvoiceStatus.CANCELLED)
                .map(Invoice::getPaidAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal collected = paymentRepository.findByCustomerIdOrderByPaidAtDesc(acme.getId()).stream()
                .filter(p -> p.getStatus() == PaymentStatus.ACTIVE)
                .map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(onInvoices.add(credit())).isEqualByComparingTo(collected);
    }

    @Test
    void voidingTakesBackTheCreditACancelledInvoiceRefunded() {
        Invoice inv = invoice(1, null);
        Payment p = pay("100.00", inv.getId());

        invoiceService.cancelWithRefund(inv.getId());
        assertThat(credit()).isEqualByComparingTo("100.00");
        assertBooksBalance();

        paymentService.voidPayment(p.getId());
        assertThat(credit()).isEqualByComparingTo("0");
        assertBooksBalance();
    }

    @Test
    void voidingTakesBackCreditThatALaterInvoiceSpent() {
        Invoice big = invoice(3, null);
        Payment p = pay("300.00", big.getId());

        invoiceService.replaceItems(big.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("50.00"))), null);
        assertThat(credit()).isEqualByComparingTo("250.00");

        Invoice later = invoice(2, null);
        assertThat(reload(later).getPaidAmount()).isEqualByComparingTo("200.00");
        assertThat(credit()).isEqualByComparingTo("50.00");
        assertBooksBalance();

        paymentService.voidPayment(p.getId());
        assertThat(reload(big).getPaidAmount()).isEqualByComparingTo("0");
        assertThat(reload(later).getPaidAmount()).isEqualByComparingTo("0");
        assertThat(reload(later).getStatus()).isEqualTo(InvoiceStatus.UNPAID);
        assertThat(credit()).isEqualByComparingTo("0");
        assertBooksBalance();
    }

    @Test
    void voidingAnOverpaymentUnpaysTheInvoiceItsCreditPaid() {
        Invoice first = invoice(1, "23.00");
        Payment p = pay("146.00");
        assertThat(credit()).isEqualByComparingTo("123.00");

        Invoice later = invoice(1, "123.00");
        assertThat(reload(later).getStatus()).isEqualTo(InvoiceStatus.FULLY_PAID);
        assertThat(credit()).isEqualByComparingTo("0");
        List<PaymentAllocation> fromCredit = allocationRepository.findByInvoiceIdWithPayment(later.getId());
        assertThat(fromCredit).hasSize(1);
        assertThat(fromCredit.get(0).getPayment().getId()).isEqualTo(p.getId());
        assertThat(fromCredit.get(0).getAmount()).isEqualByComparingTo("123.00");
        assertThat(paymentRepository.findById(p.getId()).orElseThrow().getCreditApplied())
                .isEqualByComparingTo("0");

        paymentService.voidPayment(p.getId());
        assertThat(reload(first).getPaidAmount()).isEqualByComparingTo("0");
        assertThat(reload(later).getPaidAmount()).isEqualByComparingTo("0");
        assertThat(credit()).isEqualByComparingTo("0");
        assertBooksBalance();
    }

    @Test
    void aRefundComesOffTheNewestPaymentFirst() {
        Invoice inv = invoice(2, null);
        Payment older = pay("120.00", inv.getId());
        Payment newer = pay("80.00", inv.getId());

        invoiceService.replaceItems(inv.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("150.00"))), null);

        assertThat(paymentRepository.findById(newer.getId()).orElseThrow().getCreditApplied())
                .isEqualByComparingTo("50.00");
        assertThat(paymentRepository.findById(older.getId()).orElseThrow().getCreditApplied())
                .isEqualByComparingTo("0");

        paymentService.voidPayment(newer.getId());
        assertThat(reload(inv).getPaidAmount()).isEqualByComparingTo("120.00");
        assertThat(credit()).isEqualByComparingTo("0");
        assertBooksBalance();
    }
}
