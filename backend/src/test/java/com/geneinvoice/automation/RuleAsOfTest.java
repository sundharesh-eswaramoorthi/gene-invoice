package com.geneinvoice.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.asof.TestHistoryFloor;
import com.geneinvoice.common.Money;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.asof.AsOfSource;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.FixedHistoryClock;
import com.geneinvoice.history.HistorySchemas;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceHistory;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.CustomerRegionHistory;
import com.geneinvoice.region.Region;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE THIRD OF THE PRD'S THREE CLAUSES: rule evaluation, asked as of a date (B3).
 *
 * <p>"Every list, report and rule evaluation can be asked as of a date." The lists arrived with
 * B3-SLICE-INVOICE and B3-SLICE-REST, the reports with B3-DASHBOARD, and this is the last one. It
 * needed no evaluation path of its own and that is the point: A2's conditions already compile to
 * {@code FilterSpec} leaves over the same {@code TableSchema} a list screen uses, so the whole of
 * "evaluate this rule as of 31 January" is rooting the rule's existing query where the January
 * list is rooted. {@link EntitySources} is that one decision;
 * {@code aRuleAndAListAgreeOnWhatInvoicesAsOfAPastDateMeans} asserts that it and
 * {@code InvoiceService.invoiceSource()} really are the same pair rather than two that happen to
 * agree today.
 *
 * <p>TWO DOORS, AND THEY ANSWER DIFFERENT QUESTIONS. {@code POST .../simulate?asOf=} is a BACKTEST:
 * it matches as of that day and writes nothing whatever. {@code POST .../replay} with the day in
 * its body is a CATCH-UP: it opens a real run whose recorded {@code as_of} is that day, matches
 * the population as it stood then, and then acts TODAY — re-checking each action's guard against
 * live state before it touches a customer, which is the trap B3 states rather than leaves to be
 * discovered ("you do not email a customer about an invoice they paid yesterday").
 *
 * <p>THE TIMELINES ARE REAL. Every version these tests read was written by the production history
 * writer from a real create and a real payment, with {@link FixedHistoryClock} holding the
 * write-side clock at the day in question. Nothing here inserts an {@code InvoiceHistory} row by
 * hand. The one thing written directly is {@code customer_region_history}, because
 * {@code CustomerService.create} stamps the opening placement with the wall clock — correctly —
 * and back-dating a placement is what the region arm of this feature is about.
 *
 * <p>{@link TestHistoryFloor} pushes the floor back to 2000 so these answers are the ordinary
 * RECONSTRUCTED ones rather than every one of them coming back SEEDED.
 */
@Import({FixedHistoryClock.Config.class, TestHistoryFloor.Config.class})
class RuleAsOfTest extends IntegrationTestBase {

    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired AutomationRuleService ruleService;
    @Autowired AutomationDispatcher dispatcher;
    @Autowired EntitySources entitySources;
    @Autowired FixedHistoryClock clock;
    @Autowired TestHistoryFloor floor;

    User admin;
    User ana;
    Customer acme;
    Product widget;

    LocalDate today;
    /** The day the money was raised, and the day the placements open. */
    LocalDate raised;
    /** An as-of date AFTER it was raised and BEFORE anything was paid. */
    LocalDate before;
    /** The day it was paid. */
    LocalDate paidOn;
    /** An as-of date AFTER the payment. */
    LocalDate after;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        ana = user("ana.backtester", authorRole().getName());
        widget = product("Widget", "100.00");
        today = LocalDate.now(ZoneOffset.UTC);
        raised = today.minusDays(60);
        before = today.minusDays(40);
        paidOn = today.minusDays(20);
        after = today.minusDays(10);
        acme = placed("Acme Ltd", defaultRegion(), today.minusDays(90));
    }

    /**
     * Both are singletons in a cached context: a test that moved either and did not put it back
     * would date every later test's mirror rows at its own instant, or floor every later answer at
     * its own day (B3).
     */
    @AfterEach
    void putEverythingBack() {
        clock.release();
        floor.reset();
        AsOfContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ================================================================ the backtest

    /**
     * THE CLAUSE, MADE TRUE OF A RULE: "rule evaluation can be asked as of a date" (B3).
     *
     * <p>One rule, two dates, two different answers, and both of them right. In August the old
     * invoice was outstanding and the new one did not exist; today the old one is paid and the new
     * one is outstanding. A dunning rule asked "what would you have hit on that day" names the
     * first, and asked about today names the second.
     *
     * <p>THIS IS THE LOAD-BEARING TEST OF THE UNIT. Take {@code AsOf.at(T)} out of
     * {@code EntitySources.forSubject}, or root it on {@code Invoice} instead of
     * {@code InvoiceHistory}, and it goes red — in the first case because every VERSION of the old
     * invoice matches and the count becomes the number of edits, in the second because the past is
     * simply not there to read.
     */
    @Test
    void aRuleSimulatedAsOfAPastDateMatchesTheRecordsThatMatchedThen() throws Exception {
        Invoice old = raise(acme, raised, "1000.00");
        pay(acme, old, "1000.00", paidOn);
        Invoice recent = raise(acme, today.minusDays(5), "500.00");

        // As of the day in between: the old invoice was worth 1000 and nothing had been paid, and
        // the recent one had not been raised.
        AutomationRule rule = rule("Chase");
        simulate(rule, before)
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.asOf.date").value(before.toString()))
                .andExpect(jsonPath("$.asOf.appliesTo").value("records"))
                .andExpect(jsonPath("$.asOf.origin").value(AsOfContext.ORIGIN_RECONSTRUCTED))
                // THE CAVEAT IS ON THE WIRE, and it is a caveat and not a setting: the matched SET
                // is as of that day, and the text previewed beside each record is rendered from the
                // LIVE record with only the date arithmetic counted from then. A client must not
                // put it in front of somebody as the values of that day. See SimulationDto (B3).
                .andExpect(jsonPath("$.sampleRendersTodaysValues").value(true))
                .andExpect(jsonPath("$.sample.length()").value(1))
                .andExpect(jsonPath("$.sample[0].id").value(old.getId()));

        // Today: the old one is settled and the recent one is not. Same rule, same conditions.
        simulate(rule, null)
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.asOf").doesNotExist())
                .andExpect(jsonPath("$.sample[0].id").value(recent.getId()));

        // And before either of them existed, it would have hit nothing at all.
        simulate(rule, today.minusDays(80))
                .andExpect(jsonPath("$.matched").value(0))
                .andExpect(jsonPath("$.sample.length()").value(0));
    }

    /**
     * A BACKTEST WRITES NOTHING, AND IT IS NOT A PROMISE THIS CODE MAKES AND THEN KEEPS — there is
     * no write in it to keep (A5, B3).
     *
     * <p>Six tables, checked after a simulation that really did match a record: no run, no step,
     * no outbox event, no task, no email, no promise and no dispute. The backstop is structural as
     * well as asserted — {@code HistoryWriter.drain} throws for ANY non-read-only transaction that
     * commits while an as-of context is open, which is what makes an accidental write inside a
     * simulation loud instead of silent.
     */
    @Test
    void simulatingARuleAsOfAPastDateCreatesNoTaskPromiseDisputeEmailOrOutboxRow() throws Exception {
        Invoice invoice = raise(acme, raised, "1000.00");
        AutomationRule rule = rule("Chase");

        long eventsBefore = automationEventRepository.count();
        simulate(rule, before)
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.sample[0].id").value(invoice.getId()));

        assertThat(automationRunRepository.count()).describedAs("no run").isZero();
        assertThat(automationStepRepository.count()).describedAs("no step").isZero();
        assertThat(automationEventRepository.count())
                .describedAs("no outbox row").isEqualTo(eventsBefore);
        assertThat(taskRepository.count()).describedAs("no task").isZero();
        assertThat(emailRepository.count()).describedAs("no email").isZero();
        assertThat(promiseRepository.count()).describedAs("no promise").isZero();
    }

    /**
     * ONE SWITCH, NOT TWO. The pair {@link EntitySources} hands a rule is the pair
     * {@code InvoiceService.invoiceSource()} hands a list, on both paths, so "invoices as of 40
     * days ago" cannot come to mean one thing on a screen and another in a rule (B3).
     *
     * <p>Asserted rather than claimed, because the two are reached through different code: the
     * service switches on {@code AsOfContext.isActive()} against a pair of constants, and
     * EntitySources looks the twin up by the LIVE schema's own {@code entity()} string through
     * {@code HistorySchemas.byEntity} — the same lookup {@code GET /api/as-of} publishes. They can
     * only stay equal because the twin deliberately keeps the live entity name.
     */
    @Test
    void aRuleAndAListAgreeOnWhatInvoicesAsOfAPastDateMeans() {
        actAs(admin);

        AsOfSource<?> liveRule = entitySources.forSubject(SubjectType.INVOICE);
        assertThat(liveRule.type()).isEqualTo(Invoice.class);
        assertThat(liveRule.schema()).isSameAs(TableSchemas.INVOICES);
        assertThat(liveRule.scope()).describedAs("a live rule adds no interval clause").isEmpty();
        assertThat(invoiceService.invoiceSource().type()).isEqualTo(liveRule.type());
        assertThat(invoiceService.invoiceSource().schema()).isSameAs(liveRule.schema());

        try (AsOfContext.Handle ignored = AsOfContext.open(before)) {
            AsOfSource<?> historicalRule = entitySources.forSubject(SubjectType.INVOICE);
            assertThat(historicalRule.type()).isEqualTo(InvoiceHistory.class);
            assertThat(historicalRule.schema()).isSameAs(HistorySchemas.INVOICES);
            assertThat(historicalRule.scope())
                    .describedAs("without this the rule matches every VERSION of every record")
                    .hasSize(1);
            assertThat(invoiceService.invoiceSource().type()).isEqualTo(historicalRule.type());
            assertThat(invoiceService.invoiceSource().schema()).isSameAs(historicalRule.schema());
        }

        // The other two subjects a rule can name, both mirrored, both resolved the same way.
        try (AsOfContext.Handle ignored = AsOfContext.open(before)) {
            assertThat(entitySources.forSubject(SubjectType.CUSTOMER).schema())
                    .isSameAs(HistorySchemas.CUSTOMERS);
            assertThat(entitySources.forSubject(SubjectType.PAYMENT).schema())
                    .isSameAs(HistorySchemas.PAYMENTS);
        }
    }

    // ================================================================ the catch-up

    /**
     * THE A5 ARGUMENT, MADE TRUE OF THE TRIGGER AND NOT ONLY OF THE ACTION (A5, B3).
     *
     * <p>The consumer was down and the slot never fired. Running the rule TODAY evaluates today's
     * data — which for a dunning rule means chasing the people who are overdue now instead of the
     * people who were overdue then, a different rule firing rather than a replay. Replaying it as
     * of its own day reproduces exactly the set it would have matched: here the invoice that was
     * outstanding on the missed day and has been settled since.
     *
     * <p>Both halves are asserted, because only the pair proves anything: a run opened TODAY over
     * the same fixtures plans nothing at all.
     */
    @Test
    void aMissedScheduledRunReplayedAsOfItsOwnDateMatchesWhatItWouldHaveMatched() throws Exception {
        Invoice settled = raise(acme, raised, "1000.00");
        pay(acme, settled, "1000.00", paidOn);
        AutomationRule rule = rule("Chase");

        // Run it now and it finds nothing: that invoice has been paid.
        mockMvc.perform(post("/api/automation/rules/" + rule.getId() + "/run")
                        .param("apply", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AutomationDtos.RunRequest("today-1", null)))
                        .with(as(ana)))
                .andExpect(status().isOk());
        dispatcher.sweep(Instant.now().plusSeconds(120));
        assertThat(automationStepRepository.count())
                .describedAs("today's population does not include a settled invoice").isZero();

        // Replay the missed day and it finds the one record that was outstanding then.
        replay(rule, before).andExpect(jsonPath("$.status").value("FANNING"));
        AutomationRun run = automationRunRepository.findByRuleIdAndOccasion(rule.getId(), "R" + before)
                .orElseThrow();
        assertThat(run.getAsOf()).describedAs("the run records the day it evaluates").isEqualTo(before);

        dispatcher.sweep(Instant.now().plusSeconds(120));
        assertThat(automationRunRepository.findById(run.getId()).orElseThrow().getMatched())
                .isEqualTo(1);
        assertThat(automationStepRepository.findAll()).singleElement().satisfies(step -> {
            assertThat(step.getSubjectId()).isEqualTo(settled.getId());
            assertThat(step.getOccasion()).isEqualTo("R" + before);
        });
    }

    /**
     * THE SAME QUESTION, ASKED TWICE, ANSWERED IDENTICALLY — WITH THE LIVE BOOK MOVING IN BETWEEN
     * (A5, B3).
     *
     * <p>Two things are asserted and they are different claims. The DEDUPE: a second replay of the
     * same day bounces off {@code uk_run_occasion}, reads back the first run and plans no second
     * step, which is what makes "replay the missed day" safe to press when you are not sure it
     * worked the first time. The DETERMINISM: the match set read at the run's recorded date is the
     * same set afterwards as before, although a new invoice has been raised today and would be
     * matched by a run opened now. Mirror rows are immutable, so the answer is reproducible for
     * ever — the dedupe alone would not have shown that, because a deduped run re-plans nothing
     * and could not disagree with itself.
     */
    @Test
    void theRecordedAsOfOfARunReproducesItsMatchSetExactlyTwice() throws Exception {
        Invoice outstanding = raise(acme, raised, "1000.00");
        AutomationRule rule = rule("Chase");

        List<Long> first = simulatedIds(rule, before);
        assertThat(first).containsExactly(outstanding.getId());

        replay(rule, before);
        dispatcher.sweep(Instant.now().plusSeconds(120));
        List<Long> plannedOnce = subjectIdsOfSteps();
        assertThat(plannedOnce).containsExactly(outstanding.getId());
        Long runId = automationRunRepository.findByRuleIdAndOccasion(rule.getId(), "R" + before)
                .orElseThrow().getId();

        // The book moves on: another invoice is raised TODAY, and it would be matched by a run
        // opened now. It changes nothing about the day being replayed.
        Invoice raisedSince = raise(acme, today, "250.00");
        assertThat(simulatedIds(rule, null)).contains(raisedSince.getId());

        replay(rule, before).andExpect(jsonPath("$.id").value(runId));
        dispatcher.sweep(Instant.now().plusSeconds(240));

        assertThat(automationRunRepository.count()).describedAs("one day, one run").isEqualTo(1);
        assertThat(subjectIdsOfSteps()).describedAs("and one set of steps").isEqualTo(plannedOnce);
        assertThat(simulatedIds(rule, before))
                .describedAs("the same question, asked after the book moved").isEqualTo(first);
    }

    /**
     * EVALUATE AS-OF, ACT NOW — THE TRAP, ASSERTED (A5, B3).
     *
     * <p>The invoice was outstanding on the replayed day and has been paid since. The replay
     * matches it, because that is what the day says; the ACTION is then refused, because the guard
     * is re-checked against live state before anything happens to a customer. You do not email
     * somebody about an invoice they paid yesterday.
     *
     * <p>The step settles SKIPPED with the date it was matched as of written into its result. That
     * is this build's SKIPPED_STALE: a fourth {@code StepStatus} was deliberately not added,
     * because SKIPPED already means "this correctly did not happen" and carries its reason, and
     * splitting it would make a replay's ordinary outcome look like a different kind of event in
     * every list, filter and count that reads the status. The reason names the date, so a reader
     * of the history can tell a stale replay from an ordinary publish-to-consume race.
     */
    @Test
    void aReplaySkipsAnActionWhoseLiveGuardNoLongerHoldsAndRecordsSkippedStale() throws Exception {
        Invoice settled = raise(acme, raised, "1000.00");
        pay(acme, settled, "1000.00", paidOn);
        AutomationRule rule = rule("Chase");

        replay(rule, before);
        dispatcher.sweep(Instant.now().plusSeconds(120));

        assertThat(automationStepRepository.findAll()).singleElement().satisfies(step -> {
            assertThat(step.getSubjectId()).isEqualTo(settled.getId());
            assertThat(step.getStatus()).isEqualTo(StepStatus.SKIPPED);
            assertThat(step.getResult())
                    .describedAs("the skip has to say it was a stale replay, and as of when")
                    .contains("no longer matches")
                    .contains("matched as of " + before)
                    .contains("replay");
        });
        assertThat(taskRepository.count())
                .describedAs("nothing at all happened to the customer").isZero();
    }

    /**
     * THE ACTION QUOTES TODAY'S MONEY, AND ONLY THE DATE ARITHMETIC IS THE RUN'S (A4, A5, B3).
     *
     * <p>The invoice was worth 1000 on the replayed day and 400 has been paid since. It still
     * matches, so the action fires — and the task it writes says 600, because the record it renders
     * from was read LIVE a few lines above the render. A replay that quoted the balance of the day
     * would put a number in front of a customer that has not been true for a month.
     */
    @Test
    void anActionFiredByAnAsOfRunQuotesTodaysBalanceAndNotTheAsOfBalance() throws Exception {
        Invoice invoice = raise(acme, raised, "1000.00");
        pay(acme, invoice, "400.00", paidOn);
        AutomationRule rule = rule("Chase", "Chase {{Invoice.Number}} for {{Invoice.Balance}}");

        // As of the replayed day the balance really was the full 1000, and the backtest says so.
        simulate(rule, before).andExpect(jsonPath("$.matched").value(1));

        replay(rule, before);
        dispatcher.sweep(Instant.now().plusSeconds(120));

        assertThat(automationStepRepository.findAll()).singleElement()
                .satisfies(step -> assertThat(step.getStatus()).isEqualTo(StepStatus.DONE));
        assertThat(taskRepository.findAll()).singleElement().satisfies(task ->
                assertThat(task.getTitle())
                        .describedAs("the action acts on the record as it is NOW")
                        .isEqualTo("Chase " + invoice.getInvoiceNumber() + " for "
                                + Money.format(new BigDecimal("600.00"))));
    }

    // ================================================================ the branch

    /**
     * WHICH REGION A RECORD BELONGED TO THEN, DECIDING WHAT A RULE MAY REACH (B1, B3).
     *
     * <p>The PRD's first "including" clause, asked of a rule rather than of a list. This account
     * was a NORTH account when the money was raised and has since moved to HQ. A rule that reaches
     * only HQ must not touch it as of a day when it was somebody else's — and must touch it as of
     * a day after the move, and today.
     *
     * <p>It is B3-04's two-clause leak guard doing the work: a record is reachable only if the
     * caller may see where it was THEN and where it is NOW. That is why the wildcard rule, which
     * reaches every branch, matches on both dates while the HQ rule matches on only one — and why
     * a rule cannot be used to read across a branch by asking about a date before the record got
     * there.
     */
    @Test
    void aRuleCannotReachARecordOutsideItsOwnRegionsEvenAsOfADate() throws Exception {
        Region north = region("NORTH");
        // Placed in NORTH when the money was raised, moved to HQ twenty days ago. The live row
        // says HQ, which is the "now" half of the guard.
        Customer mover = customer("Mover Ltd");
        place(mover, north.getId(), today.minusDays(90), paidOn);
        place(mover, defaultRegion().getId(), paidOn, null);
        Invoice invoice = raise(mover, raised, "1000.00");

        // ANA'S RULE NAMES NO BRANCH, so it reaches the branches she may manage, which is HQ.
        AutomationRule hqOnly = rule("Chase HQ");
        simulate(hqOnly, before)
                .andExpect(jsonPath("$.matched").value(0))
                .andExpect(jsonPath("$.sample.length()").value(0));
        simulate(hqOnly, after)
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.sample[0].id").value(invoice.getId()));
        simulate(hqOnly, null).andExpect(jsonPath("$.matched").value(1));

        // AN ADMINISTRATOR'S RULE reaches every branch, so the same record on the same day is
        // hers — which is what makes the refusal above a region decision and not an empty mirror.
        AutomationRule everywhere = adminRule("Chase everywhere");
        // Asked by the ADMINISTRATOR, because the sample is bounded by the READER's own gate as
        // well: Ana may not see where this record was on that day either, so she would be shown
        // the count and no row.
        simulate(admin, everywhere, before)
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.sample[0].id").value(invoice.getId()));
        simulate(ana, everywhere, before)
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.sample.length()").value(0));

        // AND A RULE THAT NAMES THE BRANCH THE RECORD HAS LEFT reaches it on neither date: the
        // guard is two clauses, and "where it is now" is HQ (B3-04).
        // Asked by the administrator: a rule that NAMES a branch Ana may not read is not a rule
        // Ana may see at all, and would answer 404 before any of this (A1, B1, AUTH-08).
        AutomationRule northOnly = adminRule("Chase north", List.of(north.getId()));
        simulate(admin, northOnly, before).andExpect(jsonPath("$.matched").value(0));
        simulate(admin, northOnly, null).andExpect(jsonPath("$.matched").value(0));
    }

    // ================================================================ the boundary

    /**
     * THE WRITE GUARD, KEPT RATHER THAN STEPPED AROUND (B3).
     *
     * <p>The replay writes, so it refuses {@code ?asOf} exactly as every other mutating mapping in
     * the application does, and takes the day it replays in its body instead. The simulate reads,
     * so it accepts it. That is the whole of why there are two endpoints and not one {@code mode}
     * parameter: {@code AsOfInterceptor} would have opened a context for a REPLAY arm, and
     * {@code HistoryWriter.drain} throws for any non-read-only transaction that commits while one
     * is open.
     *
     * <p>A date that is not in the past is refused by the replay too, in its own words, because
     * "replay today" is what run-now already is.
     */
    @Test
    void theReplayRefusesADateInTheQueryStringAndASimulationAcceptsOne() throws Exception {
        AutomationRule rule = rule("Chase");

        mockMvc.perform(post("/api/automation/rules/" + rule.getId() + "/replay")
                        .param("asOf", before.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AutomationDtos.RunRequest(null, before)))
                        .with(as(ana)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The past is read only"));
        assertThat(automationRunRepository.count()).isZero();

        replay(rule, today)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(AutomationRuleService.REPLAY_NEEDS_A_PAST_DATE));
        assertThat(automationRunRepository.count()).isZero();

        simulate(rule, before).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- requests

    private ResultActions simulate(AutomationRule rule, LocalDate asOf) throws Exception {
        return simulate(ana, rule, asOf);
    }

    /**
     * The COUNT is the rule's reach and the SAMPLE is the caller's, which is A5's rule and not a
     * B3 one: a record inside the rule's reach but outside the reader's own is counted and not
     * shown, because a rendered {@code {{Customer.Name}}} is a read of that customer (AUTH-08).
     */
    private ResultActions simulate(User who, AutomationRule rule, LocalDate asOf) throws Exception {
        var request = post("/api/automation/rules/" + rule.getId() + "/simulate").with(as(who));
        if (asOf != null) request = request.param("asOf", asOf.toString());
        return mockMvc.perform(request).andExpect(status().isOk());
    }

    private List<Long> simulatedIds(AutomationRule rule, LocalDate asOf) throws Exception {
        JsonNode body = objectMapper.readTree(
                simulate(rule, asOf).andReturn().getResponse().getContentAsString());
        List<Long> ids = new ArrayList<>();
        body.get("sample").forEach(row -> ids.add(row.get("id").asLong()));
        return ids;
    }

    /** The day travels in the BODY, because this endpoint writes (B3). */
    private ResultActions replay(AutomationRule rule, LocalDate asOf) throws Exception {
        return mockMvc.perform(post("/api/automation/rules/" + rule.getId() + "/replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new AutomationDtos.RunRequest(null, asOf)))
                .with(as(ana)));
    }

    private List<Long> subjectIdsOfSteps() {
        return automationStepRepository.findAll().stream()
                .map(AutomationStep::getSubjectId).sorted().toList();
    }

    // ---------------------------------------------------------------- fixtures

    /** Noon of a day, so a version opened "on" a date is comfortably inside it at both ends. */
    private static Instant noon(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant();
    }

    /**
     * A real create through the real service with the WRITE-side clock held at the day in
     * question, so the mirror row the history writer leaves behind is dated then.
     */
    private Invoice raise(Customer c, LocalDate on, String total) {
        actAs(admin);
        clock.freezeAt(noon(on));
        int quantity = new BigDecimal(total).divide(new BigDecimal("100.00")).intValue();
        Invoice invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                c.getId(), noon(on), on.plusDays(30), PaymentTerm.CUSTOM, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), quantity,
                        new BigDecimal("100.00")))));
        clock.release();
        SecurityContextHolder.clearContext();
        return invoice;
    }

    private void pay(Customer c, Invoice invoice, String amount, LocalDate on) {
        actAs(admin);
        clock.freezeAt(noon(on));
        paymentService.record(new PaymentDtos.CreatePaymentRequest(c.getId(),
                new BigDecimal(amount), "CASH", null, List.of(invoice.getId()), admin.getId(),
                null));
        clock.release();
        SecurityContextHolder.clearContext();
    }

    /**
     * An account with a placement ledger behind it. {@code IntegrationTestBase.customer} writes
     * none — {@code CustomerService.create} is the only path that does, and it stamps the opening
     * row with the wall clock — so a fixture account is in no branch as of any past date and the
     * two-clause region guard hides every record it owns (B1, B3).
     */
    private Customer placed(String name, Region region, LocalDate from) {
        Customer c = customer(name);
        place(c, region.getId(), from, null);
        return c;
    }

    private void place(Customer c, Long regionId, LocalDate from, LocalDate to) {
        customerRegionHistoryRepository.saveAndFlush(CustomerRegionHistory.builder()
                .customerId(c.getId()).regionId(regionId).validFrom(from).validTo(to).build());
    }

    private AutomationRule rule(String name) {
        return rule(name, "Chase {{Invoice.Number}}");
    }

    /** Ana's rule: it names no branch, so it reaches the branches ANA may manage (A1, B1). */
    private AutomationRule rule(String name, String title) {
        actAs(ana);
        Long id = ruleService.create(new AutomationDtos.SaveRuleRequest(name, null,
                SubjectType.INVOICE, TriggerKind.SCHEDULE_DAILY, 9, null,
                condition("balance:gt:0"),
                List.of(new ActionSpec.CreateTask(title, null, List.of(), 3)),
                null, true, List.of())).id();
        SecurityContextHolder.clearContext();
        return automationRuleRepository.findById(id).orElseThrow();
    }

    private AutomationRule adminRule(String name) {
        return adminRule(name, List.of());
    }

    /** An administrator's rule: it reaches every active branch, or exactly the ones it names. */
    private AutomationRule adminRule(String name, List<Long> regionIds) {
        actAs(admin);
        Long id = ruleService.create(new AutomationDtos.SaveRuleRequest(name, null,
                SubjectType.INVOICE, TriggerKind.SCHEDULE_DAILY, 9, null,
                condition("balance:gt:0"),
                List.of(new ActionSpec.CreateTask("Chase {{Invoice.Number}}", null, List.of(), 3)),
                null, true, regionIds)).id();
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

    private Role authorRole() {
        return roleWith("RULE_AS_OF_AUTHOR", Privileges.AUTOMATION_VIEW,
                Privileges.AUTOMATION_MANAGE, Privileges.AUTOMATION_RUN, Privileges.CUSTOMER_VIEW,
                Privileges.CUSTOMER_MANAGE, Privileges.INVOICE_VIEW, Privileges.PAYMENT_VIEW,
                Privileges.EMAIL_VIEW, Privileges.EMAIL_SEND, Privileges.TASK_VIEW,
                Privileges.TASK_MANAGE, Privileges.PROMISE_VIEW, Privileges.PROMISE_MANAGE,
                Privileges.DISPUTE_CREATE, Privileges.DISPUTE_VIEW,
                // SCOPE_OVERRIDE, as AutomationRunNowTest's author has: without it the POC book
                // empties Ana's SAMPLE of records she is not the Sales POC for, and these tests
                // would be asserting on her book rather than on the rule's reach. It widens her
                // BOOK and not her BRANCHES, which is what the region test depends on (B1).
                Privileges.SCOPE_OVERRIDE);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by RuleAsOfTest")
                .privileges(Arrays.stream(privileges)
                        .map(p -> privilegeRepository.findByName(p).orElseThrow())
                        .collect(Collectors.toCollection(HashSet<Privilege>::new)))
                .build()));
    }
}
