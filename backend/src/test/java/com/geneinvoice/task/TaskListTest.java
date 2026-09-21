package com.geneinvoice.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The tasks table: the badge on a record's tab (T5), the list with its filter chips and sorting,
 * the "my tasks" the PRD asks for, and the schema the frontend builds its filter UI from (D.4).
 */
class TaskListTest extends TaskTestBase {

    /** The badge as the record's tab asks for it. */
    private long openCount(String entityType, Long entityId) throws Exception {
        return getOk("/api/tasks/count", admin,
                "entityType", entityType, "entityId", String.valueOf(entityId)).get("open").asLong();
    }

    /** One task in every status there is, each titled after the status it is in. */
    private void seedOneTaskPerStatus() throws Exception {
        for (TaskStatus status : TaskStatus.values()) {
            raise(admin, newTask("CUSTOMER", acme.getId(),
                    "title", "Task in " + status.name(), "status", status.name()));
        }
    }

    // ---- T5: the open count behind the tab badge -------------------------------------

    /**
     * The badge counts what is <em>not</em> terminal rather than listing OPEN and IN_PROGRESS, so
     * this is written against the enum itself: one task in every status there is, and the count is
     * however many of those statuses are still live. A status added later is counted as open until
     * somebody says otherwise, instead of silently vanishing from the badge.
     */
    @Test
    void theOpenCountIsEverythingThatIsNotTerminalRatherThanAHardCodedList() throws Exception {
        seedOneTaskPerStatus();
        long stillLive = Arrays.stream(TaskStatus.values()).filter(s -> !s.isTerminal()).count();

        JsonNode badge = getOk("/api/tasks/count", admin,
                "entityType", "CUSTOMER", "entityId", String.valueOf(acme.getId()));

        assertThat(badge.get("open").asLong()).isEqualTo(stillLive);
        assertThat(taskService.openCountFor(TaskEntityType.CUSTOMER, acme.getId()))
                .isEqualTo(stillLive);
    }

    /** Finishing a task takes it off the badge, and re-opening it puts it back. */
    @Test
    void theOpenCountFollowsATaskOffTheListAndBackOnAgain() throws Exception {
        long id = idOf(raise(admin, newTask("CUSTOMER", acme.getId())));
        assertThat(openCount("CUSTOMER", acme.getId())).isEqualTo(1);

        edit(admin, id, "{\"status\": \"DONE\"}");
        assertThat(openCount("CUSTOMER", acme.getId())).isZero();

        edit(admin, id, "{\"status\": \"IN_PROGRESS\"}");
        assertThat(openCount("CUSTOMER", acme.getId())).isEqualTo(1);
    }

    /** The badge is about one record: work on the customer is not work on its invoice. */
    @Test
    void theOpenCountIsCountedPerRecordAndNotPerCustomer() throws Exception {
        raise(admin, newTask("CUSTOMER", acme.getId()));
        raise(admin, newTask("INVOICE", acmeInvoice.getId()));
        raise(admin, newTask("INVOICE", acmeInvoice.getId()));

        assertThat(openCount("CUSTOMER", acme.getId())).isEqualTo(1);
        assertThat(openCount("INVOICE", acmeInvoice.getId())).isEqualTo(2);
        assertThat(openCount("PAYMENT", acmePayment.getId())).isZero();
    }

    /**
     * The count is about somebody else's record until the caller's own read of it says otherwise,
     * so it is checked exactly as the list of that record's tasks would be — a badge is a fact
     * about a record, and a caller who cannot open the record cannot have it.
     */
    @Test
    void theOpenCountIsRefusedOnARecordTheCallerCannotSee() throws Exception {
        raise(admin, newTask("INVOICE", acmeInvoice.getId()));

        mockMvc.perform(get("/api/tasks/count").with(as(otherSales))
                        .param("entityType", "INVOICE")
                        .param("entityId", String.valueOf(acmeInvoice.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void theOpenCountRefusesAKindOfRecordTasksDoNotHangOff() throws Exception {
        mockMvc.perform(get("/api/tasks/count").with(as(admin))
                        .param("entityType", "PROMISE").param("entityId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("entityType must be one of")));
    }

    // ---- the list ---------------------------------------------------------------------

    /** Newest first, because that is the table's own default sort. */
    @Test
    void theListIsNewestFirstUnlessAskedOtherwise() throws Exception {
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "First"));
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "Second"));
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "Third"));

        JsonNode page = getOk("/api/tasks", admin);

        assertThat(page.get("sort").asText()).isEqualTo("createdAt,desc");
        assertThat(titles(page)).containsExactly("Third", "Second", "First");
        assertThat(page.get("totalElements").asInt()).isEqualTo(3);
    }

    /** A record's own tab is the same list with the record's two chips added for it. */
    @Test
    void theEntityParametersNarrowTheListToOneRecordsTab() throws Exception {
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "On the customer"));
        raise(admin, newTask("INVOICE", acmeInvoice.getId(), "title", "On the invoice"));
        raise(admin, newTask("PAYMENT", acmePayment.getId(), "title", "On the payment"));

        JsonNode tab = getOk("/api/tasks", admin,
                "entityType", "INVOICE", "entityId", String.valueOf(acmeInvoice.getId()));

        assertThat(titles(tab)).containsExactly("On the invoice");
        assertThat(tab.get("appliedFilters").toString())
                .contains("entityType:eq:INVOICE")
                .contains("entityId:eq:" + acmeInvoice.getId());
    }

    /** The convenience parameters are ordinary chips, so they stack with the caller's own. */
    @Test
    void aRecordsTabStillHonoursTheCallersOwnFilterChips() throws Exception {
        long done = idOf(raise(admin, newTask("INVOICE", acmeInvoice.getId(), "title", "Closed one")));
        edit(admin, done, "{\"status\": \"DONE\"}");
        raise(admin, newTask("INVOICE", acmeInvoice.getId(), "title", "Live one"));

        JsonNode tab = getOk("/api/tasks", admin,
                "entityType", "INVOICE", "entityId", String.valueOf(acmeInvoice.getId()),
                "filter", "status:eq:OPEN");

        assertThat(titles(tab)).containsExactly("Live one");
    }

    @Test
    void theListFiltersByStatusOneValueOrSeveral() throws Exception {
        seedOneTaskPerStatus();

        assertThat(titles(getOk("/api/tasks", admin, "filter", "status:eq:OPEN")))
                .containsExactly("Task in OPEN");
        assertThat(titles(getOk("/api/tasks", admin, "filter", "status:in:OPEN,IN_PROGRESS")))
                .containsExactlyInAnyOrder("Task in OPEN", "Task in IN_PROGRESS");
        assertThat(titles(getOk("/api/tasks", admin, "filter", "status:notIn:DONE,CANCELLED")))
                .containsExactlyInAnyOrder("Task in OPEN", "Task in IN_PROGRESS");
    }

    @Test
    void theListFiltersByTheKindOfRecordTheWorkIsOn() throws Exception {
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "On the customer"));
        raise(admin, newTask("INVOICE", acmeInvoice.getId(), "title", "On the invoice"));
        raise(admin, newTask("PAYMENT", acmePayment.getId(), "title", "On the payment"));

        assertThat(titles(getOk("/api/tasks", admin, "filter", "entityType:eq:PAYMENT")))
                .containsExactly("On the payment");
        assertThat(titles(getOk("/api/tasks", admin, "filter", "entityType:neq:CUSTOMER")))
                .containsExactlyInAnyOrder("On the invoice", "On the payment");
    }

    /** The denormalised customer is what makes one query answer "everything about this account". */
    @Test
    void theListFiltersByCustomerAcrossAllThreeKindsOfRecordAtOnce() throws Exception {
        Customer globex = customer("Globex Corp");
        Invoice theirs = invoice(globex, sales);
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "Acme customer"));
        raise(admin, newTask("INVOICE", acmeInvoice.getId(), "title", "Acme invoice"));
        raise(admin, newTask("PAYMENT", acmePayment.getId(), "title", "Acme payment"));
        raise(admin, newTask("INVOICE", theirs.getId(), "title", "Globex invoice"));

        JsonNode page = getOk("/api/tasks", admin, "filter", "customerId:eq:" + acme.getId());

        assertThat(titles(page))
                .containsExactlyInAnyOrder("Acme customer", "Acme invoice", "Acme payment");
    }

    @Test
    void theListFindsATaskByWordsInItsTitle() throws Exception {
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "Chase the remittance advice"));
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "Send the statement"));

        assertThat(titles(getOk("/api/tasks", admin, "filter", "title:contains:remittance")))
                .containsExactly("Chase the remittance advice");
    }

    /** A due-date window is how "what is late" and "what is due this week" are asked for. */
    @Test
    void theListFiltersByADueDateWindow() throws Exception {
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "Was due", "dueDate", YESTERDAY.toString()));
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "Due today", "dueDate", TODAY.toString()));
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "Due later", "dueDate", TOMORROW.toString()));
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "No date at all"));

        assertThat(titles(getOk("/api/tasks", admin, "filter", "dueDate:lte:" + YESTERDAY)))
                .containsExactly("Was due");
        assertThat(titles(getOk("/api/tasks", admin,
                "filter", "dueDate:between:" + YESTERDAY + "," + TODAY)))
                .containsExactlyInAnyOrder("Was due", "Due today");
    }

    @Test
    void theListSortsByDueDateInEitherDirection() throws Exception {
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "Later", "dueDate", TOMORROW.toString()));
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "Earlier", "dueDate", YESTERDAY.toString()));
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "Today", "dueDate", TODAY.toString()));

        assertThat(titles(getOk("/api/tasks", admin, "sort", "dueDate,asc")))
                .containsExactly("Earlier", "Today", "Later");
        assertThat(titles(getOk("/api/tasks", admin, "sort", "dueDate,desc")))
                .containsExactly("Later", "Today", "Earlier");
    }

    @Test
    void theListSortsByTitleAlphabetically() throws Exception {
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "Beta"));
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "Alpha"));

        assertThat(titles(getOk("/api/tasks", admin, "sort", "title,asc")))
                .containsExactly("Alpha", "Beta");
    }

    /**
     * Notes are long free text: they are filterable but never a sort key, and asking to sort by
     * them is a 400 rather than an unindexed scan of every task in the system.
     */
    @Test
    void theNotesColumnIsNotASortKey() throws Exception {
        mockMvc.perform(get("/api/tasks").with(as(admin)).param("sort", "notes,asc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aColumnTheTasksTableDoesNotHaveIsRefusedRatherThanIgnored() throws Exception {
        mockMvc.perform(get("/api/tasks").with(as(admin)).param("filter", "assignee:eq:7"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/tasks").with(as(admin)).param("sort", "assignee,asc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theListPagesAndReportsTheTotalAcrossThePages() throws Exception {
        for (int i = 1; i <= 12; i++) {
            raise(admin, newTask("CUSTOMER", acme.getId(), "title", "Task " + i));
        }

        JsonNode first = getOk("/api/tasks", admin, "size", "10");

        assertThat(first.get("content")).hasSize(10);
        assertThat(first.get("totalElements").asInt()).isEqualTo(12);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        assertThat(getOk("/api/tasks", admin, "size", "10", "page", "1").get("content")).hasSize(2);
    }

    // ---- "my tasks" ---------------------------------------------------------------------

    /**
     * The PRD's "my tasks". A role assignee is resolved against the POC book here and now, so the
     * rep who holds the seat finds the work and a colleague who does not holds none of it (A2).
     */
    @Test
    void mineIsTheWorkTheCallerIsOnByNameOrByTheSeatTheyHold() throws Exception {
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "Given to Cara by name",
                "assignees", List.of(toUser(collections))));
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "Given to the Collection seat",
                "assignees", List.of(toRole("COLLECTION_POC", "CUSTOMER"))));
        raise(admin, newTask("INVOICE", acmeInvoice.getId(), "title", "Given to this invoice's rep",
                "assignees", List.of(toRole("SALES_POC", "RECORD"))));
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "Nobody's in particular"));

        // Cara is the customer's primary Collection POC, so both of hers come back.
        assertThat(titles(getOk("/api/tasks", collections, "mine", "true")))
                .containsExactlyInAnyOrder("Given to Cara by name", "Given to the Collection seat");
        // Sam is the Sales POC the invoice itself names.
        assertThat(titles(getOk("/api/tasks", sales, "mine", "true")))
                .containsExactly("Given to this invoice's rep");
        // Sid holds neither seat, so nothing is his — though the team's work is still visible.
        assertThat(getOk("/api/tasks", otherSales, "mine", "true").get("content")).isEmpty();
        assertThat(getOk("/api/tasks", otherSales).get("totalElements").asInt()).isEqualTo(4);
    }

    /**
     * Nothing about who a role reaches is stored against the task: somebody who joins the book
     * this morning finds the work waiting for them, and somebody who leaves stops seeing it (A2).
     */
    @Test
    void mineFollowsThePocBookRatherThanWhoHeldTheSeatWhenTheWorkWasRaised() throws Exception {
        User cora = user("cora.collections", DataSeeder.ROLE_COLLECTION_POC);
        raise(admin, newTask("CUSTOMER", acme.getId(), "title", "For whoever collects",
                "assignees", List.of(toRole("COLLECTION_POC", "CUSTOMER"))));
        assertThat(getOk("/api/tasks", cora, "mine", "true").get("content")).isEmpty();

        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, cora.getId(), false);

        assertThat(titles(getOk("/api/tasks", cora, "mine", "true")))
                .containsExactly("For whoever collects");
    }

    /** "Mine" is the caller's own ask, not a scope the server pinned on, so no chip is locked. */
    @Test
    void mineReportsNoLockedFilterBecauseNothingWasPinnedOnTheCaller() throws Exception {
        raise(admin, newTask("CUSTOMER", acme.getId(), "assignees", List.of(toUser(collections))));

        assertThat(getOk("/api/tasks", collections, "mine", "true").get("lockedFilters")).isEmpty();
    }

    // ---- the published schema (D.4, AC-D3) ------------------------------------------------

    /**
     * The frontend builds the filter UI from the columns themselves, so the schema is served under
     * the table's own view privilege and says what each column may be sorted and filtered by.
     */
    @Test
    void theTasksSchemaIsServedUnderTaskView() throws Exception {
        JsonNode schema = getOk("/api/table-schemas/tasks", user("vera.viewer", "VIEWER"));

        assertThat(schema.get("entity").asText()).isEqualTo("tasks");
        assertThat(schema.get("defaultSort").asText()).isEqualTo("createdAt,desc");

        List<String> columns = new java.util.ArrayList<>();
        schema.get("columns").forEach(c -> columns.add(c.get("name").asText()));
        assertThat(columns).contains("id", "entityType", "entityId", "entityLabel", "customerId",
                "title", "dueDate", "status", "notes", "createdAt");

        JsonNode status = column(schema, "status");
        assertThat(status.get("type").asText()).isEqualTo("ENUM");
        List<String> values = new java.util.ArrayList<>();
        status.get("enumValues").forEach(v -> values.add(v.asText()));
        assertThat(values).containsExactly(Arrays.stream(TaskStatus.values())
                .map(Enum::name).toArray(String[]::new));

        // Notes are filterable free text and deliberately not a sort key.
        assertThat(column(schema, "notes").get("sortable").asBoolean()).isFalse();
        assertThat(column(schema, "notes").get("filterable").asBoolean()).isTrue();
    }

    private static JsonNode column(JsonNode schema, String name) {
        for (JsonNode c : schema.get("columns")) {
            if (c.get("name").asText().equals(name)) return c;
        }
        throw new AssertionError("No column " + name + " in the tasks schema");
    }
}
