package com.geneinvoice.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every dashboard figure as of a date is the one the dashboard showed that day (§5.12, D1–D8): invoices, payments
 * and allocations that existed then, as they were, under the customers' names then, aged against the viewed day.
 * On top of {@link AsOfScenario}, on D3 Beta gets invoice Z (70) backdated to D1.
 */
class AsOfDashboardTest extends AsOfScenario {

    Long backdated;

    @BeforeEach
    void aBackdatedInvoice() {
        on(D3);
        actAs(admin);
        backdated = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(beta.getId(),
                D1.atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC), D1.plusDays(30), null, null,
                sam.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("70.00"))))).getId();
        on(TODAY);
    }

    private JsonNode dashboard(String figure, User caller, LocalDate day, String... params) throws Exception {
        var request = get("/api/dashboard/" + figure);
        for (int i = 0; i < params.length; i += 2) request.param(params[i], params[i + 1]);
        return body(call(request, caller, day).andExpect(status().isOk()));
    }

    private static String money(JsonNode node) {
        return new BigDecimal(node.asText()).setScale(2).toPlainString();
    }

    /** "amount/count" of the one month asked for. */
    private String month(JsonNode series) {
        assertThat(series.get("months")).hasSize(1);
        return money(series.at("/months/0/amount")) + "/" + series.at("/months/0/count").asLong();
    }

    /** "label amount/count" of each ageing bucket with anything in it. */
    private static List<String> ages(JsonNode figure) {
        List<String> out = new ArrayList<>();
        figure.get("buckets").forEach(b -> {
            if (b.get("count").asLong() > 0) {
                out.add(b.get("label").asText() + " " + money(b.get("amount")) + "/" + b.get("count").asLong());
            }
        });
        return out;
    }

    private static List<String> customers(JsonNode figure, String amountField) {
        List<String> out = new ArrayList<>();
        figure.get("customers").forEach(c -> out.add(c.get("customerName").asText() + " " + money(c.get(amountField))));
        return out;
    }

    @Test
    void billedCountsTheInvoicesThatExistedThenEvenWhenOneWasLaterBackdatedIntoThePeriod() throws Exception {
        assertThat(month(dashboard("billed-by-month", admin, D1, "months", "1"))).isEqualTo("1600.00/3");
        assertThat(month(dashboard("billed-by-month", admin, D2, "months", "1")))
                .as("Z, dated D1 but created on D3, is not there yet").isEqualTo("1600.00/3");
        assertThat(month(dashboard("billed-by-month", admin, D3, "months", "1"))).isEqualTo("1670.00/4");
        assertThat(month(dashboard("billed-by-month", admin, D4, "months", "1"))).as("Y cancelled")
                .isEqualTo("1170.00/3");
        assertThat(month(dashboard("billed-by-month", admin, null, "months", "1"))).isEqualTo("1170.00/3");

        JsonNode d2 = dashboard("billed-by-month", admin, D2, "months", "1");
        assertThat(d2.at("/months/0/month").asText()).isEqualTo("2026-03");
        assertThat(d2.at("/scope/asOf").asText()).isEqualTo(D2.toString());
        assertThat(d2.get("coverage").asText()).isEqualTo("ALL");
        call(get("/api/dashboard/billed-by-month"), admin, D2)
                .andExpect(header().string("X-View-As-Of", D2.toString()));
        assertThat(dashboard("billed-by-month", admin, null).at("/scope/asOf").asText(null)).isNull();
    }

    @Test
    void outstandingIsAgedAgainstTheViewedDay() throws Exception {
        assertThat(ages(dashboard("outstanding-by-age", admin, D1))).containsExactly("Not yet due 1600.00/3");
        assertThat(ages(dashboard("outstanding-by-age", admin, D2)))
                .as("X part paid, due that very day").containsExactly("Not yet due 1200.00/3");
        assertThat(ages(dashboard("outstanding-by-age", admin, D3)))
                .containsExactly("Not yet due 670.00/3", "1–30 days 600.00/1");
        assertThat(ages(dashboard("outstanding-by-age", admin, D4)))
                .as("the payment voided, Y cancelled").containsExactly("Not yet due 170.00/2", "1–30 days 1000.00/1");
        assertThat(ages(dashboard("outstanding-by-age", admin, null)))
                .containsExactly("Not yet due 170.00/2", "1–30 days 1000.00/1");

        JsonNode d4 = dashboard("outstanding-by-age", admin, D4);
        assertThat(d4.at("/buckets/1/dueDateFrom").asText()).isEqualTo(D4.minusDays(30).toString());
        assertThat(d4.at("/buckets/1/dueDateTo").asText()).isEqualTo(D4.minusDays(1).toString());
        assertThat(dashboard("outstanding-by-age", admin, null).at("/buckets/1/dueDateTo").asText())
                .isEqualTo(TODAY.minusDays(1).toString());

        User acmeLogin = customerUser("acme.login.dash", acme.getId());
        JsonNode own = dashboard("outstanding-by-age", acmeLogin, D2);
        assertThat(own.get("coverage").asText()).isEqualTo("OWN");
        assertThat(ages(own)).containsExactly("Not yet due 1100.00/2");
    }

    @Test
    void topCustomersAreNamedAsTheyWereThen() throws Exception {
        JsonNode d2 = dashboard("top-outstanding-customers", admin, D2);
        assertThat(customers(d2, "outstanding")).containsExactly("Acme Ltd 1100.00", "Beta Co 100.00");
        assertThat(d2.at("/customers/0/openInvoices").asLong()).isEqualTo(2);
        assertThat(customers(dashboard("top-outstanding-customers", admin, D4), "outstanding"))
                .containsExactly("Zeta Holdings 1000.00", "Beta Co 170.00");
        assertThat(customers(dashboard("top-outstanding-customers", admin, null), "outstanding"))
                .containsExactly("Zeta Holdings 1000.00", "Beta Co 170.00");

        assertThat(customers(dashboard("top-paying-customers", admin, D2, "months", "1"), "collected"))
                .containsExactly("Acme Ltd 400.00");
        assertThat(customers(dashboard("top-paying-customers", admin, D3, "months", "1"), "collected"))
                .containsExactly("Zeta Holdings 400.00");
        assertThat(dashboard("top-paying-customers", admin, D4, "months", "1").get("customers")).isEmpty();
        assertThat(dashboard("top-paying-customers", admin, null, "months", "1").get("customers")).isEmpty();
    }

    @Test
    void aPaymentVoidedAfterTheDayCountsAsCollectedThenInBothWaysOfCounting() throws Exception {
        // Whole payments (admin sees every payment) ...
        assertThat(month(dashboard("collected-by-month", admin, D1, "months", "1"))).isEqualTo("0.00/0");
        assertThat(month(dashboard("collected-by-month", admin, D2, "months", "1"))).isEqualTo("400.00/1");
        assertThat(month(dashboard("collected-by-month", admin, D3, "months", "1"))).isEqualTo("400.00/1");
        assertThat(month(dashboard("collected-by-month", admin, D4, "months", "1"))).isEqualTo("0.00/0");
        assertThat(month(dashboard("collected-by-month", admin, null, "months", "1"))).isEqualTo("0.00/0");

        // ... and allocations to a book (Colin sees only the payments he works in NORTH and SOUTH).
        String book = north.getId() + "," + south.getId();
        JsonNode colinD2 = dashboard("collected-by-month", colin, D2, "months", "1", "regionIds", book);
        assertThat(colinD2.get("coverage").asText()).isEqualTo("BOOK");
        assertThat(month(colinD2)).isEqualTo("400.00/1");
        assertThat(colinD2.at("/scope/asOf").asText()).isEqualTo(D2.toString());
        assertThat(month(dashboard("collected-by-month", colin, D4, "months", "1", "regionIds", book)))
                .isEqualTo("0.00/0");
        assertThat(month(dashboard("collected-by-month", colin, null, "months", "1", "regionIds", book)))
                .isEqualTo("0.00/0");
        assertThat(customers(dashboard("top-paying-customers", colin, D2, "months", "1", "regionIds", book),
                "collected")).containsExactly("Acme Ltd 400.00");
    }

    @Test
    void theBookThatCountsIsTheBookItWasThen() throws Exception {
        User cody = user("cody.collections", DataSeeder.ROLE_COLLECTION_POC);
        grant(cody, north, AccessLevel.READ, RecordScope.BOOK);
        grant(cody, south, AccessLevel.READ, RecordScope.BOOK);
        actAs(admin);
        paymentService.update(paymentId, new PaymentDtos.UpdatePaymentRequest(null, cody.getId()));
        SecurityContextHolder.clearContext();

        String book = north.getId() + "," + south.getId();
        assertThat(month(dashboard("collected-by-month", colin, D3, "months", "1", "regionIds", book)))
                .as("Colin worked the payment on D3").isEqualTo("400.00/1");
        assertThat(month(dashboard("collected-by-month", cody, D3, "months", "1", "regionIds", book)))
                .isEqualTo("0.00/0");
    }

    @Test
    void theRegionsAskedAboutAreTheRegionsTheRecordsWereInThen() throws Exception {
        String n = north.getId().toString();
        JsonNode d2 = body(call(get("/api/dashboard/top-outstanding-customers").param("regionIds", n), admin, D2)
                .andExpect(status().isOk()));
        assertThat(customers(d2, "outstanding")).containsExactly("Acme Ltd 1100.00", "Beta Co 100.00");
        assertThat(d2.at("/scope/regionIds").toString()).isEqualTo("[" + n + "]");
        JsonNode d4 = body(call(get("/api/dashboard/top-outstanding-customers").param("regionIds", n), admin, D4)
                .andExpect(status().isOk()));
        assertThat(customers(d4, "outstanding")).as("Acme moved to SOUTH on D3").containsExactly("Beta Co 170.00");
        JsonNode southD2 = body(call(get("/api/dashboard/collected-by-month").param("regionIds",
                south.getId().toString()).param("months", "1"), admin, D2).andExpect(status().isOk()));
        assertThat(month(southD2)).isEqualTo("0.00/0");
        JsonNode southD3 = body(call(get("/api/dashboard/collected-by-month").param("regionIds",
                south.getId().toString()).param("months", "1"), admin, D3).andExpect(status().isOk()));
        assertThat(month(southD3)).isEqualTo("400.00/1");
    }

    @Test
    void theTilesAndTheUpcomingPromisesAreAnsweredAsOfTheDayToo() throws Exception {
        JsonNode invoices = read("/api/invoices/summary", D2);
        assertThat(money(invoices.get("outstanding"))).isEqualTo("1200.00");
        assertThat(money(invoices.get("totalPaid"))).isEqualTo("400.00");
        assertThat(invoices.get("count").asLong()).isEqualTo(3);
        assertThat(money(read("/api/invoices/summary", null).get("outstanding"))).isEqualTo("1170.00");

        JsonNode promises = read("/api/promises/summary", D2);
        assertThat(promises.get("partiallyKeptCount").asLong()).isEqualTo(1);
        assertThat(promises.get("openCount").asLong()).isZero();
        JsonNode livePromises = read("/api/promises/summary", null);
        assertThat(livePromises.get("openCount").asLong()).isEqualTo(1);
        assertThat(livePromises.get("partiallyKeptCount").asLong()).isZero();

        JsonNode upcoming = body(call(get("/api/promises").param("size", "10").param("sort", "promisedDate,asc")
                .param("filter", "status:in:OPEN,PARTIALLY_KEPT")
                .param("filter", "promisedDate:gte:" + D2), admin, D2).andExpect(status().isOk()));
        assertThat(upcoming.get("totalElements").asLong()).isEqualTo(1);
        assertThat(upcoming.at("/content/0/status").asText()).isEqualTo("PARTIALLY_KEPT");
        assertThat(upcoming.at("/content/0/customerName").asText()).isEqualTo("Acme Ltd");
    }
}
