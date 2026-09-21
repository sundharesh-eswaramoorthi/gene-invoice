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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The assignable-POC picker's search box (CP-11), and what happens when the same seat is removed
 * twice — sequentially, and by two requests at once (CP-08).
 */
class PocPickerAndSeatTest extends IntegrationTestBase {

    @Autowired PocService pocService;
    @Autowired PlatformTransactionManager transactionManager;

    User admin;
    Customer acme;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        acme = customer("Acme Ltd");
        actAs(admin);
    }

    private List<String> assignableUsernames(String query) {
        return pocService.assignable(PocType.SUCCESS, query, 25).stream()
                .map(User::getUsername).toList();
    }

    @Test
    void anUnderscoreInTheSearchMatchesOnlyAnUnderscore() {
        User maya = user("maya", DataSeeder.ROLE_SUCCESS_POC);
        User withUnderscore = user("m_ya", DataSeeder.ROLE_SUCCESS_POC);

        assertThat(assignableUsernames("m_ya"))
                .as("only the name that really has an underscore in it")
                .containsExactly(withUnderscore.getUsername());
        assertThat(assignableUsernames("maya")).containsExactly(maya.getUsername());
    }

    @Test
    void aPercentSignMatchesOnlyAPercentSign() {
        user("maya", DataSeeder.ROLE_SUCCESS_POC);
        user("olive", DataSeeder.ROLE_SUCCESS_POC);

        assertThat(assignableUsernames("%"))
                .as("a lone percent sign is a character nobody's name holds, not a wildcard")
                .isEmpty();
    }

    @Test
    void aBackslashInTheSearchIsMatchedLiterally() {
        user("maya", DataSeeder.ROLE_SUCCESS_POC);

        assertThat(assignableUsernames("\\")).isEmpty();
    }

    @Test
    void anOrdinarySearchStillFindsWhatItAlwaysDid() {
        User maya = user("maya", DataSeeder.ROLE_SUCCESS_POC);
        user("olive", DataSeeder.ROLE_SUCCESS_POC);

        assertThat(assignableUsernames("may")).containsExactly(maya.getUsername());
    }

    @Test
    void removingASeatThatIsAlreadyGoneIsANotFound() {
        User success = user("sara.success", DataSeeder.ROLE_SUCCESS_POC);
        CustomerPoc seat = pocService.add(acme.getId(), PocType.SUCCESS, success.getId(), true);

        pocService.remove(acme.getId(), seat.getId());

        assertThatThrownBy(() -> pocService.remove(acme.getId(), seat.getId()))
                .isInstanceOf(com.geneinvoice.common.NotFoundException.class)
                .hasMessage("POC assignment not found");
    }

    @Test
    void concurrentRemovalsOfOneSeatLeaveOneWinnerAndNoUnexpectedError() throws Exception {
        User success = user("sara.success", DataSeeder.ROLE_SUCCESS_POC);
        User second = user("sid.success", DataSeeder.ROLE_SUCCESS_POC);
        pocService.add(acme.getId(), PocType.SUCCESS, second.getId(), false);
        CustomerPoc seat = pocService.add(acme.getId(), PocType.SUCCESS, success.getId(), true);

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<String> succeeded = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(() -> {
                actAs(admin);
                try {
                    transactions.executeWithoutResult(status ->
                            pocService.remove(acme.getId(), seat.getId()));
                    succeeded.add("removed");
                } catch (Throwable e) {
                    failures.add(e);
                }
            }, "remove-seat-" + i);
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join(30_000);

        assertThat(succeeded).as("exactly one request removed the seat").hasSize(1);
        assertThat(failures).hasSize(2);
        assertThat(failures).allSatisfy(e -> assertThat(rootOf(e))
                .as("the losers are told the seat is not there, not that something went wrong")
                .isInstanceOf(com.geneinvoice.common.NotFoundException.class));

        List<CustomerPoc> left = pocService.listFor(acme.getId());
        assertThat(left).hasSize(1);
        assertThat(left.get(0).isPrimary()).isTrue();
    }

    private Throwable rootOf(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            if (current instanceof com.geneinvoice.common.NotFoundException) return current;
            current = current.getCause();
        }
        return current;
    }
}
