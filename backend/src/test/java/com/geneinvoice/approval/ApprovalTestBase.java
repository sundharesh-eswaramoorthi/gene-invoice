package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Helpers for the maker-checker tests (§8.6); no test of its own. Thresholds made here measure each change alone
 * ({@code windowHours = 0}) unless a test asks for a window.
 */
public abstract class ApprovalTestBase extends IntegrationTestBase {

    private static final AtomicInteger NEXT = new AtomicInteger();

    @Autowired protected InvoiceService invoiceService;
    @Autowired protected PaymentService paymentService;
    @Autowired protected PaymentPromiseService promiseService;
    @Autowired protected AuditLogRepository auditLogRepository;

    protected User admin;
    protected User seller;
    protected Product widget;

    @BeforeEach
    void approvalFixtures() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        seller = user("sid.sales", DataSeeder.ROLE_SALES_POC);
        widget = product("Widget", "1.00");
    }

    protected ApprovalThreshold threshold(Region region, ApprovalOperation op, String amount) {
        return threshold(region, op, amount, 0);
    }

    protected ApprovalThreshold threshold(Region region, ApprovalOperation op, String amount, int windowHours) {
        approvalThresholdRepository.findByRegionIdAndOperation(region.getId(), op)
                .ifPresent(approvalThresholdRepository::delete);
        return approvalThresholdRepository.save(ApprovalThreshold.builder()
                .region(region).operation(op).amount(new BigDecimal(amount)).windowHours(windowHours)
                .updatedByUserId(admin.getId()).updatedAt(AppClock.now())
                .build());
    }

    /** Someone who may approve changes in the region, and nowhere else: the ADMIN role, APPROVE/ALL there. */
    protected User approverIn(Region region) {
        return staffIn("approver", "ADMIN", region, AccessLevel.APPROVE);
    }

    /** A cashier who manages invoices in the region (MANAGE/ALL there, nothing elsewhere); never an approver. */
    protected User makerIn(Region region) {
        return staffIn("maker", "CASHIER", region, AccessLevel.MANAGE);
    }

    protected User staffIn(String prefix, String role, Region region, AccessLevel level) {
        User u = user(prefix + "." + region.getCode().toLowerCase(Locale.ROOT) + NEXT.incrementAndGet(), role);
        grantRepository.deleteAllOfUser(u.getId());
        grant(u, region, level, RecordScope.ALL);
        return u;
    }

    /** A collections person: records payments and makes, changes and overrides promises there (MANAGE/ALL). */
    protected User collectorIn(Region region) {
        return staffIn("collector", DataSeeder.ROLE_COLLECTION_POC, region, AccessLevel.MANAGE);
    }

    /** A region the administrator, the seller and the test's makers can all work in. */
    protected Region workRegion(String code) {
        Region region = region(code);
        grant(admin, region, AccessLevel.APPROVE, RecordScope.ALL);
        grant(seller, region, AccessLevel.READ, RecordScope.ALL);
        return region;
    }

    /** An unpaid invoice of one line, made by the administrator. */
    protected Invoice invoice(Customer customer, String amount) {
        actAs(admin);
        try {
            return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(customer.getId(), null, null,
                    seller.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal(amount)))));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** An open promise, made by the administrator, due in ten days. */
    protected PaymentPromise promise(Customer customer, String amount, User collectionPoc) {
        actAs(admin);
        try {
            Long id = promiseService.create(new PromiseDtos.CreatePromiseRequest(customer.getId(),
                    new BigDecimal(amount), Today.date().plusDays(10), collectionPoc.getId(), null, null)).id();
            return promiseRepository.findById(id).orElseThrow();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** {@code POST /api/invoices} with one line of the product at its catalogue price. */
    protected ResultActions createInvoice(Customer customer, Product product, int quantity, User as)
            throws Exception {
        return mockMvc.perform(post("/api/invoices").with(as(as)).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("customerId", customer.getId(), "salesPocUserId", seller.getId(),
                        "items", List.of(Map.of("productId", product.getId(), "quantity", quantity))))));
    }

    /** {@code POST /api/payments}, applied to the customer's oldest invoices first. */
    protected ResultActions pay(Customer customer, String amount, User collectionPoc, User as) throws Exception {
        return mockMvc.perform(post("/api/payments").with(as(as)).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("customerId", customer.getId(), "amount", new BigDecimal(amount),
                        "method", "Bank", "collectionPocUserId", collectionPoc.getId()))));
    }

    /** {@code PUT /api/promises/{id}}: every term replaced, the POC and invoices left as they are. */
    protected ResultActions putPromise(Long promiseId, String amount, LocalDate promisedDate, String notes, User as)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", new BigDecimal(amount));
        body.put("promisedDate", promisedDate.toString());
        body.put("notes", notes);
        return mockMvc.perform(put("/api/promises/" + promiseId).with(as(as))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    protected ResultActions override(Long promiseId, String status, String reason, User as) throws Exception {
        return mockMvc.perform(post("/api/promises/" + promiseId + "/override").with(as(as))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", status, "reason", reason))));
    }

    /** Every gated operation held above zero in the region. */
    protected void everyLimitAtZero(Region region) {
        for (ApprovalOperation op : ApprovalOperation.values()) {
            if (op.amountGated()) threshold(region, op, "0.00");
        }
    }

    protected long pendingCount() {
        return pendingChangeRepository.findAll().stream().filter(PendingChange::isPending).count();
    }

    protected ResultActions cancel(Long invoiceId, User as) throws Exception {
        return mockMvc.perform(post("/api/invoices/" + invoiceId + "/cancel").with(as(as)));
    }

    protected ResultActions approve(Long pendingChangeId, User as) throws Exception {
        return approve(pendingChangeId, as, null);
    }

    protected ResultActions approve(Long pendingChangeId, User as, String note) throws Exception {
        return mockMvc.perform(post("/api/approvals/" + pendingChangeId + "/approve").with(as(as))
                .contentType(MediaType.APPLICATION_JSON).content(note == null ? "{}" : json(Map.of("note", note))));
    }

    protected ResultActions reject(Long pendingChangeId, User as, String note) throws Exception {
        return mockMvc.perform(post("/api/approvals/" + pendingChangeId + "/reject").with(as(as))
                .contentType(MediaType.APPLICATION_JSON).content(note == null ? "{}" : json(Map.of("note", note))));
    }

    protected ResultActions withdraw(Long pendingChangeId, User as) throws Exception {
        return mockMvc.perform(post("/api/approvals/" + pendingChangeId + "/withdraw").with(as(as)));
    }

    /** The change waiting on a record, e.g. {@code pendingOf("INVOICE", id)}. */
    protected PendingChange pendingOf(String entityType, Long id) {
        return pendingChangeRepository.findByOutstandingKey(entityType + ":" + id).orElseThrow();
    }

    protected PendingChange reload(PendingChange pc) {
        return pendingChangeRepository.findById(pc.getId()).orElseThrow();
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected List<AuditLog> audit(String entityType, Long id, String action) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, id).stream()
                .filter(a -> a.getAction().equals(action)).toList();
    }

    protected List<Notification> noticesTo(User u, String type) {
        return notificationRepository.findAll().stream()
                .filter(n -> n.getUserId().equals(u.getId()) && n.getType().equals(type)).toList();
    }
}
