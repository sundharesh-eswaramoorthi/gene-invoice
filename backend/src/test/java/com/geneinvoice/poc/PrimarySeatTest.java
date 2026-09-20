package com.geneinvoice.poc;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A customer has at most one primary seat of each kind, and it stays that way (CP-02).
 *
 * <p>Making a seat primary is a clear-then-set across a whole group of rows. Run twice at once it
 * used to leave two seats primary — and then every later "make primary" on that customer answered
 * a server error for good, because the lookup behind it could only ever hold one row. Removing a
 * seat did not mend it either: the promotion went on top of the second primary instead of clearing
 * it. There was no way back from inside the app.
 */
class PrimarySeatTest extends IntegrationTestBase {

    @Autowired PocService pocService;
    @Autowired PlatformTransactionManager transactionManager;

    private static final long HOLD_MS = 300;

    User admin;
    User maya;
    User olive;
    User nina;
    Customer acme;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        maya = user("maya.success", DataSeeder.ROLE_SUCCESS_POC);
        olive = user("olive.success", DataSeeder.ROLE_SUCCESS_POC);
        nina = user("nina.success", DataSeeder.ROLE_SUCCESS_POC);
        acme = customer("Acme Ltd");
        actAs(admin);
    }

    private CustomerPoc seat(User holder) {
        return pocService.add(acme.getId(), PocType.SUCCESS, holder.getId(), false);
    }

    private List<CustomerPoc> seats() {
        return customerPocRepository.findByCustomerIdAndPocType(acme.getId(), PocType.SUCCESS);
    }

    private List<CustomerPoc> primaries() {
        return seats().stream().filter(CustomerPoc::isPrimary).toList();
    }

    @Test
    void twoPeopleMakingDifferentSeatsPrimaryAtOnceLeaveExactlyOnePrimary() throws Exception {
        CustomerPoc first = seat(maya);      // the first seat of its kind is primary
        CustomerPoc second = seat(olive);
        CustomerPoc third = seat(nina);
        assertThat(first.isPrimary()).isTrue();

        race(() -> pocService.setPrimary(acme.getId(), second.getId()),
                () -> pocService.setPrimary(acme.getId(), third.getId()));

        assertThat(primaries())
                .as("one seat of each kind is primary, whichever of the two requests won")
                .hasSize(1);

        // And the customer is still workable: "make primary" answers rather than failing for good.
        mockMvc.perform(post("/api/customers/" + acme.getId() + "/pocs/" + first.getId() + "/primary")
                        .with(as(admin)))
                .andExpect(status().isOk());
        assertThat(primaries()).extracting(CustomerPoc::getId).containsExactly(first.getId());
    }

    @Test
    void twoPeopleSeatingAPrimaryAtOnceLeaveExactlyOnePrimary() throws Exception {
        // Both are the "first" seat of the kind as far as either can see, and both ask to be primary.
        race(() -> pocService.add(acme.getId(), PocType.SUCCESS, maya.getId(), true),
                () -> pocService.add(acme.getId(), PocType.SUCCESS, olive.getId(), true));

        assertThat(primaries()).hasSize(1);
    }

    @Test
    void aCustomerLeftWithTwoPrimariesIsMendedRatherThanJammed() throws Exception {
        CustomerPoc first = seat(maya);
        CustomerPoc second = seat(olive);
        // The state the race used to leave behind, written straight into the table.
        second.setPrimary(true);
        customerPocRepository.saveAndFlush(second);
        assertThat(primaries()).hasSize(2);

        // Every later "make primary" answered 500 here, for good, on this customer and kind.
        mockMvc.perform(post("/api/customers/" + acme.getId() + "/pocs/" + first.getId() + "/primary")
                        .with(as(admin)))
                .andExpect(status().isOk());
        assertThat(primaries()).extracting(CustomerPoc::getId).containsExactly(first.getId());
    }

    @Test
    void removingASeatFromACustomerWithTwoPrimariesLeavesOne() throws Exception {
        CustomerPoc oldest = seat(maya);
        CustomerPoc middle = seat(olive);
        CustomerPoc youngest = seat(nina);
        // Two primaries, neither of them the oldest seat: removing one promotes the oldest, and
        // used to do it on top of the other primary rather than clearing it first.
        mark(oldest, false);
        mark(middle, true);
        mark(youngest, true);

        mockMvc.perform(delete("/api/customers/" + acme.getId() + "/pocs/" + middle.getId())
                        .with(as(admin)))
                .andExpect(status().isOk());

        assertThat(primaries()).hasSize(1);
        // And the customer is workable again rather than stuck at a server error.
        mockMvc.perform(post("/api/customers/" + acme.getId() + "/pocs/" + youngest.getId() + "/primary")
                        .with(as(admin)))
                .andExpect(status().isOk());
        assertThat(primaries()).extracting(CustomerPoc::getId).containsExactly(youngest.getId());
    }

    private void mark(CustomerPoc seat, boolean primary) {
        CustomerPoc fresh = customerPocRepository.findById(seat.getId()).orElseThrow();
        fresh.setPrimary(primary);
        customerPocRepository.saveAndFlush(fresh);
    }

    @Test
    void theRosterAnswersEvenWhileTwoSeatsAreMarkedPrimary() {
        seat(maya);
        CustomerPoc second = seat(olive);
        second.setPrimary(true);
        customerPocRepository.saveAndFlush(second);

        // A read must answer, not throw: the oldest of them is the primary anyone would promote.
        assertThat(pocService.primaryFor(acme.getId(), PocType.SUCCESS))
                .get().extracting(User::getId).isEqualTo(maya.getId());
    }

    /**
     * Runs {@code first} in a transaction held open for {@link #HOLD_MS} and {@code second} in its
     * own, started inside that window — two people acting on one customer at the same moment. A
     * failure of the second is an acceptable answer to the race; the state it leaves is not.
     */
    private void race(Runnable first, Runnable second) throws Exception {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch firstIsInFlight = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();

        Thread one = new Thread(() -> {
            actAs(admin);
            try {
                transactions.executeWithoutResult(status -> {
                    first.run();
                    firstIsInFlight.countDown();
                    sleep(HOLD_MS);
                });
            } catch (Throwable t) {
                firstIsInFlight.countDown();
                firstFailure.set(t);
            }
        }, "seat-one");

        Thread two = new Thread(() -> {
            actAs(admin);
            try {
                firstIsInFlight.await(5, TimeUnit.SECONDS);
                second.run();
            } catch (Throwable ignored) {
                // Being refused is fine; what the table is left holding is what matters.
            }
        }, "seat-two");

        one.start();
        two.start();
        one.join(30_000);
        two.join(30_000);
        assertThat(firstFailure.get()).isNull();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
