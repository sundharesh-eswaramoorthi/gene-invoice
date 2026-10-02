package com.geneinvoice.approval;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Decisions racing one another, or racing the maker (§5.11.5–§5.11.7): every path takes the customer first, so
 * they queue rather than interleave, and whichever comes second finds the first one's result. A change is
 * applied at most once, at most one change waits on a record, and an approval never undoes a later cancel.
 */
class ApprovalConcurrencyTest extends ApprovalTestBase {

    Region north;
    User maker;
    Customer acme;

    @BeforeEach
    void setUp() {
        north = workRegion("NORTH");
        maker = makerIn(north);
        acme = customer("Acme Ltd", north);
    }

    @Test
    void twoCheckersAtOnceApplyTheChangeOnce() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice inv = invoice(acme, "500.00");
        cancel(inv.getId(), maker);
        PendingChange pc = pendingOf("INVOICE", inv.getId());
        User first = approverIn(north);
        User second = approverIn(north);

        List<Integer> statuses = together(() -> approve(pc.getId(), first), () -> approve(pc.getId(), second));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(reload(pc).getStatus()).isEqualTo(PendingStatus.APPROVED);
        assertThat(audit("INVOICE", inv.getId(), "INVOICE_CANCELLED")).hasSize(1);
        assertThat(audit("INVOICE", inv.getId(), "CHANGE_APPROVED")).hasSize(1);
        assertThat(noticesTo(maker, ApprovalNotifier.APPROVED)).hasSize(1);
    }

    /** The maker asking again while a checker approves: one of the two wins, and the story stays whole. */
    @Test
    void aRequestReplacedWhileItIsApprovedEndsOneWayOrTheOther() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice inv = invoice(acme, "500.00");
        cancel(inv.getId(), maker);
        PendingChange pc = pendingOf("INVOICE", inv.getId());
        User approver = approverIn(north);

        List<Integer> statuses = together(() -> approve(pc.getId(), approver), () -> cancel(inv.getId(), maker));

        List<PendingChange> waiting = pendingChangeRepository.findAll().stream().filter(PendingChange::isPending)
                .toList();
        assertThat(waiting).hasSizeLessThanOrEqualTo(1);
        InvoiceStatus status = invoiceRepository.findById(inv.getId()).orElseThrow().getStatus();
        if (reload(pc).getStatus() == PendingStatus.APPROVED) {
            // Approved first: the cancel is made, and the maker's second request finds it cancelled.
            assertThat(statuses).containsExactly(200, 400);
            assertThat(status).isEqualTo(InvoiceStatus.CANCELLED);
            assertThat(waiting).isEmpty();
        } else {
            // Replaced first: the approval finds a superseded request, and the new one waits.
            assertThat(reload(pc).getStatus()).isEqualTo(PendingStatus.SUPERSEDED);
            assertThat(statuses).containsExactly(409, 202);
            assertThat(status).isEqualTo(InvoiceStatus.UNPAID);
            assertThat(waiting).singleElement()
                    .satisfies(p -> assertThat(reload(pc).getSupersededById()).isEqualTo(p.getId()));
        }
    }

    /** SC-7: an approved change of terms arriving with a cancel never brings the promise back. */
    @Test
    void aPromiseChangeApprovedWhileThePromiseIsCancelledNeverUndoesTheCancel() throws Exception {
        User collector = collectorIn(north);
        PaymentPromise promise = promise(acme, "1000.00", collector);
        threshold(north, ApprovalOperation.PROMISE_UPDATE, "100.00");
        putPromise(promise.getId(), "400.00", promise.getPromisedDate(), null, collector);
        PendingChange pc = pendingOf("PROMISE", promise.getId());
        User approver = approverIn(north);

        List<Integer> statuses = together(() -> approve(pc.getId(), approver),
                () -> mockMvc.perform(post("/api/promises/" + promise.getId() + "/cancel").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Customer withdrew\"}")));

        assertThat(statuses).containsExactly(200, 200);
        PaymentPromise after = promiseRepository.findById(promise.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PromiseStatus.CANCELLED);
        PendingChange decided = reload(pc);
        assertThat(decided.getStatus()).isIn(PendingStatus.APPROVED, PendingStatus.FAILED);
        if (decided.getStatus() == PendingStatus.FAILED) {
            assertThat(decided.getFailureReason()).isEqualTo("A cancelled promise cannot be edited");
            assertThat(after.getAmount()).isEqualByComparingTo("1000.00");
        } else {
            assertThat(after.getAmount()).isEqualByComparingTo("400.00");
        }
    }

    /** Runs both at the same moment, each on its own thread; the HTTP statuses in the order given. */
    private List<Integer> together(Callable<ResultActions> a, Callable<ResultActions> b) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResultActions>> running = new ArrayList<>();
            for (Callable<ResultActions> call : List.of(a, b)) {
                running.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return call.call();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<ResultActions> f : running) {
                statuses.add(f.get(30, TimeUnit.SECONDS).andReturn().getResponse().getStatus());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }
}
