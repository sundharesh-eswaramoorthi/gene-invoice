package com.geneinvoice.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BulkActionTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired PlatformTransactionManager transactionManager;

    User admin;
    User sales;
    User otherSales;
    User collections;
    Customer acme;
    Customer globex;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        otherSales = user("sid.sales", DataSeeder.ROLE_SALES_POC);
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        globex = customer("Globex Corp");
        widget = product("Widget", "10.00");
        actAs(admin);
    }

    private Invoice invoice(Customer c, String unitPrice, User poc) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                c.getId(), null, null, poc.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal(unitPrice)))));
    }

    private JsonNode bulk(User caller, String path, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post(path).with(as(caller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions addPoc(User caller, Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post("/api/customers/bulk").with(as(caller))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    @Test
    void anAddPocRequestNoRowCouldSatisfyIsOneBadRequest() throws Exception {
        addPoc(admin, request("ADD_POC", "ids", List.of(acme.getId(), globex.getId()),
                        "params", Map.of("userId", collections.getId(), "pocType", "SALES")))
                .andExpect(status().isBadRequest());

        User gone = user("gus.gone", DataSeeder.ROLE_COLLECTION_POC);
        gone.setActive(false);
        userRepository.save(gone);
        addPoc(admin, request("ADD_POC", "ids", List.of(acme.getId()),
                        "params", Map.of("userId", gone.getId(), "pocType", "COLLECTION")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bulkAddPocWithoutPocAssignIsForbiddenNotABadRequest() throws Exception {
        String roleName = "CUSTOMER_MANAGE_NO_POC";
        mockMvc.perform(post("/api/roles").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", roleName,
                                "privileges", List.of("CUSTOMER_VIEW", "CUSTOMER_MANAGE")))))
                .andExpect(status().isOk());
        User manager = user("mandy.manager", roleName);

        addPoc(manager, request("ADD_POC", "ids", List.of(acme.getId()),
                        "params", Map.of("userId", collections.getId(), "pocType", "COLLECTION")))
                .andExpect(status().isForbidden());
    }

    private Map<String, Object> request(String action, Object... kv) {
        Map<String, Object> m = new HashMap<>();
        m.put("action", action);
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void aPartialRunReportsWhichRowsSucceededAndWhichDidNot() throws Exception {
        Invoice cancellable = invoice(acme, "10.00", sales);
        Invoice paid = invoice(acme, "10.00", sales);
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("10.00"), "Cash", null, List.of(paid.getId()),
                collections.getId(), null));

        JsonNode result = bulk(admin, "/api/invoices/bulk",
                request("CANCEL", "ids", List.of(cancellable.getId(), paid.getId())));

        assertThat(result.get("requested").asInt()).isEqualTo(2);
        assertThat(result.get("succeeded").size()).isEqualTo(1);
        assertThat(result.get("succeeded").get(0).asLong()).isEqualTo(cancellable.getId());
        // Holding a payment is an eligibility rule, not something going wrong, so the row is
        // skipped with its reason rather than reported to the user as an error (TBL-05).
        assertThat(result.get("failed")).isEmpty();
        assertThat(result.get("skipped").size()).isEqualTo(1);
        assertThat(result.get("skipped").get(0).get("id").asLong()).isEqualTo(paid.getId());
        assertThat(result.get("skipped").get(0).get("reason").asText())
                .contains("Cannot cancel an invoice with payments");

        int accountedFor = result.get("succeeded").size() + result.get("failed").size()
                + result.get("skipped").size();
        assertThat(accountedFor).isEqualTo(result.get("requested").asInt());
        assertThat(invoiceRepository.findById(cancellable.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(invoiceRepository.findById(paid.getId()).orElseThrow().getStatus())
                .isNotEqualTo(InvoiceStatus.CANCELLED);
    }

    @Test
    void aRowThatDoesNotQualifyDoesNotRollBackTheRowsThatAlreadySucceeded() throws Exception {
        Invoice a = invoice(acme, "10.00", sales);
        Invoice b = invoice(acme, "10.00", sales);
        Invoice c = invoice(acme, "10.00", sales);
        invoiceService.cancel(b.getId());

        JsonNode result = bulk(admin, "/api/invoices/bulk",
                request("CANCEL", "ids", List.of(a.getId(), b.getId(), c.getId())));

        assertThat(result.get("succeeded").size()).isEqualTo(2);
        assertThat(result.get("failed")).isEmpty();
        assertThat(result.get("skipped").size()).isEqualTo(1);
        assertThat(invoiceRepository.findById(a.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(invoiceRepository.findById(c.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
    }

    @Test
    void aBulkRequestNamingMoreIdsThanTheLimitIsRefused() throws Exception {
        List<Long> tooMany = java.util.stream.LongStream
                .rangeClosed(1, TableQueryExecutor.BULK_ID_LIMIT + 1).boxed().toList();

        mockMvc.perform(post("/api/products/bulk").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("ACTIVATE", "ids", tooMany))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.ids").exists());
    }

    @Test
    void aBulkRequestNamingExactlyTheLimitIsAccepted() throws Exception {
        List<Long> atTheLimit = java.util.stream.LongStream
                .rangeClosed(1, TableQueryExecutor.BULK_ID_LIMIT).boxed().toList();

        mockMvc.perform(post("/api/products/bulk").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("ACTIVATE", "ids", atTheLimit))))
                .andExpect(status().isOk());
    }

    @Test
    void aBulkCancelThatLosesTheRaceSkipsTheRowRatherThanClaimingIt() throws Exception {
        Invoice contested = invoice(acme, "10.00", sales);
        Invoice untouched = invoice(acme, "10.00", sales);

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch cancelInFlight = new CountDownLatch(1);
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();

        Thread holder = new Thread(() -> {
            actAs(admin);
            try {
                transactions.executeWithoutResult(status -> {
                    invoiceService.cancel(contested.getId());
                    cancelInFlight.countDown();
                    sleep(600);
                });
            } catch (Throwable t) {
                cancelInFlight.countDown();
                holderFailure.set(t);
            }
        }, "cancel-holder");
        holder.start();
        cancelInFlight.await(5, TimeUnit.SECONDS);

        JsonNode result = bulk(admin, "/api/invoices/bulk",
                request("CANCEL", "ids", List.of(contested.getId(), untouched.getId())));
        holder.join(30_000);
        assertThat(holderFailure.get()).isNull();

        assertThat(result.get("succeeded").size()).isEqualTo(1);
        assertThat(result.get("succeeded").get(0).asLong()).isEqualTo(untouched.getId());
        assertThat(result.get("failed"))
                .as("losing a race is not something going wrong for the user to read as an error")
                .isEmpty();
        assertThat(result.get("skipped").size()).isEqualTo(1);
        assertThat(result.get("skipped").get(0).get("id").asLong()).isEqualTo(contested.getId());

        long entries = auditLogRepository.findAll().stream()
                .filter(a -> "INVOICE".equals(a.getEntityType())
                        && contested.getId().equals(a.getEntityId()))
                .filter(a -> "INVOICE_CANCELLED".equals(a.getAction()))
                .count();
        assertThat(entries).isEqualTo(1);
        assertThat(invoiceRepository.findById(contested.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void ineligibleRowsAreReportedAsSkippedRatherThanFailed() throws Exception {
        var alreadyActive = productRepository.save(
                com.geneinvoice.product.Product.builder()
                        .name("Gizmo").price(new BigDecimal("5.00")).active(true).build());

        JsonNode result = bulk(admin, "/api/products/bulk",
                request("ACTIVATE", "ids", List.of(alreadyActive.getId())));

        assertThat(result.get("succeeded").size()).isZero();
        assertThat(result.get("failed").size()).isZero();
        assertThat(result.get("skipped").size()).isEqualTo(1);
        assertThat(result.get("skipped").get(0).get("reason").asText()).isEqualTo("Already active");
    }

    @Test
    void idsOutsideTheCallersScopeAreExcludedNotAttempted() throws Exception {
        Invoice mine = invoice(acme, "10.00", sales);
        Invoice theirs = invoice(globex, "10.00", otherSales);

        JsonNode result = bulk(sales, "/api/invoices/bulk",
                request("CANCEL", "ids", List.of(mine.getId(), theirs.getId())));

        assertThat(result.get("requested").asInt()).isEqualTo(2);
        assertThat(result.get("succeeded").get(0).asLong()).isEqualTo(mine.getId());
        assertThat(result.get("skipped").get(0).get("id").asLong()).isEqualTo(theirs.getId());
        assertThat(invoiceRepository.findById(theirs.getId()).orElseThrow().getStatus())
                .isNotEqualTo(InvoiceStatus.CANCELLED);
    }

    @Test
    void anUnknownIdIsReportedAsSkippedWithoutSayingItDoesNotExist() throws Exception {
        Invoice own = invoice(acme, "10.00", sales);

        JsonNode result = bulk(admin, "/api/invoices/bulk",
                request("CANCEL", "ids", List.of(own.getId(), 99999999L)));

        assertThat(result.get("requested").asInt()).isEqualTo(2);
        assertThat(result.get("succeeded").get(0).asLong()).isEqualTo(own.getId());
        assertThat(result.get("skipped").get(0).get("id").asLong()).isEqualTo(99999999L);
        assertThat(result.get("skipped").get(0).get("reason").asText())
                .isEqualTo("Not found, or outside your scope or the current filter");
    }

    @Test
    void aCustomerScopedUserCannotReachAnotherCustomersRowsThroughABulkCall() throws Exception {
        Invoice theirs = invoice(globex, "10.00", sales);
        User acmeUser = customerUser("acme.user", acme.getId());

        mockMvc.perform(post("/api/invoices/bulk").with(as(acmeUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("CANCEL", "ids", List.of(theirs.getId())))))
                .andExpect(status().isForbidden());

        assertThat(invoiceRepository.findById(theirs.getId()).orElseThrow().getStatus())
                .isNotEqualTo(InvoiceStatus.CANCELLED);
    }

    @Test
    void anUnknownBulkActionIsRejectedWithTheListOfValidOnes() throws Exception {
        Invoice inv = invoice(acme, "10.00", sales);
        mockMvc.perform(post("/api/invoices/bulk").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("DROP_TABLE", "ids", List.of(inv.getId())))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("Unknown bulk action")));
    }

    @Test
    void selectAllMatchingTheFilterAppliesBeyondTheLoadedPage() throws Exception {
        for (int i = 0; i < 25; i++) invoice(acme, "10.00", sales);
        invoice(globex, "10.00", sales);

        JsonNode result = bulk(admin, "/api/invoices/bulk", request("CANCEL",
                "selectAllMatchingFilter", true,
                "filters", List.of("customerId:eq:" + acme.getId())));

        assertThat(result.get("requested").asInt()).isEqualTo(25);
        assertThat(result.get("succeeded").size()).isEqualTo(25);
        assertThat(invoiceRepository.findByCustomerIdOrderByInvoiceDateDesc(globex.getId()))
                .allMatch(i -> i.getStatus() != InvoiceStatus.CANCELLED);
    }

    @Test
    void theExactCountIsAvailableToTheClientBeforeItConfirms() throws Exception {
        for (int i = 0; i < 25; i++) invoice(acme, "10.00", sales);
        invoice(globex, "10.00", sales);

        mockMvc.perform(get("/api/invoices").with(as(admin))
                        .param("size", "10")
                        .param("filter", "customerId:eq:" + acme.getId()))
                .andExpect(jsonPath("$.totalElements").value(25));
    }

    @Test
    void aBulkCallWithNeitherIdsNorSelectAllIsRejected() throws Exception {
        mockMvc.perform(post("/api/invoices/bulk").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("CANCEL"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aBulkReassignmentWritesOneAuditEntryPerRecord() throws Exception {
        Invoice a = invoice(acme, "10.00", sales);
        Invoice b = invoice(acme, "10.00", sales);

        JsonNode result = bulk(admin, "/api/invoices/bulk", request("REASSIGN_SALES_POC",
                "ids", List.of(a.getId(), b.getId()),
                "params", Map.of("userId", otherSales.getId())));

        assertThat(result.get("succeeded").size()).isEqualTo(2);
        for (Invoice inv : List.of(a, b)) {
            assertThat(invoiceRepository.findById(inv.getId()).orElseThrow()
                    .getSalesPoc().getId()).isEqualTo(otherSales.getId());
            mockMvc.perform(get("/api/audit").with(as(admin))
                            .param("entityType", "INVOICE")
                            .param("entityId", inv.getId().toString()))
                    .andExpect(jsonPath("$[?(@.action=='INVOICE_UPDATED')]").isNotEmpty());
        }
    }

    @Test
    void reassignPocRequiresTheTargetUser() throws Exception {
        Invoice inv = invoice(acme, "10.00", sales);
        mockMvc.perform(post("/api/invoices/bulk").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("REASSIGN_SALES_POC", "ids", List.of(inv.getId())))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("params.userId")));
    }

    @Test
    void exportingTheSelectionReturnsCsvWithTheCallersVisibleColumns() throws Exception {
        Invoice inv = invoice(acme, "10.00", sales);

        String csv = mockMvc.perform(post("/api/invoices/export").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("EXPORT", "ids", List.of(inv.getId())))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(csv).startsWith(
                "Invoice #,Customer,Date,Due date,Total,Paid,Balance,Status,Overdue,Sales POC");
        assertThat(csv).contains("Acme Ltd").contains("sam.sales");
    }

    @Test
    void csvEscapesSeparatorsAndNeutralisesFormulaPrefixes() throws Exception {
        Customer tricky = customerRepository.save(
                Customer.builder().name("=CMD(),\"Ltd\"").build());
        Invoice inv = invoice(tricky, "10.00", sales);

        String csv = mockMvc.perform(post("/api/invoices/export").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("EXPORT", "ids", List.of(inv.getId())))))
                .andReturn().getResponse().getContentAsString();

        assertThat(csv).contains("\"'=CMD(),\"\"Ltd\"\"\"");
    }

    @Test
    void markingNotificationsReadInBulkTouchesOnlyTheCallersOwn() throws Exception {
        var mine = notificationRepository.save(com.geneinvoice.notification.Notification.builder()
                .userId(admin.getId()).type("T").title("mine").build());
        var theirs = notificationRepository.save(com.geneinvoice.notification.Notification.builder()
                .userId(sales.getId()).type("T").title("theirs").build());

        JsonNode result = bulk(admin, "/api/notifications/bulk",
                request("MARK_READ", "ids", List.of(mine.getId(), theirs.getId())));

        assertThat(result.get("requested").asInt()).isEqualTo(2);
        assertThat(result.get("skipped").get(0).get("id").asLong()).isEqualTo(theirs.getId());
        assertThat(notificationRepository.findById(mine.getId()).orElseThrow().isRead()).isTrue();
        assertThat(notificationRepository.findById(theirs.getId()).orElseThrow().isRead()).isFalse();
    }
}
