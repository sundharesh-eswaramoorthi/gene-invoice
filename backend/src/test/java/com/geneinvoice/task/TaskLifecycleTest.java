package com.geneinvoice.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.assignee.Assignee;
import com.geneinvoice.assignee.AssigneeKind;
import com.geneinvoice.assignee.AssigneeOwnerType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature T: raising a piece of work on a record, what it remembers about that record, and how it
 * moves — the snapshot and the customer copy on the way in (T1, T3), the validation, the PATCH
 * rules {@link TaskDtos.UpdateTaskRequest} sets out, the completion stamp (T4) and the derived
 * overdue flag (T9).
 */
class TaskLifecycleTest extends TaskTestBase {

    // ---- T1 / T3: what a task remembers about its record --------------------------

    /**
     * A task on a customer is the simplest case: the record is the customer, so the denormalised
     * {@code customerId} is the record's own id and the label is what the customer was called when
     * the work was raised.
     */
    @Test
    void aTaskRaisedOnACustomerSnapshotsItsNameAndPointsAtItself() throws Exception {
        JsonNode task = raise(admin, newTask("CUSTOMER", acme.getId(),
                "title", "Call about the overdue balance"));

        assertThat(task.get("entityType").asText()).isEqualTo("CUSTOMER");
        assertThat(task.get("entityId").asLong()).isEqualTo(acme.getId());
        assertThat(task.get("entityLabel").asText()).isEqualTo("Customer Acme Ltd");
        assertThat(task.get("entityLink").asText()).isEqualTo("/customers/" + acme.getId());
        assertThat(task.get("customerId").asLong()).isEqualTo(acme.getId());
        assertThat(task.get("title").asText()).isEqualTo("Call about the overdue balance");
        assertThat(task.get("status").asText()).isEqualTo("OPEN");
        assertThat(task.get("statusLabel").asText()).isEqualTo("Open");
        assertThat(task.get("overdue").asBoolean()).isFalse();
        assertThat(task.get("createdByUserId").asLong()).isEqualTo(admin.getId());
        assertThat(task.get("completedAt").isNull()).isTrue();
        assertThat(task.get("assignees")).isEmpty();
    }

    /**
     * On an invoice the two differ: the task points at the invoice and is labelled with its number,
     * but the customer copied onto the row is the invoice's customer — that copy is what the list
     * scopes on and what the customer-delete cascade sweeps, so it has to come from the record
     * rather than from anything the caller sent.
     */
    @Test
    void aTaskRaisedOnAnInvoiceLabelsTheInvoiceAndCarriesTheInvoicesCustomer() throws Exception {
        JsonNode task = raise(admin, newTask("INVOICE", acmeInvoice.getId(),
                "title", "Confirm the PO number"));

        assertThat(task.get("entityType").asText()).isEqualTo("INVOICE");
        assertThat(task.get("entityId").asLong()).isEqualTo(acmeInvoice.getId());
        assertThat(task.get("entityLabel").asText())
                .isEqualTo("Invoice " + acmeInvoice.getInvoiceNumber());
        assertThat(task.get("entityLink").asText()).isEqualTo("/invoices/" + acmeInvoice.getId());
        assertThat(task.get("customerId").asLong()).isEqualTo(acme.getId());

        Task row = stored(idOf(task));
        assertThat(row.getEntityType()).isEqualTo(TaskEntityType.INVOICE);
        assertThat(row.getCustomerId()).isEqualTo(acme.getId());
    }

    @Test
    void aTaskRaisedOnAPaymentLabelsThePaymentAndCarriesThePaymentsCustomer() throws Exception {
        JsonNode task = raise(admin, newTask("PAYMENT", acmePayment.getId(),
                "title", "Check the remittance advice"));

        assertThat(task.get("entityType").asText()).isEqualTo("PAYMENT");
        assertThat(task.get("entityId").asLong()).isEqualTo(acmePayment.getId());
        assertThat(task.get("entityLabel").asText()).isEqualTo("Payment #" + acmePayment.getId());
        assertThat(task.get("entityLink").asText()).isEqualTo("/payments/" + acmePayment.getId());
        assertThat(task.get("customerId").asLong()).isEqualTo(acme.getId());
    }

    /**
     * The customer copy is read off the record, not guessed from the caller's own work: a task on
     * another customer's invoice belongs to that customer, which is what makes the list scope and
     * the delete cascade land on the right rows.
     */
    @Test
    void theCustomerCopiedOntoATaskIsTheRecordsOwnCustomer() throws Exception {
        Customer globex = customer("Globex Corp");
        Invoice theirs = invoice(globex, sales);

        JsonNode task = raise(admin, newTask("INVOICE", theirs.getId()));

        assertThat(task.get("customerId").asLong()).isEqualTo(globex.getId());
        assertThat(stored(idOf(task)).getCustomerId()).isEqualTo(globex.getId());
    }

    /**
     * The label is a snapshot, not a join: renaming the customer afterwards leaves the task saying
     * what the record was called when the work was raised (T3).
     */
    @Test
    void theRecordLabelStaysAsItWasWhenTheTaskWasRaised() throws Exception {
        JsonNode task = raise(admin, newTask("CUSTOMER", acme.getId()));

        Customer renamed = customerRepository.findById(acme.getId()).orElseThrow();
        renamed.setName("Acme Holdings Ltd");
        customerRepository.saveAndFlush(renamed);

        assertThat(getOk("/api/tasks/" + idOf(task), admin).get("entityLabel").asText())
                .isEqualTo("Customer Acme Ltd");
    }

    // ---- validation ----------------------------------------------------------------

    @Test
    void aTaskNeedsATitle() throws Exception {
        postTask(admin, newTask("CUSTOMER", acme.getId(), "title", null))
                .andExpect(status().isBadRequest());
        postTask(admin, newTask("CUSTOMER", acme.getId(), "title", "   "))
                .andExpect(status().isBadRequest());
    }

    /**
     * The service refuses an empty title in its own right rather than leaning on the controller's
     * bean validation: a rule's stored config reaches {@code createInBackground} without ever
     * passing through it, so the only check that always runs is this one (T7).
     */
    @Test
    void theServiceRefusesATitleOfNothingButSpacesEvenWithoutBeanValidation() {
        actAs(admin);
        assertThatThrownBy(() -> taskService.create(new TaskDtos.CreateTaskRequest(
                "CUSTOMER", acme.getId(), "   ", null, null, null, null)))
                .hasMessageContaining("A task needs a title");
    }

    @Test
    void aTitleIsStoredTrimmed() throws Exception {
        JsonNode task = raise(admin, newTask("CUSTOMER", acme.getId(), "title", "  Call Acme  "));

        assertThat(task.get("title").asText()).isEqualTo("Call Acme");
    }

    /** The column is 200 characters wide, so 200 is fine and 201 is a 400 rather than a failed insert. */
    @Test
    void aTitleMayBeExactlyTheColumnWidthButNotOneMore() throws Exception {
        String atTheLimit = "t".repeat(Task.TITLE_MAX);
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", atTheLimit));

        postTask(admin, newTask("CUSTOMER", acme.getId(), "title", atTheLimit + "x"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void notesMayBeExactlyTheColumnWidthButNotOneMore() throws Exception {
        String atTheLimit = "n".repeat(Task.NOTES_MAX);
        JsonNode ok = raise(admin, newTask("CUSTOMER", acme.getId(), "notes", atTheLimit));
        assertThat(ok.get("notes").asText()).hasSize(Task.NOTES_MAX);

        postTask(admin, newTask("CUSTOMER", acme.getId(), "notes", atTheLimit + "x"))
                .andExpect(status().isBadRequest());
    }

    /** Blank notes are no notes: an empty box on the form must not become an empty string on the row. */
    @Test
    void blankNotesAreStoredAsNoNotesAtAll() throws Exception {
        JsonNode task = raise(admin, newTask("CUSTOMER", acme.getId(), "notes", "   "));

        assertThat(task.get("notes").isNull()).isTrue();
        assertThat(stored(idOf(task)).getNotes()).isNull();
    }

    @Test
    void aStatusThatIsNotOneOfTheFourIsRefusedByName() throws Exception {
        postTask(admin, newTask("CUSTOMER", acme.getId(), "status", "NEARLY_DONE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("status must be one of")));
    }

    @Test
    void aKindOfRecordATaskCannotHangOffIsRefusedByName() throws Exception {
        postTask(admin, newTask("PROMISE", 1L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("entityType must be one of")));
    }

    @Test
    void aTaskNeedsToSayWhichRecordItIsOn() throws Exception {
        postTask(admin, newTask("CUSTOMER", null)).andExpect(status().isBadRequest());
        postTask(admin, newTask(null, acme.getId())).andExpect(status().isBadRequest());
    }

    @Test
    void aDueDateThatIsNotADateIsRefused() throws Exception {
        postTask(admin, newTask("CUSTOMER", acme.getId(), "dueDate", "the end of the month"))
                .andExpect(status().isBadRequest());
    }

    /** A due date is optional: plenty of work is "when you get to it" (T1). */
    @Test
    void aTaskMayBeRaisedWithNoDueDateAtAll() throws Exception {
        JsonNode task = raise(admin, newTask("CUSTOMER", acme.getId()));

        assertThat(task.get("dueDate").isNull()).isTrue();
        assertThat(task.get("overdue").asBoolean()).isFalse();
    }

    // ---- PATCH: which nulls clear and which leave alone (TaskDtos.UpdateTaskRequest) --

    private long taskWithEverything() throws Exception {
        JsonNode task = raise(admin, newTask("INVOICE", acmeInvoice.getId(),
                "title", "Chase the PO",
                "dueDate", TOMORROW.toString(),
                "status", "IN_PROGRESS",
                "notes", "Left a voicemail",
                "assignees", List.of(toUser(collections))));
        return idOf(task);
    }

    /**
     * A null title leaves the title alone. A task must always have one, so "no title in the body"
     * can only mean "not changing it" — there is nothing to clear it to.
     */
    @Test
    void aNullTitleInAPatchLeavesTheTitleAlone() throws Exception {
        long id = taskWithEverything();

        JsonNode after = edit(admin, id, "{\"title\": null}");

        assertThat(after.get("title").asText()).isEqualTo("Chase the PO");
    }

    /** The same for a status: a task is always somewhere, so a null means "leave it where it is". */
    @Test
    void aNullStatusInAPatchLeavesTheStatusAlone() throws Exception {
        long id = taskWithEverything();

        JsonNode after = edit(admin, id, "{\"status\": null}");

        assertThat(after.get("status").asText()).isEqualTo("IN_PROGRESS");
    }

    /**
     * A null due date clears it. Taking a date off a task is an everyday edit and JSON cannot tell
     * "absent" from "null", so the form sends the whole thing and null means gone.
     */
    @Test
    void aNullDueDateInAPatchClearsTheDueDate() throws Exception {
        long id = taskWithEverything();

        JsonNode after = edit(admin, id, "{\"dueDate\": null}");

        assertThat(after.get("dueDate").isNull()).isTrue();
        assertThat(stored(id).getDueDate()).isNull();
    }

    @Test
    void aNullNotesInAPatchClearsTheNotes() throws Exception {
        long id = taskWithEverything();

        JsonNode after = edit(admin, id, "{\"notes\": null}");

        assertThat(after.get("notes").isNull()).isTrue();
        assertThat(stored(id).getNotes()).isNull();
    }

    /**
     * A field left out of the body reads as null, which is the same instruction: a patch that only
     * moves the status and sends nothing else clears the due date and the notes with it. That is
     * the documented bargain — the form always sends the whole thing — and it is pinned here so it
     * cannot drift into "absent means leave alone" for some fields and not others.
     */
    @Test
    void aPatchThatOmitsTheDueDateAndNotesClearsThemJustAsANullWould() throws Exception {
        long id = taskWithEverything();

        JsonNode after = edit(admin, id, "{\"status\": \"OPEN\"}");

        assertThat(after.get("status").asText()).isEqualTo("OPEN");
        assertThat(after.get("dueDate").isNull()).isTrue();
        assertThat(after.get("notes").isNull()).isTrue();
        // ... while the title and the assignees, whose nulls mean "leave alone", are untouched.
        assertThat(after.get("title").asText()).isEqualTo("Chase the PO");
        assertThat(after.get("assignees")).hasSize(1);
    }

    /**
     * A null assignee list leaves the list as it is. A patch that only moves the status must never
     * silently unassign everybody (A1).
     */
    @Test
    void aNullAssigneeListInAPatchLeavesTheAssigneesAlone() throws Exception {
        long id = taskWithEverything();

        JsonNode after = edit(admin, id, "{\"status\": \"DONE\", \"assignees\": null}");

        assertThat(after.get("assignees")).hasSize(1);
        assertThat(after.at("/assignees/0/userId").asLong()).isEqualTo(collections.getId());
        assertThat(assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.TASK, id)).hasSize(1);
    }

    /** An empty list, on the other hand, is somebody saying "nobody" — and it clears them. */
    @Test
    void anEmptyAssigneeListInAPatchClearsTheAssignees() throws Exception {
        long id = taskWithEverything();

        JsonNode after = edit(admin, id, "{\"assignees\": []}");

        assertThat(after.get("assignees")).isEmpty();
        assertThat(assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.TASK, id)).isEmpty();
    }

    /** And a list of somebody else replaces who was on it, rather than adding to them. */
    @Test
    void anAssigneeListInAPatchReplacesWhoeverWasOnTheTask() throws Exception {
        long id = taskWithEverything();

        JsonNode after = edit(admin, id, json(java.util.Map.of("assignees",
                List.of(toUser(sales), toRole("SALES_POC", "RECORD")))));

        assertThat(after.get("assignees")).hasSize(2);
        assertThat(after.at("/assignees/0/userId").asLong()).isEqualTo(sales.getId());
        assertThat(after.at("/assignees/1/role").asText()).isEqualTo("SALES_POC");
        assertThat(after.at("/assignees/1/level").asText()).isEqualTo("RECORD");

        List<Assignee> rows = assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.TASK, id);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getKind()).isEqualTo(AssigneeKind.USER);
        // The customer is copied onto the assignee rows too, so "my tasks" can read the POC book
        // without joining back through the task (A1).
        assertThat(rows).allSatisfy(row -> assertThat(row.getCustomerId()).isEqualTo(acme.getId()));
    }

    /** A patch cannot move a task to another record: that is a delete and a re-raise (T8). */
    @Test
    void aPatchCannotMoveATaskToAnotherRecord() throws Exception {
        long id = taskWithEverything();
        Customer globex = customer("Globex Corp");

        edit(admin, id, json(java.util.Map.of("entityType", "CUSTOMER", "entityId", globex.getId())));

        Task row = stored(id);
        assertThat(row.getEntityType()).isEqualTo(TaskEntityType.INVOICE);
        assertThat(row.getEntityId()).isEqualTo(acmeInvoice.getId());
        assertThat(row.getCustomerId()).isEqualTo(acme.getId());
    }

    @Test
    void aPatchedTitleIsHeldToTheSameLimitAsANewOne() throws Exception {
        long id = taskWithEverything();

        patchTask(admin, id, json(java.util.Map.of("title", "t".repeat(Task.TITLE_MAX + 1))))
                .andExpect(status().isBadRequest());
        assertThat(stored(id).getTitle()).isEqualTo("Chase the PO");
    }

    @Test
    void patchedNotesAreHeldToTheSameLimitAsNewOnes() throws Exception {
        long id = taskWithEverything();

        patchTask(admin, id, json(java.util.Map.of("notes", "n".repeat(Task.NOTES_MAX + 1))))
                .andExpect(status().isBadRequest());
        assertThat(stored(id).getNotes()).isEqualTo("Left a voicemail");
    }

    // ---- T4: the completion stamp ----------------------------------------------------

    @Test
    void finishingATaskRecordsWhoFinishedItAndWhen() throws Exception {
        long id = idOf(raise(admin, newTask("CUSTOMER", acme.getId())));

        JsonNode done = edit(collections, id, "{\"status\": \"DONE\"}");

        assertThat(done.get("completedAt").isNull()).isFalse();
        Task row = stored(id);
        assertThat(row.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(row.getCompletedAt()).isNotNull();
        // Who closed it, not who raised it.
        assertThat(row.getCompletedByUserId()).isEqualTo(collections.getId());
        assertThat(row.getCreatedByUserId()).isEqualTo(admin.getId());
    }

    /** Cancelling is the other way off the list, and it is stamped exactly the same way. */
    @Test
    void cancellingATaskStampsItJustAsFinishingItDoes() throws Exception {
        long id = idOf(raise(admin, newTask("CUSTOMER", acme.getId())));

        edit(collections, id, "{\"status\": \"CANCELLED\"}");

        Task row = stored(id);
        assertThat(row.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(row.getCompletedAt()).isNotNull();
        assertThat(row.getCompletedByUserId()).isEqualTo(collections.getId());
    }

    /** Work that is merely under way is not finished, so nothing is stamped on the way to it. */
    @Test
    void movingATaskToInProgressStampsNothing() throws Exception {
        long id = idOf(raise(admin, newTask("CUSTOMER", acme.getId())));

        edit(collections, id, "{\"status\": \"IN_PROGRESS\"}");

        Task row = stored(id);
        assertThat(row.getCompletedAt()).isNull();
        assertThat(row.getCompletedByUserId()).isNull();
    }

    /**
     * Editing the notes of a task finished last week must not quietly move its completion to today
     * — the stamp is about the transition, not about the row being written (T4).
     */
    @Test
    void editingAnAlreadyDoneTaskDoesNotMoveItsCompletionStamp() throws Exception {
        long id = idOf(raise(admin, newTask("CUSTOMER", acme.getId())));
        edit(collections, id, "{\"status\": \"DONE\"}");

        // Back-date the stamp so "unchanged" cannot pass by today and the stamp coinciding, and
        // read it back so the comparison is against what the database actually holds.
        Task afterDone = stored(id);
        afterDone.setCompletedAt(afterDone.getCompletedAt().minusSeconds(7 * 24 * 3600));
        taskRepository.saveAndFlush(afterDone);
        Instant lastWeek = stored(id).getCompletedAt();

        edit(admin, id, "{\"notes\": \"Customer confirmed on the phone\"}");

        Task row = stored(id);
        assertThat(row.getNotes()).isEqualTo("Customer confirmed on the phone");
        assertThat(row.getCompletedAt()).isEqualTo(lastWeek);
        assertThat(row.getCompletedByUserId()).isEqualTo(collections.getId());
    }

    /** Re-sending the status it already has is not a second completion either. */
    @Test
    void sendingDoneToATaskThatIsAlreadyDoneDoesNotRestampIt() throws Exception {
        long id = idOf(raise(admin, newTask("CUSTOMER", acme.getId())));
        edit(collections, id, "{\"status\": \"DONE\"}");

        Task afterDone = stored(id);
        afterDone.setCompletedAt(afterDone.getCompletedAt().minusSeconds(3600));
        taskRepository.saveAndFlush(afterDone);
        Instant anHourAgo = stored(id).getCompletedAt();

        edit(admin, id, "{\"status\": \"DONE\"}");

        assertThat(stored(id).getCompletedAt()).isEqualTo(anHourAgo);
        assertThat(stored(id).getCompletedByUserId()).isEqualTo(collections.getId());
    }

    /** Re-opening it takes both off again: an open task has no completion to show. */
    @Test
    void reopeningAFinishedTaskClearsItsCompletionStamp() throws Exception {
        long id = idOf(raise(admin, newTask("CUSTOMER", acme.getId())));
        edit(collections, id, "{\"status\": \"DONE\"}");

        JsonNode reopened = edit(admin, id, "{\"status\": \"OPEN\"}");

        assertThat(reopened.get("completedAt").isNull()).isTrue();
        Task row = stored(id);
        assertThat(row.getCompletedAt()).isNull();
        assertThat(row.getCompletedByUserId()).isNull();
    }

    /**
     * A rule may raise work that is already accounted for, and the API accepts a status on
     * creation for exactly that, so the stamp goes on at creation rather than waiting for an edit
     * that will never come (T4).
     */
    @Test
    void aTaskRaisedStraightIntoDoneIsStampedAsItIsCreated() throws Exception {
        JsonNode task = raise(admin, newTask("CUSTOMER", acme.getId(), "status", "DONE"));

        assertThat(task.get("completedAt").isNull()).isFalse();
        Task row = stored(idOf(task));
        assertThat(row.getCompletedAt()).isNotNull();
        assertThat(row.getCompletedByUserId()).isEqualTo(admin.getId());
    }

    /** A task raised open is not stamped, which is the other half of the same rule. */
    @Test
    void aTaskRaisedOpenCarriesNoCompletionStamp() throws Exception {
        Task row = stored(idOf(raise(admin, newTask("CUSTOMER", acme.getId()))));

        assertThat(row.getStatus()).isEqualTo(TaskStatus.OPEN);
        assertThat(row.getCompletedAt()).isNull();
        assertThat(row.getCompletedByUserId()).isNull();
    }

    // ---- T9: overdue is worked out, never stored ------------------------------------

    @Test
    void aTaskPastItsDueDateAndStillOpenIsOverdue() throws Exception {
        JsonNode task = raise(admin, newTask("CUSTOMER", acme.getId(),
                "dueDate", YESTERDAY.toString()));

        assertThat(task.get("overdue").asBoolean()).isTrue();
    }

    @Test
    void aTaskPastItsDueDateAndStillInProgressIsOverdueToo() throws Exception {
        JsonNode task = raise(admin, newTask("CUSTOMER", acme.getId(),
                "dueDate", YESTERDAY.toString(), "status", "IN_PROGRESS"));

        assertThat(task.get("overdue").asBoolean()).isTrue();
    }

    /**
     * Finishing a late task makes it not late, rather than leaving it flagged for ever: overdue is
     * "still owed and past its date", and a finished task is owed by nobody (T9).
     */
    @Test
    void aTaskPastItsDueDateButDoneIsNotOverdue() throws Exception {
        long id = idOf(raise(admin, newTask("CUSTOMER", acme.getId(),
                "dueDate", YESTERDAY.toString())));

        JsonNode done = edit(collections, id, "{\"dueDate\": \"" + YESTERDAY + "\", \"status\": \"DONE\"}");

        assertThat(done.get("dueDate").asText()).isEqualTo(YESTERDAY.toString());
        assertThat(done.get("overdue").asBoolean()).isFalse();
    }

    @Test
    void aTaskPastItsDueDateButCancelledIsNotOverdue() throws Exception {
        JsonNode task = raise(admin, newTask("CUSTOMER", acme.getId(),
                "dueDate", YESTERDAY.toString(), "status", "CANCELLED"));

        assertThat(task.get("overdue").asBoolean()).isFalse();
    }

    /** A task due today is due today, not late: lateness starts at the beginning of the next day. */
    @Test
    void aTaskDueTodayIsNotYetOverdue() throws Exception {
        JsonNode task = raise(admin, newTask("CUSTOMER", acme.getId(), "dueDate", TODAY.toString()));

        assertThat(task.get("overdue").asBoolean()).isFalse();
    }

    /**
     * Nothing is written for it: the flag changes at midnight with nobody touching the row, so it
     * is worked out on the way out and there is no column to go stale (T9).
     */
    @Test
    void overdueFollowsTheDueDateWithoutTheRowBeingTouched() throws Exception {
        long id = idOf(raise(admin, newTask("CUSTOMER", acme.getId(), "dueDate", TOMORROW.toString())));
        assertThat(getOk("/api/tasks/" + id, admin).get("overdue").asBoolean()).isFalse();

        // Straight onto the row, as the calendar would do it — no write through the API at all.
        Task row = stored(id);
        row.setDueDate(YESTERDAY);
        taskRepository.saveAndFlush(row);

        assertThat(getOk("/api/tasks/" + id, admin).get("overdue").asBoolean()).isTrue();
    }

    // ---- removing one raised in error ------------------------------------------------

    @Test
    void aTaskRaisedInErrorIsDeletedWithItsAssigneeRows() throws Exception {
        long id = taskWithEverything();
        assertThat(assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.TASK, id)).hasSize(1);

        mockMvc.perform(delete("/api/tasks/" + id).with(as(admin)))
                .andExpect(status().isNoContent());

        assertThat(taskRepository.findById(id)).isEmpty();
        assertThat(assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.TASK, id)).isEmpty();
    }

    @Test
    void aTaskThatIsNotThereAnswersAsNotFound() throws Exception {
        mockMvc.perform(delete("/api/tasks/999999").with(as(admin)))
                .andExpect(status().isNotFound());
    }

    /** Deleting a task leaves the record it was raised on exactly where it was. */
    @Test
    void deletingATaskLeavesItsRecordAlone() throws Exception {
        long id = idOf(raise(admin, newTask("PAYMENT", acmePayment.getId())));

        mockMvc.perform(delete("/api/tasks/" + id).with(as(admin)))
                .andExpect(status().isNoContent());

        assertThat(paymentRepository.findById(acmePayment.getId())).isPresent();
    }
}
