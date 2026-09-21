package com.geneinvoice.assignee;

import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.role.Role;
import com.geneinvoice.task.TaskDtos;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who internally owns a piece of work is staff identity, and a customer login never sees staff
 * identity (AC-A8). The assignee list names people, so for a customer it is empty — not a list of
 * unresolved seats, which would still say how the work is organised and who is missing from it.
 *
 * <p>Inside the company the line is drawn one notch further in. A role assignee reaches the holders
 * of the customer's POC seats and the POC on the record itself, and who those people are is POC
 * identity, which {@code POC_VIEW} gives and which the invoice list, the payment list and a
 * promise's assignees all withhold without it (AC-A6). A task and a dispute used to hand the same
 * names — and their email addresses — to anybody who could open one, which made either of them a
 * way round the check the other three make.
 */
class AssigneeStaffIdentityTest extends AssigneeTestBase {

    @Autowired DisputeService disputeService;
    @Autowired PrivilegeRepository privilegeRepository;

    // ---- promises ----------------------------------------------------------------

    @Test
    void aCustomerIsToldNothingAboutWhoIsAnswerableForItsPromise() throws Exception {
        PromiseDtos.PromiseDto p = promise(byName(cara), customerSeat(EmailRole.COLLECTION_POC));
        assertThat(p.assignees()).hasSize(2);

        mockMvc.perform(get("/api/promises/" + p.id()).with(as(acmeLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignees", hasSize(0)));
        mockMvc.perform(get("/api/promises").param("size", "50").with(as(acmeLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].assignees", hasSize(0)));

        // The same promise, read by staff, names both — so the empty list above is the masking and
        // not an empty record.
        mockMvc.perform(get("/api/promises/" + p.id()).with(as(admin)))
                .andExpect(jsonPath("$.assignees", hasSize(2)))
                .andExpect(jsonPath("$.assignees[0].userId").value(cara.getId()))
                .andExpect(jsonPath("$.assignees[1].people[0].userId").value(cara.getId()));
    }

    /**
     * Not even an unheld seat: "this account's Customer Success POC is nobody" is a fact about how
     * we are organised, so a customer is not told it either.
     */
    @Test
    void aCustomerIsNotEvenToldThatAPromisesSeatIsUnheld() throws Exception {
        PromiseDtos.PromiseDto p = promise(customerSeat(EmailRole.CUSTOMER_SUCCESS_POC));
        assertThat(only(p.assignees()).resolved()).isFalse();

        mockMvc.perform(get("/api/promises/" + p.id()).with(as(acmeLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignees", hasSize(0)));
    }

    // ---- staff without POC_VIEW (AC-A6) --------------------------------------------

    /**
     * The seats a task is assigned to, and whether anybody is sitting in them, are how the work is
     * organised and are not POC identity; the names and addresses in those seats are.
     */
    @Test
    void aStaffCallerWithoutPocViewSeesATasksSeatsButNobodyInThem() throws Exception {
        TaskDtos.TaskDto task = raise("INVOICE", acmeInvoice.getId(), "Chase the balance",
                customerSeat(EmailRole.COLLECTION_POC), recordSeat(EmailRole.SALES_POC));
        // Both seats are held, and by different people, so an empty list below is the masking.
        assertThat(reaches(task.assignees().get(0))).containsExactly(cara.getId());
        assertThat(reaches(task.assignees().get(1))).containsExactly(sam.getId());

        User blind = user("polly.nopoc", pocBlindRole().getName());

        mockMvc.perform(get("/api/tasks/" + task.id()).with(as(blind)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignees", hasSize(2)))
                .andExpect(jsonPath("$.assignees[0].role").value("COLLECTION_POC"))
                .andExpect(jsonPath("$.assignees[0].level").value("CUSTOMER"))
                .andExpect(jsonPath("$.assignees[0].resolved").value(true))
                .andExpect(jsonPath("$.assignees[0].people", hasSize(0)))
                .andExpect(jsonPath("$.assignees[1].role").value("SALES_POC"))
                .andExpect(jsonPath("$.assignees[1].resolved").value(true))
                .andExpect(jsonPath("$.assignees[1].people", hasSize(0)));

        // The list too, not only the one task: a page of work is the easier place to read names off.
        String listed = mockMvc.perform(get("/api/tasks").param("size", "50").with(as(blind)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listed).contains("Chase the balance")
                .doesNotContain("cara.collections", "sam.sales", "CARA.COLLECTIONS", "SAM.SALES");

        // The very same task read by somebody who does hold POC_VIEW still names them.
        mockMvc.perform(get("/api/tasks/" + task.id()).with(as(admin)))
                .andExpect(jsonPath("$.assignees[0].people[0].userId").value(cara.getId()))
                .andExpect(jsonPath("$.assignees[1].people[0].userId").value(sam.getId()));
    }

    /** The same for a dispute, which is assigned to the seat on its record the moment it exists (A6). */
    @Test
    void aStaffCallerWithoutPocViewSeesADisputesSeatButNobodyInIt() throws Exception {
        actAs(acmeLogin);
        Dispute d = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.INVOICE, acmeInvoice.getId(), "wrong amount", null));
        actAs(admin);
        disputeService.setAssignees(d.getId(),
                List.of(recordSeat(EmailRole.SALES_POC), customerSeat(EmailRole.COLLECTION_POC)));

        User blind = user("pearl.nopoc", pocBlindRole().getName());

        mockMvc.perform(get("/api/disputes/" + d.getId()).with(as(blind)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignees", hasSize(2)))
                .andExpect(jsonPath("$.assignees[0].role").value("SALES_POC"))
                .andExpect(jsonPath("$.assignees[0].resolved").value(true))
                .andExpect(jsonPath("$.assignees[0].people", hasSize(0)))
                .andExpect(jsonPath("$.assignees[1].people", hasSize(0)));

        String listed = mockMvc.perform(get("/api/disputes").param("size", "50").with(as(blind)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listed).contains("wrong amount")
                .doesNotContain("cara.collections", "sam.sales", "CARA.COLLECTIONS", "SAM.SALES");

        mockMvc.perform(get("/api/disputes/" + d.getId()).with(as(admin)))
                .andExpect(jsonPath("$.assignees[0].people[0].userId").value(sam.getId()))
                .andExpect(jsonPath("$.assignees[1].people[0].userId").value(cara.getId()));
    }

    /**
     * Reads tasks and disputes and everything they hang off, and holds no {@code POC_VIEW}: the one
     * privilege these two tests are about. {@code SCOPE_OVERRIDE} is there so that what they cannot
     * see is never merely what their book does not reach.
     */
    private Role pocBlindRole() {
        return roleRepository.findByName("WORK_NO_POC").orElseGet(() ->
                roleRepository.save(Role.builder().name("WORK_NO_POC")
                        .description("Reads work, sees no POC identity")
                        .privileges(new HashSet<>(Set.of(
                                privilegeRepository.findByName(Privileges.CUSTOMER_VIEW).orElseThrow(),
                                privilegeRepository.findByName(Privileges.INVOICE_VIEW).orElseThrow(),
                                privilegeRepository.findByName(Privileges.PAYMENT_VIEW).orElseThrow(),
                                privilegeRepository.findByName(Privileges.TASK_VIEW).orElseThrow(),
                                privilegeRepository.findByName(Privileges.DISPUTE_VIEW).orElseThrow(),
                                privilegeRepository.findByName(Privileges.SCOPE_OVERRIDE).orElseThrow())))
                        .build()));
    }

    // ---- disputes -----------------------------------------------------------------

    /**
     * A dispute is the one record a customer raises itself, so it is the likeliest place for staff
     * identity to leak back out: they opened it, they watch it, and it is assigned the moment it
     * exists (A6).
     */
    @Test
    void aCustomerIsToldNothingAboutWhoIsAnswerableForTheDisputeItRaised() throws Exception {
        actAs(acmeLogin);
        Dispute d = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.INVOICE, acmeInvoice.getId(), "wrong amount", null));
        actAs(admin);
        disputeService.setAssignees(d.getId(),
                List.of(byName(suki), customerSeat(EmailRole.COLLECTION_POC)));

        mockMvc.perform(get("/api/disputes/" + d.getId()).with(as(acmeLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignees", hasSize(0)));
        mockMvc.perform(get("/api/disputes").param("size", "50").with(as(acmeLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].assignees", hasSize(0)));

        mockMvc.perform(get("/api/disputes/" + d.getId()).with(as(admin)))
                .andExpect(jsonPath("$.assignees", hasSize(2)))
                .andExpect(jsonPath("$.assignees[0].userId").value(suki.getId()))
                .andExpect(jsonPath("$.assignees[1].people[0].userId").value(cara.getId()));
    }

    /** The seat a dispute opens on is staff identity too, even before anybody has touched it. */
    @Test
    void aCustomerIsToldNothingAboutTheSeatItsNewDisputeOpenedOn() throws Exception {
        actAs(acmeLogin);
        Dispute d = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.PAYMENT, acmePayment.getId(), "not mine", null));

        mockMvc.perform(get("/api/disputes/" + d.getId()).with(as(acmeLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignees", hasSize(0)));

        // And it really is assigned underneath: the masking is in the reading, not in the writing.
        assertThat(assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.DISPUTE, d.getId())).hasSize(1);
    }
}
