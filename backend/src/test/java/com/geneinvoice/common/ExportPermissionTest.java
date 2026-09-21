package com.geneinvoice.common;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExportPermissionTest extends IntegrationTestBase {

    private static final List<String> TABLES = List.of(
            "users", "roles", "disputes", "products", "invoices", "payments", "customers", "promises");
    private static final String EXPORT_ALL = "{\"action\":\"EXPORT\",\"selectAllMatchingFilter\":true}";

    @Autowired PrivilegeRepository privilegeRepository;

    private User exportOnlyUser() {
        Role role = roleRepository.findByName("EXPORT_ONLY").orElseGet(() -> roleRepository.save(
                Role.builder()
                        .name("EXPORT_ONLY")
                        .description("Export without any view privilege")
                        .privileges(new HashSet<>(Set.of(
                                privilegeRepository.findByName(Privileges.EXPORT_DATA).orElseThrow())))
                        .build()));
        return user("eddie.exporter", role.getName());
    }

    private int exportStatus(User u, String table) throws Exception {
        return mockMvc.perform(post("/api/" + table + "/export").with(as(u))
                        .contentType(MediaType.APPLICATION_JSON).content(EXPORT_ALL))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void exportWithoutTheViewPrivilegeIsForbiddenOnEveryTable() throws Exception {
        User exporter = exportOnlyUser();
        for (String table : TABLES) {
            mockMvc.perform(post("/api/" + table + "/export").with(as(exporter))
                            .contentType(MediaType.APPLICATION_JSON).content(EXPORT_ALL))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void theCashierCannotExportTablesItCannotList() throws Exception {
        User cashier = userRepository.findByUsername("cashier").orElseThrow();
        for (String table : List.of("users", "roles", "disputes")) {
            mockMvc.perform(post("/api/" + table + "/export").with(as(cashier))
                            .contentType(MediaType.APPLICATION_JSON).content(EXPORT_ALL))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/invoices/export").with(as(cashier))
                        .contentType(MediaType.APPLICATION_JSON).content(EXPORT_ALL))
                .andExpect(status().isOk());
    }

    @Test
    void adminCanStillExportEveryTable() throws Exception {
        User admin = userRepository.findByUsername("admin").orElseThrow();
        for (String table : TABLES) {
            org.assertj.core.api.Assertions.assertThat(exportStatus(admin, table))
                    .as("export of " + table).isEqualTo(200);
        }
    }
}
