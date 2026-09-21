package com.geneinvoice.common;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class DateFilterBoundsTest extends IntegrationTestBase {

    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Autowired InvoiceService invoiceService;

    User admin;
    Customer acme;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        acme = customer("Acme Ltd");
        Product widget = product("Widget", "100.00");
        actAs(admin);
        Instant midnight = TODAY.atStartOfDay(ZoneOffset.UTC).toInstant();
        for (Instant at : List.of(midnight, midnight.minusNanos(1_000))) {
            invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), at, null,
                    admin.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
        }
    }

    private long count(String dateFilter) throws Exception {
        var response = mockMvc.perform(get("/api/invoices").with(as(admin))
                        .param("filter", "customerId:eq:" + acme.getId(), dateFilter))
                .andReturn().getResponse();
        String body = response.getContentAsString();
        assertThat(response.getStatus()).as(dateFilter + " → " + body).isEqualTo(200);
        return objectMapper.readTree(body).get("totalElements").asLong();
    }

    @Test
    void aBareDateCoversItsWholeDayAndNothingOfTheNext() throws Exception {
        String yesterday = TODAY.minusDays(1).toString();

        assertThat(count("invoiceDate:lte:" + yesterday)).as("lte yesterday").isEqualTo(1);
        assertThat(count("invoiceDate:between:" + yesterday + "," + yesterday)).as("between").isEqualTo(1);
        assertThat(count("invoiceDate:relative:yesterday")).as("relative yesterday").isEqualTo(1);
        assertThat(count("invoiceDate:relative:past")).as("relative past").isEqualTo(1);
        assertThat(count("invoiceDate:gte:" + TODAY)).as("gte today").isEqualTo(1);
    }
}
