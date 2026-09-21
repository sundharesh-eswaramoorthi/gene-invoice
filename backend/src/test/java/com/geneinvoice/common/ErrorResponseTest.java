package com.geneinvoice.common;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultMatcher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ErrorResponseTest extends IntegrationTestBase {

    private static final ResultMatcher NO_INTERNALS = result -> assertThat(
            result.getResponse().getContentAsString())
            .doesNotContain("java.").doesNotContain("com.geneinvoice").doesNotContain("Exception");

    User admin;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
    }

    @Test
    void aNonNumericIdOrParameterIs400() throws Exception {
        mockMvc.perform(get("/api/invoices/abc").with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'id': must be a number"))
                .andExpect(NO_INTERNALS);
        mockMvc.perform(get("/api/invoices?size=abc").with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(NO_INTERNALS);
    }

    @Test
    void anUnknownOrMissingEnumParameterIs400() throws Exception {
        mockMvc.perform(get("/api/pocs/assignable?type=bogus").with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Invalid value for 'type': must be one of [SALES, SUCCESS, COLLECTION]"))
                .andExpect(NO_INTERNALS);
        mockMvc.perform(get("/api/pocs/assignable").with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing required parameter 'type'"));
    }

    @Test
    void unreadableOrMistypedJsonIs400() throws Exception {
        mockMvc.perform(post("/api/products").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{bad json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The request body is not valid JSON"))
                .andExpect(NO_INTERNALS);
        mockMvc.perform(post("/api/products").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"price\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.price").value("must be a number"))
                .andExpect(NO_INTERNALS);
        mockMvc.perform(post("/api/promises/999/override").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"FOO\",\"reason\":\"r\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.status", startsWith("must be one of")))
                .andExpect(NO_INTERNALS);
    }

    @Test
    void badBulkParametersAre400() throws Exception {
        Customer acme = customer("Acme Ltd");
        mockMvc.perform(post("/api/customers/bulk").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "ADD_POC", "ids", List.of(acme.getId()),
                                "params", Map.of("pocType", "foo", "userId", admin.getId())))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("pocType must be one of [SALES, SUCCESS, COLLECTION]"));
        mockMvc.perform(post("/api/customers/bulk").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "ADD_POC", "ids", List.of(acme.getId()),
                                "params", Map.of("pocType", "SUCCESS", "userId", "abc")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("params.userId must be a whole number"));
    }

    @Test
    void aWrongMethodMediaTypeOrPathGetsItsOwnStatus() throws Exception {
        mockMvc.perform(delete("/api/invoices").with(as(admin)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(NO_INTERNALS);
        mockMvc.perform(post("/api/products").with(as(admin))
                        .contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(NO_INTERNALS);
        mockMvc.perform(get("/api/no-such-thing").with(as(admin)))
                .andExpect(status().isNotFound())
                .andExpect(NO_INTERNALS);
    }
}
