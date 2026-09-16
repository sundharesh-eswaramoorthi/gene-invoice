package com.geneinvoice.common;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-41: an export is the list the user is looking at, so it comes back in the order the filter and
 * sort asked for — not in whatever order the ids happened to load.
 */
class ExportOrderTest extends IntegrationTestBase {

    User admin;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        actAs(admin);
    }

    private String exportCsv(String table, String sort) throws Exception {
        return mockMvc.perform(post("/api/" + table + "/export").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"EXPORT\",\"selectAllMatchingFilter\":true,\"sort\":\""
                                + sort + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theProductsExportFollowsTheRequestedSort() throws Exception {
        // Created in an order that has nothing to do with the sort asked for.
        product("Zeta export probe", "10.00");
        product("Alpha export probe", "20.00");
        product("Mu export probe", "30.00");

        String csv = exportCsv("products", "name,asc");

        assertThat(csv.indexOf("Alpha export probe"))
                .isLessThan(csv.indexOf("Mu export probe"));
        assertThat(csv.indexOf("Mu export probe"))
                .isLessThan(csv.indexOf("Zeta export probe"));

        String desc = exportCsv("products", "name,desc");
        assertThat(desc.indexOf("Zeta export probe"))
                .isLessThan(desc.indexOf("Alpha export probe"));
    }

    @Test
    void theUsersExportFollowsTheRequestedSort() throws Exception {
        user("zoe.export", "VIEWER");
        user("amy.export", "VIEWER");

        String csv = exportCsv("users", "username,asc");

        assertThat(csv.indexOf("amy.export")).isLessThan(csv.indexOf("zoe.export"));
    }
}
