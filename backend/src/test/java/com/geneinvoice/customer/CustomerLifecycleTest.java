package com.geneinvoice.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerLifecycleTest extends IntegrationTestBase {

    @Autowired AuditLogRepository auditLogRepository;

    User admin;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
    }

    private ResultActions send(MockHttpServletRequestBuilder req, User caller, Object body) throws Exception {
        return mockMvc.perform(req.with(as(caller))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private JsonNode createCustomer(String name, String email, String username) throws Exception {
        String body = send(post("/api/customers"), admin, Map.of("name", name, "email", email,
                        "username", username, "password", "Demo1234!"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void deletingACustomerIsRecordedAndItsHistoryIsStillReadable() throws Exception {
        long id = createCustomer("Gone Ltd", "ap@gone.test", "gone.login").get("id").asLong();
        send(put("/api/customers/" + id), admin, Map.of("name", "Gone Limited"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/customers/" + id).with(as(admin))).andExpect(status().isOk());

        assertThat(auditLogRepository.findByEntityTypeAndEntityIdIn("CUSTOMER", List.of(id)))
                .extracting(a -> a.getAction())
                .contains("CUSTOMER_CREATED", "CUSTOMER_UPDATED", "CUSTOMER_DELETED");

        String history = mockMvc.perform(get("/api/audit").with(as(admin))
                        .param("entityType", "CUSTOMER").param("entityId", String.valueOf(id)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(history).contains("CUSTOMER_DELETED").contains("Gone Ltd");
    }

    @Test
    void theDeletedCustomersHistoryIsStillClosedToItsOwnFormerLogin() throws Exception {
        JsonNode customer = createCustomer("Gone Ltd", "ap@gone.test", "gone.login");
        long id = customer.get("id").asLong();
        User theirLogin = userRepository.findByUsername("gone.login").orElseThrow();
        RequestPostProcessor asThem = as(theirLogin);

        mockMvc.perform(get("/api/audit").with(asThem)
                        .param("entityType", "CUSTOMER").param("entityId", String.valueOf(id)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/customers/" + id).with(as(admin))).andExpect(status().isOk());

        mockMvc.perform(get("/api/audit").with(asThem)
                        .param("entityType", "CUSTOMER").param("entityId", String.valueOf(id)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingAProductAndAUserIsRecordedToo() throws Exception {
        long productId = product("Widget", "10.00").getId();
        mockMvc.perform(delete("/api/products/" + productId).with(as(admin))).andExpect(status().isOk());
        assertThat(auditLogRepository.findByEntityTypeAndEntityIdIn("PRODUCT", List.of(productId)))
                .extracting(a -> a.getAction()).contains("PRODUCT_DELETED");

        User spare = user("sid.spare", "VIEWER");
        mockMvc.perform(delete("/api/users/" + spare.getId()).with(as(admin))).andExpect(status().isOk());
        assertThat(auditLogRepository.findByEntityTypeAndEntityIdIn("USER", List.of(spare.getId())))
                .extracting(a -> a.getAction()).contains("USER_DELETED");
    }

    @Test
    void aCustomersLoginCannotBeDeletedOnItsOwn() throws Exception {
        createCustomer("Orphan Ltd", "ap@orphan.test", "orphan.login");
        User theirLogin = userRepository.findByUsername("orphan.login").orElseThrow();

        mockMvc.perform(delete("/api/users/" + theirLogin.getId()).with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("This is a customer's login; delete the customer instead"));

        assertThat(userRepository.findById(theirLogin.getId())).isPresent();
    }

    @Test
    void twoCustomersCannotShareOneEmailAddress() throws Exception {
        createCustomer("First Ltd", "ap@shared.test", "first.login");

        send(post("/api/customers"), admin, Map.of("name", "Second Ltd", "email", "AP@Shared.test",
                        "username", "second.login", "password", "Demo1234!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email already exists"));

        assertThat(customerRepository.findAll()).filteredOn(c -> "ap@shared.test".equals(c.getEmail()))
                .hasSize(1);
    }

    @Test
    void anEmailFreedByADeletedLoginIsStillTheCustomersOwn() throws Exception {
        long id = createCustomer("Orphan Ltd", "ap@orphan.test", "orphan.login").get("id").asLong();
        userRepository.deleteById(userRepository.findByUsername("orphan.login").orElseThrow().getId());

        send(post("/api/customers"), admin, Map.of("name", "Second Ltd", "email", "ap@orphan.test",
                        "username", "second.login", "password", "Demo1234!"))
                .andExpect(status().isBadRequest());

        assertThat(customerRepository.findById(id).orElseThrow().getEmail()).isEqualTo("ap@orphan.test");
    }

    @Test
    void oneCustomerCannotBeEditedOntoAnothersEmailAddress() throws Exception {
        createCustomer("First Ltd", "ap@first.test", "first.login");
        long second = createCustomer("Second Ltd", "ap@second.test", "second.login").get("id").asLong();

        send(put("/api/customers/" + second), admin,
                Map.of("name", "Second Ltd", "email", "AP@First.test"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email already exists"));
    }

    @Test
    void aCustomerCanBeSavedWithTheEmailItAlreadyHas() throws Exception {
        long id = createCustomer("First Ltd", "ap@first.test", "first.login").get("id").asLong();

        send(put("/api/customers/" + id), admin,
                Map.of("name", "First Limited", "email", "ap@first.test"))
                .andExpect(status().isOk());
    }

    @Test
    void aUsernameIsTakenWhateverCaseItIsTypedIn() throws Exception {
        send(post("/api/customers"), admin, Map.of("name", "Lookalike Ltd", "email", "ap@look.test",
                        "username", "ADMIN", "password", "Demo1234!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Username already taken"));

        send(post("/api/users"), admin, Map.of("username", "Admin", "password", "Demo1234!",
                        "roleId", role("VIEWER").getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Username already exists"));

        assertThat(userRepository.findAll()).filteredOn(u -> "admin".equalsIgnoreCase(u.getUsername()))
                .hasSize(1);
    }

    @Test
    void signingInFindsTheOneAccountWhateverCaseTheNameIsTypedIn() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "Admin", "password", "admin123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("admin"));
    }
}
