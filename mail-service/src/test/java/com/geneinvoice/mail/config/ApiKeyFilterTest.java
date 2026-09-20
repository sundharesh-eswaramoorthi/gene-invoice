package com.geneinvoice.mail.config;

import com.geneinvoice.mail.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Only a caller with the key reaches {@code /api/v1/**}; health stays open. */
class ApiKeyFilterTest extends IntegrationTestBase {

    private static final String UNAUTHORIZED =
            "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Missing or invalid API key\"}";

    @Test
    void aRequestWithoutTheKeyOrWithAnotherIsTurnedAway() throws Exception {
        mockMvc.perform(get("/api/v1/connections"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().json(UNAUTHORIZED, true));
        mockMvc.perform(get("/api/v1/connections").header("X-Api-Key", "test-api-key-012345678"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(UNAUTHORIZED, true));
        mockMvc.perform(post("/api/v1/messages").header("X-Api-Key", "")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        // Before anything else: a path that does not exist says no more than one that does.
        mockMvc.perform(get("/api/v1/nothing-here")).andExpect(status().isUnauthorized());

        assertThat(messageRepository.findAll()).isEmpty();
    }

    @Test
    void theKeyLetsTheCallThrough() throws Exception {
        mockMvc.perform(get("/api/v1/connections").header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        mockMvc.perform(get("/api/v1/nothing-here").header("X-Api-Key", API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"status\":404,\"error\":\"Not Found\",\"message\":\"No such endpoint\"}", true));
    }

    @Test
    void healthNeedsNoKey() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
    }
}
