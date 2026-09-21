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
        CustomerPoc first = seat(maya);
        CustomerPoc second = seat(olive);
        CustomerPoc third = seat(nina);
        assertThat(first.isPrimary()).isTrue();

        race(() -> pocService.setPrimary(acme.getId(), second.getId()),
                () -> pocService.setPrimary(acme.getId(), third.getId()));

        assertThat(primaries())
                .as("one seat of each kind is primary, whichever of the two requests won")
                .hasSize(1);

        mockMvc.perform(post("/api/customers/" + acme.getId() + "/pocs/" + first.getId() + "/primary")
                        .with(as(admin)))
                .andExpect(status().isOk());
        assertThat(primaries()).extracting(CustomerPoc::getId).containsExactly(first.getId());
    }

    @Test
    void twoPeopleSeatingAPrimaryAtOnceLeaveExactlyOnePrimary() throws Exception {
        race(() -> pocService.add(acme.getId(), PocType.SUCCESS, maya.getId(), true),
                () -> pocService.add(acme.getId(), PocType.SUCCESS, olive.getId(), true));

        assertThat(primaries()).hasSize(1);
    }

    @Test
    void aCustomerLeftWithTwoPrimariesIsMendedRatherThanJammed() throws Exception {
        CustomerPoc first = seat(maya);
        CustomerPoc second = seat(olive);
        second.setPrimary(true);
        customerPocRepository.saveAndFlush(second);
        assertThat(primaries()).hasSize(2);

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
        mark(oldest, false);
        mark(middle, true);
        mark(youngest, true);

        mockMvc.perform(delete("/api/customers/" + acme.getId() + "/pocs/" + middle.getId())
                        .with(as(admin)))
                .andExpect(status().isOk());

        assertThat(primaries()).hasSize(1);
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

        assertThat(pocService.primaryFor(acme.getId(), PocType.SUCCESS))
                .get().extracting(User::getId).isEqualTo(maya.getId());
    }

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
