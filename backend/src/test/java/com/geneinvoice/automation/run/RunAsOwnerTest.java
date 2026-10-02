package com.geneinvoice.automation.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.MovableClock;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.automation.AutomationTestBase;
import com.geneinvoice.automation.RecordingAutomationFaults;
import com.geneinvoice.automation.rule.AutomationRule;
import com.geneinvoice.automation.rule.RuleService;
import com.geneinvoice.automation.rule.SuspendReason;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.CustomerRegionMoveService;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionMoveDtos;
import com.geneinvoice.role.Role;
import com.geneinvoice.task.Task;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A rule runs as its owner (P3, §5.10.7): the consumer starts with nobody signed in and leaves nobody signed in; what
 * the rule writes is attributed to the owner and stamped with the rule; it sees and touches only what the owner may,
 * in the regions it is restricted to; an owner who can no longer act (inactive, or lacking what it needs, by a role or
 * user change) suspends the rule, once, telling those concerned; and a region the owner only reads is a failure for
 * that record alone.
 */
class RunAsOwnerTest extends AutomationTestBase {

    @Autowired AuditLogRepository auditLogRepository;
    @Autowired RuleService ruleService;
    @Autowired CurrentUser currentUser;
    @Autowired CustomerRegionMoveService moveService;

    User admin;
    User owen;
    Product widget;
    Region north;
    Region south;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        owen = user("owen.owner", "CASHIER");
        widget = product("Widget", "100.00");
        north = region("NORTH");
        south = region("SOUTH");
        for (User u : List.of(admin, seller)) {
            grant(u, north, AccessLevel.APPROVE, RecordScope.ALL);
            grant(u, south, AccessLevel.APPROVE, RecordScope.ALL);
        }
    }

    private Long invoiceIn(Region region) {
        actAs(admin);
        return invoice(customer("Customer in " + region.getCode(), region), widget, 1, null);
    }

    private Long createdRule(User owner, Map<String, Object> body) throws Exception {
        return saveRule(owner, body);
    }

    private Map<String, Object> onCreate(String name) {
        return rule(name, "INVOICE", "ENTITY_CREATED", null, task("Chase {{Invoice.Number}}"));
    }

    private AutomationRule rule(Long id) {
        return automationRuleRepository.findById(id).orElseThrow();
    }

    private List<AuditLog> audit(String type, Long id) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(type, id);
    }

    @Test
    void theConsumerStartsWithNobodySignedInAndLeavesNobodySignedIn() throws Exception {
        Long ruleId = createdRule(owen, onCreate("Chase"));
        Long invoiceId = invoiceIn(defaultRegion());
        drain();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(ViewScopeHolder.current().regionIds()).isNull();
        assertThat(WriteOrigins.current(currentUser).kind()).isEqualTo(WriteOrigin.Kind.SYSTEM);

        Task made = tasksOf(ruleId).get(0);
        AuditLog created = audit("TASK", made.getId()).stream()
                .filter(a -> a.getAction().equals("TASK_CREATED")).findFirst().orElseThrow();
        assertThat(created.getChangedByUserId()).isEqualTo(owen.getId());
        assertThat(created.getOrigin()).isEqualTo("AUTOMATION");
        assertThat(created.getRuleId()).isEqualTo(ruleId);
        assertThat(made.getCreatedByUserId()).isEqualTo(owen.getId());
        assertThat(made.getEntityId()).isEqualTo(invoiceId);

        // And after a failure that escapes the drain.
        invoiceIn(defaultRegion());
        automationFaults.crashAfterCommit(1);
        assertThatThrownBy(this::drain).isInstanceOf(RecordingAutomationFaults.SimulatedCrash.class);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(ViewScopeHolder.current().regionIds()).isNull();
        assertThat(WriteOrigins.current(currentUser).kind()).isEqualTo(WriteOrigin.Kind.SYSTEM);
    }

    @Test
    void aSalesPocsRuleTouchesOnlyTheirOwnInvoices() throws Exception {
        User other = user("oscar.other", DataSeeder.ROLE_SALES_POC);
        Long ruleId = createdRule(seller, onCreate("My invoices"));
        Customer acme = customer("Acme Ltd");
        actAs(admin);
        Long mine = invoice(acme, widget, 1, null);
        Long theirs = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, null, null,
                other.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null)))).getId();
        drain();
        assertThat(tasksOf(ruleId)).extracting(Task::getEntityId).containsExactly(mine).doesNotContain(theirs);
    }

    @Test
    void aRuleActsOnlyWhereItsOwnerWorksAndWhereItIsRestricted() throws Exception {
        User nora = staff("nora.north", "CASHIER", north, AccessLevel.MANAGE, RecordScope.ALL);
        Long noras = createdRule(nora, onCreate("Nora's"));
        grant(owen, north, AccessLevel.MANAGE, RecordScope.ALL);
        grant(owen, south, AccessLevel.MANAGE, RecordScope.ALL);
        Long southOnly = createdRule(owen, with(onCreate("South only"), "regionIds", List.of(south.getId())));

        Long inNorth = invoiceIn(north);
        Long inSouth = invoiceIn(south);
        drain();
        assertThat(tasksOf(noras)).extracting(Task::getEntityId).containsExactly(inNorth);
        assertThat(tasksOf(southOnly)).extracting(Task::getEntityId).containsExactly(inSouth);
        assertThat(runsOf(southOnly)).as("the north invoice did not match the south rule").hasSize(1);
    }

    @Test
    void anInactiveOwnerSuspendsTheRuleOnceAndThoseConcernedAreTold() throws Exception {
        Long ruleId = createdRule(owen, onCreate("Chase"));
        User stored = userRepository.findById(owen.getId()).orElseThrow();
        stored.setActive(false);
        userRepository.save(stored);
        invoiceIn(defaultRegion());
        invoiceIn(defaultRegion());
        drain();

        AutomationRule suspended = rule(ruleId);
        assertThat(suspended.getSuspendedReason()).isEqualTo(SuspendReason.OWNER_INACTIVE);
        assertThat(suspended.getSuspendedDetail()).contains("OWEN.OWNER").contains("no longer active");
        assertThat(suspended.getVersion()).as("not a user edit").isZero();
        assertThat(tasksOf(ruleId)).isEmpty();
        assertThat(audit("RULE", ruleId)).extracting(AuditLog::getAction).containsOnlyOnce("RULE_SUSPENDED");
        List<Notification> told = notificationRepository.findAll().stream()
                .filter(n -> n.getType().equals(RuleService.NOTIF_SUSPENDED)).toList();
        assertThat(told).extracting(Notification::getUserId).containsExactly(admin.getId());
        assertThat(told.get(0).getLink()).isEqualTo("/automation/" + ruleId);
        assertThat(automationRuleRevisionRepository.findByRuleIdOrderByIdAsc(ruleId)).last()
                .satisfies(r -> assertThat(r.getSuspendedReason()).isEqualTo("OWNER_INACTIVE"));
    }

    @Test
    void anOwnerWhoLostTaskManageSuspendsTheRuleAtItsNextEvent() throws Exception {
        Role limited = roleWith("CASHIER_NO_TASKS", Privileges.AUTOMATION_VIEW, Privileges.AUTOMATION_MANAGE,
                Privileges.INVOICE_VIEW, Privileges.TASK_MANAGE, Privileges.SCOPE_OVERRIDE);
        User tia = user("tia.tasks", limited.getName());
        Long ruleId = createdRule(tia, onCreate("Chase"));
        roleWith("CASHIER_NO_TASKS", Privileges.AUTOMATION_VIEW, Privileges.AUTOMATION_MANAGE,
                Privileges.INVOICE_VIEW, Privileges.SCOPE_OVERRIDE);
        invoiceIn(defaultRegion());
        drain();
        assertThat(rule(ruleId).getSuspendedReason()).isEqualTo(SuspendReason.OWNER_LACKS_RIGHTS);
        assertThat(rule(ruleId).getSuspendedDetail()).contains(Privileges.TASK_MANAGE);
        assertThat(tasksOf(ruleId)).isEmpty();
    }

    @Test
    void losingAutomationManageSuspendsAtTheNextEventOrAtOnceWhenTheRoleOrUserIsEdited() throws Exception {
        Role role = roleWith("RULE_MAKER", Privileges.AUTOMATION_VIEW, Privileges.AUTOMATION_MANAGE,
                Privileges.INVOICE_VIEW, Privileges.TASK_MANAGE, Privileges.SCOPE_OVERRIDE);
        User ria = user("ria.rules", role.getName());
        User rex = user("rex.rules", role.getName());
        Long lazily = createdRule(ria, onCreate("Found at the next event"));

        // Changed behind the application's back: the consumer finds it at the next event.
        roleWith("RULE_MAKER", Privileges.AUTOMATION_VIEW, Privileges.INVOICE_VIEW, Privileges.TASK_MANAGE,
                Privileges.SCOPE_OVERRIDE);
        invoiceIn(defaultRegion());
        drain();
        assertThat(rule(lazily).getSuspendedReason()).isEqualTo(SuspendReason.OWNER_LACKS_RIGHTS);
        assertThat(rule(lazily).getSuspendedDetail()).contains(Privileges.AUTOMATION_MANAGE);

        // Put back, then taken away through the roles API: suspended at once, before any event (SC-14).
        roleWith("RULE_MAKER", Privileges.AUTOMATION_VIEW, Privileges.AUTOMATION_MANAGE, Privileges.INVOICE_VIEW,
                Privileges.TASK_MANAGE, Privileges.SCOPE_OVERRIDE);
        Long byRoleEdit = createdRule(rex, onCreate("Found at the role edit"));
        assertThat(rule(byRoleEdit).isSuspended()).isFalse();
        send(put("/api/roles/" + role.getId()), admin, Map.of("name", "RULE_MAKER", "description", "x",
                "privileges", List.of(Privileges.AUTOMATION_VIEW, Privileges.INVOICE_VIEW, Privileges.TASK_MANAGE)))
                .andExpect(status().isOk());
        assertThat(rule(byRoleEdit).getSuspendedReason()).isEqualTo(SuspendReason.OWNER_LACKS_RIGHTS);

        // A user moved to a role without it: suspended at once too.
        Long byUserEdit = createdRule(owen, onCreate("Found at the user edit"));
        Role viewer = role("VIEWER");
        send(put("/api/users/" + owen.getId()), admin, Map.of("roleId", viewer.getId())).andExpect(status().isOk());
        assertThat(rule(byUserEdit).getSuspendedReason()).isEqualTo(SuspendReason.OWNER_LACKS_RIGHTS);
    }

    @Test
    void deactivatingAUserSuspendsTheirRulesAndResumingIsRefusedWhileTheyAreInactive() throws Exception {
        Long ruleId = createdRule(owen, rule("Chase", "INVOICE", "ENTITY_UPDATED", null, task("Chase")));
        User sue = user("sue.second", "CASHIER");
        Long bulkRule = createdRule(sue, onCreate("Sue's"));
        send(put("/api/users/" + owen.getId()), admin, Map.of("active", false)).andExpect(status().isOk());
        assertThat(rule(ruleId).getSuspendedReason()).isEqualTo(SuspendReason.OWNER_INACTIVE);
        send(post("/api/users/bulk"), admin, Map.of("action", "DEACTIVATE", "ids", List.of(sue.getId())))
                .andExpect(status().isOk());
        assertThat(rule(bulkRule).getSuspendedReason()).isEqualTo(SuspendReason.OWNER_INACTIVE);

        // Changes made while suspended are counted, not acted on.
        Long invoiceId = invoiceIn(defaultRegion());
        actAs(admin);
        invoiceService.update(invoiceId, new InvoiceDtos.UpdateInvoiceRequest("a", null, null, null, null));
        invoiceService.update(invoiceId, new InvoiceDtos.UpdateInvoiceRequest("b", null, null, null, null));
        drain();
        assertThat(tasksOf(ruleId)).isEmpty();

        // The owner cannot act, so nobody resumes it as them; an administrator is asked to take it over.
        actAs(userRepository.findById(owen.getId()).orElseThrow());
        assertThatThrownBy(() -> ruleService.resume(ruleId, false, false)).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no longer active");
        JsonNode refused = body(send(post(RULES + "/" + ruleId + "/resume"), admin, Map.of())
                .andExpect(status().isBadRequest()));
        assertThat(refused.get("fieldErrors").has("confirmOwnershipChange")).isTrue();
        assertThat(rule(ruleId).isSuspended()).isTrue();

        User back = userRepository.findById(owen.getId()).orElseThrow();
        back.setActive(true);
        userRepository.save(back);
        MovableClock.SHARED.set(MovableClock.SHARED.instant().plus(Duration.ofSeconds(1)));
        JsonNode resumed = body(send(post(RULES + "/" + ruleId + "/resume"), owen, Map.of("replay", false))
                .andExpect(status().isOk()));
        assertThat(resumed.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(resumed.get("skippedWhileSuspended").asLong()).isEqualTo(2);
        assertThat(resumed.get("id").asLong()).isEqualTo(ruleId);
        assertThat(audit("RULE", ruleId)).extracting(AuditLog::getAction).contains("RULE_RESUMED");
        drain();
        assertThat(tasksOf(ruleId)).as("the backlog is not acted on").isEmpty();

        actAs(admin);
        invoiceService.update(invoiceId, new InvoiceDtos.UpdateInvoiceRequest("c", null, null, null, null));
        drain();
        assertThat(tasksOf(ruleId)).hasSize(1);
    }

    @Test
    void aChangeTheRuleItselfMadeIsSkippedAndChangesBeforeItWasOnAreNotItsBusiness() throws Exception {
        Long invoiceId = invoiceIn(defaultRegion());
        actAs(admin);
        invoiceService.update(invoiceId, new InvoiceDtos.UpdateInvoiceRequest("before", null, null, null, null));
        MovableClock.SHARED.set(MovableClock.SHARED.instant().plus(Duration.ofSeconds(1)));
        Long ruleId = createdRule(owen, rule("Chase", "INVOICE", "ENTITY_UPDATED", null, everyTime(task("Chase"))));
        actAs(admin);
        WriteOrigins.run(WriteOrigin.automation(ruleId, null, null, 1).withActor(owen.getId()),
                () -> invoiceService.update(invoiceId,
                        new InvoiceDtos.UpdateInvoiceRequest("by the rule", null, null, null, null)));
        drain();
        assertThat(runsOf(ruleId)).isEmpty();
        assertThat(tasksOf(ruleId)).isEmpty();
    }

    @Test
    void aRegionTheOwnerOnlyReadsFailsThatRecordAloneAndDoesNotSuspend() throws Exception {
        User rosa = staff("rosa.reader", "CASHIER", north, AccessLevel.MANAGE, RecordScope.ALL,
                south, AccessLevel.READ, RecordScope.ALL);
        Long ruleId = createdRule(rosa, onCreate("Chase"));
        Long inNorth = invoiceIn(north);
        Long inSouth = invoiceIn(south);
        drain();

        assertThat(rule(ruleId).isSuspended()).isFalse();
        assertThat(tasksOf(ruleId)).extracting(Task::getEntityId).containsExactly(inNorth);
        AutomationEffect failed = automationEffectRepository.findAll().stream()
                .filter(e -> e.getTargetId().equals(inSouth)).findFirst().orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(EffectStatus.FAILED);
        assertThat(failed.getMessage()).isEqualTo(
                "Tasks cannot be created in this record's region: TASK_MANAGE is not held there");
        assertThat(runsOf(ruleId)).extracting(AutomationRun::getStatus)
                .containsExactlyInAnyOrder(RunStatus.SUCCEEDED, RunStatus.FAILED);
    }

    /**
     * A rule restricted to some regions acts only on records still in them when its action runs (SC-4): a record
     * whose customer moved out between the event and the action is skipped, saying why, and the preview warns of it.
     * A rule restricted to no region still acts wherever its owner may.
     */
    @Test
    void aRecordThatMovedOutOfTheRulesRegionsBeforeTheActionIsSkipped() throws Exception {
        User rhea = staff("rhea.regions", "CASHIER", north, AccessLevel.MANAGE, RecordScope.ALL,
                south, AccessLevel.MANAGE, RecordScope.ALL);
        Customer acme = customer("Acme Ltd", north);
        actAs(admin);
        Long invoiceId = invoice(acme, widget, 2, null);
        drain();
        Long northOnly = saveRule(rhea, with(rule("North chase", "INVOICE", "ENTITY_UPDATED", null, task("Chase")),
                "regionIds", List.of(north.getId())));
        Long everywhere = saveRule(rhea, rule("Chase anywhere", "INVOICE", "ENTITY_UPDATED", null, task("Chase")));

        actAs(admin);
        invoiceService.update(invoiceId, new InvoiceDtos.UpdateInvoiceRequest("touched in north", null, null, null,
                null));
        // The customer moves before the consumer gets to the event.
        actAs(admin);
        moveService.move(acme.getId(), new RegionMoveDtos.MoveRequest(south.getId(), null, "moved"));
        SecurityContextHolder.clearContext();
        drain();

        assertThat(tasksOf(northOnly)).isEmpty();
        AutomationEffect skipped = automationEffectRepository.findAll().stream()
                .filter(e -> e.getRuleId().equals(northOnly)).findFirst().orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo(EffectStatus.SKIPPED);
        assertThat(skipped.getMessage()).isEqualTo("The record is no longer in the rule's regions");
        assertThat(tasksOf(everywhere)).singleElement()
                .satisfies(t -> assertThat(t.getRegion().getId()).isEqualTo(south.getId()));

        JsonNode preview = body(mockMvc.perform(get(RULES + "/" + northOnly + "/preview")
                .param("recordId", invoiceId.toString()).with(as(rhea))).andExpect(status().isOk()));
        assertThat(preview.get("record").get("actions").get(0).get("warnings")).extracting(JsonNode::asText)
                .contains("The record is not in the rule's regions, so the rule would not act on it");
        JsonNode unrestricted = body(mockMvc.perform(get(RULES + "/" + everywhere + "/preview")
                .param("recordId", invoiceId.toString()).with(as(rhea))).andExpect(status().isOk()));
        assertThat(unrestricted.get("record").get("actions").get(0).get("warnings")).extracting(JsonNode::asText)
                .doesNotContain("The record is not in the rule's regions, so the rule would not act on it");
    }
}
