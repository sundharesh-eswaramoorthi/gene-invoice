package com.geneinvoice.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE THIRD PRODUCER: somebody pressing the button, and the two operational levers beside it —
 * Retry, and the reaper.
 *
 * <p>The default is a DRY RUN, which is the habit {@code POST /api/promises/recompute} already
 * has: {@code apply} defaults to false, the dry run is the same query the real fan-out pages
 * through, and nothing at all is written. What the dry run shows and what an applied run does
 * cannot disagree, because there is one query and one consumer behind both (A5).
 */
@Import(InterleavingActions.class)
class AutomationRunNowTest extends IntegrationTestBase {

    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired InvoiceService invoiceService;
    @Autowired AutomationRuleService ruleService;
    @Autowired AutomationDispatcher dispatcher;
    @Autowired AutomationRetention retention;

    User admin;
    User ana;
    User reader;
    Customer home;
    Product widget;

    @BeforeEach
    void setUp() {
        InterleavingActions.Interleaving.reset();
        admin = userRepository.findByUsername("admin").orElseThrow();
        ana = user("ana.author", authorRole().getName());
        reader = user("rita.reader", readerRole().getName());
        widget = product("Widget", "1000.00");
        home = customer("Home Ltd", "ap@home.test");
    }

    @AfterEach
    void clearHooks() {
        InterleavingActions.Interleaving.reset();
    }

    // ---- the dry run ------------------------------------------------------------------------

    @Test
    void aDryRunShowsWhatWouldHappenAndWritesNothing() throws Exception {
        AutomationRule rule = rule("Chase");
        Invoice invoice = invoiceFor(home);

        mockMvc.perform(post("/api/automation/rules/" + rule.getId() + "/run").with(as(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.sample.length()").value(1))
                .andExpect(jsonPath("$.sample[0].id").value(invoice.getId()))
                .andExpect(jsonPath("$.sample[0].label").value(invoice.getInvoiceNumber()))
                // Rendered against the real record, through the same placeholder catalogue the
                // action itself would use, so the preview is the thing and not a description of it.
                .andExpect(jsonPath("$.sample[0].renderedTitle")
                        .value("Chase " + invoice.getInvoiceNumber()));

        // NOTHING. Not a run, not a step, not a task, not an email.
        assertThat(automationRunRepository.count()).isZero();
        assertThat(automationStepRepository.count()).isZero();
        assertThat(taskRepository.count()).isZero();
        assertThat(emailRepository.count()).isZero();
    }

    @Test
    void aDryRunCountsEveryRecordTheRuleWouldReachAndShowsOnlyTheOnesTheReaderMaySee() throws Exception {
        // AN ADMINISTRATOR'S rule: it names no branches, so its reach is every branch its author
        // may manage, which for a wildcard holder is all of them.
        actAs(admin);
        Long ruleId = ruleService.create(new AutomationDtos.SaveRuleRequest("Chase everywhere",
                null, SubjectType.INVOICE, TriggerKind.SCHEDULE_DAILY, 9, null,
                condition("balance:gt:0"),
                List.of(new ActionSpec.CreateTask("Chase {{Invoice.Number}}", null, List.of(), 3)),
                null, true, List.of())).id();
        SecurityContextHolder.clearContext();

        Invoice mine = invoiceFor(home);
        Customer north = customerRepository.save(
                Customer.builder().name("North Ltd").region(region("NORTH")).build());
        invoiceFor(north);

        // ANA MAY RUN IT AND WORKS IN HQ ONLY. The COUNT is a fact about the rule and is honest:
        // it really would touch both invoices. The SAMPLE is bounded by her own gate, because a
        // rendered {{Customer.Name}} is a read of that customer and a dry run must not become a
        // way to make one across a branch (A5, B1, AUTH-08).
        mockMvc.perform(post("/api/automation/rules/" + ruleId + "/run").with(as(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(2))
                .andExpect(jsonPath("$.sample.length()").value(1))
                .andExpect(jsonPath("$.sample[0].id").value(mine.getId()));

        // The administrator sees both, because both are hers to see.
        mockMvc.perform(post("/api/automation/rules/" + ruleId + "/run").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(2))
                .andExpect(jsonPath("$.sample.length()").value(2));
    }

    // ---- applying it ------------------------------------------------------------------------

    @Test
    void aDoubleClickedRunNowQueuesTheWorkOnce() throws Exception {
        AutomationRule rule = rule("Chase");
        Invoice invoice = invoiceFor(home);
        String body = json(new AutomationDtos.RunRequest("click-42", null));

        String first = mockMvc.perform(post("/api/automation/rules/" + rule.getId() + "/run")
                        .param("apply", "true").contentType(MediaType.APPLICATION_JSON)
                        .content(body).with(as(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.occasion").value("Mclick-42"))
                .andExpect(jsonPath("$.status").value("FANNING"))
                .andReturn().getResponse().getContentAsString();

        // THE SECOND CLICK OF THE SAME CLICK. The same requestId is the same occasion, and
        // uk_run_occasion is a constraint rather than a check-then-act, so it is answered with
        // the run the first click opened (A5).
        String second = mockMvc.perform(post("/api/automation/rules/" + rule.getId() + "/run")
                        .param("apply", "true").contentType(MediaType.APPLICATION_JSON)
                        .content(body).with(as(ana)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(second).get("id"))
                .isEqualTo(objectMapper.readTree(first).get("id"));
        assertThat(automationRunRepository.count()).isEqualTo(1);

        // And the sweeper drains it through the very same machinery the clock and the event path
        // use: one step, one task, no second anything.
        dispatcher.sweep(Instant.now().plusSeconds(120));
        assertThat(automationStepRepository.findAll()).singleElement().satisfies(step -> {
            assertThat(step.getSource()).isEqualTo(StepSource.MANUAL);
            assertThat(step.getStatus()).isEqualTo(StepStatus.DONE);
            assertThat(step.getSubjectId()).isEqualTo(invoice.getId());
        });
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    @Test
    void runningAsOfAPastDateIsRefusedWhenItWouldActuallyAct() throws Exception {
        AutomationRule rule = rule("Chase");
        invoiceFor(home);
        String yesterday = InvoiceDates.today().minusDays(1).toString();

        // Reading the past is a question somebody may ask. Creating real Tasks dated from a
        // replayed past is not, and the refusal says which of the two this was (A5, B3).
        mockMvc.perform(post("/api/automation/rules/" + rule.getId() + "/run")
                        .param("apply", "true").param("asOf", yesterday)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AutomationDtos.RunRequest("click-1", null)))
                        .with(as(ana)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(AutomationRuleService.PAST_APPLY));
        assertThat(automationRunRepository.count()).isZero();

        // The dry run of the same date is fine: it writes nothing, so there is nothing to date.
        mockMvc.perform(post("/api/automation/rules/" + rule.getId() + "/run")
                        .param("asOf", yesterday).with(as(ana)))
                .andExpect(status().isOk());

        // And a run with nothing to bounce the second click off is refused rather than accepted
        // without a guard (A5).
        mockMvc.perform(post("/api/automation/rules/" + rule.getId() + "/run")
                        .param("apply", "true").with(as(ana)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(AutomationRuleService.NEEDS_REQUEST_ID));
        assertThat(automationRunRepository.count()).isZero();
    }

    @Test
    void somebodyWhoMayViewAutomationButNotRunItIsRefused() throws Exception {
        AutomationRule rule = rule("Chase");
        invoiceFor(home);

        mockMvc.perform(post("/api/automation/rules/" + rule.getId() + "/run").with(as(reader)))
                .andExpect(status().isForbidden());

        // AND THE BULK DOOR TOO. That mapping is gated on AUTOMATION_MANAGE, and running a rule
        // is AUTOMATION_RUN: without the second check inside the arm, the bulk door would hand a
        // rule author the one thing the single-rule door refuses them (A5, AUTH-05).
        User editor = user("eddie.editor", editorRole().getName());
        mockMvc.perform(post("/api/automation/rules/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "RUN_NOW", "ids", List.of(rule.getId()))))
                        .with(as(editor)))
                .andExpect(status().isForbidden());
        assertThat(automationRunRepository.count()).isZero();

        // Somebody who holds both may, and it goes through the same run creation per id.
        mockMvc.perform(post("/api/automation/rules/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "RUN_NOW", "ids", List.of(rule.getId()))))
                        .with(as(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded.length()").value(1));
        assertThat(automationRunRepository.findAll()).singleElement()
                .satisfies(run -> assertThat(run.getSource()).isEqualTo(StepSource.MANUAL));
    }

    // ---- retry ------------------------------------------------------------------------------

    @Test
    void aPoisonedStepCanBeRetriedAndSucceedsTheSecondTime() throws Exception {
        eventRule("Chase");
        Invoice invoice = invoiceFor(home);
        dispatcher.fanOut(eventFor(invoice.getId()), Instant.now().plusSeconds(60));
        AutomationStep step = automationStepRepository.findAll().get(0);

        InterleavingActions.Interleaving.fail = new IllegalStateException("the database went away");
        Instant at = Instant.now().plusSeconds(60);
        for (int attempt = 0; attempt < AutomationDispatcher.MAX_ATTEMPTS; attempt++) {
            dispatcher.perform(step.getId(), at);
            at = at.plus(Duration.ofHours(2));
        }
        AutomationStep dead = reload(step);
        assertThat(dead.getStatus()).isEqualTo(StepStatus.POISONED);
        assertThat(dead.getAttempts()).isEqualTo(AutomationDispatcher.MAX_ATTEMPTS);
        assertThat(dead.getResult()).isEqualTo("the database went away");

        InterleavingActions.Interleaving.reset();
        mockMvc.perform(post("/api/automation/steps/" + step.getId() + "/retry").with(as(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attempts").value(0));

        AutomationStep queued = reload(step);
        assertThat(queued.getNextAttemptAt()).isNull();
        // The old error must not sit on a step that is waiting again: the sweeper writes a fresh
        // result, and a reader must not see yesterday's failure beside today's attempt (A5).
        assertThat(queued.getResult()).isNull();
        assertThat(queued.getFinishedAt()).isNull();

        dispatcher.sweep(at.plusSeconds(60));
        AutomationStep done = reload(step);
        assertThat(done.getStatus()).isEqualTo(StepStatus.DONE);
        assertThat(done.getProducedType()).isEqualTo(ProducedType.TASK);
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    @Test
    void aStepThatAlreadyDidItsWorkCannotBeRetriedIntoDoingItTwice() throws Exception {
        eventRule("Chase");
        Invoice invoice = invoiceFor(home);
        dispatcher.fanOut(eventFor(invoice.getId()), Instant.now().plusSeconds(60));
        AutomationStep step = automationStepRepository.findAll().get(0);
        dispatcher.perform(step.getId(), Instant.now().plusSeconds(60));
        assertThat(reload(step).getStatus()).isEqualTo(StepStatus.DONE);

        mockMvc.perform(post("/api/automation/steps/" + step.getId() + "/retry").with(as(ana)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("This step already did its work; running it again would do it twice"));
        assertThat(taskRepository.count()).isEqualTo(1);

        // A step about an account in a branch the reader cannot see is not there at all, which is
        // AUTH-08 and never a 403 (B1).
        Customer north = customerRepository.save(
                Customer.builder().name("North Ltd").region(region("NORTH")).build());
        AutomationStep elsewhere = reload(step);
        elsewhere.setCustomerId(north.getId());
        elsewhere.setStatus(StepStatus.SKIPPED);
        automationStepRepository.saveAndFlush(elsewhere);

        mockMvc.perform(post("/api/automation/steps/" + step.getId() + "/retry").with(as(ana)))
                .andExpect(status().isNotFound());
    }

    // ---- retention ---------------------------------------------------------------------------

    @Test
    void retentionRemovesDeliveredEventsAndKeepsEveryPoisonedStep() {
        AutomationRule rule = eventRule("Chase");
        Invoice invoice = invoiceFor(home);
        dispatcher.fanOut(eventFor(invoice.getId()), Instant.now().plusSeconds(60));

        AutomationEvent delivered = automationEventRepository.findAll().get(0);
        assertThat(delivered.getStatus()).isEqualTo(EventStatus.DONE);
        AutomationEvent gaveUp = automationEventRepository.save(AutomationEvent.builder()
                .subjectType(SubjectType.INVOICE).subjectId(invoice.getId())
                .change(Change.UPDATED).status(EventStatus.DONE)
                .lastError("nobody could ever fan this out").build());

        AutomationStep done = automationStepRepository.findAll().get(0);
        done.setStatus(StepStatus.DONE);
        done.setFinishedAt(Instant.now().minus(Duration.ofDays(400)));
        automationStepRepository.saveAndFlush(done);
        AutomationStep poisoned = automationStepRepository.save(AutomationStep.builder()
                .occasion("X1").ruleId(rule.getId()).ruleName(rule.getName()).ruleVersion(1)
                .actionIndex(0).actionKind(ActionKind.CREATE_TASK)
                .subjectType(SubjectType.INVOICE).subjectId(invoice.getId())
                .customerId(home.getId()).source(StepSource.EVENT)
                .status(StepStatus.POISONED).finishedAt(Instant.now().minus(Duration.ofDays(400)))
                .build());

        // A year and a day later. keep-events is P30D and keep-steps is P180D in application.yml.
        int gone = retention.reap(Instant.now().plus(Duration.ofDays(366)));

        assertThat(gone).isEqualTo(2);
        assertThat(automationEventRepository.findById(delivered.getId())).isEmpty();
        // A fan-out that GAVE UP is a dead letter by the same argument a poisoned step is: the row
        // is the only record that it ever happened (A5).
        assertThat(automationEventRepository.findById(gaveUp.getId())).isPresent();
        assertThat(automationStepRepository.findById(done.getId())).isEmpty();
        // NEVER. The step row IS the dead letter and the Retry button sits beside it.
        assertThat(automationStepRepository.findById(poisoned.getId())).isPresent();
    }

    @Test
    void theReaperRunsOnceADayHoweverOftenTheSweeperCallsIt() {
        Invoice invoice = invoiceFor(home);
        AutomationEvent event = automationEventRepository.findAll().get(0);
        event.setStatus(EventStatus.DONE);
        automationEventRepository.saveAndFlush(event);

        Instant far = Instant.now().plus(Duration.ofDays(366));
        retention.sweep(far);
        assertThat(automationEventRepository.count()).isZero();

        // A second delivered event an hour later is NOT reaped, because the reaper has already
        // been round today. The throttle is the whole reason a 60 s sweeper can carry it (A5).
        automationEventRepository.save(AutomationEvent.builder()
                .subjectType(SubjectType.INVOICE).subjectId(invoice.getId())
                .change(Change.UPDATED).status(EventStatus.DONE).build());
        automationEventRepository.findAll().forEach(e -> {
            e.setCreatedAt(Instant.now().minus(Duration.ofDays(90)));
            automationEventRepository.saveAndFlush(e);
        });
        retention.sweep(far.plus(Duration.ofHours(1)));
        assertThat(automationEventRepository.count()).isEqualTo(1);

        retention.sweep(far.plus(Duration.ofDays(1)).plusSeconds(1));
        assertThat(automationEventRepository.count()).isZero();
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private AutomationStep reload(AutomationStep step) {
        return automationStepRepository.findById(step.getId()).orElseThrow();
    }

    private Long eventFor(Long invoiceId) {
        return automationEventRepository.findAll().stream()
                .filter(e -> e.getSubjectType() == SubjectType.INVOICE
                        && e.getSubjectId().equals(invoiceId))
                .map(AutomationEvent::getId).findFirst().orElseThrow();
    }

    /**
     * A SCHEDULED rule, which is what "run now" is mostly for: the clock has not come round yet
     * and somebody wants it now. It is also what keeps these tests about the manual door — an
     * event-armed rule would have the invoice's own CREATED event planning a second step beside
     * the one the run planned, and both would be right (A1, A5).
     */
    private AutomationRule rule(String name) {
        return rule(name, TriggerKind.SCHEDULE_DAILY, 9, null);
    }

    /** Armed by the record changing, for the two tests that need a step from the event path. */
    private AutomationRule eventRule(String name) {
        return rule(name, TriggerKind.ON_CREATED_OR_UPDATED, null, null);
    }

    private AutomationRule rule(String name, TriggerKind kind, Integer hour, Integer dayOfWeek) {
        actAs(ana);
        Long id = ruleService.create(new AutomationDtos.SaveRuleRequest(name, null,
                SubjectType.INVOICE, kind, hour, dayOfWeek,
                condition("balance:gt:0"),
                List.of(new ActionSpec.CreateTask("Chase {{Invoice.Number}}", null, List.of(), 3)),
                null, true, List.of())).id();
        SecurityContextHolder.clearContext();
        return automationRuleRepository.findById(id).orElseThrow();
    }

    private JsonNode condition(String wire) {
        try {
            return objectMapper.readTree("{\"op\":\"AND\",\"of\":[{\"filter\":\"" + wire + "\"}]}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Invoice invoiceFor(Customer c) {
        actAs(admin);
        Invoice invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(),
                null, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("1000.00")))));
        SecurityContextHolder.clearContext();
        return invoice;
    }

    private Role authorRole() {
        return roleWith("RUN_NOW_AUTHOR", Privileges.AUTOMATION_VIEW, Privileges.AUTOMATION_MANAGE,
                Privileges.AUTOMATION_RUN, Privileges.CUSTOMER_VIEW, Privileges.CUSTOMER_MANAGE,
                Privileges.INVOICE_VIEW, Privileges.PAYMENT_VIEW, Privileges.EMAIL_VIEW,
                Privileges.EMAIL_SEND, Privileges.TASK_VIEW, Privileges.TASK_MANAGE,
                Privileges.PROMISE_VIEW, Privileges.PROMISE_MANAGE, Privileges.DISPUTE_CREATE,
                Privileges.DISPUTE_VIEW, Privileges.SCOPE_OVERRIDE);
    }

    /** May read every rule and run none of them. */
    private Role readerRole() {
        return roleWith("RUN_NOW_READER", Privileges.AUTOMATION_VIEW, Privileges.INVOICE_VIEW,
                Privileges.CUSTOMER_VIEW, Privileges.SCOPE_OVERRIDE);
    }

    /** May EDIT every rule and still run none of them: the two privileges are separate (AUTH-05). */
    private Role editorRole() {
        return roleWith("RUN_NOW_EDITOR", Privileges.AUTOMATION_VIEW, Privileges.AUTOMATION_MANAGE,
                Privileges.INVOICE_VIEW, Privileges.CUSTOMER_VIEW, Privileges.SCOPE_OVERRIDE);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by AutomationRunNowTest")
                .privileges(Arrays.stream(privileges)
                        .map(p -> privilegeRepository.findByName(p).orElseThrow())
                        .collect(Collectors.toCollection(HashSet<Privilege>::new)))
                .build()));
    }
}
