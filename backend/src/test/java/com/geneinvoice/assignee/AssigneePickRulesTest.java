package com.geneinvoice.assignee;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.task.TaskDtos;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the picker may and may not produce (A4). An assignee is checked against the same table of
 * roles the email form is checked against, so a role the record could never have is refused when it
 * is picked rather than quietly reaching nobody for the rest of the record's life.
 */
class AssigneePickRulesTest extends AssigneeTestBase {

    @Autowired DisputeService disputeService;

    private Throwable pickingOnCustomer(EmailToken... tokens) {
        return org.assertj.core.api.Assertions.catchThrowable(
                () -> raise("CUSTOMER", acme.getId(), "Chase the account", tokens));
    }

    // ---- a role the record does not offer --------------------------------------

    /**
     * A Sales POC is assigned per invoice, never per customer: {@code PocService.add} refuses to
     * seat one in the book at all, so a customer-level Sales seat could only ever have been an
     * assignee nobody holds. The two refusals are asserted together because one is the reason for
     * the other (CP-01).
     */
    @Test
    void aCustomerLevelSalesSeatIsRefusedBecauseNobodyCanEverSitInOne() {
        assertThatThrownBy(() -> pocService.add(acme.getId(), PocType.SALES, sam.getId(), true))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Sales POC is assigned per invoice, not per customer");

        assertThat(pickingOnCustomer(customerSeat(EmailRole.SALES_POC)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Sales POC (customer) is not a role on customers");
    }

    /** A customer has no POC field of its own, so its record level offers nothing (L3). */
    @Test
    void aRecordLevelSeatIsRefusedOnACustomerWhichHasNoPocFieldOfItsOwn() {
        assertThat(pickingOnCustomer(recordSeat(EmailRole.COLLECTION_POC)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Collection POC (this customer) is not a role on customers");
    }

    /** An invoice's own seat is its Sales POC; a Collection POC there is not a thing (L3). */
    @Test
    void aRecordLevelCollectionSeatIsRefusedOnAnInvoice() {
        assertThatThrownBy(() -> raise("INVOICE", acmeInvoice.getId(), "Chase it",
                recordSeat(EmailRole.COLLECTION_POC)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Collection POC (this invoice) is not a role on invoices");
    }

    /**
     * A role token with no level is read at the kind's default level (L7), so one picker can send
     * the same token to the email form or to the assignee list: on a promise the Collection POC
     * sits in the customer's book, so an unlevelled one means the book.
     */
    @Test
    void aRoleTokenWithNoLevelIsReadAtTheKindsDefaultLevel() {
        PromiseDtos.PromiseDto p = promise(unlevelled(EmailRole.COLLECTION_POC));

        AssigneeDtos.AssigneeDto seat = only(p.assignees());
        assertThat(seat.level()).isEqualTo("CUSTOMER");
        assertThat(seat.label()).isEqualTo("Collection POC (customer)");
    }

    /**
     * And it is still refused by its own name when the kind has it nowhere: a promise has no Sales
     * POC at either level, so the default level is named in the refusal rather than guessed at.
     */
    @Test
    void anUnlevelledRoleTheKindHasNowhereIsRefusedByTheLevelItDefaultedTo() {
        assertThatThrownBy(() -> promise(unlevelled(EmailRole.SALES_POC)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Sales POC (this promise) is not a role on promises");
    }

    /**
     * A dispute offers both record-level seats because it can be about an invoice or a payment,
     * and one of them is therefore always unheld (L4). Picking the wrong one is allowed — it is a
     * role the kind offers — and reads as a seat nobody holds, not as a refusal.
     */
    @Test
    void aDisputeOffersBothRecordSeatsAndTheOneItsTargetDoesNotHoldReadsAsUnheld() {
        actAs(acmeLogin);
        Dispute d = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.INVOICE, acmeInvoice.getId(), "wrong amount", null));
        actAs(admin);

        disputeService.setAssignees(d.getId(), List.of(recordSeat(EmailRole.COLLECTION_POC)));

        AssigneeDtos.AssigneeDto seat = only(disputeService.toDto(disputeService.get(d.getId())).assignees());
        assertThat(seat.label()).isEqualTo("Collection POC (this dispute)");
        assertThat(seat.resolved()).isFalse();
        assertThat(seat.people()).isEmpty();
    }

    // ---- who may be named -------------------------------------------------------

    /** The work is ours, so a customer login is never answerable for it — the email form's rule. */
    @Test
    void aCustomerLoginMayNotBeAssigned() {
        assertThat(pickingOnCustomer(byName(acmeLogin)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("acme.login is not an active internal user");
    }

    @Test
    void aUserWhoDoesNotExistIsRefusedByName() {
        long ghost = userRepository.findAll().stream().mapToLong(User::getId).max().orElse(0L) + 5000L;

        assertThat(pickingOnCustomer(EmailToken.user(ghost)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("User #" + ghost + " does not exist");
    }

    /** Somebody who has left cannot be given new work, the same rule the POC fields apply. */
    @Test
    void anInactiveUserMayNotBeAssignedByName() {
        User fresh = userRepository.findById(suki.getId()).orElseThrow();
        fresh.setActive(false);
        userRepository.saveAndFlush(fresh);

        assertThat(pickingOnCustomer(byName(suki)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("suki.success is not an active internal user");
    }

    @Test
    void aUserTokenWithNoUserIdIsRefused() {
        assertThat(pickingOnCustomer(new EmailToken("USER", null, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("A USER assignee needs a userId");
    }

    @Test
    void aTokenThatIsNeitherAPersonNorASeatIsRefused() {
        // CUSTOMER is a real email recipient token — an email can go to the customer itself — but
        // nobody outside the company is ever answerable for our work (A1).
        assertThat(pickingOnCustomer(EmailToken.customer()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("An assignee type must be USER or ROLE");
    }

    @Test
    void aRoleOrLevelThatIsNotAWordTheSystemKnowsIsRefused() {
        assertThat(pickingOnCustomer(new EmailToken("ROLE", null, "BILLING_POC", "CUSTOMER")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("role must be one of");
        assertThat(pickingOnCustomer(new EmailToken("ROLE", null, "COLLECTION_POC", "TEAM")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("level must be CUSTOMER or RECORD");
    }

    /** Assigning is a staff act: a customer never sees staff, so it cannot name one (AC-A8). */
    @Test
    void aCustomerLoginMayNotSetADisputesAssignees() {
        actAs(acmeLogin);
        Dispute d = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.INVOICE, acmeInvoice.getId(), "wrong amount", null));

        assertThatThrownBy(() -> disputeService.setAssignees(d.getId(), List.of(byName(cara))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Only staff may assign a dispute");
    }

    // ---- repeats and the ceiling -------------------------------------------------

    /**
     * What {@code AssigneeService.parse} actually dedupes: the pick, not the person. The same
     * person named twice is one row, because it is literally the same pick.
     */
    @Test
    void theSamePersonNamedTwiceInOneListIsWrittenOnce() {
        TaskDtos.TaskDto task = raise("CUSTOMER", acme.getId(), "Chase the account",
                byName(cara), byName(suki), byName(cara));

        assertThat(task.assignees()).hasSize(2);
        assertThat(task.assignees().stream().map(AssigneeDtos.AssigneeDto::userId))
                .containsExactly(cara.getId(), suki.getId());
    }

    /**
     * And what it does not dedupe: naming Cara and naming the seat she happens to sit in are two
     * different picks, so both are kept even though they reach the same person today. They mean
     * different things tomorrow — she is answerable by name whatever happens to the seat, and the
     * seat is answerable whoever is in it (A2).
     */
    @Test
    void theSamePersonPickedByNameAndBySeatIsTwoRowsBecauseTheyAreTwoDifferentPicks() {
        TaskDtos.TaskDto task = raise("CUSTOMER", acme.getId(), "Chase the account",
                byName(cara), customerSeat(EmailRole.COLLECTION_POC));

        assertThat(task.assignees()).hasSize(2);
        assertThat(task.assignees().get(0).kind()).isEqualTo(AssigneeKind.USER);
        assertThat(task.assignees().get(1).kind()).isEqualTo(AssigneeKind.ROLE);
        // Both reach Cara right now, which is exactly why the rows have to be told apart by pick.
        assertThat(reaches(task.assignees().get(0))).containsExactly(cara.getId());
        assertThat(reaches(task.assignees().get(1))).containsExactly(cara.getId());
    }

    /** The same role at both levels is likewise two picks, even where one person holds both. */
    @Test
    void theSameRoleAtBothLevelsIsTwoRowsEvenWhenOnePersonHoldsBoth() {
        TaskDtos.TaskDto task = raise("PAYMENT", acmePayment.getId(), "Check this payment",
                customerSeat(EmailRole.COLLECTION_POC), recordSeat(EmailRole.COLLECTION_POC));

        assertThat(labels(task.assignees()))
                .containsExactly("Collection POC (customer)", "Collection POC (this payment)");
        assertThat(reaches(task.assignees().get(0))).containsExactly(cara.getId());
        assertThat(reaches(task.assignees().get(1))).containsExactly(cara.getId());
    }

    /** A seat repeated in one list is one row, the same rule as a person repeated. */
    @Test
    void theSameSeatPickedTwiceInOneListIsWrittenOnce() {
        TaskDtos.TaskDto task = raise("CUSTOMER", acme.getId(), "Chase the account",
                customerSeat(EmailRole.COLLECTION_POC), customerSeat(EmailRole.COLLECTION_POC));

        assertThat(task.assignees()).hasSize(1);
    }

    /**
     * The ceiling is counted on what was picked, before repeats are dropped — the picker never
     * offers that many, so a list this long is a mistake however it was built.
     */
    @Test
    void moreThanTwentyPicksAreRefusedEvenWhenTheyAreAllTheSamePerson() {
        List<EmailToken> tooMany = Collections.nCopies(AssigneeService.MAX_ASSIGNEES + 1, byName(cara));

        assertThatThrownBy(() -> taskService.create(new TaskDtos.CreateTaskRequest(
                "CUSTOMER", acme.getId(), "Chase the account", null, null, null, tooMany)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("A record takes at most " + AssigneeService.MAX_ASSIGNEES
                        + " assignees");
        assertThat(taskRepository.findAll()).isEmpty();
    }

    /** Exactly the ceiling is allowed, and the repeats still collapse to the one pick they are. */
    @Test
    void exactlyTwentyPicksAreAllowedAndStillCollapseToThePicksTheyAre() {
        List<EmailToken> atTheLimit = Collections.nCopies(AssigneeService.MAX_ASSIGNEES, byName(cara));

        TaskDtos.TaskDto task = taskService.toDto(taskService.create(new TaskDtos.CreateTaskRequest(
                "CUSTOMER", acme.getId(), "Chase the account", null, null, null, atTheLimit)));

        assertThat(task.assignees()).hasSize(1);
    }

    /** The ceiling holds on every kind of record that has assignees, not just the one it was written for. */
    @Test
    void theCeilingHoldsOnPromisesToo() {
        List<EmailToken> tooMany = Collections.nCopies(AssigneeService.MAX_ASSIGNEES + 1, byName(cara));

        assertThatThrownBy(() -> promiseService.create(new PromiseDtos.CreatePromiseRequest(
                acme.getId(), new BigDecimal("500.00"), LocalDate.now().plusDays(7),
                cara.getId(), "n", null, tooMany)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("A record takes at most " + AssigneeService.MAX_ASSIGNEES);
        assertThat(promiseRepository.findAll()).isEmpty();
    }

    /**
     * A list the picker could not have produced changes nothing: the tokens are read before the old
     * rows are deleted, so a refused edit leaves the record assigned exactly as it was.
     */
    @Test
    void aRefusedEditLeavesTheAssigneesThatWereAlreadyThere() {
        TaskDtos.TaskDto task = raise("CUSTOMER", acme.getId(), "Chase the account", byName(cara));

        assertThatThrownBy(() -> taskService.update(task.id(), new TaskDtos.UpdateTaskRequest(
                null, null, null, null, List.of(byName(suki), customerSeat(EmailRole.SALES_POC)))))
                .isInstanceOf(BadRequestException.class);

        assertThat(only(reread(task.id()).assignees()).userId()).isEqualTo(cara.getId());
    }

    /** Nothing to do with assignees, but it is the same service: a task still needs a title. */
    @Test
    void aTaskWithNoTitleIsRefusedBeforeAnyAssigneeIsWritten() {
        assertThatThrownBy(() -> taskService.create(new TaskDtos.CreateTaskRequest(
                "CUSTOMER", acme.getId(), "  ", null, null, null, List.of(byName(cara)))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("A task needs a title");
        assertThat(assigneeRepository.findAll()).isEmpty();
    }
}
