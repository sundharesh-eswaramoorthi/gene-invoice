package com.geneinvoice.auth;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Changing a password ends the sessions that were signed in with the old one (AUTH-04).
 *
 * <p>Changing a password is what a person does when they think somebody else has their session.
 * It used to do nothing about it: the token minted before the change went on working for the rest
 * of its 24 hours, and the only way to evict it was to deactivate the account — which also
 * destroys that person's Gmail connection, so it is no workaround at all. A token now carries the
 * generation of credentials it was minted against, and one from before the latest change is no
 * longer the account's.
 */
class CredentialsGenerationTest extends IntegrationTestBase {

    @Autowired JwtService jwtService;

    User person;

    @BeforeEach
    void setUp() {
        person = user("pat.person", "VIEWER");
        person.setPassword(passwordEncoder.encode("Start123!"));
        userRepository.save(person);
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private ResultActions me(String token) throws Exception {
        return mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token));
    }

    private ResultActions changePassword(String token, String current, String next) throws Exception {
        return mockMvc.perform(post("/api/auth/change-password")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("currentPassword", current, "newPassword", next))));
    }

    @Test
    void aTokenMintedBeforeAPasswordChangeStopsWorking() throws Exception {
        String before = login("pat.person", "Start123!");
        me(before).andExpect(status().isOk());

        changePassword(before, "Start123!", "Newpass123!").andExpect(status().isOk());

        me(before).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/customers").header("Authorization", "Bearer " + before))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The one session that must not be evicted is the one doing the changing: it just proved the
     * current password. The response carries a token of the new generation, which is what the app
     * stores — without it the user is told "Password changed successfully" and thrown to the
     * sign-in screen on their next tap (AUTH-04, the backend/frontend half of it).
     */
    @Test
    void theSessionThatChangedThePasswordIsHandedATokenThatWorks() throws Exception {
        String before = login("pat.person", "Start123!");

        String body = changePassword(before, "Start123!", "Newpass123!")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.token").isString())
                .andReturn().getResponse().getContentAsString();
        String handedBack = objectMapper.readTree(body).get("token").asText();

        assertThat(handedBack).isNotEqualTo(before);
        me(before).andExpect(status().isUnauthorized());
        me(handedBack).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("pat.person"));
    }

    @Test
    void theTokenTakenOutAfterTheChangeKeepsWorking() throws Exception {
        String before = login("pat.person", "Start123!");
        changePassword(before, "Start123!", "Newpass123!").andExpect(status().isOk());

        String after = login("pat.person", "Newpass123!");
        me(after).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("pat.person"));
    }

    /** An administrator resetting somebody's password evicts that person's sessions too. */
    @Test
    void anAdministratorsPasswordResetAlsoEndsTheSessionsItReplaces() throws Exception {
        String theirs = login("pat.person", "Start123!");
        User admin = userRepository.findByUsername("admin").orElseThrow();

        mockMvc.perform(put("/api/users/" + person.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("password", "Reset123!"))))
                .andExpect(status().isOk());

        me(theirs).andExpect(status().isUnauthorized());
        me(login("pat.person", "Reset123!")).andExpect(status().isOk());
    }

    /**
     * An account whose password has never changed keeps every token it has issued: the column is
     * null on every row of an existing deployment, and adding it must not sign the whole company
     * out on the next restart.
     */
    @Test
    void anAccountThatHasNeverChangedItsPasswordKeepsItsTokens() throws Exception {
        String token = login("pat.person", "Start123!");

        assertThat(userRepository.findById(person.getId()).orElseThrow().getCredentialsChangedAt())
                .isNull();
        me(token).andExpect(status().isOk());
    }

    /**
     * And a token minted before the column existed — one carrying no generation at all — stops
     * working once the password does change, which is the whole point of the change.
     */
    @Test
    void aTokenFromBeforeTheUpgradeIsStillEvictedByAPasswordChange() throws Exception {
        String old = jwtService.generateToken(person.getUsername(), Map.of("role", "VIEWER"));
        me(old).andExpect(status().isOk());

        changePassword(old, "Start123!", "Newpass123!").andExpect(status().isOk());

        me(old).andExpect(status().isUnauthorized());
    }
}
