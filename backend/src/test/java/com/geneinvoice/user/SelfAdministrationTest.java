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

class SelfAdministrationTest extends IntegrationTestBase {

    User admin;
    User otherAdmin;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        otherAdmin = user("ada.admin", "ADMIN");
    }

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

    @Test
    void yourOwnNameEmailAndPasswordAreStillYoursToChange() throws Exception {
        send(put("/api/users/" + otherAdmin.getId()), otherAdmin,
                Map.of("fullName", "Ada A", "email", "ada@test.local", "password", "Passw0rd!"))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(otherAdmin.getId()).orElseThrow().getFullName())
                .isEqualTo("Ada A");
    }

    @Test
    void theLastAccountThatCanManageUsersCannotBeDeactivatedOrDeleted() throws Exception {
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
