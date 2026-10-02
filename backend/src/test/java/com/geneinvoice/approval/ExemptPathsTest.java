package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the gate never stops (§5.11.1): the system's own bookkeeping (the promise sweep, recomputing, re-reading
 * a customer's promises, credit applied to an approved invoice) holds nothing even where every limit is zero. A
 * caller that cannot take a hold fails closed and leaves nothing behind, and a change above a limit with nobody
 * asking for it is a fault, never an authentication failure.
 */
class ExemptPathsTest extends ApprovalTestBase {

    @Autowired MakerChecker makerChecker;
    @Autowired PlatformTransactionManager txManager;

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
    void theSystemsOwnBookkeepingHoldsNothingEvenAtZeroLimits() throws Exception {
        // Credit on account, and a promise already due.
        actAs(admin);
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(), new BigDecimal("300.00"), "Bank",
                null, null, collector.getId(), null));
        Long overdue = promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(),
                new BigDecimal("900.00"), Today.date(), collector.getId(), null, null)).id();
        SecurityContextHolder.clearContext();
        PaymentPromise due = promiseRepository.findById(overdue).orElseThrow();
        due.setPromisedDate(Today.date().minusDays(2));
        promiseRepository.save(due);
        everyLimitAtZero(north);
        everyLimitAtZero(defaultRegion());

        promiseService.sweepOverdue();
        actAs(admin);
        promiseService.recomputeAll(true);
        promiseService.reevaluateForCustomer(acme.getId());
        SecurityContextHolder.clearContext();
        assertThat(pendingChangeRepository.count()).isZero();
        assertThat(promiseRepository.findById(overdue).orElseThrow().getStatus()).isNotEqualTo(PromiseStatus.OPEN);

        // An approved create takes the customer's credit; that is part of the approved change, not a new one.
        Product gadget = product("Gadget", "250.00");
        JsonNode held = body(createInvoice(acme, gadget, 2, cashier).andExpect(status().isAccepted()));
        JsonNode detail = body(approve(held.get("pendingChange").get("id").asLong(), approver)
                .andExpect(status().isOk()));

        assertThat(detail.get("status").asText()).isEqualTo("APPROVED");
        Invoice made = invoiceRepository.findById(detail.get("result").get("entityId").asLong()).orElseThrow();
        assertThat(made.getPaidAmount()).isEqualByComparingTo("300.00");
        assertThat(made.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
        assertThat(customerRepository.findById(acme.getId()).orElseThrow().getCreditBalance())
                .isEqualByComparingTo("0.00");
        assertThat(pendingChangeRepository.findAll()).singleElement()
                .satisfies(pc -> assertThat(pc.getStatus()).isEqualTo(PendingStatus.APPROVED));
    }

    /** A caller that cannot take a hold fails closed; its transaction takes the pending change with it. */
    @Test
    void theOldMethodsCannotBeHeldSoTheyRefuseAndKeepNothing() {
        invoice(acme, "500.00");
        everyLimitAtZero(north);
        actAs(cashier);

        assertThatThrownBy(() -> paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("50.00"), "Bank", null, null, collector.getId(), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("held for approval");
        actAs(collector);
        assertThatThrownBy(() -> promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(),
                new BigDecimal("50.00"), Today.date().plusDays(3), collector.getId(), null, null)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(pendingChangeRepository.count()).isZero();
        assertThat(paymentRepository.count()).isZero();
        assertThat(promiseRepository.count()).isZero();
        assertThat(audit("CUSTOMER", acme.getId(), "CHANGE_REQUESTED")).isEmpty();
    }

    /** No one asked: a system path that meets a limit is a bug, not a sign-in problem. */
    @Test
    void aChangeAboveTheLimitWithNobodyAskingIsAFault() {
        threshold(north, ApprovalOperation.PAYMENT_RECORD, "10.00");
        Gated g = new Gated(ApprovalOperation.PAYMENT_RECORD, null, "New payment from Acme Ltd", "PAYMENT", null,
                null, acme.getId(), "Acme Ltd", north.getId(), null, new BigDecimal("50.00"), Map.of(), null, null,
                "Record payment of ₹50.00 from Acme Ltd", false);
        TransactionTemplate tx = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> makerChecker.gate(g)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PAYMENT_RECORD above threshold with no principal");

        assertThat(pendingChangeRepository.count()).isZero();
    }
}
