package com.geneinvoice.region;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.approval.ApprovalService;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.approval.PendingChangeStatus;
import com.geneinvoice.asof.TestHistoryFloor;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.FixedHistoryClock;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A DELETED ACCOUNT'S PAST BELONGS TO THE BRANCH IT WAS IN, NOT TO NOBODY (B1, B3).
 *
 * <p>B3-04 reads "an as-of row is visible iff the region it belonged to THEN is one the reader may
 * read now, AND the region it belongs to NOW is also one they may read now — for a deleted record,
 * its last region". The two clauses were built over {@code customers} and
 * {@code customer_region_history}, and a hard delete removes BOTH rows, so for a deleted account
 * every clause was false on every date: its whole past dropped out of every as-of read for anybody
 * who is not a wildcard holder. {@code AsOfRegionAndApprovalTest} pins the contract clause ("a
 * deleted customer is still listed as of before it was deleted") entirely as {@code admin}, who
 * holds the null-region wildcard and for whom no region clause is built at all — so the one
 * assertion of it never exercised the code that fails.
 *
 * <p>BOTH DIRECTIONS ARE ASSERTED. The reader whose branch the account was in gets its January
 * back; the reader whose branch it never was in still does not, because the fallback reads the
 * mirror's surviving region and not "anybody may see a deleted account".
 */
@Import({FixedHistoryClock.Config.class, TestHistoryFloor.Config.class})
class DeletedAccountAsOfTest extends IntegrationTestBase {

    @Autowired ApprovalService approvalService;
    @Autowired FixedHistoryClock clock;
    @Autowired TestHistoryFloor floor;

    User admin;
    /** A second approver: a maker may never approve their own change (B2). */
    User checker;
    /** CASHIER: SCOPE_OVERRIDE, so no POC book narrows it and the region axis is the only filter. */
    User cashier;
    /** A reader whose only branch is one this account was never in. */
    User westerner;
    Region west;
    Customer doomed;
    Customer alive;

    LocalDate today;
    LocalDate born;
    LocalDate asked;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        checker = user("charlie.checker", "ADMIN");
        cashier = userRepository.findByUsername("cashier").orElseThrow();
        west = region("WEST");
        westerner = user("wendy.west", "CASHIER");
        revokeRegionGrants(westerner);
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(westerner.getId()).regionId(west.getId()).right(RegionRight.VIEW).build());

        today = LocalDate.now(ZoneOffset.UTC);
        born = today.minusDays(60);
        asked = today.minusDays(30);
        doomed = account("Doomed Ltd");
        alive = account("Alive Ltd");
        actAs(admin);
    }

    @AfterEach
    void putEverythingBack() {
        clock.release();
        floor.reset();
    }

    /**
     * The account was in the cashier's own branch on the day she is asking about, and it is gone
     * today. Before this, the tombstone was served to the wildcard holder and hidden from her:
     * two people asking the identical historical question got different answers, and the
     * difference was not "your branches".
     */
    @Test
    void aRegionScopedReaderStillSeesADeletedAccountAsOfBeforeItWasDeleted() throws Exception {
        deleteThroughTheGate(doomed);

        assertThat(customerRepository.findById(doomed.getId())).isEmpty();
        assertThat(customerRegionHistoryRepository.findOpen(doomed.getId())).isEmpty();

        // The wildcard holder's answer, which was always right, and the scoped reader's, which was
        // not: now they are the same answer.
        assertThat(namesAsOf(admin)).contains("Doomed Ltd", "Alive Ltd");
        assertThat(namesAsOf(cashier)).contains("Doomed Ltd", "Alive Ltd");
        mockMvc.perform(get("/api/customers/" + doomed.getId())
                        .param("asOf", asked.toString()).with(as(cashier)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Doomed Ltd"));

        // And today it is gone from both of them, which is what a delete means.
        assertThat(namesLive(cashier)).doesNotContain("Doomed Ltd");
        mockMvc.perform(get("/api/customers/" + doomed.getId()).with(as(cashier)))
                .andExpect(status().isNotFound());
    }

    /**
     * THE LEAK GUARD STILL HOLDS, which is the half that makes the fix a fix rather than a hole:
     * the account was never in WEST, so its January is still none of WEST's business. It fails
     * closed, and with a 404 rather than a 403, exactly as an account that still exists does
     * (AUTH-08).
     */
    @Test
    void aReaderFromAnotherBranchStillDoesNotSeeIt() throws Exception {
        deleteThroughTheGate(doomed);

        assertThat(namesAsOf(westerner)).doesNotContain("Doomed Ltd");
        mockMvc.perform(get("/api/customers/" + doomed.getId())
                        .param("asOf", asked.toString()).with(as(westerner)))
                .andExpect(status().isNotFound());
    }

    /**
     * And nothing about an account that still exists changed: the fallback is guarded on the
     * account being GONE, so a moved account is still answered by the placement ledger and a
     * reader whose branch it has left still cannot read the past it accumulated elsewhere.
     */
    @Test
    void anAccountThatStillExistsIsUnaffected() throws Exception {
        assertThat(namesAsOf(cashier)).contains("Alive Ltd", "Doomed Ltd");
        assertThat(namesAsOf(westerner)).isEmpty();
    }

    // ---------------------------------------------------------------- fixtures

    private static Instant noon(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant();
    }

    /** An account that already existed then, with a real mirror row and a real placement row. */
    private Customer account(String name) {
        clock.freezeAt(noon(born));
        Customer c = customerRepository.saveAndFlush(
                Customer.builder().name(name).region(defaultRegion()).build());
        clock.release();
        customerRegionHistoryRepository.saveAndFlush(CustomerRegionHistory.builder()
                .customerId(c.getId()).regionId(defaultRegion().getId()).validFrom(born).build());
        return c;
    }

    /** CUSTOMER_DELETE is alwaysChecked, so the maker is held at 202 and the checker applies it. */
    private void deleteThroughTheGate(Customer c) throws Exception {
        mockMvc.perform(delete("/api/customers/" + c.getId()).with(as(admin)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("PENDING_APPROVAL"));
        PendingChange waiting = pendingChangeRepository.findAll().stream()
                .filter(pc -> pc.getStatus() == PendingChangeStatus.PENDING)
                .findFirst().orElseThrow();
        actAs(checker);
        approvalService.approve(waiting.getId(), null);
        actAs(admin);
    }

    private List<String> namesAsOf(User who) throws Exception {
        return names(get("/api/customers").param("asOf", asked.toString()).with(as(who)));
    }

    private List<String> namesLive(User who) throws Exception {
        return names(get("/api/customers").with(as(who)));
    }

    private List<String> names(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        String body = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode page = objectMapper.readTree(body);
        List<String> names = new ArrayList<>();
        page.get("content").forEach(row -> names.add(row.get("name").asText()));
        return names;
    }
}
