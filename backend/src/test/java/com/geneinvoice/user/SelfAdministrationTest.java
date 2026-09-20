package com.geneinvoice.user;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.role.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Nobody can lock user and role administration out of the app (AUTH-03).
 *
 * <p>Three doors led to the same dead end: deactivating or demoting your own account, deleting it,
 * and taking USER_MANAGE off the role you hold. Each answered 200 and each left the person — or,
 * on a deployment with one administrator, everybody — unable to get the ability back, because the
 * request that would give it back is the one they can no longer make. Recovery meant writing to
 * the database by hand. The bulk endpoint already refused the caller's own id; these are the same
 * rule on the paths a single request takes, plus the one that protects the last administrator.
 */
class SelfAdministrationTest extends IntegrationTestBase {

    User admin;
    User otherAdmin;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        // A second holder of USER_MANAGE, so the self-guards are tested on their own rather than
        // shadowed by the last-administrator rule.
        otherAdmin = user("ada.admin", "ADMIN");
    }

    /**
     * The roles this test creates are its own. The shared reset clears users and reactivates the
     * seeded accounts; roles are seeded once per context, so anything added here is taken away
     * here rather than left for the next test class to trip over.
     */
    @org.junit.jupiter.api.AfterEach
    void removeRolesThisTestMade() {
        for (String name : List.of("ONLY_ADMINS", "SPARE_ROLE")) {
            roleRepository.findByName(name).ifPresent(r -> {
                userRepository.findByRoleName(name).forEach(userRepository::delete);
                roleRepository.delete(r);
            });
        }
    }

    private ResultActions send(MockHttpServletRequestBuilder req, User caller, Object body) throws Exception {
        return mockMvc.perform(req.with(as(caller))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private Long viewerRoleId() {
        return role("VIEWER").getId();
    }

    // ---- your own account -------------------------------------------------------

    @Test
    void youCannotDeactivateYourOwnAccount() throws Exception {
        send(put("/api/users/" + otherAdmin.getId()), otherAdmin, Map.of("active", false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("You cannot deactivate or change the role of your own account"));

        assertThat(userRepository.findById(otherAdmin.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void youCannotMoveYourOwnAccountToAnotherRole() throws Exception {
        send(put("/api/users/" + otherAdmin.getId()), otherAdmin, Map.of("roleId", viewerRoleId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("You cannot deactivate or change the role of your own account"));

        assertThat(userRepository.findById(otherAdmin.getId()).orElseThrow().getRole().getName())
                .isEqualTo("ADMIN");
    }

    @Test
    void youCannotDeleteYourOwnAccount() throws Exception {
        mockMvc.perform(delete("/api/users/" + otherAdmin.getId()).with(as(otherAdmin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You cannot delete your own account"));

        assertThat(userRepository.findById(otherAdmin.getId())).isPresent();
    }

    /** Everything else about one's own account is still one's own to edit. */
    @Test
    void yourOwnNameEmailAndPasswordAreStillYoursToChange() throws Exception {
        send(put("/api/users/" + otherAdmin.getId()), otherAdmin,
                Map.of("fullName", "Ada A", "email", "ada@test.local", "password", "Passw0rd!"))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(otherAdmin.getId()).orElseThrow().getFullName())
                .isEqualTo("Ada A");
    }

    // ---- the last administrator -------------------------------------------------

    @Test
    void theLastAccountThatCanManageUsersCannotBeDeactivatedOrDeleted() throws Exception {
        // Ada is now the only active holder of USER_MANAGE, and admin asks about themselves last.
        send(put("/api/users/" + admin.getId()), otherAdmin, Map.of("active", false))
                .andExpect(status().isOk());

        send(put("/api/users/" + otherAdmin.getId()), admin, Map.of("active", false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This is the last active account that can"
                        + " manage users; give another account that ability first"));
        send(put("/api/users/" + otherAdmin.getId()), admin, Map.of("roleId", viewerRoleId()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/users/" + otherAdmin.getId()).with(as(admin)))
                .andExpect(status().isBadRequest());

        User survivor = userRepository.findById(otherAdmin.getId()).orElseThrow();
        assertThat(survivor.isActive()).isTrue();
        assertThat(survivor.getRole().getName()).isEqualTo("ADMIN");
    }

    // ---- the role editor --------------------------------------------------------

    @Test
    void youCannotTakeUserOrRoleManagementOffYourOwnRole() throws Exception {
        Role own = roleRepository.findByName("ADMIN").orElseThrow();
        List<String> without = own.getPrivileges().stream()
                .map(p -> p.getName())
                .filter(name -> !Privileges.USER_MANAGE.equals(name))
                .toList();

        send(put("/api/roles/" + own.getId()), admin,
                Map.of("name", "ADMIN", "privileges", without))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("You cannot remove your own ability to manage users and roles"));

        assertThat(roleRepository.findByName("ADMIN").orElseThrow().getPrivileges())
                .anyMatch(p -> Privileges.USER_MANAGE.equals(p.getName()));
    }

    @Test
    void theLastRoleThatCanManageUsersCannotHaveItTakenAway() throws Exception {
        // A separate administrator role, held by somebody else, so no self-guard applies. ADMIN
        // itself then goes, leaving this role as the only way anyone can administer users.
        Role only = roleRepository.save(Role.builder()
                .name("ONLY_ADMINS")
                .description("The last role that can manage users")
                .privileges(new java.util.HashSet<>(roleRepository.findByName("ADMIN").orElseThrow()
                        .getPrivileges()))
                .build());
        User keeper = user("kit.keeper", only.getName());
        send(put("/api/users/" + admin.getId()), keeper, Map.of("active", false)).andExpect(status().isOk());
        send(put("/api/users/" + otherAdmin.getId()), keeper, Map.of("active", false)).andExpect(status().isOk());

        List<String> without = only.getPrivileges().stream()
                .map(p -> p.getName())
                .filter(name -> !Privileges.USER_MANAGE.equals(name))
                .toList();
        send(put("/api/roles/" + only.getId()), keeper, Map.of("name", only.getName(), "privileges", without))
                .andExpect(status().isBadRequest());

        assertThat(roleRepository.findById(only.getId()).orElseThrow().getPrivileges())
                .anyMatch(p -> Privileges.USER_MANAGE.equals(p.getName()));
    }

    /** A role nobody depends on is still freely edited: the guard is narrow. */
    @Test
    void anotherRoleIsStillFreelyEdited() throws Exception {
        Role spare = roleRepository.save(Role.builder().name("SPARE_ROLE")
                .description("Nobody's administration").privileges(new java.util.HashSet<>()).build());

        send(put("/api/roles/" + spare.getId()), admin,
                Map.of("name", "SPARE_ROLE", "privileges", List.of(Privileges.CUSTOMER_VIEW)))
                .andExpect(status().isOk());

        assertThat(roleRepository.findById(spare.getId()).orElseThrow().getPrivileges()).hasSize(1);
    }
}
