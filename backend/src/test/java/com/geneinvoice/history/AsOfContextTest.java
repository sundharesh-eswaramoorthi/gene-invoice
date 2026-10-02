package com.geneinvoice.history;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.MovableClock;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.ViewScope;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.common.scope.ViewScopeInterceptor;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * How a past date is taken from a request (§5.2.2, D9): refused when it is not a date, is in the future or is before
 * history starts; answered live when it is today; otherwise bound for the one request, said in the header and echoed
 * in the page, and gone again before the next request.
 */
class AsOfContextTest extends IntegrationTestBase {

    static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");

    @Autowired JdbcTemplate jdbc;

    User admin;
    Customer acme;

    @BeforeEach
    void acmeFromTheSeventeenth() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        MovableClock.SHARED.set(Instant.parse("2026-08-17T09:00:00Z"));
        acme = customer("Acme Ltd");
        MovableClock.SHARED.set(Instant.parse("2026-08-19T09:00:00Z"));
        customer("Beta Co");
        MovableClock.SHARED.set(NOW);
    }

    private ResultActions customers(String asOf) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/customers").with(as(admin));
        if (asOf != null) request.param("asOf", asOf);
        return mockMvc.perform(request);
    }

    private void historyStartsOn(String instant) {
        jdbc.update("insert into history_meta (id, started_at, baseline_completed_at) values (1, ?, ?)",
                Timestamp.from(Instant.parse(instant)), Timestamp.from(Instant.parse(instant)));
    }

    @Test
    void aDateThatIsNotOneOrIsStillToComeIsRefused() throws Exception {
        customers("2026-02-30").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("AS_OF_INVALID"));
        customers("20260819").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("AS_OF_INVALID"));
        customers("2026-08-21").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("AS_OF_FUTURE"))
                .andExpect(header().doesNotExist(ViewScopeInterceptor.HEADER));
    }

    @Test
    void todayIsTheLiveView() throws Exception {
        customers("2026-08-20").andExpect(status().isOk())
                .andExpect(header().string(ViewScopeInterceptor.HEADER, "live"))
                .andExpect(jsonPath("$.asOf").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].canManage").value(true));
    }

    @Test
    void aPastDateIsAnsweredAsOfThatDayAndSaysSo() throws Exception {
        customers("2026-08-18").andExpect(status().isOk())
                .andExpect(header().string(ViewScopeInterceptor.HEADER, "2026-08-18"))
                .andExpect(jsonPath("$.asOf").value("2026-08-18"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Acme Ltd"))
                .andExpect(jsonPath("$.content[0].canManage").value(false));
        customers("2026-08-16").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/products").with(as(admin)).param("asOf", "2026-08-18"))
                .andExpect(status().isOk())
                .andExpect(header().string(ViewScopeInterceptor.HEADER, "2026-08-18"))
                .andExpect(jsonPath("$.asOf").value("2026-08-18"));
        mockMvc.perform(get("/api/customers/" + acme.getId()).with(as(admin)).param("asOf", "2026-08-18"))
                .andExpect(status().isOk())
                .andExpect(header().string(ViewScopeInterceptor.HEADER, "2026-08-18"));
    }

    @Test
    void aDateBeforeHistoryStartsIsRefusedAndItsFirstDayIsAnswered() throws Exception {
        historyStartsOn("2026-08-17T09:00:00Z");

        customers("2026-08-16").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("AS_OF_BEFORE_HISTORY"))
                .andExpect(jsonPath("$.message").value("History is available from 17 Aug 2026"));
        customers("2026-08-17").andExpect(status().isOk())
                .andExpect(header().string(ViewScopeInterceptor.HEADER, "2026-08-17"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void theDateBelongsToItsRequestAlone() throws Exception {
        customers("2026-08-18").andExpect(jsonPath("$.totalElements").value(1));
        assertThat(ViewScopeHolder.current()).isEqualTo(ViewScope.LIVE_ALL);
        assertThat(AsOf.active()).isFalse();

        customers(null).andExpect(status().isOk())
                .andExpect(header().doesNotExist(ViewScopeInterceptor.HEADER))
                .andExpect(jsonPath("$.asOf").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(2));

        customers("2026-08-99").andExpect(status().isBadRequest());
        assertThat(ViewScopeHolder.current()).isEqualTo(ViewScope.LIVE_ALL);
        customers(null).andExpect(jsonPath("$.totalElements").value(2));
    }

    /**
     * The dashboard and the record tabs, which refused a past date until their as-of answers were built (BE-10),
     * now answer it, and say so in the header.
     */
    @Test
    void theDashboardAndTheRecordTabsAnswerAPastDate() throws Exception {
        for (String url : new String[] {"/api/dashboard/outstanding-by-age", "/api/documents?entityType=CUSTOMER&entityId=" + acme.getId(),
                "/api/documents/count?entityType=CUSTOMER&entityId=" + acme.getId(),
                "/api/emails?entityType=CUSTOMER&entityId=" + acme.getId(),
                "/api/audit?entityType=CUSTOMER&entityId=" + acme.getId(),
                "/api/customers/" + acme.getId() + "/pocs"}) {
            mockMvc.perform(get(url).with(as(admin)).param("asOf", "2026-08-18"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(ViewScopeInterceptor.HEADER, "2026-08-18"));
            mockMvc.perform(get(url).with(as(admin))).andExpect(status().isOk());
        }
    }
}
