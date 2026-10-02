package com.geneinvoice.common.time;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.MovableClock;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URL;
import java.net.URLClassLoader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ClockTest extends IntegrationTestBase {

    static final Instant PAST = Instant.parse("2025-03-04T10:15:30Z");

    @Autowired Clock clock;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired AuditService auditService;

    User admin;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
    }

    @Test
    void theMovableClockFollowsSystemTimeUntilATestSetsIt() {
        assertThat(clock).as("beans are handed the test clock").isSameAs(MovableClock.SHARED);
        assertThat(AppClock.get()).isSameAs(MovableClock.SHARED);

        Instant before = Instant.now();
        Instant seen = clock.instant();
        Instant after = Instant.now();
        assertThat(seen).isBetween(before, after);

        MovableClock.SHARED.set(PAST);
        assertThat(clock.instant()).isEqualTo(PAST);
        assertThat(AppClock.now()).isEqualTo(PAST);
        assertThat(Today.date()).isEqualTo(LocalDate.of(2025, 3, 4));
        assertThat(InvoiceDates.today()).isEqualTo(LocalDate.of(2025, 3, 4));

        MovableClock.SHARED.advance(Duration.ofDays(1));
        assertThat(clock.instant()).isEqualTo(PAST.plus(Duration.ofDays(1)));
        assertThat(Today.liveDate()).isEqualTo(LocalDate.of(2025, 3, 5));

        MovableClock.SHARED.reset();
        before = Instant.now();
        seen = clock.instant();
        after = Instant.now();
        assertThat(seen).isBetween(before, after);
    }

    @Test
    void anInvoiceRaisedWithNoDateTakesItsDateNumberAndCreationTimeFromTheClock() throws Exception {
        MovableClock.SHARED.set(PAST);
        User sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        Customer acme = customer("Acme Ltd");
        Product widget = product("Widget", "100.00");

        MvcResult created = mockMvc.perform(post("/api/invoices").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", acme.getId(), "salesPocUserId", sales.getId(),
                                "items", List.of(Map.of("productId", widget.getId(), "quantity", 1))))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());

        assertThat(body.get("invoiceNumber").asText()).startsWith("INV-20250304-");
        Invoice stored = invoiceRepository.findById(body.get("id").asLong()).orElseThrow();
        assertThat(stored.getInvoiceDate()).isEqualTo(PAST);
        assertThat(stored.getCreatedAt()).isEqualTo(PAST);
        assertThat(customerRepository.findById(acme.getId()).orElseThrow().getCreatedAt()).isEqualTo(PAST);
    }

    @Test
    void auditRowsAreStampedWithTheClock() throws Exception {
        MovableClock.SHARED.set(PAST);

        mockMvc.perform(post("/api/customers").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Acme Ltd", "username", "acme", "password", "Demo1234!"))))
                .andExpect(status().isOk());

        Customer acme = customerRepository.findAll().stream()
                .filter(c -> c.getName().equals("Acme Ltd")).findFirst().orElseThrow();
        List<AuditLog> trail = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                "CUSTOMER", acme.getId());
        assertThat(trail).isNotEmpty();
        assertThat(trail).allSatisfy(a -> {
            assertThat(a.getCreatedAt()).isEqualTo(PAST);
            assertThat(a.getOrigin()).as("a person's own change needs no origin").isNull();
        });
    }

    @Test
    void auditRowsSayWhoAndWhatWasBehindAChange() throws Exception {
        MovableClock.SHARED.set(PAST);
        Customer acme = customer("Acme Ltd");
        User checker = user("carol.checker", DataSeeder.ROLE_SALES_POC);
        actAs(admin);

        AuditLog own = auditService.record("CUSTOMER", acme.getId(), "CUSTOMER_UPDATED", null, null,
                admin.getId(), null, "typo");
        AuditLog byRule = WriteOrigins.callWith(WriteOrigin.automation(41L, 7L, 9L, 1),
                () -> auditService.record("CUSTOMER", acme.getId(), "CUSTOMER_UPDATED", null, null,
                        admin.getId(), null, "rule"));
        AuditLog approved = WriteOrigins.callWith(WriteOrigin.approval(55L, checker.getId()).withActor(admin.getId()),
                () -> auditService.record("CUSTOMER", acme.getId(), "CUSTOMER_UPDATED", null, null,
                        admin.getId(), null, "x".repeat(600), 3L));
        AuditLog linked = auditService.recordLinked("CUSTOMER", acme.getId(), "CHANGE_REQUESTED", null, null,
                admin.getId(), null, 56L, "held");
        SecurityContextHolder.clearContext();
        AuditLog background = auditService.record("CUSTOMER", acme.getId(), "CUSTOMER_UPDATED", null, null,
                null, null, "sweep");

        assertThat(own.getOrigin()).isNull();
        assertThat(byRule.getOrigin()).isEqualTo("AUTOMATION");
        assertThat(byRule.getRuleId()).isEqualTo(41L);
        assertThat(approved.getOrigin()).isEqualTo("APPROVAL");
        assertThat(approved.getPendingChangeId()).isEqualTo(55L);
        assertThat(approved.getApprovedByUserId()).isEqualTo(checker.getId());
        assertThat(approved.getRegionId()).isEqualTo(3L);
        assertThat(approved.getReason()).hasSize(500).endsWith("…");
        assertThat(linked.getOrigin()).isNull();
        assertThat(linked.getPendingChangeId()).isEqualTo(56L);
        assertThat(linked.getApprovedByUserId()).isNull();
        assertThat(background.getOrigin()).isEqualTo("SYSTEM");

        mockMvc.perform(get("/api/audit").with(as(admin))
                        .param("entityType", "CUSTOMER").param("entityId", acme.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)].origin", approved.getId()).value("APPROVAL"))
                .andExpect(jsonPath("$[?(@.id == %d)].pendingChangeId", approved.getId()).value(55))
                .andExpect(jsonPath("$[?(@.id == %d)].approvedByUserId", approved.getId()).value(checker.getId().intValue()))
                .andExpect(jsonPath("$[?(@.id == %d)].approvedByUsername", approved.getId()).value("carol.checker"))
                .andExpect(jsonPath("$[?(@.id == %d)].ruleId", byRule.getId()).value(41));
    }

    @Test
    void signingInIsUnaffectedByAClockSetInThePast() throws Exception {
        MovableClock.SHARED.set(Instant.parse("2000-01-01T00:00:00Z"));

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "admin", "password", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"));
    }

    @Test
    void theStaticBridgeIsNeverNullEvenBeforeAnythingSetsIt() throws Exception {
        URL classes = AppClock.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader fresh = new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader())) {
            Class<?> untouched = fresh.loadClass(AppClock.class.getName());
            assertThat(untouched).isNotSameAs(AppClock.class);
            Object c = untouched.getMethod("get").invoke(null);
            assertThat(c).isInstanceOf(Clock.class);
            Instant before = Instant.now();
            Instant seen = (Instant) untouched.getMethod("now").invoke(null);
            assertThat(seen).isBetween(before, Instant.now());
        }
    }

    @Test
    void theResetBeforeEachTestPutsEntityCallbacksBackOnTheTestClock() {
        // What booting a second context does to the JVM-wide bridge.
        AppClock.set(Clock.systemUTC());
        MovableClock.SHARED.set(PAST);
        assertThat(customer("Drifted").getCreatedAt()).isNotEqualTo(PAST);

        resetTransactionalData();

        MovableClock.SHARED.set(PAST);
        assertThat(customer("Steady").getCreatedAt()).isEqualTo(PAST);
    }
}
