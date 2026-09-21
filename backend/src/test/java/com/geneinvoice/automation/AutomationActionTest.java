package com.geneinvoice.automation;

import com.geneinvoice.assignee.Assignee;
import com.geneinvoice.assignee.AssigneeKind;
import com.geneinvoice.assignee.AssigneeOwnerType;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeRepository;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.email.Email;
import com.geneinvoice.email.EmailDtos;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailRecipient;
import com.geneinvoice.email.EmailStatus;
import com.geneinvoice.email.RecordingMailTransport.Mode;
import com.geneinvoice.email.RecipientField;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.task.Task;
import com.geneinvoice.task.TaskService;
import com.geneinvoice.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The THEN half: what each of the four actions actually makes, and who it is attributed to (R6).
 *
 * <p>Every test here drives the worker itself, with the security context cleared first, because
 * that is the condition the consumer really runs under: a background thread with nobody logged in.
 * The actions have to write a task, a promise, a dispute or an email there without a caller to ask
 * for a privilege, a customer restriction or a name to attribute the work to (R7).
 */
class AutomationActionTest extends AutomationTestBase {

    @Autowired DisputeRepository disputeRepository;
    @Autowired AuditLogRepository auditLogRepository;

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    /** Runs one rule against one record the way a consumer would: no transaction, nobody logged in. */
    private AutomationEvent fire(AutomationRule rule, Long entityId) {
        AutomationEvent queued = ruleRow(rule, entityId, Instant.now());
        SecurityContextHolder.clearContext();
        worker.process(queued.getId(), Instant.now());
        return reload(queued);
    }

    private List<AuditLog> auditOf(String entityType, Long entityId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId);
    }

    // ---- CREATE_TASK -----------------------------------------------------------------

    /**
     * A task on the record the rule fired against, with the assignees as they were picked. Nobody
     * asked for it, so nobody is recorded as having raised it — the rule is the reason, and the
     * task's own history says so without inventing a stand-in user (T7).
     */
    @Test
    void aTaskRuleRaisesTheTaskOnTheRecordAndAttributesItToNobody() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule chase = rule("Chase big invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of("total:gt:500"), ActionType.CREATE_TASK,
                new AutomationDtos.ActionSpec("Chase this invoice", "Over the limit", 3, null, null,
                        List.of(roleToken("COLLECTION_POC", "CUSTOMER")), "IN_PROGRESS"));

        AutomationEvent run = fire(chase, inv.getId());

        assertThat(run.getStatus()).isEqualTo(AutomationEventStatus.DONE);
        Task raised = tasksOn(inv).get(0);
        assertThat(raised.getTitle()).isEqualTo("Chase this invoice");
        assertThat(raised.getNotes()).isEqualTo("Over the limit");
        assertThat(raised.getDueDate()).isEqualTo(TODAY.plusDays(3));
        // A rule may raise work that is already in hand, so it may state the opening status.
        assertThat(raised.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(raised.getCustomerId()).isEqualTo(acme.getId());
        assertThat(raised.getEntityLabel()).isNotBlank();
        assertThat(raised.getCreatedByUserId()).isNull();
        assertThat(run.getLastError()).startsWith("Raised task #" + raised.getId());

        assertThat(auditOf(TaskService.ENTITY, raised.getId())).singleElement().satisfies(log -> {
            assertThat(log.getAction()).isEqualTo("TASK_CREATED");
            // Null rather than an exception: the trail says a person did not do this.
            assertThat(log.getChangedByUserId()).isNull();
        });
    }

    /**
     * The same rule run by a person pressing Run now is attributed to them: where there IS a
     * caller, they are the truer answer to who asked for the work.
     */
    @Test
    void thatSameTaskRaisedByAPersonPressingRunNowIsAttributedToThem() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule chase = rule("Chase big invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of("total:gt:500"), ActionType.CREATE_TASK,
                taskSpec("Chase this invoice", userToken(collections)));

        actAs(admin);
        assertThat(automationService.runNow(chase.getId())).isEqualTo(1);

        assertThat(tasksOn(inv)).singleElement().satisfies(t ->
                assertThat(t.getCreatedByUserId()).isEqualTo(admin.getId()));
    }

    /** A rule may raise work for one named person just as well as for a seat. */
    @Test
    void aTaskRuleMayNameThePersonRatherThanTheSeat() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule chase = rule("Chase big invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of(), ActionType.CREATE_TASK,
                taskSpec("Chase this invoice", userToken(collections)));

        fire(chase, inv.getId());

        Task raised = tasksOn(inv).get(0);
        assertThat(assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.TASK, raised.getId()))
                .singleElement().satisfies(a -> {
                    assertThat(a.getKind()).isEqualTo(AssigneeKind.USER);
                    assertThat(a.getUserId()).isEqualTo(collections.getId());
                });
    }

    // ---- CREATE_PROMISE ---------------------------------------------------------------

    /**
     * A promise for the record's customer, for the amount the rule states — never one read off the
     * record, which would commit a customer to a number no person ever agreed — answered for by
     * the customer's own Collection POC, and attributed to whoever wrote the rule.
     */
    @Test
    void aPromiseRuleRecordsThePromiseForTheCustomerAndAttributesItToTheRulesAuthor() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule promiseIt = rule("Record what they promised", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of(), ActionType.CREATE_PROMISE,
                new AutomationDtos.ActionSpec(null, "Agreed on the phone", 14,
                        new BigDecimal("250.00"), null,
                        List.of(roleToken("COLLECTION_POC", "CUSTOMER")), null));

        AutomationEvent run = fire(promiseIt, inv.getId());

        assertThat(run.getStatus()).isEqualTo(AutomationEventStatus.DONE);
        PaymentPromise made = promiseRepository.findAll().get(0);
        assertThat(made.getAmount()).isEqualByComparingTo("250.00");
        assertThat(made.getPromisedDate()).isEqualTo(TODAY.plusDays(14));
        assertThat(made.getNotes()).isEqualTo("Agreed on the phone");
        assertThat(made.getStatus()).isEqualTo(PromiseStatus.OPEN);
        assertThat(made.getCustomer().getId()).isEqualTo(acme.getId());
        // Somebody has to answer for a promise or nobody will chase it (AC-B5).
        assertThat(made.getCollectionPoc().getId()).isEqualTo(collections.getId());
        // No caller on a consumer thread, so the rule's author is who it is done as (R7).
        assertThat(made.getCreatedByUserId()).isEqualTo(admin.getId());
        assertThat(assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.PROMISE, made.getId())).hasSize(1);
        assertThat(run.getLastError()).startsWith("Recorded promise #" + made.getId());

        assertThat(auditOf(PaymentPromiseService.ENTITY, made.getId())).singleElement().satisfies(log -> {
            assertThat(log.getAction()).isEqualTo("PROMISE_CREATED");
            assertThat(log.getChangedByUserId()).isEqualTo(admin.getId());
            // The record's history names the rule, which is what somebody checking wants to read.
            assertThat(log.getReason()).isEqualTo("Automation rule: Record what they promised");
            assertThat(log.getAfterJson()).contains("\"ruleName\":\"Record what they promised\"");
        });
    }

    /**
     * A customer with nobody in the Collection seat has nobody to answer for the promise, so the
     * run skips with that reason rather than writing a promise nobody will ever chase.
     */
    @Test
    void aPromiseRuleSkipsACustomerWithNoActiveCollectionPoc() {
        Customer beta = customer("Beta Ltd", "ap@beta.test");
        actAs(admin);
        Invoice theirs = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                beta.getId(), null, null, sales.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("600.00")))));
        AutomationRule promiseIt = rule("Record what they promised", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of(), ActionType.CREATE_PROMISE,
                new AutomationDtos.ActionSpec(null, null, 14, new BigDecimal("250.00"), null,
                        List.of(), null));

        AutomationEvent run = fire(promiseIt, theirs.getId());

        assertThat(run.getStatus()).isEqualTo(AutomationEventStatus.SKIPPED);
        assertThat(run.getLastError()).isEqualTo(
                "This customer has no active Collection POC, so there is nobody to answer for the promise");
        assertThat(promiseRepository.findAll()).isEmpty();
    }

    // ---- CREATE_DISPUTE ----------------------------------------------------------------

    /**
     * A dispute against the invoice the rule fired on, opened as the rule's author — the dispute
     * service itself would refuse an internal caller outright, since only a customer login may
     * raise one by hand, so the rules it enforces are enforced here instead.
     */
    @Test
    void aDisputeRuleRaisesItAgainstTheInvoiceAndOpensItAsTheRulesAuthor() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule flag = rule("Flag odd invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of(), ActionType.CREATE_DISPUTE,
                new AutomationDtos.ActionSpec(null, "This invoice needs checking", null, null, null,
                        List.of(roleToken("COLLECTION_POC", "CUSTOMER")), null));

        AutomationEvent run = fire(flag, inv.getId());

        assertThat(run.getStatus()).isEqualTo(AutomationEventStatus.DONE);
        List<Dispute> raised = disputeRepository.findByTargetTypeAndTargetId(
                DisputeTargetType.INVOICE, inv.getId());
        assertThat(raised).singleElement().satisfies(d -> {
            assertThat(d.getCustomerId()).isEqualTo(acme.getId());
            assertThat(d.getStatus()).isEqualTo(DisputeStatus.PENDING);
            assertThat(d.getReason()).isEqualTo("This invoice needs checking");
            assertThat(d.getOpenedByUserId()).isEqualTo(admin.getId());
        });
        Long disputeId = raised.get(0).getId();
        assertThat(assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.DISPUTE, disputeId)).hasSize(1);
        assertThat(auditOf(DisputeService.ENTITY, disputeId)).singleElement().satisfies(log -> {
            assertThat(log.getAction()).isEqualTo("DISPUTE_OPENED");
            assertThat(log.getReason()).isEqualTo("Automation rule: Flag odd invoices");
            assertThat(log.getDisputeId()).isEqualTo(disputeId);
        });
    }

    /**
     * One open dispute per target is the rule a person gets, so a rule firing again on the next
     * edit must not stack a second one on top of the one somebody is already working through.
     */
    @Test
    void aDisputeRuleFiringAgainDoesNotStackASecondOpenDisputeOnTheSameInvoice() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule flag = rule("Flag odd invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of(), ActionType.CREATE_DISPUTE,
                new AutomationDtos.ActionSpec(null, "This invoice needs checking", null, null, null,
                        List.of(), null));
        fire(flag, inv.getId());

        // A second piece of work for the same rule and record, as tomorrow's edit would queue.
        AutomationEvent second = ruleRow(flag, inv.getId(), Instant.now().plusSeconds(86_400));
        SecurityContextHolder.clearContext();
        worker.process(second.getId(), Instant.now());

        AutomationEvent after = reload(second);
        assertThat(after.getStatus()).isEqualTo(AutomationEventStatus.SKIPPED);
        assertThat(after.getLastError()).isEqualTo("An open dispute already exists for this invoice");
        assertThat(disputeRepository.findByTargetTypeAndTargetId(DisputeTargetType.INVOICE, inv.getId()))
                .hasSize(1);
    }

    /**
     * A dispute is raised as somebody. A rule with no author — written before the column existed,
     * or by an account since removed — has nobody to raise it as, and says so rather than opening
     * one belonging to no one.
     */
    @Test
    void aDisputeRuleWithNoAuthorSkipsBecauseThereIsNobodyToRaiseItAs() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule ownerless = automationRuleRepository.save(AutomationRule.builder()
                .name("Flag odd invoices").enabled(true)
                .entityType(AutomationEntityType.INVOICE).trigger(TriggerKind.UPDATED)
                .filtersJson("[]").action(ActionType.CREATE_DISPUTE)
                .actionJson("{\"body\":\"This invoice needs checking\"}")
                .createdByUserId(null).runCount(0L).build());

        AutomationEvent run = fire(ownerless, inv.getId());

        assertThat(run.getStatus()).isEqualTo(AutomationEventStatus.SKIPPED);
        assertThat(run.getLastError())
                .isEqualTo("This rule has no author, so there is nobody to raise the dispute as");
        assertThat(disputeRepository.findByTargetTypeAndTargetId(DisputeTargetType.INVOICE, inv.getId()))
                .isEmpty();
    }

    // ---- SEND_EMAIL ---------------------------------------------------------------------

    /**
     * The email goes through the service the compose form uses, so it lands in the record's Email
     * tab like any other and its recipients resolve against this record's own role holders at the
     * moment it goes out (E4) — one copy handed to the mail service per addressee.
     */
    @Test
    void anEmailRuleSendsAboutTheRecordAndReachesThePeopleThePickerWouldReach() {
        mailTransport.mode(Mode.SUCCESS);
        Invoice inv = invoice("600.00", 1);
        AutomationRule remind = rule("Remind about big invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of(), ActionType.SEND_EMAIL,
                new AutomationDtos.ActionSpec("Your invoice is due", "Please pay it", null, null,
                        userToken(admin),
                        List.of(roleToken("COLLECTION_POC", "CUSTOMER"), customerToken()), null));

        AutomationEvent run = fire(remind, inv.getId());

        assertThat(run.getStatus()).isEqualTo(AutomationEventStatus.DONE);
        Email sent = emailRepository.findAll().get(0);
        assertThat(sent.getEntityType()).isEqualTo(EmailEntityType.INVOICE);
        assertThat(sent.getEntityId()).isEqualTo(inv.getId());
        assertThat(sent.getSubject()).isEqualTo("Your invoice is due");
        assertThat(sent.getStatus()).isEqualTo(EmailStatus.QUEUED);
        // Nobody pressed Send, so nobody is recorded as having sent it; the From is the rule's.
        assertThat(sent.getSentByUserId()).isNull();
        assertThat(sent.getFromUserId()).isEqualTo(admin.getId());
        assertThat(emailRecipientRepository.findByEmailIdOrderByIdAsc(sent.getId()))
                .filteredOn(r -> r.getField() == RecipientField.TO)
                .extracting(EmailRecipient::getAddress)
                .containsExactlyInAnyOrder(collections.getEmail(), "ap@acme.test");
        assertThat(mailTransport.copiesHandedOver()).hasSize(2);
        assertThat(run.getLastError()).startsWith("Sent email #" + sent.getId());
    }

    /**
     * The one thing an automated email cannot do without is a sender: a request may leave From out
     * and mean "me", and a rule has no "me". So the author is told while they are still looking at
     * the form, rather than saving a rule that would skip on every run with nobody watching — which
     * is the whole reason the action is checked at authoring time at all (R7).
     */
    @Test
    void anEmailRuleWithNoSenderIsRefusedWhenItIsWritten() {
        actAs(admin);

        assertThatThrownBy(() -> rule("Remind about big invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of(), ActionType.SEND_EMAIL,
                new AutomationDtos.ActionSpec("Your invoice is due", "Please pay it", null, null,
                        null, List.of(customerToken()), null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("needs a sender");

        // Nothing was written, so there is no rule sitting there waiting to skip.
        assertThat(automationRuleRepository.findAll()).isEmpty();
    }

    /**
     * The sender is picked from the same book the recipients are, so a role the record could never
     * hold is refused in the same breath as a recipient would be.
     */
    @Test
    void anEmailRuleWhoseSenderIsARoleTheRecordCannotHoldIsRefused() {
        actAs(admin);

        assertThatThrownBy(() -> rule("Remind about big invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of(), ActionType.SEND_EMAIL,
                new AutomationDtos.ActionSpec("Your invoice is due", "Please pay it", null, null,
                        roleToken("SALES_POC", "CUSTOMER"),
                        List.of(customerToken()), null)))
                .isInstanceOf(BadRequestException.class);

        assertThat(automationRuleRepository.findAll()).isEmpty();
    }

    /**
     * Without the mail service the email is still saved and still lands in the record's Email tab
     * and the recipients' inboxes — the run did what it was asked, and delivery says why it has
     * not gone out. That is a done run, not a failed rule.
     */
    @Test
    void anEmailRuleStillRecordsTheEmailWhenTheMailServiceIsNotConfigured() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule remind = rule("Remind about big invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of(), ActionType.SEND_EMAIL,
                new AutomationDtos.ActionSpec("Your invoice is due", "Please pay it", null, null,
                        userToken(admin), List.of(customerToken()), null));

        AutomationEvent run = fire(remind, inv.getId());

        assertThat(run.getStatus()).isEqualTo(AutomationEventStatus.DONE);
        assertThat(emailRepository.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(EmailStatus.NOT_SENT);
            assertThat(e.getError()).isEqualTo("Email delivery is not configured (mail service)");
        });
    }

    /**
     * A rule that names a person who has since been deactivated cannot reach them, and that is the
     * rule saying so about this record — a skip with the reason, not a retry that will never come
     * good.
     */
    @Test
    void anEmailRuleNamingSomebodyWhoHasLeftSkipsWithTheReason() {
        mailTransport.mode(Mode.SUCCESS);
        Invoice inv = invoice("600.00", 1);
        AutomationRule remind = rule("Remind about big invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of(), ActionType.SEND_EMAIL,
                new AutomationDtos.ActionSpec("Your invoice is due", "Please pay it", null, null,
                        userToken(admin), List.of(userToken(collections)), null));
        collections.setActive(false);
        userRepository.save(collections);

        AutomationEvent run = fire(remind, inv.getId());

        assertThat(run.getStatus()).isEqualTo(AutomationEventStatus.SKIPPED);
        assertThat(run.getLastError()).isEqualTo("cara.collections is not an active internal user");
        assertThat(emailRepository.findAll()).isEmpty();
    }

    // ---- the record itself ---------------------------------------------------------------

    /**
     * A customer rule reaches the customer, which no privilege check on the consumer thread could
     * have allowed — there is no caller to hold a privilege. The decision was taken when the rule
     * was written, by somebody who held AUTOMATION_MANAGE (R7).
     */
    @Test
    void aRuleOnCustomersRaisesItsTaskOnTheCustomerWithNobodyLoggedIn() {
        AutomationRule greet = rule("Greet new customers", AutomationEntityType.CUSTOMER,
                TriggerKind.CREATED, List.of(), ActionType.CREATE_TASK,
                taskSpec("Welcome this customer", userToken(collections)));

        AutomationEvent run = fire(greet, acme.getId());

        assertThat(run.getStatus()).isEqualTo(AutomationEventStatus.DONE);
        assertThat(taskRepository.findByCustomerId(acme.getId())).singleElement().satisfies(t -> {
            assertThat(t.getTitle()).isEqualTo("Welcome this customer");
            assertThat(t.getEntityId()).isEqualTo(acme.getId());
            assertThat(t.getCreatedByUserId()).isNull();
        });
    }

    /** The assignees a rule picked are read as rows, not as people, so a repeated pick is one row. */
    @Test
    void aRepeatedPickInOneRulesAssigneeListIsWrittenOnce() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule chase = rule("Chase big invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of(), ActionType.CREATE_TASK,
                taskSpec("Chase this invoice", userToken(collections), userToken(collections),
                        roleToken("COLLECTION_POC", "CUSTOMER")));

        fire(chase, inv.getId());

        List<Assignee> on = assigneeRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(
                AssigneeOwnerType.TASK, tasksOn(inv).get(0).getId());
        assertThat(on).hasSize(2);
    }

    /** The tokens a rule stores are the tokens it was written with, whatever the run made of them. */
    @Test
    void theRuleItselfIsUnchangedByHavingRun() {
        Invoice inv = invoice("600.00", 1);
        AutomationRule chase = rule("Chase big invoices", AutomationEntityType.INVOICE,
                TriggerKind.UPDATED, List.of("total:gt:500"), ActionType.CREATE_TASK,
                taskSpec("Chase this invoice", roleToken("COLLECTION_POC", "CUSTOMER")));

        fire(chase, inv.getId());

        AutomationRule after = automationRuleRepository.findById(chase.getId()).orElseThrow();
        assertThat(after.getRunCount()).isEqualTo(1L);
        assertThat(after.getLastRunAt()).isNotNull();
        actAs(admin);
        AutomationDtos.RuleDto dto = automationService.dto(chase.getId());
        assertThat(dto.filters()).containsExactly("total:gt:500");
        assertThat(dto.actionSpec().assignees())
                .containsExactly(new EmailDtos.EmailToken("ROLE", null, "COLLECTION_POC", "CUSTOMER"));
    }
}
