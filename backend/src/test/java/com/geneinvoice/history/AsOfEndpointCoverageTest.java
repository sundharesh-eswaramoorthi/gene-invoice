package com.geneinvoice.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.geneinvoice.approval.ApprovalOperation;
import com.geneinvoice.approval.ChangeOrigin;
import com.geneinvoice.approval.Gated;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.run.AutomationEffect;
import com.geneinvoice.automation.run.AutomationRun;
import com.geneinvoice.automation.run.EffectStatus;
import com.geneinvoice.automation.run.RunStatus;
import com.geneinvoice.CountingStatements;
import com.geneinvoice.common.scope.ViewScopeInterceptor;
import com.geneinvoice.common.scope.ViewScopeInterceptor.Mode;
import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.document.Document;
import com.geneinvoice.document.DocumentEntityType;
import com.geneinvoice.document.DocumentVisibility;
import com.geneinvoice.email.Email;
import com.geneinvoice.email.EmailDirection;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailDtos;
import com.geneinvoice.email.EmailStatus;
import com.geneinvoice.task.TaskDtos;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.task.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Every endpoint that answers as of a date really does (§8.2, critic R1 and C-1): asked about {@link #DAY} in the
 * {@link AsOfScenario} story, each one answers 200 under that date's header, its answer differs from today's, and
 * the answer comes from where the endpoint says it does: versions ({@code *_versions} are read) or its own
 * timestamps (a row that exists today is left out). Today's answer reads no version at all.
 *
 * <p>The endpoints are found from the handler mapping, so an endpoint made AWARE without a row here fails the test.
 * Later slices add their rows (tasks, approvals, rules, runs).
 */
class AsOfEndpointCoverageTest extends AsOfScenario {

    static final LocalDate DAY = D2;

    /** Where an endpoint's historical answer comes from. */
    enum Mechanism { VERSIONED, INTERVAL }

    /**
     * One endpoint's sample request. {@code witness}, when set, is a second request whose answer on {@link #DAY}
     * must differ from today's: for a by-id read whose record carries nothing historical (an email's delivery is
     * told as it is now), the proof that the date is applied is that an email written later is not there then.
     */
    record Probe(Mechanism mechanism, Supplier<MockHttpServletRequestBuilder> request,
                 Supplier<MockHttpServletRequestBuilder> witness) {
        static Probe of(Mechanism m, Supplier<MockHttpServletRequestBuilder> request) {
            return new Probe(m, request, null);
        }
    }

    /** Capability flags and the fields that differ by definition between a live and a historical answer. */
    static final Pattern FLAG = Pattern.compile("can[A-Z]\\w*|editTakesOwnership|blockedReason");
    static final Set<String> BY_DEFINITION = Set.of("asOf", "version");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    Long betaDispute;
    Long acmeDocument;
    Long laterDocument;
    Long failedEmail;
    Long laterEmail;
    Long betaTask;
    Long rejectedChange;
    Long laterChange;
    Long laterChangeOnB;
    Long chaseRule;
    Long laterRule;
    Long chaseRun;
    Long laterRun;

    @Autowired TaskService taskService;

    @BeforeEach
    void tabsWithSomethingAddedAfterTheDay() {
        betaDispute = betaDisputesB();
        on(D1);
        acmeDocument = document("contract.pdf");
        failedEmail = email(EmailStatus.FAILED);
        betaTask = task("Call Beta");
        rejectedChange = pendingCancel(b, beta, "100.00");
        chaseRule = saveRule(chase("Chase open"));
        chaseRun = automationRunRepository.save(run(chaseRule, 1L)).getId();
        effect(chaseRun, x);
        on(D3);
        PendingChange rejected = pendingChangeRepository.findById(rejectedChange).orElseThrow();
        rejected.reject(admin.getId(), "Not now", AppClock.now());
        pendingChangeRepository.save(rejected);
        laterChange = pendingCancel(x, acme, "250.00");
        laterChangeOnB = pendingCancel(b, beta, "150.00");
        laterDocument = document("renewal.pdf");
        laterEmail = email(EmailStatus.SENT);
        actAs(admin);
        taskService.patch(betaTask, new TaskDtos.PatchTaskRequest("Call Beta Group", null, null, null, null, 0L));
        task("Visit Beta");
        Map<String, Object> renamed = new HashMap<>(chase("Chase open invoices"));
        renamed.put("version", 0);
        rulesApi(put("/api/automation/rules/" + chaseRule), renamed);
        laterRule = saveRule(chase("Later rule"));
        // The run made on D1 acted again, and finished, on D3; another run was made on D3.
        effect(chaseRun, b);
        AutomationRun finished = automationRunRepository.findById(chaseRun).orElseThrow();
        finished.finish(RunStatus.SUCCEEDED, null, AppClock.now());
        automationRunRepository.save(finished);
        laterRun = automationRunRepository.save(run(chaseRule, 2L)).getId();
        SecurityContextHolder.clearContext();
        on(TODAY);
    }

    /** A rule on unpaid invoices: on {@link #DAY} X (part paid), Y and B; today X and B (Y is cancelled). */
    private static Map<String, Object> chase(String name) {
        return Map.of("name", name, "enabled", true,
                "trigger", Map.of("kind", "ENTITY_UPDATED", "entityType", "INVOICE"),
                "condition", Map.of("op", "AND", "children", List.of(Map.of("filter", "status:in:UNPAID,PARTIALLY_PAID"))),
                "actions", List.of(Map.of("type", "CREATE_TASK", "title", "Chase {{Invoice.Number}}")));
    }

    private Long saveRule(Map<String, Object> body) {
        return rulesApi(post("/api/automation/rules"), body).get("id").asLong();
    }

    private JsonNode rulesApi(MockHttpServletRequestBuilder request, Object body) {
        try {
            return objectMapper.readTree(mockMvc.perform(request.with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A run by hand of the rule, started now: as the consumer writes it. */
    private AutomationRun run(Long ruleId, Long eventId) {
        AutomationRun run = AutomationRun.builder().ruleId(ruleId).eventId(eventId).triggerType(AutomationEvent.MANUAL)
                .status(RunStatus.RUNNING).matched(2).createdAt(AppClock.now()).startedAt(AppClock.now())
                .scheduledFor(AppClock.now()).build();
        return run;
    }

    /** A task the run made on the invoice, now. */
    private void effect(Long runId, Long invoiceId) {
        automationEffectRepository.save(AutomationEffect.of(runId, chaseRule, 1L, "k-" + invoiceId, "CREATE_TASK",
                "INVOICE", invoiceId, "Invoice " + invoiceId, EffectStatus.DONE, "TASK", 1L, null, List.of(),
                "r" + chaseRule + ":k-" + invoiceId, AppClock.now()));
    }

    /** A task on Beta (in NORTH throughout), assigned to admin. */
    private Long task(String title) {
        actAs(admin);
        return taskService.create(new TaskDtos.CreateTaskRequest(TaskEntityType.CUSTOMER, beta.getId(), title, null,
                null, List.of(EmailDtos.EmailToken.user(admin.getId())))).getId();
    }

    /** A cancel held for approval, written as the gate writes it; nothing about the invoice changes. */
    private Long pendingCancel(Long invoiceId, com.geneinvoice.customer.Customer customer, String amount) {
        Gated g = new Gated(ApprovalOperation.INVOICE_CANCEL, invoiceId, "Invoice " + invoiceId, "INVOICE", invoiceId,
                null, customer.getId(), customer.getName(), north.getId(), null, new BigDecimal(amount),
                Map.of("invoiceId", invoiceId), Map.of("status", "UNPAID"), Map.of("status", "CANCELLED"),
                "Cancel invoice " + invoiceId, false);
        return pendingChangeRepository.save(PendingChange.pending(g, north, BigDecimal.ONE, sam.getId(),
                ChangeOrigin.USER, null, null, json(g.payload()), json(g.base()), json(g.proposed()),
                AppClock.now())).getId();
    }

    private Map<String, Probe> probes() {
        String acmeId = acme.getId().toString();
        String onAcme = "?entityType=CUSTOMER&entityId=" + acmeId;
        Map<String, Probe> p = new TreeMap<>();
        p.put("GET /api/customers", Probe.of(Mechanism.VERSIONED, () -> get("/api/customers")));
        p.put("GET /api/customers/summary", Probe.of(Mechanism.VERSIONED, () -> get("/api/customers/summary")));
        p.put("GET /api/customers/{id}", Probe.of(Mechanism.VERSIONED, () -> get("/api/customers/" + acmeId)));
        p.put("GET /api/customers/{id}/pocs",
                Probe.of(Mechanism.VERSIONED, () -> get("/api/customers/" + acmeId + "/pocs")));
        p.put("POST /api/customers/export", Probe.of(Mechanism.VERSIONED, () -> export("/api/customers/export")));
        p.put("GET /api/invoices", Probe.of(Mechanism.VERSIONED, () -> get("/api/invoices")));
        p.put("GET /api/invoices/summary", Probe.of(Mechanism.VERSIONED, () -> get("/api/invoices/summary")));
        p.put("GET /api/invoices/{id}", Probe.of(Mechanism.VERSIONED, () -> get("/api/invoices/" + x)));
        p.put("POST /api/invoices/export", Probe.of(Mechanism.VERSIONED, () -> export("/api/invoices/export")));
        p.put("GET /api/payments", Probe.of(Mechanism.VERSIONED, () -> get("/api/payments")));
        p.put("GET /api/payments/summary", Probe.of(Mechanism.VERSIONED, () -> get("/api/payments/summary")));
        p.put("GET /api/payments/{id}", Probe.of(Mechanism.VERSIONED, () -> get("/api/payments/" + paymentId)));
        p.put("GET /api/payments/credits/{customerId}",
                Probe.of(Mechanism.VERSIONED, () -> get("/api/payments/credits/" + acmeId)));
        p.put("POST /api/payments/export", Probe.of(Mechanism.VERSIONED, () -> export("/api/payments/export")));
        p.put("GET /api/promises", Probe.of(Mechanism.VERSIONED, () -> get("/api/promises")));
        p.put("GET /api/promises/summary", Probe.of(Mechanism.VERSIONED, () -> get("/api/promises/summary")));
        p.put("GET /api/promises/{id}", Probe.of(Mechanism.VERSIONED, () -> get("/api/promises/" + promiseId)));
        p.put("POST /api/promises/export", Probe.of(Mechanism.VERSIONED, () -> export("/api/promises/export")));
        p.put("GET /api/disputes", Probe.of(Mechanism.VERSIONED, () -> get("/api/disputes")));
        p.put("GET /api/disputes/{id}", Probe.of(Mechanism.VERSIONED, () -> get("/api/disputes/" + betaDispute)));
        p.put("POST /api/disputes/export", Probe.of(Mechanism.VERSIONED, () -> export("/api/disputes/export")));
        p.put("GET /api/products", Probe.of(Mechanism.VERSIONED, () -> get("/api/products")));
        p.put("GET /api/products/{id}", Probe.of(Mechanism.VERSIONED, () -> get("/api/products/" + widget.getId())));
        p.put("POST /api/products/export", Probe.of(Mechanism.VERSIONED, () -> export("/api/products/export")));
        for (String figure : List.of("billed-by-month", "outstanding-by-age", "top-outstanding-customers",
                "collected-by-month", "top-paying-customers")) {
            p.put("GET /api/dashboard/" + figure, Probe.of(Mechanism.VERSIONED, () -> get("/api/dashboard/" + figure)));
        }
        String onBeta = "?entityType=CUSTOMER&entityId=" + beta.getId();
        p.put("GET /api/tasks", Probe.of(Mechanism.VERSIONED, () -> get("/api/tasks")));
        p.put("GET /api/tasks/summary", Probe.of(Mechanism.VERSIONED, () -> get("/api/tasks/summary")));
        p.put("GET /api/tasks/mine", Probe.of(Mechanism.VERSIONED, () -> get("/api/tasks/mine")));
        p.put("GET /api/tasks/mine/summary", Probe.of(Mechanism.VERSIONED, () -> get("/api/tasks/mine/summary")));
        p.put("GET /api/tasks/mine/count", Probe.of(Mechanism.VERSIONED, () -> get("/api/tasks/mine/count")));
        p.put("GET /api/tasks/count", Probe.of(Mechanism.VERSIONED, () -> get("/api/tasks/count" + onBeta)));
        p.put("GET /api/tasks/{id}", Probe.of(Mechanism.VERSIONED, () -> get("/api/tasks/" + betaTask)));
        p.put("POST /api/tasks/export", Probe.of(Mechanism.VERSIONED, () -> export("/api/tasks/export")));
        p.put("GET /api/audit", Probe.of(Mechanism.INTERVAL, () -> get("/api/audit" + onAcme)));
        p.put("GET /api/documents", Probe.of(Mechanism.INTERVAL, () -> get("/api/documents" + onAcme)));
        p.put("GET /api/documents/count", Probe.of(Mechanism.INTERVAL, () -> get("/api/documents/count" + onAcme)));
        p.put("GET /api/emails", Probe.of(Mechanism.INTERVAL, () -> get("/api/emails" + onAcme)));
        p.put("GET /api/emails/{id}", new Probe(Mechanism.INTERVAL, () -> get("/api/emails/" + failedEmail),
                () -> get("/api/emails/" + laterEmail)));
        // Rejected after the day: pending then. Asked for after it: not there then.
        p.put("GET /api/approvals/{id}", new Probe(Mechanism.INTERVAL,
                () -> get("/api/approvals/" + rejectedChange), () -> get("/api/approvals/" + laterChange)));
        // On the day only the change on B later rejected was waiting; today the two asked for after it are.
        p.put("GET /api/approvals", Probe.of(Mechanism.INTERVAL, () -> get("/api/approvals")));
        p.put("GET /api/approvals/summary", Probe.of(Mechanism.INTERVAL, () -> get("/api/approvals/summary")));
        p.put("GET /api/approvals/awaiting-me/count",
                Probe.of(Mechanism.INTERVAL, () -> get("/api/approvals/awaiting-me/count")));
        p.put("GET /api/approvals/for-record", Probe.of(Mechanism.INTERVAL,
                () -> get("/api/approvals/for-record?entityType=INVOICE&entityId=" + b)));
        p.put("POST /api/approvals/export", Probe.of(Mechanism.INTERVAL, () -> export("/api/approvals/export")));
        // Rules as of a date come from their revisions: the later rule is not there then, the other has its old name.
        String rule = "/api/automation/rules/" + chaseRule;
        p.put("GET /api/automation/rules", Probe.of(Mechanism.INTERVAL, () -> get("/api/automation/rules")));
        p.put("GET /api/automation/rules/summary", new Probe(Mechanism.INTERVAL,
                () -> get("/api/automation/rules/summary"), () -> get("/api/automation/rules/" + laterRule)));
        p.put("GET /api/automation/rules/{id}", new Probe(Mechanism.INTERVAL, () -> get(rule),
                () -> get("/api/automation/rules/" + laterRule)));
        // A preview evaluates the records as they were.
        p.put("POST /api/automation/rules/preview", Probe.of(Mechanism.VERSIONED,
                () -> post("/api/automation/rules/preview").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("rule", chase("Chase open"), "sampleSize", 10)))));
        p.put("GET /api/automation/rules/{id}/preview", Probe.of(Mechanism.VERSIONED, () -> get(rule + "/preview")));
        p.put("GET /api/automation/rules/{id}/matches", Probe.of(Mechanism.VERSIONED, () -> get(rule + "/matches")));
        // Runs and effects as of a date are those made by then; a run says where it stood then.
        p.put("GET /api/automation/rules/{id}/runs", Probe.of(Mechanism.INTERVAL, () -> get(rule + "/runs")));
        p.put("GET /api/automation/runs/{id}", new Probe(Mechanism.INTERVAL,
                () -> get("/api/automation/runs/" + chaseRun), () -> get("/api/automation/runs/" + laterRun)));
        p.put("GET /api/automation/runs/{id}/effects", Probe.of(Mechanism.INTERVAL,
                () -> get("/api/automation/runs/" + chaseRun + "/effects")));
        return p;
    }

    /** Every AWARE handler under /api, as "METHOD /path/{pattern}". */
    private Set<String> awareEndpoints() {
        Set<String> out = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            for (RequestMethod method : e.getKey().getMethodsCondition().getMethods()) {
                for (String path : e.getKey().getPatternValues()) {
                    if (!path.startsWith("/api/")) continue;
                    if (ViewScopeInterceptor.classify(e.getValue(), method.name()) == Mode.AWARE) {
                        out.add(method.name() + " " + path);
                    }
                }
            }
        }
        return out;
    }

    @Test
    void everyEndpointThatAnswersAsOfADateHasASampleHere() {
        assertThat(probes().keySet()).as("add a row for each new AWARE endpoint")
                .containsExactlyInAnyOrderElementsOf(awareEndpoints());
    }

    @Test
    void everyAwareEndpointAnswersThePastFromHistoryAndDiffersFromToday() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Probe> e : probes().entrySet()) {
            String endpoint = e.getKey();
            Probe probe = e.getValue();

            MockHttpServletResponse[] live = new MockHttpServletResponse[1];
            long liveVersionReads = CountingStatements.reads("_versions", () -> live[0] = send(probe.request(), null));
            MockHttpServletResponse[] past = new MockHttpServletResponse[1];
            long pastVersionReads = CountingStatements.reads("_versions", () -> past[0] = send(probe.request(), DAY));

            if (live[0].getStatus() != 200) problems.add(endpoint + ": live answered " + live[0].getStatus());
            if (liveVersionReads != 0) problems.add(endpoint + ": live read versions " + liveVersionReads + " times");
            if (past[0].getStatus() != 200) {
                problems.add(endpoint + ": " + DAY + " answered " + past[0].getStatus() + " " + text(past[0]));
                continue;
            }
            if (!DAY.toString().equals(past[0].getHeader(ViewScopeInterceptor.HEADER))) {
                problems.add(endpoint + ": header " + past[0].getHeader(ViewScopeInterceptor.HEADER));
            }
            boolean differs = !comparable(past[0]).equals(comparable(live[0]));
            if (probe.witness() != null) {
                MockHttpServletResponse liveWitness = send(probe.witness(), null);
                MockHttpServletResponse pastWitness = send(probe.witness(), DAY);
                differs = liveWitness.getStatus() != pastWitness.getStatus()
                        || !comparable(liveWitness).equals(comparable(pastWitness));
            }
            if (!differs) problems.add(endpoint + ": the answer on " + DAY + " is today's");

            switch (probe.mechanism()) {
                case VERSIONED -> {
                    if (pastVersionReads == 0) problems.add(endpoint + ": read no versions");
                }
                case INTERVAL -> {
                    if (!leavesOutALiveRow(endpoint, live[0], past[0], probe)) {
                        problems.add(endpoint + ": its interval left out nothing that exists today");
                    }
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    /** Whether the historical answer lacks something today's has: an entry, a row, a count, or a later record. */
    private boolean leavesOutALiveRow(String endpoint, MockHttpServletResponse live, MockHttpServletResponse past,
                                      Probe probe) throws Exception {
        if (probe.witness() != null) {
            return send(probe.witness(), null).getStatus() == 200 && send(probe.witness(), DAY).getStatus() == 404;
        }
        String type = live.getContentType();
        if (type != null && type.contains("csv")) {
            Set<String> thenLines = new HashSet<>(List.of(text(past).split("\r\n")));
            return List.of(text(live).split("\r\n")).stream().anyMatch(line -> !thenLines.contains(line));
        }
        JsonNode now = objectMapper.readTree(text(live));
        JsonNode then = objectMapper.readTree(text(past));
        if (endpoint.endsWith("/count")) return then.get("count").asLong() < now.get("count").asLong();
        if (now.has("pendingCount")) return then.get("pendingCount").asLong() < now.get("pendingCount").asLong();
        JsonNode nowRows = now.isArray() ? now : now.get("content");
        JsonNode thenRows = then.isArray() ? then : then.get("content");
        Set<String> thenIds = new HashSet<>();
        thenRows.forEach(r -> thenIds.add(r.path("id").asText()));
        for (JsonNode r : nowRows) {
            if (!r.path("id").isMissingNode() && !r.path("id").isNull() && !thenIds.contains(r.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    /** The body with what differs by definition (the date, row versions, capability flags) taken out. */
    private String comparable(MockHttpServletResponse response) throws Exception {
        String body = text(response);
        String type = response.getContentType();
        if (type == null || !type.contains("json") || body.isBlank()) return response.getStatus() + " " + body;
        JsonNode node = objectMapper.readTree(body);
        strip(node);
        return response.getStatus() + " " + node;
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            for (Iterator<String> names = obj.fieldNames(); names.hasNext(); ) {
                String name = names.next();
                if (BY_DEFINITION.contains(name) || FLAG.matcher(name).matches()) names.remove();
            }
            obj.elements().forEachRemaining(AsOfEndpointCoverageTest::strip);
        } else if (node != null && node.isArray()) {
            node.forEach(AsOfEndpointCoverageTest::strip);
        }
    }

    private MockHttpServletResponse send(Supplier<MockHttpServletRequestBuilder> request, LocalDate day)
            throws Exception {
        return call(request.get(), admin, day).andReturn().getResponse();
    }

    private static String text(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    private MockHttpServletRequestBuilder export(String url) {
        return post(url).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("selectAllMatchingFilter", true, "filters", List.of())));
    }

    private Long document(String filename) {
        return documentRepository.save(Document.builder()
                .entityType(DocumentEntityType.CUSTOMER).entityId(acme.getId()).entityLabel("Customer Acme Ltd")
                .customerId(acme.getId()).filename(filename).contentType("application/pdf").sizeBytes(10)
                .checksum("0".repeat(64)).storageKey("customers/" + acme.getId() + "/" + filename)
                .visibility(DocumentVisibility.INTERNAL).uploadedByUserId(admin.getId()).uploadedByName("Admin")
                .uploadedAt(AppClock.now())
                .build()).getId();
    }

    private Long email(EmailStatus status) {
        return emailRepository.save(Email.builder()
                .entityType(EmailEntityType.CUSTOMER).entityId(acme.getId()).entityLabel("Customer Acme Ltd")
                .direction(EmailDirection.OUTBOUND).status(status).subject("About your account")
                .fromUserId(admin.getId()).fromName("Admin").fromInternal(true).sentByUserId(admin.getId())
                .build()).getId();
    }
}
