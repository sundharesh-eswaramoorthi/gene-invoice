package com.geneinvoice.assignee;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.task.TaskDtos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One picker, three kinds of record (A1). A person and a seat are picked with the very same tokens
 * the email To field produces, and come back the same way on a task, a promise and a dispute — so
 * the shape is proved once per owner kind over the HTTP surface the picker actually talks to.
 */
class AssigneeRoundTripTest extends AssigneeTestBase {

    @Autowired DisputeService disputeService;

    // ---- tasks ------------------------------------------------------------------

    @Test
    void aTaskTakesAPersonAndASeatAndGivesThemBackAsPicked() throws Exception {
        JsonNode created = read(mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new TaskDtos.CreateTaskRequest("CUSTOMER", acme.getId(),
                                "Chase the account", null, null, null,
                                List.of(byName(suki), customerSeat(EmailRole.COLLECTION_POC)))))
                        .with(as(admin)))
                .andExpect(status().isCreated()));
        long taskId = created.get("id").asLong();

        mockMvc.perform(get("/api/tasks/" + taskId).with(as(admin)))
                .andExpect(status().isOk())
                // The person, named: she is herself, so she is her own only holder.
                .andExpect(jsonPath("$.assignees[0].kind").value("USER"))
                .andExpect(jsonPath("$.assignees[0].userId").value(suki.getId()))
                .andExpect(jsonPath("$.assignees[0].resolved").value(true))
                .andExpect(jsonPath("$.assignees[0].people[0].userId").value(suki.getId()))
                .andExpect(jsonPath("$.assignees[0].people[0].address").value("suki.success@test.local"))
                // The seat, stored as a seat: the row keeps the role and the level, never a person.
                .andExpect(jsonPath("$.assignees[1].kind").value("ROLE"))
                .andExpect(jsonPath("$.assignees[1].role").value("COLLECTION_POC"))
                .andExpect(jsonPath("$.assignees[1].level").value("CUSTOMER"))
                .andExpect(jsonPath("$.assignees[1].label").value("Collection POC (customer)"))
                .andExpect(jsonPath("$.assignees[1].resolved").value(true))
                .andExpect(jsonPath("$.assignees[1].people[0].userId").value(cara.getId()));

        // A USER row carries a user and no seat; a ROLE row carries a seat and no user (A1).
        List<Assignee> stored = assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.TASK, taskId);
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).getUserId()).isEqualTo(suki.getId());
        assertThat(stored.get(0).getRole()).isNull();
        assertThat(stored.get(1).getUserId()).isNull();
        assertThat(stored.get(1).getRole()).isEqualTo(EmailRole.COLLECTION_POC);
        // Denormalised so "my tasks" can match a seat without joining back to the record.
        assertThat(stored).allMatch(a -> acme.getId().equals(a.getCustomerId()));
    }

    // ---- promises ---------------------------------------------------------------

    @Test
    void aPromiseTakesAPersonAndASeatAndGivesThemBackAsPicked() throws Exception {
        JsonNode created = read(mockMvc.perform(post("/api/promises")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PromiseDtos.CreatePromiseRequest(
                                acme.getId(), new java.math.BigDecimal("500.00"),
                                java.time.LocalDate.now().plusDays(7), cara.getId(), "n", null,
                                List.of(byName(suki), recordSeat(EmailRole.COLLECTION_POC)))))
                        .with(as(admin)))
                .andExpect(status().isOk()));

        assertThat(created.at("/assignees/0/kind").asText()).isEqualTo("USER");
        assertThat(created.at("/assignees/0/userId").asLong()).isEqualTo(suki.getId());
        // Record level on a promise is the promise's own Collection POC field (L3).
        assertThat(created.at("/assignees/1/kind").asText()).isEqualTo("ROLE");
        assertThat(created.at("/assignees/1/level").asText()).isEqualTo("RECORD");
        assertThat(created.at("/assignees/1/label").asText()).isEqualTo("Collection POC (this promise)");
        assertThat(created.at("/assignees/1/people/0/userId").asLong()).isEqualTo(cara.getId());

        long promiseId = created.get("id").asLong();
        assertThat(assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.PROMISE, promiseId)).hasSize(2);
    }

    /**
     * A promise nobody was named on answers to its Collection POC, seeded as that person by name
     * (A6) — the seed goes through the same parse a picked assignee does, so it is a pick the
     * picker could have made.
     */
    @Test
    void aPromiseNobodyWasNamedOnIsAssignedToItsCollectionPocByName() {
        PromiseDtos.PromiseDto p = promise();

        AssigneeDtos.AssigneeDto seeded = only(p.assignees());
        assertThat(seeded.kind()).isEqualTo(AssigneeKind.USER);
        assertThat(seeded.userId()).isEqualTo(cara.getId());
        assertThat(seeded.resolved()).isTrue();
    }

    // ---- disputes ---------------------------------------------------------------

    /**
     * A dispute is opened from the customer's side, where staff cannot be seen at all, so it starts
     * on the seat the record it is about holds (A6) and staff move it from there.
     */
    @Test
    void aDisputeStartsOnItsTargetsOwnSeatAndTakesAPersonAndASeatFromStaff() throws Exception {
        actAs(acmeLogin);
        Dispute d = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.INVOICE, acmeInvoice.getId(), "wrong amount", null));
        actAs(admin);

        // Not "Sam": the Sales POC of the invoice it disputes, read now (A2).
        AssigneeDtos.AssigneeDto seeded = only(disputeService.toDto(disputeService.get(d.getId())).assignees());
        assertThat(seeded.kind()).isEqualTo(AssigneeKind.ROLE);
        assertThat(seeded.role()).isEqualTo("SALES_POC");
        assertThat(seeded.level()).isEqualTo("RECORD");
        assertThat(seeded.label()).isEqualTo("Sales POC (this dispute)");
        assertThat(reaches(seeded)).containsExactly(sam.getId());

        mockMvc.perform(patch("/api/disputes/" + d.getId() + "/assignees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DisputeDtos.SetAssigneesRequest(
                                List.of(byName(suki), customerSeat(EmailRole.COLLECTION_POC)))))
                        .with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignees[0].kind").value("USER"))
                .andExpect(jsonPath("$.assignees[0].userId").value(suki.getId()))
                .andExpect(jsonPath("$.assignees[1].kind").value("ROLE"))
                .andExpect(jsonPath("$.assignees[1].label").value("Collection POC (customer)"))
                .andExpect(jsonPath("$.assignees[1].people[0].userId").value(cara.getId()));

        // Setting the list replaces it: the seat the dispute opened on is gone, not kept beside.
        assertThat(assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.DISPUTE, d.getId())).hasSize(2);
    }

    /**
     * A dispute about a payment opens on the payment's Collection POC instead: a dispute has no POC
     * of its own, so its record level is whichever of the two its target holds (L4).
     */
    @Test
    void aDisputeAboutAPaymentStartsOnThatPaymentsCollectionPoc() {
        actAs(acmeLogin);
        Dispute d = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.PAYMENT, acmePayment.getId(), "not mine", null));
        actAs(admin);

        AssigneeDtos.AssigneeDto seeded = only(disputeService.toDto(disputeService.get(d.getId())).assignees());
        assertThat(seeded.role()).isEqualTo("COLLECTION_POC");
        assertThat(seeded.level()).isEqualTo("RECORD");
        assertThat(reaches(seeded)).containsExactly(cara.getId());
    }
}
