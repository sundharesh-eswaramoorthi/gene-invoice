package com.geneinvoice.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.approval.ApprovalService;
import com.geneinvoice.approval.ApprovalThreshold;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.approval.PendingChangeStatus;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.email.EmailDtos;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.email.RoleRef;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.PocType;
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
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What happens when a rule's action is over the branch's approval limit (A5, B2 INTEGRATION).
 *
 * <p>THE SETTLED ANSWER, and it overrides Part A's own first draft: a held action is a SUCCESSFUL
 * TERMINAL OUTCOME. The step settles DONE against PENDING_CHANGE, the run reads "1 awaiting
 * approval" rather than "1 failed", and it is NEVER RETRIED — retrying it would raise the same
 * change again, and again, for as long as nobody decided the first one.
 *
 * <p>And the change names a real person. The engine has no principal, so without
 * {@code ApprovalContext.actingAs} the maker would be null — and "approval from someone else"
 * would then be satisfied by ANYONE, including the very person whose rule raised it.
 */
@Import(InterleavingActions.class)
class AutomationApprovalTest extends IntegrationTestBase {

    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired InvoiceService invoiceService;
    @Autowired AutomationRuleService ruleService;
    @Autowired AutomationDispatcher dispatcher;
    @Autowired ApprovalService approvalService;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired PlatformTransactionManager transactionManager;

    TransactionTemplate ownTransaction;
    User admin;
    User ana;
    Customer home;
    Product widget;

    @BeforeEach
    void setUp() {
        InterleavingActions.Interleaving.reset();
        ownTransaction = new TransactionTemplate(transactionManager);
        ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        admin = userRepository.findByUsername("admin").orElseThrow();
        ana = user("ana.author", authorRole().getName());
        widget = product("Widget", "1000.00");
        home = customer("Home Ltd", "ap@home.test");
        customerPocRepository.save(CustomerPoc.builder()
                .customer(home).user(admin).pocType(PocType.COLLECTION)
                .primary(true).createdByUserId(admin.getId()).build());
        // Well above the 1,000 invoices below and well below the 50,000 promise the rule makes,
        // so exactly one thing in each test is held (B2).
        threshold(defaultRegion().getId(), "5000.00");
    }

    @AfterEach
    void clearHooks() {
        InterleavingActions.Interleaving.reset();
    }

    @Test
    void anActionOverTheBranchesLimitSettlesDoneAsAPendingChangeAndIsNeverRetried() {
        AutomationRule rule = rule(List.of(bigPromise()));
        invoiceFor(home);

        dispatcher.sweep(Instant.now().plusSeconds(60));

        AutomationStep step = stepsOf(rule).get(0);
        // DONE and not FAILED, and not QUEUED-for-another-go. "Made a change that is waiting for
        // somebody" is the honest answer, and it is a success (A5, B2).
        assertThat(step.getStatus()).isEqualTo(StepStatus.DONE);
        assertThat(step.getProducedType()).isEqualTo(ProducedType.PENDING_CHANGE);
        assertThat(step.getAttempts()).isEqualTo(1);
        assertThat(step.getNextAttemptAt()).isNull();

        PendingChange change = pendingChangeRepository.findById(step.getProducedId()).orElseThrow();
        assertThat(change.getStatus()).isEqualTo(PendingChangeStatus.PENDING);
        assertThat(change.getExposure()).isEqualByComparingTo("50000.00");
        // THE KNOWN HOLE, written where a reader of the history will meet it: a change nobody
        // approves never happens, and this step still says it succeeded (A5).
        assertThat(step.getResult()).startsWith("Waiting for approval (change #"
                + change.getId() + "):");
        assertThat(promiseRepository.count()).isZero();

        // Three more sweeps over three hours change nothing at all. This is the assertion that
        // says "never retried" — a step that went back on the queue would raise a second change.
        dispatcher.sweep(Instant.now().plusSeconds(3600));
        dispatcher.sweep(Instant.now().plusSeconds(7200));
        dispatcher.sweep(Instant.now().plusSeconds(10800));
        assertThat(pendingChangeRepository.count()).isEqualTo(1);
        assertThat(stepsOf(rule)).hasSize(1);
        assertThat(stepsOf(rule).get(0).getAttempts()).isEqualTo(1);
    }

    @Test
    void aChangeRaisedByARuleNamesTheRulesAuthorAsItsMakerAndThatAuthorCannotApproveIt() throws Exception {
        AutomationRule rule = rule(List.of(bigPromise()));
        invoiceFor(home);
        dispatcher.sweep(Instant.now().plusSeconds(60));

        PendingChange change = pendingChangeRepository.findAll().get(0);
        // A REAL, ACCOUNTABLE PERSON. A null here would not be a cosmetic gap: guardApprover
        // waives the second-pair-of-eyes rule when there is no maker, so a null maker makes the
        // change approvable by anybody at all — including its own author (B2, A5).
        assertThat(change.getRequestedByUserId()).isEqualTo(ana.getId());

        mockMvc.perform(post("/api/approvals/" + change.getId() + "/approve").with(as(ana)))
                .andExpect(status().isForbidden());
        // The endpoint answers the one generic 403 every AccessDeniedException gets, so the
        // SENTENCE is asserted where it is produced — it is the reason, and a reason nobody can
        // read is how "approval from someone else" quietly becomes "approval from anyone" (B2).
        actAs(ana);
        assertThatThrownBy(() -> approvalService.approve(change.getId(), null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("You cannot approve a change you raised");
        SecurityContextHolder.clearContext();
        assertThat(promiseRepository.count()).isZero();

        // Somebody else decides it, and only then does the promise exist.
        mockMvc.perform(post("/api/approvals/" + change.getId() + "/approve").with(as(admin)))
                .andExpect(status().isOk());
        assertThat(promiseRepository.findAll()).singleElement().satisfies(p ->
                assertThat(p.getAmount()).isEqualByComparingTo("50000.00"));
        assertThat(stepsOf(rule).get(0).getStatus()).isEqualTo(StepStatus.DONE);
    }

    @Test
    void aHeldActionLeavesNoTaskAndNoOrphanPendingChangeWhenItsStepWasReclaimed() {
        AutomationRule rule = rule(List.of(
                new ActionSpec.CreateTask("Chase {{Invoice.Number}}", null, List.of(), 3),
                bigPromise()));
        Invoice invoice = invoiceFor(home);
        dispatcher.fanOut(eventFor(invoice.getId()), Instant.now().plusSeconds(60));
        List<AutomationStep> planned = stepsOf(rule);
        assertThat(planned).hasSize(2);

        // Action 0 runs normally and really does make a task, so the assertion below about the
        // task count is about THIS step and not about a rule that makes none.
        dispatcher.perform(planned.get(0).getId(), Instant.now().plusSeconds(60));
        assertThat(taskRepository.count()).isEqualTo(1);

        // And while action 1 is inside tx2 — after the gate has thrown and before the change is
        // written — a sweeper on another instance reclaims the step and commits that.
        InterleavingActions.Interleaving.interleave = () -> ownTransaction.executeWithoutResult(s -> {
            AutomationStep taken = automationStepRepository.findById(planned.get(1).getId()).orElseThrow();
            taken.setStatus(StepStatus.QUEUED);
            taken.setClaimToken(null);
            taken.setNextAttemptAt(null);
            automationStepRepository.saveAndFlush(taken);
        });

        dispatcher.perform(planned.get(1).getId(), Instant.now().plusSeconds(120));

        // NO ORPHAN. The park and the settle were one transaction, so a change that could not be
        // attached to a step was not written at all (A5, B2).
        assertThat(pendingChangeRepository.count()).isZero();
        assertThat(promiseRepository.count()).isZero();
        assertThat(taskRepository.count()).isEqualTo(1);
        assertThat(reload(planned.get(1)).getStatus()).isEqualTo(StepStatus.QUEUED);

        // The winner finishes it, once.
        dispatcher.perform(planned.get(1).getId(), Instant.now().plusSeconds(180));
        assertThat(pendingChangeRepository.count()).isEqualTo(1);
        assertThat(reload(planned.get(1)).getProducedType()).isEqualTo(ProducedType.PENDING_CHANGE);
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    /**
     * The promise half of "validate and execute read the token the same way" (A3, A6, B2).
     *
     * <p>{@code AutomationRuleService.kind} accepts a token type by {@code trim()} +
     * {@code equalsIgnoreCase} and stores it verbatim, so {@code {"type":"role"}} really is a rule
     * the REST API accepts. {@code AutomationActions.resolveOnePerson} used to compare it with
     * {@code "ROLE".equals} and return null with NOTHING recorded as unresolved, so the promise
     * fell back to the account's default collection seat — a different person from the one the
     * rule named, silently — or was refused outright on an account that has no such seat.
     *
     * <p>The held change is the observation point, and an honest one: the gate's payload IS the
     * request the mutator was about to make, so the seat the token resolved to is in it.
     */
    @Test
    void aCollectionPocTokenSavedInLowerCaseStillNamesThatSeat() {
        AutomationRule rule = rule(List.of(new ActionSpec.CreatePromise(
                ActionSpec.AmountSource.FIXED, new BigDecimal("50000.00"), 7,
                new EmailDtos.EmailToken("role", null, "COLLECTION_POC", "CUSTOMER"),
                "Promised after a chase")));
        invoiceFor(home);

        dispatcher.sweep(Instant.now().plusSeconds(60));

        assertThat(stepsOf(rule)).singleElement().satisfies(step -> {
            assertThat(step.getStatus()).isEqualTo(StepStatus.DONE);
            assertThat(step.getProducedType()).isEqualTo(ProducedType.PENDING_CHANGE);
        });
        PendingChange change = pendingChangeRepository.findAll().get(0);
        assertThat(change.getPayloadJson())
                .contains("\"collectionPocUserId\":" + admin.getId());
    }

    // ---- the park must not re-trigger the rule that raised it ----------------------------------

    /**
     * THE RUNAWAY LOOP, and the one test in this class whose rule is about CUSTOMERS (A5, B2).
     *
     * <p>A create-shaped change has no target, so the park's own audit row is anchored on the
     * ACCOUNT — and the audit chokepoint turns a CUSTOMER anchor into a CUSTOMER/UPDATED fact.
     * That fact arms this very rule again: it still matches, the gate still holds, the park audits
     * again. One pending change, one step, one audit row and one notification to every approver in
     * the branch, per sweep, without bound — and with {@code async: true} not per sweep at all but
     * as fast as the database allows, on the one thread every other rule in the product shares.
     *
     * <p>The audit row is NOT what was cut — it is the entire approval trail for a save that
     * rolled back, and it is asserted below. What was cut is its re-entry into the change feed as
     * a TRIGGER, which is a thing that never happened: the customer did not change, a request
     * about the customer was written down.
     */
    @Test
    void aHeldActionOnACustomerRuleParksOnceAndDoesNotRePublishTheCustomerItParkedAgainst()
            throws Exception {
        AutomationRule rule = customerRule(List.of(bigPromise()));
        automationEventRepository.deleteAll();

        // One ordinary edit to the account, through the endpoint a person would use.
        mockMvc.perform(put("/api/customers/" + home.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CustomerDtos.CustomerUpdateRequest("Home Ltd", null,
                                "ap@home.test", null, null, null))))
                .andExpect(status().isOk());
        assertThat(customerEvents()).hasSize(1);

        dispatcher.sweep(Instant.now().plusSeconds(60));
        dispatcher.sweep(Instant.now().plusSeconds(3600));
        dispatcher.sweep(Instant.now().plusSeconds(7200));

        // ONE of everything. Before the cut this was one more of each per sweep, for ever.
        assertThat(stepsOf(rule)).hasSize(1);
        assertThat(pendingChangeRepository.count()).isEqualTo(1);
        assertThat(promiseRepository.count()).isZero();
        assertThat(stepsOf(rule).get(0).getProducedType()).isEqualTo(ProducedType.PENDING_CHANGE);

        // The park published nothing: the edit's own event is still the only one there, and it is
        // finished. A NEW event left on the table is the loop's next turn.
        assertThat(customerEvents()).hasSize(1);
        assertThat(customerEvents().get(0).getStatus()).isEqualTo(EventStatus.DONE);

        // AND THE TRAIL IS INTACT. Suppressing the audit would have been the wrong cut: this row
        // is the only trace that the engine probed the gate at all (B2).
        PendingChange change = pendingChangeRepository.findAll().get(0);
        assertThat(auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtDesc("CUSTOMER", home.getId()))
                .anySatisfy(row -> {
                    assertThat(row.getAction()).isEqualTo("CHANGE_REQUESTED");
                    assertThat(row.getPendingChangeId()).isEqualTo(change.getId());
                });
    }

    // ---- fixtures -----------------------------------------------------------------------------

    /**
     * The collection POC is named as a SEAT and not as a person, which is also what makes the
     * promise creatable at all: a customer with no active Collection POC refuses one (A3, A6).
     */
    private static ActionSpec bigPromise() {
        return new ActionSpec.CreatePromise(ActionSpec.AmountSource.FIXED,
                new BigDecimal("50000.00"), 7,
                EmailDtos.EmailToken.role(RoleRef.customer(EmailRole.COLLECTION_POC)),
                "Promised after a chase");
    }

    private AutomationStep reload(AutomationStep step) {
        return automationStepRepository.findById(step.getId()).orElseThrow();
    }

    private List<AutomationStep> stepsOf(AutomationRule rule) {
        return automationStepRepository.findAll().stream()
                .filter(s -> s.getRuleId().equals(rule.getId()))
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }

    /**
     * A rule about the ACCOUNT, with no condition at all: the shape that loops, because the park's
     * audit row for a create-shaped change is anchored on the account (A5, B2).
     */
    private AutomationRule customerRule(List<ActionSpec> actions) {
        actAs(ana);
        Long id = ruleService.create(new AutomationDtos.SaveRuleRequest("Chase the account", null,
                SubjectType.CUSTOMER, TriggerKind.ON_CREATED_OR_UPDATED, null, null, null,
                actions, null, true, List.of())).id();
        SecurityContextHolder.clearContext();
        return automationRuleRepository.findById(id).orElseThrow();
    }

    private List<AutomationEvent> customerEvents() {
        return automationEventRepository.findAll().stream()
                .filter(e -> e.getSubjectType() == SubjectType.CUSTOMER
                        && e.getSubjectId().equals(home.getId()))
                .toList();
    }

    private Long eventFor(Long invoiceId) {
        return automationEventRepository.findAll().stream()
                .filter(e -> e.getSubjectType() == SubjectType.INVOICE
                        && e.getSubjectId().equals(invoiceId))
                .map(AutomationEvent::getId).findFirst().orElseThrow();
    }

    private void threshold(Long regionId, String amount) {
        ApprovalThreshold row = approvalThresholdRepository.findByRegionId(regionId)
                .orElseGet(() -> ApprovalThreshold.builder().regionId(regionId).build());
        row.setAmount(new BigDecimal(amount));
        row.setEnabled(true);
        approvalThresholdRepository.saveAndFlush(row);
    }

    private AutomationRule rule(List<ActionSpec> actions) {
        actAs(ana);
        Long id = ruleService.create(new AutomationDtos.SaveRuleRequest("Chase", null,
                SubjectType.INVOICE, TriggerKind.ON_CREATED_OR_UPDATED, null, null, condition(),
                actions, null, true, List.of())).id();
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
        return roleWith("RULE_AUTHOR_APPROVALS", Privileges.AUTOMATION_VIEW,
                Privileges.AUTOMATION_MANAGE, Privileges.CUSTOMER_VIEW, Privileges.CUSTOMER_MANAGE,
                Privileges.INVOICE_VIEW, Privileges.TASK_VIEW, Privileges.TASK_MANAGE,
                Privileges.PROMISE_VIEW, Privileges.PROMISE_MANAGE, Privileges.APPROVAL_VIEW,
                Privileges.APPROVAL_APPROVE, Privileges.SCOPE_OVERRIDE);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by AutomationApprovalTest")
                .privileges(Arrays.stream(privileges)
                        .map(p -> privilegeRepository.findByName(p).orElseThrow())
                        .collect(Collectors.toCollection(HashSet<Privilege>::new)))
                .build()));
    }
}
