package com.geneinvoice.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Money;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.email.EmailDtos;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.email.RoleRef;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.role.Role;
import com.geneinvoice.task.Task;
import com.geneinvoice.task.TaskAssignee;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A5 end to end: an event becomes steps, the steps become real records, and everything that can
 * stop one of them in between is a sentence somebody can read rather than a silence.
 *
 * <p>THE HEADLINE: a rule can never slow down or break the user's save, and nothing is lost if the
 * consumer is down. {@code #nothingIsLostWhileTheConsumerIsDown} is the one that says so — with
 * the engine switched off (the configuration these tests run in), the save still leaves the fact
 * behind, and a sweep an hour later still does the work.
 */
@Import(InterleavingActions.class)
class AutomationRunTest extends IntegrationTestBase {

    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired InvoiceService invoiceService;
    @Autowired AutomationRuleService ruleService;
    @Autowired AutomationDispatcher dispatcher;

    User admin;
    User ana;
    Customer home;
    Product widget;

    @BeforeEach
    void setUp() {
        InterleavingActions.Interleaving.reset();
        admin = userRepository.findByUsername("admin").orElseThrow();
        ana = user("ana.author", authorRole().getName());
        widget = product("Widget", "1000.00");
        home = customer("Home Ltd", "ap@home.test");
    }

    @AfterEach
    void clearHooks() {
        InterleavingActions.Interleaving.reset();
    }

    // ---- the happy path ---------------------------------------------------------------------

    @Test
    void anOverdueInvoiceEndsUpAsATaskAndAnEmailInThatOrder() {
        AutomationRule rule = rule("Chase", List.of(
                new ActionSpec.CreateTask("Chase {{Invoice.Number}}", "Balance {{Invoice.Balance}}",
                        List.of(EmailDtos.EmailToken.role(RoleRef.record(EmailRole.SALES_POC))), 3),
                new ActionSpec.SendEmail(EmailDtos.EmailToken.user(ana.getId()),
                        List.of(EmailDtos.EmailToken.customer()),
                        "Invoice {{Invoice.Number}} is overdue", "You owe {{Invoice.Balance}}")));
        // The round trip through actions_json FIRST, because a rule whose actions cannot be read
        // back is a rule that does nothing at all — and reads as saved, and answers its own DTO
        // with an empty action list rather than an error. A-CONSUMER found exactly that and fixed
        // the writer; this assertion is where a reader meets it (A3).
        actAs(ana);
        assertThat(ruleService.dto(rule.getId()).actions()).hasSize(2);
        SecurityContextHolder.clearContext();

        Invoice invoice = invoiceFor(home);

        dispatcher.fanOut(eventFor(SubjectType.INVOICE, invoice.getId()), in(60));
        List<AutomationStep> planned = stepsOf(rule);
        assertThat(planned).hasSize(2);

        // THE ORDERING GATE, asserted before the sweep rather than inferred from it: the email is
        // action 1 and the task is action 0, and a worker offered the email first must decline it
        // while the task is still queued (A3, A5).
        dispatcher.perform(planned.get(1).getId(), in(60));
        assertThat(reload(planned.get(1)).getStatus()).isEqualTo(StepStatus.QUEUED);
        assertThat(reload(planned.get(1)).getAttempts()).isZero();

        dispatcher.sweep(in(120));

        AutomationStep task = reload(planned.get(0));
        AutomationStep email = reload(planned.get(1));
        assertThat(task.getStatus()).isEqualTo(StepStatus.DONE);
        assertThat(task.getProducedType()).isEqualTo(ProducedType.TASK);
        assertThat(email.getStatus()).isEqualTo(StepStatus.DONE);
        assertThat(email.getProducedType()).isEqualTo(ProducedType.EMAIL);
        assertThat(task.getFinishedAt()).isBeforeOrEqualTo(email.getFinishedAt());

        Task made = taskRepository.findById(task.getProducedId()).orElseThrow();
        assertThat(made.getTitle()).isEqualTo("Chase " + invoice.getInvoiceNumber());
        assertThat(made.getNotes()).isEqualTo("Balance " + Money.format(invoice.getTotal()));
        assertThat(made.getDueDate()).isEqualTo(InvoiceDates.today().plusDays(3));
        assertThat(made.getCreatedByUserId()).isEqualTo(ana.getId());
        assertThat(made.getCreatedByStepId()).isEqualTo(task.getId());
        // The seat and not the person: the source says WHY this individual is on the task (A6).
        List<TaskAssignee> seats = taskAssigneeRepository.findByTaskIdOrderByIdAsc(made.getId());
        assertThat(seats).singleElement().satisfies(seat -> {
            assertThat(seat.getUserId()).isEqualTo(admin.getId());
            assertThat(seat.getSource()).isEqualTo("ROLE:RECORD:SALES_POC");
        });

        assertThat(emailRepository.findById(email.getProducedId()).orElseThrow().getSubject())
                .isEqualTo("Invoice " + invoice.getInvoiceNumber() + " is overdue");
        // The rule's author is answerable for the send; the sender of record is the named person.
        assertThat(emailRepository.findById(email.getProducedId()).orElseThrow().getSentByUserId())
                .isEqualTo(ana.getId());
    }

    /**
     * VALIDATE AND EXECUTE READ THE TOKEN THE SAME WAY (A3, A6).
     *
     * <p>{@code AutomationRuleService.kind} accepts a token type by {@code trim()} +
     * {@code equalsIgnoreCase} — the house convention every enum here follows, and the one
     * {@code EmailAddressing.kind} uses at ACT time on the SEND_EMAIL arm — and then stores the
     * token verbatim. So {@code {"type":"role"}} is a rule the REST API really does accept. The
     * act path used to compare it with {@code "ROLE".equals} and resolve nobody at all: a task
     * with no assignee, carrying the factually false sentence "A customer cannot own a task" for a
     * token that is a ROLE. Every test in this tree builds tokens through the uppercase factories,
     * so the wire format the save path advertises was never exercised anywhere.
     *
     * <p>The promise half of the same divergence — {@code resolveOnePerson} — is pinned by
     * AutomationApprovalTest#aCollectionPocTokenSavedInLowerCaseStillNamesThatSeat.
     */
    @Test
    void aTokenTypeInTheCaseTheSaveAcceptedResolvesTheSameSeatWhenTheRuleRuns() {
        EmailDtos.EmailToken lowerRole = new EmailDtos.EmailToken("role", null, "SALES_POC", "RECORD");
        EmailDtos.EmailToken paddedUser = new EmailDtos.EmailToken(" User ", ana.getId(), null, null);
        AutomationRule rule = rule("Chase", List.of(
                new ActionSpec.CreateTask("Chase it", null, List.of(lowerRole, paddedUser), 3)));
        invoiceFor(home);

        dispatcher.sweep(in(60));

        AutomationStep planned = stepsOf(rule).get(0);
        assertThat(planned.getStatus())
                .as("%s", planned.getResult())
                .isEqualTo(StepStatus.DONE);
        // Nobody unresolved, and in particular not the sentence "A customer cannot own a task" —
        // which is what a ROLE token used to be told it was.
        assertThat(planned.getUnresolved()).isNull();

        // Both seats the rule named, resolved exactly as their uppercase twins would be.
        Task made = taskRepository.findById(planned.getProducedId()).orElseThrow();
        assertThat(taskAssigneeRepository.findByTaskIdOrderByIdAsc(made.getId()))
                .extracting(TaskAssignee::getUserId, TaskAssignee::getSource)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(admin.getId(), "ROLE:RECORD:SALES_POC"),
                        org.assertj.core.groups.Tuple.tuple(ana.getId(), TaskAssignee.SOURCE_USER));
    }

    // ---- the three skips --------------------------------------------------------------------

    @Test
    void anInvoicePaidBetweenTheSaveAndTheActIsSkippedWithAReasonAPersonCanRead() {
        AutomationRule rule = rule("Chase", List.of(chaseTask()));
        Invoice invoice = invoiceFor(home);
        dispatcher.fanOut(eventFor(SubjectType.INVOICE, invoice.getId()), in(60));
        AutomationStep step = stepsOf(rule).get(0);

        // Paid in full between the fan-out and the act. HOW it stopped matching is not the point;
        // that the engine looks again before it acts is (A5).
        Invoice paid = invoiceRepository.findById(invoice.getId()).orElseThrow();
        paid.setPaidAmount(paid.getTotal());
        invoiceRepository.saveAndFlush(paid);

        dispatcher.perform(step.getId(), in(120));

        AutomationStep settled = reload(step);
        assertThat(settled.getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(settled.getResult()).isEqualTo("The record no longer matches");
        assertThat(settled.getProducedId()).isNull();
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    void aRuleEditedAfterItsStepsWerePlannedRunsNeitherTheOldConfigurationNorTheNew() {
        AutomationRule rule = rule("Chase", List.of(
                new ActionSpec.CreateTask("The old title", null, List.of(), null)));
        Invoice invoice = invoiceFor(home);
        dispatcher.fanOut(eventFor(SubjectType.INVOICE, invoice.getId()), in(60));
        AutomationStep step = stepsOf(rule).get(0);
        assertThat(step.getRuleVersion()).isEqualTo(1);

        actAs(ana);
        ruleService.update(rule.getId(), save("Chase",
                List.of(new ActionSpec.CreateTask("The new title", null, List.of(), null)), null, true));

        dispatcher.perform(step.getId(), in(120));

        assertThat(reload(step).getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(reload(step).getResult()).isEqualTo("The rule changed before this ran");
        // Neither one, which is the whole asymmetry: an edit does not retroactively rewrite work
        // that was already promised, and it does not silently apply the old plan either (A5).
        assertThat(taskRepository.findAll()).isEmpty();
    }

    @Test
    void aRuleTurnedOffBetweenPublishAndActDoesNothingAtAll() {
        AutomationRule rule = rule("Chase", List.of(chaseTask()));
        Invoice invoice = invoiceFor(home);
        dispatcher.fanOut(eventFor(SubjectType.INVOICE, invoice.getId()), in(60));
        AutomationStep step = stepsOf(rule).get(0);

        actAs(ana);
        ruleService.setEnabled(rule.getId(), false);

        dispatcher.perform(step.getId(), in(120));
        assertThat(reload(step).getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(reload(step).getResult()).isEqualTo("The rule was turned off or removed");
        assertThat(taskRepository.count()).isZero();

        // And a soft delete says the same thing, because history has to keep naming a rule that
        // has been removed (A1, A5).
        AutomationRule second = rule("Chase again", List.of(chaseTask()));
        Invoice another = invoiceFor(home);
        dispatcher.fanOut(eventFor(SubjectType.INVOICE, another.getId()), in(180));
        AutomationStep secondStep = stepsOf(second).get(0);
        actAs(ana);
        ruleService.delete(second.getId());

        dispatcher.perform(secondStep.getId(), in(240));
        assertThat(reload(secondStep).getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(reload(secondStep).getResult()).isEqualTo("The rule was turned off or removed");
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    void anAuthorWhoLostThePrivilegeStopsTheStepInsteadOfWritingWithDeadAuthority() {
        AutomationRule rule = rule("Chase", List.of(chaseTask()));
        Invoice invoice = invoiceFor(home);
        dispatcher.fanOut(eventFor(SubjectType.INVOICE, invoice.getId()), in(60));
        AutomationStep step = stepsOf(rule).get(0);

        // Demoted after writing the rule: the rule is still armed and still matches, and the one
        // thing that must not happen is the write going through on yesterday's authority (A3).
        ana.setRole(roleWith("RULE_AUTHOR_DEMOTED", Privileges.AUTOMATION_VIEW,
                Privileges.AUTOMATION_MANAGE, Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW,
                Privileges.SCOPE_OVERRIDE));
        userRepository.saveAndFlush(ana);

        dispatcher.perform(step.getId(), in(120));

        assertThat(reload(step).getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(reload(step).getResult())
                .isEqualTo("ana.author no longer has TASK_MANAGE, so this rule cannot run");
        assertThat(taskRepository.count()).isZero();

        // A deactivated author is refused in the same words, because it is the same fact about
        // the same person (A3).
        ana.setRole(authorRole());
        ana.setActive(false);
        userRepository.saveAndFlush(ana);
        AutomationStep retried = reload(step);
        retried.setStatus(StepStatus.QUEUED);
        retried.setNextAttemptAt(null);
        retried.setFinishedAt(null);
        automationStepRepository.saveAndFlush(retried);

        dispatcher.perform(step.getId(), in(180));
        assertThat(reload(step).getResult())
                .isEqualTo("ana.author no longer has TASK_MANAGE, so this rule cannot run");
    }

    // ---- durability -------------------------------------------------------------------------

    @Test
    void nothingIsLostWhileTheConsumerIsDown() {
        rule("Chase", List.of(chaseTask()));

        Invoice invoice = invoiceFor(home);

        // THE CONSUMER IS DOWN. app.automation.async is false here, so the nudge arrives on the
        // committing thread — where the dispatcher deliberately declines to work, because Spring
        // has not finished with that thread's transaction yet. The save cost the user nothing and
        // produced nothing but a FACT (A5).
        AutomationEvent event = automationEventRepository.findAll().stream()
                .filter(e -> e.getSubjectType() == SubjectType.INVOICE
                        && e.getSubjectId().equals(invoice.getId()))
                .findFirst().orElseThrow();
        assertThat(event.getStatus()).isEqualTo(EventStatus.NEW);
        assertThat(automationStepRepository.count()).isZero();
        assertThat(taskRepository.count()).isZero();

        // An hour later the sweeper comes past, and the work happens. The event row committed
        // inside the user's own transaction is the guarantee; the nudge was only the hurry (A5).
        dispatcher.sweep(in(3600));

        assertThat(automationEventRepository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(EventStatus.DONE);
        assertThat(taskRepository.findAll()).singleElement()
                .satisfies(t -> assertThat(t.getEntityId()).isEqualTo(invoice.getId()));
    }

    @Test
    void aTransientFailureBacksOffAndTheFifthAttemptPoisonsTheStepAndTellsTheRulesAuthor() {
        AutomationRule rule = rule("Chase", List.of(chaseTask()));
        Invoice invoice = invoiceFor(home);
        dispatcher.fanOut(eventFor(SubjectType.INVOICE, invoice.getId()), in(60));
        AutomationStep step = stepsOf(rule).get(0);
        InterleavingActions.Interleaving.fail = new IllegalStateException("the database went away");

        Instant first = in(60);
        dispatcher.perform(step.getId(), first);
        assertThat(reload(step).getStatus()).isEqualTo(StepStatus.QUEUED);
        assertThat(reload(step).getAttempts()).isEqualTo(1);
        assertThat(reload(step).getResult()).isEqualTo("the database went away");
        assertThat(reload(step).getNextAttemptAt()).isEqualTo(first.plus(1, ChronoUnit.MINUTES));

        // One bad step must never starve the queue: offered again before its hour, it is not
        // even claimed (A5).
        dispatcher.perform(step.getId(), first.plusSeconds(10));
        assertThat(reload(step).getAttempts()).isEqualTo(1);

        assertThat(attemptAt(step, first.plus(2, ChronoUnit.MINUTES)).getNextAttemptAt())
                .isEqualTo(first.plus(2, ChronoUnit.MINUTES).plus(5, ChronoUnit.MINUTES));
        assertThat(attemptAt(step, first.plus(10, ChronoUnit.MINUTES)).getNextAttemptAt())
                .isEqualTo(first.plus(10, ChronoUnit.MINUTES).plus(15, ChronoUnit.MINUTES));
        assertThat(attemptAt(step, first.plus(30, ChronoUnit.MINUTES)).getNextAttemptAt())
                .isEqualTo(first.plus(30, ChronoUnit.MINUTES).plus(1, ChronoUnit.HOURS));

        AutomationStep poisoned = attemptAt(step, first.plus(3, ChronoUnit.HOURS));
        assertThat(poisoned.getAttempts()).isEqualTo(5);
        assertThat(poisoned.getStatus()).isEqualTo(StepStatus.POISONED);
        assertThat(poisoned.getNextAttemptAt()).isNull();
        assertThat(poisoned.getFinishedAt()).isNotNull();
        assertThat(automationStepRepository.countPoisoned()).isEqualTo(1);

        // The rule's OWNER, and not every administrator in the company: the person who wrote it
        // is the person who can fix it (A5, AUTH-05).
        List<Notification> told = notificationRepository.findByUserIdOrderByCreatedAtDesc(ana.getId())
                .stream().filter(n -> n.getType().equals("AUTOMATION_FAILED")).toList();
        assertThat(told).singleElement().satisfies(n ->
                assertThat(n.getMessage()).contains(rule.getName()).contains("the database went away"));

        // Terminal: a later sweep does not pick it up again.
        dispatcher.sweep(first.plus(9, ChronoUnit.HOURS));
        assertThat(reload(step).getAttempts()).isEqualTo(5);
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    void aPermanentFailureIsSkippedAtOnceAndNeverRetried() {
        AutomationRule rule = rule("Chase", List.of(chaseTask()));
        Invoice invoice = invoiceFor(home);
        dispatcher.fanOut(eventFor(SubjectType.INVOICE, invoice.getId()), in(60));
        AutomationStep step = stepsOf(rule).get(0);
        InterleavingActions.Interleaving.fail = new BadRequestException("that will never work");

        dispatcher.perform(step.getId(), in(60));

        // SKIPPED and not POISONED, and at the FIRST attempt: mirroring BulkExecutor turning a
        // BadRequestException into "skipped, ineligible" rather than "failed" (A5).
        AutomationStep settled = reload(step);
        assertThat(settled.getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(settled.getAttempts()).isEqualTo(1);
        assertThat(settled.getNextAttemptAt()).isNull();
        assertThat(settled.getResult()).isEqualTo("that will never work");
        assertThat(automationStepRepository.countPoisoned()).isZero();

        dispatcher.sweep(in(7200));
        assertThat(reload(step).getAttempts()).isEqualTo(1);
        assertThat(reload(step).getStatus()).isEqualTo(StepStatus.SKIPPED);
    }

    // ---- the many-record fan-out ------------------------------------------------------------

    @Test
    void aFanOutResumesFromItsLastCommittedPageAndNeverDoublesAStep() {
        AutomationRule rule = rule("Chase", List.of(chaseTask()));
        Invoice one = invoiceFor(home);
        Invoice two = invoiceFor(home);
        Invoice three = invoiceFor(home);
        AutomationRun run = automationRunRepository.saveAndFlush(AutomationRun.builder()
                .ruleId(rule.getId()).ruleName(rule.getName()).ruleVersion(rule.getDefinitionVersion())
                .occasion("Mtest").source(StepSource.MANUAL).asOf(InvoiceDates.today())
                .status(RunStatus.FANNING).build());

        dispatcher.materialisePage(run.getId(), in(60));

        assertThat(stepsOf(rule)).hasSize(3);
        AutomationRun after = automationRunRepository.findById(run.getId()).orElseThrow();
        assertThat(after.getCursorSubjectId()).isEqualTo(three.getId());
        assertThat(after.getMatched()).isEqualTo(3);
        assertThat(after.getStepsPlanned()).isEqualTo(3);
        // A short page means the fan-out is finished; the steps are now the queue's business.
        assertThat(after.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(after.isTruncated()).isFalse();

        // The crash: a page committed its steps and then died before its cursor. The rerun
        // re-plans invoices two and three, both of which uk_step_occasion already holds, and the
        // count does not move (A5).
        after.setStatus(RunStatus.FANNING);
        after.setCursorSubjectId(one.getId());
        automationRunRepository.saveAndFlush(after);

        dispatcher.materialisePage(run.getId(), in(120));

        assertThat(stepsOf(rule)).hasSize(3);
        assertThat(automationStepRepository.findAll().stream()
                .filter(s -> s.getSubjectId().equals(two.getId())).count()).isEqualTo(1);
        AutomationRun resumed = automationRunRepository.findById(run.getId()).orElseThrow();
        assertThat(resumed.getCursorSubjectId()).isEqualTo(three.getId());
        assertThat(resumed.getStepsPlanned()).isEqualTo(3);
    }

    @Test
    void aCooldownStopsTheSameRuleActingTwiceOnOneRecordInsideTheWindow() {
        AutomationRule rule = ruleWithCooldown("Chase", List.of(chaseTask()), 7);
        Invoice invoice = invoiceFor(home);

        dispatcher.sweep(in(60));
        assertThat(stepsOf(rule)).hasSize(1);
        assertThat(taskRepository.count()).isEqualTo(1);

        // The same invoice changes again the next day. The rule still matches it and is still
        // armed; the cooldown is one more predicate in the same query and it says no (A1).
        automationEventRepository.saveAndFlush(AutomationEvent.builder()
                .subjectType(SubjectType.INVOICE).subjectId(invoice.getId())
                .change(Change.UPDATED).status(EventStatus.NEW).build());
        dispatcher.sweep(in(3600));
        assertThat(stepsOf(rule)).hasSize(1);
        assertThat(taskRepository.count()).isEqualTo(1);

        // Ten days later the window has passed and the rule chases again.
        AutomationStep done = stepsOf(rule).get(0);
        done.setFinishedAt(Instant.now().minus(10, ChronoUnit.DAYS));
        automationStepRepository.saveAndFlush(done);
        automationEventRepository.saveAndFlush(AutomationEvent.builder()
                .subjectType(SubjectType.INVOICE).subjectId(invoice.getId())
                .change(Change.UPDATED).status(EventStatus.NEW).build());
        dispatcher.sweep(in(7200));
        assertThat(stepsOf(rule)).hasSize(2);
        assertThat(taskRepository.count()).isEqualTo(2);
    }

    /**
     * THE BACKLOG, which is the case the cooldown could not suppress (A5).
     *
     * <p>The test above is strictly sequential: it sweeps, the step finishes, and only THEN does
     * the second event arrive — so the cooldown's "is there a DONE step inside the window?" has a
     * DONE step to find. A consumer that was down, or simply more than thirty seconds behind, does
     * not produce that: it produces four NEW events about one invoice sitting there together.
     * {@code sweep} fans out every due event BEFORE it performs any step, and the settle window
     * guarantees a step planned in a pass cannot finish in that pass, so all four fan-outs used to
     * see zero DONE steps, plan four steps under four distinct occasions — {@code uk_step_occasion}
     * is keyed on the occasion, so it does not collide — and the customer got four chase actions
     * in one minute from a rule that says once a week.
     */
    @Test
    void aBacklogOfEventsAboutOneRecordIsSuppressedByTheCooldownJustAsASequenceIs() {
        AutomationRule rule = ruleWithCooldown("Chase", List.of(chaseTask()), 7);
        Invoice invoice = invoiceFor(home);      // the creation's own event

        // Three more transactions touched this invoice while nothing was consuming.
        for (int i = 0; i < 3; i++) {
            automationEventRepository.saveAndFlush(AutomationEvent.builder()
                    .subjectType(SubjectType.INVOICE).subjectId(invoice.getId())
                    .change(Change.UPDATED).status(EventStatus.NEW).build());
        }
        assertThat(automationEventRepository.count()).isEqualTo(4);

        // The instance comes back and sweeps. All four events are past the settle window and all
        // four fan out in this one pass, before any step can have finished.
        dispatcher.sweep(in(60));

        assertThat(stepsOf(rule)).hasSize(1);
        assertThat(taskRepository.count()).isEqualTo(1);
        // Every event is accounted for; they were consumed and found nothing to do, not left NEW.
        assertThat(automationEventRepository.findAll())
                .allSatisfy(e -> assertThat(e.getStatus()).isEqualTo(EventStatus.DONE));

        // And the next tick, with the step now DONE and inside the window, still adds nothing.
        dispatcher.sweep(in(3600));
        assertThat(stepsOf(rule)).hasSize(1);
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private static Instant in(long seconds) {
        return Instant.now().plusSeconds(seconds);
    }

    private AutomationStep attemptAt(AutomationStep step, Instant when) {
        dispatcher.perform(step.getId(), when);
        return reload(step);
    }

    private AutomationStep reload(AutomationStep step) {
        return automationStepRepository.findById(step.getId()).orElseThrow();
    }

    private List<AutomationStep> stepsOf(AutomationRule rule) {
        return automationStepRepository.findAll().stream()
                .filter(s -> s.getRuleId().equals(rule.getId()))
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }

    private Long eventFor(SubjectType type, Long subjectId) {
        return automationEventRepository.findAll().stream()
                .filter(e -> e.getSubjectType() == type && e.getSubjectId().equals(subjectId))
                .map(AutomationEvent::getId).findFirst().orElseThrow();
    }

    private static ActionSpec chaseTask() {
        return new ActionSpec.CreateTask("Chase {{Invoice.Number}}", null, List.of(), 3);
    }

    private AutomationRule rule(String name, List<ActionSpec> actions) {
        return ruleWithCooldown(name, actions, null);
    }

    private AutomationRule ruleWithCooldown(String name, List<ActionSpec> actions, Integer cooldown) {
        actAs(ana);
        Long id = ruleService.create(save(name, actions, cooldown, true)).id();
        return automationRuleRepository.findById(id).orElseThrow();
    }

    private AutomationDtos.SaveRuleRequest save(String name, List<ActionSpec> actions,
                                                Integer cooldown, boolean enabled) {
        return new AutomationDtos.SaveRuleRequest(name, null, SubjectType.INVOICE,
                TriggerKind.ON_CREATED_OR_UPDATED, null, null, condition("balance:gt:0"),
                actions, cooldown, enabled, List.of());
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
        // The engine has no principal, and leaving one behind would be the quietest possible way
        // for a test to prove something the consumer thread cannot do (A5).
        SecurityContextHolder.clearContext();
        return invoice;
    }

    /**
     * SCOPE_OVERRIDE is deliberate and is the same trap A-RULES recorded from the other side: the
     * POC book is an older, separate narrowing that would refuse ana the invoice before the BRANCH
     * was ever consulted, and these tests are about the engine and not about the book (A1, B1).
     */
    private Role authorRole() {
        return roleWith("RULE_AUTHOR", Privileges.AUTOMATION_VIEW, Privileges.AUTOMATION_MANAGE,
                Privileges.CUSTOMER_VIEW, Privileges.CUSTOMER_MANAGE, Privileges.INVOICE_VIEW,
                Privileges.PAYMENT_VIEW, Privileges.EMAIL_VIEW, Privileges.EMAIL_SEND,
                Privileges.TASK_VIEW, Privileges.TASK_MANAGE, Privileges.PROMISE_VIEW,
                Privileges.PROMISE_MANAGE, Privileges.DISPUTE_CREATE, Privileges.DISPUTE_VIEW,
                Privileges.SCOPE_OVERRIDE);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by AutomationRunTest")
                .privileges(Arrays.stream(privileges)
                        .map(p -> privilegeRepository.findByName(p).orElseThrow())
                        .collect(Collectors.toCollection(HashSet<Privilege>::new)))
                .build()));
    }
}
