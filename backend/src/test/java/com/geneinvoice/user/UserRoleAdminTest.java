package com.geneinvoice.user;

import com.geneinvoice.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * User and role administration answers a clash with a message the admin can act on — never a
 * failed insert — and a blank email is no email, not a second '' colliding with the first.
 */
class UserRoleAdminTest extends IntegrationTestBase {

    User admin;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
    }

    private ResultActions send(MockHttpServletRequestBuilder req, Object body) throws Exception {
        return mockMvc.perform(req.with(as(admin)).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions createUser(String username, String email) throws Exception {
        return send(post("/api/users"), Map.of("username", username, "email", email,
                "fullName", username, "password", "Passw0rd!", "roleId", role("VIEWER").getId()));
    }

    private static String unique(String prefix) {
        return prefix + System.nanoTime();
    }

    // ---- D-27 -------------------------------------------------------------------

    // ---- D-42: one password rule everywhere -------------------------------------

    private Long viewerRoleId() {
        return roleRepository.findAll().stream().filter(r -> "VIEWER".equals(r.getName()))
                .findFirst().orElseThrow().getId();
    }

    @Test
    void aPasswordShorterThanSixCharactersIsRefusedWhereverItCanBeSet() throws Exception {
        String tooShort = "abc12";
        send(post("/api/users"), Map.of("username", unique("shorty"), "password", tooShort,
                        "roleId", viewerRoleId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Password must be at least 6 characters"));

        User vic = user("vic.viewer", "VIEWER");
        send(put("/api/users/" + vic.getId()), Map.of("password", tooShort))
                .andExpect(status().isBadRequest());
        send(post("/api/customers"), Map.of("name", unique("Short Ltd"),
                        "username", unique("short.login"), "password", tooShort))
                .andExpect(status().isBadRequest());

        send(post("/api/users"), Map.of("username", unique("okpass"), "password", "abc123",
                        "roleId", viewerRoleId()))
                .andExpect(status().isOk());
    }

    // ---- D-43: deleting something that isn't there ------------------------------

    @Test
    void deletingARoleThatDoesNotExistIsNotFound() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/roles/99999999").with(as(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anEmailAlreadyInUseInAnyCaseIsRefused() throws Exception {
        User vera = user("vera.viewer", "VIEWER");
        User vic = user("vic.viewer", "VIEWER");

        createUser("val.viewer", "VERA.VIEWER@test.local")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email already exists"));
        send(put("/api/users/" + vic.getId()), Map.of("email", "Vera.Viewer@Test.local"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email already exists"));
        send(put("/api/users/" + vera.getId()), Map.of("email", "vera.viewer@test.local", "fullName", "Vera"))
                .andExpect(status().isOk());
        send(post("/api/customers"), Map.of("name", "Clash Ltd", "email", "vic.viewer@test.local",
                "username", "clash.login", "password", "Passw0rd!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email already exists"));
    }

    @Test
    void roleNamesAreUniqueInAnyCase() throws Exception {
        send(post("/api/roles"), Map.of("name", "admin", "privileges", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Role name already exists"));

        String name = unique("temp-role-");
        Long id = objectMapper.readTree(send(post("/api/roles"), Map.of("name", name, "privileges", List.of()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        send(put("/api/roles/" + id), Map.of("name", "Viewer", "privileges", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Role name already exists"));
        send(put("/api/roles/" + id), Map.of("name", name, "description", "renamed nothing"))
                .andExpect(status().isOk());
    }

    @Test
    void aRoleInUseCannotBeDeleted() throws Exception {
        String used = unique("used-role-");
        String unused = unique("unused-role-");
        Long usedId = objectMapper.readTree(send(post("/api/roles"), Map.of("name", used, "privileges", List.of()))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        Long unusedId = objectMapper.readTree(send(post("/api/roles"), Map.of("name", unused, "privileges", List.of()))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        user("holder", used);

        mockMvc.perform(delete("/api/roles/" + usedId).with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Role is assigned to 1 user; move them to another role first"));
        assertThat(roleRepository.findById(usedId)).isPresent();

        mockMvc.perform(delete("/api/roles/" + unusedId).with(as(admin))).andExpect(status().isOk());
        assertThat(roleRepository.findById(unusedId)).isEmpty();
        mockMvc.perform(delete("/api/roles/99999999").with(as(admin))).andExpect(status().isNotFound());
    }

    // ---- D-28 -------------------------------------------------------------------

    @Test
    void aBlankEmailIsStoredAsNoEmail() throws Exception {
        createUser("nobody.one", "").andExpect(status().isOk()).andExpect(jsonPath("$.email", nullValue()));
        // A second blank email used to collide with the first on the unique column.
        Long id = objectMapper.readTree(createUser("nobody.two", "")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();

        send(put("/api/users/" + id), Map.of("email", "", "active", false)).andExpect(status().isOk());
        send(put("/api/users/" + id), Map.of("email", "", "active", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    // ---- D-29 -------------------------------------------------------------------

    @Test
    void overlongOrMalformedUserFieldsAreFieldErrors() throws Exception {
        createUser("u".repeat(81), "long@test.local")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.username").value("must be at most 80 characters"));
        createUser("u".repeat(80), "long@test.local").andExpect(status().isOk());
        createUser("bad.email", "not-an-email")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists());
    }
}
