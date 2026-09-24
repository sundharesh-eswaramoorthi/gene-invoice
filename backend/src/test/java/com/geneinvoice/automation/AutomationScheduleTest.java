package com.geneinvoice.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE SECOND PRODUCER: the clock.
 *
 * <p>A scheduled rule is a rule with an hour in a column and a next firing in another, claimed by
 * one conditional UPDATE. Everything that could go wrong with that is a property somebody has to
 * be able to state in a sentence: a rule missed for three days fires ONCE, two instances ticking
 * together fire it ONCE, a slot that arrives while the last one is still going is recorded as
 * skipped rather than piled up, and a rule that is off is never claimed at all.
 *
 * <p>There is no {@code @Scheduled(cron)} anywhere in this feature and these tests are half the
 * reason: Spring's cron trigger reads the system clock and ignores every seam, so none of this
 * could be asserted. Every test here calls {@code schedules.tick(instant)} directly, which is the
 * house habit for all four scheduled jobs (A1, A5).
 */
class AutomationScheduleTest extends IntegrationTestBase {

    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired InvoiceService invoiceService;
    @Autowired AutomationRuleService ruleService;
    @Autowired AutomationSchedules schedules;
    @Autowired AutomationDispatcher dispatcher;
    @Autowired AutomationSweepScheduler scheduler;
    @Autowired PlatformTransactionManager transactionManager;

    User admin;
    User ana;
    Customer home;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        ana = user("ana.author", authorRole().getName());
        widget = product("Widget", "1000.00");
        home = customer("Home Ltd", "ap@home.test");
    }

    // ---- the claim --------------------------------------------------------------------------

    @Test
    void aDailyRuleMissedForThreeDaysFiresOnceAndNotThreeTimes() {
        AutomationRule rule = daily(9);
        invoiceFor(home);
        Instant due = rule.getNextRunAt();
        assertThat(due).isNotNull();

        // The instance was down for three days. The next slot is computed FORWARD from the clock
        // rather than by adding a day to a missed next_run_at, which is the right dunning
        // behaviour: a customer who was not chased on Monday is chased today, not three times.
        Instant backOnline = due.plus(Duration.ofDays(3));
        schedules.tick(backOnline);

        assertThat(automationRunRepository.findAll()).singleElement().satisfies(run -> {
            assertThat(run.getSource()).isEqualTo(StepSource.SCHEDULE);
            assertThat(run.getStatus()).isEqualTo(RunStatus.FANNING);
            assertThat(run.getRuleId()).isEqualTo(rule.getId());
        });

        AutomationRule after = reload(rule);
        assertThat(after.getLastRunAt()).isEqualTo(backOnline);
        assertThat(after.getNextRunAt()).isAfter(backOnline);
        assertThat(after.getNextRunAt()).isBefore(backOnline.plus(Duration.ofHours(25)));

        // The very same moment again, and again: the claim is conditional on next_run_at, which
        // has already moved, so the other two missed days do not arrive as two more runs.
        schedules.tick(backOnline);
        schedules.tick(backOnline.plusSeconds(1));
        assertThat(automationRunRepository.count()).isEqualTo(1);
    }

    @Test
    void twoInstancesTickingTogetherFireTheRuleOnce() {
        AutomationRule rule = daily(9);
        Invoice invoice = invoiceFor(home);
        Instant slot = rule.getNextRunAt().plusSeconds(60);

        schedules.tick(slot);
        assertThat(automationRunRepository.count()).isEqualTo(1);
        String occasion = automationRunRepository.findAll().get(0).getOccasion();

        // THE OTHER INSTANCE. It read next_run_at before this one advanced it, which is exactly
        // the race, so its claim is put back and it ticks at the same moment. The slot key
        // buckets to the hour, so both instances compute the same occasion and uk_run_occasion —
        // not the claim, which they both won — is what leaves one run behind (A1, A5).
        AutomationRule reopened = reload(rule);
        reopened.setNextRunAt(rule.getNextRunAt());
        automationRuleRepository.saveAndFlush(reopened);
        schedules.tick(slot);

        assertThat(automationRunRepository.findAll()).singleElement()
                .satisfies(run -> assertThat(run.getOccasion()).isEqualTo(occasion));

        // And one firing means one step and one task, not two of either.
        dispatcher.sweep(slot);
        assertThat(stepsOf(rule)).singleElement()
                .satisfies(step -> assertThat(step.getSubjectId()).isEqualTo(invoice.getId()));
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    @Test
    void aWeeklyRuleFiresOnItsOwnWeekdayAndHourInUtc() {
        AutomationRule rule = weekly(3, 9);          // Wednesday, 09:00 UTC
        invoiceFor(home);

        Instant due = rule.getNextRunAt();
        LocalDateTime at = LocalDateTime.ofInstant(due, ZoneOffset.UTC);
        assertThat(at.getDayOfWeek().getValue()).isEqualTo(3);
        assertThat(at.getHour()).isEqualTo(9);
        assertThat(at.getMinute()).isZero();
        assertThat(due).isAfter(Instant.now());

        // A second before its hour is not its hour.
        schedules.tick(due.minusSeconds(1));
        assertThat(automationRunRepository.count()).isZero();

        schedules.tick(due.plusSeconds(1));
        assertThat(automationRunRepository.count()).isEqualTo(1);
        // A week on, to the second, and on the same weekday.
        assertThat(reload(rule).getNextRunAt()).isEqualTo(due.plus(Duration.ofDays(7)));
    }

    @Test
    void aDisabledOrDeletedRuleIsNeverClaimed() {
        AutomationRule off = daily(9);
        AutomationRule gone = daily(10);
        actAs(ana);
        ruleService.setEnabled(off.getId(), false);
        ruleService.delete(gone.getId());
        SecurityContextHolder.clearContext();
        invoiceFor(home);

        // A week later, so nobody can say it was simply not due yet.
        schedules.tick(Instant.now().plus(Duration.ofDays(7)));

        assertThat(automationRunRepository.count()).isZero();
        assertThat(automationStepRepository.count()).isZero();
        // A soft delete also takes the arming away, so the row cannot even be offered.
        assertThat(reload(gone).getNextRunAt()).isNull();
    }

    // ---- overrun, and the thing that stops it being permanent ---------------------------------

    @Test
    void aRunStillFanningWhenTheNextSlotArrivesRecordsASkippedOverrunInsteadOfPilingUp() {
        AutomationRule rule = daily(9);
        Invoice invoice = invoiceFor(home);

        Instant first = rule.getNextRunAt().plusSeconds(60);
        schedules.tick(first);
        AutomationRun opened = automationRunRepository.findAll().get(0);
        assertThat(opened.getStatus()).isEqualTo(RunStatus.FANNING);

        // Tomorrow, and yesterday's run has not even found its records yet.
        Instant second = reload(rule).getNextRunAt().plusSeconds(60);
        schedules.tick(second);

        List<AutomationRun> runs = automationRunRepository.findAll();
        assertThat(runs).hasSize(2);
        AutomationRun skipped = runs.stream()
                .filter(r -> r.getStatus() == RunStatus.SKIPPED_OVERRUN).findFirst().orElseThrow();
        // Recorded and not dropped: somebody reading the history has to see that the rule fell
        // behind, which is the whole difference between a skip and a silence (A5).
        assertThat(skipped.getError()).isEqualTo(AutomationSchedules.OVERRUN);
        assertThat(skipped.getFinishedAt()).isEqualTo(second);
        assertThat(skipped.getOccasion()).isNotEqualTo(opened.getOccasion());

        // AND THE SUBJECT IS NOT PROCESSED TWICE. The skipped slot planned nothing, so when the
        // first run finally runs, this invoice gets one step and one task and no more (A1, A5).
        dispatcher.sweep(second);
        assertThat(stepsOf(rule)).singleElement()
                .satisfies(step -> assertThat(step.getSubjectId()).isEqualTo(invoice.getId()));
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    @Test
    void aRunWhoseStepsHaveAllFinishedIsClosedSoTheNextSlotIsNotAnOverrun() {
        AutomationRule rule = daily(9);
        invoiceFor(home);

        Instant first = rule.getNextRunAt().plusSeconds(60);
        schedules.tick(first);
        dispatcher.sweep(first);

        Long runId = automationRunRepository.findAll().get(0).getId();
        assertThat(automationRunRepository.findById(runId).orElseThrow().getStatus())
                .isEqualTo(RunStatus.RUNNING);
        assertThat(stepsOf(rule)).allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(StepStatus.DONE));

        Instant second = reload(rule).getNextRunAt().plusSeconds(60);
        schedules.tick(second);

        // NOTHING ELSE IN THE ENGINE WRITES DONE ON A RUN, and a run left open for ever would
        // make every later slot of this rule an overrun — the safety valve becoming a permanent
        // off switch. Tomorrow's run opens because yesterday's was closed (A5).
        AutomationRun yesterday = automationRunRepository.findById(runId).orElseThrow();
        assertThat(yesterday.getStatus()).isEqualTo(RunStatus.DONE);
        assertThat(yesterday.getFinishedAt()).isEqualTo(second);

        List<AutomationRun> runs = automationRunRepository.findAll();
        assertThat(runs).hasSize(2);
        assertThat(runs.stream().map(AutomationRun::getStatus))
                .containsExactlyInAnyOrder(RunStatus.DONE, RunStatus.FANNING);
    }

    @Test
    void aRunIsNotClosedWhileOneOfItsStepsIsStillWaiting() {
        AutomationRule rule = daily(9);
        Customer other = customer("Away Ltd", "ap@away.test");
        invoiceFor(home);
        invoiceFor(other);

        Instant first = rule.getNextRunAt().plusSeconds(60);
        schedules.tick(first);
        dispatcher.sweep(first);
        Long runId = automationRunRepository.findAll().get(0).getId();

        // One step put back on the queue by hand, the way a reclaim or a transient failure would
        // leave it. The run has work left in it and must not be reported finished.
        AutomationStep waiting = stepsOf(rule).get(0);
        waiting.setStatus(StepStatus.QUEUED);
        waiting.setFinishedAt(null);
        automationStepRepository.saveAndFlush(waiting);

        schedules.closeFinishedRuns(first.plusSeconds(600));
        assertThat(automationRunRepository.findById(runId).orElseThrow().getStatus())
                .isEqualTo(RunStatus.RUNNING);
    }

    // ---- the claim committed and the run did not ----------------------------------------------

    /**
     * A CLAIMED SLOT THAT OPENS NO RUN IS PUT BACK (A5).
     *
     * <p>{@code claimSchedule} is the only thing that makes a rule due, and it commits on its own,
     * before the run is opened in a second transaction. {@code insert} recovers from a duplicate
     * key and from nothing else, so a reset connection, a statement timeout or a lock timeout used
     * to reach {@code fireQuietly}'s WARN — which promised that the next tick would try again,
     * when the claim had just made that impossible. A daily dunning rule lost its entire
     * population for that day and left one misleading log line behind.
     *
     * <p>The fault is injected through a REAL repository interface rather than a mock framework:
     * a proxy that delegates every call to the real bean except the one insert, handed to a real
     * {@link AutomationSchedules} over the real transaction manager. Everything below the fault is
     * the production path, including the compensating UPDATE.
     */
    @Test
    void aSlotWhoseRunCannotBeOpenedIsHandedBackInsteadOfBeingLost() {
        AutomationRule rule = daily(9);
        invoiceFor(home);
        Instant due = rule.getNextRunAt();
        Instant slot = due.plusSeconds(60);

        AutomationSchedules failing = new AutomationSchedules(automationRuleRepository,
                brokenInsert(), automationStepRepository, new TransactionTemplate(transactionManager));
        failing.tick(slot);

        // Nothing opened, and the schedule is due again rather than parked on tomorrow.
        assertThat(automationRunRepository.count()).isZero();
        AutomationRule after = reload(rule);
        assertThat(after.getNextRunAt()).isEqualTo(due);
        // last_run_at is deliberately NOT rewound: an attempt really was made, and a schedule whose
        // last attempt is newer than its last run is the discrepancy an operator wants to see.
        assertThat(after.getLastRunAt()).isEqualTo(slot);

        // And the sentence in the log is now true: the next tick really does fire it.
        schedules.tick(slot);
        assertThat(automationRunRepository.findAll()).singleElement().satisfies(run -> {
            assertThat(run.getStatus()).isEqualTo(RunStatus.FANNING);
            assertThat(run.getOccasion()).isEqualTo(AutomationSchedules.slotOf(slot));
        });
        dispatcher.sweep(slot);
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    /**
     * And the other half of the guard: a slot another instance has since claimed and moved on is
     * never dragged backwards by a straggler's compensation (A5).
     */
    @Test
    void aScheduleAnotherInstanceHasSinceMovedOnIsNotDraggedBackwards() {
        AutomationRule rule = daily(9);
        Instant due = rule.getNextRunAt();
        Instant moved = due.plus(Duration.ofDays(1));
        Instant later = due.plus(Duration.ofDays(2));

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Integer claimed = tx.execute(s ->
                automationRuleRepository.claimSchedule(rule.getId(), due.plusSeconds(1), moved));
        assertThat(claimed).isEqualTo(1);

        // The straggler thinks it still owns the slot it claimed and tries to hand back `due`.
        Integer refused = tx.execute(s ->
                automationRuleRepository.releaseSchedule(rule.getId(), later, due, due));
        assertThat(refused).isZero();
        assertThat(reload(rule).getNextRunAt()).isEqualTo(moved);

        // Its own claim, though, it may undo.
        Integer undone = tx.execute(s ->
                automationRuleRepository.releaseSchedule(rule.getId(), moved, due, due));
        assertThat(undone).isEqualTo(1);
        assertThat(reload(rule).getNextRunAt()).isEqualTo(due);
    }

    /** Every call but the run insert reaches the real repository; that one loses its connection. */
    private AutomationRunRepository brokenInsert() {
        return (AutomationRunRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{AutomationRunRepository.class},
                (proxy, method, args) -> {
                    if ("save".equals(method.getName())) {
                        throw new QueryTimeoutException("the connection was reset");
                    }
                    try {
                        return method.invoke(automationRunRepository, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    // ---- end to end -------------------------------------------------------------------------

    @Test
    void aScheduledRunPlansOneStepPerMatchingRecordAndTheSweeperDoesTheWork() {
        AutomationRule rule = daily(9);
        Invoice one = invoiceFor(home);
        Customer other = customer("Away Ltd", "ap@away.test");
        Invoice two = invoiceFor(other);

        Instant slot = rule.getNextRunAt().plusSeconds(60);
        schedules.tick(slot);
        dispatcher.sweep(slot);

        AutomationRun run = automationRunRepository.findAll().get(0);
        assertThat(run.getMatched()).isEqualTo(2);
        assertThat(run.getStepsPlanned()).isEqualTo(2);
        assertThat(run.isTruncated()).isFalse();
        assertThat(run.getAsOf()).isEqualTo(com.geneinvoice.invoice.InvoiceDates.dayOf(slot));

        assertThat(stepsOf(rule)).hasSize(2).allSatisfy(step -> {
            assertThat(step.getSource()).isEqualTo(StepSource.SCHEDULE);
            assertThat(step.getRunId()).isEqualTo(run.getId());
            assertThat(step.getStatus()).isEqualTo(StepStatus.DONE);
            assertThat(step.getProducedType()).isEqualTo(ProducedType.TASK);
        });
        assertThat(taskRepository.findAll()).extracting("entityId")
                .containsExactlyInAnyOrder(one.getId(), two.getId());
    }

    /**
     * THE FOURTH SCHEDULED JOB, wired. One bean, three arms, and this is the only place they are
     * all driven together: the clock opens the run, the consumer carries its page, and the reaper
     * takes what nobody needs any more. They are three separate try/catch blocks so one failing
     * never stops the other two, and the pool
     * ({@code spring.task.scheduling.pool.size: 5}) already sizes for this bean (A5).
     */
    @Test
    void theOneScheduledBeanCarriesTheClockTheConsumerAndTheReaper() {
        AutomationRule rule = daily(9);
        Invoice invoice = invoiceFor(home);

        // Due an hour ago on the REAL clock, because @Scheduled has no seam and this test drives
        // the scheduled method itself rather than the three things behind it.
        AutomationRule due = reload(rule);
        due.setNextRunAt(Instant.now().minus(Duration.ofHours(1)));
        automationRuleRepository.saveAndFlush(due);

        AutomationStep ancient = automationStepRepository.save(AutomationStep.builder()
                .occasion("OLD1").ruleId(rule.getId()).ruleName(rule.getName()).ruleVersion(1)
                .actionIndex(0).actionKind(ActionKind.CREATE_TASK)
                .subjectType(SubjectType.INVOICE).subjectId(invoice.getId())
                .customerId(home.getId()).source(StepSource.SCHEDULE)
                .status(StepStatus.DONE)
                .finishedAt(Instant.now().minus(Duration.ofDays(400)))
                .build());

        scheduler.sweep();      // the clock opens the run, and the reaper goes round
        scheduler.sweep();      // the consumer carries its first page

        assertThat(automationRunRepository.findAll()).singleElement().satisfies(run -> {
            assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
            assertThat(run.getStepsPlanned()).isEqualTo(1);
        });
        assertThat(automationStepRepository.findById(ancient.getId())).isEmpty();
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private AutomationRule reload(AutomationRule rule) {
        return automationRuleRepository.findById(rule.getId()).orElseThrow();
    }

    private List<AutomationStep> stepsOf(AutomationRule rule) {
        return automationStepRepository.findAll().stream()
                .filter(s -> s.getRuleId().equals(rule.getId()))
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }

    private AutomationRule daily(int hourUtc) {
        return scheduled(TriggerKind.SCHEDULE_DAILY, null, hourUtc);
    }

    private AutomationRule weekly(int dayOfWeek, int hourUtc) {
        return scheduled(TriggerKind.SCHEDULE_WEEKLY, dayOfWeek, hourUtc);
    }

    private AutomationRule scheduled(TriggerKind kind, Integer dayOfWeek, int hourUtc) {
        actAs(ana);
        AutomationDtos.SaveRuleRequest req = new AutomationDtos.SaveRuleRequest(
                "Nightly chase " + kind + " " + hourUtc, null, SubjectType.INVOICE, kind,
                hourUtc, dayOfWeek, condition("balance:gt:0"),
                List.of(new ActionSpec.CreateTask("Chase {{Invoice.Number}}", null, List.of(), 3)),
                null, true, List.of());
        Long id = ruleService.create(req).id();
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

    /** SCOPE_OVERRIDE for the reason A-RULES and A-CONSUMER both recorded: the POC book is an
     *  older, separate narrowing and these tests are about the clock, not about the book. */
    private Role authorRole() {
        return roleWith("SCHEDULE_AUTHOR", Privileges.AUTOMATION_VIEW, Privileges.AUTOMATION_MANAGE,
                Privileges.AUTOMATION_RUN, Privileges.CUSTOMER_VIEW, Privileges.CUSTOMER_MANAGE,
                Privileges.INVOICE_VIEW, Privileges.PAYMENT_VIEW, Privileges.EMAIL_VIEW,
                Privileges.EMAIL_SEND, Privileges.TASK_VIEW, Privileges.TASK_MANAGE,
                Privileges.PROMISE_VIEW, Privileges.PROMISE_MANAGE, Privileges.DISPUTE_CREATE,
                Privileges.DISPUTE_VIEW, Privileges.SCOPE_OVERRIDE);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by AutomationScheduleTest")
                .privileges(Arrays.stream(privileges)
                        .map(p -> privilegeRepository.findByName(p).orElseThrow())
                        .collect(Collectors.toCollection(HashSet<Privilege>::new)))
                .build()));
    }
}
