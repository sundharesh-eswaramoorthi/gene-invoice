package com.geneinvoice.common.scope;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.MovableClock;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ViewScopeTest extends IntegrationTestBase {

    static final Instant NOW = Instant.parse("2026-06-30T12:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 6, 30);

    User admin;

    /** The interceptor in front of probe endpoints that report the scope they were handed. */
    MockMvc probe;

    @BeforeEach
    void setUp() {
        MovableClock.SHARED.set(NOW);
        admin = userRepository.findByUsername("admin").orElseThrow();
        probe = MockMvcBuilders.standaloneSetup(new AwareProbe(), new UnmarkedProbe())
                .addMappedInterceptors(new String[] {"/api/**"},
                        new ViewScopeInterceptor(MovableClock.SHARED, d -> { }))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void regionIdsMayBeRepeatedOrCommaSeparatedAndBlanksAreSkipped() throws Exception {
        probe.perform(get("/api/probe/scope").param("regionIds", "3,1").param("regionIds", " 2 , ,3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionIds").value(org.hamcrest.Matchers.contains(1, 2, 3)))
                .andExpect(jsonPath("$.asOf").doesNotExist());

        probe.perform(get("/api/probe/scope").param("regionIds", " , ").param("regionIds", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionIds").doesNotExist());

        probe.perform(get("/api/probe/scope"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionIds").doesNotExist())
                .andExpect(header().doesNotExist(ViewScopeInterceptor.HEADER));
    }

    @Test
    void regionIdsThatAreNotPositiveNumbersAreRefusedWithACode() throws Exception {
        for (String garbage : List.of("north", "1,x", "0", "-4", "1.5", "99999999999999999999")) {
            mockMvc.perform(get("/api/customers").with(as(admin)).param("regionIds", garbage))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("REGION_IDS_INVALID"))
                    .andExpect(jsonPath("$.message")
                            .value("regionIds must be a comma-separated list of region ids"));
        }
    }

    @Test
    void anAsOfThatIsNotAStrictIsoDateIsRefused() throws Exception {
        for (String bad : List.of("2026-02-30", "30-06-2026", "2026-6-1", "yesterday", "2026-06-30T00:00:00Z")) {
            mockMvc.perform(get("/api/customers").with(as(admin)).param("asOf", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("AS_OF_INVALID"))
                    .andExpect(jsonPath("$.message").value("asOf must be a date (yyyy-MM-dd)"));
        }
    }

    @Test
    void anAsOfAfterTodayIsRefused() throws Exception {
        mockMvc.perform(get("/api/customers").with(as(admin)).param("asOf", "2026-07-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("AS_OF_FUTURE"))
                .andExpect(jsonPath("$.message").value("asOf cannot be after today"));
    }

    @Test
    void anAsOfOfTodayIsLive() throws Exception {
        customer("Acme Ltd");

        mockMvc.perform(get("/api/customers").with(as(admin)).param("asOf", TODAY.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(ViewScopeInterceptor.HEADER, "live"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.asOf").doesNotExist())
                .andExpect(jsonPath("$.regionIds.length()").value(1))
                .andExpect(jsonPath("$.regionIds[0]").value(defaultRegion().getId().intValue()))
                .andExpect(jsonPath("$.bookRegionIds").isEmpty());

        probe.perform(post("/api/probe/write").param("asOf", TODAY.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(ViewScopeInterceptor.HEADER, "live"));
    }

    @Test
    void aPastDateIsBoundAndAnsweredFromHistory() throws Exception {
        customer("Acme Ltd");

        // The customer was made today: the day before, there was none.
        mockMvc.perform(get("/api/customers").with(as(admin)).param("asOf", "2026-06-29"))
                .andExpect(status().isOk())
                .andExpect(header().string(ViewScopeInterceptor.HEADER, "2026-06-29"))
                .andExpect(jsonPath("$.asOf").value("2026-06-29"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(post("/api/customers/export").with(as(admin)).param("asOf", "2026-06-29")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"selectAllMatchingFilter\":true}"))
                .andExpect(status().isOk())
                .andExpect(header().string(ViewScopeInterceptor.HEADER, "2026-06-29"));
        mockMvc.perform(get("/api/customers").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void aWriteSentWithAPastDateIsRefusedAndChangesNothing() throws Exception {
        long before = customerRepository.count();

        mockMvc.perform(post("/api/customers").with(as(admin)).param("asOf", "2026-06-29")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Acme Ltd", "username", "acme", "password", "Demo1234!"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("AS_OF_READ_ONLY"))
                .andExpect(jsonPath("$.message").value(
                        "You are viewing 29 Jun 2026. Changes can only be made in the current view."));

        assertThat(customerRepository.count()).isEqualTo(before);
        assertThat(userRepository.findByUsername("acme")).isEmpty();
    }

    @Test
    void configurationAndHousekeepingAnswerLiveWhenAPastDateIsViewed() throws Exception {
        mockMvc.perform(get("/api/users").with(as(admin)).param("asOf", "2026-06-01"))
                .andExpect(status().isOk())
                .andExpect(header().string(ViewScopeInterceptor.HEADER, "live"))
                .andExpect(jsonPath("$.asOf").doesNotExist());
        mockMvc.perform(post("/api/notifications/mark-all-read").with(as(admin)).param("asOf", "2026-06-01"))
                .andExpect(status().isOk())
                .andExpect(header().string(ViewScopeInterceptor.HEADER, "live"));
    }

    @Test
    void theProbeSeesEachClassificationOfAPastDate() throws Exception {
        probe.perform(get("/api/probe/scope").param("asOf", "2026-06-29").param("regionIds", "7"))
                .andExpect(status().isOk())
                .andExpect(header().string(ViewScopeInterceptor.HEADER, "2026-06-29"))
                .andExpect(jsonPath("$.asOf").value("2026-06-29"))
                .andExpect(jsonPath("$.instant").value("2026-06-29T23:59:59.999999Z"))
                .andExpect(jsonPath("$.today").value("2026-06-29"))
                .andExpect(jsonPath("$.regionIds[0]").value(7));
        probe.perform(post("/api/probe/export").param("asOf", "2026-06-29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-06-29"));
        probe.perform(post("/api/probe/housekeeping").param("asOf", "2026-06-29"))
                .andExpect(status().isOk())
                .andExpect(header().string(ViewScopeInterceptor.HEADER, "live"))
                .andExpect(jsonPath("$.asOf").doesNotExist());
        probe.perform(post("/api/probe/write").param("asOf", "2026-06-29"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("AS_OF_READ_ONLY"));
        probe.perform(get("/api/unmarked/scope").param("asOf", "2026-06-29"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("AS_OF_UNSUPPORTED"));
        probe.perform(get("/api/unmarked/scope"))
                .andExpect(status().isOk());
    }

    @Test
    void theScopeOfOneRequestNeverLeaksIntoTheNext() throws Exception {
        probe.perform(get("/api/probe/scope").param("asOf", "2026-06-29").param("regionIds", "4,5"))
                .andExpect(jsonPath("$.asOf").value("2026-06-29"));
        assertThat(ViewScopeHolder.current()).isEqualTo(ViewScope.LIVE_ALL);

        ViewScopeHolder.set(new ViewScope(Set.of(9L), AsOfPoint.endOfDay(LocalDate.of(2026, 1, 1))));
        probe.perform(get("/api/probe/scope"))
                .andExpect(jsonPath("$.regionIds").doesNotExist())
                .andExpect(jsonPath("$.asOf").doesNotExist())
                .andExpect(jsonPath("$.today").value(TODAY.toString()));
        assertThat(ViewScopeHolder.current()).isEqualTo(ViewScope.LIVE_ALL);
    }

    @Test
    void withRestoresWhateverWasBoundBefore() {
        AsOfPoint june = AsOfPoint.endOfDay(LocalDate.of(2026, 6, 1));
        AsOfPoint may = AsOfPoint.endOfDay(LocalDate.of(2026, 5, 1));
        ViewScope north = new ViewScope(Set.of(1L), null);

        String seen = ViewScopeHolder.with(north, () -> AsOf.with(june, () -> {
            assertThat(ViewScopeHolder.current().regionIds()).containsExactly(1L);
            String inner = AsOf.with(may, () -> Today.date().toString());
            assertThat(AsOf.current()).isEqualTo(june);
            return inner + " " + Today.date();
        }));

        assertThat(seen).isEqualTo("2026-05-01 2026-06-01");
        assertThat(ViewScopeHolder.current()).isEqualTo(ViewScope.LIVE_ALL);
        assertThat(AsOf.active()).isFalse();
        assertThat(Today.date()).isEqualTo(TODAY);

        assertThatThrownBy(() -> ViewScopeHolder.with(north, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(ViewScopeHolder.current()).isEqualTo(ViewScope.LIVE_ALL);
    }

    @Test
    void writePathsRefuseToRunWhileAPastDateIsBound() {
        ViewScopeHolder.requireLive();
        AsOf.with(AsOfPoint.endOfDay(LocalDate.of(2026, 6, 1)), () -> {
            assertThatThrownBy(ViewScopeHolder::requireLive)
                    .isInstanceOf(AsOfException.class)
                    .hasMessage("You are viewing 1 Jun 2026. Changes can only be made in the current view.")
                    .extracting(e -> ((AsOfException) e).code).isEqualTo("AS_OF_READ_ONLY");
            return null;
        });
    }

    @Test
    void narrowingKeepsOnlyTheRegionsBothAskedForAndHeld() {
        assertThat(ViewScope.LIVE_ALL.narrow(Set.of(1L, 2L))).containsExactlyInAnyOrder(1L, 2L);
        assertThat(new ViewScope(Set.of(2L, 3L), null).narrow(Set.of(1L, 2L))).containsExactly(2L);
        assertThat(new ViewScope(Set.of(3L), null).narrow(Set.of(1L, 2L))).isEmpty();
        assertThat(AsOfPoint.at(Instant.parse("2026-06-29T23:59:59.999999999Z")))
                .isEqualTo(new AsOfPoint(LocalDate.of(2026, 6, 29), Instant.parse("2026-06-29T23:59:59.999999Z")));
    }

    @Controller
    @AsOfAware
    @RequestMapping("/api/probe")
    static class AwareProbe {

        @GetMapping("/scope")
        @ResponseBody
        Map<String, Object> scope() {
            return describe();
        }

        @PostMapping("/export")
        @AsOfAware
        @ResponseBody
        Map<String, Object> export() {
            return describe();
        }

        @PostMapping("/housekeeping")
        @AsOfIgnored
        @ResponseBody
        Map<String, Object> housekeeping() {
            return describe();
        }

        @PostMapping("/write")
        @ResponseBody
        Map<String, Object> write() {
            return describe();
        }
    }

    @Controller
    @RequestMapping("/api/unmarked")
    static class UnmarkedProbe {

        @GetMapping("/scope")
        @ResponseBody
        Map<String, Object> scope() {
            return describe();
        }
    }

    static Map<String, Object> describe() {
        ViewScope s = ViewScopeHolder.current();
        Map<String, Object> out = new HashMap<>();
        if (s.regionIds() != null) out.put("regionIds", List.copyOf(s.regionIds()));
        if (s.asOf() != null) {
            out.put("asOf", s.asOf().date().toString());
            out.put("instant", s.asOf().instant().toString());
        }
        out.put("today", Today.date().toString());
        return out;
    }
}
