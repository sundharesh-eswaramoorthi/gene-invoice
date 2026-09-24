package com.geneinvoice.automation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.approval.ApprovalDtos;
import com.geneinvoice.approval.ApprovalService;
import com.geneinvoice.approval.PendingApprovalException;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.asof.AsOfSource;
import com.geneinvoice.common.query.ConditionNode;
import com.geneinvoice.common.query.Conditions;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.email.EmailDispatcher;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.email.RenderContext;
import com.geneinvoice.email.RoleResolver;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.region.RegionScope;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The consumer: claim, act and settle (A5).
 *
 * <p>THE TWO LINES THIS CLASS EXISTS FOR, both of whose failure modes are SILENT:
 * <ol>
 *   <li>the FENCE — {@code if (settled == 0) throw new ConcurrencyFailureException(FENCED)} in
 *       {@link #act}, which rolls tx2 back and takes the Task, Promise, Dispute or Email with it.
 *       Pinned by AutomationFenceTest#theLosingWorkerLeavesNoTaskBehindWhenItsSettleFindsNoRow.</li>
 *   <li>the HELD OUTCOME — {@link PendingApprovalException} caught OUTSIDE tx2 and settled
 *       {@code DONE / PENDING_CHANGE}, so an action over the branch's approval limit is a
 *       SUCCESSFUL terminal outcome and is never retried. Pinned by
 *       AutomationApprovalTest#anActionOverTheBranchesLimitSettlesDoneAsAPendingChangeAndIsNeverRetried.</li>
 * </ol>
 *
 * <p>It is EmailDispatcher transliterated, with its one bug fixed: every claim and every settle
 * takes ONE {@code now} and uses it for both the eligibility test and the timestamp, where
 * EmailDispatcher compares against a {@code due} it was handed and then writes a fresh
 * {@code Instant.now()}. The package-private {@code sweep/fanOut/perform/materialisePage}
 * overloads that take an {@code Instant} are THE ONLY TIME SEAM: there is no
 * {@code java.time.Clock} bean anywhere in this application and this unit introduces none.
 *
 * <p>NOTHING IS LOST IF THIS CLASS NEVER RUNS. The event row was committed inside the user's own
 * transaction by ChangeFeed, and {@link #sweep} is the guarantee; {@link #nudge} is only the
 * hurry. Turning {@code app.automation.async} off and lengthening the sweep interval stops the
 * engine acting without dropping a single fact (A5).
 */
@Component
@Slf4j
public class AutomationDispatcher implements AutomationNudge {

    /** Five tries and then the step is the dead letter — there is no dead-letter table (A5). */
    static final int MAX_ATTEMPTS = 5;

    static final String RULE_GONE = "The rule was turned off or removed";
    static final String RULE_CHANGED = "The rule changed before this ran";
    static final String NO_LONGER_MATCHES = "The record no longer matches";
    static final String SUBJECT_GONE = "The record this was about is no longer there";
    static final String ACTIONS_UNREADABLE = "The rule's actions could not be read";
    static final String RECLAIMED = "The worker holding this step stopped responding; it was reclaimed";
    static final String FENCED = "This step was reclaimed by another worker";
    static final String POISON_NOTIFICATION = "AUTOMATION_FAILED";

    private static final Duration STALE_RUNNING = Duration.ofMinutes(10);
    private static final Duration SETTLE = Duration.ofSeconds(30);
    private static final int SWEEP_BATCH = 200;
    private static final int FAN_OUT_PAGE = 500;
    private static final int FANNING_RUNS = 20;
    private static final Duration[] BACKOFF = {Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofHours(1)};

    /** The trigger kinds an event can arm; the clock kinds are A-SCHEDULE's producer (A1). */
    private static final List<TriggerKind> EVENT_KINDS = List.of(TriggerKind.ON_CREATED,
            TriggerKind.ON_UPDATED, TriggerKind.ON_CREATED_OR_UPDATED);

    private final AutomationEventRepository events;
    private final AutomationStepRepository steps;
    private final AutomationRunRepository runs;
    private final AutomationRuleRepository rules;
    private final AutomationActions actions;
    private final AutomationProperties properties;
    private final RuleRegions ruleRegions;
    private final EntitySources entitySources;
    private final ConditionJson conditionJson;
    // OUR OWN reader, deliberately not AutomationRuleService's: that one swallows a parse failure
    // to an empty list so a broken rule stays openable on screen, and acting on zero actions
    // silently is the one thing this path must never do (A3, A5).
    private final ObjectMapper objectMapper;
    private final TableQueryExecutor queryExecutor;
    private final CustomerRepository customers;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final RoleResolver roleResolver;
    private final ApprovalService approvalService;
    // The producer, injected into the CONSUMER, and only for {@link #parkAndSettle}: the park's
    // own audit row would otherwise come back round as a trigger for the rule that raised it
    // (A5, B2 INTEGRATION).
    private final ChangeFeed changeFeed;
    private final NotificationService notificationService;
    private final EmailDispatcher emailDispatcher;
    private final TransactionTemplate transactions;
    private final boolean async;

    private final ExecutorService background = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "automation");
        t.setDaemon(true);
        return t;
    });

    /** Step ids this JVM is already working on, so a sweep does not queue them a second time. */
    private final Set<Long> backgroundOwned = ConcurrentHashMap.newKeySet();

    public AutomationDispatcher(AutomationEventRepository events, AutomationStepRepository steps,
                                AutomationRunRepository runs, AutomationRuleRepository rules,
                                AutomationActions actions, AutomationProperties properties,
                                RuleRegions ruleRegions, EntitySources entitySources,
                                ConditionJson conditionJson,
                                ObjectMapper objectMapper,
                                TableQueryExecutor queryExecutor,
                                CustomerRepository customers, InvoiceRepository invoices,
                                PaymentRepository payments, RoleResolver roleResolver,
                                ApprovalService approvalService, ChangeFeed changeFeed,
                                NotificationService notificationService,
                                EmailDispatcher emailDispatcher, TransactionTemplate transactions,
                                @Value("${app.automation.async:true}") boolean async) {
        this.events = events;
        this.steps = steps;
        this.runs = runs;
        this.rules = rules;
        this.actions = actions;
        this.properties = properties;
        this.ruleRegions = ruleRegions;
        this.entitySources = entitySources;
        this.conditionJson = conditionJson;
        this.objectMapper = objectMapper;
        this.queryExecutor = queryExecutor;
        this.customers = customers;
        this.invoices = invoices;
        this.payments = payments;
        this.roleResolver = roleResolver;
        this.approvalService = approvalService;
        this.changeFeed = changeFeed;
        this.notificationService = notificationService;
        this.emailDispatcher = emailDispatcher;
        this.transactions = transactions;
        this.async = async;
    }

    @PreDestroy
    void shutdown() {
        background.shutdownNow();
    }

    // ---- the three ways in --------------------------------------------------------------------

    /**
     * Told which event ids have just committed. An OPTIMISATION and never the guarantee (A5).
     *
     * <p>THE INLINE ARM REFUSES TO WORK ON THE COMMITTING THREAD, and that is not timidity. This
     * is called from {@code TransactionSynchronization.afterCompletion}, and Spring clears
     * {@code actualTransactionActive} and unbinds the connection in {@code cleanupAfterCompletion}
     * — which runs AFTER the callbacks. Opening a transaction here would therefore JOIN the
     * transaction that has just finished, on a connection that is about to be released. The event
     * row is committed and the sweeper is 30 seconds away, so doing nothing is the correct answer
     * and not a lost fact (A5).
     */
    @Override
    public void nudge(List<Long> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) return;
        List<Long> ids = List.copyOf(eventIds);
        if (!async) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) return;
            ids.forEach(id -> drainQuietly(id, Instant.now()));
            return;
        }
        try {
            // The WORKER's body and not the submit call, exactly as EmailDispatcher does it: the
            // hatch is a ThreadLocal and is deliberately not inheritable. This body installs no
            // ambient of its own — every read below names its own narrowing reason (A5, B1).
            background.execute(() -> ids.forEach(id -> drainQuietly(id, Instant.now())));
        } catch (RejectedExecutionException e) {
            log.warn("Shutting down; {} automation event(s) stay queued for the sweeper", ids.size());
        }
    }

    /**
     * Catching up on whatever happened while this instance was down.
     *
     * <p>Posted to the daemon thread and deliberately NOT run inline like
     * {@code PromiseSweepScheduler.sweepOnStartup}, which executes on the startup thread — in
     * every Spring test context as well as in production. With the engine handed over inline
     * ({@code async: false}, the rollback lever and the test posture) there IS no daemon thread to
     * post to, and a sweep on the startup thread is the one thing this is avoiding, so it does
     * nothing at all and the scheduled sweeper is the catch-up (A5).
     */
    @EventListener(ApplicationReadyEvent.class)
    void catchUpOnStartup() {
        if (!async) return;
        try {
            background.execute(() -> sweepQuietly(Instant.now()));
        } catch (RejectedExecutionException e) {
            log.warn("Shutting down before the startup catch-up could run");
        }
    }

    public void sweep() {
        sweep(Instant.now());
    }

    /**
     * THE GUARANTEE (A5).
     *
     * <p>Reclaim the steps whose worker died holding them, fan out every event older than the
     * settle window, carry every half-finished run one page further, then work the due steps this
     * JVM is not already working on. Every query is paged and ordered by id.
     */
    void sweep(Instant now) {
        Instant staleBefore = now.minus(STALE_RUNNING);
        for (Long id : page(() -> steps.findStaleRunning(staleBefore, PageRequest.of(0, SWEEP_BATCH)))) {
            transactions.executeWithoutResult(s -> reclaim(id, staleBefore, now));
        }
        // The SETTLE window, and it is load-bearing: the event row is inserted in beforeCommit and
        // nudged in afterCompletion, so for a moment a committed-looking row exists that the
        // request thread is still about to take. A sweeper inside that window races it (A5).
        for (Long id : page(() -> events.findDue(now, now.minus(SETTLE), PageRequest.of(0, SWEEP_BATCH)))) {
            fanOutQuietly(id, now);
        }
        for (Long id : page(() -> runs.findFanning(PageRequest.of(0, FANNING_RUNS)))) {
            materialisePageQuietly(id, now);
        }
        int held = backgroundOwned.size();
        page(() -> steps.findDue(now, now.minus(SETTLE), PageRequest.of(0, SWEEP_BATCH + held)))
                .stream()
                .filter(id -> !backgroundOwned.contains(id))
                .limit(SWEEP_BATCH)
                .forEach(id -> performQuietly(id, now));
    }

    // ---- fan out ------------------------------------------------------------------------------

    /**
     * One event becomes zero or more steps.
     *
     * <p>The rules that apply are the rules as they are NOW: an event is a FACT ("this invoice
     * changed"), not a plan, so a rule edited between the save and the sweep is applied as edited
     * and nothing was promised in between (A1, A5).
     *
     * @return the ids of the steps this call actually planned, so a nudging thread can work them
     */
    List<Long> fanOut(Long eventId, Instant now) {
        outsideATransaction("An automation event must be fanned out outside a transaction");
        if (!Boolean.TRUE.equals(transactions.execute(s -> events.claim(eventId, now) == 1))) {
            return List.of();
        }
        try {
            List<Plan> plans = transactions.execute(s -> planEvent(eventId, now));
            List<Long> created = insert(plans);
            transactions.executeWithoutResult(s ->
                    events.settle(eventId, EventStatus.DONE, null, null, now));
            return created;
        } catch (RuntimeException e) {
            transactions.executeWithoutResult(s -> eventFailed(eventId, e, now));
            return List.of();
        }
    }

    private List<Plan> planEvent(Long eventId, Instant now) {
        AutomationEvent event = events.findById(eventId).orElse(null);
        if (event == null) return List.of();
        Subject subject = subjectOf(event.getSubjectType(), event.getSubjectId());
        // The subject is gone — a customer deleted between the save and the sweep. That is a
        // normal fan-out that matched nothing, not an error to retry for ever (A5).
        if (subject == null) return List.of();

        List<Plan> plans = new ArrayList<>();
        for (AutomationRule rule : rules.findArmed(event.getSubjectType(), EVENT_KINDS)) {
            if (!rule.getTriggerKind().armedBy(event.getChange())) continue;
            if (!matches(rule, event.getSubjectType(), event.getSubjectId(), now,
                    RegionScope.SystemReason.AUTOMATION_FANOUT, true)) {
                continue;
            }
            plans.addAll(plansFor(rule, "E" + eventId, event.getSubjectType(), event.getSubjectId(),
                    subject.customerId(), null, StepSource.EVENT));
        }
        return plans;
    }

    private void eventFailed(Long eventId, RuntimeException e, Instant now) {
        AutomationEvent event = events.findById(eventId).orElse(null);
        if (event == null || event.getStatus() != EventStatus.FANNING) return;
        String message = AutomationActions.fit(reasonOf(e), AutomationEvent.ERROR_MAX);
        if (event.getAttempts() >= MAX_ATTEMPTS) {
            // EventStatus has no POISONED and findDue reads FAILED as due again, so a terminal
            // failure has to be a status findDue does not select or the row is retried for ever.
            // The error stays on the row and this is logged loudly (A5).
            log.warn("Giving up on automation event {} after {} attempts: {}",
                    eventId, event.getAttempts(), message);
            events.settle(eventId, EventStatus.DONE, message, null, now);
            return;
        }
        events.settle(eventId, EventStatus.FAILED, message, backoffFrom(now, event.getAttempts()), now);
    }

    // ---- fan out, the many-record half --------------------------------------------------------

    /**
     * One page of a run's fan-out, resumable (A5).
     *
     * <p>The keyset cursor and the counters are advanced in the SAME transaction as the page's
     * inserts, so a crash halfway through 50,000 records resumes from the last committed page. A
     * page that was partly written by a transaction that then rolled back is replanned row by row
     * against {@code uk_step_occasion}, which is what makes replanning safe rather than doubling.
     */
    void materialisePage(Long runId, Instant now) {
        outsideATransaction("An automation run page must be planned outside a transaction");
        try {
            transactions.executeWithoutResult(s -> onePage(runId, now));
        } catch (DataIntegrityViolationException duplicate) {
            log.info("Run {} met a page it had already partly planned; replanning row by row", runId);
            resumePage(runId, now);
        }
    }

    private void onePage(Long runId, Instant now) {
        AutomationRun run = runs.findByIdForUpdate(runId).orElse(null);
        PlannedPage planned = planPage(run, now);
        if (planned == null) return;
        int created = planned.plans().isEmpty() ? 0
                : steps.saveAll(planned.plans().stream().map(AutomationDispatcher::entity).toList()).size();
        advance(run, planned, created);
    }

    private void resumePage(Long runId, Instant now) {
        PlannedPage planned = transactions.execute(s ->
                planPage(runs.findByIdForUpdate(runId).orElse(null), now));
        if (planned == null) return;
        int created = insert(planned.plans()).size();
        transactions.executeWithoutResult(s -> {
            AutomationRun run = runs.findByIdForUpdate(runId).orElse(null);
            if (run == null || run.getStatus() != RunStatus.FANNING) return;
            advance(run, planned, created);
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private PlannedPage planPage(AutomationRun run, Instant now) {
        if (run == null || run.getStatus() != RunStatus.FANNING) return null;
        AutomationRule rule = rules.findById(run.getRuleId()).orElse(null);
        if (rule == null || !rule.live()) {
            run.setStatus(RunStatus.FAILED);
            run.setError(RULE_GONE);
            run.setFinishedAt(now);
            runs.save(run);
            return null;
        }
        SubjectType type = rule.getSubjectType();
        Long cursor = run.getCursorSubjectId() == null ? 0L : run.getCursorSubjectId();
        // THE DATE IS OPENED AROUND THE POPULATION READ AND AROUND NOTHING ELSE (B3).
        //
        // This method runs INSIDE the transaction that inserts the page's steps, and
        // HistoryWriter.drain throws the moment a non-read-only transaction commits while an
        // as-of context is open — deliberately, because as-of is a read mode and a row dated from
        // a replayed past would make the past disagree with itself. So the run's own date is
        // opened for the query that finds the population and handed straight back, and every
        // write below it happens at the real now.
        List<Long> ids;
        try (AsOfContext.Handle replaying = replayHandle(run)) {
            AsOfSource<?> source = entitySources.forSubject(type);
            // null, and not AsOfContext.instant(): a replay's date is open on this very thread
            // for the population read, and the cooldown is the one clause that stays LIVE — the
            // run acts today, so what the rule has already done since the missed day is exactly
            // what must suppress it (A5, B3).
            List<PredicateFactory> scope = conditionScope(source, rule, type, now, true, null);
            scope.add((root, q, cb) -> cb.greaterThan(root.<Long>get("id"), cursor));
            // "id,asc" is what makes the paging deterministic: the executor adds its own id
            // tiebreak only when the primary sort is something else. On a mirror root `id` is the
            // BUSINESS id, so the keyset cursor still walks records and not versions (A5, B3).
            TableQuery query = TableQuery.parseUnpaged(source.schema(), "id,asc", List.of());
            ids = RegionScope.asRegions(ruleRegions.reachOf(rule),
                    RegionScope.SystemReason.AUTOMATION_FANOUT,
                    () -> queryExecutor.ids((Class) source.type(), source.schema(), query, scope,
                            FAN_OUT_PAGE));
        }

        Map<Long, Long> owners = ownersOf(type, ids);
        List<Plan> plans = new ArrayList<>();
        for (Long id : ids) {
            Long customerId = owners.get(id);
            if (customerId == null) continue;
            plans.addAll(plansFor(rule, run.getOccasion(), type, id, customerId, run.getId(),
                    run.getSource()));
        }
        return new PlannedPage(ids, plans, cursor, ids.size() < FAN_OUT_PAGE);
    }

    private void advance(AutomationRun run, PlannedPage page, int created) {
        if (!page.ids().isEmpty()) {
            run.setCursorSubjectId(page.ids().get(page.ids().size() - 1));
        }
        run.setMatched(run.getMatched() + page.ids().size());
        run.setStepsPlanned(run.getStepsPlanned() + created);
        if (run.getStepsPlanned() >= properties.maxStepsPerRun()) {
            // SHOWN on the run and never swallowed: a rule that stopped at the cap did not finish
            // and the person reading the history has to know (A5).
            run.setTruncated(true);
            run.setStatus(RunStatus.RUNNING);
        } else if (page.lastPage()) {
            run.setStatus(RunStatus.RUNNING);
        }
        runs.save(run);
    }

    // ---- perform ------------------------------------------------------------------------------

    /**
     * THE THREE BOUNDARIES (A5).
     *
     * <p>tx1 claims ALONE, so the attempt count and the RUNNING status survive a failure and are
     * what drive the backoff. tx2 is THE DOMAIN WRITE AND THE SETTLE TOGETHER, because every
     * automation action is a row insert and never an outbound call — EmailDispatcher needs three
     * transactions only because its act is an HTTP call. tx3 writes down what went wrong.
     */
    void perform(Long stepId, Instant now) {
        outsideATransaction("An automation step must be run outside a transaction");
        String token = UUID.randomUUID().toString().replace("-", "");
        if (!Boolean.TRUE.equals(transactions.execute(s -> claimIfReady(stepId, token, now)))) return;

        Long emailId;
        try {
            emailId = transactions.execute(s -> act(stepId, token, now));
        } catch (PendingApprovalException held) {
            // OUTSIDE tx2 on purpose: tx2 has rolled back with nothing written and no pending row,
            // and the park and the settle then happen together in a transaction of their own, so a
            // step reclaimed during tx2 cannot leave an orphan change behind (A5, B2).
            try {
                transactions.executeWithoutResult(s -> parkAndSettle(stepId, token, held, now));
            } catch (ConcurrencyFailureException fenced) {
                log.info("Step {} was reclaimed while its change was being held; the winner holds it", stepId);
            }
            return;
        } catch (RuntimeException e) {
            transactions.executeWithoutResult(s -> failed(stepId, token, e, now));
            return;
        }
        if (emailId == null) return;
        // Outside every transaction, exactly as EmailService.send does it. A crash here leaves the
        // email QUEUED and EmailRepository.findDue picks it up 30 seconds later (A5).
        try {
            emailDispatcher.dispatch(emailId);
        } catch (RuntimeException e) {
            log.warn("Email {} stayed queued; its own sweeper will send it", emailId, e);
        }
    }

    /**
     * THE ORDERING GATE, read before the claim and not baked into it.
     *
     * <p>A step whose earlier sibling in the same {@code (occasion, rule, subject)} group has not
     * finished answers "not yet" and is NOT claimed — not a failure, and the sweeper takes it on
     * the next tick. That is how "create the task, THEN email about it" holds across instances
     * without an EXISTS inside the conditional UPDATE (A3, A5).
     */
    private boolean claimIfReady(Long stepId, String token, Instant now) {
        AutomationStep step = steps.findById(stepId).orElse(null);
        if (step == null || step.getStatus() != StepStatus.QUEUED) return false;
        if (step.getNextAttemptAt() != null && step.getNextAttemptAt().isAfter(now)) return false;
        if (steps.existsEarlierUnfinished(step.getOccasion(), step.getRuleId(), step.getSubjectId(),
                step.getActionIndex())) {
            return false;
        }
        // THE SWEEP'S now DECIDES ELIGIBILITY; THE WALL CLOCK STAMPS THE CLAIM (A5).
        //
        // This class's own javadoc calls EmailDispatcher's fresh Instant.now() its one bug, and
        // for the eligibility test that is right — one instant for what was selected and what is
        // claimed. But claimed_at is not an eligibility test, it is the liveness clock
        // findStaleRunning reads, and a sweep threads ONE now through up to 200 serial steps: a
        // sweep that runs longer than STALE_RUNNING (200 SEND_EMAIL steps against a degraded mail
        // service will) would record step 150 as claimed at minute 0 and let the next reclaimer —
        // even this same JVM's other thread — take it away from a worker that is still inside
        // tx2, rolling its work back and burning one of its five attempts.
        return steps.claim(stepId, now, Instant.now(), token) == 1;
    }

    /** @return the id of an email to dispatch after this transaction commits, or null */
    private Long act(Long stepId, String token, Instant now) {
        AutomationStep step = steps.findById(stepId).orElse(null);
        if (step == null) return null;
        AutomationRule rule = rules.findById(step.getRuleId()).orElse(null);
        // enabled is read LIVE because "stop doing this" has to mean now; the VERSION is read
        // frozen because an edit does not retroactively rewrite work already promised (A1, A5).
        if (rule == null || !rule.live()) return settleSkip(step, token, RULE_GONE, now);
        if (rule.getDefinitionVersion() != step.getRuleVersion()) {
            return settleSkip(step, token, RULE_CHANGED, now);
        }

        // The publish-to-consume gap: an invoice paid between the save and the act. COUNT and not
        // inScope, because inScope short-circuits to true for an empty scope and would answer
        // without running any SQL at all (A2, A5).
        //
        // EVALUATE AS-OF, ACT NOW. For a REPLAY this same guard is the whole of the trap B3 names:
        // the population was matched as it stood on a past day and the action is about to happen
        // today, so you do not email a customer about an invoice they paid yesterday. The guard is
        // re-run LIVE — nothing is open on this thread — and a step that no longer holds is
        // settled SKIPPED with the date it was matched as of written into its result, so a reader
        // of the history can tell a stale replay from an ordinary race (A5, B3).
        LocalDate evaluatedAsOf = asOfOf(step);
        if (!matches(rule, step.getSubjectType(), step.getSubjectId(), now,
                RegionScope.SystemReason.AUTOMATION_ACT, false)) {
            return settleSkip(step, token, staleOrPlain(evaluatedAsOf), now);
        }

        List<ActionSpec> specs = readActions(rule);
        if (specs == null) return settleSkip(step, token, ACTIONS_UNREADABLE, now);
        if (step.getActionIndex() >= specs.size()) return settleSkip(step, token, RULE_CHANGED, now);
        Subject subject = subjectOf(step.getSubjectType(), step.getSubjectId());
        if (subject == null) return settleSkip(step, token, SUBJECT_GONE, now);

        Set<Long> reach = ruleRegions.reachOf(rule);
        EmailEntityType type = step.getSubjectType().emailType();
        // ONE snapshot per step: one read of the POC book, region-checked inside snapshot itself,
        // shared by every placeholder and every role token this action names (A4).
        EmailTargets.Target target = RegionScope.asRegions(reach,
                RegionScope.SystemReason.AUTOMATION_ACT,
                () -> roleResolver.snapshot(type, step.getSubjectId()));
        // THE RECORD IS TODAY'S AND ONLY THE DATE ARITHMETIC IS THE RUN'S. subject.entity() was
        // read live a few lines above, so a replayed action quotes today's balance and today's
        // status; evaluatedAsOf is what "days overdue" counts from, so a replayed run produces the
        // dates it produced the first time (A4, A5, B3).
        RenderContext render = RenderContext.of(type, subject.entity(), subject.customer(),
                target, evaluatedAsOf);

        AutomationActions.Outcome outcome = actions.perform(new AutomationActions.Act(
                rule, step, specs.get(step.getActionIndex()), reach, render));
        if (outcome.skipped()) return settleSkip(step, token, outcome.skipReason(), now);

        int settled = steps.settle(stepId, StepStatus.DONE, describe(outcome),
                outcome.producedType(), outcome.producedId(), csv(outcome.unresolved()), now, token);
        // ======================================================================================
        // THE SINGLE MOST IMPORTANT LINE IN PART A (A5).
        //
        // 0 means a sweeper reclaimed this step while we were working and somebody else now owns
        // it. Throwing rolls THIS transaction back — and because the domain write above happened
        // in the very same transaction, the Task, Promise, Dispute or Email we just wrote goes
        // with it. The winner's copy is the only one.
        //
        // A refactor that "tidies up" by committing the domain write in its own inner transaction
        // silently un-fences this and produces two tasks, with nothing failing and nothing logged.
        // AutomationFenceTest#theLosingWorkerLeavesNoTaskBehindWhenItsSettleFindsNoRow is the test
        // that goes red; it is load-bearing and it must not be weakened.
        // ======================================================================================
        if (settled == 0) throw new ConcurrencyFailureException(FENCED);
        return outcome.emailId();
    }

    /**
     * "The record no longer matches", and for a replay the date it DID match as of. One sentence
     * rather than a second StepStatus: SKIPPED already means "this correctly did not happen" and
     * carries its reason, and splitting it would make a replay's ordinary outcome look like a
     * different kind of event in every list, filter and count that reads the status (A5, B3).
     */
    private static String staleOrPlain(LocalDate evaluatedAsOf) {
        return evaluatedAsOf == null || !evaluatedAsOf.isBefore(InvoiceDates.todayForWrite())
                ? NO_LONGER_MATCHES
                : NO_LONGER_MATCHES + "; it was matched as of " + evaluatedAsOf
                        + " and this was a replay";
    }

    private Long settleSkip(AutomationStep step, String token, String reason, Instant now) {
        int settled = steps.settle(step.getId(), StepStatus.SKIPPED,
                AutomationActions.fit(reason, AutomationStep.RESULT_MAX), null, null, null, now, token);
        if (settled == 0) throw new ConcurrencyFailureException(FENCED);
        return null;
    }

    /**
     * A HELD ACTION IS A SUCCESS (A5, B2).
     *
     * <p>The gate threw out of the mutator, tx2 rolled back with nothing written, and the unsaved
     * change is now written down and the step settled DONE against PENDING_CHANGE in ONE
     * transaction. The run reads "3 created, 2 awaiting approval" and the step is never retried,
     * because retrying it would raise the same change again for ever.
     *
     * <p>THE KNOWN HOLE, written into the step's own result line so a reader meets it where it
     * matters: a change nobody ever approves never happens, while this history says the step
     * succeeded. The approvals queue is where that is visible, not here.
     *
     * <p>AND THE PARK IS CUT OUT OF THE CHANGE FEED, which is what stops this being a loop rather
     * than an outcome. {@code ApprovalService.park} audits, and it must — that row is the whole
     * approval trail for a save that rolled back. But a create-shaped change has no target, so the
     * audit anchors on the ACCOUNT, and the audit chokepoint turns a CUSTOMER anchor into a
     * CUSTOMER/UPDATED fact. That fact re-arms this very rule: it still matches, the gate still
     * holds, the park audits again, and a customer rule with a gated action grows one pending
     * change, one step, one audit row and one notification to every approver in the branch per
     * sweep, without bound. The audit stays; only its re-entry as a TRIGGER is cut, because
     * nothing about the record changed — a request about it was written down (A5, B2 INTEGRATION).
     */
    private void parkAndSettle(Long stepId, String token, PendingApprovalException held, Instant now) {
        ApprovalDtos.Accepted parked =
                changeFeed.withoutTriggers(() -> approvalService.park(held.change(), null));
        String result = "Waiting for approval (change #" + parked.pendingChangeId() + "): "
                + parked.summary();
        int settled = steps.settle(stepId, StepStatus.DONE,
                AutomationActions.fit(result, AutomationStep.RESULT_MAX),
                ProducedType.PENDING_CHANGE, parked.pendingChangeId(), null, now, token);
        if (settled == 0) throw new ConcurrencyFailureException(FENCED);
    }

    /** tx3: what went wrong, and whether it is worth trying again (A5). */
    private void failed(Long stepId, String token, RuntimeException e, Instant now) {
        // The step is not ours any more: the winner will settle it and we must not touch it (A5).
        if (e instanceof ConcurrencyFailureException) return;
        AutomationStep step = steps.findById(stepId).orElse(null);
        if (step == null || step.getStatus() != StepStatus.RUNNING
                || !token.equals(step.getClaimToken())) {
            return;
        }
        String message = AutomationActions.fit(reasonOf(e), AutomationStep.RESULT_MAX);
        if (permanent(e)) {
            // SKIPPED and never retried, mirroring BulkExecutor.eligibility turning a
            // BadRequestException into "skipped, ineligible" rather than "failed" (A5).
            step.setStatus(StepStatus.SKIPPED);
            step.setFinishedAt(now);
            step.setNextAttemptAt(null);
        } else if (step.getAttempts() >= MAX_ATTEMPTS) {
            step.setStatus(StepStatus.POISONED);
            step.setFinishedAt(now);
            step.setNextAttemptAt(null);
            tellTheAuthor(step, message);
        } else {
            step.setStatus(StepStatus.QUEUED);
            step.setNextAttemptAt(backoffFrom(now, step.getAttempts()));
        }
        step.setResult(message);
        steps.save(step);
    }

    /**
     * The rule's OWNER, and deliberately not {@code notifyAdmins}: that resolves the literal role
     * name "ADMIN", with no region and no active filter, which is the thing AUTH-05 moved away
     * from. The person who wrote the rule is the person who can fix it (A5).
     */
    private void tellTheAuthor(AutomationStep step, String message) {
        AutomationRule rule = rules.findById(step.getRuleId()).orElse(null);
        if (rule == null || rule.getCreatedByUserId() == null) return;
        notificationService.notify(rule.getCreatedByUserId(), POISON_NOTIFICATION,
                "An automation rule stopped working",
                rule.getName() + " gave up after " + step.getAttempts() + " attempts: " + message,
                "/automation/rules/" + rule.getId());
    }

    /**
     * A step whose worker died holding it goes back on the queue — and its TOKEN IS CLEARED, which
     * is the other half of the fence: the worker that is still running will find no row to settle
     * and will roll its own work away (A5).
     */
    private void reclaim(Long stepId, Instant staleBefore, Instant now) {
        AutomationStep step = steps.findById(stepId).orElse(null);
        if (step == null || step.getStatus() != StepStatus.RUNNING
                || step.getClaimedAt() == null || !step.getClaimedAt().isBefore(staleBefore)) {
            return;
        }
        step.setStatus(StepStatus.QUEUED);
        step.setClaimToken(null);
        step.setNextAttemptAt(backoffFrom(now, step.getAttempts()));
        step.setResult(AutomationActions.fit(RECLAIMED, AutomationStep.RESULT_MAX));
        steps.save(step);
    }

    // ---- matching -----------------------------------------------------------------------------

    /**
     * What a dry run found: the true figures, and the head of the list it would work through (A5).
     */
    record Prospect(long matched, boolean truncated, List<Long> sample) {}

    /**
     * WHAT WOULD THIS RULE DO, AND NOTHING AT ALL BESIDES (A5).
     *
     * <p>It lives HERE rather than in the service that serves the endpoint, and deliberately: the
     * region hatch, the condition tree and the cooldown are one composed query, and a second copy
     * of it in another file is a second answer that would drift from the one the real fan-out
     * uses. "This is what would happen" and "this is what happened" have to be the same SQL or
     * the dry run is worth nothing (A2, A5, B1).
     *
     * <p>The figures are the rule's OWN reach and not the caller's: a dry run answers what the
     * rule would do, not what this reader may see. The SAMPLE is bounded by the caller's own gate
     * instead, back in {@code AutomationRuleService}, so a rendered customer name can never cross
     * a branch (A5, B1, AUTH-08).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    Prospect wouldMatch(AutomationRule rule, Instant now, int sampleSize) {
        SubjectType type = rule.getSubjectType();
        // Live this is the live table and an empty interval clause, so nothing about a dry run
        // changes. Under an open as-of context — which only POST /api/automation/rules/{id}/simulate
        // opens — it is the mirror, its twin schema and AsOf.at(T), and this method becomes the
        // backtest with no second body to keep in step (A5, B3).
        AsOfSource<?> source = entitySources.forSubject(type);
        // AND THE COOLDOWN WINDOW IS CLOSED AT THE ASKED-FOR DAY (B3). automation_steps is not a
        // mirrored table, so the subquery reads TODAY'S rows whatever instant its lower bound is
        // anchored at: without this, a step this rule finished in February suppresses a record in
        // a backtest of January, about work that had not happened yet on the day being asked
        // about. Null live, where "so far" is the right end of the window and the acting paths —
        // planPage's replay included — keep their live cooldown deliberately ("evaluate as-of,
        // act now"): a replay must not email somebody the rule chased yesterday.
        Instant until = AsOfContext.isActive() ? AsOfContext.instant() : null;
        List<PredicateFactory> scope = conditionScope(source, rule, type, now, true, until);
        // "id,asc" for the same reason the real fan-out pages that way: the sample is the head of
        // the list the run would work through, not an arbitrary twenty of it (A5).
        TableQuery query = TableQuery.parseUnpaged(source.schema(), "id,asc", List.of());
        // Measured in STEPS and not in records, because that is what the cap counts: a rule with
        // three actions runs out of room three times as fast, and a dry run that said otherwise
        // would promise work the real run would stop short of (A5).
        List<ActionSpec> specs = readActions(rule);
        int perRecord = specs == null ? 0 : specs.size();
        return RegionScope.asRegions(ruleRegions.reachOf(rule),
                RegionScope.SystemReason.AUTOMATION_FANOUT, () -> {
                    long matched = queryExecutor.count((Class) source.type(), source.schema(),
                            query, scope);
                    List<Long> head = sampleSize <= 0 ? List.<Long>of()
                            : queryExecutor.ids((Class) source.type(), source.schema(), query,
                                    scope, sampleSize);
                    boolean truncated = perRecord > 0
                            && matched * perRecord > properties.maxStepsPerRun();
                    return new Prospect(matched, truncated, head);
                });
    }

    /**
     * Does this ONE record match this rule, right now?
     *
     * <p>{@code count(id:eq:N + the tree + the cooldown)} through the very executor the record's
     * own list screen uses, inside the narrowing hatch, so the rule's regions bound the answer and
     * an empty reach answers no rather than everything (A2, A5, B1).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean matches(AutomationRule rule, SubjectType type, Long subjectId, Instant now,
                            RegionScope.SystemReason why, boolean withCooldown) {
        // NO as-of context is ever open here on the acting path, and that is the whole of
        // "evaluate as-of, act now": the sweeper works on its own thread, AsOfContext is not
        // inheritable, and planPage gave the date back before this step was ever committed. So a
        // replayed step's guard is re-checked against LIVE state before anything happens to a
        // customer (A5, B3).
        AsOfSource<?> source = entitySources.forSubject(type);
        List<PredicateFactory> scope = conditionScope(source, rule, type, now, withCooldown, null);
        TableQuery query = TableQuery.parseUnpaged(source.schema(), null,
                List.of("id:eq:" + subjectId));
        try {
            return RegionScope.asRegions(ruleRegions.reachOf(rule), why, () ->
                    queryExecutor.count((Class) source.type(), source.schema(), query, scope) > 0);
        } catch (BadRequestException e) {
            // A column can stop being filterable between the save and the run. One rule that can
            // no longer be evaluated must not take the whole sweep down with it (A2, A5).
            log.warn("Rule {} could not be evaluated against {} {}: {}",
                    rule.getId(), type, subjectId, e.getMessage());
            return false;
        }
    }

    /**
     * THE RULE'S OWN QUERY, over whichever root {@link EntitySources} is answering from (A2, B3).
     *
     * <p>The interval clause LEADS the list under an open as-of context and is absent live, which
     * is the only difference between the two paths: the condition tree is compiled against the
     * source's schema — the live one, or the twin whose columns are the same ColumnDef objects —
     * so every operator, coercion, relative date and custom resolver behaves identically. Without
     * {@code AsOf.at(T)} at the head, a rule asked as of January would match every VERSION of
     * every record (B3).
     */
    private List<PredicateFactory> conditionScope(AsOfSource<?> source, AutomationRule rule,
                                                  SubjectType type, Instant now,
                                                  boolean withCooldown, Instant cooldownUntil) {
        List<PredicateFactory> scope = new ArrayList<>(source.scope());
        ConditionNode tree = conditionJson.parse(rule.getConditionJson());
        PredicateFactory condition = Conditions.factory(tree, source.schema());
        // GUARD THE NULL FACTORY: TableQueryExecutor skips a factory that RETURNS null and NPEs on
        // a null factory in the list, and a rule with no conditions produces exactly that (A2).
        if (condition != null) scope.add(condition);
        if (withCooldown && rule.getCooldownDays() != null && rule.getCooldownDays() > 0) {
            scope.add(cooldown(rule.getId(), type,
                    now.minus(Duration.ofDays(rule.getCooldownDays())), cooldownUntil));
            // LIVE ONLY, and deliberately not in a backtest: a step that is queued today did not
            // exist on the as-of day and cannot have suppressed anything on it (A5, B3).
            if (cooldownUntil == null) scope.add(inFlight(rule.getId(), type));
        }
        return scope;
    }

    /**
     * Suppression as one more predicate rather than as a second engine: has this rule already
     * finished a step about this record inside the window? It composes with the conditions instead
     * of being applied to their answer afterwards (A1, A5).
     */
    private static PredicateFactory cooldown(Long ruleId, SubjectType type, Instant since,
                                             Instant until) {
        return (root, q, cb) -> {
            Subquery<Long> sq = q.subquery(Long.class);
            var prior = sq.from(AutomationStep.class);
            sq.select(cb.literal(1L));
            Predicate window = cb.and(
                    cb.equal(prior.get("ruleId"), ruleId),
                    cb.equal(prior.get("subjectType"), type),
                    cb.equal(prior.get("subjectId"), root.get("id")),
                    cb.equal(prior.get("status"), StepStatus.DONE),
                    cb.greaterThan(prior.<Instant>get("finishedAt"), since));
            // The window is OPEN at the top live — "since then, and ever since" — and CLOSED at
            // the as-of boundary in a backtest, where a step finished after that day had not
            // happened yet and cannot have suppressed anything on it (A1, A5, B3).
            if (until != null) {
                window = cb.and(window, cb.lessThanOrEqualTo(prior.<Instant>get("finishedAt"), until));
            }
            sq.where(window);
            return cb.not(cb.exists(sq));
        };
    }

    /**
     * THE OTHER HALF OF THE COOLDOWN, and without it a cooldown cannot suppress a BACKLOG (A5).
     *
     * <p>{@link #cooldown} counts only steps that have already reached DONE, and {@link #sweep}
     * fans out EVERY due event before it performs any step — while {@code findDue}'s settle window
     * guarantees that a step planned in a pass cannot be worked in that pass. So eight events
     * about one invoice, queued while the consumer was down or lagging, all fan out against zero
     * DONE steps, all plan a step under their own occasion (so {@code uk_step_occasion} does not
     * collide), and the next tick performs all eight — from a rule whose own editor says "once
     * every N days per record". A customer gets eight chase emails in a minute.
     *
     * <p>A step that is QUEUED or RUNNING will finish AFTER now, so it will land inside any window
     * measured from now: an in-flight step is a finished one that has not got there yet, and it
     * suppresses on exactly the same grounds. It is a SECOND predicate rather than an OR inside
     * the first because it is not date-bounded and must not be: the window is about when the rule
     * last acted, and this is about the rule acting right now.
     *
     * <p>DELIBERATELY NOT FIXED BY FLIPPING {@code act()}'s {@code withCooldown} to true. That
     * flag is load-bearing for an ordered multi-action rule: {@code claimIfReady} holds action 1
     * until action 0 is finished, so by the time action 1 acts, action 0 is DONE inside the window
     * and a cooldown re-checked there would suppress every action after the first (A3, A5).
     */
    private static PredicateFactory inFlight(Long ruleId, SubjectType type) {
        return (root, q, cb) -> {
            Subquery<Long> sq = q.subquery(Long.class);
            var working = sq.from(AutomationStep.class);
            sq.select(cb.literal(1L));
            sq.where(cb.and(
                    cb.equal(working.get("ruleId"), ruleId),
                    cb.equal(working.get("subjectType"), type),
                    cb.equal(working.get("subjectId"), root.get("id")),
                    cb.or(cb.equal(working.get("status"), StepStatus.QUEUED),
                            cb.equal(working.get("status"), StepStatus.RUNNING))));
            return cb.not(cb.exists(sq));
        };
    }

    // ---- the rows -----------------------------------------------------------------------------

    private List<Plan> plansFor(AutomationRule rule, String occasion, SubjectType type,
                                Long subjectId, Long customerId, Long runId, StepSource source) {
        List<ActionSpec> specs = readActions(rule);
        if (specs == null || specs.isEmpty()) {
            // A rule whose actions will not parse cannot be planned. There is no step to settle
            // yet, so this is the one place it can only be logged (A3, A5).
            if (specs == null) log.warn("Rule {} has actions that will not parse", rule.getId());
            return List.of();
        }
        List<Plan> plans = new ArrayList<>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            plans.add(new Plan(AutomationActions.fit(occasion, AutomationStep.OCCASION_MAX),
                    rule.getId(), rule.getName(), rule.getDefinitionVersion(), i, specs.get(i).kind(),
                    type, subjectId, customerId, runId, source));
        }
        return plans;
    }

    /**
     * Plan the page, and let the DATABASE decide what is new.
     *
     * <p>Duplicate recovery is Java and not SQL: no {@code on conflict do nothing}, which is
     * Postgres only, and no {@code merge into}, which H2 spells differently. Both dialects enforce
     * {@code uk_step_occasion} identically, which is the whole reason this works (A5).
     */
    private List<Long> insert(List<Plan> plans) {
        if (plans == null || plans.isEmpty()) return List.of();
        try {
            return transactions.execute(s -> steps
                    .saveAll(plans.stream().map(AutomationDispatcher::entity).toList())
                    .stream().map(AutomationStep::getId).toList());
        } catch (DataIntegrityViolationException duplicate) {
            List<Long> created = new ArrayList<>();
            for (Plan plan : plans) {
                try {
                    // A FRESH entity each time: the failed batch left ids on the old ones, and a
                    // saved-then-rolled-back entity would be merged rather than inserted (A5).
                    Long id = transactions.execute(s -> steps.save(entity(plan)).getId());
                    if (id != null) created.add(id);
                } catch (DataIntegrityViolationException already) {
                    log.debug("Step {} action {} of rule {} was already planned",
                            plan.occasion(), plan.actionIndex(), plan.ruleId());
                }
            }
            return created;
        }
    }

    private static AutomationStep entity(Plan plan) {
        return AutomationStep.builder()
                .occasion(plan.occasion())
                .ruleId(plan.ruleId())
                .ruleName(plan.ruleName())
                .ruleVersion(plan.ruleVersion())
                .actionIndex(plan.actionIndex())
                .actionKind(plan.actionKind())
                .subjectType(plan.subjectType())
                .subjectId(plan.subjectId())
                .customerId(plan.customerId())
                .runId(plan.runId())
                .source(plan.source())
                .status(StepStatus.QUEUED)
                .build();
    }

    /** @return null when the stored actions will not parse, which is a skip and not an empty rule */
    private List<ActionSpec> readActions(AutomationRule rule) {
        String stored = rule.getActionsJson();
        if (stored == null || stored.isBlank()) return List.of();
        try {
            return objectMapper.readValue(stored, new TypeReference<List<ActionSpec>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    // ---- subjects -----------------------------------------------------------------------------

    /** The record a step is about, read WITHOUT a principal — the region bound is the hatch (A5). */
    private Subject subjectOf(SubjectType type, Long id) {
        return switch (type) {
            case CUSTOMER -> customers.findById(id).map(c -> new Subject(c, c)).orElse(null);
            case INVOICE -> invoices.findById(id)
                    .map(i -> new Subject(i, i.getCustomer())).orElse(null);
            case PAYMENT -> payments.findById(id)
                    .map(p -> new Subject(p, p.getCustomer())).orElse(null);
        };
    }

    /**
     * The account behind each record of a page, in ONE query rather than one per row.
     *
     * <p>{@code automation_steps.customer_id} is NOT NULL and it IS the region axis, so a step
     * that could not name an account must not be planned at all (A5, B1).
     */
    private Map<Long, Long> ownersOf(SubjectType type, List<Long> ids) {
        Map<Long, Long> owners = new LinkedHashMap<>();
        if (ids.isEmpty()) return owners;
        switch (type) {
            case CUSTOMER -> ids.forEach(id -> owners.put(id, id));
            case INVOICE -> {
                for (Invoice invoice : invoices.findAllById(ids)) {
                    owners.put(invoice.getId(), invoice.getCustomer().getId());
                }
            }
            case PAYMENT -> {
                for (Payment payment : payments.findAllById(ids)) {
                    owners.put(payment.getId(), payment.getCustomer().getId());
                }
            }
        }
        return owners;
    }

    /**
     * The date this step's arithmetic reads. A run carries its own, so a replayed run produces the
     * dates it produced the first time; an event has no run and is about today (A5, B3).
     *
     * <p>{@code todayForWrite} and not {@code today}: this is read on the sweeper's thread where
     * nothing is open, but naming the write-side clock says out loud that a step's date must never
     * follow a reader into the past (B3).
     */
    private LocalDate asOfOf(AutomationStep step) {
        if (step.getRunId() == null) return InvoiceDates.todayForWrite();
        return runs.findById(step.getRunId()).map(AutomationRun::getAsOf)
                .orElseGet(InvoiceDates::todayForWrite);
    }

    /**
     * THE SIX-LINE SEAM, and the only place in the engine that reads the past (B3).
     *
     * <p>A run whose recorded {@code as_of} is strictly in the past is a REPLAY: it evaluates the
     * population as it stood on that day. Every other run — a schedule's slot, a "run now" — has
     * today's date on it and gets a handle that does nothing at all, so not one statement of the
     * ordinary fan-out changes and no mirror table is read.
     *
     * <p>A no-op handle rather than an {@code if} around the caller, because the caller has to
     * close what it opened on every path including a throw, and two shapes of that block is how
     * one of them ends up leaking a date onto a pooled thread.
     */
    private AsOfContext.Handle replayHandle(AutomationRun run) {
        LocalDate asOf = run.getAsOf();
        if (asOf == null || !asOf.isBefore(InvoiceDates.todayForWrite())) {
            return () -> { };
        }
        return AsOfContext.open(asOf);
    }

    // ---- plumbing -----------------------------------------------------------------------------

    private void drainQuietly(Long eventId, Instant now) {
        List<Long> planned;
        try {
            planned = fanOut(eventId, now);
        } catch (RuntimeException e) {
            log.warn("Fanning out event {} failed; the sweeper will pick it up", eventId, e);
            return;
        }
        if (planned.isEmpty()) return;
        backgroundOwned.addAll(planned);
        try {
            planned.forEach(id -> performQuietly(id, now));
        } finally {
            planned.forEach(backgroundOwned::remove);
        }
    }

    private void fanOutQuietly(Long eventId, Instant now) {
        try {
            fanOut(eventId, now);
        } catch (RuntimeException e) {
            log.warn("Fanning out event {} failed; it stays for the next sweep", eventId, e);
        }
    }

    private void materialisePageQuietly(Long runId, Instant now) {
        try {
            materialisePage(runId, now);
        } catch (RuntimeException e) {
            log.warn("Planning a page of run {} failed; it stays for the next sweep", runId, e);
        }
    }

    private void performQuietly(Long stepId, Instant now) {
        try {
            perform(stepId, now);
        } catch (RuntimeException e) {
            log.warn("Step {} failed outside its own transaction; the sweeper will retry it", stepId, e);
        }
    }

    private void sweepQuietly(Instant now) {
        try {
            sweep(now);
        } catch (RuntimeException e) {
            log.warn("An automation sweep failed; the next one carries on", e);
        }
    }

    private List<Long> page(Supplier<List<Long>> read) {
        List<Long> ids = transactions.execute(s -> read.get());
        return ids == null ? List.of() : ids;
    }

    private static void outsideATransaction(String complaint) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(complaint);
        }
    }

    private static Instant backoffFrom(Instant now, int attempts) {
        return now.plus(BACKOFF[Math.min(Math.max(attempts, 1) - 1, BACKOFF.length - 1)]);
    }

    /**
     * The four refusals that mean "this will never work", the set
     * {@code GlobalExceptionHandler} already turns into a 4xx. Anything else is the database
     * having a bad day and is worth another go (A5).
     */
    private static boolean permanent(RuntimeException e) {
        return e instanceof BadRequestException
                || e instanceof NotFoundException
                || e instanceof AccessDeniedException
                || e instanceof com.geneinvoice.common.GlobalExceptionHandler.InvalidFieldsException;
    }

    private static String reasonOf(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String describe(AutomationActions.Outcome outcome) {
        if (outcome.producedType() == null) return null;
        String noun = switch (outcome.producedType()) {
            case TASK -> "Task";
            case PROMISE -> "Promise";
            case DISPUTE -> "Dispute";
            case EMAIL -> "Email";
            case PENDING_CHANGE -> "Change";
        };
        return noun + " #" + outcome.producedId();
    }

    private static String csv(List<String> unresolved) {
        return unresolved == null || unresolved.isEmpty() ? null
                : AutomationActions.fit(String.join(", ", unresolved), AutomationStep.UNRESOLVED_MAX);
    }

    /** A record and the account it hangs off, read together so neither is read twice (A5). */
    private record Subject(Object entity, Customer customer) {

        Long customerId() {
            return customer.getId();
        }
    }

    /** One step, before it is a row. Held as data so a failed batch can be replayed fresh (A5). */
    private record Plan(String occasion, Long ruleId, String ruleName, int ruleVersion,
                        int actionIndex, ActionKind actionKind, SubjectType subjectType,
                        Long subjectId, Long customerId, Long runId, StepSource source) {}

    /** One page of a run's fan-out, and whether it was the last one (A5). */
    private record PlannedPage(List<Long> ids, List<Plan> plans, Long cursor, boolean lastPage) {}
}
