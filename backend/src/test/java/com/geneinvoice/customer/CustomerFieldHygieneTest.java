package com.geneinvoice.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the app stores when a form is submitted with stray spaces or an empty box (CP-12, CP-15),
 * what a DELETE of something that is not there answers (CP-10), and what the customers export
 * carries (CP-07, CP-14).
 */
class CustomerFieldHygieneTest extends IntegrationTestBase {

    @Autowired PocService pocService;

    User admin;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        actAs(admin);
    }

    // ---- CP-12: a username is stored as the customer is told it --------------------

    /**
     * The customer is given "tri_sp" and the row held "  tri_sp  ", so the only string that
     * signed in was one with spaces nobody had been told about (CP-12).
     */
    @Test
    void aCustomersUsernameIsTrimmedOnTheWayIn() throws Exception {
        mockMvc.perform(post("/api/customers").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "TRI Trim", "username", "  tri_sp  ",
                                "password", "Demo1234!", "email", "tri.sp@t.example"))))
                .andExpect(status().isOk());

        assertThat(userRepository.findByUsername("tri_sp"))
                .as("the login is the username the customer was given")
                .isPresent();
        assertThat(userRepository.findByUsername("  tri_sp  ")).isEmpty();
    }

    /** The same on a staff account, which is created through its own endpoint. */
    @Test
    void aStaffUsernameIsTrimmedOnTheWayIn() throws Exception {
        Long roleId = roleRepository.findByName(DataSeeder.ROLE_SALES_POC).orElseThrow().getId();

        mockMvc.perform(post("/api/users").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "  spacey  ", "password", "Demo1234!",
                                "roleId", roleId, "fullName", "Spacey"))))
                .andExpect(status().isOk());

        assertThat(userRepository.findByUsername("spacey")).isPresent();
    }

    // ---- CP-15: trimmed text, and an empty box means "not given" -------------------

    @Test
    void aProductsNameIsTrimmedAndAnEmptyDescriptionIsStoredAsNothing() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/products").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "  TRI Trim  ", "description", "   ",
                                "price", "1.00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("TRI Trim"))
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        Product stored = productRepository.findById(id).orElseThrow();
        assertThat(stored.getName()).isEqualTo("TRI Trim");
        assertThat(stored.getDescription())
                .as("an empty box is nothing, so the table shows its placeholder")
                .isNull();
    }

    @Test
    void aProductEditTrimsTheSameFields() throws Exception {
        Product p = product("Widget", "10.00");

        mockMvc.perform(put("/api/products/" + p.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "  Widget mk2  ", "description", "",
                                "price", "10.00"))))
                .andExpect(status().isOk());

        Product stored = productRepository.findById(p.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("Widget mk2");
        assertThat(stored.getDescription()).isNull();
    }

    @Test
    void aCustomersBlankPhoneAndAddressAreStoredAsNothing() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/customers").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "  Spaced Out  ", "phone", "",
                                "address", "   ", "username", "spaced", "password", "Demo1234!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Spaced Out"))
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        Customer stored = customerRepository.findById(id).orElseThrow();
        assertThat(stored.getName()).isEqualTo("Spaced Out");
        assertThat(stored.getPhone()).isNull();
        assertThat(stored.getAddress()).isNull();
    }

    @Test
    void aCustomerEditTrimsTheSameFields() throws Exception {
        Customer c = customer("Acme Ltd");

        mockMvc.perform(put("/api/customers/" + c.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "  Acme Limited  ", "phone", "  ",
                                "address", ""))))
                .andExpect(status().isOk());

        Customer stored = customerRepository.findById(c.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("Acme Limited");
        assertThat(stored.getPhone()).isNull();
        assertThat(stored.getAddress()).isNull();
    }

    // ---- CP-10: deleting something that is not there ------------------------------

    /**
     * A DELETE of a missing id answered 200 with an empty body while a GET of the same id
     * answered 404, so a caller could not tell a real delete from a no-op (CP-10).
     */
    @Test
    void deletingACustomerThatDoesNotExistIs404() throws Exception {
        mockMvc.perform(delete("/api/customers/999999").with(as(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingAProductThatDoesNotExistIs404() throws Exception {
        mockMvc.perform(delete("/api/products/999999").with(as(admin)))
                .andExpect(status().isNotFound());
    }

    // ---- CP-07, CP-14: what the customers export carries --------------------------

    /**
     * The export is what a collections user takes away, so it carries the fields the screens treat
     * as first-class — the terms and the overdue figure — and its money columns are written the
     * same way throughout (CP-14). A deactivated seat holder is marked as the screens mark them,
     * rather than named as though they were still the person to contact (CP-07).
     */
    @Test
    void theExportCarriesTermsOverdueScaledMoneyAndMarksInactivePocs() throws Exception {
        Customer c = customer("Export Co");
        User success = user("sara.success", DataSeeder.ROLE_SUCCESS_POC);
        User gone = user("gus.gone", DataSeeder.ROLE_COLLECTION_POC);
        pocService.add(c.getId(), PocType.SUCCESS, success.getId(), true);
        pocService.add(c.getId(), PocType.COLLECTION, gone.getId(), true);
        gone.setActive(false);
        userRepository.save(gone);

        String csv = export(List.of(c.getId()));
        List<String> lines = List.of(csv.split("\r\n"));
        assertThat(lines).hasSize(2);

        assertThat(lines.get(0)).isEqualTo("Id,Name,Phone,Email,Payment terms,Credit balance,"
                + "Outstanding,Overdue,Customer Success POCs,Collection POCs");
        String row = lines.get(1);
        // A customer with no invoices owes 0.00, not a bare 0, like every other money column.
        assertThat(row).contains(",0.00,0.00,0.00,");
        assertThat(row).contains("sara.success (primary)");
        assertThat(row).contains("gus.gone (primary) (inactive)");
        // The terms column names whichever applies, falling back to the configured default.
        assertThat(row.split(",")[4]).isNotEmpty();
    }

    private String export(List<Long> ids) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "EXPORT");
        body.put("ids", ids);
        body.put("filters", List.of());
        return mockMvc.perform(post("/api/customers/export").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
