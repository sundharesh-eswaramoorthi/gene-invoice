package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An approval re-runs the maker's change against the record as it is now (§5.11.6): a change the rules now
 * refuse, one that meets someone else's edit of the same field, one that has grown, and one its maker can no
 * longer make are recorded as FAILED with the reason (never a 401); an edit of a field the change leaves alone
 * is kept. A record locked by someone else is a 409 and the change still waits.
 */
class ApproveFailureTest extends ApprovalTestBase {

    @Autowired Environment environment;

    Region north;
    User cashier;
    User collector;
    User approver;
    Customer acme;

    @BeforeEach
    void setUp() {
        north = workRegion("NORTH");
        cashier = makerIn(north);
        collector = collectorIn(north);
        approver = approverIn(north);
        acme = customer("Acme Ltd", north);
    }

    @Test
    void aCancelTheRulesNowRefuseFailsWithTheirReason() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice inv = invoice(acme, "500.00");
        cancel(inv.getId(), cashier).andExpect(status().isAccepted());
        PendingChange pc = pendingOf("INVOICE", inv.getId());
        // A small payment lands on the invoice while the cancel waits.
        pay(acme, "10.00", collector, cashier).andExpect(status().isOk());

        JsonNode detail = body(approve(pc.getId(), approver).andExpect(status().isOk()));

        assertThat(detail.get("status").asText()).isEqualTo("FAILED");
        assertThat(detail.get("failureReason").asText()).isEqualTo("Cannot cancel an invoice with payments; refund first");
        Invoice after = invoiceRepository.findById(inv.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
        assertThat(reload(pc).getOutstandingKey()).isNull();
        assertThat(noticesTo(cashier, ApprovalNotifier.FAILED)).singleElement()
                .satisfies(n -> assertThat(n.getMessage()).endsWith(": Cannot cancel an invoice with payments; refund first"));
    }

    /**
     * A PUT replaces every term, so the maker's request names the POC and notes as they saw them. The approval
     * re-bases it: what someone else changed meanwhile in a term the request leaves alone survives.
     */
    @Test
    void aPocReassignedWhileTheChangeWaitedIsKept() throws Exception {
        PaymentPromise promise = promise(acme, "1000.00", collector);
        threshold(north, ApprovalOperation.PROMISE_UPDATE, "100.00");
        mockMvc.perform(put("/api/promises/" + promise.getId()).with(as(collector))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("amount", 500, "promisedDate", promise.getPromisedDate().toString(),
                                "collectionPocUserId", collector.getId(), "invoiceIds", List.of()))))
                .andExpect(status().isAccepted());
        PendingChange pc = pendingOf("PROMISE", promise.getId());
        User other = collectorIn(north);
        mockMvc.perform(put("/api/promises/" + promise.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("amount", 1000, "promisedDate", promise.getPromisedDate().toString(),
                                "collectionPocUserId", other.getId(), "notes", "Called the customer"))))
                .andExpect(status().isOk());

        JsonNode detail = body(approve(pc.getId(), approver).andExpect(status().isOk()));

        assertThat(detail.get("status").asText()).isEqualTo("APPROVED");
        PaymentPromise after = promiseRepository.findById(promise.getId()).orElseThrow();
        assertThat(after.getAmount()).isEqualByComparingTo("500.00");
        assertThat(after.getCollectionPoc().getId()).as("the reassignment made meanwhile").isEqualTo(other.getId());
        assertThat(after.getNotes()).as("the note added meanwhile").isEqualTo("Called the customer");
        assertThat(audit("PROMISE", promise.getId(), "PROMISE_UPDATED")).anySatisfy(a -> {
            assertThat(a.getChangedByUserId()).isEqualTo(collector.getId());
            assertThat(a.getApprovedByUserId()).isEqualTo(approver.getId());
        });
    }

    @Test
    void aFieldSomeoneElseChangedMeanwhileFailsTheApproval() throws Exception {
        PaymentPromise promise = promise(acme, "1000.00", collector);
        threshold(north, ApprovalOperation.PROMISE_UPDATE, "100.00");
        putPromise(promise.getId(), "500.00", promise.getPromisedDate(), null, collector)
                .andExpect(status().isAccepted());
        PendingChange pc = pendingOf("PROMISE", promise.getId());
        // Within the limit, so made at once, by someone else.
        putPromise(promise.getId(), "950.00", promise.getPromisedDate(), null, admin).andExpect(status().isOk());

        JsonNode detail = body(approve(pc.getId(), approver).andExpect(status().isOk()));

        assertThat(detail.get("status").asText()).isEqualTo("FAILED");
        assertThat(detail.get("failureReason").asText()).isEqualTo("Amount was changed by someone else");
        assertThat(promiseRepository.findById(promise.getId()).orElseThrow().getAmount())
                .isEqualByComparingTo("950.00");
    }

    @Test
    void aChangeThatGrewSinceItWasAskedForFails() throws Exception {
        Invoice inv = invoice(acme, "500.00");
        actAs(admin);
        Payment paid = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("200.00"), "Bank", null, null, collector.getId(), null));
        SecurityContextHolder.clearContext();
        threshold(north, ApprovalOperation.INVOICE_TERMS, "100.00");
        mockMvc.perform(patch("/api/invoices/" + inv.getId()).with(as(cashier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("dueDate", inv.getDueDate().plusDays(30).toString()))))
                .andExpect(status().isAccepted());
        PendingChange pc = pendingOf("INVOICE", inv.getId());
        assertThat(pc.getAmount()).isEqualByComparingTo("300.00");
        // The payment is voided: the balance whose due date would move is back to 500.
        actAs(admin);
        paymentService.voidPayment(paid.getId());
        SecurityContextHolder.clearContext();

        JsonNode detail = body(approve(pc.getId(), approver).andExpect(status().isOk()));

        assertThat(detail.get("status").asText()).isEqualTo("FAILED");
        assertThat(detail.get("failureReason").asText())
                .isEqualTo("The amount grew from ₹300.00 to ₹500.00 since the request");
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getDueDate()).isEqualTo(inv.getDueDate());
    }

    /** G26: never a 401 for the checker, whoever the maker has become. */
    @Test
    void aMakerWhoIsGoneOrLostTheRightMakesTheApprovalFail() throws Exception {
        threshold(north, ApprovalOperation.PAYMENT_RECORD, "100.00");
        invoice(acme, "500.00");
        pay(acme, "300.00", collector, cashier).andExpect(status().isAccepted());
        pay(acme, "400.00", collector, collector).andExpect(status().isAccepted());
        PendingChange byCashier = pendingChangeRepository.findAll().stream()
                .filter(p -> p.getMakerUserId().equals(cashier.getId())).findFirst().orElseThrow();
        PendingChange byCollector = pendingChangeRepository.findAll().stream()
                .filter(p -> p.getMakerUserId().equals(collector.getId())).findFirst().orElseThrow();

        User gone = userRepository.findById(cashier.getId()).orElseThrow();
        gone.setActive(false);
        userRepository.save(gone);
        grantRepository.deleteAllOfUser(collector.getId());
        grant(collector, north, AccessLevel.READ, RecordScope.ALL);

        approve(byCashier.getId(), approver).andExpect(status().isOk());
        approve(byCollector.getId(), approver).andExpect(status().isOk());

        assertThat(reload(byCashier).getStatus()).isEqualTo(PendingStatus.FAILED);
        assertThat(reload(byCashier).getFailureReason()).isEqualTo("The requester's account is no longer active");
        assertThat(reload(byCollector).getStatus()).isEqualTo(PendingStatus.FAILED);
        assertThat(reload(byCollector).getFailureReason())
                .isEqualTo("The requester no longer has the right to make this change");
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void aProductTakenOutOfTheCatalogueMeanwhileFailsTheCreate() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CREATE, "100.00");
        Product gadget = product("Gadget", "250.00");
        Long id = body(createInvoice(acme, gadget, 2, cashier).andExpect(status().isAccepted()))
                .get("pendingChange").get("id").asLong();
        gadget.setActive(false);
        productRepository.save(gadget);
        long invoices = invoiceRepository.count();

        JsonNode detail = body(approve(id, approver).andExpect(status().isOk()));

        assertThat(detail.get("status").asText()).isEqualTo("FAILED");
        assertThat(detail.get("failureReason").asText()).isEqualTo("Gadget is no longer an active product");
        assertThat(invoiceRepository.count()).isEqualTo(invoices);
    }

    /** Someone else holding the customer: the approval is a 409, nothing is decided, and it can be tried again. */
    @Test
    void aCustomerLockedElsewhereIsAConflictAndTheChangeStillWaits() throws Exception {
        threshold(north, ApprovalOperation.PAYMENT_RECORD, "100.00");
        invoice(acme, "500.00");
        Long id = body(pay(acme, "300.00", collector, cashier).andExpect(status().isAccepted()))
                .get("pendingChange").get("id").asLong();

        CountDownLatch held = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> holding = holder.submit(() -> {
                try (Connection c = DriverManager.getConnection(environment.getProperty("spring.datasource.url"),
                        environment.getProperty("spring.datasource.username"), "")) {
                    c.setAutoCommit(false);
                    try (PreparedStatement st = c.prepareStatement("select id from customers where id = ? for update")) {
                        st.setLong(1, acme.getId());
                        try (ResultSet rs = st.executeQuery()) {
                            assertThat(rs.next()).isTrue();
                        }
                    }
                    held.countDown();
                    Thread.sleep(3000);
                    c.rollback();
                }
                return null;
            });
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            approve(id, approver).andExpect(status().isConflict());
            assertThat(pendingChangeRepository.findById(id).orElseThrow().getStatus()).isEqualTo(PendingStatus.PENDING);
            assertThat(paymentRepository.count()).isZero();

            holding.get(10, TimeUnit.SECONDS);
        } finally {
            holder.shutdownNow();
        }

        approve(id, approver).andExpect(status().isOk());
        assertThat(pendingChangeRepository.findById(id).orElseThrow().getStatus()).isEqualTo(PendingStatus.APPROVED);
        assertThat(paymentRepository.count()).isEqualTo(1);
    }
}
