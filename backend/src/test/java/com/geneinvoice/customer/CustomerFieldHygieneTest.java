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

class CustomerFieldHygieneTest extends IntegrationTestBase {

    @Autowired PocService pocService;

    User admin;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        actAs(admin);
    }

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
        assertThat(row).contains(",0.00,0.00,0.00,");
        assertThat(row).contains("sara.success (primary)");
        assertThat(row).contains("gus.gone (primary) (inactive)");
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
