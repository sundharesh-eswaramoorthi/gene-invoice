package com.geneinvoice.poc;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PocColumnPrivacyTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;

    User admin;
    User sales;
    User customerLogin;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        Customer acme = customer("Acme Ltd");
        Product widget = product("Widget", "100.00");
        customerLogin = customerUser("acme.login", acme.getId());
        actAs(admin);
        invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, "n",
                sales.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
    }

    @Test
    void aCustomerCannotFilterOrSortOnPocColumns() throws Exception {
        List<String> probes = List.of(
                "/api/invoices?filter=salesPocUserId:eq:" + sales.getId(),
                "/api/invoices?filter=salesPocName:contains:SAM",
                "/api/invoices?sort=salesPocName,asc",
                "/api/invoices/summary?filter=salesPocUserId:eq:" + sales.getId(),
                "/api/payments?filter=collectionPocUserId:eq:" + sales.getId(),
                "/api/payments?sort=collectionPocName,asc",
                "/api/customers?filter=successPocUserId:eq:" + sales.getId(),
                "/api/customers?filter=collectionPocUserId:isEmpty:",
                "/api/promises?filter=collectionPocName:contains:SAM");
        for (String probe : probes) {
            mockMvc.perform(get(probe).with(as(customerLogin)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Unknown column")));
        }
        mockMvc.perform(get("/api/invoices").with(as(customerLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void theCustomersPublishedSchemaMatchesWhatItMayFilterOn() throws Exception {
        mockMvc.perform(get("/api/table-schemas/invoices").with(as(customerLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[*].name", not(hasItem("salesPocUserId"))))
                .andExpect(jsonPath("$.columns[*].name", not(hasItem("salesPocName"))));
    }

    @Test
    void staffCanStillFilterOnPocColumns() throws Exception {
        mockMvc.perform(get("/api/invoices?filter=salesPocUserId:eq:" + sales.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(get("/api/table-schemas/invoices").with(as(admin)))
                .andExpect(jsonPath("$.columns[*].name", hasItem("salesPocUserId")));
    }
}
