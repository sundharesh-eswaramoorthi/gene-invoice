package com.geneinvoice.automation.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.MovableClock;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.automation.AutomationTestBase;
import com.geneinvoice.automation.run.AutomationRun;
import com.geneinvoice.automation.run.RunStatus;
import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.email.EmailText;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rules over HTTP (§6.14, §5.10.7, §5.10.10): whoever saves a rule owns it and must hold what it needs where it acts;
 * someone else changes it only as an administrator who takes it over; every change is a revision, and the rules as
 * of a date are read from those; a rule is seen by its owner, administrators and whoever works where it acts.
 */
class RuleApiTest extends AutomationTestBase {

    @Autowired AuditLogRepository auditLogRepository;
    @Autowired TransactionTemplate tx;

    User admin;
    /** Collections, default region at APPROVE/ALL: may make rules of their own. */
    User cara;
    /** Sales, default region: may make rules, and is no administrator. */
    User sam;
    Region north;
    Region south;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        cara = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        north = region("NORTH");
        south = region("SOUTH");
    }

    private Map<String, Object> chase(String name) {
        return rule(name, "INVOICE", "ENTITY_UPDATED", and(leaf("status:in:UNPAID,PARTIALLY_PAID")),
                task("Chase {{Invoice.Number}}"));
    }

    private JsonNode read(MockHttpServletRequestBuilder request, User caller) throws Exception {
        return body(mockMvc.perform(request.with(as(caller))).andExpect(status().isOk()));
    }

    private List<String> names(JsonNode page) {
        return StreamSupport.stream(page.get("content").spliterator(), false).map(n -> n.get("name").asText())
                .sorted().toList();
    }

    private List<String> auditActions(Long ruleId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("RULE", ruleId).stream()
                .sorted(Comparator.comparing(AuditLog::getId)).map(AuditLog::getAction).toList();
    }

    @Test
    void savingARuleMakesTheCallerItsOwner() throws Exception {
        Map<String, Object> body = chase("Chase unpaid");
        Map<String, Object> keyless = task("Call {{Customer.Name}}");
        keyless.remove("key");
        body.put("actions", List.of(((List<?>) body.get("actions")).get(0), keyless));

        JsonNode saved = body(send(post(RULES), cara, body).andExpect(status().isOk()));
        assertThat(saved.get("owner").get("id").asLong()).isEqualTo(cara.getId());
        assertThat(saved.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(saved.get("canEdit").asBoolean()).isTrue();
        assertThat(saved.get("canRun").asBoolean()).isTrue();
        assertThat(saved.get("editTakesOwnership").asBoolean()).isFalse();
        assertThat(saved.get("actions").get(1).get("key").asText()).as("the server gives a keyless action a key")
                .hasSize(36);
        assertThat(saved.get("listLink").asText()).isEqualTo("/invoices?f=status%3Ain%3AUNPAID%2CPARTIALLY_PAID");
        assertThat(saved.get("trigger").get("zone").asText()).isEqualTo("UTC");
        Long id = saved.get("id").asLong();

        AutomationRule stored = automationRuleRepository.findById(id).orElseThrow();
        assertThat(stored.getOwnerUserId()).isEqualTo(cara.getId());
        assertThat(stored.getCreatedByUserId()).isEqualTo(cara.getId());
        assertThat(stored.getActiveSince()).isNotNull();
        assertThat(automationRuleRevisionRepository.findByRuleIdOrderByIdAsc(id)).hasSize(1);
        assertThat(auditActions(id)).containsExactly("RULE_CREATED");

        JsonNode read = read(get(RULES + "/" + id), cara);
        assertThat(read.get("actions")).isEqualTo(saved.get("actions"));
        assertThat(read.get("condition")).isEqualTo(saved.get("condition"));

        JsonNode taken = body(send(post(RULES), sam, chase("chase UNPAID")).andExpect(status().isBadRequest()));
        assertThat(taken.get("fieldErrors").get("name").asText()).isEqualTo("Another rule is called chase UNPAID");
    }

    @Test
    void someoneElsesRuleIsChangedOnlyByAnAdministratorWhoTakesItOver() throws Exception {
        Long id = saveRule(cara, chase("Chase unpaid"));
        Map<String, Object> renamed = with(chase("Chase everything"), "version", 0);

        send(put(RULES + "/" + id), sam, renamed).andExpect(status().isForbidden());
        JsonNode asked = body(send(put(RULES + "/" + id), admin, renamed).andExpect(status().isBadRequest()));
        assertThat(asked.get("fieldErrors").get("confirmOwnershipChange").asText())
                .startsWith("This makes you the rule's owner");
        send(post(RULES + "/" + id + "/disable"), sam, Map.of()).andExpect(status().isForbidden());
        AutomationRule unchanged = automationRuleRepository.findById(id).orElseThrow();
        assertThat(unchanged.getName()).isEqualTo("Chase unpaid");
        assertThat(unchanged.getOwnerUserId()).isEqualTo(cara.getId());
        assertThat(unchanged.getVersion()).isZero();
        assertThat(automationRuleRevisionRepository.findByRuleIdOrderByIdAsc(id)).hasSize(1);

        JsonNode asCara = read(get(RULES + "/" + id), admin);
        assertThat(asCara.get("editTakesOwnership").asBoolean()).isTrue();
        assertThat(asCara.get("canEdit").asBoolean()).isFalse();

        JsonNode taken = body(send(put(RULES + "/" + id), admin, with(renamed, "confirmOwnershipChange", true))
                .andExpect(status().isOk()));
        assertThat(taken.get("owner").get("id").asLong()).isEqualTo(admin.getId());
        assertThat(taken.get("name").asText()).isEqualTo("Chase everything");
        assertThat(auditActions(id)).containsExactly("RULE_CREATED", "RULE_UPDATED", "RULE_OWNERSHIP_TAKEN");
        JsonNode nowCaras = read(get(RULES + "/" + id), cara);
        assertThat(nowCaras.get("canEdit").asBoolean()).isFalse();
        assertThat(nowCaras.get("editTakesOwnership").asBoolean()).as("cara is no administrator").isFalse();

        // Turning someone else's rule on makes it act: that too is taken over first.
        Long other = saveRule(cara, with(chase("Chase later"), "enabled", false));
        send(post(RULES + "/" + other + "/enable"), admin, Map.of()).andExpect(status().isBadRequest());
        assertThat(automationRuleRepository.findById(other).orElseThrow().isEnabled()).isFalse();
        JsonNode enabled = body(send(post(RULES + "/" + other + "/enable"), admin,
                Map.of("confirmOwnershipChange", true)).andExpect(status().isOk()));
        assertThat(enabled.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(enabled.get("owner").get("id").asLong()).isEqualTo(admin.getId());
        // Stopping one is not taking it over.
        Long third = saveRule(cara, chase("Chase third"));
        JsonNode stopped = body(send(post(RULES + "/" + third + "/disable"), admin, Map.of())
                .andExpect(status().isOk()));
        assertThat(stopped.get("status").asText()).isEqualTo("DISABLED");
        assertThat(stopped.get("owner").get("id").asLong()).isEqualTo(cara.getId());
    }

    @Test
    void aStaleVersionIsAConflict() throws Exception {
        Long id = saveRule(cara, chase("Chase unpaid"));
        send(put(RULES + "/" + id), cara, with(chase("Chase one"), "version", 0)).andExpect(status().isOk());
        send(put(RULES + "/" + id), cara, with(chase("Chase two"), "version", 0)).andExpect(status().isConflict());
        AutomationRule stored = automationRuleRepository.findById(id).orElseThrow();
        assertThat(stored.getName()).isEqualTo("Chase one");
        assertThat(stored.getVersion()).isEqualTo(1L);
    }

    /** A PUT that leaves out {@code enabled} or {@code maxRecordsPerRun} keeps them as the rule has them. */
    @Test
    void aChangeThatLeavesOutEnabledOrTheLimitKeepsThem() throws Exception {
        Long id = saveRule(cara, with(chase("Chase unpaid"), "maxRecordsPerRun", 250));
        send(post(RULES + "/" + id + "/disable"), cara, Map.of()).andExpect(status().isOk());
        Map<String, Object> renamed = chase("Chase renamed");
        renamed.remove("enabled");
        renamed.remove("maxRecordsPerRun");

        JsonNode saved = body(send(put(RULES + "/" + id), cara, renamed).andExpect(status().isOk()));

        assertThat(saved.get("status").asText()).isEqualTo("DISABLED");
        AutomationRule stored = automationRuleRepository.findById(id).orElseThrow();
        assertThat(stored.getName()).isEqualTo("Chase renamed");
        assertThat(stored.isEnabled()).isFalse();
        assertThat(stored.getMaxRecordsPerRun()).isEqualTo(250);
    }

    @Test
    void whoeverSavesARuleMustHoldWhatItNeeds() throws Exception {
        User nate = user("nate.no.tasks", roleWith("AUTOMATION_WITHOUT_TASKS", Privileges.AUTOMATION_VIEW,
                Privileges.AUTOMATION_MANAGE, Privileges.INVOICE_VIEW).getName());
        JsonNode refused = body(send(post(RULES), nate, chase("Chase unpaid")).andExpect(status().isBadRequest()));
        assertThat(refused.get("fieldErrors").get("actions").asText())
                .isEqualTo("This rule needs TASK_MANAGE, which you do not hold, so it could never act");
        assertThat(automationRuleRepository.count()).isZero();
    }

    @Test
    void aRuleIsRestrictedOnlyToRegionsWhereItsOwnerMayDoWhatItDoes() throws Exception {
        grant(cara, north, AccessLevel.READ, RecordScope.ALL);
        JsonNode outside = body(send(post(RULES), cara, with(chase("South only"), "regionIds", List.of(south.getId())))
                .andExpect(status().isBadRequest()));
        assertThat(outside.get("fieldErrors").get("regionIds").asText())
                .isEqualTo("You cannot do what this rule does in South");
        JsonNode readOnly = body(send(post(RULES), cara, with(chase("North only"), "regionIds", List.of(north.getId())))
                .andExpect(status().isBadRequest()));
        assertThat(readOnly.get("fieldErrors").get("regionIds").asText()).as("TASK_MANAGE needs MANAGE there")
                .isEqualTo("You cannot do what this rule does in North");
        assertThat(automationRuleRepository.count()).isZero();

        JsonNode own = body(send(post(RULES), cara, with(chase("Default only"), "regionIds",
                List.of(defaultRegion().getId()))).andExpect(status().isOk()));
        assertThat(own.get("regionIds")).extracting(JsonNode::asLong).containsExactly(defaultRegion().getId());
        Long ownId = own.get("id").asLong();
        List<Long> stored = tx.execute(s -> List.copyOf(automationRuleRepository.findById(ownId).orElseThrow()
                .getRegionIds()));
        assertThat(stored).containsExactly(defaultRegion().getId());
    }

    /** "Updated" means a write to the record; becoming overdue is not one (§5.8.3, PC-5). */
    @Test
    void anUpdatedRuleOnOverdueIsSavedWithAWarning() throws Exception {
        JsonNode updated = body(send(post(RULES), cara, rule("Overdue changes", "INVOICE", "ENTITY_UPDATED",
                and(leaf("overdue:eq:true"), leaf("invoiceDate:relative:last30Days")), task("Chase")))
                .andExpect(status().isOk()));
        assertThat(updated.get("warnings")).extracting(JsonNode::asText)
                .containsExactly("Becoming overdue is not an update; use a daily schedule to catch it");
        assertThat(read(get(RULES + "/" + updated.get("id").asLong()), cara).get("warnings"))
                .extracting(JsonNode::asText).contains("Becoming overdue is not an update; use a daily schedule to "
                        + "catch it");

        JsonNode daily = body(send(post(RULES), cara, rule("Overdue daily", "INVOICE", "SCHEDULE_DAILY",
                and(leaf("overdue:eq:true")), task("Chase"))).andExpect(status().isOk()));
        assertThat(daily.get("warnings")).isEmpty();
        assertThat(daily.get("trigger").get("time").asText()).isEqualTo("09:00");
        assertThat(daily.get("trigger").get("catchUp").asText()).isEqualTo("ONE");

        JsonNode badTime = body(send(post(RULES), cara, rule("Weekly", "INVOICE", "SCHEDULE_WEEKLY", null,
                task("Chase"))).andExpect(status().isOk()));
        assertThat(badTime.get("trigger").get("day").asText()).isEqualTo("MONDAY");
        Map<String, Object> noon = rule("Noonish", "INVOICE", "SCHEDULE_DAILY", null, task("Chase"));
        noon.put("trigger", Map.of("kind", "SCHEDULE_DAILY", "entityType", "INVOICE", "time", "25:00",
                "zone", "Mars/Olympus"));
        JsonNode refused = body(send(post(RULES), cara, noon).andExpect(status().isBadRequest()));
        assertThat(refused.get("fieldErrors").get("trigger.time").asText()).isEqualTo("must be a time of day, HH:mm");
        assertThat(refused.get("fieldErrors").get("trigger.zone").asText()).isEqualTo("is not a time zone");
    }

    @Test
    void aDeletedRuleIsGoneButItsRevisionsAndTrailStay() throws Exception {
        Long id = saveRule(cara, chase("Chase unpaid"));
        mockMvc.perform(delete(RULES + "/" + id).with(as(sam))).andExpect(status().isForbidden());
        mockMvc.perform(delete(RULES + "/" + id).with(as(cara))).andExpect(status().isNoContent());

        mockMvc.perform(get(RULES + "/" + id).with(as(cara))).andExpect(status().isNotFound());
        assertThat(read(get(RULES), cara).get("totalElements").asLong()).isZero();
        AutomationRule gone = automationRuleRepository.findById(id).orElseThrow();
        assertThat(gone.getDeletedAt()).isNotNull();
        assertThat(gone.isEnabled()).isFalse();
        List<AutomationRuleRevision> revisions = automationRuleRevisionRepository.findByRuleIdOrderByIdAsc(id);
        assertThat(revisions).extracting(AutomationRuleRevision::isDeleted).containsExactly(false, true);
        JsonNode trail = read(get("/api/audit").param("entityType", "RULE").param("entityId", id.toString()), admin);
        assertThat(trail).extracting(n -> n.get("action").asText()).contains("RULE_CREATED", "RULE_DELETED");

        // The name is free again.
        saveRule(cara, chase("Chase unpaid"));
    }

    @Test
    void theSchemasOfRulesAndRunsAreReadByThoseWhoMaySeeRules() throws Exception {
        JsonNode rules = read(get("/api/table-schemas/automation-rules"), cara);
        assertThat(rules.get("defaultSort").asText()).isEqualTo("name,asc");
        assertThat(rules.get("columns")).extracting(c -> c.get("name").asText())
                .contains("name", "status", "triggerKind", "entityType", "ownerUserId", "regionIds", "lastRunAt");
        JsonNode runs = read(get("/api/table-schemas/automation-runs"), cara);
        assertThat(runs.get("defaultSort").asText()).isEqualTo("createdAt,desc");
        User viewer = user("vera.viewer", "VIEWER");
        mockMvc.perform(get("/api/table-schemas/automation-rules").with(as(viewer))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/table-schemas/automation-runs").with(as(viewer))).andExpect(status().isForbidden());
        mockMvc.perform(get(RULES).with(as(viewer))).andExpect(status().isForbidden());
    }

    @Test
    void aWriteAskedAsOfAPastDateIsRefused() throws Exception {
        String yesterday = Today.date().minusDays(1).toString();
        JsonNode refused = body(send(post(RULES).param("asOf", yesterday), cara, chase("Chase unpaid"))
                .andExpect(status().isBadRequest()));
        assertThat(refused.get("error").asText()).isEqualTo("AS_OF_READ_ONLY");
        Long id = saveRule(cara, chase("Chase unpaid"));
        send(put(RULES + "/" + id).param("asOf", yesterday), cara, chase("Other")).andExpect(status().isBadRequest());
        send(post(RULES + "/" + id + "/disable").param("asOf", yesterday), cara, Map.of())
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete(RULES + "/" + id).param("asOf", yesterday).with(as(cara)))
                .andExpect(status().isBadRequest());
        assertThat(automationRuleRepository.findAll()).extracting(AutomationRule::getName).containsExactly("Chase unpaid");
        assertThat(automationRuleRevisionRepository.count()).isEqualTo(1);
    }

    @Test
    void everyChangeIsARevision() throws Exception {
        Long id = saveRule(cara, chase("Chase unpaid"));
        send(put(RULES + "/" + id), cara, with(chase("Chase all"), "version", 0)).andExpect(status().isOk());
        send(post(RULES + "/" + id + "/disable"), cara, Map.of()).andExpect(status().isOk());
        send(post(RULES + "/" + id + "/enable"), cara, Map.of()).andExpect(status().isOk());
        mockMvc.perform(delete(RULES + "/" + id).with(as(cara))).andExpect(status().isNoContent());

        List<AutomationRuleRevision> revisions = automationRuleRevisionRepository.findByRuleIdOrderByIdAsc(id);
        assertThat(revisions).extracting(AutomationRuleRevision::getName)
                .containsExactly("Chase unpaid", "Chase all", "Chase all", "Chase all", "Chase all");
        assertThat(revisions).extracting(AutomationRuleRevision::isEnabled)
                .containsExactly(true, true, false, true, false);
        assertThat(revisions).extracting(AutomationRuleRevision::getRuleVersion).containsExactly(0L, 1L, 2L, 3L, 4L);
        assertThat(revisions).extracting(AutomationRuleRevision::getSavedByUserId).containsOnly(cara.getId());
        assertThat(auditActions(id)).containsExactly("RULE_CREATED", "RULE_UPDATED", "RULE_DISABLED",
                "RULE_ENABLED", "RULE_DELETED");
    }

    /** The rules page as of a date is read from the revisions saved by then (D9). */
    @Test
    void theRulesAsOfADateAreTheirRevisionsThen() throws Exception {
        LocalDate today = LocalDate.ofInstant(MovableClock.SHARED.instant(), ZoneOffset.UTC);
        LocalDate d1 = today.minusDays(10);
        LocalDate d2 = today.minusDays(9);
        LocalDate d3 = today.minusDays(8);
        MovableClock.SHARED.set(d1.atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC));
        customer("History starts");
        Long alpha = saveRule(cara, chase("Alpha"));
        MovableClock.SHARED.set(d3.atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC));
        send(put(RULES + "/" + alpha), cara, with(chase("Alpha renamed"), "version", 0)).andExpect(status().isOk());
        Long beta = saveRule(cara, chase("Beta"));
        MovableClock.SHARED.reset();

        JsonNode then = read(get(RULES).param("asOf", d2.toString()), cara);
        assertThat(names(then)).containsExactly("Alpha");
        assertThat(then.get("asOf").asText()).isEqualTo(d2.toString());
        assertThat(names(read(get(RULES), cara))).containsExactly("Alpha renamed", "Beta");
        assertThat(names(read(get(RULES).param("asOf", d3.toString()), cara))).containsExactly("Alpha renamed", "Beta");
        assertThat(names(read(get(RULES).param("asOf", d2.toString()).param("filter", "name:contains:alp"), cara)))
                .containsExactly("Alpha");
        assertThat(names(read(get(RULES).param("asOf", d2.toString()).param("filter", "entityType:in:CUSTOMER"),
                cara))).isEmpty();
        JsonNode unsupported = body(mockMvc.perform(get(RULES).param("asOf", d2.toString())
                .param("filter", "lastRunStatus:eq:FAILED").with(as(cara))).andExpect(status().isBadRequest()));
        assertThat(unsupported.get("message").asText()).isEqualTo("This filter isn't available for past dates");

        JsonNode alphaThen = read(get(RULES + "/" + alpha).param("asOf", d2.toString()), cara);
        assertThat(alphaThen.get("name").asText()).isEqualTo("Alpha");
        assertThat(alphaThen.get("canEdit").asBoolean()).isFalse();
        assertThat(alphaThen.get("canRun").asBoolean()).isFalse();
        mockMvc.perform(get(RULES + "/" + beta).param("asOf", d2.toString()).with(as(cara)))
                .andExpect(status().isNotFound());
        JsonNode summaryThen = read(get(RULES + "/summary").param("asOf", d2.toString()), cara);
        assertThat(summaryThen.get("active").asLong()).isEqualTo(1);
    }

    /** CT-6: a reloaded rule names the people its actions name, now and as of a date after it was saved. */
    @Test
    void aRuleNamesThePeopleItsActionsName() throws Exception {
        LocalDate today = LocalDate.ofInstant(MovableClock.SHARED.instant(), ZoneOffset.UTC);
        LocalDate d1 = today.minusDays(5);
        MovableClock.SHARED.set(d1.atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC));
        customer("History starts");
        Long id = saveRule(admin, rule("Chase with people", "INVOICE", "ENTITY_UPDATED", null,
                task("Chase {{Invoice.Number}}", userToken(cara)),
                email(null, List.of(userToken(sam)), "About {{Invoice.Number}}", "Hello")));
        Long plain = saveRule(admin, chase("No people"));
        MovableClock.SHARED.reset();
        User caraNow = userRepository.findById(cara.getId()).orElseThrow();
        User samNow = userRepository.findById(sam.getId()).orElseThrow();
        Map<String, String> expected = Map.of(cara.getId().toString(), EmailText.nameOf(caraNow),
                sam.getId().toString(), EmailText.nameOf(samNow));

        JsonNode now = read(get(RULES + "/" + id), admin);
        assertThat(objectMapper.convertValue(now.get("people"), Map.class)).isEqualTo(expected);
        JsonNode then = read(get(RULES + "/" + id).param("asOf", d1.plusDays(1).toString()), admin);
        assertThat(objectMapper.convertValue(then.get("people"), Map.class)).isEqualTo(expected);
        assertThat(read(get(RULES + "/" + plain), admin).get("people").isEmpty()).isTrue();
    }

    private List<String> namesInOrder(JsonNode page) {
        return StreamSupport.stream(page.get("content").spliterator(), false).map(n -> n.get("name").asText())
                .toList();
    }

    /** PC-2: as of a date, the rules list sorts and filters as it does live, but on what only the live rule knows. */
    @Test
    void theRulesAsOfADateSortAndFilterAsTheyDoLive() throws Exception {
        grant(admin, north, AccessLevel.APPROVE, RecordScope.ALL);
        LocalDate today = LocalDate.ofInstant(MovableClock.SHARED.instant(), ZoneOffset.UTC);
        LocalDate d1 = today.minusDays(5);
        String asOf = d1.plusDays(1).toString();
        MovableClock.SHARED.set(d1.atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC));
        customer("History starts");
        saveRule(cara, chase("Alpha"));
        saveRule(admin, with(rule("Beta", "CUSTOMER", "ENTITY_CREATED", null, task("Call {{Customer.Name}}")),
                "regionIds", List.of(north.getId())));
        saveRule(admin, with(rule("Gamma", "PAYMENT", "MANUAL", null, task("Check the payment")),
                "enabled", false));
        MovableClock.SHARED.reset();

        assertThat(namesInOrder(read(get(RULES).param("asOf", asOf).param("sort", "triggerKind,asc"), admin)))
                .containsExactly("Beta", "Alpha", "Gamma");
        assertThat(namesInOrder(read(get(RULES).param("asOf", asOf).param("sort", "entityType,desc"), admin)))
                .containsExactly("Gamma", "Alpha", "Beta");
        assertThat(names(read(get(RULES).param("asOf", asOf).param("filter", "status:eq:ACTIVE"), admin)))
                .containsExactly("Alpha", "Beta");
        assertThat(names(read(get(RULES).param("asOf", asOf).param("filter", "ownerUserId:eq:" + cara.getId()),
                admin))).containsExactly("Alpha");
        assertThat(names(read(get(RULES).param("asOf", asOf).param("filter", "regionIds:in:" + north.getId()),
                admin))).as("an unrestricted rule is not one restricted to NORTH").containsExactly("Beta");
        assertThat(names(read(get(RULES).param("asOf", asOf).param("filter", "regionIds:isEmpty:"), admin)))
                .containsExactly("Alpha", "Gamma");

        // Nothing has changed since, so each answers exactly as the live list does.
        for (String sort : List.of("triggerKind,asc", "triggerKind,desc", "entityType,asc", "entityType,desc",
                "suspendedReason,asc", "createdAt,desc", "updatedAt,asc", "name,desc", "enabled,asc")) {
            assertThat(namesInOrder(read(get(RULES).param("asOf", asOf).param("sort", sort), admin))).as(sort)
                    .containsExactlyElementsOf(namesInOrder(read(get(RULES).param("sort", sort), admin)));
        }
        for (String filter : List.of("status:in:DISABLED,SUSPENDED", "status:neq:ACTIVE", "status:eq:suspended",
                "ownerUserId:in:" + admin.getId() + "," + cara.getId(), "ownerUserId:neq:" + cara.getId(),
                "regionIds:neq:" + north.getId(), "regionIds:isNotEmpty:", "suspendedReason:eq:OWNER_INACTIVE",
                "suspendedReason:notIn:OWNER_INACTIVE", "name:eq:Beta", "name:eq:beta", "name:neq:Beta",
                "name:contains:MM", "name:isEmpty:", "triggerKind:notIn:MANUAL", "entityType:neq:CUSTOMER",
                "enabled:eq:false")) {
            assertThat(names(read(get(RULES).param("asOf", asOf).param("filter", filter), admin))).as(filter)
                    .isEqualTo(names(read(get(RULES).param("filter", filter), admin)));
        }

        for (String sort : List.of("lastRunAt,desc", "lastRunStatus,asc", "nextRunAt,asc")) {
            JsonNode refused = body(mockMvc.perform(get(RULES).param("asOf", asOf).param("sort", sort)
                    .with(as(admin))).andExpect(status().isBadRequest()));
            assertThat(refused.get("message").asText()).isEqualTo("This sort isn't available for past dates");
        }
        for (String filter : List.of("lastRunAt:gte:2020-01-01", "nextRunAt:gte:2020-01-01",
                "lastRunStatus:eq:FAILED")) {
            JsonNode refused = body(mockMvc.perform(get(RULES).param("asOf", asOf).param("filter", filter)
                    .with(as(admin))).andExpect(status().isBadRequest()));
            assertThat(refused.get("message").asText()).isEqualTo("This filter isn't available for past dates");
        }
        String liveBadStatus = body(mockMvc.perform(get(RULES).param("filter", "status:eq:PAUSED")
                .with(as(admin))).andExpect(status().isBadRequest())).get("message").asText();
        assertThat(body(mockMvc.perform(get(RULES).param("asOf", asOf).param("filter", "status:eq:PAUSED")
                .with(as(admin))).andExpect(status().isBadRequest())).get("message").asText())
                .isEqualTo(liveBadStatus);
    }

    /**
     * Who sees a rule (PC-8): SOUTH-only Vic sees Olga's unrestricted rule (Olga works in SOUTH) and admin's, but not
     * the one restricted to NORTH; asking for NORTH narrows the list, the summary and its failed runs, for everyone.
     */
    @Test
    void aRuleIsSeenWhereItActs() throws Exception {
        grant(admin, north, AccessLevel.APPROVE, RecordScope.ALL);
        grant(admin, south, AccessLevel.APPROVE, RecordScope.ALL);
        User olga = staff("olga.south", DataSeeder.ROLE_COLLECTION_POC, south, AccessLevel.MANAGE, RecordScope.ALL);
        User vic = staff("vic.viewer", roleWith("AUTOMATION_VIEWER", Privileges.AUTOMATION_VIEW).getName(),
                south, AccessLevel.READ, RecordScope.ALL);
        Long northOnly = saveRule(admin, with(chase("North only"), "regionIds", List.of(north.getId())));
        Long olgas = saveRule(olga, chase("Olga's rule"));
        Long everywhere = saveRule(admin, chase("Admin everywhere"));
        failedRun(northOnly, Duration.ofHours(1));
        failedRun(olgas, Duration.ofHours(2));
        failedRun(olgas, Duration.ofHours(30));

        JsonNode seen = read(get(RULES), vic);
        assertThat(names(seen)).containsExactly("Admin everywhere", "Olga's rule");
        assertThat(seen.get("regionIds")).extracting(JsonNode::asLong).containsExactly(south.getId());
        JsonNode summary = read(get(RULES + "/summary"), vic);
        assertThat(summary.get("active").asLong()).isEqualTo(2);
        assertThat(summary.get("failedRuns24h").asLong()).as("Olga's in the last day only").isEqualTo(1);
        mockMvc.perform(get(RULES + "/" + northOnly).with(as(vic))).andExpect(status().isNotFound());
        read(get(RULES + "/" + olgas), vic);
        read(get(RULES + "/" + everywhere), vic);

        assertThat(names(read(get(RULES), admin))).containsExactly("Admin everywhere", "North only", "Olga's rule");
        assertThat(read(get(RULES + "/summary"), admin).get("failedRuns24h").asLong()).isEqualTo(2);
        JsonNode askedNorth = read(get(RULES).param("regionIds", north.getId().toString()), admin);
        assertThat(names(askedNorth)).containsExactly("Admin everywhere", "North only");
        assertThat(read(get(RULES + "/summary").param("regionIds", north.getId().toString()), admin)
                .get("failedRuns24h").asLong()).isEqualTo(1);
        assertThat(names(read(get(RULES).param("regionIds", south.getId().toString()), admin)))
                .containsExactly("Admin everywhere", "Olga's rule");
        assertThat(names(read(get(RULES).param("regionIds", north.getId().toString()), vic)))
                .as("Vic holds nothing in NORTH, so asking for it answers nothing").isEmpty();
        assertThat(names(read(get(RULES).param("filter", "regionIds:in:" + north.getId()), admin)))
                .containsExactly("North only");
        assertThat(names(read(get(RULES).param("filter", "status:eq:ACTIVE"), vic)))
                .containsExactly("Admin everywhere", "Olga's rule");
    }

    private void failedRun(Long ruleId, Duration ago) {
        Instant finished = AppClock.now().minus(ago);
        automationRunRepository.save(AutomationRun.builder().ruleId(ruleId).eventId(ruleId * 1000 + ago.toHours())
                .triggerType("SCHEDULED").status(RunStatus.FAILED).createdAt(finished.minusSeconds(60))
                .startedAt(finished.minusSeconds(30)).finishedAt(finished).build());
    }
}
