package com.geneinvoice.automation;

import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The outbox itself (R1, R4, R8): that the row commits with the save and never without it, that a
 * consumer which is down or broken costs the user nothing, and that the same piece of work asked
 * for twice is done once.
 */
class AutomationOutboxTest extends AutomationTestBase {

    @Autowired TransactionTemplate transactions;

    /** The rule of the end-to-end tests, without the filters: every invoice matches it. */
    private AutomationRule chaseEveryInvoice() {
        return rule("Chase every invoice", AutomationEntityType.INVOICE, TriggerKind.UPDATED,
                List.of(), ActionType.CREATE_TASK,
                taskSpec("Chase this invoice", userToken(collections)));
    }

    /**
     * A rule the run cannot carry out and cannot describe either: the promised amount is wider than
     * {@code payment_promises.amount}, so the insert itself dies rather than the rule answering no.
     * That is what makes it the right vehicle for the retry ladder — an unexpected failure, not a
     * skip.
     *
     * <p>Written straight to the table rather than through the rules screen, because the authoring
     * check measures the amount now and refuses this one at the form (D-74). The shape is honest:
     * a failure that nothing at authoring time could have caught is, by then, a rule already in the
     * table from before the check existed, and the engine has to hold it exactly the same way.
     */
    private AutomationRule aRuleThatBlowsUpOnEveryRun() {
        return automationRuleRepository.save(AutomationRule.builder()
                .name("Promise the impossible")
                .enabled(true)
                .entityType(AutomationEntityType.INVOICE)
                .trigger(TriggerKind.UPDATED)
                .filtersJson("[]")
                .action(ActionType.CREATE_PROMISE)
                .actionJson("{\"body\":\"they said they would pay\",\"dueInDays\":7,"
                        + "\"amount\":99999999999999999}")
                .createdByUserId(admin.getId())
                .runCount(0L)
                .build());
    }

    // ---- the row commits with the save, or not at all ------------------------------

    /**
     * The outbox row goes in inside the caller's own transaction, so a save that rolls back leaves
     * no event behind: a consumer can never be handed the id of a record nobody else can see.
     */
    @Test
    void aSaveThatRollsBackLeavesNoOutboxRowBehind() {
        actAs(admin);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                    acme.getId(), null, null, sales.getId(),
                    List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("600.00")))));
            // Whatever goes wrong later in the user's transaction: the invoice never existed, so
            // neither may the row that says it was created.
            throw new IllegalStateException("the rest of the save failed");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("the rest of the save failed");

        assertThat(invoiceRepository.findAll()).isEmpty();
        assertThat(automationEventRepository.findAll()).isEmpty();
        assertThat(taskRepository.findAll()).isEmpty();
    }

    /**
     * What the save writes names only what happened. No rule is read on the user's thread — which
     * rules apply is decided on the consumer, against the rules as they stand when the work is
     * actually done (R2) — and that is visible in the row: it carries no rule at all.
     */
    @Test
    void theRowASaveWritesNamesWhatHappenedAndNoRule() throws Exception {
        Invoice inv = invoice("600.00", 1);
        chaseEveryInvoice();

        edit(inv, "chased");

        waitUntil(this::outboxSettled);
        assertThat(fanOutEvents()).filteredOn(e -> e.getTrigger() == TriggerKind.UPDATED)
                .singleElement().satisfies(e -> {
                    assertThat(e.getRuleId()).isNull();
                    assertThat(e.getEntityType()).isEqualTo(AutomationEntityType.INVOICE);
                    assertThat(e.getEntityId()).isEqualTo(inv.getId());
                    assertThat(e.getIdempotencyKey()).startsWith("*|INVOICE|" + inv.getId() + "|UPDATED|");
                });
    }

    /**
     * The consumer running the rule's action blows up, on the caller's own thread, and the caller
     * is not troubled by it: everything after the row is written is wrapped, because by then the
     * row is safe and the sweeper will come back to it (R8).
     */
    @Test
    void aConsumerThatBlowsUpDoesNotFailTheCallThatSetItOff() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule broken = aRuleThatBlowsUpOnEveryRun();

        // The very call an entity service makes as it saves. With no transaction open the work is
        // handed over inline, so the consumer's failure happens on this thread — and this thread
        // still returns normally.
        outbox.recordUpdated(AutomationEntityType.INVOICE, inv.getId());

        assertThat(promiseRepository.findAll()).isEmpty();
        assertThat(fanOutEvents()).filteredOn(e -> e.getTrigger() == TriggerKind.UPDATED)
                .singleElement().satisfies(e ->
                        assertThat(e.getStatus()).isEqualTo(AutomationEventStatus.DONE));
        assertThat(eventsOf(broken)).singleElement().satisfies(e -> {
            // Not settled as an error yet: it is an unexpected failure, so it has two more goes.
            assertThat(e.getStatus()).isEqualTo(AutomationEventStatus.QUEUED);
            assertThat(e.getAttempts()).isEqualTo(1);
            assertThat(e.getLastError()).isNotBlank();
        });
    }

    // ---- nothing is lost if the consumer is down -----------------------------------

    /**
     * The nudge after a commit is best-effort and always will be — the process can die between the
     * commit and the publish. The row is still in the table, so the sweep publishes it and the
     * action happens, late but not lost (R8).
     */
    @Test
    void nothingIsLostWhenNobodyEverPublishedTheEvent() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule chase = chaseEveryInvoice();
        // As the table looks after a save whose nudge never reached a worker.
        AutomationEvent orphan = unpublishedFanOut(AutomationEntityType.INVOICE, inv.getId(),
                TriggerKind.UPDATED);
        assertThat(tasksOn(inv)).isEmpty();

        scheduler.sweep(Instant.now());

        assertThat(tasksOn(inv)).singleElement().satisfies(t ->
                assertThat(t.getTitle()).isEqualTo("Chase this invoice"));
        assertThat(reload(orphan).getStatus()).isEqualTo(AutomationEventStatus.DONE);
        assertThat(eventsOf(chase)).singleElement().satisfies(e ->
                assertThat(e.getStatus()).isEqualTo(AutomationEventStatus.DONE));
    }

    /**
     * A row a nudge is still carrying must not be published again underneath it, or a worker and
     * the sweeper would both be holding the same piece of work: anything published inside the
     * republish window is left alone.
     */
    @Test
    void theSweepLeavesAloneARowThatIsAlreadyOnItsWayToAWorker() {
        Invoice inv = invoice("600.00", 1);
        chaseEveryInvoice();
        AutomationEvent inFlight = automationEventRepository.save(AutomationEvent.builder()
                .entityType(AutomationEntityType.INVOICE).entityId(inv.getId())
                .trigger(TriggerKind.UPDATED)
                .idempotencyKey(AutomationEvent.fanOutKey(AutomationEntityType.INVOICE, inv.getId(),
                        TriggerKind.UPDATED))
                .status(AutomationEventStatus.QUEUED)
                .enqueuedAt(Instant.now())
                .build());

        scheduler.sweep(Instant.now());

        assertThat(reload(inFlight).getStatus()).isEqualTo(AutomationEventStatus.QUEUED);
        assertThat(tasksOn(inv)).isEmpty();
        // Once the window has gone by with nobody having worked it, the sweep does publish it.
        scheduler.sweep(Instant.now().plus(AutomationScheduler.REPUBLISH_AFTER).plusSeconds(1));
        assertThat(reload(inFlight).getStatus()).isEqualTo(AutomationEventStatus.DONE);
        assertThat(tasksOn(inv)).hasSize(1);
    }

    /**
     * A worker that died half way through is NOT run again: the action may already have happened,
     * and a second task is worse than one nobody recorded. The row is closed as failed, with the
     * reason a person can act on (R8).
     */
    @Test
    void aRunThatDiedHalfWayIsClosedAsFailedAndNeverRunAgain() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule chase = chaseEveryInvoice();
        AutomationEvent claimed = ruleRow(chase, inv.getId(), Instant.now());
        claimed.setStatus(AutomationEventStatus.RUNNING);
        claimed.setAttempts(1);
        automationEventRepository.save(claimed);

        // Ten minutes after the worker last touched it, as far as this sweep is concerned.
        scheduler.sweep(Instant.now().plus(AutomationScheduler.STALE_RUNNING).plus(Duration.ofMinutes(1)));

        AutomationEvent after = reload(claimed);
        assertThat(after.getStatus()).isEqualTo(AutomationEventStatus.FAILED);
        assertThat(after.getLastError()).isEqualTo(
                "This run was interrupted; it may or may not have happened, so run the rule again to be sure");
        assertThat(tasksOn(inv)).isEmpty();
    }

    /**
     * Settled rows are dropped once they are past the keep-for, but a failed one stays: it is the
     * only record that something may not have happened, and nobody should have to find that out
     * from a log.
     */
    @Test
    void settledRowsArePurgedAfterTheKeepForAndAFailedOneIsKept() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule chase = chaseEveryInvoice();
        AutomationEvent done = settled(chase, inv.getId(), AutomationEventStatus.DONE, 1);
        AutomationEvent skipped = settled(chase, inv.getId() + 1, AutomationEventStatus.SKIPPED, 2);
        AutomationEvent failed = settled(chase, inv.getId() + 2, AutomationEventStatus.FAILED, 3);

        scheduler.sweep(Instant.now().plus(Duration.ofDays(40)));

        assertThat(automationEventRepository.findById(done.getId())).isEmpty();
        assertThat(automationEventRepository.findById(skipped.getId())).isEmpty();
        assertThat(automationEventRepository.findById(failed.getId())).isPresent();
    }

    private AutomationEvent settled(AutomationRule rule, Long entityId, AutomationEventStatus status, int n) {
        AutomationEvent row = ruleRow(rule, entityId, Instant.now().minusSeconds(n));
        row.setStatus(status);
        return automationEventRepository.save(row);
    }

    // ---- the same work asked for twice ----------------------------------------------

    /**
     * The unique index on the key is the whole answer to double delivery: the second ask finds the
     * work already queued and writes nothing, so one task exists and not two (R4).
     */
    @Test
    void theSameRuleWorkQueuedTwiceInOneDayIsOneRowAndOneTask() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule chase = chaseEveryInvoice();

        List<Long> first = outbox.enqueueForRule(chase, List.of(inv.getId()), MORNING);
        List<Long> second = outbox.enqueueForRule(chase, List.of(inv.getId()), MORNING.plus(Duration.ofHours(6)));

        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
        assertThat(eventsOf(chase)).hasSize(1);
        assertThat(tasksOn(inv)).hasSize(1);
    }

    /** The next UTC day is new work, because once a day is the line an UPDATED rule is drawn at. */
    @Test
    void theSameRuleWorkOnTheNextUtcDayIsNewWork() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule chase = chaseEveryInvoice();

        outbox.enqueueForRule(chase, List.of(inv.getId()), MORNING);
        outbox.enqueueForRule(chase, List.of(inv.getId()), MORNING.plus(Duration.ofDays(1)));

        assertThat(eventsOf(chase)).hasSize(2);
        assertThat(tasksOn(inv)).hasSize(2);
    }

    /**
     * A record is created once, so a CREATED rule's key has no bucket that moves: a second delivery
     * a month later is still the same piece of work and is dropped.
     */
    @Test
    void workForACreatedRuleIsNeverQueuedASecondTimeHoweverLongLater() throws Exception {
        Invoice inv = invoice("600.00", 1);
        // The invoice's own save is finished with before the rule exists, so the only work here is
        // the work this test queues by hand.
        waitUntil(this::outboxSettled);
        AutomationRule greet = rule("Greet every invoice", AutomationEntityType.INVOICE,
                TriggerKind.CREATED, List.of(), ActionType.CREATE_TASK,
                taskSpec("Check this new invoice", userToken(collections)));

        outbox.enqueueForRule(greet, List.of(inv.getId()), MORNING);
        outbox.enqueueForRule(greet, List.of(inv.getId()), MORNING.plus(Duration.ofDays(30)));

        assertThat(eventsOf(greet)).hasSize(1);
        assertThat(tasksOn(inv)).hasSize(1);
    }

    // ---- a run that did nothing does not hold the slot ------------------------------

    /**
     * The key is claimed when the work is QUEUED, and the WHERE is not looked at until it is RUN.
     * A record the filters refused this morning has to be askable again this afternoon, once
     * somebody has changed the very thing the rule was watching for: the bucket is there to
     * collapse repeated actions, and a non-match is not one. It used to hold the record's only slot
     * for that rule for the rest of the UTC day, so the first edit of the day decided the whole day
     * (D-72).
     */
    @Test
    void aRecordTheFiltersRefusedCanStillBeAskedAgainTheSameDay() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule chase = chaseWhatSomebodyAskedToBeChased();

        // Nothing to chase yet: the rule is asked about this invoice and answers no.
        assertThat(outbox.enqueueForRule(chase, List.of(inv.getId()), MORNING)).hasSize(1);
        assertThat(tasksOn(inv)).isEmpty();
        assertThat(eventsOf(chase)).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(AutomationEventStatus.SKIPPED);
            // The row stays — it is what the runs list shows — but its key stops claiming the day.
            assertThat(e.getIdempotencyKey()).isEqualTo(AutomationEvent.releasedKey(
                    AutomationEvent.ruleKey(chase.getId(), AutomationEntityType.INVOICE,
                            inv.getId(), TriggerKind.UPDATED, MORNING), e.getId()));
        });

        askToBeChased(inv);
        assertThat(outbox.enqueueForRule(chase, List.of(inv.getId()), MORNING.plus(Duration.ofHours(6))))
                .hasSize(1);

        assertThat(tasksOn(inv)).singleElement().satisfies(t ->
                assertThat(t.getTitle()).isEqualTo("Chase this invoice"));
        assertThat(eventsOf(chase)).hasSize(2);
    }

    /**
     * And a run that DID act still holds it. Two matches in one UTC day are one task, which is the
     * whole reason the bucket is in the key (R4) and the thing releasing a skip must not cost.
     */
    @Test
    void aRunThatActedStillHoldsTheSlotForTheRestOfTheDay() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule chase = chaseWhatSomebodyAskedToBeChased();
        askToBeChased(inv);

        assertThat(outbox.enqueueForRule(chase, List.of(inv.getId()), MORNING)).hasSize(1);
        assertThat(outbox.enqueueForRule(chase, List.of(inv.getId()), MORNING.plus(Duration.ofHours(6))))
                .isEmpty();

        assertThat(tasksOn(inv)).hasSize(1);
        assertThat(eventsOf(chase)).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(AutomationEventStatus.DONE);
            assertThat(e.getIdempotencyKey()).doesNotContain("skipped");
        });
    }

    /**
     * And the release does not let the double delivery the key was written for back in. A row holds
     * its key from the moment it is queued until the moment it says it did nothing, so the delivery
     * that acted keeps it and every later delivery of that same event finds it taken — even when an
     * earlier delivery of it skipped and gave the key up (R4).
     */
    @Test
    void anEventDeliveredAgainAfterASkipReleasedTheKeyStillRaisesOneTask() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule chase = chaseWhatSomebodyAskedToBeChased();
        AutomationEvent fanOut = unpublishedFanOut(AutomationEntityType.INVOICE, inv.getId(),
                TriggerKind.UPDATED);

        // Delivered once while the invoice says nothing: a rule row is written and it skips.
        worker.process(fanOut.getId(), Instant.now());
        assertThat(tasksOn(inv)).isEmpty();

        askToBeChased(inv);
        // The sweeper publishing that same row again, twice over.
        redeliver(fanOut);
        worker.process(fanOut.getId(), Instant.now());
        redeliver(fanOut);
        worker.process(fanOut.getId(), Instant.now());

        assertThat(tasksOn(inv)).hasSize(1);
        // The one that skipped, and the one that acted. The third delivery wrote nothing.
        assertThat(eventsOf(chase)).hasSize(2);
        assertThat(reload(fanOut).getLastError()).isEqualTo("Queued 0 of 1 rule(s)");
    }

    /** A rule that fires on a word somebody puts on the invoice, so the same record can stop and start matching. */
    private AutomationRule chaseWhatSomebodyAskedToBeChased() {
        return rule("Chase what somebody asked to be chased", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of("notes:contains:chase"), ActionType.CREATE_TASK,
                taskSpec("Chase this invoice", userToken(collections)));
    }

    /**
     * The invoice now says the thing the rule watches for. Written straight to the column: how it
     * came to say it is not what these tests are about, and going through the service would set a
     * second fan-out running on the background thread underneath the delivery being tested.
     */
    private void askToBeChased(Invoice inv) {
        Invoice changed = invoiceRepository.findById(inv.getId()).orElseThrow();
        changed.setNotes("chase this one");
        invoiceRepository.save(changed);
    }

    /** As if the sweeper had published a row again after the process carrying it died. */
    private void redeliver(AutomationEvent event) {
        AutomationEvent again = reload(event);
        again.setStatus(AutomationEventStatus.QUEUED);
        automationEventRepository.save(again);
    }

    /**
     * The row itself is claimed by a conditional update, so handing the same row to two workers —
     * a nudge and the sweeper reaching it together — runs it once. Losing the claim is the
     * ordinary case, not an error.
     */
    @Test
    void theSameOutboxRowDeliveredTwiceIsWorkedOnce() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule chase = chaseEveryInvoice();
        AutomationEvent queued = ruleRow(chase, inv.getId(), Instant.now());

        worker.process(queued.getId(), Instant.now());
        worker.process(queued.getId(), Instant.now());

        assertThat(tasksOn(inv)).hasSize(1);
        AutomationEvent after = reload(queued);
        assertThat(after.getStatus()).isEqualTo(AutomationEventStatus.DONE);
        assertThat(after.getAttempts()).isEqualTo(1);
    }

    /**
     * A fan-out row run twice writes no new rule rows either — it creates nothing itself, which is
     * what makes it safe to run twice (R2).
     */
    @Test
    void aFanOutRowWorkedTwiceQueuesTheRuleOnlyOnce() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule chase = chaseEveryInvoice();
        AutomationEvent fanOut = unpublishedFanOut(AutomationEntityType.INVOICE, inv.getId(),
                TriggerKind.UPDATED);

        worker.process(fanOut.getId(), Instant.now());
        // As if the sweeper had published it again after the worker's process died: the row is put
        // back to queued by hand, so the second pass really does redo the fan-out.
        AutomationEvent again = reload(fanOut);
        again.setStatus(AutomationEventStatus.QUEUED);
        automationEventRepository.save(again);
        worker.process(fanOut.getId(), Instant.now());

        assertThat(eventsOf(chase)).hasSize(1);
        assertThat(tasksOn(inv)).hasSize(1);
        assertThat(reload(fanOut).getLastError()).isEqualTo("Queued 0 of 1 rule(s)");
    }

    // ---- skipped, and failed ---------------------------------------------------------

    /**
     * The rule is read afresh when the work runs, not carried on the row, so a rule deleted in
     * between is a skip with the reason rather than an action taken under a rule that has gone.
     */
    @Test
    void workQueuedForARuleThatHasSinceBeenDeletedSkipsWithTheReason() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule chase = chaseEveryInvoice();
        AutomationEvent queued = ruleRow(chase, inv.getId(), Instant.now());
        automationRuleRepository.deleteById(chase.getId());

        worker.process(queued.getId(), Instant.now());

        AutomationEvent after = reload(queued);
        assertThat(after.getStatus()).isEqualTo(AutomationEventStatus.SKIPPED);
        assertThat(after.getLastError()).isEqualTo("This rule has been deleted");
        assertThat(tasksOn(inv)).isEmpty();
    }

    /** Switching a rule off stops the work already queued for it, not only the work to come. */
    @Test
    void workQueuedForARuleThatHasSinceBeenSwitchedOffSkips() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule chase = chaseEveryInvoice();
        AutomationEvent queued = ruleRow(chase, inv.getId(), Instant.now());
        chase.setEnabled(false);
        automationRuleRepository.save(chase);

        worker.process(queued.getId(), Instant.now());

        AutomationEvent after = reload(queued);
        assertThat(after.getStatus()).isEqualTo(AutomationEventStatus.SKIPPED);
        assertThat(after.getLastError()).isEqualTo("This rule is switched off");
        assertThat(tasksOn(inv)).isEmpty();
    }

    /**
     * A rule moved to another kind of record must not act on work queued under its old meaning:
     * the row says invoice, the rule now says payment, and the run says so rather than raising a
     * task on the wrong thing.
     */
    @Test
    void workQueuedBeforeARuleMovedToAnotherKindIsSkippedRatherThanActedOn() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule chase = chaseEveryInvoice();
        AutomationEvent queued = ruleRow(chase, inv.getId(), Instant.now());
        actAs(admin);
        automationService.update(chase.getId(), new AutomationDtos.UpdateRuleRequest(
                chase.getName(), null, true, "PAYMENT", "UPDATED", List.of(), "CREATE_TASK",
                taskSpec("Check this payment", userToken(collections))));

        worker.process(queued.getId(), Instant.now());

        AutomationEvent after = reload(queued);
        assertThat(after.getStatus()).isEqualTo(AutomationEventStatus.SKIPPED);
        assertThat(after.getLastError()).isEqualTo("This rule no longer watches invoices");
        assertThat(tasksOn(inv)).isEmpty();
    }

    /**
     * A record that has gone by the time the rule runs is a skip and not an error. The reason
     * names the filters, because the WHERE is asked first and a record that is not there matches
     * nothing — the "no longer exists" reason is for the narrower race where it goes between the
     * match and the action.
     */
    @Test
    void aRecordThatHasGoneByTheTimeTheRuleRunsIsSkippedAndNotFailed() {
        AutomationRule chase = chaseEveryInvoice();
        AutomationEvent queued = ruleRow(chase, 999_999L, Instant.now());

        worker.process(queued.getId(), Instant.now());

        AutomationEvent after = reload(queued);
        assertThat(after.getStatus()).isEqualTo(AutomationEventStatus.SKIPPED);
        assertThat(after.getLastError()).isEqualTo("The filters did not match this invoice");
        assertThat(taskRepository.findAll()).isEmpty();
    }

    /**
     * A failure the rule cannot describe is the app having a bad moment, so it is tried again — a
     * minute out and then five — and only then left FAILED for somebody to look at. Nobody may take
     * it before its wait is over, or a retry would be no wait at all.
     */
    @Test
    void anUnexpectedFailureIsRetriedTwiceAndThenLeftFailed() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule broken = aRuleThatBlowsUpOnEveryRun();
        // Whole milliseconds, so the wait the row is given reads back exactly as it was written.
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AutomationEvent queued = ruleRow(broken, inv.getId(), t0);

        worker.process(queued.getId(), t0);
        AutomationEvent first = reload(queued);
        assertThat(first.getStatus()).isEqualTo(AutomationEventStatus.QUEUED);
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getNextAttemptAt()).isEqualTo(t0.plus(Duration.ofMinutes(1)));
        // Cleared, so the sweeper publishes it again rather than leaving it to a nudge that has
        // already been and gone.
        assertThat(first.getEnqueuedAt()).isNull();

        // Too early: the wait is not over, so there is nothing to claim and nothing changes.
        worker.process(queued.getId(), t0.plus(Duration.ofSeconds(30)));
        assertThat(reload(queued).getAttempts()).isEqualTo(1);

        worker.process(queued.getId(), t0.plus(Duration.ofMinutes(2)));
        AutomationEvent second = reload(queued);
        assertThat(second.getStatus()).isEqualTo(AutomationEventStatus.QUEUED);
        assertThat(second.getAttempts()).isEqualTo(2);
        assertThat(second.getNextAttemptAt()).isEqualTo(t0.plus(Duration.ofMinutes(7)));

        worker.process(queued.getId(), t0.plus(Duration.ofMinutes(10)));
        AutomationEvent third = reload(queued);
        assertThat(third.getStatus()).isEqualTo(AutomationEventStatus.FAILED);
        assertThat(third.getAttempts()).isEqualTo(AutomationWorker.MAX_ATTEMPTS);
        assertThat(third.getNextAttemptAt()).isNull();
        assertThat(third.getLastError()).isNotBlank();
        assertThat(promiseRepository.findAll()).isEmpty();
    }

    /**
     * What a rule has been asked about lately, whatever came of it — the skips included, because a
     * skip is the ordinary answer and reads as information rather than as a fault.
     */
    @Test
    void theRunsListShowsEverythingTheRuleWasAskedAboutNewestFirst() {
        Invoice big = invoice("600.00", 1);
        Invoice small = invoice("100.00", 1);
        AutomationRule chase = rule("Chase big invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of("total:gt:500"), ActionType.CREATE_TASK,
                taskSpec("Chase this invoice", userToken(collections)));
        worker.process(ruleRow(chase, big.getId(), Instant.now()).getId(), Instant.now());
        worker.process(ruleRow(chase, small.getId(), Instant.now()).getId(), Instant.now());

        actAs(admin);
        List<AutomationDtos.RuleRunDto> runs = automationService.runs(chase.getId(), null);

        assertThat(runs).hasSize(2);
        assertThat(runs.get(0).entityId()).isEqualTo(small.getId());
        assertThat(runs.get(0).status()).isEqualTo(AutomationEventStatus.SKIPPED);
        assertThat(runs.get(0).lastError()).isEqualTo("The filters did not match this invoice");
        assertThat(runs.get(1).entityId()).isEqualTo(big.getId());
        assertThat(runs.get(1).status()).isEqualTo(AutomationEventStatus.DONE);
    }
}
