package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.MovableClock;
import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A move is never a way round a limit (D7, SC-4): for {@code app.approval.move-cooldown-days} (30) after a customer
 * leaves a region, its changes are held at the stricter of that region's limit and the new one's; after that, only
 * the new region's counts. A move into a stricter region is stricter at once. The change still waits in the region
 * the record is in, for that region's approvers.
 */
class RegionMoveCooldownTest extends ApprovalTestBase {

    Region north;
    Region south;
    User cashier;
    Customer acme;

    @BeforeEach
    void setUp() {
        north = workRegion("NORTH");
        south = workRegion("SOUTH");
        // Manages customers and invoices in both regions; approves nothing.
        cashier = staffIn("carl", "CASHIER", north, AccessLevel.MANAGE);
        grant(cashier, south, AccessLevel.MANAGE, RecordScope.ALL);
        acme = customer("Acme Ltd", north);
    }

    @Test
    void aCustomerMovedToALaxerRegionStaysUnderTheOldLimitUntilTheCoolDownEnds() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice first = invoice(acme, "500.00");
        Invoice second = invoice(acme, "500.00");

        move(acme, south, cashier).andExpect(status().isOk());
        JsonNode held = body(cancel(first.getId(), cashier).andExpect(status().isAccepted()));

        assertThat(held.at("/pendingChange/threshold").decimalValue()).as("NORTH's limit, still")
                .isEqualByComparingTo("100.00");
        PendingChange pc = pendingOf("INVOICE", first.getId());
        assertThat(pc.getRegionId()).as("it waits where the invoice is now").isEqualTo(south.getId());
        assertThat(pc.getThresholdAmount()).isEqualByComparingTo("100.00");
        assertThat(invoiceRepository.findById(first.getId()).orElseThrow().getStatus().name()).isEqualTo("UNPAID");
        // SOUTH's approvers decide it; NORTH's alone cannot.
        approve(pc.getId(), approverIn(north)).andExpect(status().isNotFound());
        approve(pc.getId(), approverIn(south)).andExpect(status().isOk());

        MovableClock.SHARED.set(AppClock.now().plus(Duration.ofDays(31)));
        cancel(second.getId(), cashier).andExpect(status().isOk());
        assertThat(invoiceRepository.findById(second.getId()).orElseThrow().getStatus().name())
                .isEqualTo("CANCELLED");
    }

    @Test
    void theCoolDownKeepsTheStricterOfTheTwoLimits() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        threshold(south, ApprovalOperation.INVOICE_CANCEL, "10000.00");
        Invoice inv = invoice(acme, "500.00");

        move(acme, south, cashier).andExpect(status().isOk());

        JsonNode held = body(cancel(inv.getId(), cashier).andExpect(status().isAccepted()));
        assertThat(held.at("/pendingChange/threshold").decimalValue()).isEqualByComparingTo("100.00");
    }

    @Test
    void twoQuickMovesKeepTheFirstRegionsLimitToo() throws Exception {
        Region east = workRegion("EAST");
        grant(cashier, east, AccessLevel.MANAGE, RecordScope.ALL);
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice inv = invoice(acme, "500.00");

        move(acme, south, cashier).andExpect(status().isOk());
        move(acme, east, cashier).andExpect(status().isOk());

        cancel(inv.getId(), cashier).andExpect(status().isAccepted());
        assertThat(pendingOf("INVOICE", inv.getId()).getRegionId()).isEqualTo(east.getId());
    }

    @Test
    void aMoveIntoAStricterRegionAppliesItsLimitAtOnce() throws Exception {
        threshold(south, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Invoice inv = invoice(acme, "500.00");
        Invoice other = invoice(acme, "500.00");
        cancel(other.getId(), cashier).andExpect(status().isOk());

        move(acme, south, cashier).andExpect(status().isOk());

        JsonNode held = body(cancel(inv.getId(), cashier).andExpect(status().isAccepted()));
        assertThat(held.at("/pendingChange/threshold").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(held.at("/pendingChange/regionName").asText()).isEqualTo("South");
    }

    @Test
    void aCustomerNeverMovedIsGatedByItsOwnRegionAlone() throws Exception {
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        Customer beta = customer("Beta Co", south);
        Invoice inv = invoice(beta, "500.00");
        // Acme's move says nothing about Beta.
        move(acme, south, cashier).andExpect(status().isOk());

        cancel(inv.getId(), cashier).andExpect(status().isOk());
    }

    private ResultActions move(Customer customer, Region to, User as) throws Exception {
        return mockMvc.perform(post("/api/customers/" + customer.getId() + "/move-region").with(as(as))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("regionId", to.getId()))));
    }
}
