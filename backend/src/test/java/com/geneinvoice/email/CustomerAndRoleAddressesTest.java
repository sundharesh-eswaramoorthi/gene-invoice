package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The addresses an email can use: a customer's several addresses, and a role's mailbox. */
class CustomerAndRoleAddressesTest extends IntegrationTestBase {

    User admin;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
    }

    private ResultActions call(MockHttpServletRequestBuilder req, Object body) throws Exception {
        return mockMvc.perform(req.with(as(admin)).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private Map<String, Object> customerBody(String email, List<String> others) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "Acme Ltd");
        m.put("email", email);
        m.put("additionalEmails", others);
        m.put("username", "acme" + System.nanoTime());
        m.put("password", "Passw0rd!");
        return m;
    }

    @Test
    void aCustomersOtherAddressesAreTidiedAndKeptBesideTheMainOne() throws Exception {
        String json = call(post("/api/customers"), customerBody("billing@acme.test",
                java.util.Arrays.asList("ap@acme.test", "AP@acme.test", "", "BILLING@acme.test", "ceo@acme.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("billing@acme.test"))
                .andReturn().getResponse().getContentAsString();
        JsonNode saved = objectMapper.readTree(json);
        long id = saved.get("id").asLong();
        assertThat(saved.get("additionalEmails").toString()).isEqualTo("[\"ap@acme.test\",\"ceo@acme.test\"]");

        mockMvc.perform(get("/api/emails/addresses").param("customerId", String.valueOf(id)).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addresses.length()").value(3))
                .andExpect(jsonPath("$.addresses[0]").value("billing@acme.test"))
                .andExpect(jsonPath("$.addresses[2]").value("ceo@acme.test"));

        // Leaving the list out keeps it; an empty list clears it.
        Map<String, Object> update = new HashMap<>(Map.of("name", "Acme Ltd", "email", "billing@acme.test"));
        call(put("/api/customers/" + id), update)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.additionalEmails.length()").value(2));
        update.put("additionalEmails", List.of());
        call(put("/api/customers/" + id), update)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.additionalEmails.length()").value(0));
    }

    @Test
    void aCustomersOtherAddressesMustBeEmailAddresses() throws Exception {
        call(post("/api/customers"), customerBody("billing@acme.test", List.of("not an address")))
                .andExpect(status().isBadRequest());
        List<String> tooMany = java.util.stream.IntStream.range(0, 11).mapToObj(i -> "a" + i + "@acme.test").toList();
        call(post("/api/customers"), customerBody("billing@acme.test", tooMany))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A customer can have at most 10 other email addresses"));
    }

    @Test
    void aRoleHasAMailbox() throws Exception {
        String name = "EMAIL_MAILBOX_ROLE_" + System.nanoTime();
        String json = call(post("/api/roles"), Map.of("name", name, "email", "team@gene.test",
                        "privileges", List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("team@gene.test"))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(json).get("id").asLong();

        call(put("/api/roles/" + id), Map.of("name", name, "email", "nope", "privileges", List.of()))
                .andExpect(status().isBadRequest());
        call(put("/api/roles/" + id), Map.of("name", name, "email", "", "privileges", List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist());

        mockMvc.perform(delete("/api/roles/" + id).with(as(admin))).andExpect(status().isOk());
    }
}
