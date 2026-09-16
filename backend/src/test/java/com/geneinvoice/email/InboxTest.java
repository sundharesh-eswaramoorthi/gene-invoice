package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Each user's Inbox: what they received, newest first, with read status that is theirs alone. */
class InboxTest extends IntegrationTestBase {

    User admin;
    User cara;
    User cole;
    User sam;
    Role collections;
    Customer acme;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        cara = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        cole = user("cole.collections", DataSeeder.ROLE_COLLECTION_POC);
        sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        collections = role(DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
    }

    private long send(String subject, Map<String, Object> to) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", acme.getId());
        body.put("from", Map.of("type", "USER", "id", admin.getId()));
        body.put("to", to);
        body.put("subject", subject);
        String json = mockMvc.perform(post("/api/emails").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asLong();
    }

    private JsonNode inbox(User u, String... params) throws Exception {
        var req = get("/api/inbox").with(as(u));
        for (int i = 0; i < params.length; i += 2) req.param(params[i], params[i + 1]);
        return objectMapper.readTree(mockMvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void theInboxListsWhatTheUserReceivedDirectlyOrThroughARoleNewestFirst() throws Exception {
        long direct = send("Direct", Map.of("userIds", List.of(cara.getId())));
        long viaRole = send("Via role", Map.of("roleIds", List.of(collections.getId())));
        long both = send("Both", Map.of("userIds", List.of(cara.getId()), "roleIds", List.of(collections.getId())));
        send("Not for Cara", Map.of("userIds", List.of(sam.getId())));

        JsonNode page = inbox(cara);
        assertThat(page.get("totalElements").asInt()).isEqualTo(3);
        List<Long> ids = new java.util.ArrayList<>();
        page.get("content").forEach(item -> ids.add(item.get("id").asLong()));
        assertThat(ids).containsExactly(both, viaRole, direct);
        JsonNode newest = page.get("content").get(0);
        assertThat(newest.get("read").asBoolean()).isFalse();
        assertThat(newest.get("email").get("subject").asText()).isEqualTo("Both");
        assertThat(newest.get("email").get("from").get("name").asText()).isEqualTo("System Administrator");

        assertThat(inbox(cole).get("totalElements").asInt()).isEqualTo(2);
        assertThat(inbox(sam).get("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void readingAnEmailMarksItReadForThatUserOnly() throws Exception {
        long id = send("Shared", Map.of("roleIds", List.of(collections.getId())));

        mockMvc.perform(get("/api/inbox/" + id).with(as(cara)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(false))
                .andExpect(jsonPath("$.email.to[0].members.length()").value(2));

        mockMvc.perform(post("/api/inbox/" + id + "/read").with(as(cara)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));
        // Reading it again changes nothing, and the first read time stands.
        String firstRead = inbox(cara).get("content").get(0).get("readAt").asText();
        mockMvc.perform(post("/api/inbox/" + id + "/read").with(as(cara)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readAt").value(firstRead));

        assertThat(inbox(cara).get("content").get(0).get("read").asBoolean()).isTrue();
        assertThat(inbox(cole).get("content").get(0).get("read").asBoolean()).isFalse();
    }

    @Test
    void someoneWhoDidNotReceiveAnEmailCannotOpenIt() throws Exception {
        long id = send("For Cara", Map.of("userIds", List.of(cara.getId())));
        mockMvc.perform(get("/api/inbox/" + id).with(as(sam))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/inbox/" + id + "/read").with(as(sam))).andExpect(status().isNotFound());
        assertThat(emailDeliveryRepository.countByUserIdAndReadAtIsNull(cara.getId())).isEqualTo(1);
    }

    @Test
    void markAllAsReadCoversEveryPageAndNobodyElse() throws Exception {
        for (int i = 0; i < 12; i++) {
            send("Note " + i, Map.of("userIds", List.of(cara.getId(), cole.getId())));
        }
        JsonNode second = inbox(cara, "size", "10", "page", "1");
        assertThat(second.get("totalElements").asInt()).isEqualTo(12);
        assertThat(second.get("totalPages").asInt()).isEqualTo(2);
        assertThat(second.get("content").size()).isEqualTo(2);
        assertThat(second.get("content").get(1).get("email").get("subject").asText()).isEqualTo("Note 0");

        mockMvc.perform(post("/api/inbox/read-all").with(as(cara)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(12));

        assertThat(emailDeliveryRepository.countByUserIdAndReadAtIsNull(cara.getId())).isZero();
        assertThat(emailDeliveryRepository.countByUserIdAndReadAtIsNull(cole.getId())).isEqualTo(12);
        assertThat(inbox(cara, "size", "10", "page", "1").get("content").get(0).get("read").asBoolean()).isTrue();
    }

    @Test
    void theInboxTakesNoFilters() throws Exception {
        mockMvc.perform(get("/api/inbox").param("filter", "sentAt:relative:today").with(as(cara)))
                .andExpect(status().isBadRequest());
    }
}
