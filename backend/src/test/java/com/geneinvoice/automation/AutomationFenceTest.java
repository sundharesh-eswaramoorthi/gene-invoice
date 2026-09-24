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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE FENCE, which is the whole no-double-action guarantee (A5).
 *
 * <p>The claim writes a token and the settle only matches a step that still carries it. That, plus
 * the domain write and the settle sharing ONE transaction, is what makes a reclaimed step safe
 * rather than merely unlikely: the worker that lost the step finds no row to finish, throws, and
 * its own Task rolls back with it.
 *
 * <p>{@code #theLosingWorkerLeavesNoTaskBehindWhenItsSettleFindsNoRow} IS THE LOAD-BEARING ONE. It
 * exists so that nobody can refactor the settle into an inner transaction "for tidiness" without
 * the build going red — that refactor is silent in every other test in this repository, because
 * everything still succeeds and there are simply two tasks.
 */
@Import(InterleavingActions.class)
class AutomationFenceTest extends IntegrationTestBase {

    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired InvoiceService invoiceService;
    @Autowired AutomationRuleService ruleService;
    @Autowired AutomationDispatcher dispatcher;
    @Autowired PlatformTransactionManager transactionManager;

    TransactionTemplate transactions;
    TransactionTemplate ownTransaction;
    User admin;
    User ana;
    Customer home;
    Product widget;

    @BeforeEach
    void setUp() {
        InterleavingActions.Interleaving.reset();
        transactions = new TransactionTemplate(transactionManager);
        ownTransaction = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW: the interleaved reclaim has to COMMIT while the worker's own transaction
        // is still open, which is exactly what a sweeper on another instance does (A5).
        ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        admin = userRepository.findByUsername("admin").orElseThrow();
        ana = user("ana.author", authorRole().getName());
        widget = product("Widget", "1000.00");
        home = customer("Home Ltd", "ap@home.test");
    }

    @AfterEach
    void clearHooks() {
        InterleavingActions.Interleaving.reset();
    }

    @Test
    void aStepReclaimedBySweeperCannotBeSettledByTheWorkerThatLostIt() {
        AutomationStep step = plannedStep();
        Instant claimedAt = Instant.now().minus(20, ChronoUnit.MINUTES);
        // Both instants are the same twenty-minutes-ago here BECAUSE this test is manufacturing a
        // worker that really did claim the step twenty minutes ago; the sweeper reads the second
        // argument's column and nothing else (A5).
        transactions.executeWithoutResult(s ->
                assertThat(automationStepRepository.claim(step.getId(), claimedAt, claimedAt,
                        "worker-one")).isEqualTo(1));

        // Twenty minutes later nobody has settled it, so the sweeper takes it back.
        dispatcher.sweep(Instant.now());

        AutomationStep reclaimed = reload(step);
        assertThat(reclaimed.getStatus()).isEqualTo(StepStatus.QUEUED);
        assertThat(reclaimed.getClaimToken()).isNull();
        assertThat(reclaimed.getResult()).isEqualTo(AutomationDispatcher.RECLAIMED);

        // The worker that lost it is still running and still believes it owns the step. Its
        // settle matches NOTHING, which is the only thing standing between it and a second task.
        assertThat(settleAs(step, "worker-one")).isZero();

        // And it is the TOKEN and not merely the status: a second worker claims the step and only
        // ITS token is accepted.
        transactions.executeWithoutResult(s ->
                assertThat(automationStepRepository.claim(step.getId(), Instant.now().plusSeconds(3600),
                        Instant.now(), "worker-two")).isEqualTo(1));
        assertThat(settleAs(step, "worker-one")).isZero();
        assertThat(settleAs(step, "worker-two")).isEqualTo(1);
    }

    @Test
    void theLosingWorkerLeavesNoTaskBehindWhenItsSettleFindsNoRow() {
        AutomationStep step = plannedStep();

        // While the worker is inside tx2 — after it has written the Task and before it settles —
        // a sweeper on another instance reclaims the step and COMMITS that. This is the real
        // race, produced by a real committed transaction and not by a mock (A5).
        InterleavingActions.Interleaving.interleave = () -> ownTransaction.executeWithoutResult(s -> {
            AutomationStep taken = automationStepRepository.findById(step.getId()).orElseThrow();
            taken.setStatus(StepStatus.QUEUED);
            taken.setClaimToken(null);
            taken.setNextAttemptAt(null);
            automationStepRepository.saveAndFlush(taken);
        });

        dispatcher.perform(step.getId(), Instant.now().plusSeconds(60));

        // NO TASK. The settle found no row, the worker threw, and its transaction took the task
        // with it. This assertion is the whole unit (A5).
        assertThat(taskRepository.count()).isZero();
        AutomationStep after = reload(step);
        assertThat(after.getStatus()).isEqualTo(StepStatus.QUEUED);
        assertThat(after.getProducedId()).isNull();

        // And the step is still there to be done ONCE, by whoever holds it now.
        dispatcher.perform(step.getId(), Instant.now().plusSeconds(120));
        assertThat(reload(step).getStatus()).isEqualTo(StepStatus.DONE);
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    @Test
    void theSameEventDeliveredTwiceProducesOneStepAndOneTask() {
        AutomationRule rule = rule();
        Invoice invoice = invoiceFor(home);
        Long eventId = eventFor(invoice.getId());

        assertThat(dispatcher.fanOut(eventId, Instant.now().plusSeconds(60))).hasSize(1);

        // The same event offered again — a redelivery, or a sweeper that raced the nudge. Put it
        // back on the queue so the claim cannot be what saves us, and prove the DATABASE is:
        // uk_step_occasion refuses the second insert and the fan-out plans nothing (A5).
        AutomationEvent again = automationEventRepository.findById(eventId).orElseThrow();
        again.setStatus(EventStatus.NEW);
        automationEventRepository.saveAndFlush(again);

        assertThat(dispatcher.fanOut(eventId, Instant.now().plusSeconds(120))).isEmpty();
        assertThat(stepsOf(rule)).hasSize(1);
        // And the redelivery is DELIVERED, not failed. The duplicate is recovered from in Java —
        // the batch insert rolls back and each row is replayed on its own, keeping the new ones
        // and skipping the ones already there — so a redelivered event settles cleanly rather
        // than backing off and retrying a collision for ever (A5).
        AutomationEvent settled = automationEventRepository.findById(eventId).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(EventStatus.DONE);
        assertThat(settled.getLastError()).isNull();

        dispatcher.sweep(Instant.now().plusSeconds(180));
        assertThat(stepsOf(rule)).hasSize(1);
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    /**
     * THE SWEEP'S OWN now AGES, and claimed_at must not age with it (A5).
     *
     * <p>{@code sweep(now)} threads ONE instant through up to two hundred serial steps, and every
     * one of those steps may make a synchronous outbound call. The instant is right for the
     * eligibility test — what was selected is what is claimed — and wrong for {@code claimed_at},
     * which is not an eligibility test at all but the liveness clock {@code findStaleRunning}
     * reads. A step claimed at minute twelve of a long sweep, stamped with minute zero, is handed
     * straight to the next reclaimer while its worker is still inside tx2: the work is rolled
     * back, an attempt out of five is burned, and nothing above INFO is ever logged.
     *
     * <p>The seam is the same one every test here uses, turned the other way round: an instant in
     * the PAST stands in for a sweep that began half an hour ago and is still going.
     */
    @Test
    void aStepIsStampedWithWhenItWasReallyClaimedAndNotWithTheSweepsAgeingNow() {
        AutomationStep step = plannedStep();
        Instant sweepBegan = Instant.now().minus(30, ChronoUnit.MINUTES);

        Instant reallyNow = Instant.now();
        dispatcher.perform(step.getId(), sweepBegan);

        AutomationStep worked = reload(step);
        assertThat(worked.getStatus()).isEqualTo(StepStatus.DONE);
        // The claim is stamped from the wall clock, so a reclaimer ten minutes behind real time
        // still sees a live worker. Stamped with the sweep's own now it would already be twenty
        // minutes past STALE_RUNNING the moment it was written.
        assertThat(worked.getClaimedAt()).isAfterOrEqualTo(reallyNow);
        assertThat(worked.getClaimedAt()).isAfter(sweepBegan.plus(20, ChronoUnit.MINUTES));
        // And the sweep's instant still decides everything it decided before: the settle is
        // stamped from the pass's own now, not from the wall clock.
        assertThat(worked.getFinishedAt()).isBefore(reallyNow);
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private int settleAs(AutomationStep step, String token) {
        return transactions.execute(s -> automationStepRepository.settle(step.getId(),
                StepStatus.DONE, "done", ProducedType.TASK, 1L, null, Instant.now(), token));
    }

    private AutomationStep reload(AutomationStep step) {
        return automationStepRepository.findById(step.getId()).orElseThrow();
    }

    private List<AutomationStep> stepsOf(AutomationRule rule) {
        return automationStepRepository.findAll().stream()
                .filter(s -> s.getRuleId().equals(rule.getId())).toList();
    }

    /** One rule, one invoice, one QUEUED step — the state every test here starts from. */
    private AutomationStep plannedStep() {
        AutomationRule rule = rule();
        Invoice invoice = invoiceFor(home);
        dispatcher.fanOut(eventFor(invoice.getId()), Instant.now().plusSeconds(60));
        return stepsOf(rule).get(0);
    }

    private Long eventFor(Long invoiceId) {
        return automationEventRepository.findAll().stream()
                .filter(e -> e.getSubjectType() == SubjectType.INVOICE
                        && e.getSubjectId().equals(invoiceId))
                .map(AutomationEvent::getId).findFirst().orElseThrow();
    }

    private AutomationRule rule() {
        actAs(ana);
        Long id = ruleService.create(new AutomationDtos.SaveRuleRequest("Chase", null,
                SubjectType.INVOICE, TriggerKind.ON_CREATED_OR_UPDATED, null, null,
                condition(), List.of(new ActionSpec.CreateTask("Chase {{Invoice.Number}}",
                null, List.of(), 3)), null, true, List.of())).id();
        SecurityContextHolder.clearContext();
        return automationRuleRepository.findById(id).orElseThrow();
    }

    private JsonNode condition() {
        try {
            return objectMapper.readTree("{\"op\":\"AND\",\"of\":[{\"filter\":\"balance:gt:0\"}]}");
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
                .description("Built by AutomationFenceTest")
                .privileges(Arrays.stream(privileges)
                        .map(p -> privilegeRepository.findByName(p).orElseThrow())
                        .collect(Collectors.toCollection(HashSet<Privilege>::new)))
                .build()));
    }
}
