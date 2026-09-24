package com.geneinvoice.region;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WHICH BRANCH A RECORD IS FILED IN IS THE COMPANY'S OWN BUSINESS, AND A CUSTOMER LOGIN IS NOT
 * TOLD IT (B1, AUTH-08).
 *
 * <p>B1 put regionId and regionName on every customer-facing row and on every customer-facing
 * schema, so a customer reading their own invoice learned which internal branch handles them, and
 * a customer filtering on regionId could walk the branch id space one integer at a time and count
 * the branches. Neither is a fact they can act on and neither was theirs before regions existed.
 *
 * <p>The answer has two halves and BOTH are asserted here, because either alone is a hole: the
 * columns are marked {@code pocRestricted()} so {@code TableSchema.visibleTo(isCustomer())} strikes
 * them from the schema, the sort and the filter — and the DTO empties the same two slots, so the
 * payload cannot hand over what the column list no longer admits to having. The mechanism is the
 * one POC identity already uses; nothing new was invented for this.
 *
 * <p>Every case is paired with the SAME read as a staff caller, because a test that only asserts
 * the absence would pass just as well against a build where the columns went away for everybody.
 */
class CustomerRegionBlindnessTest extends IntegrationTestBase {

    static final Instant RAISED = Instant.parse("2026-03-01T09:00:00Z");

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;

    User admin;
    User sales;
    User collections;
    Customer acme;
    User acmeLogin;
    Product widget;
    Invoice invoice;
    Payment payment;
    PromiseDtos.PromiseDto promise;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        acmeLogin = customerUser("acme.login", acme.getId());
        widget = product("Widget", "100.00");
        actAs(admin);
        invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                acme.getId(), RAISED, null, null, null, sales.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("100.00")))));
        payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("10.00"), "CASH", null,
                List.of(invoice.getId()), collections.getId(), null));
        promise = promiseService.create(new PromiseDtos.CreatePromiseRequest(
                acme.getId(), new BigDecimal("90.00"), LocalDate.now(ZoneOffset.UTC).plusDays(7),
                collections.getId(), null, List.of(invoice.getId())));
    }

    /**
     * THE SCHEMA HALF. The branch columns are not offered to a customer login on any of the four
     * lists they can reach, and they are still offered to staff, so the column did not simply
     * disappear (B1, AUTH-08).
     */
    @Test
    void acustomerLoginIsOfferedNoBranchColumnOnAnyListItCanReach() throws Exception {
        for (String entity : List.of("invoices", "payments", "promises", "customers", "disputes")) {
            assertThat(columns("/api/table-schemas/" + entity, acmeLogin))
                    .describedAs(entity + " as a customer login")
                    .doesNotContain("regionId", "regionName");
            assertThat(columns("/api/table-schemas/" + entity, admin))
                    .describedAs(entity + " as staff")
                    .contains("regionId", "regionName");
        }
    }

    /**
     * THE FILTER AND THE SORT. Striking the column from the schema is what makes the branch id
     * space unprobeable: the request is parsed against the caller's OWN schema, so a customer
     * naming the column is told it is not a column at all — the same 400 any invented name gets,
     * which is the answer that leaks least (B1, AUTH-08).
     */
    @Test
    void acustomerLoginCannotFilterOrSortByBranchAndStaffStillCan() throws Exception {
        Long region = defaultRegion().getId();
        mockMvc.perform(get("/api/invoices?filter=regionId:eq:" + region).with(as(acmeLogin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown column: regionId"));
        mockMvc.perform(get("/api/invoices?sort=regionName,asc").with(as(acmeLogin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown column: regionName"));
        mockMvc.perform(get("/api/customers?filter=regionId:eq:" + region).with(as(acmeLogin)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/payments?filter=regionId:eq:" + region).with(as(acmeLogin)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/promises?filter=regionId:eq:" + region).with(as(acmeLogin)))
                .andExpect(status().isBadRequest());

        // Staff are unaffected: this is the read B1 built the column for.
        mockMvc.perform(get("/api/invoices?filter=regionId:eq:" + region).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/invoices?sort=regionName,asc").with(as(admin)))
                .andExpect(status().isOk());
    }

    /**
     * THE DTO HALF, on every list and every detail read a customer login has. Null and not absent,
     * because null is already this codebase's word for "not asked" and the wire shape does not
     * change (B1, AUTH-08).
     */
    @Test
    void acustomerLoginIsToldNoBranchOnItsOwnRowsWhileStaffStillAre() throws Exception {
        for (String path : List.of(
                "/api/invoices", "/api/payments", "/api/promises", "/api/customers")) {
            JsonNode mine = read(mockMvc.perform(get(path).with(as(acmeLogin)))
                    .andExpect(status().isOk()));
            assertThat(mine.get("content")).describedAs(path).isNotEmpty();
            mine.get("content").forEach(row -> {
                assertThat(row.get("regionId").isNull()).describedAs(path + " regionId").isTrue();
                assertThat(row.get("regionName").isNull()).describedAs(path + " regionName").isTrue();
            });

            JsonNode theirs = read(mockMvc.perform(get(path).with(as(admin)))
                    .andExpect(status().isOk()));
            assertThat(theirs.get("content")).describedAs(path).isNotEmpty();
            theirs.get("content").forEach(row -> {
                assertThat(row.get("regionId").asLong()).describedAs(path + " as staff")
                        .isEqualTo(defaultRegion().getId());
                assertThat(row.get("regionName").asText()).isEqualTo(defaultRegion().getName());
            });
        }

        for (String path : List.of(
                "/api/invoices/" + invoice.getId(),
                "/api/payments/" + payment.getId(),
                "/api/promises/" + promise.id(),
                "/api/customers/" + acme.getId())) {
            JsonNode mine = read(mockMvc.perform(get(path).with(as(acmeLogin)))
                    .andExpect(status().isOk()));
            assertThat(mine.get("regionId").isNull()).describedAs(path).isTrue();
            assertThat(mine.get("regionName").isNull()).describedAs(path).isTrue();

            JsonNode theirs = read(mockMvc.perform(get(path).with(as(admin)))
                    .andExpect(status().isOk()));
            assertThat(theirs.get("regionId").asLong()).describedAs(path + " as staff")
                    .isEqualTo(defaultRegion().getId());
            assertThat(theirs.get("regionName").asText()).isEqualTo(defaultRegion().getName());
        }
    }

    /**
     * AND NOTHING ELSE MOVED. Emptying two slots of a twenty-field record by hand is one
     * mistyped component away from handing the customer somebody else's number in a field that
     * happens to have the same type, and no assertion about regionId would ever notice. So the
     * two reads of the SAME record are compared field by field and the set of fields that differ
     * is pinned: B1's two region slots, and the POC slots that were already blind to a customer
     * login before regions existed (B1, AUTH-08).
     */
    @Test
    void thebranchAndThePocAreTheOnlyThingsACustomersCopyOfARecordIsMissing() throws Exception {
        assertThat(differingFields("/api/invoices/" + invoice.getId()))
                .containsExactlyInAnyOrder("regionId", "regionName", "salesPoc", "pocMissing");
        assertThat(differingFields("/api/payments/" + payment.getId()))
                .containsExactlyInAnyOrder("regionId", "regionName", "collectionPoc", "pocMissing");
        // createdByUserId is PaymentPromiseService.toDto's own showStaff rule and predates this
        // change: a customer login is not told which member of staff raised the promise.
        assertThat(differingFields("/api/promises/" + promise.id()))
                .containsExactlyInAnyOrder("regionId", "regionName", "collectionPoc",
                        "createdByUserId");
        assertThat(differingFields("/api/customers/" + acme.getId()))
                .containsExactlyInAnyOrder("regionId", "regionName",
                        "successPocs", "collectionPocs", "pocMissing");
    }

    /** Which keys of one record read two ways carry different values. */
    private List<String> differingFields(String path) throws Exception {
        JsonNode mine = read(mockMvc.perform(get(path).with(as(acmeLogin)))
                .andExpect(status().isOk()));
        JsonNode theirs = read(mockMvc.perform(get(path).with(as(admin)))
                .andExpect(status().isOk()));
        assertThat(fieldNames(mine)).describedAs(path + " field set")
                .containsExactlyElementsOf(fieldNames(theirs));
        return fieldNames(mine).stream()
                .filter(name -> !mine.get(name).equals(theirs.get(name)))
                .toList();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private List<String> columns(String path, User who) throws Exception {
        JsonNode schema = read(mockMvc.perform(get(path).with(as(who))).andExpect(status().isOk()));
        return java.util.stream.StreamSupport
                .stream(schema.get("columns").spliterator(), false)
                .map(c -> c.get("name").asText()).toList();
    }

    private JsonNode read(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
