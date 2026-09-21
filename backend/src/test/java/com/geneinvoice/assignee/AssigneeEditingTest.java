package com.geneinvoice.assignee;

import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.task.TaskDtos;
import com.geneinvoice.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Changing a record without meaning to change who is answerable for it. The assignee list is
 * replaced wholesale when it is sent and left alone when it is not, because a patch that only
 * moves a status or a POC must never silently unassign everybody (A7).
 */
class AssigneeEditingTest extends AssigneeTestBase {

    @Autowired DisputeService disputeService;

    // ---- tasks ------------------------------------------------------------------

    /** A null list means "not a statement about the assignees", which is what a status move is. */
    @Test
    void aPatchThatOnlyMovesATasksStatusLeavesItsAssigneesAlone() {
        TaskDtos.TaskDto task = raise("CUSTOMER", acme.getId(), "Chase the account",
                byName(cara), customerSeat(EmailRole.COLLECTION_POC));

        taskService.update(task.id(), new TaskDtos.UpdateTaskRequest(
                null, null, TaskStatus.IN_PROGRESS.name(), null, null));

        TaskDtos.TaskDto after = reread(task.id());
        assertThat(after.status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(after.assignees()).hasSize(2);
    }

    /** An empty list, on the other hand, is a statement: this is nobody's now. */
    @Test
    void anEmptyListOnATaskClearsItsAssignees() {
        TaskDtos.TaskDto task = raise("CUSTOMER", acme.getId(), "Chase the account", byName(cara));

        taskService.update(task.id(), new TaskDtos.UpdateTaskRequest(
                null, null, null, null, List.of()));

        assertThat(reread(task.id()).assignees()).isEmpty();
        assertThat(assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.TASK, task.id())).isEmpty();
    }

    /** Sending a list replaces it outright rather than adding to it. */
    @Test
    void sendingAListReplacesWhatWasThereRatherThanAddingToIt() {
        TaskDtos.TaskDto task = raise("CUSTOMER", acme.getId(), "Chase the account", byName(cara));

        taskService.update(task.id(), new TaskDtos.UpdateTaskRequest(
                null, null, null, null, List.of(customerSeat(EmailRole.COLLECTION_POC))));

        AssigneeDtos.AssigneeDto now = only(reread(task.id()).assignees());
        assertThat(now.kind()).isEqualTo(AssigneeKind.ROLE);
        // The seat reaches Cara, but she is no longer named: the row that named her has gone.
        assertThat(reaches(now)).containsExactly(cara.getId());
    }

    /** A deleted task leaves no assignee rows pointing at an id that no longer exists (A1). */
    @Test
    void deletingATaskTakesItsAssigneeRowsWithIt() {
        TaskDtos.TaskDto task = raise("CUSTOMER", acme.getId(), "Chase the account",
                byName(cara), customerSeat(EmailRole.COLLECTION_POC));

        taskService.delete(task.id());

        assertThat(taskRepository.findById(task.id())).isEmpty();
        assertThat(assigneeRepository.findAll()).isEmpty();
    }

    // ---- promises ----------------------------------------------------------------

    /**
     * Moving a promise's Collection POC is not a statement about who is answerable for it, so the
     * inline row action and the bulk reassign leave the list where it is. A bulk reassign that
     * emptied every promise's assignees would be a silent loss of the work's owners (A7).
     */
    @Test
    void reassigningAPromisesCollectionPocLeavesItsAssigneesAlone() {
        PromiseDtos.PromiseDto p = promise(byName(suki), customerSeat(EmailRole.COLLECTION_POC));

        PromiseDtos.PromiseDto after = promiseService.reassignCollectionPoc(p.id(), carl.getId());

        assertThat(after.collectionPoc().id()).isEqualTo(carl.getId());
        assertThat(after.assignees()).hasSize(2);
        assertThat(after.assignees().get(0).userId()).isEqualTo(suki.getId());
        // And the seat still means the book, which Carl joining the promise did not put him in.
        assertThat(reaches(after.assignees().get(1))).containsExactly(cara.getId());
    }

    /** A promise can be left assigned to nobody, and that is a state it reads back in. */
    @Test
    void anEmptyListOnAPromiseUpdateClearsItsAssignees() {
        PromiseDtos.PromiseDto p = promise(byName(suki));

        promiseService.update(p.id(), new PromiseDtos.UpdatePromiseRequest(
                p.amount(), p.promisedDate(), null, p.notes(), null, List.of()));

        assertThat(promiseService.dto(p.id()).assignees()).isEmpty();
    }

    // ---- disputes -----------------------------------------------------------------

    /** The same for a dispute: an empty list leaves it assigned to nobody at all. */
    @Test
    void anEmptyListOnADisputeLeavesItAssignedToNobody() {
        actAs(acmeLogin);
        Dispute d = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.INVOICE, acmeInvoice.getId(), "wrong amount", null));
        actAs(admin);

        disputeService.setAssignees(d.getId(), List.of());

        assertThat(disputeService.toDto(disputeService.get(d.getId())).assignees()).isEmpty();
        assertThat(assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.DISPUTE, d.getId())).isEmpty();
    }

    /** A dispute can be handed on while it is still open, which is the only time it matters. */
    @Test
    void aDisputeCanBeHandedFromOneSeatToAPersonAndBackAgain() {
        actAs(acmeLogin);
        Dispute d = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.INVOICE, acmeInvoice.getId(), "wrong amount", null));
        actAs(admin);

        disputeService.setAssignees(d.getId(), List.of(byName(suki)));
        assertThat(only(disputeService.toDto(disputeService.get(d.getId())).assignees()).userId())
                .isEqualTo(suki.getId());

        disputeService.setAssignees(d.getId(), List.of(recordSeat(EmailRole.SALES_POC)));
        AssigneeDtos.AssigneeDto back = only(disputeService.toDto(disputeService.get(d.getId())).assignees());
        assertThat(back.kind()).isEqualTo(AssigneeKind.ROLE);
        assertThat(reaches(back)).containsExactly(sam.getId());
    }
}
