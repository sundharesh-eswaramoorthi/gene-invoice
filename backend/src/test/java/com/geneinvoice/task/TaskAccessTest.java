package com.geneinvoice.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.assignee.AssigneeOwnerType;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.user.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may raise work on what (T1), who may see it at all, and what happens to it when the customer
 * it was raised against is deleted (T6). Raising a task is exactly as allowed as reading the record
 * it hangs off, because the create path loads that record through the caller's own eyes.
 */
class TaskAccessTest extends TaskTestBase {

    @Autowired TaskCascade taskCascade;
    @Autowired PlatformTransactionManager txManager;

    /** Reads tasks and records, but was never given TASK_MANAGE: the seeded read-only role. */
    private User viewer() {
        return user("vera.viewer", "VIEWER");
    }

    /** Sees customers and invoices, holds no task privilege of any kind. */
    private User strangerToTasks() {
        return user("nora.notasks", roleWith("NO_TASKS_CLERK",
                Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW, Privileges.SCOPE_OVERRIDE).getName());
    }

    /** May raise work, but has no business looking at invoices. */
    private User taskManagerWithoutInvoiceView() {
        return user("ingrid.noinvoices", roleWith("TASKS_WITHOUT_INVOICES",
                Privileges.TASK_VIEW, Privileges.TASK_MANAGE,
                Privileges.CUSTOMER_VIEW, Privileges.SCOPE_OVERRIDE).getName());
    }

    // ---- T1: a task is exactly as raisable as its record is readable -----------------

    /**
     * {@link #otherSales} is a real Sales POC with TASK_MANAGE, but this invoice is another rep's.
     * Their read of it is a 404, so raising work on it is the same 404 — there is no second rule
     * here that could fall out of step with the record's own.
     */
    @Test
    void aTaskCannotBeRaisedOnAnInvoiceOutsideTheCallersOwnBook() throws Exception {
        postTask(otherSales, newTask("INVOICE", acmeInvoice.getId()))
                .andExpect(status().isNotFound());

        assertThat(taskRepository.count()).isZero();
    }

    /** The same for a customer nobody has seated them on and whose invoices are not theirs. */
    @Test
    void aTaskCannotBeRaisedOnACustomerOutsideTheCallersOwnBook() throws Exception {
        postTask(otherSales, newTask("CUSTOMER", acme.getId()))
                .andExpect(status().isNotFound());

        assertThat(taskRepository.count()).isZero();
    }

    /**
     * A sales rep holds PAYMENT_VIEW but is nobody's Collection POC, so their payments book is
     * empty rather than the company's — and an empty book reaches no payment at all (§7).
     */
    @Test
    void aTaskCannotBeRaisedOnAPaymentTheCallersBookNeverReaches() throws Exception {
        postTask(otherSales, newTask("PAYMENT", acmePayment.getId()))
                .andExpect(status().isNotFound());

        assertThat(taskRepository.count()).isZero();
    }

    /** Being the rep on the invoice is what makes it theirs, and then the task goes on. */
    @Test
    void theRepWhoseInvoiceItIsCanRaiseWorkOnIt() throws Exception {
        JsonNode task = raise(sales, newTask("INVOICE", acmeInvoice.getId()));

        assertThat(task.get("entityId").asLong()).isEqualTo(acmeInvoice.getId());
        assertThat(task.get("createdByUserId").asLong()).isEqualTo(sales.getId());
    }

    /**
     * A caller who may not look at invoices at all is refused before the record is even loaded:
     * the kind of record is closed to them, which is a 403 rather than the 404 that means "not
     * yours".
     */
    @Test
    void aCallerWhoMayNotSeeInvoicesAtAllIsRefusedTheKindRatherThanTheRecord() throws Exception {
        postTask(taskManagerWithoutInvoiceView(), newTask("INVOICE", acmeInvoice.getId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTaskCannotBeRaisedOnARecordThatIsNotThere() throws Exception {
        postTask(admin, newTask("CUSTOMER", 999999L)).andExpect(status().isNotFound());
        postTask(admin, newTask("INVOICE", 999999L)).andExpect(status().isNotFound());
        postTask(admin, newTask("PAYMENT", 999999L)).andExpect(status().isNotFound());
    }

    // ---- privilege negatives ---------------------------------------------------------

    /** TASK_VIEW is a reader's privilege: it sees the work and never raises, edits or removes it. */
    @Test
    void taskViewReadsTheWorkButCannotRaiseEditOrRemoveIt() throws Exception {
        User vera = viewer();
        long id = idOf(raise(admin, newTask("CUSTOMER", acme.getId())));

        getOk("/api/tasks/" + id, vera);
        getOk("/api/tasks", vera);

        postTask(vera, newTask("CUSTOMER", acme.getId())).andExpect(status().isForbidden());
        patchTask(vera, id, "{\"status\": \"DONE\"}").andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/tasks/" + id).with(as(vera)))
                .andExpect(status().isForbidden());

        assertThat(stored(id).getStatus()).isEqualTo(TaskStatus.OPEN);
    }

    /** No task privilege at all sees nothing: not the list, not one task, not even the badge. */
    @Test
    void aCallerWithNoTaskPrivilegeSeesNoPartOfTheFeature() throws Exception {
        User nora = strangerToTasks();
        long id = idOf(raise(admin, newTask("CUSTOMER", acme.getId())));

        mockMvc.perform(get("/api/tasks").with(as(nora))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tasks/" + id).with(as(nora))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tasks/count").with(as(nora))
                        .param("entityType", "CUSTOMER")
                        .param("entityId", String.valueOf(acme.getId())))
                .andExpect(status().isForbidden());
        postTask(nora, newTask("CUSTOMER", acme.getId())).andExpect(status().isForbidden());
    }

    /**
     * The table's shape is read under the table's own view privilege: a caller who may not list
     * tasks has no business enumerating the columns either, and the table is not even named to
     * them (AUTH-07).
     */
    @Test
    void theTasksSchemaIsClosedToACallerWithoutTaskView() throws Exception {
        User nora = strangerToTasks();

        mockMvc.perform(get("/api/table-schemas/tasks").with(as(nora)))
                .andExpect(status().isForbidden());

        JsonNode named = getOk("/api/table-schemas", nora);
        assertThat(named.toString()).doesNotContain("tasks");
        assertThat(getOk("/api/table-schemas/all", nora).has("tasks")).isFalse();
    }

    /**
     * Work is internal: a customer login is not offered the feature at all, neither to read nor to
     * raise. Who owes what internally is staff identity, which customers never see (AC-A8).
     */
    @Test
    void aCustomerLoginIsRefusedTheWholeFeature() throws Exception {
        long id = idOf(raise(admin, newTask("CUSTOMER", acme.getId())));

        mockMvc.perform(get("/api/tasks").with(as(acmeLogin))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tasks/" + id).with(as(acmeLogin))).andExpect(status().isForbidden());
        postTask(acmeLogin, newTask("CUSTOMER", acme.getId())).andExpect(status().isForbidden());
        patchTask(acmeLogin, id, "{\"status\": \"DONE\"}").andExpect(status().isForbidden());
        mockMvc.perform(get("/api/table-schemas/tasks").with(as(acmeLogin)))
                .andExpect(status().isForbidden());
    }

    /**
     * And underneath the privilege, the read itself holds a customer to its own rows: another
     * customer's task answers exactly as a missing one does, so the id space cannot be walked to
     * learn that somebody else's record exists (AUTH-08).
     */
    @Test
    void aCustomerScopedCallerCannotTellAnotherCustomersTaskFromAMissingOne() throws Exception {
        Customer globex = customer("Globex Corp");
        long theirs = idOf(raise(admin, newTask("CUSTOMER", globex.getId())));
        long ours = idOf(raise(admin, newTask("CUSTOMER", acme.getId())));

        actAs(acmeLogin);
        assertThatThrownBy(() -> taskService.get(theirs))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Task not found");
        assertThatThrownBy(() -> taskService.get(999999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Task not found");
        // Its own is still its own, which is what makes the two answers worth telling apart.
        assertThat(taskService.get(ours).getId()).isEqualTo(ours);
    }

    // ---- T2: reading a task is not re-reading its record ------------------------------

    /**
     * A task is reachable by the person it was given to even when the record's book is not theirs
     * — that is the whole point of assigning work to somebody who is not its POC (T2). Re-checking
     * the record on the way out would make the assignment unreadable by the assignee.
     */
    @Test
    void aTaskOnARecordOutsideTheCallersBookIsStillReadableByThem() throws Exception {
        long id = idOf(raise(admin, newTask("INVOICE", acmeInvoice.getId(),
                "assignees", List.of(toUser(otherSales)))));

        // otherSales cannot open the invoice itself...
        mockMvc.perform(get("/api/invoices/" + acmeInvoice.getId()).with(as(otherSales)))
                .andExpect(status().isNotFound());
        // ...and can still see the work they were given on it.
        JsonNode task = getOk("/api/tasks/" + id, otherSales);
        assertThat(task.get("entityLabel").asText())
                .isEqualTo("Invoice " + acmeInvoice.getInvoiceNumber());
    }

    // ---- T6: the customer-delete cascade ----------------------------------------------

    /**
     * A task points at its record by id with no foreign key to stop it, so without the cascade its
     * rows would either outlive the customer or hold the customer's own row down and turn the
     * delete into a 409. Both its tasks and their assignee rows go, in the delete's own
     * transaction.
     */
    @Test
    void deletingACustomerTakesItsTasksAndTheirAssigneeRowsWithItWithoutAConflict() throws Exception {
        Customer gone = customer("Gone Ltd", "ap@gone.test");
        long doomed = idOf(raise(admin, newTask("CUSTOMER", gone.getId(),
                "title", "Chase Gone Ltd",
                "assignees", List.of(toUser(collections), toRole("COLLECTION_POC", "CUSTOMER")))));
        long kept = idOf(raise(admin, newTask("CUSTOMER", acme.getId(),
                "title", "Chase Acme",
                "assignees", List.of(toUser(collections)))));

        mockMvc.perform(delete("/api/customers/" + gone.getId()).with(as(admin)))
                .andExpect(status().isOk());

        assertThat(taskRepository.findById(doomed)).isEmpty();
        assertThat(taskRepository.findByCustomerId(gone.getId())).isEmpty();
        assertThat(assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.TASK, doomed)).isEmpty();

        // Another customer's work is untouched: the sweep is keyed on the customer being deleted.
        assertThat(taskRepository.findById(kept)).isPresent();
        assertThat(assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.TASK, kept)).hasSize(1);
    }

    /**
     * The sweep is keyed on the denormalised {@code customer_id}, so it reaches the work raised on
     * the customer's invoices and payments as well as on the customer itself — which is what that
     * column is on the row for (T6).
     *
     * <p>It is driven through the very two statements {@code CustomerService.delete} runs, in one
     * transaction as it runs them, rather than through the endpoint: a customer that still has
     * invoices and payments cannot be deleted at all — those rows hold it down in their own right,
     * long before tasks come into it — and this is precisely the case the customer copy exists for.
     */
    @Test
    void theCascadeAlsoSweepsTheTasksRaisedOnThatCustomersInvoicesAndPayments() throws Exception {
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "On the customer"));
        raise(admin, newTask("INVOICE", acmeInvoice.getId(), "title", "On the invoice",
                "assignees", List.of(toUser(sales))));
        raise(admin, newTask("PAYMENT", acmePayment.getId(), "title", "On the payment",
                "assignees", List.of(toUser(collections))));
        Customer globex = customer("Globex Corp");
        long elsewhere = idOf(raise(admin, newTask("CUSTOMER", globex.getId(),
                "title", "Somebody else's", "assignees", List.of(toUser(collections)))));
        assertThat(taskRepository.findByCustomerId(acme.getId())).hasSize(3);

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            taskCascade.onCustomerDeleted(acme.getId());
            assigneeRepository.deleteForCustomer(acme.getId());
        });

        // Every kind of record the customer owned, in one sweep.
        assertThat(taskRepository.findByCustomerId(acme.getId())).isEmpty();
        // And the assignee rows of all three, swept once for every kind of owner at a time.
        assertThat(assigneeRepository.findAll())
                .allSatisfy(row -> assertThat(row.getCustomerId()).isEqualTo(globex.getId()));
        assertThat(taskRepository.findById(elsewhere)).isPresent();
        assertThat(assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.TASK, elsewhere)).hasSize(1);
    }
}
