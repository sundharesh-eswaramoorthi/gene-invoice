package com.geneinvoice.assignee;

import com.geneinvoice.email.EmailRole;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.task.TaskDtos;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The point of a ROLE assignee (A2): it stores the seat, never the people in it. Who it reaches is
 * read at the moment it is asked for, so somebody who joins the book afterwards is assigned by the
 * very same row and somebody who leaves stops being assigned — with nobody touching the task.
 */
class AssigneeRoleIsLiveTest extends AssigneeTestBase {

    /** The seat as the row stores it, so a test can prove the row itself never changed. */
    private record Seat(EmailRole role, com.geneinvoice.email.RoleLevel level, Instant createdAt) {}

    private Seat storedSeat(Long taskId) {
        Assignee row = assigneeRepository
                .findByOwnerTypeAndOwnerIdOrderByIdAsc(AssigneeOwnerType.TASK, taskId).get(0);
        return new Seat(row.getRole(), row.getLevel(), row.getCreatedAt());
    }

    // ---- joining and leaving the book ------------------------------------------

    /**
     * THE central behaviour. The task is assigned to the customer's Collection seat while Cara is
     * the only person in it; Carl is seated afterwards and is reached by the same row. Nothing
     * about the task is edited in between — if the people had been snapshotted at pick time, Carl
     * would never appear.
     */
    @Test
    void someoneSeatedAfterTheTaskWasAssignedIsReachedByTheSameRowWithoutTouchingTheTask() {
        TaskDtos.TaskDto task = raise("CUSTOMER", acme.getId(), "Chase the account",
                customerSeat(EmailRole.COLLECTION_POC));
        assertThat(reaches(only(task.assignees()))).containsExactly(cara.getId());
        Seat before = storedSeat(task.id());
        Instant lastEdited = taskRepository.findById(task.id()).orElseThrow().getUpdatedAt();

        pocService.add(acme.getId(), PocType.COLLECTION, carl.getId(), false);

        // Primary first, then in seating order — the same order the email form offers them in (L2).
        assertThat(reaches(only(reread(task.id()).assignees())))
                .containsExactly(cara.getId(), carl.getId());
        // And the row is untouched: same seat, same level, same moment it was written.
        assertThat(storedSeat(task.id())).isEqualTo(before);
        assertThat(taskRepository.findById(task.id()).orElseThrow().getUpdatedAt()).isEqualTo(lastEdited);
    }

    /**
     * The other half: a holder who leaves the book stops being reached, again without the task
     * being edited. This is why the row cannot store people — a stale name would go on being
     * answerable for work after the person had handed the account over.
     */
    @Test
    void someoneRemovedFromTheBookStopsBeingReachedByTheSameRow() {
        CustomerPoc carlsSeat = pocService.add(acme.getId(), PocType.COLLECTION, carl.getId(), false);
        TaskDtos.TaskDto task = raise("CUSTOMER", acme.getId(), "Chase the account",
                customerSeat(EmailRole.COLLECTION_POC));
        assertThat(reaches(only(task.assignees()))).containsExactly(cara.getId(), carl.getId());

        pocService.remove(acme.getId(), carlsSeat.getId());

        assertThat(reaches(only(reread(task.id()).assignees()))).containsExactly(cara.getId());
    }

    /** Who is primary is who is named first, and moving it moves the order with nothing else. */
    @Test
    void makingTheSecondHolderPrimaryPutsThemFirstInWhatTheSeatReaches() {
        CustomerPoc carlsSeat = pocService.add(acme.getId(), PocType.COLLECTION, carl.getId(), false);
        TaskDtos.TaskDto task = raise("CUSTOMER", acme.getId(), "Chase the account",
                customerSeat(EmailRole.COLLECTION_POC));

        pocService.setPrimary(acme.getId(), carlsSeat.getId());

        assertThat(reaches(only(reread(task.id()).assignees())))
                .containsExactly(carl.getId(), cara.getId());
    }

    // ---- customer level versus record level ------------------------------------

    /**
     * The two levels answer different questions on the same record (L2, L3): the customer's book
     * reaches everyone in the seat, while the record's own field reaches the one person it names.
     * Both are on one payment task, so the difference cannot be an accident of fixtures.
     */
    @Test
    void theCustomerSeatReachesEveryHolderWhileTheRecordSeatReachesOnlyThePersonTheRecordNames() {
        pocService.add(acme.getId(), PocType.COLLECTION, carl.getId(), false);
        // The payment stores Cara; the book now holds Cara and Carl.
        TaskDtos.TaskDto task = raise("PAYMENT", acmePayment.getId(), "Check this payment",
                customerSeat(EmailRole.COLLECTION_POC), recordSeat(EmailRole.COLLECTION_POC));

        List<AssigneeDtos.AssigneeDto> rows = task.assignees();
        assertThat(labels(rows))
                .containsExactly("Collection POC (customer)", "Collection POC (this payment)");
        assertThat(reaches(rows.get(0))).containsExactly(cara.getId(), carl.getId());
        assertThat(reaches(rows.get(1))).containsExactly(cara.getId());
    }

    /** Moving the record's own POC moves the record-level seat, and leaves the book's seat alone. */
    @Test
    void reassigningTheRecordsOwnPocMovesOnlyTheRecordLevelSeat() {
        TaskDtos.TaskDto task = raise("INVOICE", acmeInvoice.getId(), "Chase this invoice",
                customerSeat(EmailRole.COLLECTION_POC), recordSeat(EmailRole.SALES_POC));
        assertThat(reaches(task.assignees().get(1))).containsExactly(sam.getId());

        // Admin holds every privilege, so admin is assignable as a Sales POC too.
        invoiceService.reassignSalesPoc(acmeInvoice.getId(), admin.getId());

        List<AssigneeDtos.AssigneeDto> rows = reread(task.id()).assignees();
        assertThat(reaches(rows.get(0))).containsExactly(cara.getId());
        assertThat(reaches(rows.get(1))).containsExactly(admin.getId());
    }

    // ---- nobody in the seat -----------------------------------------------------

    /**
     * A seat nobody holds is unassigned work, which somebody has to see and fix, so it reads as an
     * assignee that resolved to nobody rather than dropping out of the list.
     */
    @Test
    void aSeatNobodyHoldsReadsAsUnresolvedWithNoPeopleRatherThanVanishing() {
        // Acme has a Collection POC and no Customer Success POC at all.
        TaskDtos.TaskDto task = raise("CUSTOMER", acme.getId(), "Chase the account",
                customerSeat(EmailRole.CUSTOMER_SUCCESS_POC));

        AssigneeDtos.AssigneeDto unheld = only(task.assignees());
        assertThat(unheld.kind()).isEqualTo(AssigneeKind.ROLE);
        assertThat(unheld.role()).isEqualTo("CUSTOMER_SUCCESS_POC");
        assertThat(unheld.label()).isEqualTo("Customer Success POC (customer)");
        assertThat(unheld.resolved()).isFalse();
        assertThat(unheld.people()).isEmpty();
        // And the row is really there: it is unresolved, not unwritten.
        assertThat(assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.TASK, task.id())).hasSize(1);
    }

    /**
     * Inactive people hold nothing (L2). Work assigned to a seat whose only holder has been
     * deactivated goes unresolved rather than being silently reassigned to whoever is left.
     */
    @Test
    void anInactiveUserHoldsNothingSoTheSeatGoesUnresolved() {
        TaskDtos.TaskDto task = raise("CUSTOMER", acme.getId(), "Chase the account",
                customerSeat(EmailRole.COLLECTION_POC));
        assertThat(only(task.assignees()).resolved()).isTrue();

        deactivate(cara);

        AssigneeDtos.AssigneeDto seat = only(reread(task.id()).assignees());
        assertThat(seat.resolved()).isFalse();
        assertThat(seat.people()).isEmpty();
        // Her seat in the book is untouched; she simply holds nothing while she is inactive.
        assertThat(customerPocRepository.findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc(acme.getId()))
                .hasSize(1);
    }

    /** With two in the seat, deactivating one leaves the seat held by the other. */
    @Test
    void deactivatingOneHolderLeavesTheSeatReachingTheRest() {
        pocService.add(acme.getId(), PocType.COLLECTION, carl.getId(), false);
        TaskDtos.TaskDto task = raise("CUSTOMER", acme.getId(), "Chase the account",
                customerSeat(EmailRole.COLLECTION_POC));

        deactivate(cara);

        AssigneeDtos.AssigneeDto seat = only(reread(task.id()).assignees());
        assertThat(seat.resolved()).isTrue();
        assertThat(reaches(seat)).containsExactly(carl.getId());
    }

    /** A record-level seat whose person is inactive goes unresolved for the same reason (L3). */
    @Test
    void aPromiseWhoseOwnCollectionPocWasDeactivatedReadsItsRecordSeatAsUnresolved() {
        PromiseDtos.PromiseDto p = promise(recordSeat(EmailRole.COLLECTION_POC));
        assertThat(reaches(only(p.assignees()))).containsExactly(cara.getId());

        deactivate(cara);

        AssigneeDtos.AssigneeDto seat = only(promiseService.dto(p.id()).assignees());
        assertThat(seat.resolved()).isFalse();
        assertThat(seat.people()).isEmpty();
    }

    /**
     * The other half of the same rule, and the reason the two kinds exist at all: a person is
     * answerable until somebody changes the row, while a role is answerable only for as long as
     * they hold it (A2). So deactivating somebody empties the seat they sat in but does not quietly
     * take their name off work that named them — that is still assigned, to somebody who has gone,
     * which is a thing a person has to see and re-assign rather than something to hide.
     */
    @Test
    void someoneNamedByNameStaysNamedAfterTheyAreDeactivatedUnlikeASeatTheyHeld() {
        TaskDtos.TaskDto task = raise("CUSTOMER", acme.getId(), "Chase the account",
                byName(cara), customerSeat(EmailRole.COLLECTION_POC));

        deactivate(cara);

        List<AssigneeDtos.AssigneeDto> rows = reread(task.id()).assignees();
        assertThat(rows.get(0).kind()).isEqualTo(AssigneeKind.USER);
        assertThat(rows.get(0).resolved()).isTrue();
        assertThat(reaches(rows.get(0))).containsExactly(cara.getId());
        assertThat(rows.get(1).kind()).isEqualTo(AssigneeKind.ROLE);
        assertThat(rows.get(1).resolved()).isFalse();
    }

    private void deactivate(User u) {
        User fresh = userRepository.findById(u.getId()).orElseThrow();
        fresh.setActive(false);
        userRepository.saveAndFlush(fresh);
    }
}
