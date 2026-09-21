package com.geneinvoice.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.common.Money;
import com.geneinvoice.email.EmailDtos;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.role.Role;
import com.geneinvoice.task.Task;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Writing a rule: the WHEN, the WHERE and the THEN, and everything that is refused while the author
 * is still looking at the form (R1, R9). The point of validating the WHERE with
 * {@code TableQuery.parseUnpaged} at this moment is that a rule which could never match is a 400
 * naming the column, not a run that skips quietly every night at seven.
 */
class AutomationRuleAuthoringTest extends AutomationTestBase {

    @Autowired PrivilegeRepository privilegeRepository;

    private ResultActions postRule(User caller, AutomationDtos.CreateRuleRequest req) throws Exception {
        return mockMvc.perform(post("/api/automation/rules").with(as(caller))
                .contentType(MediaType.APPLICATION_JSON).content(json(req)));
    }

    /** A rule that is well formed apart from whatever the test is about. */
    private AutomationDtos.CreateRuleRequest chaseBigInvoices(List<String> filters) {
        return new AutomationDtos.CreateRuleRequest("Chase big invoices", "Anything over 500",
                null, "INVOICE", "UPDATED", filters, "CREATE_TASK",
                taskSpec("Chase this invoice", roleToken("COLLECTION_POC", "CUSTOMER")));
    }

    private String messageOf(ResultActions result) throws Exception {
        JsonNode body = objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
        return body.get("message").asText();
    }

    // ---- WHEN, WHERE and THEN ------------------------------------------------------

    /**
     * The whole sentence, written and read back: when an invoice is updated, where its total is
     * over 500, then create a task for the customer's Collection POC. The labels come back with it
     * because one service decides how a rule reads, rather than each screen wording it again.
     */
    @Test
    void aRuleStatesWhenWhereAndThenAndReadsBackTheWayItWasWritten() throws Exception {
        JsonNode rule = objectMapper.readTree(postRule(admin, chaseBigInvoices(List.of("total:gt:500")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(rule.get("entityType").asText()).isEqualTo("INVOICE");
        assertThat(rule.get("trigger").asText()).isEqualTo("UPDATED");
        assertThat(rule.get("triggerLabel").asText()).isEqualTo("is updated");
        assertThat(rule.get("filters")).hasSize(1);
        assertThat(rule.get("filters").get(0).asText()).isEqualTo("total:gt:500");
        assertThat(rule.get("action").asText()).isEqualTo("CREATE_TASK");
        assertThat(rule.get("actionLabel").asText()).isEqualTo("create a task");
        assertThat(rule.at("/actionSpec/title").asText()).isEqualTo("Chase this invoice");
        // The pick is kept as it was picked and not resolved into people: a rule has no record
        // behind it, so there is no customer whose POC book could answer who it reaches (A2).
        assertThat(rule.at("/actionSpec/assignees/0/role").asText()).isEqualTo("COLLECTION_POC");
        assertThat(rule.at("/actionSpec/assignees/0/level").asText()).isEqualTo("CUSTOMER");
        // Somebody writing a rule means it to run, and it is theirs.
        assertThat(rule.get("enabled").asBoolean()).isTrue();
        assertThat(rule.get("runCount").asLong()).isZero();
        assertThat(rule.get("createdByUserId").asLong()).isEqualTo(admin.getId());
    }

    /** A rule with no WHERE at all matches every record of its kind, which is a real thing to want. */
    @Test
    void aRuleMayHaveNoFiltersAtAllAndThenItMatchesEveryRecordOfItsKind() throws Exception {
        AutomationRule saved = rule("Every new invoice", AutomationEntityType.INVOICE,
                TriggerKind.CREATED, List.of(), ActionType.CREATE_TASK,
                taskSpec("Look at this", userToken(collections)));

        assertThat(automationService.dto(saved.getId()).filters()).isEmpty();
    }

    // ---- a bad WHERE is a 400 at authoring time ------------------------------------

    /**
     * The whole reason the WHERE is parsed against the list's own schema when the rule is saved:
     * a column that does not exist is named here and now, rather than every run skipping with a
     * reason nobody is reading (R9).
     */
    @Test
    void aFilterOnAColumnTheRecordDoesNotHaveIsRefusedWhenTheRuleIsWritten() throws Exception {
        ResultActions refused = postRule(admin, chaseBigInvoices(List.of("totl:gt:500")))
                .andExpect(status().isBadRequest());

        assertThat(messageOf(refused)).isEqualTo("Unknown column: totl");
        assertThat(automationRuleRepository.findAll()).isEmpty();
    }

    /** The same for an operator the column's type does not take: the column and its type are both named. */
    @Test
    void aFilterUsingAnOperatorTheColumnsTypeDoesNotTakeIsRefusedByName() throws Exception {
        ResultActions refused = postRule(admin, chaseBigInvoices(List.of("invoiceNumber:gt:5")))
                .andExpect(status().isBadRequest());

        assertThat(messageOf(refused)).isEqualTo("Operator gt is not valid for column invoiceNumber (TEXT)");
        assertThat(automationRuleRepository.findAll()).isEmpty();
    }

    /**
     * The WHERE is checked against the kind the rule watches, not against tables in general:
     * "total" is an invoice's column and a rule on customers may not ask for it.
     */
    @Test
    void aFilterIsCheckedAgainstTheKindOfRecordTheRuleWatchesAndNotAnyOther() throws Exception {
        ResultActions refused = postRule(admin, new AutomationDtos.CreateRuleRequest(
                "Watch customers", null, null, "CUSTOMER", "UPDATED", List.of("total:gt:500"),
                "CREATE_TASK", taskSpec("Look at this", userToken(collections))))
                .andExpect(status().isBadRequest());

        assertThat(messageOf(refused)).isEqualTo("Unknown column: total");
    }

    /** A chip that is not {@code field:op:value} at all is refused the way the filter bar refuses one. */
    @Test
    void aFilterThatIsNotAChipAtAllIsRefused() throws Exception {
        ResultActions refused = postRule(admin, chaseBigInvoices(List.of("total > 500")))
                .andExpect(status().isBadRequest());

        assertThat(messageOf(refused)).startsWith("Malformed filter");
    }

    /** More filters than this is somebody building a query rather than a rule, and every one is run per record. */
    @Test
    void aRuleTakesAtMostTwentyFilters() throws Exception {
        List<String> tooMany = Stream.generate(() -> "total:gt:1")
                .limit(AutomationService.MAX_FILTERS + 1).toList();

        ResultActions refused = postRule(admin, chaseBigInvoices(tooMany)).andExpect(status().isBadRequest());

        assertThat(messageOf(refused)).isEqualTo("A rule takes at most 20 filters");
    }

    // ---- a bad THEN is a 400 too ---------------------------------------------------

    @Test
    void aTaskRuleWithoutATitleIsRefusedBecauseThereWouldBeNothingOnTheTask() throws Exception {
        ResultActions refused = postRule(admin, new AutomationDtos.CreateRuleRequest(
                "Untitled", null, null, "INVOICE", "UPDATED", List.of(), "CREATE_TASK",
                taskSpec(null, userToken(collections)))).andExpect(status().isBadRequest());

        assertThat(messageOf(refused)).isEqualTo("A task rule needs a title");
    }

    /**
     * A promise commits a customer to a figure, so the figure is the rule author's and is never
     * guessed from the record: a rule that states no amount is not a rule.
     */
    @Test
    void aPromiseRuleWithoutAnAmountIsRefused() throws Exception {
        ResultActions refused = postRule(admin, new AutomationDtos.CreateRuleRequest(
                "Promise nothing", null, null, "INVOICE", "UPDATED", List.of(), "CREATE_PROMISE",
                new AutomationDtos.ActionSpec(null, "note", 7, null, null, List.of(), null)))
                .andExpect(status().isBadRequest());

        assertThat(messageOf(refused)).isEqualTo("A promise rule needs an amount greater than zero");
    }

    /**
     * One ActionSpec answers for all four actions, so its own bounds are an email's: a title of 500
     * and a body of 20000. A task holds 200 and 2000. A rule that stated more saved cleanly and
     * then skipped on every run for ever, with the reason on a runs list nobody reads — the exact
     * thing this whole check exists to stop happening (D-74).
     */
    @Test
    void aTaskRuleIsMeasuredAgainstATasksOwnColumnsAndNotAnEmailSubject() throws Exception {
        String subjectLength = "C".repeat(Task.TITLE_MAX + 1);

        ResultActions refused = postRule(admin, new AutomationDtos.CreateRuleRequest(
                "Chase at length", null, null, "INVOICE", "UPDATED", List.of(), "CREATE_TASK",
                taskSpec(subjectLength, userToken(collections)))).andExpect(status().isBadRequest());

        assertThat(messageOf(refused)).isEqualTo("A task title must be at most 200 characters");
        assertThat(automationRuleRepository.findAll()).isEmpty();
    }

    /** The same for the notes, against the task's notes column rather than an email body's. */
    @Test
    void aTaskRuleWhoseNotesAreWiderThanATasksAreRefused() throws Exception {
        ResultActions refused = postRule(admin, new AutomationDtos.CreateRuleRequest(
                "Chase at length", null, null, "INVOICE", "UPDATED", List.of(), "CREATE_TASK",
                new AutomationDtos.ActionSpec("Chase this invoice", "N".repeat(Task.NOTES_MAX + 1),
                        null, null, null, List.of(userToken(collections)), null)))
                .andExpect(status().isBadRequest());

        assertThat(messageOf(refused)).isEqualTo("Task notes must be at most 2000 characters");
        assertThat(automationRuleRepository.findAll()).isEmpty();
    }

    /**
     * And the opening status, which is read at run time by {@code TaskStatus.parse} and was read
     * nowhere at all before that. "TODO" is what somebody types; a task's statuses are OPEN,
     * IN_PROGRESS, DONE and CANCELLED, and saying so at the form is the difference between a
     * corrected rule and a rule that skips for ever (D-74).
     */
    @Test
    void aTaskRuleWithAnOpeningStatusNoTaskHasIsRefusedAndSaysWhatThereIs() throws Exception {
        ResultActions refused = postRule(admin, new AutomationDtos.CreateRuleRequest(
                "Chase, already done", null, null, "INVOICE", "UPDATED", List.of(), "CREATE_TASK",
                new AutomationDtos.ActionSpec("Chase this invoice", null, null, null, null,
                        List.of(userToken(collections)), "TODO")))
                .andExpect(status().isBadRequest());

        assertThat(messageOf(refused))
                .isEqualTo("status must be one of [OPEN, IN_PROGRESS, DONE, CANCELLED]");
        assertThat(automationRuleRepository.findAll()).isEmpty();
    }

    /** A status a task does have is accepted, and read back as it was written. */
    @Test
    void aTaskRuleMayStateAnOpeningStatusATaskActuallyHas() throws Exception {
        AutomationRule saved = rule("Chase, in progress", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of(), ActionType.CREATE_TASK,
                new AutomationDtos.ActionSpec("Chase this invoice", null, null, null, null,
                        List.of(userToken(collections)), "IN_PROGRESS"));

        assertThat(automationService.dto(saved.getId()).actionSpec().status()).isEqualTo("IN_PROGRESS");
    }

    /**
     * The one figure a rule states gets the same treatment as its text. {@code
     * payment_promises.amount} is NUMERIC(14,2), and an amount wider than that is worse than a
     * skip: the insert dies, so every run is an unexpected failure, retried twice and left FAILED,
     * once for every record the rule ever matches (D-74).
     */
    @Test
    void aPromiseRuleForAnAmountWiderThanTheColumnIsRefused() throws Exception {
        ResultActions refused = postRule(admin, new AutomationDtos.CreateRuleRequest(
                "Promise the impossible", null, null, "INVOICE", "UPDATED", List.of(),
                "CREATE_PROMISE", new AutomationDtos.ActionSpec(null, "they said they would pay",
                        7, new BigDecimal("99999999999999999"), null, List.of(), null)))
                .andExpect(status().isBadRequest());

        assertThat(messageOf(refused))
                .isEqualTo("The promised amount must be at most 999999999999.99");
        assertThat(automationRuleRepository.findAll()).isEmpty();
    }

    /** And an amount with more decimals than money has, which the column would silently round. */
    @Test
    void aPromiseRuleForAnAmountFinerThanMoneyIsRefused() throws Exception {
        ResultActions refused = postRule(admin, new AutomationDtos.CreateRuleRequest(
                "Promise a fraction", null, null, "INVOICE", "UPDATED", List.of(),
                "CREATE_PROMISE", new AutomationDtos.ActionSpec(null, null, 7,
                        new BigDecimal("100.12345"), null, List.of(), null)))
                .andExpect(status().isBadRequest());

        assertThat(messageOf(refused))
                .isEqualTo("The promised amount " + Money.CENTS_MESSAGE);
    }

    /**
     * A dispute is about one invoice or one payment. A rule on customers could only ever skip, once
     * per customer forever, so it is refused at authoring instead.
     */
    @Test
    void aDisputeRuleOnCustomersIsRefusedBecauseThereIsNothingToDispute() throws Exception {
        ResultActions refused = postRule(admin, new AutomationDtos.CreateRuleRequest(
                "Dispute the customer", null, null, "CUSTOMER", "UPDATED", List.of(), "CREATE_DISPUTE",
                new AutomationDtos.ActionSpec(null, "Wrong", null, null, null, List.of(), null)))
                .andExpect(status().isBadRequest());

        assertThat(messageOf(refused))
                .isEqualTo("A dispute is raised against an invoice or a payment, not a customer");
    }

    @Test
    void anEmailRuleWithNoRecipientIsRefused() throws Exception {
        ResultActions refused = postRule(admin, new AutomationDtos.CreateRuleRequest(
                "Tell nobody", null, null, "INVOICE", "UPDATED", List.of(), "SEND_EMAIL",
                new AutomationDtos.ActionSpec("Your invoice", "Hello", null, null,
                        userToken(admin), List.of(), null)))
                .andExpect(status().isBadRequest());

        assertThat(messageOf(refused)).isEqualTo("An email rule needs at least one recipient");
    }

    /**
     * The assignee tokens go through the same check a person picking them on the record would get,
     * so a role the kind of record does not offer is refused now rather than reaching nobody
     * forever (A4). An invoice's Sales POC is the invoice's own field, not a seat in the customer's
     * book.
     */
    @Test
    void aRuleCannotAssignARoleTheKindOfRecordDoesNotOffer() throws Exception {
        ResultActions refused = postRule(admin, new AutomationDtos.CreateRuleRequest(
                "Wrong seat", null, null, "INVOICE", "UPDATED", List.of(), "CREATE_TASK",
                taskSpec("Chase", roleToken("SALES_POC", "CUSTOMER"))))
                .andExpect(status().isBadRequest());

        assertThat(messageOf(refused)).isEqualTo("Sales POC (customer) is not a role on invoices");
    }

    /** A task due a thousand years out is a typed extra digit, not an intention. */
    @Test
    void aDueDateFurtherOutThanTenYearsIsRefused() throws Exception {
        ResultActions refused = postRule(admin, new AutomationDtos.CreateRuleRequest(
                "Far too far", null, null, "INVOICE", "UPDATED", List.of(), "CREATE_TASK",
                new AutomationDtos.ActionSpec("Chase", null, 3651, null, null,
                        List.of(userToken(collections)), null)))
                .andExpect(status().isBadRequest());

        assertThat(messageOf(refused)).isEqualTo("dueInDays must be between 0 and 3650");
    }

    /** An unknown WHEN or THEN is a 400 that lists what there is, rather than a rule that never fires. */
    @Test
    void anUnknownTriggerOrActionIsRefusedAndSaysWhatThereIs() throws Exception {
        ResultActions badTrigger = postRule(admin, new AutomationDtos.CreateRuleRequest(
                "When what", null, null, "INVOICE", "WHENEVER", List.of(), "CREATE_TASK",
                taskSpec("Chase", userToken(collections)))).andExpect(status().isBadRequest());
        ResultActions badAction = postRule(admin, new AutomationDtos.CreateRuleRequest(
                "Then what", null, null, "INVOICE", "UPDATED", List.of(), "SET_FIRE_TO_IT",
                taskSpec("Chase", userToken(collections)))).andExpect(status().isBadRequest());

        assertThat(messageOf(badTrigger)).startsWith("trigger must be one of");
        assertThat(messageOf(badAction)).startsWith("action must be one of");
    }

    /**
     * The audit trail of everything a rule makes names the rule, so two rules of one name would
     * make every one of those entries ambiguous.
     */
    @Test
    void twoRulesCannotShareAName() throws Exception {
        postRule(admin, chaseBigInvoices(List.of("total:gt:500"))).andExpect(status().isOk());

        ResultActions refused = postRule(admin, chaseBigInvoices(List.of("total:gt:900")))
                .andExpect(status().isBadRequest());

        assertThat(messageOf(refused)).isEqualTo("Another rule is already called 'Chase big invoices'");
        assertThat(automationRuleRepository.findAll()).hasSize(1);
    }

    // ---- editing and removing ------------------------------------------------------

    /**
     * An update states the whole rule, as the form does, and may move it to another kind of record
     * — with its WHERE checked against the new kind, not the old one.
     */
    @Test
    void anUpdateStatesTheWholeRuleAndMayMoveItToAnotherKindOfRecord() throws Exception {
        AutomationRule saved = rule("Chase big invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of("total:gt:500"), ActionType.CREATE_TASK,
                taskSpec("Chase this invoice", roleToken("COLLECTION_POC", "CUSTOMER")));

        JsonNode moved = objectMapper.readTree(mockMvc.perform(
                        put("/api/automation/rules/" + saved.getId()).with(as(admin))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(new AutomationDtos.UpdateRuleRequest(
                                        "Chase big payments", "Moved to payments", false, "PAYMENT",
                                        "CREATED", List.of("amount:gt:500"), "CREATE_TASK",
                                        taskSpec("Check this payment", userToken(collections))))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(moved.get("entityType").asText()).isEqualTo("PAYMENT");
        assertThat(moved.get("trigger").asText()).isEqualTo("CREATED");
        assertThat(moved.get("enabled").asBoolean()).isFalse();
        assertThat(moved.get("filters").get(0).asText()).isEqualTo("amount:gt:500");
    }

    /** Moved to another kind, the old kind's columns no longer mean anything and the save says so. */
    @Test
    void anUpdateThatMovesARuleKeepsItsFiltersHonestAboutTheNewKind() throws Exception {
        AutomationRule saved = rule("Chase big invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of("total:gt:500"), ActionType.CREATE_TASK,
                taskSpec("Chase this invoice", userToken(collections)));

        ResultActions refused = mockMvc.perform(put("/api/automation/rules/" + saved.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AutomationDtos.UpdateRuleRequest(
                                "Chase big invoices", null, true, "CUSTOMER", "UPDATED",
                                List.of("total:gt:500"), "CREATE_TASK",
                                taskSpec("Chase this invoice", userToken(collections))))))
                .andExpect(status().isBadRequest());

        assertThat(messageOf(refused)).isEqualTo("Unknown column: total");
        // Refused whole: the rule still watches invoices.
        assertThat(automationRuleRepository.findById(saved.getId()).orElseThrow().getEntityType())
                .isEqualTo(AutomationEntityType.INVOICE);
    }

    /**
     * Removing a rule leaves its outbox rows where they are: the settled ones are the record of
     * what it did, which is exactly what somebody deleting a misfiring rule wants to read.
     */
    @Test
    void deletingARuleLeavesTheRecordOfWhatItDid() throws Exception {
        AutomationRule saved = rule("Chase big invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of(), ActionType.CREATE_TASK,
                taskSpec("Chase this invoice", userToken(collections)));
        outbox.enqueueForRule(saved, List.of(invoice("600.00", 1).getId()), MORNING);
        assertThat(eventsOf(saved)).hasSize(1);

        mockMvc.perform(delete("/api/automation/rules/" + saved.getId()).with(as(admin)))
                .andExpect(status().isOk());

        assertThat(automationRuleRepository.findById(saved.getId())).isEmpty();
        assertThat(eventsOf(saved)).hasSize(1);
    }

    // ---- the list and the runs list ------------------------------------------------

    /** The rules screen reads through the same table machinery every other list uses. */
    @Test
    void theRulesListFiltersOnTheColumnsTheScreenShows() throws Exception {
        rule("Chase big invoices", AutomationEntityType.INVOICE, TriggerKind.UPDATED,
                List.of("total:gt:500"), ActionType.CREATE_TASK,
                taskSpec("Chase", userToken(collections)));
        rule("Greet new customers", AutomationEntityType.CUSTOMER, TriggerKind.CREATED,
                List.of(), ActionType.CREATE_TASK, taskSpec("Say hello", userToken(collections)));

        JsonNode page = objectMapper.readTree(mockMvc.perform(get("/api/automation/rules")
                        .param("filter", "entityType:eq:INVOICE").with(as(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(page.get("totalElements").asInt()).isEqualTo(1);
        assertThat(page.at("/content/0/name").asText()).isEqualTo("Chase big invoices");
    }

    /** A rule nobody has run yet has an empty runs list rather than a 404. */
    @Test
    void aRuleThatHasNeverRunHasAnEmptyRunsList() throws Exception {
        AutomationRule saved = rule("Chase big invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of(), ActionType.CREATE_TASK,
                taskSpec("Chase", userToken(collections)));

        JsonNode runs = objectMapper.readTree(mockMvc.perform(
                        get("/api/automation/rules/" + saved.getId() + "/runs").with(as(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(runs).isEmpty();
    }

    @Test
    void aRuleThatIsNotThereIsANotFoundAndNotAnEmptyOne() throws Exception {
        mockMvc.perform(get("/api/automation/rules/999999").with(as(admin)))
                .andExpect(status().isNotFound());
    }

    // ---- who may write one ---------------------------------------------------------

    /** Reading the rules is one privilege; making the app act for everybody is another. */
    private User rulesReader() {
        Role role = roleRepository.findByName("AUTOMATION_READER").orElseGet(() -> roleRepository.save(
                Role.builder()
                        .name("AUTOMATION_READER")
                        .description("Reads the automation rules and what they did")
                        .privileges(Stream.of(Privileges.AUTOMATION_VIEW, Privileges.CUSTOMER_VIEW,
                                        Privileges.INVOICE_VIEW, Privileges.SCOPE_OVERRIDE)
                                .map(n -> privilegeRepository.findByName(n).orElseThrow())
                                .collect(Collectors.toCollection(HashSet::new)))
                        .build()));
        return user("ruby.reader", role.getName());
    }

    @Test
    void aReaderMayLookAtTheRulesButNotWriteOne() throws Exception {
        User reader = rulesReader();
        AutomationRule saved = rule("Chase big invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of(), ActionType.CREATE_TASK,
                taskSpec("Chase", userToken(collections)));

        mockMvc.perform(get("/api/automation/rules").with(as(reader))).andExpect(status().isOk());
        mockMvc.perform(get("/api/automation/rules/" + saved.getId() + "/runs").with(as(reader)))
                .andExpect(status().isOk());
        postRule(reader, chaseBigInvoices(List.of())).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/automation/rules/" + saved.getId()).with(as(reader)))
                .andExpect(status().isForbidden());
        assertThat(automationRuleRepository.findById(saved.getId())).isPresent();
    }

    /**
     * Run now makes the rule act, so it is held to the same privilege as writing one: somebody
     * given a screen to look at must not be able to raise a hundred tasks from it.
     */
    @Test
    void aReaderMayNotPressRunNowEither() throws Exception {
        User reader = rulesReader();
        AutomationRule saved = rule("Chase big invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of(), ActionType.CREATE_TASK,
                taskSpec("Chase", userToken(collections)));
        invoice("600.00", 1);

        mockMvc.perform(post("/api/automation/rules/" + saved.getId() + "/run").with(as(reader)))
                .andExpect(status().isForbidden());

        assertThat(eventsOf(saved)).isEmpty();
        assertThat(taskRepository.findAll()).isEmpty();
    }

    /** A customer login holds neither privilege, so the rules do not exist as far as it is concerned. */
    @Test
    void aCustomerLoginSeesNothingOfTheRulesAtAll() throws Exception {
        User acmeLogin = customerUser("acme.login", acme.getId());
        AutomationRule saved = rule("Chase big invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of(), ActionType.CREATE_TASK,
                taskSpec("Chase", userToken(collections)));

        mockMvc.perform(get("/api/automation/rules").with(as(acmeLogin))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/automation/rules/" + saved.getId()).with(as(acmeLogin)))
                .andExpect(status().isForbidden());
        postRule(acmeLogin, chaseBigInvoices(List.of())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/automation/rules/" + saved.getId() + "/run").with(as(acmeLogin)))
                .andExpect(status().isForbidden());
    }

    /** A rule's assignees may not be a customer login: the work is ours, however it was raised. */
    @Test
    void aRuleCannotAssignWorkToACustomerLogin() throws Exception {
        User acmeLogin = customerUser("acme.login", acme.getId());

        ResultActions refused = postRule(admin, new AutomationDtos.CreateRuleRequest(
                "Chase the customer", null, null, "INVOICE", "UPDATED", List.of(), "CREATE_TASK",
                taskSpec("Chase", new EmailDtos.EmailToken("USER", acmeLogin.getId(), null, null))))
                .andExpect(status().isBadRequest());

        assertThat(messageOf(refused)).isEqualTo("acme.login is not an active internal user");
    }

    /** The promise notes have to fit the column the run will put them in, and that is checked here. */
    @Test
    void textLongerThanTheColumnThatWillHoldItIsRefusedWhileTheAuthorIsLooking() throws Exception {
        ResultActions refused = postRule(admin, new AutomationDtos.CreateRuleRequest(
                "Very wordy promise", null, null, "INVOICE", "UPDATED", List.of(), "CREATE_PROMISE",
                new AutomationDtos.ActionSpec(null, "x".repeat(1001), null, new BigDecimal("100"),
                        null, List.of(), null)))
                .andExpect(status().isBadRequest());

        assertThat(messageOf(refused)).isEqualTo("The promise notes must be at most 1000 characters");
    }
}
