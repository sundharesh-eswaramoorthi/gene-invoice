package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * At most one change waits on a record (§5.11.7): the same maker asking again for the same thing replaces their
 * request; anything else must wait for it to be decided (409, skipped in bulk). A create has no record yet, so
 * any number may wait.
 */
class OutstandingChangeTest extends ApprovalTestBase {

    Region north;
    User collector;
    User otherCollector;
    Customer acme;
    PaymentPromise promise;

    @BeforeEach
    void setUp() {
        north = workRegion("NORTH");
        collector = collectorIn(north);
        otherCollector = collectorIn(north);
        acme = customer("Acme Ltd", north);
        promise = promise(acme, "1000.00", collector);
        threshold(north, ApprovalOperation.PROMISE_UPDATE, "100.00");
        threshold(north, ApprovalOperation.PROMISE_OVERRIDE, "100.00");
    }

    @Test
    void theSameMakerAskingAgainForTheSameThingReplacesTheirRequest() throws Exception {
        putPromise(promise.getId(), "600.00", promise.getPromisedDate(), null, collector)
                .andExpect(status().isAccepted());
        PendingChange first = pendingOf("PROMISE", promise.getId());

        JsonNode second = body(putPromise(promise.getId(), "500.00", promise.getPromisedDate(), null, collector)
                .andExpect(status().isAccepted()));

        PendingChange now = pendingOf("PROMISE", promise.getId());
        assertThat(second.get("pendingChange").get("id").asLong()).isEqualTo(now.getId());
        assertThat(second.get("pendingChange").get("supersededId").asLong()).isEqualTo(first.getId());
        assertThat(now.getAmount()).isEqualByComparingTo("500.00");
        PendingChange replaced = reload(first);
        assertThat(replaced.getStatus()).isEqualTo(PendingStatus.SUPERSEDED);
        assertThat(replaced.getSupersededById()).isEqualTo(now.getId());
        assertThat(replaced.getDecidedAt()).isEqualTo(now.getRequestedAt());
        assertThat(pendingCount()).isEqualTo(1);
    }

    @Test
    void anotherOperationOrAnotherMakerMustWaitForTheChangeToBeDecided() throws Exception {
        putPromise(promise.getId(), "600.00", promise.getPromisedDate(), null, collector)
                .andExpect(status().isAccepted());
        PendingChange waiting = pendingOf("PROMISE", promise.getId());
        String message = "Promise #" + promise.getId() + " from Acme Ltd already has a change awaiting approval (#"
                + waiting.getId() + ", Change promise by " + collector.getFullName()
                + "). It must be approved, rejected or withdrawn first.";

        // The same maker, another operation on the same promise.
        override(promise.getId(), "KEPT", "Settled", collector).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(message));
        // Another maker, the same operation.
        putPromise(promise.getId(), "500.00", promise.getPromisedDate(), null, otherCollector)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(message));

        assertThat(pendingChangeRepository.findAll()).singleElement()
                .satisfies(pc -> assertThat(pc.getId()).isEqualTo(waiting.getId()));
        // Once it is withdrawn, the promise can be asked about again.
        withdraw(waiting.getId(), collector).andExpect(status().isOk());
        override(promise.getId(), "KEPT", "Settled", collector).andExpect(status().isAccepted());
    }

    @Test
    void aBulkActionSkipsARecordWithAChangeWaiting() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_TERMS, "100.00");
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        User cashier = makerIn(north);
        User otherCashier = makerIn(north);
        Invoice waiting = invoice(acme, "500.00");
        Invoice free = invoice(acme, "600.00");
        mockMvc.perform(patch("/api/invoices/" + waiting.getId()).with(as(cashier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("dueDate", waiting.getDueDate().plusDays(30).toString()))))
                .andExpect(status().isAccepted());

        JsonNode result = body(mockMvc.perform(post("/api/invoices/bulk").with(as(otherCashier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "CANCEL", "ids", List.of(waiting.getId(), free.getId())))))
                .andExpect(status().isOk()));

        assertThat(result.get("skipped")).singleElement()
                .satisfies(s -> assertThat(s.get("id").asLong()).isEqualTo(waiting.getId()));
        assertThat(result.get("pending")).singleElement()
                .satisfies(p -> assertThat(p.get("id").asLong()).isEqualTo(free.getId()));
        assertThat(pendingOf("INVOICE", waiting.getId()).getOperation()).isEqualTo(ApprovalOperation.INVOICE_TERMS);
    }

    /** A change waiting on an invoice that is then cancelled can never be made; it is closed, not left to fail. */
    @Test
    void cancellingAnInvoiceClosesTheChangeWaitingOnIt() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_TERMS, "100.00");
        User cashier = makerIn(north);
        Invoice inv = invoice(acme, "500.00");
        mockMvc.perform(patch("/api/invoices/" + inv.getId()).with(as(cashier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("dueDate", inv.getDueDate().plusDays(30).toString()))))
                .andExpect(status().isAccepted());
        PendingChange pc = pendingOf("INVOICE", inv.getId());

        cancel(inv.getId(), admin).andExpect(status().isOk());

        PendingChange closed = reload(pc);
        assertThat(closed.getStatus()).isEqualTo(PendingStatus.CLOSED);
        assertThat(closed.getDecisionNote()).isEqualTo("Invoice cancelled");
        assertThat(closed.getDecidedAt()).isNotNull();
        assertThat(audit("INVOICE", inv.getId(), PendingChangeCloser.CHANGE_CLOSED)).singleElement()
                .satisfies(a -> assertThat(a.getPendingChangeId()).isEqualTo(pc.getId()));
        assertThat(noticesTo(cashier, ApprovalNotifier.CLOSED)).singleElement()
                .satisfies(n -> assertThat(n.getLink()).isEqualTo("/approvals/" + pc.getId()));
    }

    @Test
    void createsHaveNoRecordYetSoAnyNumberMayWait() throws Exception {
        threshold(north, ApprovalOperation.PAYMENT_RECORD, "100.00");
        invoice(acme, "900.00");

        pay(acme, "300.00", collector, collector).andExpect(status().isAccepted());
        pay(acme, "300.00", collector, collector).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.pendingChange.supersededId").doesNotExist());
        pay(acme, "400.00", collector, otherCollector).andExpect(status().isAccepted());

        assertThat(pendingChangeRepository.findAll()).hasSize(3).allSatisfy(pc -> {
            assertThat(pc.getStatus()).isEqualTo(PendingStatus.PENDING);
            assertThat(pc.getOutstandingKey()).isNull();
        });
    }
}
