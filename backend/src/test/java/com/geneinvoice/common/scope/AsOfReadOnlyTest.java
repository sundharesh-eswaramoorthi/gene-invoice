package com.geneinvoice.common.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.common.scope.ViewScopeInterceptor.Mode;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.VersionRow;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * While a past date is being viewed nothing can be changed (D2): every write the application has, sent with a past
 * {@code asOf}, is refused with {@code AS_OF_READ_ONLY} before it runs, and not a row or a version moves. The
 * housekeeping writes of §5.2.4 are the exception: they answer live.
 */
class AsOfReadOnlyTest extends IntegrationTestBase {

    /** Every table a write could touch, and every version table. */
    static final List<String> TABLES = List.of("customers", "invoices", "invoice_items", "payments",
            "payment_allocations", "payment_promises", "disputes", "customer_pocs", "products", "users", "roles",
            "regions", "user_region_grants", "documents", "emails", "email_recipients", "notifications",
            "audit_logs", "customer_region_moves", "gmail_connections", "customer_versions", "invoice_versions",
            "payment_versions", "payment_allocation_versions", "promise_versions", "dispute_versions",
            "customer_poc_versions", "product_versions", "entity_link_versions", "history_meta",
            "invoice_number_sequence", "tasks", "record_assignees", "task_versions", "pending_changes",
            "approval_thresholds", "approval_usage", "automation_rules", "automation_rule_regions",
            "automation_rule_revisions", "automation_runs", "automation_effects", "automation_events");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Autowired InvoiceService invoiceService;
    @Autowired JdbcTemplate jdbc;

    User admin;
    String yesterday;

    @BeforeEach
    void somethingToChange() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        actAs(admin);
        Customer acme = customer("Acme Ltd");
        Product widget = product("Widget", "10.00");
        invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, seller().getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
        yesterday = Today.date().minusDays(1).toString();
    }

    /** Row counts, and for each version table its open versions: a write that only closes one shows there. */
    private Map<String, Long> rows() {
        Map<String, Long> rows = new TreeMap<>();
        for (String table : TABLES) {
            rows.put(table, jdbc.queryForObject("select count(*) from " + table, Long.class));
            if (table.endsWith("_versions")) {
                rows.put(table + " open", jdbc.queryForObject("select count(*) from " + table + " where valid_to = ?",
                        Long.class, Timestamp.from(VersionRow.OPEN)));
            }
        }
        return rows;
    }

    /** Each write endpoint (method and path pattern) with the media type it consumes, if it names one. */
    private Map<String, String> writes() {
        Map<String, String> writes = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            Set<MediaType> consumes = info.getConsumesCondition().getConsumableMediaTypes();
            String type = consumes.isEmpty() ? MediaType.APPLICATION_JSON_VALUE : consumes.iterator().next().toString();
            for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                for (String path : info.getPatternValues()) {
                    if (!path.startsWith("/api/")) continue;
                    if (ViewScopeInterceptor.classify(e.getValue(), method.name()) != Mode.WRITE) continue;
                    writes.put(method.name() + " " + path, type);
                }
            }
        }
        return writes;
    }

    private MockHttpServletResponse send(String endpoint, String mediaType) throws Exception {
        return send(endpoint, mediaType, "{}");
    }

    private MockHttpServletResponse send(String endpoint, String mediaType, String body) throws Exception {
        String method = endpoint.substring(0, endpoint.indexOf(' '));
        String uri = endpoint.substring(endpoint.indexOf(' ') + 1).replaceAll("\\{[^}]+}", "1");
        MockHttpServletRequestBuilder request = mediaType.startsWith("multipart/")
                ? multipart(HttpMethod.valueOf(method), uri)
                : request(HttpMethod.valueOf(method), uri).contentType(mediaType).content(body);
        return mockMvc.perform(request.param("asOf", yesterday).with(as(admin))).andReturn().getResponse();
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body.isBlank() ? "{}" : body);
    }

    @Test
    void everyWriteIsRefusedWhileAPastDateIsViewedAndNothingChanges() throws Exception {
        Map<String, String> writes = writes();
        assertThat(writes).as("the write endpoints were found").hasSizeGreaterThan(40)
                .containsKeys("POST /api/invoices", "PATCH /api/invoices/{id}", "DELETE /api/customers/{id}",
                        "POST /api/customers/{id}/move-region", "POST /api/documents", "PUT /api/users/{id}/region-grants");
        Map<String, Long> before = rows();

        List<String> notRefused = new ArrayList<>();
        for (Map.Entry<String, String> write : writes.entrySet()) {
            MockHttpServletResponse response = send(write.getKey(), write.getValue());
            JsonNode body = json(response);
            if (response.getStatus() != 400 || !AsOfException.READ_ONLY.equals(body.path("error").asText())) {
                notRefused.add(write.getKey() + " -> " + response.getStatus() + " " + body.path("error").asText());
            }
        }

        assertThat(notRefused).as("every write refuses a past date").isEmpty();
        assertThat(rows()).as("not a row or a version changed").isEqualTo(before);
    }

    @Test
    void housekeepingIsDoneLiveWhileAPastDateIsViewed() throws Exception {
        for (String endpoint : ViewScopeInventoryTest.HOUSEKEEPING) {
            MockHttpServletResponse response = send(endpoint, MediaType.APPLICATION_JSON_VALUE);
            assertThat(json(response).path("error").asText()).as(endpoint).isNotEqualTo(AsOfException.READ_ONLY);
        }
        for (String endpoint : List.of("POST /api/notifications/mark-all-read", "POST /api/inbox/mark-all-read",
                "POST /api/admin/history/reconcile", "POST /api/users/export", "POST /api/roles/export")) {
            MockHttpServletResponse response = send(endpoint, MediaType.APPLICATION_JSON_VALUE,
                    "{\"selectAllMatchingFilter\":true}");
            assertThat(response.getStatus()).as(endpoint).isEqualTo(200);
            assertThat(response.getHeader(ViewScopeInterceptor.HEADER)).as(endpoint)
                    .isEqualTo(ViewScopeInterceptor.LIVE);
        }
    }

    /** Someone who may sell in the default region: every invoice has a Sales POC. */
    private User seller() {
        return user("sid.sales", DataSeeder.ROLE_SALES_POC);
    }
}
