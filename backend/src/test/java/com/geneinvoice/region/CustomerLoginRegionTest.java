package com.geneinvoice.region;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.auth.JwtService;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A customer login has no grants and no choice of region (D17): its rights are its role, in its own customer's
 * region, read on every request. Asking about other regions changes nothing for it, other customers' records
 * stay out of reach whatever their region, and when its customer moves region its rights move with it.
 */
class CustomerLoginRegionTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired DisputeService disputeService;
    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;

    User admin;
    User sales;
    Region north;
    Region south;
    Product widget;
    Customer acme;
    Invoice acmeInvoice;
    User acmeLogin;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        north = region("NORTH");
        south = region("SOUTH");
        grant(admin, north, AccessLevel.APPROVE, RecordScope.ALL);
        grant(admin, south, AccessLevel.APPROVE, RecordScope.ALL);
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        // A POC is assignable only where they hold a grant (§5.6.7).
        grant(sales, north, AccessLevel.READ, RecordScope.BOOK);
        grant(sales, south, AccessLevel.READ, RecordScope.BOOK);
        widget = product("Widget", "100.00");
        acme = customer("Acme Ltd", north);
        acmeInvoice = invoice(acme);
        acmeLogin = customerUser("acme.login", acme.getId());
    }

    private Invoice invoice(Customer c) {
        actAs(admin);
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(), null, null,
                sales.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
    }

    private Long dispute(User login, Invoice target) {
        actAs(login);
        return disputeService.open(new DisputeDtos.CreateDisputeRequest(DisputeTargetType.INVOICE,
                target.getId(), "Wrong quantity", null)).getId();
    }

    /** A real token: the request builds its principal, and so its rights, the way production does. */
    private RequestPostProcessor bearer(User u) {
        User fresh = userRepository.findById(u.getId()).orElseThrow();
        String token = "Bearer " + jwtService.generateToken(fresh.getUsername(), jwtService.claimsFor(fresh));
        SecurityContextHolder.clearContext();
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, token);
            return request;
        };
    }

    private JsonNode read(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * What a region move does to the rows (BE-08 adds the endpoint): every one of the customer's rows moves.
     * Straight in SQL, because no ORM update writes a region (F3); like the move, it then names the rows it
     * changed to {@code HistoryTracker}, so their history follows.
     */
    private void moveAcmeTo(Region to) {
        Map<String, Class<?>> tables = Map.of("invoices", Invoice.class, "payments", Payment.class,
                "payment_promises", PaymentPromise.class, "disputes", Dispute.class);
        tables.forEach((table, type) -> {
            List<Long> ids = jdbc.queryForList("select id from " + table + " where customer_id = ?", Long.class,
                    acme.getId());
            jdbc.update("update " + table + " set region_id = ? where customer_id = ?", to.getId(), acme.getId());
            historyTestSupport.touch(type, ids);
        });
        jdbc.update("update customers set region_id = ? where id = ?", to.getId(), acme.getId());
        historyTestSupport.touch(Customer.class, List.of(acme.getId()));
    }

    @Test
    void askingAboutOtherRegionsChangesNothingForACustomerLogin() throws Exception {
        Customer southCo = customer("South Co", south);
        invoice(southCo);
        RequestPostProcessor login = bearer(acmeLogin);

        for (String path : List.of("/api/invoices", "/api/customers")) {
            JsonNode plain = read(mockMvc.perform(get(path).with(login)).andExpect(status().isOk()));
            for (Region asked : List.of(north, south)) {
                JsonNode narrowed = read(mockMvc.perform(get(path).with(login)
                        .param("regionIds", asked.getId().toString())).andExpect(status().isOk()));
                assertThat(narrowed.get("content")).as(path + " asked about " + asked.getCode())
                        .isEqualTo(plain.get("content"));
                assertThat(narrowed.get("regionIds")).as("a customer login answers no choice of region").isEmpty();
            }
            assertThat(plain.get("totalElements").asLong()).as(path).isEqualTo(1);
        }
        JsonNode tiles = read(mockMvc.perform(get("/api/invoices/summary").with(login)
                .param("regionIds", south.getId().toString())).andExpect(status().isOk()));
        assertThat(tiles.get("count").asLong()).isEqualTo(1);
        mockMvc.perform(get("/api/invoices/" + acmeInvoice.getId()).with(login)
                .param("regionIds", south.getId().toString())).andExpect(status().isOk());
    }

    @Test
    void anotherCustomersInvoiceIsMissingAndTheirDisputeIsForbiddenInEveryRegion() throws Exception {
        Customer neighbour = customer("Neighbour Ltd", north);
        Customer faraway = customer("Faraway Ltd", south);
        Invoice neighbourInvoice = invoice(neighbour);
        Invoice farawayInvoice = invoice(faraway);
        Long neighbourDispute = dispute(customerUser("neighbour.login", neighbour.getId()), neighbourInvoice);
        Long farawayDispute = dispute(customerUser("faraway.login", faraway.getId()), farawayInvoice);
        Long ownDispute = dispute(acmeLogin, acmeInvoice);
        RequestPostProcessor login = bearer(acmeLogin);

        for (Invoice other : List.of(neighbourInvoice, farawayInvoice)) {
            mockMvc.perform(get("/api/invoices/" + other.getId()).with(login)).andExpect(status().isNotFound());
        }
        for (Long other : List.of(neighbourDispute, farawayDispute)) {
            mockMvc.perform(get("/api/disputes/" + other).with(login)).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/invoices/" + acmeInvoice.getId()).with(login)).andExpect(status().isOk());
        mockMvc.perform(get("/api/disputes/" + ownDispute).with(login)).andExpect(status().isOk());
    }

    /**
     * A login whose role may edit its own customer, holding a token issued in NORTH: after the move to SOUTH the
     * same token still edits it, because the rights are rebuilt on every request from the customer's region. A
     * principal built before the move, and so still pinned to NORTH, is refused: the check does read the region.
     */
    @Test
    void aCustomerLoginFollowsItsCustomerToAnotherRegion() throws Exception {
        Set<Privilege> privileges = new HashSet<>(role("CUSTOMER").getPrivileges());
        privileges.add(privilegeRepository.findByName(Privileges.CUSTOMER_MANAGE).orElseThrow());
        Role selfEditor = roleRepository.findByName("CUSTOMER_SELF_EDITOR").orElseGet(() -> Role.builder()
                .name("CUSTOMER_SELF_EDITOR").description("Customer login that may edit itself").build());
        selfEditor.setPrivileges(privileges);
        roleRepository.save(selfEditor);
        acmeLogin.setRole(selfEditor);
        userRepository.save(acmeLogin);
        assertThat(principals.principalFor(acmeLogin.getId()).orElseThrow().getRights().grantedRegions())
                .containsExactly(north.getId());

        RequestPostProcessor token = bearer(acmeLogin);
        RequestPostProcessor pinnedToNorth = as(acmeLogin);
        moveAcmeTo(south);

        assertThat(principals.principalFor(acmeLogin.getId()).orElseThrow().getRights().grantedRegions())
                .containsExactly(south.getId());
        mockMvc.perform(put("/api/customers/" + acme.getId()).with(pinnedToNorth)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "Acme North"))))
                .andExpect(status().isForbidden());
        JsonNode renamed = read(mockMvc.perform(put("/api/customers/" + acme.getId()).with(token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "Acme South"))))
                .andExpect(status().isOk()));
        assertThat(renamed.get("name").asText()).isEqualTo("Acme South");
        assertThat(renamed.get("regionId").asLong()).isEqualTo(south.getId());

        JsonNode invoices = read(mockMvc.perform(get("/api/invoices").with(token)).andExpect(status().isOk()));
        assertThat(invoices.get("content")).extracting(n -> n.get("regionCode").asText()).containsExactly("SOUTH");
    }
}
