package com.geneinvoice.assignee;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.task.TaskDtos;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "My tasks" (A2): a person is named on a task in three different ways, and none of them is stored
 * against the task. Each is proved on its own — one task per way, one person per way — so a list
 * that answered only by name, or only by seat, could not pass.
 */
class MyTasksAssignmentTest extends AssigneeTestBase {

    /** Named by name; holds no seat anywhere. */
    TaskDtos.TaskDto namedTask;
    /** A customer-level seat; Cara sits in Acme's Collection book. */
    TaskDtos.TaskDto bookSeatTask;
    /** An invoice's own Sales POC field; Sam is on the invoice and in no book. */
    TaskDtos.TaskDto invoicePocTask;
    /** A payment's own Collection POC field; Carl is on that payment and in no book. */
    TaskDtos.TaskDto paymentPocTask;

    Payment carlsPayment;

    @BeforeEach
    void raiseOneTaskPerWayOfBeingNamed() {
        carlsPayment = paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("15.00"), "Cash", null, null, carl.getId(), null));

        namedTask = raise("CUSTOMER", acme.getId(), "Named outright", byName(suki));
        bookSeatTask = raise("CUSTOMER", acme.getId(), "Whoever collects for Acme",
                customerSeat(EmailRole.COLLECTION_POC));
        invoicePocTask = raise("INVOICE", acmeInvoice.getId(), "Whoever sold this invoice",
                recordSeat(EmailRole.SALES_POC));
        paymentPocTask = raise("PAYMENT", carlsPayment.getId(), "Whoever collected this payment",
                recordSeat(EmailRole.COLLECTION_POC));
    }

    /** The ids of the tasks this person's own list answers with, over the endpoint the app uses. */
    private List<Long> mine(User who) throws Exception {
        JsonNode body = read(mockMvc.perform(get("/api/tasks")
                        .param("mine", "true")
                        .param("size", "50")
                        .with(as(who)))
                .andExpect(status().isOk()));
        List<Long> ids = new ArrayList<>();
        body.get("content").forEach(row -> ids.add(row.get("id").asLong()));
        return ids;
    }

    // ---- the three ways ---------------------------------------------------------

    @Test
    void someoneNamedOnATaskByNameFindsItInTheirOwnList() throws Exception {
        assertThat(mine(suki)).containsExactly(namedTask.id());
    }

    @Test
    void someoneSittingInTheCustomersBookFindsTheSeatsTaskInTheirOwnList() throws Exception {
        assertThat(mine(cara)).containsExactly(bookSeatTask.id());
    }

    @Test
    void someoneWhoIsTheRecordsOwnSalesPocFindsThatRecordsTaskInTheirOwnList() throws Exception {
        assertThat(mine(sam)).containsExactly(invoicePocTask.id());
    }

    @Test
    void someoneWhoIsTheRecordsOwnCollectionPocFindsThatRecordsTaskInTheirOwnList() throws Exception {
        assertThat(mine(carl)).containsExactly(paymentPocTask.id());
    }

    /**
     * The control the other four need: somebody named in none of the three ways sees none of the
     * work, even though every task is plainly visible to them in the ordinary list.
     */
    @Test
    void someoneNamedInNoneOfTheThreeWaysSeesNoneOfTheWork() throws Exception {
        assertThat(mine(nina)).isEmpty();

        JsonNode everything = read(mockMvc.perform(get("/api/tasks").param("size", "50")
                        .with(as(nina)))
                .andExpect(status().isOk()));
        assertThat(everything.get("totalElements").asInt()).isEqualTo(4);
    }

    // ---- and the seat stays live in the list too --------------------------------

    /**
     * The list resolves the seat the same way the record does (A2), so somebody seated this morning
     * finds the work waiting for them with nobody having edited the task.
     */
    @Test
    void someoneSeatedInTheBookAfterwardsFindsTheSeatsWorkWaitingForThem() throws Exception {
        assertThat(mine(carl)).containsExactly(paymentPocTask.id());

        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, carl.getId(), false);

        assertThat(mine(carl)).containsExactlyInAnyOrder(paymentPocTask.id(), bookSeatTask.id());
    }

    /** And somebody who leaves the book stops seeing its work, again without the task being edited. */
    @Test
    void someoneWhoLeavesTheBookStopsSeeingTheSeatsWork() throws Exception {
        actAs(admin);
        CustomerPoc carasSeat = customerPocRepository
                .findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc(acme.getId()).get(0);
        assertThat(mine(cara)).containsExactly(bookSeatTask.id());

        actAs(admin);
        pocService.remove(acme.getId(), carasSeat.getId());

        assertThat(mine(cara)).isEmpty();
    }

    /**
     * A customer-scoped account is nobody's assignee — assignees are always internal (A1) — so its
     * own list is empty rather than its whole account's work. The login here carries a role with
     * TASK_VIEW, which the seeded CUSTOMER role does not, because the rule being checked is the
     * scope and not the privilege in front of it.
     */
    @Test
    void aCustomerLoginAskingForItsOwnTasksGetsAnEmptyList() throws Exception {
        User portal = userRepository.save(User.builder()
                .username("acme.portal")
                .email("acme.portal@test.local")
                .fullName("ACME PORTAL")
                .password(passwordEncoder.encode("password"))
                .role(role("VIEWER"))
                .customerId(acme.getId())
                .active(true)
                .build());

        assertThat(mine(portal)).isEmpty();

        // Not because it can see nothing: without "mine" it sees its own customer's tasks.
        JsonNode theirs = read(mockMvc.perform(get("/api/tasks").param("size", "50")
                        .with(as(portal)))
                .andExpect(status().isOk()));
        assertThat(theirs.get("totalElements").asInt()).isEqualTo(4);
    }
}
