package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.CountingStatements;
import com.geneinvoice.MovableClock;
import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Splitting a change never slips it under a limit (§5.11.3, SC-9): within a threshold's window, the same maker's
 * unapproved changes of the same kind to the same customer count together, those applied and those still waiting.
 * Another maker's changes, and changes older than the window, do not; a window of 0 measures each change alone. The
 * window is read only when a limit exists and the change alone is within it.
 */
class CumulativeGateTest extends ApprovalTestBase {

    @Autowired ApprovalHousekeeping housekeeping;

    Region north;
    User collector;
    User otherCollector;
    Customer acme;

    @BeforeEach
    void setUp() {
        north = workRegion("NORTH");
        collector = collectorIn(north);
        otherCollector = collectorIn(north);
        acme = customer("Acme Ltd", north);
    }

    @Test
    void theSameMakersChangesToOneCustomerInTheWindowCountTogether() throws Exception {
        threshold(north, ApprovalOperation.PAYMENT_RECORD, "100000.00", 24);

        pay(acme, "60000.00", collector, collector).andExpect(status().isOk());
        JsonNode held = body(pay(acme, "50000.00", collector, collector).andExpect(status().isAccepted()));

        assertThat(held.at("/pendingChange/amount").decimalValue()).as("its own measure")
                .isEqualByComparingTo("50000.00");
        assertThat(held.at("/pendingChange/summary").asText()).isEqualTo("Record payment of ₹50,000.00 from Acme Ltd;"
                + " with your other changes to Acme Ltd in the last 24 hours this comes to ₹1,10,000.00");
        assertThat(paymentRepository.count()).isEqualTo(1);

        // Someone else's change is theirs alone.
        pay(acme, "50000.00", otherCollector, otherCollector).andExpect(status().isOk());

        // A day and an hour on, the 60000 is out of the window; the 50000 still waiting counts: exactly at the limit.
        MovableClock.SHARED.set(AppClock.now().plus(Duration.ofHours(25)));
        pay(acme, "50000.00", collector, collector).andExpect(status().isOk());
        assertThat(paymentRepository.count()).isEqualTo(3);
        assertThat(pendingCount()).isEqualTo(1);
    }

    @Test
    void anApprovedChangeNoLongerCountsAndAnotherCustomerNeverDid() throws Exception {
        threshold(north, ApprovalOperation.PAYMENT_RECORD, "100000.00", 24);
        Customer beta = customer("Beta Co", north);

        pay(acme, "60000.00", collector, collector).andExpect(status().isOk());
        pay(beta, "60000.00", collector, collector).andExpect(status().isOk());
        pay(acme, "50000.00", collector, collector).andExpect(status().isAccepted());
        approve(pendingChangeRepository.findAll().get(0).getId(), approverIn(north)).andExpect(status().isOk());

        // 60000 applied unapproved + 30000 = 90000; the approved 50000 has been seen by a checker.
        pay(acme, "30000.00", collector, collector).andExpect(status().isOk());
        assertThat(approvalUsageRepository.findAll()).extracting(u -> u.getAmount().toPlainString())
                .containsExactlyInAnyOrder("60000.00", "60000.00", "30000.00");
    }

    @Test
    void aWindowOfNoHoursMeasuresEachChangeAlone() throws Exception {
        threshold(north, ApprovalOperation.PAYMENT_RECORD, "100000.00", 0);

        pay(acme, "60000.00", collector, collector).andExpect(status().isOk());
        pay(acme, "60000.00", collector, collector).andExpect(status().isOk());

        assertThat(pendingCount()).isZero();
        assertThat(approvalUsageRepository.count()).as("nothing to remember").isZero();
    }

    @Test
    void aPromiseWalkedDownInSmallStepsIsHeldWhenTheStepsAddUp() throws Exception {
        PaymentPromise promise = promise(acme, "200000.00", collector);
        threshold(north, ApprovalOperation.PROMISE_UPDATE, "100000.00", 24);
        LocalDate due = Today.date().plusDays(10);

        putPromise(promise.getId(), "170000.00", due, null, collector).andExpect(status().isOk());
        putPromise(promise.getId(), "140000.00", due, null, collector).andExpect(status().isOk());
        putPromise(promise.getId(), "110000.00", due, null, collector).andExpect(status().isOk());
        JsonNode held = body(putPromise(promise.getId(), "80000.00", due, null, collector)
                .andExpect(status().isAccepted()));

        assertThat(held.at("/pendingChange/summary").asText()).endsWith("this comes to ₹1,20,000.00");
        assertThat(promiseRepository.findById(promise.getId()).orElseThrow().getAmount())
                .isEqualByComparingTo("110000.00");
    }

    @Test
    void theWindowIsReadOnlyWhenALimitExistsAndTheChangeAloneIsWithinIt() throws Exception {
        assertThat(CountingStatements.reads("approval_usage",
                () -> pay(acme, "60000.00", collector, collector).andExpect(status().isOk())))
                .as("no limit at all").isZero();

        threshold(north, ApprovalOperation.PAYMENT_RECORD, "1000.00", 24);
        assertThat(CountingStatements.reads("approval_usage",
                () -> pay(acme, "5000.00", collector, collector).andExpect(status().isAccepted())))
                .as("above the limit on its own").isZero();
        assertThat(CountingStatements.reads("approval_usage",
                () -> pay(acme, "500.00", collector, collector).andExpect(status().isAccepted())))
                .as("within it alone, so the window is read: 500 + the 5000 waiting").isPositive();
        assertThat(approvalUsageRepository.count()).as("nothing applied within a limit yet").isZero();
    }

    @Test
    void housekeepingForgetsWhatNoWindowCanReach() throws Exception {
        threshold(north, ApprovalOperation.PAYMENT_RECORD, "100000.00", 24);
        pay(acme, "1000.00", collector, collector).andExpect(status().isOk());
        MovableClock.SHARED.set(AppClock.now().plus(Duration.ofDays(29)));
        pay(acme, "2000.00", collector, collector).andExpect(status().isOk());
        MovableClock.SHARED.set(AppClock.now().plus(Duration.ofDays(2)));

        assertThat(housekeeping.purge()).isEqualTo(1);
        assertThat(approvalUsageRepository.findAll()).extracting(u -> u.getAmount().toPlainString())
                .containsExactly("2000.00");
    }
}
