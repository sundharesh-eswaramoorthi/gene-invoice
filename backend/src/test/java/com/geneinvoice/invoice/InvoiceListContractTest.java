package com.geneinvoice.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.auth.AppUserDetails;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract test for the invoice list endpoints (GET /api/invoices and
 * GET /api/invoices?customerId=...). It pins the contract the frontend already
 * consumes: the response remains a direct JSON array whose elements carry exactly
 * the nine pre-existing summary properties plus the single additive property
 * {@code reminderCount} (present on every element, never absent, never null,
 * serialized as a non-negative number), and the INVOICE_VIEW authority gate plus
 * the customer-ownership restriction are unchanged. Because the change is purely
 * additive, no consumer-side change is required.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class InvoiceListContractTest {

    private static final String ENTITY_TYPE_INVOICE = "INVOICE";
    private static final String REMINDER_ACTION_PREFIX = "OVERDUE_REMINDER_STEP_";

    private static final String INVOICE_A1_NUMBER = "CNTRCT-A-0001";
    private static final String INVOICE_A2_NUMBER = "CNTRCT-A-0002";
    private static final String INVOICE_B1_NUMBER = "CNTRCT-B-0001";

    /**
     * The nine pre-existing invoice-summary properties, unchanged in name, plus
     * exactly one additive property: reminderCount.
     */
    private static final Set<String> EXPECTED_PROPERTIES = Set.of(
            "id", "invoiceNumber", "customerId", "customerName", "invoiceDate",
            "total", "paidAmount", "balance", "status", "reminderCount");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private Customer customerA;
    private Customer customerB;
    private Invoice invoiceA1;
    private Invoice invoiceA2;
    private Invoice invoiceB1;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        invoiceRepository.deleteAll();
        customerRepository.deleteAll();

        customerA = customerRepository.save(Customer.builder().name("Acme Traders").build());
        customerB = customerRepository.save(Customer.builder().name("Bravo Retail").build());

        invoiceA1 = invoiceRepository.save(Invoice.builder()
                .invoiceNumber(INVOICE_A1_NUMBER)
                .customer(customerA)
                .invoiceDate(Instant.now().minus(60, ChronoUnit.DAYS))
                .total(new BigDecimal("100.00"))
                .paidAmount(new BigDecimal("40.00"))
                .status(InvoiceStatus.PARTIALLY_PAID)
                .build());
        invoiceA2 = invoiceRepository.save(Invoice.builder()
                .invoiceNumber(INVOICE_A2_NUMBER)
                .customer(customerA)
                .invoiceDate(Instant.now().minus(10, ChronoUnit.DAYS))
                .total(new BigDecimal("50.00"))
                .paidAmount(BigDecimal.ZERO)
                .status(InvoiceStatus.UNPAID)
                .build());
        invoiceB1 = invoiceRepository.save(Invoice.builder()
                .invoiceNumber(INVOICE_B1_NUMBER)
                .customer(customerB)
                .invoiceDate(Instant.now().minus(90, ChronoUnit.DAYS))
                .total(new BigDecimal("75.00"))
                .paidAmount(BigDecimal.ZERO)
                .status(InvoiceStatus.UNPAID)
                .build());

        // Two reminder audit rows for A1 (reminderCount 2), one for B1 (reminderCount 1),
        // and a non-reminder row for A2 (reminderCount must still be present, as 0).
        saveAudit(invoiceA1.getId(), REMINDER_ACTION_PREFIX + "1");
        saveAudit(invoiceA1.getId(), REMINDER_ACTION_PREFIX + "2");
        saveAudit(invoiceA2.getId(), "CREATED");
        saveAudit(invoiceB1.getId(), REMINDER_ACTION_PREFIX + "1");
    }

    private void saveAudit(Long invoiceId, String action) {
        auditLogRepository.save(AuditLog.builder()
                .entityType(ENTITY_TYPE_INVOICE)
                .entityId(invoiceId)
                .action(action)
                .build());
    }

    @Test
    void listReturnsDirectJsonArrayWithNineExistingPropertiesPlusAdditiveReminderCount() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/invoices")
                        .with(caller(null, Privileges.INVOICE_VIEW)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(body.isArray(), "list response must remain a direct JSON array, not a wrapped envelope");
        assertTrue(body.size() >= 3, "expected at least the three seeded invoices");

        Map<String, JsonNode> byNumber = byInvoiceNumber(body);
        JsonNode a1 = byNumber.get(INVOICE_A1_NUMBER);
        JsonNode a2 = byNumber.get(INVOICE_A2_NUMBER);
        JsonNode b1 = byNumber.get(INVOICE_B1_NUMBER);
        assertNotNull(a1);
        assertNotNull(a2);
        assertNotNull(b1);

        byNumber.values().forEach(this::assertContractElement);

        // reminderCount is a non-negative number, present for every element.
        assertEquals(2L, a1.get("reminderCount").asLong());
        assertEquals(0L, a2.get("reminderCount").asLong());
        assertEquals(1L, b1.get("reminderCount").asLong());

        // The nine pre-existing properties keep their names and values.
        assertEquals(invoiceA1.getId().longValue(), a1.get("id").asLong());
        assertEquals(INVOICE_A1_NUMBER, a1.get("invoiceNumber").asText());
        assertEquals(customerA.getId().longValue(), a1.get("customerId").asLong());
        assertEquals("Acme Traders", a1.get("customerName").asText());
        assertTrue(a1.get("invoiceDate").isTextual());
        assertEquals(0, new BigDecimal("100.00").compareTo(a1.get("total").decimalValue()));
        assertEquals(0, new BigDecimal("40.00").compareTo(a1.get("paidAmount").decimalValue()));
        assertEquals(0, new BigDecimal("60.00").compareTo(a1.get("balance").decimalValue()));
        assertEquals("PARTIALLY_PAID", a1.get("status").asText());
    }

    @Test
    void listByCustomerReturnsTheSameShapeWithReminderCountsAndExistingOrdering() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/invoices")
                        .param("customerId", String.valueOf(customerA.getId()))
                        .with(caller(null, Privileges.INVOICE_VIEW)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(body.isArray(), "customer-filtered response must remain a direct JSON array");
        assertEquals(2, body.size());

        body.forEach(element -> {
            assertContractElement(element);
            assertEquals(customerA.getId().longValue(), element.get("customerId").asLong());
        });

        // Existing ordering (invoiceDate descending) is untouched.
        assertEquals(INVOICE_A2_NUMBER, body.get(0).get("invoiceNumber").asText());
        assertEquals(INVOICE_A1_NUMBER, body.get(1).get("invoiceNumber").asText());
        assertEquals(0L, body.get(0).get("reminderCount").asLong());
        assertEquals(2L, body.get(1).get("reminderCount").asLong());
    }

    @Test
    void listWithoutInvoiceViewAuthorityIsDenied() throws Exception {
        mockMvc.perform(get("/api/invoices")
                        .with(caller(null, Privileges.CUSTOMER_VIEW)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/invoices")
                        .param("customerId", String.valueOf(customerA.getId()))
                        .with(caller(null, Privileges.CUSTOMER_VIEW)))
                .andExpect(status().isForbidden());
    }

    @Test
    void listWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(get("/api/invoices"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/invoices")
                        .param("customerId", String.valueOf(customerA.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerCallerCannotObtainAnotherCustomersInvoices() throws Exception {
        mockMvc.perform(get("/api/invoices")
                        .param("customerId", String.valueOf(customerB.getId()))
                        .with(caller(customerA.getId(), Privileges.INVOICE_VIEW)))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerCallerSeesOnlyOwnInvoicesWithOwnReminderCounts() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/invoices")
                        .with(caller(customerA.getId(), Privileges.INVOICE_VIEW)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(body.isArray());
        assertEquals(2, body.size(), "a customer-restricted caller must not see another customer's invoices");

        Map<String, JsonNode> byNumber = byInvoiceNumber(body);
        body.forEach(element -> {
            assertContractElement(element);
            assertEquals(customerA.getId().longValue(), element.get("customerId").asLong());
        });
        assertTrue(byNumber.containsKey(INVOICE_A1_NUMBER));
        assertTrue(byNumber.containsKey(INVOICE_A2_NUMBER));
        assertTrue(!byNumber.containsKey(INVOICE_B1_NUMBER),
                "customer B's invoice (and thereby its reminderCount) must not leak to customer A");

        // Only the caller's own tallies are exposed: A1 -> 2, A2 -> 0;
        // B1's reminderCount of 1 never reaches the response.
        assertEquals(2L, byNumber.get(INVOICE_A1_NUMBER).get("reminderCount").asLong());
        assertEquals(0L, byNumber.get(INVOICE_A2_NUMBER).get("reminderCount").asLong());
    }

    /**
     * An element must carry exactly the nine pre-existing properties plus the one
     * additive reminderCount: nothing renamed, nothing removed, nothing else added.
     * reminderCount must be present, never null, and a non-negative number.
     */
    private void assertContractElement(JsonNode element) {
        assertTrue(element.isObject(), "each list element must be a JSON object");
        Set<String> keys = new HashSet<>();
        element.fieldNames().forEachRemaining(keys::add);
        assertEquals(EXPECTED_PROPERTIES, keys,
                "element must carry exactly the nine pre-existing properties plus reminderCount");

        JsonNode reminderCount = element.get("reminderCount");
        assertNotNull(reminderCount, "reminderCount must be present for every element");
        assertTrue(!reminderCount.isNull(), "reminderCount must never be null");
        assertTrue(reminderCount.isIntegralNumber(), "reminderCount must serialize as a number");
        assertTrue(reminderCount.asLong() >= 0, "reminderCount must be non-negative");
    }

    private Map<String, JsonNode> byInvoiceNumber(JsonNode body) {
        Map<String, JsonNode> byNumber = new HashMap<>();
        body.forEach(element -> byNumber.put(element.get("invoiceNumber").asText(), element));
        return byNumber;
    }

    /**
     * Builds a request post-processor whose authenticated principal is an
     * {@link AppUserDetails}, so both the INVOICE_VIEW authority check and the
     * customer-ownership restriction (via the caller's customerId) behave exactly
     * as they do for a JWT-authenticated caller.
     */
    private RequestPostProcessor caller(Long customerId, String... authorities) {
        User user = User.builder()
                .username("contract-test-user")
                .password("unused")
                .customerId(customerId)
                .build();
        List<SimpleGrantedAuthority> grants = Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        AppUserDetails principal = new AppUserDetails(user, grants);
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(principal, null, grants));
    }
}
