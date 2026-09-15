package com.geneinvoice.auth;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A caller without a valid session gets 401 so the app signs them out; a signed-in caller who lacks
 * the privilege gets 403. Before, both were 403, and an expired session left every screen failing.
 */
class AuthenticationStatusTest extends IntegrationTestBase {

    @Autowired JwtService jwtService;

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/invoices")).andExpect(status().isUnauthorized());
    }

    @Test
    void anInvalidTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/invoices").header("Authorization", "Bearer not.a.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aValidTokenForAUserWhoLacksThePrivilegeIsForbidden() throws Exception {
        User viewer = user("vera.viewer", "VIEWER");
        String token = jwtService.generateToken(viewer.getUsername(), Map.of());

        mockMvc.perform(get("/api/invoices").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
