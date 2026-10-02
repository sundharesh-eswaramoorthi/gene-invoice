package com.geneinvoice.history;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The customer, invoice, product, payment, promise and dispute lists, their filters, sorts, tiles and exports, as of
 * each day of the {@link AsOfScenario}: every figure is what the list said that day, with "today" being the day
 * looked at.
 */
class AsOfListTest extends AsOfScenario {

    private static List<String> texts(JsonNode page, String field) {
        List<String> out = new ArrayList<>();
        page.get("content").forEach(row -> out.add(row.get(field).asText()));
        return out;
    }

    private JsonNode customers(LocalDate day, String... filters) throws Exception {
        return read("/api/customers", day, filters);
    }

    private JsonNode invoices(LocalDate day, String... filters) throws Exception {
        return read("/api/invoices", day, filters);
    }

    private JsonNode row(JsonNode page, long id) {
        for (JsonNode r : page.get("content")) {
            if (r.get("id").asLong() == id) return r;
        }
        throw new AssertionError("no row " + id + " in " + page);
    }

    @Test
    void theCustomerListShowsEachCustomerAsItWasWithTheFiguresOfThatDay() throws Exception {
        assertThat(customers(D0).get("totalElements").asInt()).as("nobody existed yet").isZero();

        JsonNode d1 = customers(D1);
        assertThat(d1.get("asOf").asText()).isEqualTo(D1.toString());
        assertThat(texts(d1, "name")).containsExactly("Acme Ltd", "Beta Co");
        JsonNode acmeD1 = row(d1, acme.getId());
        assertThat(acmeD1.get("regionCode").asText()).isEqualTo("NORTH");
        assertThat(acmeD1.get("outstanding").decimalValue()).isEqualByComparingTo("1500");
        assertThat(acmeD1.get("overdueAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(acmeD1.at("/successPocs/0/user/username").asText()).isEqualTo("sam.sales");
        assertThat(acmeD1.get("canManage").asBoolean()).as("nothing is changed in a past view").isFalse();
        assertThat(acmeD1.get("canMoveRegion").asBoolean()).isFalse();
        assertThat(acmeD1.has("version")).as("a rebuilt record has no row version").isFalse();

        JsonNode acmeD2 = row(customers(D2), acme.getId());
        assertThat(acmeD2.get("outstanding").decimalValue()).isEqualByComparingTo("1100");
        assertThat(acmeD2.get("overdueAmount").decimalValue())
                .as("X is due on D2, so on D2 it is not late yet").isEqualByComparingTo("0");

        JsonNode d3 = customers(D3);
        assertThat(texts(d3, "name")).containsExactly("Beta Co", "Gamma Co", "Zeta Holdings");
        JsonNode acmeD3 = row(d3, acme.getId());
        assertThat(acmeD3.get("regionCode").asText()).isEqualTo("SOUTH");
        assertThat(acmeD3.get("overdueAmount").decimalValue()).isEqualByComparingTo("600");
        assertThat(acmeD3.get("successPocs")).isEmpty();
        assertThat(acmeD3.get("pocMissing").asBoolean()).isTrue();

        JsonNode acmeD4 = row(customers(D4), acme.getId());
        assertThat(acmeD4.get("outstanding").decimalValue())
                .as("the payment is voided and Y cancelled").isEqualByComparingTo("1000");
        assertThat(acmeD4.get("overdueAmount").decimalValue()).isEqualByComparingTo("1000");

        JsonNode live = customers(null);
        assertThat(live.get("asOf").isNull()).isTrue();
        assertThat(row(live, acme.getId()).get("canManage").asBoolean()).isTrue();
        assertThat(row(live, acme.getId()).get("version").isNumber()).isTrue();
    }

    @Test
    void customersAreFilteredByWhatTheirFiguresAndSeatsWereThatDay() throws Exception {
        assertThat(texts(customers(D2, "outstanding:gt:1000"), "name")).containsExactly("Acme Ltd");
        assertThat(texts(customers(D4, "outstanding:gt:1000"), "name")).isEmpty();
        assertThat(texts(customers(null, "outstanding:gt:1000"), "name")).isEmpty();

        String sam = "successPocUserId:eq:" + this.sam.getId();
        assertThat(texts(customers(D2, sam), "name")).containsExactly("Acme Ltd");
        assertThat(texts(customers(D3, sam), "name")).as("the seat was removed on D3").isEmpty();
        assertThat(texts(customers(D2, "successPocUserId:isEmpty:"), "name")).containsExactly("Beta Co");

        String inNorth = "regionId:eq:" + north.getId();
        assertThat(texts(customers(D2, inNorth), "name")).containsExactly("Acme Ltd", "Beta Co");
        assertThat(texts(customers(D3, inNorth), "name")).containsExactly("Beta Co", "Gamma Co");
        assertThat(texts(customers(D2, "name:contains:zeta"), "name")).isEmpty();
        assertThat(texts(customers(D3, "name:contains:zeta"), "name")).containsExactly("Zeta Holdings");

        JsonNode byOutstanding = read("/api/customers?sort=outstanding,desc", D2);
        assertThat(texts(byOutstanding, "name")).containsExactly("Acme Ltd", "Beta Co");
    }

    @Test
    void theCustomerTilesAddUpTheDayLookedAt() throws Exception {
        JsonNode d2 = read("/api/customers/summary", D2);
        assertThat(d2.get("count").asLong()).isEqualTo(2);
        assertThat(d2.get("totalOutstanding").decimalValue()).isEqualByComparingTo("1200");
        assertThat(d2.get("missingSuccessPocCount").asLong()).isEqualTo(1);
        assertThat(d2.get("missingCollectionPocCount").asLong()).isEqualTo(2);

        JsonNode d4 = read("/api/customers/summary", D4);
        assertThat(d4.get("count").asLong()).isEqualTo(3);
        assertThat(d4.get("totalOutstanding").decimalValue()).isEqualByComparingTo("1100");
        assertThat(d4.get("missingSuccessPocCount").asLong()).isEqualTo(3);
    }

    @Test
    void theInvoiceListShowsEachInvoiceAsItWasOverdueByTheDayLookedAt() throws Exception {
        assertThat(invoices(D0).get("totalElements").asInt()).isZero();

        JsonNode d1 = invoices(D1);
        assertThat(d1.get("totalElements").asInt()).isEqualTo(3);
        JsonNode xD1 = row(d1, x);
        assertThat(xD1.get("customerName").asText()).isEqualTo("Acme Ltd");
        assertThat(xD1.get("status").asText()).isEqualTo("UNPAID");
        assertThat(xD1.get("overdue").asBoolean()).isFalse();
        assertThat(xD1.at("/salesPoc/username").asText()).isEqualTo("sam.sales");
        assertThat(xD1.get("regionCode").asText()).isEqualTo("NORTH");
        assertThat(xD1.get("canManage").asBoolean()).isFalse();

        JsonNode xD2 = row(invoices(D2), x);
        assertThat(xD2.get("status").asText()).isEqualTo("PARTIALLY_PAID");
        assertThat(xD2.get("paidAmount").decimalValue()).isEqualByComparingTo("400");
        assertThat(xD2.get("balance").decimalValue()).isEqualByComparingTo("600");
        assertThat(xD2.get("overdue").asBoolean()).isFalse();

        JsonNode xD3 = row(invoices(D3), x);
        assertThat(xD3.get("customerName").asText()).isEqualTo("Zeta Holdings");
        assertThat(xD3.get("regionCode").asText()).isEqualTo("SOUTH");
        assertThat(xD3.get("overdue").asBoolean()).as("late from the day after it fell due").isTrue();
        assertThat(xD3.get("daysOverdue").asInt()).isEqualTo(1);

        JsonNode d4 = invoices(D4);
        assertThat(row(d4, x).get("status").asText()).isEqualTo("UNPAID");
        assertThat(row(d4, x).get("paidAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(row(d4, x).get("daysOverdue").asInt()).isEqualTo(2);
        assertThat(row(d4, y).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(row(invoices(D3), y).get("status").asText()).isEqualTo("UNPAID");
    }

    @Test
    void invoicesAreFilteredAndSortedByWhatTheyAndTheirCustomerWereThatDay() throws Exception {
        assertThat(invoices(D2, "customerName:contains:zeta").get("totalElements").asInt()).isZero();
        assertThat(invoices(D3, "customerName:contains:zeta").get("totalElements").asInt()).isEqualTo(2);

        JsonNode d2 = read("/api/invoices?sort=customerName,asc", D2);
        assertThat(texts(d2, "customerName")).containsExactly("Acme Ltd", "Acme Ltd", "Beta Co");
        JsonNode d3 = read("/api/invoices?sort=customerName,asc", D3);
        assertThat(texts(d3, "customerName")).containsExactly("Beta Co", "Zeta Holdings", "Zeta Holdings");

        String inNorth = "regionId:eq:" + north.getId();
        assertThat(invoices(D2, inNorth).get("totalElements").asInt()).isEqualTo(3);
        assertThat(invoices(D3, inNorth).get("totalElements").asInt()).as("Acme moved on D3").isEqualTo(1);

        assertThat(invoices(D2, "overdue:eq:true").get("totalElements").asInt()).isZero();
        assertThat(texts(invoices(D3, "overdue:eq:true"), "id")).containsExactly(x.toString());
        assertThat(invoices(D4, "status:eq:CANCELLED").get("totalElements").asInt()).isEqualTo(1);
        assertThat(invoices(D3, "status:eq:CANCELLED").get("totalElements").asInt()).isZero();
        assertThat(invoices(D2, "paidAmount:gt:0").get("totalElements").asInt()).isEqualTo(1);
        assertThat(invoices(D4, "paidAmount:gt:0").get("totalElements").asInt()).isZero();

        assertThat(invoices(D1, "invoiceDate:relative:today").get("totalElements").asInt())
                .as("today is the day looked at").isEqualTo(3);
        assertThat(invoices(D2, "invoiceDate:relative:today").get("totalElements").asInt()).isZero();
        assertThat(invoices(D2, "invoiceDate:relative:yesterday").get("totalElements").asInt()).isEqualTo(3);
        assertThat(read("/api/invoices?customerId=" + beta.getId(), D2).get("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void theInvoiceTilesAddUpTheDayLookedAt() throws Exception {
        JsonNode d2 = read("/api/invoices/summary", D2);
        assertThat(d2.get("count").asLong()).isEqualTo(3);
        assertThat(d2.get("totalBilled").decimalValue()).isEqualByComparingTo("1600");
        assertThat(d2.get("totalPaid").decimalValue()).isEqualByComparingTo("400");
        assertThat(d2.get("outstanding").decimalValue()).isEqualByComparingTo("1200");
        assertThat(d2.get("partiallyPaidCount").asLong()).isEqualTo(1);
        assertThat(d2.get("overdueCount").asLong()).isZero();

        JsonNode d4 = read("/api/invoices/summary", D4);
        assertThat(d4.get("totalPaid").decimalValue()).isEqualByComparingTo("0");
        assertThat(d4.get("outstanding").decimalValue()).isEqualByComparingTo("1100");
        assertThat(d4.get("cancelledCount").asLong()).isEqualTo(1);
        assertThat(d4.get("overdueCount").asLong()).isEqualTo(1);
        assertThat(d4.get("overdueAmount").decimalValue()).isEqualByComparingTo("1000");

        JsonNode acmeD2 = read("/api/invoices/summary?customerId=" + acme.getId(), D2);
        assertThat(acmeD2.get("count").asLong()).isEqualTo(2);
        assertThat(read("/api/invoices/summary", D3, "regionId:eq:" + south.getId()).get("count").asLong())
                .isEqualTo(2);
    }

    @Test
    void theProductListShowsTheCatalogueAsItWas() throws Exception {
        assertThat(texts(read("/api/products", D2), "name")).containsExactly("Widget");
        assertThat(texts(read("/api/products", D3), "name")).containsExactly("Gadget", "Widget Pro");
        assertThat(texts(read("/api/products", D2, "name:contains:pro"), "name")).isEmpty();
        assertThat(read("/api/products", D0).get("totalElements").asInt()).isZero();
        assertThat(read("/api/products", D2).get("asOf").asText()).isEqualTo(D2.toString());
    }

    @Test
    void exportsAreTheListAsItWasThatDay() throws Exception {
        String customersD2 = export("/api/customers/export", D2);
        assertThat(customersD2).contains("Acme Ltd").contains("1100.00").doesNotContain("Zeta").doesNotContain("Gamma");
        assertThat(customersD2).contains("sam.sales (primary)");
        String customersD3 = export("/api/customers/export", D3);
        assertThat(customersD3).contains("Zeta Holdings").contains("Gamma Co").doesNotContain("sam.sales");

        String invoicesD2 = export("/api/invoices/export", D2, "customerId:eq:" + acme.getId());
        assertThat(invoicesD2).contains("Acme Ltd").contains("400.00").doesNotContain("Zeta");
        assertThat(invoicesD2.lines().count()).as("a header and X and Y").isEqualTo(3);
        String invoicesD4 = export("/api/invoices/export", D4, "status:eq:CANCELLED");
        assertThat(invoicesD4).contains("Zeta Holdings").contains("CANCELLED");
        assertThat(invoicesD4.lines().count()).isEqualTo(2);

        String productsD2 = export("/api/products/export", D2);
        assertThat(productsD2).contains("Widget").doesNotContain("Widget Pro").doesNotContain("Gadget");
        assertThat(export("/api/products/export", D3)).contains("Widget Pro").contains("Gadget");
    }

    @Test
    void thePaymentListShowsEachPaymentWithTheAllocationsOfThatDay() throws Exception {
        assertThat(read("/api/payments", D1).get("totalElements").asInt()).isZero();

        JsonNode d2 = read("/api/payments", D2);
        assertThat(d2.get("asOf").asText()).isEqualTo(D2.toString());
        JsonNode pD2 = row(d2, paymentId);
        assertThat(pD2.get("customerName").asText()).isEqualTo("Acme Ltd");
        assertThat(pD2.get("regionCode").asText()).isEqualTo("NORTH");
        assertThat(pD2.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(pD2.at("/collectionPoc/username").asText()).isEqualTo("colin.collections");
        assertThat(pD2.get("invoices")).hasSize(1);
        assertThat(pD2.at("/invoices/0/id").asLong()).isEqualTo(x);
        assertThat(pD2.at("/invoices/0/allocatedAmount").decimalValue()).isEqualByComparingTo("400");
        assertThat(pD2.at("/invoices/0/paidAmount").decimalValue()).as("X as it was that day").isEqualByComparingTo("400");
        assertThat(pD2.at("/invoices/0/status").asText()).isEqualTo("PARTIALLY_PAID");
        assertThat(pD2.get("canManage").asBoolean()).isFalse();

        JsonNode pD3 = row(read("/api/payments", D3), paymentId);
        assertThat(pD3.get("customerName").asText()).isEqualTo("Zeta Holdings");
        assertThat(pD3.get("regionCode").asText()).isEqualTo("SOUTH");
        assertThat(pD3.get("status").asText()).isEqualTo("ACTIVE");

        JsonNode pD4 = row(read("/api/payments", D4), paymentId);
        assertThat(pD4.get("status").asText()).isEqualTo("VOIDED");
        assertThat(pD4.get("invoices")).as("the void took the money back off X").isEmpty();

        assertThat(read("/api/payments", D3, "status:eq:VOIDED").get("totalElements").asInt()).isZero();
        assertThat(read("/api/payments", D4, "status:eq:VOIDED").get("totalElements").asInt()).isEqualTo(1);
        assertThat(read("/api/payments", D2, "regionId:eq:" + north.getId()).get("totalElements").asInt())
                .isEqualTo(1);
        assertThat(read("/api/payments", D3, "regionId:eq:" + north.getId()).get("totalElements").asInt())
                .as("Acme moved on D3").isZero();
        assertThat(read("/api/payments", D2, "customerName:contains:zeta").get("totalElements").asInt()).isZero();
        assertThat(read("/api/payments", D3, "customerName:contains:zeta").get("totalElements").asInt())
                .isEqualTo(1);

        JsonNode sumD2 = read("/api/payments/summary", D2);
        assertThat(sumD2.get("totalCollected").decimalValue()).isEqualByComparingTo("400");
        assertThat(sumD2.get("activeCount").asLong()).isEqualTo(1);
        JsonNode sumD4 = read("/api/payments/summary", D4);
        assertThat(sumD4.get("totalCollected").decimalValue()).isEqualByComparingTo("0");
        assertThat(sumD4.get("voidedCount").asLong()).isEqualTo(1);

        JsonNode live = row(read("/api/payments", null), paymentId);
        assertThat(live.get("status").asText()).isEqualTo("VOIDED");
        assertThat(live.get("canManage").asBoolean()).isTrue();
    }

    @Test
    void thePromiseListShowsEachPromiseWithTheLinksAndFiguresOfThatDay() throws Exception {
        assertThat(read("/api/promises", D1).get("totalElements").asInt()).isZero();

        JsonNode prD2 = row(read("/api/promises", D2), promiseId);
        assertThat(prD2.get("customerName").asText()).isEqualTo("Acme Ltd");
        assertThat(prD2.get("status").asText()).isEqualTo("PARTIALLY_KEPT");
        assertThat(prD2.get("fulfilledAmount").decimalValue()).isEqualByComparingTo("400");
        assertThat(prD2.get("remainingAmount").decimalValue()).isEqualByComparingTo("200");
        assertThat(prD2.at("/invoices/0/id").asLong()).isEqualTo(x);
        assertThat(prD2.at("/invoices/0/balance").decimalValue()).isEqualByComparingTo("600");
        assertThat(prD2.at("/payments/0/id").asLong()).isEqualTo(paymentId);
        assertThat(prD2.at("/payments/0/status").asText()).isEqualTo("ACTIVE");
        assertThat(prD2.get("canManage").asBoolean()).isFalse();
        assertThat(prD2.get("canOverride").asBoolean()).isFalse();

        JsonNode prD3 = row(read("/api/promises", D3), promiseId);
        assertThat(prD3.get("customerName").asText()).isEqualTo("Zeta Holdings");
        assertThat(prD3.get("regionCode").asText()).isEqualTo("SOUTH");

        JsonNode prD4 = row(read("/api/promises", D4), promiseId);
        assertThat(prD4.get("status").asText()).as("the voided payment no longer counts").isEqualTo("OPEN");
        assertThat(prD4.get("fulfilledAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(prD4.get("payments")).isEmpty();
        assertThat(prD4.at("/invoices/0/balance").decimalValue()).isEqualByComparingTo("1000");

        String onX = "invoiceId:eq:" + x;
        assertThat(read("/api/promises", D1, onX).get("totalElements").asInt()).isZero();
        assertThat(read("/api/promises", D2, onX).get("totalElements").asInt()).isEqualTo(1);
        assertThat(read("/api/promises", D2, "invoiceId:eq:" + y).get("totalElements").asInt()).isZero();
        String withPayment = "paymentId:eq:" + paymentId;
        assertThat(read("/api/promises", D2, withPayment).get("totalElements").asInt()).isEqualTo(1);
        assertThat(read("/api/promises", D4, withPayment).get("totalElements").asInt())
                .as("the link was dropped with the void").isZero();
        assertThat(read("/api/promises", D2, "paymentId:isEmpty:").get("totalElements").asInt()).isZero();
        assertThat(read("/api/promises", D4, "paymentId:isEmpty:").get("totalElements").asInt()).isEqualTo(1);
        assertThat(read("/api/promises", D3, "status:eq:PARTIALLY_KEPT").get("totalElements").asInt())
                .isEqualTo(1);
        assertThat(read("/api/promises", D4, "status:eq:PARTIALLY_KEPT").get("totalElements").asInt()).isZero();
        assertThat(read("/api/promises", D2, "regionId:eq:" + north.getId()).get("totalElements").asInt())
                .isEqualTo(1);
        assertThat(read("/api/promises", D3, "regionId:eq:" + north.getId()).get("totalElements").asInt())
                .isZero();

        JsonNode sumD2 = read("/api/promises/summary", D2);
        assertThat(sumD2.get("partiallyKeptCount").asLong()).isEqualTo(1);
        assertThat(sumD2.get("fulfilledAmount").decimalValue()).isEqualByComparingTo("400");
        JsonNode sumD4 = read("/api/promises/summary", D4);
        assertThat(sumD4.get("openCount").asLong()).isEqualTo(1);
        assertThat(sumD4.get("openAmount").decimalValue()).isEqualByComparingTo("600");
        assertThat(sumD4.get("fulfilledAmount").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void theDisputeListShowsEachDisputeAsItWasThatDay() throws Exception {
        Long betaDispute = betaDisputesB();
        assertThat(read("/api/disputes", D1).get("totalElements").asInt()).isZero();

        JsonNode d2 = read("/api/disputes", D2);
        assertThat(d2.get("totalElements").asInt()).isEqualTo(1);
        JsonNode bD2 = row(d2, betaDispute);
        assertThat(bD2.get("status").asText()).isEqualTo("PENDING");
        assertThat(bD2.get("customerName").asText()).isEqualTo("Beta Co");
        assertThat(bD2.get("targetAmount").decimalValue()).as("B as it was that day").isEqualByComparingTo("100");
        assertThat(bD2.get("resolvedAt").isNull()).isTrue();
        assertThat(bD2.get("canResolve").asBoolean()).as("nothing is decided in a past view").isFalse();

        JsonNode bD3 = row(read("/api/disputes", D3), betaDispute);
        assertThat(bD3.get("status").asText()).isEqualTo("DENIED");
        assertThat(bD3.get("customerName").asText()).isEqualTo("Beta Group");
        assertThat(bD3.get("targetAmount").decimalValue()).isEqualByComparingTo("150");
        assertThat(bD3.get("adminNotes").asText()).isEqualTo("It was ordered");

        JsonNode d4 = read("/api/disputes", D4);
        assertThat(d4.get("totalElements").asInt()).isEqualTo(2);
        JsonNode acmeD4 = row(d4, disputeId);
        assertThat(acmeD4.get("customerName").asText()).isEqualTo("Zeta Holdings");
        assertThat(acmeD4.get("regionCode").asText()).isEqualTo("SOUTH");
        assertThat(acmeD4.get("status").asText()).isEqualTo("APPROVED");

        assertThat(read("/api/disputes", D2, "status:eq:PENDING").get("totalElements").asInt()).isEqualTo(1);
        assertThat(read("/api/disputes", D3, "status:eq:PENDING").get("totalElements").asInt()).isZero();
        assertThat(read("/api/disputes", D4, "regionId:eq:" + south.getId()).get("totalElements").asInt())
                .isEqualTo(1);
        assertThat(read("/api/disputes?customerId=" + beta.getId(), D4).get("totalElements").asInt())
                .isEqualTo(1);

        JsonNode liveBeta = row(read("/api/disputes", null), betaDispute);
        assertThat(liveBeta.get("customerName").asText()).isEqualTo("Beta Group");
    }

    @Test
    void paymentPromiseAndDisputeExportsAreTheListsAsTheyWereThatDay() throws Exception {
        String paymentsD2 = export("/api/payments/export", D2);
        assertThat(paymentsD2).contains("Acme Ltd").contains("ACTIVE").contains("400.00").doesNotContain("Zeta");
        String paymentsD4 = export("/api/payments/export", D4);
        assertThat(paymentsD4).contains("Zeta Holdings").contains("VOIDED").doesNotContain("ACTIVE");
        assertThat(export("/api/payments/export", D1).lines().count()).as("a header only").isEqualTo(1);

        String promisesD2 = export("/api/promises/export", D2, "invoiceId:eq:" + x);
        assertThat(promisesD2).contains("Acme Ltd").contains("PARTIALLY_KEPT").doesNotContain("Zeta");
        assertThat(export("/api/promises/export", D4)).contains("Zeta Holdings").contains("OPEN");

        Long betaDispute = betaDisputesB();
        String disputesD2 = export("/api/disputes/export", D2);
        assertThat(disputesD2).contains("Beta Co").contains("PENDING").doesNotContain("Beta Group");
        assertThat(disputesD2.lines().count()).isEqualTo(2);
        String disputesD4 = export("/api/disputes/export", D4);
        assertThat(disputesD4).contains("Beta Group").contains("DENIED").contains("Zeta Holdings").contains("APPROVED");
        assertThat(disputesD4).contains(betaDispute.toString());
    }

    @Test
    void theHeaderSaysWhichDayAnsweredAndTheLiveListIsUntouched() throws Exception {
        call(get("/api/invoices"), admin, D2)
                .andExpect(status().isOk())
                .andExpect(header().string("X-View-As-Of", D2.toString()));
        JsonNode live = invoices(null);
        assertThat(row(live, y).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(row(live, x).get("customerName").asText()).isEqualTo("Zeta Holdings");
        assertThat(row(live, x).get("canManage").asBoolean()).isTrue();
        assertThat(texts(read("/api/products", null), "name")).containsExactly("Gadget", "Widget Pro");
    }
}
