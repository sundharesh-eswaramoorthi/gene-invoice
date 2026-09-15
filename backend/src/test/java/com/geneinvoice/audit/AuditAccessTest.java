package com.geneinvoice.audit;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A record's history is readable exactly where the record is: AUDIT_VIEW alone is not enough, the
 * caller needs the record's own view privilege, and a POC's book bounds it like the record.
 */
class AuditAccessTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;

    User admin;
    User sales;
    User otherSales;
    User collections;
    Product widget;
    Invoice theirs;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        otherSales = user("sid.sales", DataSeeder.ROLE_SALES_POC);
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        widget = product("Widget", "100.00");
        actAs(admin);
        theirs = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(customer("Globex Corp").getId(),
                null, null, otherSales.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
    }

    private String history(String type, Long id) {
        return "/api/audit?entityType=" + type + "&entityId=" + id;
    }

    @Test
    void historyNeedsTheRecordsOwnViewPrivilege() throws Exception {
        // Both roles hold AUDIT_VIEW; neither may list users, and a Collection POC may not list products.
        mockMvc.perform(get(history("USER", admin.getId())).with(as(sales))).andExpect(status().isForbidden());
        mockMvc.perform(get(history("PRODUCT", widget.getId())).with(as(collections))).andExpect(status().isForbidden());

        mockMvc.perform(get(history("USER", admin.getId())).with(as(admin))).andExpect(status().isOk());
        mockMvc.perform(get(history("PRODUCT", widget.getId())).with(as(sales))).andExpect(status().isOk());
    }

    @Test
    void historyOutsideTheBookIsNotFound() throws Exception {
        mockMvc.perform(get(history("INVOICE", theirs.getId())).with(as(sales))).andExpect(status().isNotFound());
        mockMvc.perform(get(history("INVOICE", theirs.getId())).with(as(otherSales))).andExpect(status().isOk());
    }
}
