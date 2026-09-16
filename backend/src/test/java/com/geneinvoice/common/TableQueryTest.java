package com.geneinvoice.common;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Feature D and E: server-side paging, sorting, filtering, scoping and filter-aware tiles. */
class TableQueryTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PocService pocService;

    User admin;
    User sales;
    User otherSales;
    User collections;
    Customer acme;
    Customer globex;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        otherSales = user("sid.sales", DataSeeder.ROLE_SALES_POC);
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        globex = customer("Globex Corp");
        widget = product("Widget", "10.00");
        actAs(admin);
    }

    private Invoice invoice(Customer c, String unitPrice, int qty, User poc) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                c.getId(), null, null, poc.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), qty, new BigDecimal(unitPrice)))));
    }

    private JsonNode getJson(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ---- AC-D1 / AC-D2: paging never over-fetches and is stably ordered ---------

    // ---- D-40: paging and sort validation ---------------------------------------

    @Test
    void aSortDirectionThatIsNeitherAscNorDescIsRefused() throws Exception {
        invoice(acme, "10.00", 1, sales);

        mockMvc.perform(get("/api/invoices").with(as(admin)).param("sort", "invoiceDate,sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("asc or desc")));
        mockMvc.perform(get("/api/invoices").with(as(admin)).param("sort", "invoiceDate,DESC"))
                .andExpect(status().isOk());
    }

    @Test
    void aPageFarPastTheEndIsEmptyRatherThanAServerError() throws Exception {
        invoice(acme, "10.00", 1, sales);

        // page * size overflows int arithmetic here.
        JsonNode page = getJson(get("/api/invoices").with(as(admin))
                .param("page", String.valueOf(Integer.MAX_VALUE)).param("size", "20"));

        assertThat(page.get("content").size()).isZero();
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void aPageNeverReturnsMoreRowsThanThePageSize() throws Exception {
        for (int i = 0; i < 25; i++) invoice(acme, "10.00", 1, sales);

        JsonNode page = getJson(get("/api/invoices").with(as(admin)).param("size", "10"));

        assertThat(page.get("content").size()).isEqualTo(10);
        assertThat(page.get("totalElements").asLong()).isEqualTo(25);
        assertThat(page.get("totalPages").asInt()).isEqualTo(3);
        assertThat(page.get("size").asInt()).isEqualTo(10);
    }

    @Test
    void pagingIsStableSoNoRowIsSkippedOrRepeated() throws Exception {
        // All 25 share an invoice date, so only the id tiebreak keeps the order deterministic.
        for (int i = 0; i < 25; i++) invoice(acme, "10.00", 1, sales);

        Set<Integer> seen = new HashSet<>();
        List<Integer> all = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            JsonNode page = getJson(get("/api/invoices").with(as(admin))
                    .param("size", "10").param("page", String.valueOf(p)));
            for (JsonNode row : page.get("content")) {
                all.add(row.get("id").asInt());
                seen.add(row.get("id").asInt());
            }
        }
        assertThat(all).hasSize(25);
        assertThat(seen).hasSize(25);
    }

    @Test
    void theDefaultPageSizeIsTwenty() throws Exception {
        JsonNode page = getJson(get("/api/invoices").with(as(admin)));
        assertThat(page.get("size").asInt()).isEqualTo(20);
    }

    // ---- AC-D3: sorting is server-side and only on declared columns -------------

    @Test
    void sortingIsAppliedServerSide() throws Exception {
        invoice(acme, "30.00", 1, sales);
        invoice(acme, "10.00", 1, sales);
        invoice(acme, "20.00", 1, sales);

        JsonNode asc = getJson(get("/api/invoices").with(as(admin)).param("sort", "total,asc"));
        JsonNode desc = getJson(get("/api/invoices").with(as(admin)).param("sort", "total,desc"));

        assertThat(asc.get("content").get(0).get("total").asDouble()).isEqualTo(10.0);
        assertThat(desc.get("content").get(0).get("total").asDouble()).isEqualTo(30.0);
    }

    @Test
    void sortingByAJoinedColumnWorks() throws Exception {
        invoice(globex, "10.00", 1, sales);
        invoice(acme, "10.00", 1, sales);

        JsonNode asc = getJson(get("/api/invoices").with(as(admin)).param("sort", "customerName,asc"));
        assertThat(asc.get("content").get(0).get("customerName").asText()).isEqualTo("Acme Ltd");
    }

    @Test
    void aColumnDeclaredUnsortableIsRejected() throws Exception {
        mockMvc.perform(get("/api/invoices").with(as(admin)).param("sort", "notes,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not sortable")));
    }

    @Test
    void theSchemaListsColumnsInTheOrderTheyAreDeclared() throws Exception {
        // The filter picker is built straight from this list, so the order must be the declared
        // one rather than whatever a hash map happens to produce.
        JsonNode schema = getJson(get("/api/table-schemas/invoices").with(as(admin)));
        List<String> names = new ArrayList<>();
        for (JsonNode c : schema.get("columns")) {
            names.add(c.get("name").asText());
        }
        assertThat(names).startsWith("id", "invoiceNumber", "customerId", "customerName",
                "invoiceDate", "total", "paidAmount", "balance", "status");
    }

    @Test
    void theSchemaEndpointDocumentsEverySortableColumn() throws Exception {
        JsonNode schema = getJson(get("/api/table-schemas/invoices").with(as(admin)));
        List<String> sortable = new ArrayList<>();
        for (JsonNode c : schema.get("columns")) {
            if (c.get("sortable").asBoolean()) sortable.add(c.get("name").asText());
        }
        assertThat(sortable).contains("invoiceNumber", "invoiceDate", "total", "paidAmount",
                "balance", "status", "customerName");
        assertThat(schema.get("pageSizes").toString()).isEqualTo("[10,20,50]");
    }

    // ---- AC-D9: every filter input is validated server-side ---------------------

    @Test
    void anUnknownColumnIsRejectedCleanly() throws Exception {
        mockMvc.perform(get("/api/invoices").with(as(admin)).param("filter", "nonsense:eq:1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Unknown column")));
    }

    @Test
    void anUnknownOperatorIsRejectedCleanly() throws Exception {
        mockMvc.perform(get("/api/invoices").with(as(admin)).param("filter", "total:sideways:1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("Unknown filter operator")));
    }

    @Test
    void anOperatorThatDoesNotSuitTheColumnTypeIsRejected() throws Exception {
        mockMvc.perform(get("/api/invoices").with(as(admin)).param("filter", "total:contains:1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not valid for column")));
    }

    @Test
    void anOversizedPageSizeIsRejected() throws Exception {
        mockMvc.perform(get("/api/invoices").with(as(admin)).param("size", "5000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("size must be one of")));
    }

    @Test
    void aNonsenseFilterValueIsRejectedRatherThanReachingTheDatabase() throws Exception {
        mockMvc.perform(get("/api/invoices").with(as(admin)).param("filter", "total:gt:abc"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/invoices").with(as(admin)).param("filter", "status:eq:NOPE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aFilterValueIsBoundNotInterpolatedSoSqlMetacharactersAreLiteral() throws Exception {
        Customer odd = customerRepository.save(Customer.builder().name("100% Ltd").build());
        invoice(odd, "10.00", 1, sales);
        invoice(acme, "10.00", 1, sales);

        JsonNode wildcard = getJson(get("/api/invoices").with(as(admin))
                .param("filter", "customerName:contains:%"));
        assertThat(wildcard.get("totalElements").asLong()).isEqualTo(1);

        JsonNode injection = getJson(get("/api/invoices").with(as(admin))
                .param("filter", "customerName:contains:' OR 1=1 --"));
        assertThat(injection.get("totalElements").asLong()).isZero();
    }

    // ---- filtering behaviour ----------------------------------------------------

    @Test
    void moneyRangeEnumSetAndTextFiltersCombineWithAnd() throws Exception {
        invoice(acme, "10.00", 1, sales);
        invoice(acme, "50.00", 1, sales);
        invoice(globex, "50.00", 1, sales);

        JsonNode result = getJson(get("/api/invoices").with(as(admin))
                .param("filter", "total:between:20,80")
                .param("filter", "status:in:UNPAID,PARTIALLY_PAID")
                .param("filter", "customerName:contains:acme"));

        assertThat(result.get("totalElements").asLong()).isEqualTo(1);
        assertThat(result.get("content").get(0).get("customerName").asText()).isEqualTo("Acme Ltd");
    }

    @Test
    void aMultiValueOperatorSurvivesArrivingAsOneParameter() throws Exception {
        invoice(acme, "10.00", 1, sales);
        invoice(acme, "50.00", 1, sales);
        invoice(acme, "900.00", 1, sales);

        // One `filter` parameter carrying a comma: the values belong to the operator, and must
        // not be split into two separate chips.
        JsonNode between = getJson(get("/api/invoices").with(as(admin))
                .param("filter", "total:between:20,100"));
        assertThat(between.get("totalElements").asLong()).isEqualTo(1);

        JsonNode anyOf = getJson(get("/api/invoices").with(as(admin))
                .param("filter", "status:in:UNPAID,CANCELLED"));
        assertThat(anyOf.get("totalElements").asLong()).isEqualTo(3);
    }

    @Test
    void aTextFilterMayItselfContainCommasAndColons() throws Exception {
        Customer odd = customerRepository.save(Customer.builder().name("Smith, Jones & Co: Ltd").build());
        invoice(odd, "10.00", 1, sales);
        invoice(acme, "10.00", 1, sales);

        JsonNode result = getJson(get("/api/invoices").with(as(admin))
                .param("filter", "customerName:contains:Jones & Co: Ltd"));
        assertThat(result.get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void aRelativeDatePresetSelectsTheRightWindow() throws Exception {
        invoice(acme, "10.00", 1, sales);
        JsonNode today = getJson(get("/api/invoices").with(as(admin))
                .param("filter", "invoiceDate:relative:today"));
        JsonNode yesterday = getJson(get("/api/invoices").with(as(admin))
                .param("filter", "invoiceDate:relative:yesterday"));
        assertThat(today.get("totalElements").asLong()).isEqualTo(1);
        assertThat(yesterday.get("totalElements").asLong()).isZero();
    }

    @Test
    void theIsEmptyOperatorFindsCustomersWithNoCollectionPoc() throws Exception {
        pocService.add(acme.getId(), PocType.COLLECTION, collections.getId(), true);

        JsonNode missing = getJson(get("/api/customers").with(as(admin))
                .param("filter", "collectionPocUserId:isEmpty:"));

        assertThat(missing.get("totalElements").asLong()).isEqualTo(1);
        assertThat(missing.get("content").get(0).get("name").asText()).isEqualTo("Globex Corp");
    }

    // ---- AC-D10: a customer-scoped user's scope cannot be widened ---------------

    @Test
    void aCustomerScopedUserSeesOnlyTheirOwnRowsWhateverFilterTheySend() throws Exception {
        invoice(acme, "10.00", 1, sales);
        invoice(globex, "10.00", 1, sales);
        User acmeUser = customerUser("acme.user", acme.getId());

        JsonNode unfiltered = getJson(get("/api/invoices").with(as(acmeUser)));
        assertThat(unfiltered.get("totalElements").asLong()).isEqualTo(1);

        // Asking for the other customer explicitly returns nothing, not their rows.
        JsonNode widened = getJson(get("/api/invoices").with(as(acmeUser))
                .param("filter", "customerId:eq:" + globex.getId()));
        assertThat(widened.get("totalElements").asLong()).isZero();

        JsonNode viaNotIn = getJson(get("/api/invoices").with(as(acmeUser))
                .param("filter", "customerId:eq:" + acme.getId()));
        assertThat(viaNotIn.get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void aCustomerScopedUsersTilesAreScopedTheSameWay() throws Exception {
        invoice(acme, "10.00", 1, sales);
        invoice(globex, "999.00", 1, sales);
        User acmeUser = customerUser("acme.user", acme.getId());

        JsonNode tiles = getJson(get("/api/invoices/summary").with(as(acmeUser)));
        assertThat(tiles.get("count").asLong()).isEqualTo(1);
        assertThat(tiles.get("totalBilled").asDouble()).isEqualTo(10.0);
    }

    // ---- AC-A6: a POC's default book is enforced and reported as locked ---------

    @Test
    void aSalesPocWithoutScopeOverrideIsLockedToTheirOwnBook() throws Exception {
        invoice(acme, "10.00", 1, sales);
        invoice(acme, "10.00", 1, otherSales);

        JsonNode mine = getJson(get("/api/invoices").with(as(sales)));

        assertThat(mine.get("totalElements").asLong()).isEqualTo(1);
        assertThat(mine.get("lockedFilters").get(0).asText())
                .isEqualTo("salesPocUserId:eq:" + sales.getId());
    }

    @Test
    void aLockedPocCannotWidenTheirBookWithAFilter() throws Exception {
        invoice(acme, "10.00", 1, otherSales);

        JsonNode attempt = getJson(get("/api/invoices").with(as(sales))
                .param("filter", "salesPocUserId:eq:" + otherSales.getId()));

        assertThat(attempt.get("totalElements").asLong()).isZero();
    }

    @Test
    void myScopeTellsTheClientWhetherTheChipIsClearable() throws Exception {
        JsonNode locked = getJson(get("/api/pocs/my-scope").with(as(sales)));
        assertThat(locked.get("clearable").asBoolean()).isFalse();
        assertThat(locked.get("sales").asBoolean()).isTrue();

        JsonNode free = getJson(get("/api/pocs/my-scope").with(as(collections)));
        assertThat(free.get("clearable").asBoolean()).isTrue();
    }

    @Test
    void aCollectionPocWithScopeOverrideSeesEverythingByDefault() throws Exception {
        invoice(acme, "10.00", 1, sales);
        invoice(globex, "10.00", 1, otherSales);

        JsonNode all = getJson(get("/api/invoices").with(as(collections)));

        assertThat(all.get("totalElements").asLong()).isEqualTo(2);
        assertThat(all.get("lockedFilters").size()).isZero();
    }

    // ---- Feature E: tiles reflect the filter, not the page ----------------------

    @Test
    void tilesAreComputedOverTheWholeFilteredSetNotTheVisiblePage() throws Exception {
        for (int i = 0; i < 25; i++) invoice(acme, "10.00", 1, sales);

        JsonNode tiles = getJson(get("/api/invoices/summary").with(as(admin)));

        assertThat(tiles.get("count").asLong()).isEqualTo(25);
        assertThat(tiles.get("totalBilled").asDouble()).isEqualTo(250.0);
        assertThat(tiles.get("outstanding").asDouble()).isEqualTo(250.0);
        assertThat(tiles.get("unpaidCount").asLong()).isEqualTo(25);
    }

    @Test
    void narrowingTheFilterChangesEveryTile() throws Exception {
        invoice(acme, "10.00", 1, sales);
        invoice(globex, "500.00", 1, otherSales);

        JsonNode all = getJson(get("/api/invoices/summary").with(as(admin)));
        JsonNode narrowed = getJson(get("/api/invoices/summary").with(as(admin))
                .param("filter", "salesPocUserId:eq:" + sales.getId()));

        assertThat(all.get("totalBilled").asDouble()).isEqualTo(510.0);
        assertThat(narrowed.get("count").asLong()).isEqualTo(1);
        assertThat(narrowed.get("totalBilled").asDouble()).isEqualTo(10.0);
    }

    @Test
    void anEmptyResultSetRendersZerosNotBlanks() throws Exception {
        JsonNode tiles = getJson(get("/api/invoices/summary").with(as(admin))
                .param("filter", "invoiceNumber:eq:NOPE"));
        assertThat(tiles.get("count").asLong()).isZero();
        assertThat(tiles.get("totalBilled").asDouble()).isZero();
        assertThat(tiles.get("outstanding").asDouble()).isZero();
    }

    @Test
    void moneyTilesUseExactDecimalArithmetic() throws Exception {
        invoice(acme, "0.10", 1, sales);
        invoice(acme, "0.20", 1, sales);

        JsonNode tiles = getJson(get("/api/invoices/summary").with(as(admin)));

        assertThat(new BigDecimal(tiles.get("totalBilled").asText()))
                .isEqualByComparingTo(new BigDecimal("0.30"));
    }

    @Test
    void tilesCountRecordsMissingAPoc() throws Exception {
        invoice(acme, "10.00", 1, sales);
        invoiceRepository.save(Invoice.builder()
                .customer(acme).invoiceNumber("LEGACY-1").invoiceDate(java.time.Instant.now())
                .total(new BigDecimal("5.00")).build());

        JsonNode tiles = getJson(get("/api/invoices/summary").with(as(admin)));
        assertThat(tiles.get("pocMissingCount").asLong()).isEqualTo(1);
    }

    @Test
    void paymentTilesExcludeVoidedMoney() throws Exception {
        Invoice inv = invoice(acme, "100.00", 1, sales);
        var payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("100.00"), "Cash", null, List.of(inv.getId()),
                collections.getId(), null));
        paymentService.voidPayment(payment.getId());

        JsonNode tiles = getJson(get("/api/payments/summary").with(as(admin)));
        assertThat(tiles.get("count").asLong()).isEqualTo(1);
        assertThat(tiles.get("voidedCount").asLong()).isEqualTo(1);
        assertThat(tiles.get("totalCollected").asDouble()).isZero();
    }

    @Test
    void customerTilesSumOutstandingAcrossInvoices() throws Exception {
        invoice(acme, "100.00", 1, sales);
        invoice(globex, "40.00", 1, sales);

        JsonNode tiles = getJson(get("/api/customers/summary").with(as(admin)));
        assertThat(tiles.get("count").asLong()).isEqualTo(2);
        assertThat(tiles.get("totalOutstanding").asDouble()).isEqualTo(140.0);
        assertThat(tiles.get("missingCollectionPocCount").asLong()).isEqualTo(2);
    }
}
