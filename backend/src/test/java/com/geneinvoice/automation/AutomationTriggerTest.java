package com.geneinvoice.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.assignee.Assignee;
import com.geneinvoice.assignee.AssigneeKind;
import com.geneinvoice.assignee.AssigneeOwnerType;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.email.RoleLevel;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.task.Task;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The engine from one end to the other: somebody saves a record, and the thing the rule said would
 * happen happens (R1). These are the only tests here that wait, because the nudge after a user's
 * save is the one hand-off that goes to the background thread — which is the whole point of it.
 */
class AutomationTriggerTest extends AutomationTestBase {

    @Autowired CustomerService customerService;
    @Autowired PaymentService paymentService;

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    /** The rule of the PRD: when an invoice is updated and its total is over 500, chase it. */
    private AutomationRule chaseBigInvoices() {
        return rule("Chase big invoices", AutomationEntityType.INVOICE, TriggerKind.UPDATED,
                List.of("total:gt:500"), ActionType.CREATE_TASK,
                new AutomationDtos.ActionSpec("Chase this invoice", "It is over the limit", 3,
                        null, null, List.of(roleToken("COLLECTION_POC", "CUSTOMER")), null));
    }

    // ---- the heart of it ------------------------------------------------------------

    /**
     * The whole PRD in one test: a rule written in the morning, an invoice edited in the afternoon,
     * and a task waiting for the customer's Collection POC — with nobody having asked for it.
     */
    @Test
    void whenAnInvoiceIsUpdatedAndItsTotalIsOverTheRulesFigureATaskAppearsForTheCustomersCollectionPoc()
            throws Exception {
        Invoice big = invoice("600.00", 1);
        AutomationRule chase = chaseBigInvoices();

        edit(big, "customer says they will pay on Friday");

        waitUntil(() -> !tasksOn(big).isEmpty());
        Task raised = tasksOn(big).get(0);
        assertThat(raised.getTitle()).isEqualTo("Chase this invoice");
        assertThat(raised.getNotes()).isEqualTo("It is over the limit");
        assertThat(raised.getCustomerId()).isEqualTo(acme.getId());
        assertThat(raised.getStatus()).isEqualTo(TaskStatus.OPEN);
        // The offset is counted from the run, not from the record: three days from now.
        assertThat(raised.getDueDate()).isEqualTo(TODAY.plusDays(3));
        // The assignee is kept as the role it was picked as, so it reaches whoever holds the seat
        // on the day somebody opens the task rather than whoever held it when the rule fired (A2).
        List<Assignee> on = assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.TASK, raised.getId());
        assertThat(on).singleElement().satisfies(a -> {
            assertThat(a.getKind()).isEqualTo(AssigneeKind.ROLE);
            assertThat(a.getRole()).isEqualTo(EmailRole.COLLECTION_POC);
            assertThat(a.getLevel()).isEqualTo(RoleLevel.CUSTOMER);
            assertThat(a.getCustomerId()).isEqualTo(acme.getId());
        });

        waitUntil(this::outboxSettled);
        AutomationEvent run = eventsOf(chase).get(0);
        assertThat(run.getStatus()).isEqualTo(AutomationEventStatus.DONE);
        assertThat(run.getLastError()).startsWith("Raised task #" + raised.getId());
        // The rule counts what it actually did, not how many records it was asked about.
        AutomationRule after = automationRuleRepository.findById(chase.getId()).orElseThrow();
        assertThat(after.getRunCount()).isEqualTo(1L);
        assertThat(after.getLastRunAt()).isNotNull();
    }

    /**
     * The other half of the same rule: an invoice under the figure is asked about and answers no.
     * That is a skip with a reason on the runs list, not an error — most rows a rule is ever asked
     * about are this one.
     */
    @Test
    void anInvoiceTheRulesWhereDoesNotMatchRaisesNothingAndTheRunSaysWhy() throws Exception {
        Invoice small = invoice("100.00", 1);
        AutomationRule chase = chaseBigInvoices();

        edit(small, "a note that changes nothing else");

        waitUntil(this::outboxSettled);
        assertThat(tasksOn(small)).isEmpty();
        AutomationEvent run = eventsOf(chase).get(0);
        assertThat(run.getStatus()).isEqualTo(AutomationEventStatus.SKIPPED);
        assertThat(run.getLastError()).isEqualTo("The filters did not match this invoice");
    }

    /**
     * Switching a rule off is how somebody stops it without losing what it says, so it has to stop
     * it at the fan-out: no rule row is even written, and the fan-out says so rather than failing.
     */
    @Test
    void aDisabledRuleIsLeftAloneByTheFanOutAndRaisesNothing() throws Exception {
        Invoice big = invoice("600.00", 1);
        actAs(admin);
        AutomationDtos.RuleDto off = automationService.create(new AutomationDtos.CreateRuleRequest(
                "Chase big invoices", null, false, "INVOICE", "UPDATED", List.of("total:gt:500"),
                "CREATE_TASK", taskSpec("Chase this invoice", userToken(collections))));

        edit(big, "still nothing should happen");

        waitUntil(this::outboxSettled);
        assertThat(tasksOn(big)).isEmpty();
        assertThat(automationEventRepository.findAll().stream()
                .filter(e -> off.id().equals(e.getRuleId()))).isEmpty();
        assertThat(fanOutEvents()).filteredOn(e -> e.getTrigger() == TriggerKind.UPDATED)
                .singleElement().satisfies(e -> {
                    assertThat(e.getStatus()).isEqualTo(AutomationEventStatus.DONE);
                    assertThat(e.getLastError()).isEqualTo("No rule watches invoices for this");
                });
    }

    /**
     * Somebody correcting an invoice three times in an afternoon wants one task out of it, not
     * three. The UTC day is part of the rule row's key, so the second and third edits write a
     * fan-out row each and neither of them can queue the rule a second time (R4).
     */
    @Test
    void severalUpdatesToOneInvoiceInOneUtcDayCollapseToOneTask() throws Exception {
        Invoice big = invoice("600.00", 1);
        AutomationRule chase = chaseBigInvoices();

        edit(big, "chased once");
        edit(big, "chased twice");
        edit(big, "chased three times");

        waitUntil(this::outboxSettled);
        assertThat(tasksOn(big)).hasSize(1);
        assertThat(eventsOf(chase)).hasSize(1);
        // Every save still wrote its own outbox row. The save never decides anything; the
        // collapsing happens a stage later, on the rule row's key.
        assertThat(fanOutEvents()).filteredOn(e -> e.getTrigger() == TriggerKind.UPDATED).hasSize(3);
        assertThat(automationRuleRepository.findById(chase.getId()).orElseThrow().getRunCount())
                .isEqualTo(1L);
    }

    /** A rule may watch creation instead, and then it fires as the record first appears. */
    @Test
    void aRuleThatWatchesCreationFiresAsTheInvoiceIsCreated() throws Exception {
        rule("Greet every invoice", AutomationEntityType.INVOICE, TriggerKind.CREATED,
                List.of(), ActionType.CREATE_TASK,
                taskSpec("Check this new invoice", userToken(collections)));

        Invoice fresh = invoice("100.00", 1);

        waitUntil(() -> !tasksOn(fresh).isEmpty());
        assertThat(tasksOn(fresh)).singleElement().satisfies(t ->
                assertThat(t.getTitle()).isEqualTo("Check this new invoice"));
    }

    /**
     * The third kind of record, through its own save: a payment is recorded and the rule raises
     * work on the payment itself, for the Collection POC the payment names.
     */
    @Test
    void aRuleOnPaymentsFiresWhenAPaymentIsRecordedAndRaisesWorkOnThePayment() throws Exception {
        Invoice inv = invoice("600.00", 1);
        rule("Check every payment", AutomationEntityType.PAYMENT, TriggerKind.CREATED,
                List.of("amount:gte:100"), ActionType.CREATE_TASK,
                taskSpec("Check this payment", roleToken("COLLECTION_POC", "RECORD")));

        actAs(admin);
        Payment paid = paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("600.00"), "Cash", null, List.of(inv.getId()),
                collections.getId(), null));

        waitUntil(() -> !taskRepository.findByEntityTypeAndEntityIdOrderByIdAsc(
                TaskEntityType.PAYMENT, paid.getId()).isEmpty());
        assertThat(taskRepository.findByEntityTypeAndEntityIdOrderByIdAsc(
                TaskEntityType.PAYMENT, paid.getId()))
                .singleElement().satisfies(t -> {
                    assertThat(t.getTitle()).isEqualTo("Check this payment");
                    assertThat(t.getCustomerId()).isEqualTo(acme.getId());
                });
    }

    // ---- what a payment changes besides the payment ---------------------------------

    /**
     * The most important transition in a collections app, and the engine could not see it. A
     * payment's invoice rows are written by PaymentService — setPaidAmount, recomputeStatus, save —
     * and not through InvoiceService, which is where every other invoice event comes from, so an
     * invoice going FULLY_PAID published nothing at all while {@code status} sat there in the rule
     * editor's list of columns to filter on (D-73).
     */
    @Test
    void payingAnInvoiceOffFiresARuleWatchingForFullyPaidInvoices() throws Exception {
        Invoice inv = invoice("600.00", 1);
        rule("Thank them once it is paid", AutomationEntityType.INVOICE, TriggerKind.UPDATED,
                List.of("status:eq:FULLY_PAID"), ActionType.CREATE_TASK,
                taskSpec("Thank the customer", userToken(collections)));

        pay(inv, "600.00");

        waitUntil(() -> !tasksOn(inv).isEmpty());
        assertThat(tasksOn(inv)).singleElement().satisfies(t -> {
            assertThat(t.getTitle()).isEqualTo("Thank the customer");
            assertThat(t.getCustomerId()).isEqualTo(acme.getId());
        });
    }

    /**
     * And the other way round: a void takes the money back off the invoice, and that is as much a
     * change to it as the payment was. The rule here was asked on the payment too and answered no,
     * which is the other half of what makes this work — a run that matched nothing may not hold the
     * invoice's slot for the rest of the day (D-72), or the void could not be acted on until
     * tomorrow.
     */
    @Test
    void voidingThatPaymentFiresTheInvoiceAgainAsItFallsBackToUnpaid() throws Exception {
        Invoice inv = invoice("600.00", 1);
        rule("Chase it again if it comes back", AutomationEntityType.INVOICE, TriggerKind.UPDATED,
                List.of("status:eq:UNPAID"), ActionType.CREATE_TASK,
                taskSpec("It is unpaid again", userToken(collections)));
        Payment paid = pay(inv, "600.00");
        waitUntil(this::outboxSettled);
        // Fully paid is not what this rule watches for, so the payment itself raised nothing.
        assertThat(tasksOn(inv)).isEmpty();

        actAs(admin);
        paymentService.voidPayment(paid.getId());

        waitUntil(() -> !tasksOn(inv).isEmpty());
        assertThat(tasksOn(inv)).singleElement().satisfies(t ->
                assertThat(t.getTitle()).isEqualTo("It is unpaid again"));
    }

    /**
     * The customer moves too. Money over and above what is owed becomes credit on the customer's
     * own row, and credit balance is a column a rule may watch, so the customer is published as
     * well as the invoice (D-73).
     */
    @Test
    void aPaymentThatLeavesCreditOnTheCustomerFiresACustomerRule() throws Exception {
        Invoice inv = invoice("600.00", 1);
        rule("Look at customers in credit", AutomationEntityType.CUSTOMER, TriggerKind.UPDATED,
                List.of("creditBalance:gt:0"), ActionType.CREATE_TASK,
                taskSpec("They have paid more than they owe", userToken(collections)));

        pay(inv, "1000.00");

        waitUntil(() -> !tasksOnCustomer().isEmpty());
        assertThat(tasksOnCustomer()).singleElement().satisfies(t -> {
            assertThat(t.getTitle()).isEqualTo("They have paid more than they owe");
            assertThat(t.getCustomerId()).isEqualTo(acme.getId());
        });
        assertThat(customerRepository.findById(acme.getId()).orElseThrow().getCreditBalance())
                .isEqualByComparingTo(new BigDecimal("400.00"));
    }

    /**
     * And a payment that changed no invoice tells no invoice. Nothing was owed, so the whole amount
     * became credit: the customer is published and no invoice is, because a record that did not
     * move must not spend its one slot for the day on having stayed still (D-73).
     */
    @Test
    void aPaymentThatLandedOnNoInvoiceTellsTheCustomerAndNoInvoice() throws Exception {
        Invoice paidOff = invoice("600.00", 1);
        pay(paidOff, "600.00");
        waitUntil(this::outboxSettled);
        automationEventRepository.deleteAll();

        pay(null, "250.00");

        waitUntil(this::outboxSettled);
        assertThat(fanOutEvents()).filteredOn(e -> e.getEntityType() == AutomationEntityType.INVOICE)
                .isEmpty();
        assertThat(fanOutEvents()).filteredOn(e -> e.getEntityType() == AutomationEntityType.CUSTOMER)
                .singleElement().satisfies(e -> {
                    assertThat(e.getEntityId()).isEqualTo(acme.getId());
                    assertThat(e.getTrigger()).isEqualTo(TriggerKind.UPDATED);
                });
    }

    /** A payment for Acme, against one invoice or against whatever is outstanding. */
    private Payment pay(Invoice inv, String amount) {
        actAs(admin);
        return paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal(amount), "Cash", null,
                inv == null ? List.of() : List.of(inv.getId()), collections.getId(), null));
    }

    private List<Task> tasksOnCustomer() {
        return taskRepository.findByEntityTypeAndEntityIdOrderByIdAsc(
                TaskEntityType.CUSTOMER, acme.getId());
    }

    /** A rule watching one kind of record is not woken by another kind being saved. */
    @Test
    void aRuleOnInvoicesIsNotWokenByACustomerBeingSaved() throws Exception {
        rule("Chase every invoice edit", AutomationEntityType.INVOICE, TriggerKind.UPDATED,
                List.of(), ActionType.CREATE_TASK,
                taskSpec("Check this invoice", userToken(collections)));

        actAs(admin);
        customerService.update(acme.getId(), new CustomerDtos.CustomerUpdateRequest(
                "Acme Limited", null, "ap@acme.test", null, null, null));

        waitUntil(this::outboxSettled);
        assertThat(taskRepository.findAll()).isEmpty();
        assertThat(fanOutEvents()).singleElement().satisfies(e -> {
            assertThat(e.getEntityType()).isEqualTo(AutomationEntityType.CUSTOMER);
            assertThat(e.getLastError()).isEqualTo("No rule watches customers for this");
        });
    }

    // ---- running one by hand --------------------------------------------------------

    /**
     * Run now goes down the same path a trigger does — it writes outbox rows and hands them over —
     * so it reports how many pieces of work it queued rather than pretending to do the work itself.
     */
    @Test
    void runNowFiresTheRuleOverEverythingItMatchesAndReportsHowManyItQueued() throws Exception {
        Invoice big = invoice("600.00", 1);
        Invoice alsoBig = invoice("900.00", 1);
        Invoice small = invoice("100.00", 1);
        AutomationRule chase = chaseBigInvoices();

        JsonNode result = objectMapper.readTree(mockMvc.perform(
                        post("/api/automation/rules/" + chase.getId() + "/run").with(as(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(result.get("matched").asInt()).isEqualTo(2);
        assertThat(result.get("queued").asInt()).isEqualTo(2);
        assertThat(result.get("cap").asInt()).isEqualTo(AutomationScheduler.RUN_LIMIT);
        assertThat(result.get("capped").asBoolean()).isFalse();
        assertThat(tasksOn(big)).hasSize(1);
        assertThat(tasksOn(alsoBig)).hasSize(1);
        assertThat(tasksOn(small)).isEmpty();
    }

    /**
     * Pressing it twice in one day is the same piece of work asked for twice, and does nothing the
     * first press did not already do (R4).
     */
    @Test
    void pressingRunNowAgainTheSameDayQueuesNothingNew() throws Exception {
        Invoice big = invoice("600.00", 1);
        AutomationRule chase = chaseBigInvoices();
        assertThat(automationService.runNow(chase.getId())).isEqualTo(1);

        JsonNode again = objectMapper.readTree(mockMvc.perform(
                        post("/api/automation/rules/" + chase.getId() + "/run").with(as(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(again.get("matched").asInt()).isEqualTo(1);
        assertThat(again.get("queued").asInt()).isZero();
        assertThat(tasksOn(big)).hasSize(1);
    }

    /** A rule that is switched off cannot be run by hand either; that is what switched off means. */
    @Test
    void runNowRefusesADisabledRuleAndSaysToSwitchItOn() throws Exception {
        invoice("600.00", 1);
        actAs(admin);
        AutomationDtos.RuleDto off = automationService.create(new AutomationDtos.CreateRuleRequest(
                "Chase big invoices", null, false, "INVOICE", "UPDATED", List.of("total:gt:500"),
                "CREATE_TASK", taskSpec("Chase this invoice", userToken(collections))));

        JsonNode error = objectMapper.readTree(mockMvc.perform(
                        post("/api/automation/rules/" + off.id() + "/run").with(as(admin)))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString());

        assertThat(error.get("message").asText())
                .isEqualTo("This rule is switched off; switch it on before running it");
        assertThat(taskRepository.findAll()).isEmpty();
    }

    // ---- the clock ------------------------------------------------------------------

    /**
     * A daily rule has no record behind it until the run finds one, so the run walks everything the
     * WHERE matches and queues a row each — and the actions happen exactly as a trigger's would.
     */
    @Test
    void theDailyRunQueuesOneRowPerMatchingRecordAndDoesTheWork() {
        Invoice big = invoice("600.00", 1);
        Invoice alsoBig = invoice("900.00", 1);
        Invoice small = invoice("100.00", 1);
        AutomationRule sweepUp = rule("Chase everything big every morning", AutomationEntityType.INVOICE,
                TriggerKind.DAILY, List.of("total:gt:500"), ActionType.CREATE_TASK,
                taskSpec("Chase this invoice", roleToken("COLLECTION_POC", "CUSTOMER")));

        int queued = scheduler.runScheduled(TriggerKind.DAILY, MORNING);

        assertThat(queued).isEqualTo(2);
        assertThat(tasksOn(big)).hasSize(1);
        assertThat(tasksOn(alsoBig)).hasSize(1);
        assertThat(tasksOn(small)).isEmpty();
        assertThat(eventsOf(sweepUp)).hasSize(2)
                .allSatisfy(e -> assertThat(e.getStatus()).isEqualTo(AutomationEventStatus.DONE));
    }

    /**
     * The same morning's run happening twice — two instances, or a restart mid-run — is the same
     * day's work, and the day is part of the key. Tomorrow is not.
     */
    @Test
    void aDailyRunThatFiresTwiceInOneUtcDayIsOneMorningsWorkAndTomorrowIsAnother() {
        Invoice big = invoice("600.00", 1);
        rule("Chase everything big every morning", AutomationEntityType.INVOICE, TriggerKind.DAILY,
                List.of("total:gt:500"), ActionType.CREATE_TASK,
                taskSpec("Chase this invoice", userToken(collections)));

        assertThat(scheduler.runScheduled(TriggerKind.DAILY, MORNING)).isEqualTo(1);
        assertThat(scheduler.runScheduled(TriggerKind.DAILY, MORNING.plus(Duration.ofHours(2)))).isZero();
        assertThat(scheduler.runScheduled(TriggerKind.DAILY, MORNING.plus(Duration.ofDays(1)))).isEqualTo(1);

        assertThat(tasksOn(big)).hasSize(2);
    }

    /** A weekly rule collapses to one firing per ISO week, for exactly the same reason. */
    @Test
    void aWeeklyRunCollapsesToOneFiringPerIsoWeek() {
        Invoice big = invoice("600.00", 1);
        rule("Chase everything big every Monday", AutomationEntityType.INVOICE, TriggerKind.WEEKLY,
                List.of("total:gt:500"), ActionType.CREATE_TASK,
                taskSpec("Chase this invoice", userToken(collections)));

        // MORNING is a Monday; the day after is the same ISO week, the Monday after is not.
        assertThat(scheduler.runScheduled(TriggerKind.WEEKLY, MORNING)).isEqualTo(1);
        assertThat(scheduler.runScheduled(TriggerKind.WEEKLY, MORNING.plus(Duration.ofDays(1)))).isZero();
        assertThat(scheduler.runScheduled(TriggerKind.WEEKLY, MORNING.plus(Duration.ofDays(7)))).isEqualTo(1);

        assertThat(tasksOn(big)).hasSize(2);
    }

    /** A daily run walks daily rules; a rule waiting for an edit is not swept up by the clock. */
    @Test
    void aScheduledRunLeavesRulesOfAnotherTriggerAlone() {
        invoice("600.00", 1);
        AutomationRule onEdit = chaseBigInvoices();

        assertThat(scheduler.runScheduled(TriggerKind.DAILY, MORNING)).isZero();

        assertThat(eventsOf(onEdit)).isEmpty();
        assertThat(taskRepository.findAll()).isEmpty();
    }

    /**
     * One rule that cannot be read — a filter whose column has gone since it was written — must not
     * stop the rest of the morning's rules, so each is caught on its own.
     */
    @Test
    void oneUnreadableRuleDoesNotStopTheRestOfTheMorningsRules() {
        Invoice big = invoice("600.00", 1);
        // Written straight to the table: this is a rule whose column the schema no longer has, which
        // the authoring check would refuse today.
        automationRuleRepository.save(AutomationRule.builder()
                .name("Written against a column that has gone")
                .enabled(true)
                .entityType(AutomationEntityType.INVOICE)
                .trigger(TriggerKind.DAILY)
                .filtersJson("[\"totl:gt:500\"]")
                .action(ActionType.CREATE_TASK)
                .actionJson("{\"title\":\"Chase this invoice\"}")
                .createdByUserId(admin.getId())
                .runCount(0L)
                .build());
        AutomationRule good = rule("Chase everything big every morning", AutomationEntityType.INVOICE,
                TriggerKind.DAILY, List.of("total:gt:500"), ActionType.CREATE_TASK,
                taskSpec("Chase this invoice", userToken(collections)));

        int queued = scheduler.runScheduled(TriggerKind.DAILY, MORNING);

        assertThat(queued).isEqualTo(1);
        assertThat(eventsOf(good)).hasSize(1);
        assertThat(tasksOn(big)).hasSize(1);
    }
}
