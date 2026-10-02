package com.geneinvoice.automation.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.MovableClock;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.automation.AutomationTestBase;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.rule.AutomationRule;
import com.geneinvoice.automation.rule.RuleService;
import com.geneinvoice.automation.rule.SuspendReason;
import com.geneinvoice.common.scope.AsOfException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.task.Task;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * "Run now" (§5.10.9; PC-6, PC-12, SC-2, SC-16): it queues a run (202) that the consumer carries out as the owner,
 * at the time asked for, over what the rule matches or the records named, in the regions asked about; an identical
 * request while one waits is answered with it (200), a different one queues another, and two at once make one; a
 * rule that is off can be run, a suspended one cannot; and whoever runs a rule is its owner.
 */
class RunNowTest extends AutomationTestBase {

    @Autowired AuditLogRepository auditLogRepository;
    @Autowired RuleService ruleService;

    User admin;
    User owen;
    Product widget;
    Customer acme;
    List<Long> invoices;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        owen = user("owen.owner", "CASHIER");
        widget = product("Widget", "100.00");
        acme = customer("Acme Ltd");
        actAs(admin);
        invoices = new ArrayList<>();
        for (int i = 1; i <= 3; i++) invoices.add(invoice(acme, widget, i, null));
        drain();
    }

    private Map<String, Object> byHand(String name) {
        return rule(name, "INVOICE", "MANUAL", null, everyTime(task("Chase {{Invoice.Number}}")));
    }

    private MockHttpServletResponse run(Long ruleId, User caller, Map<String, Object> body) throws Exception {
        return run(post(RULES + "/" + ruleId + "/run"), caller, body);
    }

    private MockHttpServletResponse run(MockHttpServletRequestBuilder request, User caller, Map<String, Object> body)
            throws Exception {
        return send(request, caller, body).andReturn().getResponse();
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString());
    }

    @Test
    void runNowQueuesARunThatTheConsumerCarriesOutAsTheOwner() throws Exception {
        Long ruleId = saveRule(owen, byHand("By hand"));
        MockHttpServletResponse response = run(ruleId, owen, Map.of());
        assertThat(response.getStatus()).isEqualTo(202);
        JsonNode queued = json(response);
        assertThat(queued.get("status").asText()).isEqualTo("QUEUED");
        AutomationRun run = automationRunRepository.findById(queued.get("runId").asLong()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(run.getTriggerType()).isEqualTo(AutomationEvent.MANUAL);
        assertThat(run.getRequestedByUserId()).isEqualTo(owen.getId());
        AutomationEvent ev = event(queued.get("eventId").asLong());
        assertThat(ev.getKind()).isEqualTo(AutomationEvent.MANUAL);
        assertThat(ev.getActorUserId()).isEqualTo(owen.getId());
        assertThat(ev.getScheduledFor()).isEqualTo(run.getScheduledFor());
        assertThat(ev.getNextAttemptAt()).isNull();
        assertThat(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("RULE", ruleId))
                .extracting(AuditLog::getAction).contains("RULE_RUN_REQUESTED");

        drain();
        assertThat(tasksOf(ruleId)).extracting(Task::getEntityId).containsExactlyInAnyOrderElementsOf(invoices);
        assertThat(tasksOf(ruleId)).allSatisfy(t -> assertThat(t.getCreatedByUserId()).isEqualTo(owen.getId()));
        AutomationRun done = automationRunRepository.findById(run.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(done.getMatched()).isEqualTo(3);
        assertThat(done.getDone()).isEqualTo(3);
        AutomationRule rule = automationRuleRepository.findById(ruleId).orElseThrow();
        assertThat(rule.getLastRunId()).isEqualTo(run.getId());
        assertThat(rule.getLastRunStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(rule.getVersion()).as("what its runs change is not a user edit").isZero();
    }

    @Test
    void runNowOnChosenRecordsTouchesOnlyThose() throws Exception {
        Long ruleId = saveRule(owen, byHand("By hand"));
        assertThat(run(ruleId, owen, Map.of("recordIds", List.of(invoices.get(1)))).getStatus()).isEqualTo(202);
        drain();
        assertThat(tasksOf(ruleId)).extracting(Task::getEntityId).containsExactly(invoices.get(1));
    }

    @Test
    void fiftyLargeIdsAreKeptExactlyAndFiftyOneAreRefused() throws Exception {
        Long ruleId = saveRule(owen, byHand("By hand"));
        List<Long> fifty = LongStream.range(0, 50).map(i -> Long.MAX_VALUE - i).boxed().toList();
        JsonNode queued = json(run(ruleId, owen, Map.of("recordIds", fifty)));
        assertThat(event(queued.get("eventId").asLong()).getRecordIds())
                .isEqualTo(fifty.stream().map(String::valueOf).collect(Collectors.joining(",")));

        List<Long> fiftyOne = LongStream.range(0, 51).map(i -> i + 1).boxed().toList();
        MockHttpServletResponse tooMany = run(ruleId, owen, Map.of("recordIds", fiftyOne));
        assertThat(tooMany.getStatus()).isEqualTo(400);
        assertThat(json(tooMany).get("fieldErrors").get("recordIds").asText())
                .isEqualTo("At most 50 records can be run at once");
        assertThat(run(ruleId, owen, Map.of("recordIds", List.of(0))).getStatus()).isEqualTo(400);
        assertThat(events(AutomationEvent.MANUAL)).hasSize(1);
    }

    @Test
    void anIdenticalRequestWhileOneWaitsIsAnsweredWithItAndADifferentOneQueuesAnother() throws Exception {
        Long ruleId = saveRule(owen, byHand("By hand"));
        JsonNode first = json(run(ruleId, owen, Map.of("recordIds", List.of(invoices.get(0)))));
        MockHttpServletResponse again = run(ruleId, owen, Map.of("recordIds", List.of(invoices.get(0))));
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(json(again).get("runId").asLong()).isEqualTo(first.get("runId").asLong());

        MockHttpServletResponse other = run(ruleId, owen, Map.of("recordIds", List.of(invoices.get(2))));
        assertThat(other.getStatus()).isEqualTo(202);
        assertThat(json(other).get("runId").asLong()).isNotEqualTo(first.get("runId").asLong());
        assertThat(run(ruleId, owen, Map.of()).getStatus()).as("all of it is different again").isEqualTo(202);
        assertThat(events(AutomationEvent.MANUAL)).hasSize(3);

        drain();
        assertThat(run(ruleId, owen, Map.of("recordIds", List.of(invoices.get(0)))).getStatus())
                .as("the earlier one has run, so this is new").isEqualTo(202);
    }

    @Test
    void twoIdenticalRequestsAtOnceMakeOneRun() throws Exception {
        Long ruleId = saveRule(owen, byHand("By hand"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> sent = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                sent.add(pool.submit(() -> {
                    start.await();
                    return run(ruleId, owen, Map.of("recordIds", List.of(invoices.get(0)))).getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : sent) statuses.add(f.get(30, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(202, 200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(events(AutomationEvent.MANUAL)).hasSize(1);
        assertThat(runsOf(ruleId)).hasSize(1);
    }

    @Test
    void aRunAtALaterTimeWaitsForItAndLooksAtTheRecordsAsTheyAreThen() throws Exception {
        Long ruleId = saveRule(owen, byHand("By hand"));
        Instant now = MovableClock.SHARED.instant();
        Instant at = now.plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        MockHttpServletResponse queued = run(ruleId, owen, Map.of("runAt", at.toString()));
        assertThat(queued.getStatus()).isEqualTo(202);
        assertThat(Instant.parse(json(queued).get("scheduledFor").asText())).isEqualTo(at);
        assertThat(event(json(queued).get("eventId").asLong()).getNextAttemptAt()).isEqualTo(at);

        drain();
        assertThat(tasksOf(ruleId)).isEmpty();
        actAs(admin);
        Long later = invoice(acme, widget, 4, null);
        MovableClock.SHARED.set(at.plus(Duration.ofMinutes(5)));
        drain();
        assertThat(tasksOf(ruleId)).extracting(Task::getEntityId).contains(later).hasSize(4);

        for (Instant wrong : List.of(now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofDays(91)))) {
            MovableClock.SHARED.set(now);
            MockHttpServletResponse refused = run(ruleId, owen, Map.of("runAt", wrong.toString()));
            assertThat(refused.getStatus()).isEqualTo(400);
            assertThat(json(refused).get("fieldErrors").has("runAt")).isTrue();
        }
    }

    @Test
    void theRegionsAskedAboutNarrowTheRun() throws Exception {
        Region north = region("NORTH");
        Region south = region("SOUTH");
        for (User u : List.of(owen, admin, seller)) {
            grant(u, north, AccessLevel.MANAGE, RecordScope.ALL);
            grant(u, south, AccessLevel.MANAGE, RecordScope.ALL);
        }
        actAs(admin);
        Long inNorth = invoice(customer("Nord", north), widget, 1, null);
        Long inSouth = invoice(customer("Sud", south), widget, 1, null);
        Long ruleId = saveRule(owen, byHand("By hand"));
        JsonNode queued = json(run(post(RULES + "/" + ruleId + "/run").param("regionIds", south.getId().toString()),
                owen, Map.of()));
        assertThat(event(queued.get("eventId").asLong()).getRegionIds()).isEqualTo(south.getId().toString());
        drain();
        assertThat(tasksOf(ruleId)).extracting(Task::getEntityId).containsExactly(inSouth).doesNotContain(inNorth);
    }

    @Test
    void aRuleThatIsOffCanBeRunButASuspendedOneCannot() throws Exception {
        Long off = saveRule(owen, with(rule("Off", "INVOICE", "ENTITY_UPDATED", null, task("Chase")), "enabled",
                false));
        assertThat(run(off, owen, Map.of()).getStatus()).isEqualTo(202);
        drain();
        assertThat(tasksOf(off)).hasSize(3);

        Long suspended = saveRule(owen, byHand("Suspended"));
        ruleService.suspend(suspended, SuspendReason.OWNER_LACKS_RIGHTS, "Test");
        MockHttpServletResponse refused = run(suspended, owen, Map.of());
        assertThat(refused.getStatus()).isEqualTo(400);
        assertThat(json(refused).get("message").asText()).isEqualTo("The rule is suspended; resume it first");
        assertThat(runsOf(suspended)).isEmpty();
    }

    @Test
    void whoeverRunsARuleIsItsOwner() throws Exception {
        Long ruleId = saveRule(owen, byHand("By hand"));
        User cleo = user("cleo.cashier", "CASHIER");
        assertThat(run(ruleId, cleo, Map.of()).getStatus()).as("no administrator").isEqualTo(403);

        MockHttpServletResponse unconfirmed = run(ruleId, admin, Map.of());
        assertThat(unconfirmed.getStatus()).isEqualTo(400);
        assertThat(json(unconfirmed).get("fieldErrors").has("confirmOwnershipChange")).isTrue();
        assertThat(automationRuleRepository.findById(ruleId).orElseThrow().getOwnerUserId()).isEqualTo(owen.getId());
        assertThat(events(AutomationEvent.MANUAL)).isEmpty();

        assertThat(run(ruleId, admin, Map.of("confirmOwnershipChange", true)).getStatus()).isEqualTo(202);
        assertThat(automationRuleRepository.findById(ruleId).orElseThrow().getOwnerUserId()).isEqualTo(admin.getId());
        assertThat(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("RULE", ruleId))
                .extracting(AuditLog::getAction).contains("RULE_OWNERSHIP_TAKEN", "RULE_RUN_REQUESTED");
        drain();
        assertThat(tasksOf(ruleId)).hasSize(3)
                .allSatisfy(t -> assertThat(t.getCreatedByUserId()).as("it ran as its new owner").isEqualTo(admin.getId()));
    }

    /**
     * SC-2: an administrator who takes a rule over to run it is its owner from then on, so the dispute its
     * CREATE_DISPUTE raises is theirs, and the change it proposes is not theirs to approve.
     */
    @Test
    void whoeverRunsARuleCannotApproveTheChangeItsDisputeProposes() throws Exception {
        roleWith("DISPUTE_RULER", Privileges.AUTOMATION_VIEW, Privileges.AUTOMATION_MANAGE, Privileges.INVOICE_VIEW,
                Privileges.CUSTOMER_VIEW, Privileges.DISPUTE_VIEW, Privileges.DISPUTE_MANAGE,
                Privileges.SCOPE_OVERRIDE);
        User dora = user("dora.disputes", "DISPUTE_RULER");
        Map<String, Object> dispute = new LinkedHashMap<>();
        dispute.put("type", "CREATE_DISPUTE");
        dispute.put("reason", "Should {{Invoice.Number}} be cancelled?");
        dispute.put("proposedChange", "cancel");
        Long ruleId = saveRule(dora, rule("Review", "INVOICE", "MANUAL", null, dispute));

        assertThat(run(ruleId, admin, Map.of("recordIds", List.of(invoices.get(0)),
                "confirmOwnershipChange", true)).getStatus()).isEqualTo(202);
        drain();
        Dispute raised = disputeRepository.findAll().stream().filter(d -> ruleId.equals(d.getRuleId()))
                .findFirst().orElseThrow();
        assertThat(raised.getOpenedByUserId()).as("opened as the rule's new owner").isEqualTo(admin.getId());

        MockHttpServletResponse own = run(post("/api/disputes/" + raised.getId() + "/approve"), admin, Map.of());
        assertThat(own.getStatus()).isEqualTo(400);
        assertThat(json(own).get("message").asText())
                .isEqualTo("A change your own rule proposed must be approved by someone else");
        assertThat(disputeRepository.findById(raised.getId()).orElseThrow().getStatus())
                .isEqualTo(DisputeStatus.PENDING);
        assertThat(invoiceRepository.findById(invoices.get(0)).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.UNPAID);
    }

    @Test
    void aRunIsRefusedInAPastViewAndStopsAtTheRulesLimit() throws Exception {
        Long ruleId = saveRule(owen, with(byHand("By hand"), "maxRecordsPerRun", 2));
        MockHttpServletResponse past = run(post(RULES + "/" + ruleId + "/run")
                .param("asOf", MovableClock.SHARED.instant().minus(Duration.ofDays(1)).toString().substring(0, 10)),
                owen, Map.of());
        assertThat(past.getStatus()).isEqualTo(400);
        assertThat(json(past).get("error").asText()).isEqualTo(AsOfException.READ_ONLY);
        assertThat(events(AutomationEvent.MANUAL)).isEmpty();

        assertThat(run(ruleId, owen, Map.of()).getStatus()).isEqualTo(202);
        drain();
        AutomationRun run = runsOf(ruleId).get(0);
        assertThat(run.getMatched()).isEqualTo(2);
        assertThat(run.isTruncated()).isTrue();
        assertThat(run.getMessage()).isEqualTo("Stopped after 2 records, the rule's limit");
        assertThat(tasksOf(ruleId)).hasSize(2);
    }
}
