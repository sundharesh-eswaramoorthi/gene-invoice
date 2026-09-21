package com.geneinvoice.poc;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StaffIdentityPrivacyTest extends IntegrationTestBase {

    @Autowired PaymentPromiseService promiseService;
    @Autowired InvoiceService invoiceService;
    @Autowired DisputeService disputeService;

    User admin;
    User collections;
    User customerLogin;
    Customer acme;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        customerLogin = customerUser("acme.login", acme.getId());
        actAs(admin);
    }

    @Test
    void aCustomerSeesNoStaffIdsOnItsPromises() throws Exception {
        PromiseDtos.PromiseDto p = promiseService.create(new PromiseDtos.CreatePromiseRequest(
                acme.getId(), new BigDecimal("50.00"), LocalDate.now().plusDays(5),
                collections.getId(), "n", null));
        promiseService.override(p.id(), PromiseStatus.KEPT, "paid in cash");

        mockMvc.perform(get("/api/promises/" + p.id()).with(as(customerLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdByUserId", nullValue()))
                .andExpect(jsonPath("$.overriddenByUserId", nullValue()))
                .andExpect(jsonPath("$.collectionPoc", nullValue()));
        mockMvc.perform(get("/api/promises").with(as(customerLogin)))
                .andExpect(jsonPath("$.content[0].createdByUserId", nullValue()))
                .andExpect(jsonPath("$.content[0].overriddenByUserId", nullValue()));
        mockMvc.perform(get("/api/promises/" + p.id()).with(as(admin)))
                .andExpect(jsonPath("$.createdByUserId").value(admin.getId()))
                .andExpect(jsonPath("$.overriddenByUserId").value(admin.getId()));
    }

    @Test
    void aCustomerSeesNoStaffIdOnAResolvedDispute() throws Exception {
        Invoice inv = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null,
                admin.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
        actAs(customerLogin);
        Dispute d = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.INVOICE, inv.getId(), "wrong amount", null));
        actAs(admin);
        disputeService.deny(d.getId(), new DisputeDtos.ResolveDisputeRequest("checked, it is right", null));

        mockMvc.perform(get("/api/disputes/" + d.getId()).with(as(customerLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedByUserId", nullValue()));
        mockMvc.perform(get("/api/disputes/" + d.getId()).with(as(admin)))
                .andExpect(jsonPath("$.resolvedByUserId").value(admin.getId()));
    }
}
