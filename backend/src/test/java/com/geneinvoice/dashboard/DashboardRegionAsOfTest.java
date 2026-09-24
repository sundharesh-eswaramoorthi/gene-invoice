package com.geneinvoice.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.asof.TestHistoryFloor;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.FixedHistoryClock;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.CustomerRegionHistory;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "WHICH BRANCH IS THIS MONEY" HAS TO HAVE ONE ANSWER ON BOTH SCREENS (B1, B3).
 *
 * <p>{@code ?region=} on a dashboard figure and {@code filter=regionId:eq:} on the equivalent list
 * are the same question. Under {@code ?asOf} the list resolved it from the placement ledger at
 * that date and the dashboard resolved it from today's {@code customers.region_id}, so for an
 * account that has since moved the two disagreed: the same "as of 31 January" chip billed
 * January's money to two different branches depending on which screen you were on.
 *
 * <p>The account here really has moved, which is the only configuration in which the two spellings
 * can differ — {@code DashboardRegionTest} narrows live, where today's placement IS the answer,
 * and every other as-of figure test creates the account in its branch and leaves it there.
 */
@Import({FixedHistoryClock.Config.class, TestHistoryFloor.Config.class})
class DashboardRegionAsOfTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired FixedHistoryClock clock;
    @Autowired TestHistoryFloor floor;

    User admin;
    Region west;
    Customer moved;
    Product widget;
    Invoice januarys;

    LocalDate today;
    /** Raised while the account was in WEST. */
    LocalDate raised;
    /** The as-of date: after the invoice, before the move. */
    LocalDate asked;
    /** The day it moved to the default branch. */
    LocalDate movedOn;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        west = region("WEST");
        widget = product("Widget", "100.00");
        today = LocalDate.now(ZoneOffset.UTC);
        raised = today.minusDays(40);
        asked = today.minusDays(30);
        movedOn = today.minusDays(20);

        // Filed in the default branch TODAY, and in WEST until twenty days ago: the account row
        // says where it is now and the ledger says where it was, which is what an as-of read has
        // to follow (B1, B3).
        clock.freezeAt(noon(today.minusDays(60)));
        moved = customerRepository.saveAndFlush(
                Customer.builder().name("Moved Ltd").region(defaultRegion()).build());
        clock.release();
        place(west, today.minusDays(60), movedOn);
        place(defaultRegion(), movedOn, null);

        actAs(admin);
        januarys = raise("1000.00");
    }

    @AfterEach
    void putEverythingBack() {
        clock.release();
        floor.reset();
    }

    /**
     * The dashboard narrowed to WEST as of that day counts the invoice, because WEST is where the
     * account was then — and narrowed to the branch it is in TODAY it counts nothing. The list
     * under the same date and the same narrowing says the identical thing, which is the claim:
     * one question, one answer, two screens.
     *
     * <p>Live, both spellings still read today's placement and both name the default branch, so
     * the live behaviour this method has had since R6 is pinned here too.
     */
    @Test
    void anAsOfFigureNarrowedByRegionUsesTheBranchTheAccountWasInThen() throws Exception {
        assertThat(billed(asked, west)).isEqualByComparingTo("1000.00");
        assertThat(billed(asked, defaultRegion())).isEqualByComparingTo("0.00");
        // The list, asked the same question through its own spelling of the narrowing.
        assertThat(invoiceIds(asked, west)).containsExactly(januarys.getId());
        assertThat(invoiceIds(asked, defaultRegion())).isEmpty();

        // Today the account is in the default branch and both screens say so.
        assertThat(billed(null, defaultRegion())).isEqualByComparingTo("1000.00");
        assertThat(billed(null, west)).isEqualByComparingTo("0.00");
        assertThat(invoiceIds(null, defaultRegion())).containsExactly(januarys.getId());
        assertThat(invoiceIds(null, west)).isEmpty();
    }

    // ---------------------------------------------------------------- fixtures

    private static Instant noon(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant();
    }

    private void place(Region region, LocalDate from, LocalDate to) {
        customerRegionHistoryRepository.saveAndFlush(CustomerRegionHistory.builder()
                .customerId(moved.getId()).regionId(region.getId())
                .validFrom(from).validTo(to).build());
    }

    private Invoice raise(String total) {
        clock.freezeAt(noon(raised));
        int quantity = new BigDecimal(total).divide(new BigDecimal("100.00")).intValue();
        Invoice invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                moved.getId(), noon(raised), raised.plusDays(30), PaymentTerm.CUSTOM, null,
                admin.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), quantity,
                        new BigDecimal("100.00")))));
        clock.release();
        return invoice;
    }

    /** Six months of window, so the month the invoice was raised in is inside it whatever today is. */
    private BigDecimal billed(LocalDate asOf, Region narrowTo) throws Exception {
        var request = get("/api/dashboard/billed-by-month")
                .param("months", "6").param("region", narrowTo.getId().toString()).with(as(admin));
        if (asOf != null) request = request.param("asOf", asOf.toString());
        String body = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, DashboardDtos.MonthlySeries.class).months().stream()
                .map(DashboardDtos.MonthPoint::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<Long> invoiceIds(LocalDate asOf, Region narrowTo) throws Exception {
        var request = get("/api/invoices")
                .param("filter", "regionId:eq:" + narrowTo.getId()).with(as(admin));
        if (asOf != null) request = request.param("asOf", asOf.toString());
        String body = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode page = objectMapper.readTree(body);
        List<Long> ids = new java.util.ArrayList<>();
        page.get("content").forEach(row -> ids.add(row.get("id").asLong()));
        return ids;
    }
}
