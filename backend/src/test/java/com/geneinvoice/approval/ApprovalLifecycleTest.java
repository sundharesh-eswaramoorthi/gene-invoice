package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentStatus;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What happens to a waiting change when its record's life goes on (§5.11.7, D7): the customer deleted closes it; a
 * move closes it when the mover approves changes where the customer was, and is refused otherwise (SC-4); a move and
 * an approval on one customer take turns and never deadlock (CF-8, SC-19); its maker is deactivated rather than
 * deleted; and a change of the region's limits leaves it waiting.
 */
class ApprovalLifecycleTest extends ApprovalTestBase {

    @Autowired JdbcTemplate jdbc;

    Region north;
    Region south;
    User maker;
    User approver;
    Customer acme;

    @BeforeEach
    void setUp() {
        north = workRegion("NORTH");
        south = workRegion("SOUTH");
        maker = makerIn(north);
        approver = approverIn(north);
        acme = customer("Acme Ltd", north);
    }

    @Test
    void deletingTheCustomerClosesWhatWaitsOnIt() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CREATE, "100.00");
        createInvoice(acme, widget, 500, maker).andExpect(status().isAccepted());
        PendingChange create = pendingChangeRepository.findAll().get(0);

        mockMvc.perform(delete("/api/customers/" + acme.getId()).with(as(admin))).andExpect(status().isOk());

        PendingChange closed = reload(create);
        assertThat(closed.getStatus()).isEqualTo(PendingStatus.CLOSED);
        assertThat(closed.getDecidedByUserId()).isNull();
        assertThat(closed.getDecidedAt()).isNotNull();
        assertThat(closed.getDecisionNote()).isEqualTo("Customer deleted");
        assertThat(audit("CUSTOMER", acme.getId(), "CHANGE_CLOSED")).singleElement().satisfies(a -> {
            assertThat(a.getPendingChangeId()).isEqualTo(create.getId());
            assertThat(a.getChangedByUserId()).isNull();
        });
        assertThat(noticesTo(maker, ApprovalNotifier.CLOSED)).hasSize(1);
        approve(create.getId(), approver).andExpect(status().isConflict());
        assertThat(invoiceRepository.count()).isZero();
    }

    /** SC-4: a move must never let someone without the approval right discard other people's requests. */
    @Test
    void aMoveByARegionMoverWhoCannotApproveIsRefusedWhileChangesWait() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice inv = invoice(acme, "500.00");
        cancel(inv.getId(), maker).andExpect(status().isAccepted());
        PendingChange pc = pendingOf("INVOICE", inv.getId());
        User mover = staffIn("mover", "CASHIER", north, AccessLevel.MANAGE);
        grant(mover, south, AccessLevel.MANAGE, RecordScope.ALL);
        long moves = customerRegionMoveRepository.count();

        move(acme, south, mover).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Acme Ltd has 1 change awaiting approval; they must be decided first"));

        assertThat(Region.idOf(customerRepository.findById(acme.getId()).orElseThrow().getRegion()))
                .isEqualTo(north.getId());
        assertThat(Region.idOf(invoiceRepository.findById(inv.getId()).orElseThrow().getRegion()))
                .isEqualTo(north.getId());
        assertThat(customerRegionMoveRepository.count()).isEqualTo(moves);
        assertThat(reload(pc).getStatus()).isEqualTo(PendingStatus.PENDING);
        assertThat(noticesTo(maker, ApprovalNotifier.CLOSED)).isEmpty();
    }

    @Test
    void aMoveBySomeoneWhoApprovesInTheRegionLeftClosesWhatWaits() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice inv = invoice(acme, "150.00");
        threshold(north, ApprovalOperation.INVOICE_CREATE, "100.00");
        cancel(inv.getId(), maker).andExpect(status().isAccepted());
        createInvoice(acme, widget, 500, maker).andExpect(status().isAccepted());
        PendingChange pc = pendingOf("INVOICE", inv.getId());

        JsonNode result = body(move(acme, south, admin).andExpect(status().isOk()));

        assertThat(result.at("/moved/other/PendingChangeMoveParticipant").asInt()).isEqualTo(2);
        assertThat(pendingChangeRepository.findAll()).allSatisfy(c -> {
            assertThat(c.getStatus()).isEqualTo(PendingStatus.CLOSED);
            assertThat(c.getDecisionNote()).isEqualTo("Customer moved from North to South");
            assertThat(c.getRegionId()).as("the region it was asked in").isEqualTo(north.getId());
        });
        assertThat(noticesTo(maker, ApprovalNotifier.CLOSED)).hasSize(2);
        approve(pc.getId(), approver).andExpect(status().isConflict());
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus().name()).isEqualTo("UNPAID");
    }

    /**
     * CF-8, SC-19: an approval and a move of the same customer both take the customer's row first, so one waits for
     * the other. The approval holding the customer while the move arrives: both succeed, one after the other. The
     * move holding it while the approval arrives: the move closes the change, and the approval is then a conflict.
     */
    @Test
    void aMoveAndADisputeApprovalOnOneCustomerTakeTurnsAndNeverDeadlock() throws Exception {
        User collector = collectorIn(north);
        grant(collector, south, AccessLevel.READ, RecordScope.ALL);
        invoice(acme, "500.00");
        actAs(admin);
        Payment payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("500.00"), "Bank", null, null, collector.getId(), null));
        SecurityContextHolder.clearContext();
        threshold(north, ApprovalOperation.DISPUTE_APPROVE, "100.00");
        User resolver = staffIn("resolver", "ADMIN", north, AccessLevel.MANAGE);
        User acmeLogin = customerUser("acme.login", acme.getId());

        // The approval first: the move arrives while it holds the customer.
        Long first = heldDisputeApproval(acmeLogin, resolver, payment.getId());
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            @SuppressWarnings("unchecked")
            Future<MockHttpServletResponse>[] moving = new Future[1];
            historyFaults.onNextSnapshotOf("PAYMENT", payment.getId(), () -> {
                moving[0] = other.submit(() -> move(acme, south, admin).andReturn().getResponse());
                awaitSomeoneWaitingOnALock(moving[0]);
            });
            approve(pendingOf("DISPUTE", first).getId(), approver).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));
            assertThat(moving[0].get(10, TimeUnit.SECONDS).getStatus()).as("the move waited, then went ahead")
                    .isEqualTo(200);
        } finally {
            other.shutdownNow();
        }
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.VOIDED);
        assertThat(Region.idOf(customerRepository.findById(acme.getId()).orElseThrow().getRegion()))
                .isEqualTo(south.getId());

        // The move first: the approval arrives while it holds the customer (now moving back to NORTH).
        threshold(south, ApprovalOperation.DISPUTE_APPROVE, "100.00");
        User southResolver = staffIn("resolver", "ADMIN", south, AccessLevel.MANAGE);
        User southApprover = approverIn(south);
        actAs(admin);
        Payment second = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("300.00"), "Bank", null, null, admin.getId(), null));
        SecurityContextHolder.clearContext();
        Long secondDispute = heldDisputeApproval(acmeLogin, southResolver, second.getId());
        PendingChange waiting = pendingOf("DISPUTE", secondDispute);
        ExecutorService approving = Executors.newSingleThreadExecutor();
        try {
            @SuppressWarnings("unchecked")
            Future<MockHttpServletResponse>[] approval = new Future[1];
            historyFaults.onNextSnapshotOf("CUSTOMER", acme.getId(), () -> {
                approval[0] = approving.submit(() -> approve(waiting.getId(), southApprover).andReturn().getResponse());
                awaitSomeoneWaitingOnALock(approval[0]);
            });
            move(acme, north, admin).andExpect(status().isOk());
            assertThat(approval[0].get(10, TimeUnit.SECONDS).getStatus())
                    .as("the move closed it while the approval waited").isEqualTo(409);
        } finally {
            approving.shutdownNow();
        }
        assertThat(reload(waiting).getStatus()).isEqualTo(PendingStatus.CLOSED);
        assertThat(disputeRepository.findById(secondDispute).orElseThrow().getStatus())
                .isEqualTo(DisputeStatus.PENDING);
        assertThat(paymentRepository.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.ACTIVE);
    }

    @Test
    void aMakerWithChangesOnRecordIsDeactivatedNotDeleted() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice inv = invoice(acme, "500.00");
        cancel(inv.getId(), maker).andExpect(status().isAccepted());
        withdraw(pendingOf("INVOICE", inv.getId()).getId(), maker).andExpect(status().isOk());

        mockMvc.perform(delete("/api/users/" + maker.getId()).with(as(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.deactivated").value(true));

        assertThat(userRepository.findById(maker.getId()).orElseThrow().isActive()).isFalse();
        assertThat(pendingChangeRepository.count()).isEqualTo(1);
    }

    @Test
    void changingTheRegionsLimitsLeavesWhatWaitsWaiting() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice inv = invoice(acme, "500.00");
        cancel(inv.getId(), maker).andExpect(status().isAccepted());
        PendingChange pc = pendingOf("INVOICE", inv.getId());

        putThresholds(Map.of("operation", "INVOICE_CANCEL", "amount", new BigDecimal("50.00")))
                .andExpect(status().isOk());
        assertThat(reload(pc).getStatus()).isEqualTo(PendingStatus.PENDING);

        putThresholds(Map.of("operation", "INVOICE_CANCEL", "amount", new BigDecimal("1000.00")))
                .andExpect(status().isAccepted());
        PendingChange loosening = pendingChangeRepository.findByOutstandingKey("REGION_THRESHOLDS:" + north.getId())
                .orElseThrow();
        approve(loosening.getId(), approver).andExpect(status().isOk());
        assertThat(approvalThresholdRepository.findByRegionIdAndOperation(north.getId(),
                ApprovalOperation.INVOICE_CANCEL).orElseThrow().getAmount()).isEqualByComparingTo("1000.00");
        assertThat(reload(pc).getStatus()).as("a limit held it; changing the limit decides nothing")
                .isEqualTo(PendingStatus.PENDING);
    }

    private ResultActions putThresholds(Map<String, Object> row) throws Exception {
        return mockMvc.perform(put("/api/regions/" + north.getId() + "/approval-thresholds").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("thresholds", List.of(row)))));
    }

    private ResultActions move(Customer customer, Region to, User as) throws Exception {
        return mockMvc.perform(post("/api/customers/" + customer.getId() + "/move-region").with(as(as))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("regionId", to.getId()))));
    }

    /** The customer disputes the payment; the resolver, who cannot approve changes, approves it and is held. */
    private Long heldDisputeApproval(User customerLogin, User resolver, Long paymentId) throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("targetType", "PAYMENT");
        req.put("targetId", paymentId);
        req.put("reason", "Paid twice");
        req.put("proposedChangeJson", "{\"action\":\"void\"}");
        Long disputeId = body(mockMvc.perform(post("/api/disputes").with(as(customerLogin))
                .contentType(MediaType.APPLICATION_JSON).content(json(req))).andExpect(status().isOk()))
                .get("id").asLong();
        mockMvc.perform(post("/api/disputes/" + disputeId + "/approve").with(as(resolver))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isAccepted());
        return disputeId;
    }

    /** Until another session is seen waiting on a lock; its finishing first means it never waited. */
    private void awaitSomeoneWaitingOnALock(Future<?> running) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            if (running.isDone()) fail("finished without waiting for the customer's lock");
            Long waiting = jdbc.queryForObject(
                    "select count(*) from information_schema.sessions where blocker_id is not null", Long.class);
            if (waiting != null && waiting > 0) return;
            Thread.onSpinWait();
        }
        fail("was never seen waiting for the customer's lock");
    }
}
