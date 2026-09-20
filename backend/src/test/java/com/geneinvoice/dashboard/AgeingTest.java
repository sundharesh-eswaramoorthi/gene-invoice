package com.geneinvoice.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature B: the ageing chart measures days past the due date, not days since the invoice date
 * (D4). Every bucket edge is here at its exact day, the five of them add up to what the rest of
 * the dashboard calls outstanding (AC-B3), and each one's dates fetch exactly its own rows from
 * the invoice list (AC-B6).
 */
class AgeingTest extends IntegrationTestBase {

    static final LocalDate TODAY = InvoiceDates.today();

    /** The invoice list filter behind every bucket: what is still owed (AC-B2). */
    static final String OPEN_ONLY = "status:in:UNPAID,PARTIALLY_PAID";

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired DashboardService dashboardService;
    @Autowired DataSource dataSource;

    User admin;
    User sales;
    User collector;
    Customer acme;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        collector = user("cora.collect", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(admin);
    }

    /** Raised well before any due date a test asks for, so only the due date is in play. */
    private Invoice dueOn(LocalDate due, String amount) {
        Instant raised = TODAY.minusDays(400).atStartOfDay(ZoneOffset.UTC).toInstant();
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), raised, due,
                null, null, sales.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal(amount)))));
    }

    private void pay(String amount, List<Long> invoiceIds) {
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal(amount), "Cash", null, invoiceIds, collector.getId(), null));
    }

    private List<DashboardDtos.AgeBucket> buckets() {
        return dashboardService.outstandingByAge(TODAY).buckets();
    }

    // ---- AC-B1, AC-B5, AC-B8: the labels and every edge -----------------------------

    @Test
    void theBucketsAreLabelledByLatenessAndAnInvoiceDueTodayIsNotYetLate() {
        dueOn(TODAY.plusDays(7), "10.00");
        dueOn(TODAY, "20.00");             // due today: not yet due (AC-A9)
        dueOn(TODAY.minusDays(1), "30.00");
        dueOn(TODAY.minusDays(30), "40.00");
        dueOn(TODAY.minusDays(31), "50.00");
        dueOn(TODAY.minusDays(60), "60.00");
        dueOn(TODAY.minusDays(61), "70.00");
        dueOn(TODAY.minusDays(90), "80.00");
        dueOn(TODAY.minusDays(91), "90.00");

        List<DashboardDtos.AgeBucket> buckets = buckets();

        assertThat(buckets).extracting(DashboardDtos.AgeBucket::label)
                .containsExactly("Not yet due", "1–30 days", "31–60 days", "61–90 days", "Over 90 days");
        assertThat(buckets).extracting(b -> b.amount().toPlainString())
                .containsExactly("30.00", "70.00", "110.00", "150.00", "90.00");
        assertThat(buckets).extracting(DashboardDtos.AgeBucket::count)
                .containsExactly(2L, 2L, 2L, 2L, 1L);
    }

    @Test
    void theDayBoundsOfEachBucketAreTheOpenEndsAndTheRestAreContiguous() {
        List<DashboardDtos.AgeBucket> buckets = buckets();

        assertThat(buckets).extracting(DashboardDtos.AgeBucket::fromDays)
                .containsExactly(null, 1, 31, 61, 91);
        assertThat(buckets).extracting(DashboardDtos.AgeBucket::toDays)
                .containsExactly(0, 30, 60, 90, null);
    }

    /** AC-B2: the balance, never the total, and cancelled or settled invoices do not appear. */
    @Test
    void onlyWhatIsStillOwedIsCounted() {
        Invoice partly = dueOn(TODAY.minusDays(10), "200.00");
        pay("50.00", List.of(partly.getId()));
        Invoice settled = dueOn(TODAY.minusDays(10), "100.00");
        pay("100.00", List.of(settled.getId()));
        Invoice cancelled = dueOn(TODAY.minusDays(10), "900.00");
        invoiceService.cancel(cancelled.getId());

        List<DashboardDtos.AgeBucket> buckets = buckets();

        assertThat(buckets.get(1).amount()).isEqualByComparingTo("150.00");
        assertThat(buckets.get(1).count()).isEqualTo(1);
        assertThat(total(buckets)).isEqualByComparingTo("150.00");
    }

    // ---- AC-B3: the buckets reconcile with outstanding --------------------------------

    @Test
    void theBucketsAddUpToWhatTheDashboardCallsOutstanding() throws Exception {
        dueOn(TODAY.plusDays(5), "100.00");
        dueOn(TODAY.minusDays(15), "200.00");
        Invoice partly = dueOn(TODAY.minusDays(95), "300.00");
        pay("120.00", List.of(partly.getId()));
        Invoice cancelled = dueOn(TODAY.minusDays(50), "900.00");
        invoiceService.cancel(cancelled.getId());

        BigDecimal fromBuckets = total(buckets());

        assertThat(fromBuckets).isEqualByComparingTo("480.00");
        assertThat(dashboardService.topOutstanding(20).customers()).singleElement()
                .satisfies(c -> assertThat(c.outstanding()).isEqualByComparingTo(fromBuckets));
        JsonNode tiles = getJson("/api/invoices/summary", admin);
        assertThat(tiles.get("outstanding").decimalValue()).isEqualByComparingTo(fromBuckets);
    }

    /** The reconciliation has to hold when nothing is late at all. */
    @Test
    void theBucketsStillAddUpWhenEveryInvoiceIsNotYetDue() throws Exception {
        dueOn(TODAY, "100.00");
        dueOn(TODAY.plusDays(60), "250.00");

        List<DashboardDtos.AgeBucket> buckets = buckets();

        assertThat(buckets.get(0).amount()).isEqualByComparingTo("350.00");
        assertThat(buckets.subList(1, buckets.size()))
                .allSatisfy(b -> assertThat(b.amount()).isEqualByComparingTo("0.00"));
        assertThat(total(buckets)).isEqualByComparingTo("350.00");
        assertThat(getJson("/api/invoices/summary", admin).get("outstanding").decimalValue())
                .isEqualByComparingTo("350.00");
    }

    /**
     * The one invoice that can have no due date at all is one the upgrade could not reach (§2.5),
     * and it still owes money. It belongs with what is not yet due — a row in no bucket would
     * take its balance off a chart that has to add up to outstanding (AC-B3), and out of both
     * halves of the overdue filter, since SQL knows nothing of a null.
     */
    @Test
    void anInvoiceWithNoDueDateAtAllIsStillCountedAndStillListed() throws Exception {
        dueOn(TODAY.minusDays(10), "200.00");
        Invoice undated = dueOn(TODAY.plusDays(10), "500.00");

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("alter table invoices alter column due_date set null");
            st.execute("update invoices set due_date = null where id = " + undated.getId());
            try {
                List<DashboardDtos.AgeBucket> buckets = buckets();

                assertThat(buckets.get(0).amount()).isEqualByComparingTo("500.00");
                assertThat(buckets.get(0).count()).isEqualTo(1);
                assertThat(total(buckets)).isEqualByComparingTo("700.00");
                assertThat(getJson("/api/invoices/summary", admin).get("outstanding").decimalValue())
                        .isEqualByComparingTo("700.00");

                // And the two halves of the overdue filter still make up the whole list.
                long late = getJson("/api/invoices?filter=overdue:eq:true", admin)
                        .get("totalElements").asLong();
                long inTime = getJson("/api/invoices?filter=overdue:eq:false", admin)
                        .get("totalElements").asLong();
                assertThat(late).isEqualTo(1);
                assertThat(late + inTime)
                        .isEqualTo(getJson("/api/invoices", admin).get("totalElements").asLong());
            } finally {
                // The other tests get a table every invoice can be written to, as before.
                st.execute("update invoices set due_date = date '" + undated.getDueDate()
                        + "' where id = " + undated.getId());
                st.execute("alter table invoices alter column due_date date not null");
            }
        }
    }

    @Test
    void anEmptyBookReadsAsFiveZeroes() {
        List<DashboardDtos.AgeBucket> buckets = buckets();

        assertThat(buckets).hasSize(5).allSatisfy(b -> {
            assertThat(b.amount()).isEqualByComparingTo("0.00");
            assertThat(b.count()).isZero();
        });
    }

    // ---- AC-B6: clicking a bucket lands on its own rows --------------------------------

    @Test
    void eachBucketsDatesFetchExactlyTheInvoicesBehindIt() throws Exception {
        dueOn(TODAY.plusDays(7), "10.00");
        dueOn(TODAY, "20.00");
        dueOn(TODAY.minusDays(1), "30.00");
        dueOn(TODAY.minusDays(45), "50.00");
        dueOn(TODAY.minusDays(75), "70.00");
        dueOn(TODAY.minusDays(200), "90.00");
        Invoice settled = dueOn(TODAY.minusDays(45), "100.00");
        pay("100.00", List.of(settled.getId()));

        for (DashboardDtos.AgeBucket bucket : buckets()) {
            JsonNode page = getJson("/api/invoices?" + deepLink(bucket), admin);

            assertThat(page.get("totalElements").asLong())
                    .describedAs("rows behind '%s'", bucket.label())
                    .isEqualTo(bucket.count());
            BigDecimal owed = BigDecimal.ZERO;
            for (JsonNode row : page.get("content")) {
                owed = owed.add(row.get("balance").decimalValue());
            }
            assertThat(owed).describedAs("money behind '%s'", bucket.label())
                    .isEqualByComparingTo(bucket.amount());
        }
    }

    /** AC-B4: the chart keeps the scope and coverage of the list it summarises. */
    @Test
    void aCustomerLoginSeesOnlyItsOwnAgeingAndSaysSo() throws Exception {
        Customer globex = customer("Globex Corp");
        dueOn(TODAY.minusDays(10), "100.00");
        invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(globex.getId(),
                TODAY.minusDays(400).atStartOfDay(ZoneOffset.UTC).toInstant(),
                TODAY.minusDays(10), null, null, sales.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("700.00")))));
        User acmeLogin = customerUser("acme.login", acme.getId());

        JsonNode own = getJson("/api/dashboard/outstanding-by-age", acmeLogin);

        assertThat(own.get("coverage").asText()).isEqualTo("OWN");
        assertThat(own.get("buckets").get(1).get("label").asText()).isEqualTo("1–30 days");
        assertThat(own.get("buckets").get(1).get("amount").decimalValue())
                .isEqualByComparingTo("100.00");

        JsonNode everything = getJson("/api/dashboard/outstanding-by-age", admin);
        assertThat(everything.get("coverage").asText()).isEqualTo("ALL");
        assertThat(everything.get("buckets").get(1).get("amount").decimalValue())
                .isEqualByComparingTo("800.00");
    }

    @Test
    void theBucketsCarryTheDatesTheInvoiceListTakes() throws Exception {
        JsonNode buckets = getJson("/api/dashboard/outstanding-by-age", admin).get("buckets");

        assertThat(buckets.get(0).get("dueDateFrom").asText()).isEqualTo(TODAY.toString());
        assertThat(buckets.get(0).get("dueDateTo").isNull()).isTrue();
        assertThat(buckets.get(1).get("dueDateFrom").asText()).isEqualTo(TODAY.minusDays(30).toString());
        assertThat(buckets.get(1).get("dueDateTo").asText()).isEqualTo(TODAY.minusDays(1).toString());
        assertThat(buckets.get(4).get("dueDateFrom").isNull()).isTrue();
        assertThat(buckets.get(4).get("dueDateTo").asText()).isEqualTo(TODAY.minusDays(91).toString());
    }

    // ---- helpers ------------------------------------------------------------------------

    /** A bucket's bounds as the invoice list's filter chips, which is what the chart links to. */
    private static String deepLink(DashboardDtos.AgeBucket bucket) {
        List<String> chips = new ArrayList<>(List.of("filter=" + OPEN_ONLY));
        if (bucket.dueDateFrom() != null && bucket.dueDateTo() != null) {
            chips.add("filter=dueDate:between:" + bucket.dueDateFrom() + "," + bucket.dueDateTo());
        } else if (bucket.dueDateFrom() != null) {
            chips.add("filter=dueDate:gte:" + bucket.dueDateFrom());
        } else {
            chips.add("filter=dueDate:lte:" + bucket.dueDateTo());
        }
        return String.join("&", chips);
    }

    private static BigDecimal total(List<DashboardDtos.AgeBucket> buckets) {
        return buckets.stream().map(DashboardDtos.AgeBucket::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Read as UTF-8: the bucket labels carry an en dash, and MockMvc's response does not say so. */
    private JsonNode getJson(String path, User caller) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(path).with(as(caller)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }
}
