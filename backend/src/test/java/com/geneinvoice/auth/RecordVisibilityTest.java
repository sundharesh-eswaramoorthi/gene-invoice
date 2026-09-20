package com.geneinvoice.auth;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a caller is told about a record that is not theirs (AUTH-08), and who may read the shape of
 * a table (AUTH-07).
 */
class RecordVisibilityTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired PocService pocService;
    @Autowired PrivilegeRepository privilegeRepository;

    User admin;
    User collections;
    Customer acme;
    Customer globex;
    User globexLogin;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        globex = customer("Globex Corp");
        globexLogin = customerUser("globex.login", globex.getId());
        widget = product("Widget", "100.00");
        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, collections.getId(), true);
    }

    // ---- AUTH-08: a foreign record answers as a missing one -------------------------

    /**
     * 403 for another customer's record and 404 for one that does not exist let a customer login
     * walk the id space and count records that are none of theirs. One answer for both, as
     * /api/customers and /api/documents already give (AUTH-08).
     */
    @Test
    void aCustomerCannotTellAForeignInvoiceFromAMissingOne() throws Exception {
        Invoice foreign = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                acme.getId(), null, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("100.00")))));

        mockMvc.perform(get("/api/invoices/" + foreign.getId()).with(as(globexLogin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/invoices/999999").with(as(globexLogin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aCustomerCannotTellAForeignPaymentFromAMissingOne() throws Exception {
        Payment foreign = paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("50.00"), "Cash", null, null,
                collections.getId(), null));

        mockMvc.perform(get("/api/payments/" + foreign.getId()).with(as(globexLogin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/payments/999999").with(as(globexLogin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aCustomerCannotTellAForeignPromiseFromAMissingOne() throws Exception {
        PromiseDtos.PromiseDto foreign = promiseService.create(new PromiseDtos.CreatePromiseRequest(
                acme.getId(), new BigDecimal("100.00"),
                LocalDate.now(ZoneOffset.UTC).plusDays(1), null, null, List.of()));

        mockMvc.perform(get("/api/promises/" + foreign.id()).with(as(globexLogin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/promises/999999").with(as(globexLogin)))
                .andExpect(status().isNotFound());
    }

    /** A customer still reads its own records, which is the point of the login. */
    @Test
    void aCustomerStillReadsItsOwnInvoice() throws Exception {
        Invoice own = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                globex.getId(), null, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("100.00")))));

        mockMvc.perform(get("/api/invoices/" + own.getId()).with(as(globexLogin)))
                .andExpect(status().isOk());
    }

    // ---- AUTH-07: a table's shape needs that table's view privilege ------------------

    /** A role holding no privileges at all: it may sign in and nothing more. */
    private User zeroPrivilegeUser() {
        Role role = roleRepository.save(Role.builder()
                .name("ZERO_PRIVILEGE")
                .description("Holds nothing at all")
                .privileges(new HashSet<>())
                .build());
        return user("zed.zero", role.getName());
    }

    /**
     * The schema endpoints carried no check of any kind, so a caller who is 403 on GET /api/users
     * could still read the users table's columns, types, operators and sortability — and every
     * other table's in one call (AUTH-07).
     */
    @Test
    void aCallerWithNoPrivilegesCannotReadATableSchema() throws Exception {
        User zed = zeroPrivilegeUser();

        mockMvc.perform(get("/api/table-schemas/users").with(as(zed)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theAllSchemasCallReturnsOnlyWhatTheCallerMaySee() throws Exception {
        Role role = roleRepository.save(Role.builder()
                .name("PRODUCTS_ONLY")
                .description("Reads the catalogue and nothing else")
                .privileges(Stream.of(Privileges.PRODUCT_VIEW)
                        .map(n -> privilegeRepository.findByName(n).orElseThrow())
                        .collect(Collectors.toCollection(HashSet::new)))
                .build());
        User pam = user("pam.products", role.getName());

        String body = mockMvc.perform(get("/api/table-schemas/all").with(as(pam)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var schemas = objectMapper.readTree(body);
        assertThat(schemas.fieldNames()).toIterable().containsExactly("products");
        // The list of table names agrees with it, rather than naming tables it would then refuse.
        String entities = mockMvc.perform(get("/api/table-schemas").with(as(pam)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readValue(entities, List.class)).containsExactly("products");

        mockMvc.perform(get("/api/table-schemas/products").with(as(pam)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/table-schemas/users").with(as(pam)))
                .andExpect(status().isForbidden());
    }

    /** An administrator is unaffected: every table is still there. */
    @Test
    void anAdministratorStillReadsEverySchema() throws Exception {
        String body = mockMvc.perform(get("/api/table-schemas/all").with(as(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).size())
                .isEqualTo(com.geneinvoice.common.query.TableSchemas.entities().size());
    }
}
