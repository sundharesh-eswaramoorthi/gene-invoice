package com.geneinvoice.poc;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PocAssignmentTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PocService pocService;

    User admin;
    User sales;
    User collections;
    User success;
    User viewer;
    Customer acme;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        success = user("sue.success", DataSeeder.ROLE_SUCCESS_POC);
        viewer = user("vic.viewer", "VIEWER");
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(admin);
    }

    private InvoiceDtos.CreateInvoiceRequest invoiceReq(Long salesPocId) {
        return new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, "n", salesPocId,
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null)));
    }

    @Test
    void creatingAnInvoiceWithoutASalesPocIsRejected() {
        assertThatThrownBy(() -> invoiceService.create(invoiceReq(null)))
                .hasMessageContaining("Sales POC is required");
    }

    @Test
    void creatingAPaymentWithoutACollectionPocIsRejected() {
        assertThatThrownBy(() -> paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("10.00"), "Cash", null, null, null, null)))
                .hasMessageContaining("Collection POC is required");
    }

    @Test
    void aUserWhoseRoleLacksTheAssignabilityPrivilegeCannotBeTheSalesPoc() {
        assertThatThrownBy(() -> invoiceService.create(invoiceReq(viewer.getId())))
                .hasMessageContaining("not assignable as Sales POC");
    }

    @Test
    void anInactiveUserCannotBeAssigned() {
        sales.setActive(false);
        userRepository.save(sales);
        assertThatThrownBy(() -> invoiceService.create(invoiceReq(sales.getId())))
                .hasMessageContaining("inactive");
    }

    @Test
    void aCustomerAccountCannotBeAssignedAsAPoc() {
        User selfService = customerUser("acme.user", acme.getId());
        assertThatThrownBy(() -> invoiceService.create(invoiceReq(selfService.getId())))
                .hasMessageContaining("customer account cannot be assigned");
    }

    @Test
    void aValidSalesPocIsRecordedOnTheInvoice() {
        Invoice created = invoiceService.create(invoiceReq(sales.getId()));
        assertThat(created.getSalesPoc().getId()).isEqualTo(sales.getId());
    }

    @Test
    void assignableListsOnlyActiveUsersHoldingTheMatchingPrivilege() {
        List<String> names = pocService.assignable(PocType.SALES, null, 25).stream()
                .map(User::getUsername).toList();
        assertThat(names).contains("sam.sales", "admin");
        assertThat(names).doesNotContain("vic.viewer", "cara.collections");
    }

    @Test
    void assignableIsSearchableAndBounded() {
        assertThat(pocService.assignable(PocType.SALES, "sam", 25))
                .extracting(User::getUsername).containsExactly("sam.sales");
        assertThat(pocService.assignable(PocType.SALES, null, 1)).hasSize(1);
    }

    @Test
    void assignableSkipsDeactivatedUsers() {
        sales.setActive(false);
        userRepository.save(sales);
        assertThat(pocService.assignable(PocType.SALES, "sam", 25)).isEmpty();
    }

    @Test
    void aCustomerHoldsManyPocsOfEachKindAndTheFirstBecomesPrimary() {
        User second = user("sue.two", DataSeeder.ROLE_SUCCESS_POC);
        CustomerPoc first = pocService.add(acme.getId(), PocType.SUCCESS, success.getId(), false);
        CustomerPoc other = pocService.add(acme.getId(), PocType.SUCCESS, second.getId(), false);

        assertThat(first.isPrimary()).isTrue();
        assertThat(other.isPrimary()).isFalse();
        assertThat(pocService.listFor(acme.getId())).hasSize(2);
    }

    @Test
    void removingThePrimaryPromotesTheNextHolderRatherThanLeavingADanglingPointer() {
        User second = user("sue.two", DataSeeder.ROLE_SUCCESS_POC);
        CustomerPoc primary = pocService.add(acme.getId(), PocType.SUCCESS, success.getId(), true);
        pocService.add(acme.getId(), PocType.SUCCESS, second.getId(), false);

        pocService.remove(acme.getId(), primary.getId());

        assertThat(pocService.primaryFor(acme.getId(), PocType.SUCCESS))
                .isPresent()
                .get()
                .extracting(User::getId).isEqualTo(second.getId());
    }

    @Test
    void removingTheOnlyPocClearsThePrimaryWithoutError() {
        CustomerPoc only = pocService.add(acme.getId(), PocType.COLLECTION, collections.getId(), true);
        pocService.remove(acme.getId(), only.getId());
        assertThat(pocService.primaryFor(acme.getId(), PocType.COLLECTION)).isEmpty();
        assertThat(pocService.listFor(acme.getId())).isEmpty();
    }

    @Test
    void redesignatingThePrimaryLeavesExactlyOne() {
        User second = user("cara.two", DataSeeder.ROLE_COLLECTION_POC);
        pocService.add(acme.getId(), PocType.COLLECTION, collections.getId(), true);
        CustomerPoc other = pocService.add(acme.getId(), PocType.COLLECTION, second.getId(), false);

        pocService.setPrimary(acme.getId(), other.getId());

        assertThat(customerPocRepository.findByCustomerIdAndPocType(acme.getId(), PocType.COLLECTION))
                .filteredOn(CustomerPoc::isPrimary)
                .extracting(p -> p.getUser().getId())
                .containsExactly(second.getId());
    }

    @Test
    void theSameUserCannotHoldTheSameSeatTwice() {
        pocService.add(acme.getId(), PocType.SUCCESS, success.getId(), false);
        assertThatThrownBy(() -> pocService.add(acme.getId(), PocType.SUCCESS, success.getId(), false))
                .hasMessageContaining("already a Customer Success POC");
    }

    @Test
    void salesPocIsPerInvoiceNotPerCustomer() {
        assertThatThrownBy(() -> pocService.add(acme.getId(), PocType.SALES, sales.getId(), false))
                .hasMessageContaining("assigned per invoice");
    }

    @Test
    void deletingAnAssignedUserDeactivatesThemInsteadOfOrphaningTheRecord() throws Exception {
        Invoice inv = invoiceService.create(invoiceReq(sales.getId()));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/users/" + sales.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.deactivated").value(true));

        Invoice reloaded = invoiceRepository.findById(inv.getId()).orElseThrow();
        assertThat(reloaded.getSalesPoc()).isNotNull();
        assertThat(reloaded.getSalesPoc().getId()).isEqualTo(sales.getId());
        assertThat(userRepository.findById(sales.getId()).orElseThrow().isActive()).isFalse();

        mockMvc.perform(get("/api/invoices/" + inv.getId()).with(as(admin)))
                .andExpect(jsonPath("$.salesPoc.username").value("sam.sales"))
                .andExpect(jsonPath("$.salesPoc.active").value(false));
    }

    @Test
    void anUnassignedUserIsStillDeletedOutright() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/users/" + viewer.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        assertThat(userRepository.findById(viewer.getId())).isEmpty();
    }

    @Test
    void takingOverAsPrimaryRecordsTheSeatThatWasDemoted() throws Exception {
        pocService.add(acme.getId(), PocType.COLLECTION, collections.getId(), true);
        User cora = user("cora.collections", DataSeeder.ROLE_COLLECTION_POC);

        pocService.add(acme.getId(), PocType.COLLECTION, cora.getId(), true);

        mockMvc.perform(get("/api/audit").with(as(admin))
                        .param("entityType", "CUSTOMER")
                        .param("entityId", acme.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='POC_PRIMARY_CHANGED')]").isNotEmpty());
    }

    @Test
    void removingThePrimaryPocRecordsWhoWasPromotedInItsPlace() throws Exception {
        CustomerPoc primary = pocService.add(acme.getId(), PocType.COLLECTION, collections.getId(), true);
        User cora = user("cora.collections", DataSeeder.ROLE_COLLECTION_POC);
        pocService.add(acme.getId(), PocType.COLLECTION, cora.getId(), false);

        pocService.remove(acme.getId(), primary.getId());

        assertThat(customerPocRepository.findByCustomerIdAndPocType(acme.getId(), PocType.COLLECTION))
                .singleElement()
                .satisfies(seat -> assertThat(seat.isPrimary()).isTrue());
        mockMvc.perform(get("/api/audit").with(as(admin))
                        .param("entityType", "CUSTOMER")
                        .param("entityId", acme.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='POC_PRIMARY_CHANGED')]").isNotEmpty());
    }

    @Test
    void assigningAndRemovingAPocWritesAuditEntries() throws Exception {
        CustomerPoc seat = pocService.add(acme.getId(), PocType.SUCCESS, success.getId(), true);
        pocService.remove(acme.getId(), seat.getId());

        mockMvc.perform(get("/api/audit").with(as(admin))
                        .param("entityType", "CUSTOMER")
                        .param("entityId", acme.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='POC_ASSIGNED')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.action=='POC_REMOVED')]").isNotEmpty());
    }

    @Test
    void aSelfServiceCustomerSeesNoPocFieldsOnTheirOwnInvoice() throws Exception {
        Invoice inv = invoiceService.create(invoiceReq(sales.getId()));
        User selfService = customerUser("acme.user", acme.getId());

        mockMvc.perform(get("/api/invoices/" + inv.getId()).with(as(selfService)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesPoc").doesNotExist())
                .andExpect(jsonPath("$.pocMissing").doesNotExist());
    }

    @Test
    void aSelfServiceCustomerCannotReadThePocRosterOrTheAssignableDropdown() throws Exception {
        User selfService = customerUser("acme.user", acme.getId());
        mockMvc.perform(get("/api/customers/" + acme.getId() + "/pocs").with(as(selfService)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/pocs/assignable").with(as(selfService)).param("type", "SALES"))
                .andExpect(status().isForbidden());
    }

    @Test
    void thePocColumnsAreAbsentFromASelfServiceCustomersTableSchema() throws Exception {
        User selfService = customerUser("acme.user", acme.getId());
        mockMvc.perform(get("/api/table-schemas/invoices").with(as(selfService)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[?(@.name=='salesPocUserId')]").isEmpty());
        mockMvc.perform(get("/api/table-schemas/invoices").with(as(admin)))
                .andExpect(jsonPath("$.columns[?(@.name=='salesPocUserId')]").isNotEmpty());
    }

    @Test
    void aLegacyInvoiceWithNoPocIsFlaggedAndFindableByTheIsEmptyFilter() throws Exception {
        Invoice legacy = invoiceRepository.save(Invoice.builder()
                .customer(acme).invoiceNumber("LEGACY-1").invoiceDate(java.time.Instant.now())
                .total(new BigDecimal("50.00")).build());
        invoiceService.create(invoiceReq(sales.getId()));

        mockMvc.perform(get("/api/invoices/" + legacy.getId()).with(as(admin)))
                .andExpect(jsonPath("$.pocMissing").value(true));

        mockMvc.perform(get("/api/invoices").with(as(admin))
                        .param("filter", "salesPocUserId:isEmpty:"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].invoiceNumber").value("LEGACY-1"));
    }

    @Test
    void aLegacyInvoiceRemainsPayable() {
        Invoice legacy = invoiceRepository.save(Invoice.builder()
                .customer(acme).invoiceNumber("LEGACY-2").invoiceDate(java.time.Instant.now())
                .total(new BigDecimal("50.00")).build());

        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("50.00"), "Cash", null, List.of(legacy.getId()),
                collections.getId(), null));

        assertThat(invoiceRepository.findById(legacy.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void thePocRosterEndpointsRoundTrip() throws Exception {
        String body = mockMvc.perform(post("/api/customers/" + acme.getId() + "/pocs").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("pocType", "COLLECTION", "userId", collections.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primary").value(true))
                .andReturn().getResponse().getContentAsString();
        Long seatId = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(get("/api/customers/" + acme.getId() + "/pocs").with(as(admin)))
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/customers/" + acme.getId() + "/pocs/" + seatId + "/primary")
                        .with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primary").value(true));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/customers/" + acme.getId() + "/pocs/" + seatId).with(as(admin)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/customers/" + acme.getId() + "/pocs").with(as(admin)))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
