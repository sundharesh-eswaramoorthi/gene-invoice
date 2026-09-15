package com.geneinvoice.poc;

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
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A Sales POC without SCOPE_OVERRIDE works their own book (AC-A6). The book bounds every read and
 * write by id, not just the list: another rep's record is not found, exactly as in the list.
 */
class BookScopeByIdTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;

    User admin;
    User sales;
    Customer acme;
    Customer globex;
    Invoice mine;
    Invoice theirs;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        User otherSales = user("sid.sales", DataSeeder.ROLE_SALES_POC);
        acme = customer("Acme Ltd");
        globex = customer("Globex Corp");
        Product widget = product("Widget", "100.00");
        actAs(admin);
        mine = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null,
                sales.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
        theirs = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(globex.getId(), null, null,
                otherSales.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
    }

    @Test
    void anotherRepsInvoiceCannotBeReadEditedOrCancelledById() throws Exception {
        mockMvc.perform(get("/api/invoices/" + theirs.getId()).with(as(sales)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/invoices/" + theirs.getId()).with(as(sales))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("notes", "mine now"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/invoices/" + theirs.getId() + "/cancel").with(as(sales)))
                .andExpect(status().isNotFound());

        Invoice untouched = invoiceRepository.findById(theirs.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(InvoiceStatus.UNPAID);
        assertThat(untouched.getNotes()).isNull();

        mockMvc.perform(get("/api/invoices/" + mine.getId()).with(as(sales))).andExpect(status().isOk());
        mockMvc.perform(get("/api/invoices/" + theirs.getId()).with(as(admin))).andExpect(status().isOk());
    }

    @Test
    void aCustomerOutsideTheBookCannotBeReachedOrReseated() throws Exception {
        mockMvc.perform(get("/api/customers/" + globex.getId()).with(as(sales)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/customers/" + acme.getId()).with(as(sales)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/customers/" + globex.getId() + "/pocs").with(as(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("pocType", "SUCCESS", "userId", sales.getId(), "primary", true))))
                .andExpect(status().isNotFound());
        assertThat(customerPocRepository.findAll()).isEmpty();
    }
}
