package com.geneinvoice.automation.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.MovableClock;
import com.geneinvoice.automation.AutomationTestBase;
import com.geneinvoice.automation.RecordingAutomationFaults;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.common.Money;
import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.product.Product;
import com.geneinvoice.task.Task;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The outbox consumer keeps its promises (A5, P2; §5.10.6): nothing is lost while it is down, an event is acted on
 * once however often it is delivered, a worker that lost its lease changes nothing, failures are retried and then
 * given up on with the owner told, a lost database stops the drain without spending an attempt, and each event is
 * evaluated at the moment it happened, on its own (D10: never merged with another).
 */
class WorkerDurabilityTest extends AutomationTestBase {

    @Autowired TransactionTemplate tx;

    User admin;
    User owen;
    Customer acme;
    Product widget;
    Long invoiceId;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        owen = user("owen.owner", "CASHIER");
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(admin);
        invoiceId = invoice(acme, widget, 2, null);
        // The invoice's own events are before any rule; the tests start from an empty queue.
        drain();
    }

    private Long chaseRule(Map<String, Object> action) throws Exception {
        return saveRule(owen, rule("Chase", "INVOICE", "ENTITY_UPDATED", null, action));
    }

    private void touch(String notes) {
        actAs(admin);
        invoiceService.update(invoiceId, new InvoiceDtos.UpdateInvoiceRequest(notes, null, null, null, null));
    }

    private AutomationEvent onlyUpdate() {
        return events(AutomationEvent.UPDATED).stream().filter(e -> "INVOICE".equals(e.getEntityType()))
                .reduce((a, b) -> b).orElseThrow();
    }

    /** A conditional update, run in a transaction of its own; the rows it changed. */
    private int rows(java.util.function.Supplier<Integer> update) {
        Integer n = tx.execute(s -> update.get());
        return n == null ? 0 : n;
    }

    private Instant now() {
        return MovableClock.SHARED.instant();
    }

    private void later(Duration by) {
        MovableClock.SHARED.set(now().plus(by));
    }

    @Test
    void whileTheConsumerIsDownEventsWaitAndNothingIsLost() throws Exception {
        Long ruleId = chaseRule(task("Chase"));
        touch("first");
        AutomationEvent waiting = onlyUpdate();
        assertThat(waiting.getStatus()).isEqualTo(AutomationEvent.PENDING);
        later(Duration.ofHours(3));
        assertThat(event(waiting.getId()).getStatus()).isEqualTo(AutomationEvent.PENDING);
        assertThat(tasksOf(ruleId)).isEmpty();

        drain();
        assertThat(event(waiting.getId()).getStatus()).isEqualTo(AutomationEvent.DONE);
        assertThat(event(waiting.getId()).getProcessedAt()).isNotNull();
        assertThat(tasksOf(ruleId)).hasSize(1);
    }

    @Test
    void ofTwoClaimsOneWinsAndTwoWorkersDrainingAtOnceActOnce() throws Exception {
        Long ruleId = chaseRule(everyTime(task("Chase")));
        touch("first");
        Long id = onlyUpdate().getId();
        Instant at = now();
        assertThat(rows(() -> automationEventRepository.claim(id, "a", at, at.plusSeconds(300)))).isEqualTo(1);
        assertThat(rows(() -> automationEventRepository.claim(id, "b", at, at.plusSeconds(300)))).isZero();
        assertThat(event(id).getClaims()).isEqualTo(1);
        assertThat(event(id).getClaimedBy()).isEqualTo("a");
        tx.execute(s -> automationEventRepository.release(id, 1, at, at));

        touch("second");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> drains = List.of(pool.submit(() -> {
                start.await();
                return worker.drain(now());
            }), pool.submit(() -> {
                start.await();
                return worker.drain(now());
            }));
            start.countDown();
            int claimed = 0;
            for (Future<Integer> f : drains) claimed += f.get(30, TimeUnit.SECONDS);
            assertThat(claimed).as("each event claimed by one worker only").isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
        assertThat(tasksOf(ruleId)).hasSize(2);
        assertThat(runsOf(ruleId)).hasSize(2).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(RunStatus.SUCCEEDED));
    }

    @Test
    void aProcessThatStopsJustAfterTheActionCommittedLeavesOneTaskOnceTheLeaseRunsOut() throws Exception {
        Long ruleId = chaseRule(task("Chase"));
        touch("first");
        Long id = onlyUpdate().getId();
        automationFaults.crashAfterCommit(1);

        assertThatThrownBy(this::drain).isInstanceOf(RecordingAutomationFaults.SimulatedCrash.class);
        assertThat(event(id).getStatus()).isEqualTo(AutomationEvent.PROCESSING);
        assertThat(tasksOf(ruleId)).hasSize(1);
        assertThat(drain()).as("the lease is still held").isZero();

        later(Duration.ofMinutes(6));
        drain();
        assertThat(event(id).getStatus()).isEqualTo(AutomationEvent.DONE);
        assertThat(event(id).getClaims()).isEqualTo(2);
        assertThat(event(id).getAttempts()).as("a lost lease is not an attempt").isZero();
        assertThat(tasksOf(ruleId)).hasSize(1);
        AutomationRun run = runsOf(ruleId).get(0);
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getDone()).isEqualTo(1);
    }

    @Test
    void aWorkerWhoseLeaseRanOutCannotOverwriteTheOutcome() throws Exception {
        Long ruleId = chaseRule(task("Chase"));
        touch("first");
        Long id = onlyUpdate().getId();
        Instant at = now();
        tx.execute(s -> automationEventRepository.claim(id, "slow-node", at, at.plusSeconds(300)));

        later(Duration.ofMinutes(6));
        drain();
        assertThat(event(id).getStatus()).isEqualTo(AutomationEvent.DONE);
        assertThat(event(id).getClaims()).isEqualTo(2);

        Instant then = now();
        assertThat(rows(() -> automationEventRepository.finishRetry(id, 1, then, then, "late"))).isZero();
        assertThat(rows(() -> automationEventRepository.finishDone(id, 1, then))).isZero();
        assertThat(rows(() -> automationEventRepository.heartbeat(id, 1, then, then))).isZero();
        assertThat(event(id).getStatus()).isEqualTo(AutomationEvent.DONE);
        assertThat(event(id).getLastError()).isNull();
        assertThat(tasksOf(ruleId)).hasSize(1);
    }

    @Test
    void everyClaimTakesItsLeaseFromTheClockAtThatMomentNotFromTheStartOfTheDrain() throws Exception {
        Long ruleId = chaseRule(everyTime(task("Chase")));
        touch("first");
        touch("second");
        List<AutomationEvent> queued = events(AutomationEvent.UPDATED).stream()
                .filter(e -> "INVOICE".equals(e.getEntityType())).toList();
        Long second = queued.get(1).getId();
        Instant start = now();
        MovableClock.SHARED.set(start);
        AtomicReference<Instant> secondLease = new AtomicReference<>();
        automationFaults.onNextBeforeAction(() -> {
            // The first event takes longer than a whole lease.
            later(Duration.ofMinutes(10));
            automationFaults.onNextBeforeAction(() -> secondLease.set(event(second).getLeaseUntil()));
        });

        drain();
        assertThat(secondLease.get()).as("a lease already run out would let another node take the event too")
                .isEqualTo(start.plus(Duration.ofMinutes(10)).plusSeconds(300));
        assertThat(queued).allSatisfy(e -> assertThat(event(e.getId()).getStatus()).isEqualTo(AutomationEvent.DONE));
        assertThat(tasksOf(ruleId)).hasSize(2);
    }

    @Test
    void aTransientFailureWaitsThenSucceedsWithoutADuplicate() throws Exception {
        Long ruleId = chaseRule(task("Chase"));
        touch("first");
        Long id = onlyUpdate().getId();
        automationFaults.failBeforeAction(() -> new CannotAcquireLockException("customer is busy"));

        drain();
        AutomationEvent waiting = event(id);
        assertThat(waiting.getStatus()).isEqualTo(AutomationEvent.PENDING);
        assertThat(waiting.getAttempts()).isEqualTo(1);
        assertThat(waiting.getNextAttemptAt()).isAfter(now().plusSeconds(29)).isBefore(now().plusSeconds(31));
        assertThat(waiting.getLastError()).isEqualTo("1 action to try again");
        assertThat(tasksOf(ruleId)).isEmpty();
        assertThat(runsOf(ruleId)).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(RunStatus.RUNNING));

        assertThat(drain()).as("not due yet").isZero();
        later(Duration.ofSeconds(31));
        drain();
        assertThat(event(id).getStatus()).isEqualTo(AutomationEvent.DONE);
        assertThat(tasksOf(ruleId)).hasSize(1);
        assertThat(runsOf(ruleId)).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(r.getDone()).isEqualTo(1);
        });
    }

    @Test
    void afterFiveAttemptsTheEventIsGivenUpOnItsRunFailsAndTheOwnerIsTold() throws Exception {
        Long ruleId = chaseRule(task("Chase"));
        touch("first");
        Long id = onlyUpdate().getId();
        for (int i = 0; i < 5; i++) {
            automationFaults.failBeforeAction(() -> new CannotAcquireLockException("customer is busy"));
        }
        for (int i = 0; i < 5; i++) {
            drain();
            later(Duration.ofHours(2));
        }
        AutomationEvent dead = event(id);
        assertThat(dead.getStatus()).isEqualTo(AutomationEvent.DEAD);
        assertThat(dead.getAttempts()).isEqualTo(5);
        assertThat(dead.getLastError()).isEqualTo("Gave up after 5 attempts: 1 action to try again");
        AutomationRun run = runsOf(ruleId).get(0);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getMessage()).startsWith("Gave up after 5 attempts");
        assertThat(automationRuleRepository.findById(ruleId).orElseThrow().getLastRunStatus())
                .isEqualTo(RunStatus.FAILED);
        List<Notification> told = notificationRepository.findAll().stream()
                .filter(n -> n.getType().equals("AUTOMATION_RUN_FAILED")).toList();
        assertThat(told).singleElement().satisfies(n -> {
            assertThat(n.getUserId()).isEqualTo(owen.getId());
            assertThat(n.getLink()).isEqualTo("/automation/" + ruleId);
        });
        assertThat(drain()).isZero();
        assertThat(tasksOf(ruleId)).isEmpty();
    }

    @Test
    void anEventClaimedTwentyTimesWithoutFinishingIsGivenUpOn() throws Exception {
        chaseRule(task("Chase"));
        touch("first");
        Long id = onlyUpdate().getId();
        tx.executeWithoutResult(s -> {
            AutomationEvent ev = automationEventRepository.findById(id).orElseThrow();
            ev.setClaims(20);
            automationEventRepository.save(ev);
        });
        drain();
        assertThat(event(id).getStatus()).isEqualTo(AutomationEvent.DEAD);
        assertThat(event(id).getLastError()).isEqualTo("Processing never finished after 20 claims");
    }

    @Test
    void aLostDatabaseStopsTheDrainWithoutSpendingAnAttempt() throws Exception {
        Long ruleId = chaseRule(everyTime(task("Chase")));
        touch("first");
        touch("second");
        List<AutomationEvent> queued = events(AutomationEvent.UPDATED).stream()
                .filter(e -> "INVOICE".equals(e.getEntityType())).toList();
        assertThat(queued).hasSize(2);
        automationFaults.failBeforeAction(() -> new DataAccessResourceFailureException("connection refused"));

        drain();
        AutomationEvent first = event(queued.get(0).getId());
        assertThat(first.getStatus()).isEqualTo(AutomationEvent.PENDING);
        assertThat(first.getAttempts()).isZero();
        assertThat(first.getNextAttemptAt()).isAfter(now().plusSeconds(29));
        assertThat(event(queued.get(1).getId()).getClaims()).as("the drain stopped").isZero();
        assertThat(tasksOf(ruleId)).isEmpty();

        later(Duration.ofSeconds(31));
        drain();
        assertThat(tasksOf(ruleId)).hasSize(2);
    }

    @Test
    void aLockTimeoutIsRetriedLaterAndDoesNotStopTheDrain() throws Exception {
        Long ruleId = chaseRule(everyTime(task("Chase")));
        touch("first");
        touch("second");
        automationFaults.failAfterLock(() -> new CannotAcquireLockException("Timeout trying to lock table"));

        drain();
        List<AutomationEvent> queued = events(AutomationEvent.UPDATED).stream()
                .filter(e -> "INVOICE".equals(e.getEntityType())).toList();
        assertThat(queued).extracting(AutomationEvent::getStatus)
                .containsExactly(AutomationEvent.PENDING, AutomationEvent.DONE);
        assertThat(queued.get(0).getAttempts()).isEqualTo(1);
        assertThat(tasksOf(ruleId)).hasSize(1);
    }

    @Test
    void theDrainRefusesToRunInsideATransaction() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> worker.drain(now())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not run inside a transaction");
    }

    @Test
    void aRunGivenUpOnIsTriedAgainByItsOwnerAndAnEventRequeuedByAnAdministrator() throws Exception {
        Long ruleId = chaseRule(everyTime(task("Chase")));
        touch("first");
        Long id = onlyUpdate().getId();
        giveUp(id);
        AutomationRun run = runsOf(ruleId).get(0);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);

        User sam = user("sam.sales", "SALES_POC");
        send(post("/api/automation/runs/" + run.getId() + "/retry"), sam, Map.of()).andExpect(status().isForbidden());
        JsonNode queued = body(send(post("/api/automation/runs/" + run.getId() + "/retry"), owen, Map.of())
                .andExpect(status().isAccepted()));
        assertThat(queued.get("runId").asLong()).isEqualTo(run.getId());
        assertThat(queued.get("eventId").asLong()).isEqualTo(id);
        assertThat(queued.get("status").asText()).isEqualTo("QUEUED");
        assertThat(event(id).getStatus()).isEqualTo(AutomationEvent.PENDING);
        assertThat(event(id).getAttempts()).isZero();
        assertThat(automationRunRepository.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(RunStatus.QUEUED);
        send(post("/api/automation/runs/" + run.getId() + "/retry"), owen, Map.of())
                .andExpect(status().isBadRequest());

        drain();
        assertThat(tasksOf(ruleId)).hasSize(1);
        assertThat(automationRunRepository.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(RunStatus.SUCCEEDED);

        touch("second");
        Long other = onlyUpdate().getId();
        send(post("/api/automation/events/" + other + "/requeue"), admin, Map.of())
                .andExpect(status().isBadRequest());
        giveUp(other);
        send(post("/api/automation/events/" + other + "/requeue"), owen, Map.of()).andExpect(status().isForbidden());
        JsonNode requeued = body(send(post("/api/automation/events/" + other + "/requeue"), admin, Map.of())
                .andExpect(status().isAccepted()));
        assertThat(requeued.get("status").asText()).isEqualTo("PENDING");
        drain();
        assertThat(event(other).getStatus()).isEqualTo(AutomationEvent.DONE);
    }

    /**
     * A run by hand makes the rule act, even a disabled one, so an administrator who is not its owner cannot requeue
     * its event (SC-3): they try it again from the run, which takes the rule over. The owner still may.
     */
    @Test
    void aRunByHandGivenUpOnIsNotRequeuedByAnAdministratorWhoDoesNotOwnTheRule() throws Exception {
        Long ruleId = saveRule(owen, with(rule("Chase", "INVOICE", "MANUAL", null, task("Chase")), "enabled", false));
        JsonNode queued = body(send(post(RULES + "/" + ruleId + "/run"), owen, Map.of())
                .andExpect(status().isAccepted()));
        Long id = queued.get("eventId").asLong();
        Long runId = queued.get("runId").asLong();
        giveUp(id);
        assertThat(tasksOf(ruleId)).isEmpty();

        JsonNode refused = body(send(post("/api/automation/events/" + id + "/requeue"), admin, Map.of())
                .andExpect(status().isBadRequest()));
        assertThat(refused.get("message").asText()).isEqualTo("A run by hand is tried again from its run: open the "
                + "rule's Runs tab and use Retry, which takes the rule over (run #" + runId + ")");
        assertThat(refused.get("message").asText()).as("words for a person, not an API path (RR-2)")
                .contains("Runs tab").doesNotContain("/api/").doesNotContain("null");
        assertThat(event(id).getStatus()).isEqualTo(AutomationEvent.DEAD);
        assertThat(automationRunRepository.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
        drain();
        assertThat(tasksOf(ruleId)).isEmpty();
        assertThat(automationRuleRepository.findById(ruleId).orElseThrow().getOwnerUserId()).isEqualTo(owen.getId());

        // Its owner, were they an administrator, requeues it as before.
        owen.setRole(role("ADMIN"));
        owen = userRepository.save(owen);
        send(post("/api/automation/events/" + id + "/requeue"), owen, Map.of()).andExpect(status().isAccepted());
        drain();
        assertThat(event(id).getStatus()).isEqualTo(AutomationEvent.DONE);
        assertThat(tasksOf(ruleId)).hasSize(1);
    }

    @Test
    void theHealthViewShowsAdministratorsWhatIsWaitingAndWhatWasGivenUpOn() throws Exception {
        chaseRule(everyTime(task("Chase")));
        touch("first");
        Long dead = onlyUpdate().getId();
        giveUp(dead);
        touch("second");
        later(Duration.ofSeconds(40));

        JsonNode health = body(mockMvc.perform(get("/api/automation/health").with(as(admin)))
                .andExpect(status().isOk()));
        assertThat(health.get("consumerEnabled").asBoolean()).isTrue();
        assertThat(health.get("pending").asLong()).as("the second change").isEqualTo(1);
        assertThat(health.get("processing").asLong()).isZero();
        assertThat(health.get("retrying").asLong()).isZero();
        assertThat(health.get("dead").asLong()).isEqualTo(1);
        assertThat(health.get("oldestPendingSeconds").asLong()).isGreaterThanOrEqualTo(40);
        JsonNode listed = health.get("deadEvents").get(0);
        assertThat(listed.get("id").asLong()).isEqualTo(dead);
        assertThat(listed.get("kind").asText()).isEqualTo(AutomationEvent.UPDATED);
        assertThat(listed.get("entityType").asText()).isEqualTo("INVOICE");
        assertThat(listed.get("entityId").asLong()).isEqualTo(invoiceId);
        assertThat(listed.get("attempts").asInt()).isEqualTo(5);
        assertThat(listed.get("lastError").asText()).startsWith("Gave up after 5 attempts");

        mockMvc.perform(get("/api/automation/health").with(as(owen))).andExpect(status().isForbidden());
    }

    @Test
    void aRequeuedEventGetsATokenNoEarlierWorkerHoldsAndCountsItsClaimsAfresh() throws Exception {
        Long ruleId = chaseRule(task("Chase"));
        touch("first");
        Long id = onlyUpdate().getId();
        tx.executeWithoutResult(s -> {
            AutomationEvent ev = automationEventRepository.findById(id).orElseThrow();
            ev.setClaims(20);
            automationEventRepository.save(ev);
        });
        drain();
        assertThat(event(id).getStatus()).isEqualTo(AutomationEvent.DEAD);
        int stale = event(id).getClaims();

        send(post("/api/automation/events/" + id + "/requeue"), admin, Map.of()).andExpect(status().isAccepted());
        assertThat(event(id).getClaims()).as("the token never goes back").isEqualTo(stale);
        Instant at = now();
        assertThat(rows(() -> automationEventRepository.claim(id, "fresh-node", at, at.plusSeconds(300))))
                .isEqualTo(1);
        for (int token : List.of(1, stale)) {
            assertThat(rows(() -> automationEventRepository.finishDone(id, token, at))).isZero();
            assertThat(rows(() -> automationEventRepository.heartbeat(id, token, at, at))).isZero();
            assertThat(rows(() -> automationEventRepository.finishRetry(id, token, at, at, "late"))).isZero();
        }
        assertThat(event(id).getStatus()).isEqualTo(AutomationEvent.PROCESSING);
        assertThat(rows(() -> automationEventRepository.release(id, stale + 1, at, at))).isEqualTo(1);

        drain();
        assertThat(event(id).getStatus()).as("the claims before the requeue are not counted again")
                .isEqualTo(AutomationEvent.DONE);
        assertThat(tasksOf(ruleId)).hasSize(1);
    }

    /** Fails every attempt at the event until it is given up on. */
    private void giveUp(Long id) {
        for (int i = 0; i < 5; i++) {
            automationFaults.failBeforeAction(() -> new CannotAcquireLockException("customer is busy"));
        }
        for (int i = 0; i < 5 && !AutomationEvent.DEAD.equals(event(id).getStatus()); i++) {
            drain();
            later(Duration.ofHours(2));
        }
        automationFaults.reset();
        assertThat(event(id).getStatus()).isEqualTo(AutomationEvent.DEAD);
    }

    // ---- the moment of the event (PC-1) ---------------------------------------------------------------------

    @Test
    void aRuleOnWhatAnInvoiceWasWhenCreatedFiresThoughItWasPaidBeforeTheConsumerRan() throws Exception {
        Long ruleId = saveRule(owen, rule("New and unpaid", "INVOICE", "ENTITY_CREATED",
                and(leaf("status:eq:UNPAID")), task("Chase {{Invoice.Number}}: {{Invoice.Balance}} left")));
        actAs(admin);
        Long fresh = invoice(acme, widget, 1, null);
        pay(acme, invoiceRepository.findById(fresh).orElseThrow().getTotal().toPlainString(), fresh);
        assertThat(invoiceRepository.findById(fresh).orElseThrow().getStatus().name()).isEqualTo("FULLY_PAID");

        drain();
        List<Task> tasks = tasksOf(ruleId);
        String number = invoiceRepository.findById(fresh).orElseThrow().getInvoiceNumber();
        assertThat(tasks).extracting(Task::getTitle)
                .as("matched as it was when created; the text says how it is now")
                .containsExactly("Chase " + number + ": " + Money.format(BigDecimal.ZERO) + " left");
    }

    @Test
    void eventsAreNeverMergedEachIsEvaluatedAtItsOwnMoment() throws Exception {
        Long eachUpdate = saveRule(owen, rule("Every update", "INVOICE", "ENTITY_UPDATED", null,
                everyTime(task("Look again"))));
        Long whileUnpaid = saveRule(owen, rule("While unpaid", "INVOICE", "ENTITY_UPDATED",
                and(leaf("status:eq:UNPAID")), everyTime(task("Still unpaid"))));
        touch("first");
        actAs(admin);
        pay(acme, "200.00", invoiceId);

        drain();
        assertThat(tasksOf(eachUpdate)).as("one for the edit, one for the payment").hasSize(2);
        assertThat(runsOf(eachUpdate)).extracting(AutomationRun::getEventId).doesNotHaveDuplicates().hasSize(2);
        assertThat(tasksOf(whileUnpaid)).as("unpaid at the edit, paid at the payment").hasSize(1);
    }

    @Test
    void aRuleSwitchedOnBetweenTwoChangesActsOnTheSecondOnly() throws Exception {
        Long ruleId = saveRule(owen, with(rule("Chase", "INVOICE", "ENTITY_UPDATED", null,
                everyTime(task("Chase"))), "enabled", false));
        touch("first");
        later(Duration.ofSeconds(1));
        send(post(RULES + "/" + ruleId + "/enable"), owen, Map.of()).andExpect(status().isOk());
        later(Duration.ofSeconds(1));
        touch("second");

        drain();
        assertThat(runsOf(ruleId)).singleElement()
                .satisfies(r -> assertThat(r.getEventId()).isEqualTo(onlyUpdate().getId()));
        assertThat(tasksOf(ruleId)).hasSize(1);
    }

    @Test
    void aRuleIsNotRunForTheChangeItsOwnActionMadeButIsForTheNextOneByAPerson() throws Exception {
        Long ruleId = chaseRule(everyTime(task("Chase")));
        actAs(admin);
        WriteOrigins.run(WriteOrigin.automation(ruleId, null, null, 1).withActor(owen.getId()),
                () -> invoiceService.update(invoiceId,
                        new InvoiceDtos.UpdateInvoiceRequest("by the rule", null, null, null, null)));
        AutomationEvent caused = onlyUpdate();
        assertThat(caused.getCausedByRuleId()).isEqualTo(ruleId);
        touch("by a person");

        drain();
        assertThat(runsOf(ruleId)).singleElement()
                .satisfies(r -> assertThat(r.getEventId()).isNotEqualTo(caused.getId()));
        assertThat(event(caused.getId()).getStatus()).isEqualTo(AutomationEvent.DONE);
    }
}
