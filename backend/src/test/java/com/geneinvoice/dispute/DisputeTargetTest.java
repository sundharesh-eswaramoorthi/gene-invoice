package com.geneinvoice.dispute;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** A dispute names its target as a number and an amount, for the app to format (D-39). */
class DisputeTargetTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired DisputeService disputeService;

    @Test
    void aDisputeCarriesItsTargetsNumberAndAmount() throws Exception {
        User admin = userRepository.findByUsername("admin").orElseThrow();
        User collector = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        Customer acme = customer("Acme Ltd");
        Product widget = product("Widget", "451234.50");
        User login = customerUser("acme.login", acme.getId());
        actAs(admin);
        Invoice inv = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null,
                admin.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
        Payment pay = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("1000.00"), "Cash", null, List.of(inv.getId()), collector.getId(), null));

        actAs(login);
        Dispute onInvoice = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.INVOICE, inv.getId(), "wrong total", null));
        Dispute onPayment = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.PAYMENT, pay.getId(), "paid twice", null));

        mockMvc.perform(get("/api/disputes/" + onInvoice.getId()).with(as(admin)))
                .andExpect(jsonPath("$.targetNumber").value(inv.getInvoiceNumber()))
                .andExpect(jsonPath("$.targetAmount").value(451234.50))
                .andExpect(jsonPath("$.targetSummary").value(inv.getInvoiceNumber() + " — 451234.50"));
        mockMvc.perform(get("/api/disputes/" + onPayment.getId()).with(as(admin)))
                .andExpect(jsonPath("$.targetNumber").value("#" + pay.getId()))
                .andExpect(jsonPath("$.targetAmount").value(1000.00));
    }
}
