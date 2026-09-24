package com.geneinvoice.automation;

import com.geneinvoice.CountingStatements;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.approval.ApprovalContext;
import com.geneinvoice.approval.ApprovalThreshold;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The trigger, end to end: what a save publishes, what it deliberately does not, and the two
 * properties the whole design rests on — the outbox row commits with the change, and nothing the
 * engine does can reach the person who pressed Save (A1, A5).
 *
 * <p>THREE OF THESE ARE LOAD-BEARING and a reviewer should know which before simplifying anything.
 * {@code creditMovingOnAVoidedPaymentLeavesACustomerEventAlthoughNothingIsAuditedAgainstTheCustomer}
 * is the only test that fails if one of the four hand-placed credit calls is removed, because the
 * audit chokepoint genuinely never hears about those writes.
 * {@code aBulkCancelPublishesOneEventPerRow} is the only test that fails if the pending map is
 * moved off the transaction synchronization and onto a bound resource, which is the "simplify"
 * this class most invites. And {@code approvingAPendingChangeLeavesAnEventForTheRecordItChanged}
 * is the only test that fails if the hook is placed in the eight-arg {@code AuditService.record}
 * delegate instead of in the nine-arg body every write actually passes through.
 *
 * <p>The nudge is a real bean here, recording what it was handed, because two claims about it need
 * a body to be claims at all: that it fires after the commit with the committed ids, and that a
 * broken one is invisible to the caller.
 */
class AutomationTriggerTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired CustomerService customerService;
    @Autowired PocService pocService;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired AuditService auditService;
    @Autowired ChangeFeed changeFeed;
    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ApprovalContext approvalContext;

    /**
     * A consumer that RECORDS rather than acts, so that "the nudge is told about the commit" and
     * "a broken nudge never reaches the caller" are assertions about what the feed hands over
     * rather than about what a real engine then does with it (A5).
     *
     * <p>{@code @Primary} since A-CONSUMER: AutomationDispatcher now implements
     * {@link AutomationNudge} too, and ChangeFeed resolves the interface with
     * {@code ObjectProvider.getIfAvailable()}, which throws NoUniqueBeanDefinitionException for
     * two candidates — and afterCompletion swallows it, so every nudge in this forked context
     * would silently stop being delivered and these two tests would fail with an empty list
     * rather than with a reason. The marker picks this one and leaves the real engine idle here,
     * which is what the rest of this class asserts about anyway (A5).
     */
    @TestConfiguration
    static class NudgeConfig {
        @Bean
        @Primary
        AutomationNudge recordingNudge() {
            return new RecordingNudge();
        }
    }

    static class RecordingNudge implements AutomationNudge {
        static final List<Long> SEEN = new ArrayList<>();
        static boolean explode;

        @Override
        public void nudge(List<Long> eventIds) {
            SEEN.addAll(eventIds);
            if (explode) throw new IllegalStateException("the consumer is broken");
        }
    }

    User admin;
    User collections;
    Customer acme;
    Product widget;

    @BeforeEach
    void setUp() {
        RecordingNudge.SEEN.clear();
        RecordingNudge.explode = false;
        admin = userRepository.findByUsername("admin").orElseThrow();
        collections = user("cora.collections", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(admin);
    }

    // ---- coalescing ----------------------------------------------------------------------------

    @Test
    void creatingAnInvoiceLeavesExactlyOneCreatedEventEvenThoughItAuditsTwice() {
        // A payment with nothing to pay leaves 500.00 of credit held by a real payment row, which
        // is what makes the invoice below spend it through CreditLedger and audit a second time.
        paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("500.00"), "NEFT", null, List.of(),
                collections.getId(), null));
        automationEventRepository.deleteAll();

        Invoice inv = invoiceService.create(request(1));

        // Two audit rows against INVOICE in one transaction: the creation itself, and the credit
        // the creation spent. A design that published per audit row would publish twice (A1).
        assertThat(actionsAgainst("INVOICE", inv.getId()))
                .containsExactlyInAnyOrder("INVOICE_CREATED", "PAYMENT_APPLIED");

        List<AutomationEvent> forInvoice = eventsFor(SubjectType.INVOICE, inv.getId());
        assertThat(forInvoice).hasSize(1);
        // CREATED and not UPDATED: the second audit row is an update and CREATED wins the merge,
        // or a rule armed on "an invoice is raised" would miss every invoice paid from credit (A1).
        assertThat(forInvoice.get(0).getChange()).isEqualTo(Change.CREATED);
        assertThat(forInvoice.get(0).getStatus()).isEqualTo(EventStatus.NEW);
        assertThat(forInvoice.get(0).getAttempts()).isZero();

        // The same transaction moved the customer's credit balance, which is a different record
        // and therefore a different fact (A1).
        assertThat(eventsFor(SubjectType.CUSTOMER, acme.getId())).hasSize(1);
        assertThat(eventsFor(SubjectType.CUSTOMER, acme.getId()).get(0).getChange())
                .isEqualTo(Change.UPDATED);
    }

    @Test
    void aPaymentAcrossThreeInvoicesLeavesOnePaymentEventAndThreeInvoiceEvents() {
        Invoice first = invoiceService.create(request(1));
        Invoice second = invoiceService.create(request(1));
        Invoice third = invoiceService.create(request(1));
        automationEventRepository.deleteAll();

        Payment paid = paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("300.00"), "NEFT", null, List.of(),
                collections.getId(), null));

        // Coalescing is per RECORD and not per transaction: three invoices really did change.
        assertThat(subjects(SubjectType.INVOICE))
                .containsExactlyInAnyOrder(first.getId(), second.getId(), third.getId());
        assertThat(eventsFor(SubjectType.PAYMENT, paid.getId())).hasSize(1);
        assertThat(eventsFor(SubjectType.PAYMENT, paid.getId()).get(0).getChange())
                .isEqualTo(Change.CREATED);
        assertThat(eventsFor(SubjectType.INVOICE, first.getId()).get(0).getChange())
                .isEqualTo(Change.UPDATED);
        // Nothing overpaid, so no credit moved and the customer did not change.
        assertThat(eventsFor(SubjectType.CUSTOMER, acme.getId())).isEmpty();
    }

    // ---- classification ------------------------------------------------------------------------

    @Test
    void aPocSeatChangeCountsAsTheCustomerChangingAlthoughItPassesNoBefore() {
        // COLLECTION and not SALES: a Sales POC is assigned per invoice, not per customer.
        pocService.add(acme.getId(), PocType.COLLECTION, collections.getId(), true);

        // PocService.add audits with before = null. A feed that read nullness as "created" would
        // tell every rule armed on a new customer that this one was new (A1).
        assertThat(actionsAgainst("CUSTOMER", acme.getId())).contains(PocService.AUDIT_POC_ASSIGNED);
        List<AutomationEvent> events = eventsFor(SubjectType.CUSTOMER, acme.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getChange()).isEqualTo(Change.UPDATED);
    }

    @Test
    void deletingACustomerLeavesNoEventBecauseTheRecordIsGone() {
        Customer doomed = customer("Gone Ltd");

        // CUSTOMER_DELETE is alwaysChecked, so a deletion only ever runs inside a replay. This is
        // that replay's inner half exactly: ApprovalContext.applying is what ApprovalService wraps
        // the mutator in, and it is the only configuration in which CustomerService.delete runs at
        // all (B2).
        approvalContext.applying(null, () -> {
            customerService.delete(doomed.getId());
            return null;
        });

        // It really did audit — the filter is on the ACTION and not on the absence of an audit
        // row, which is what makes it survive somebody changing what a delete writes down (A1).
        assertThat(actionsAgainst("CUSTOMER", doomed.getId())).contains("CUSTOMER_DELETED");
        assertThat(customerRepository.findById(doomed.getId())).isEmpty();
        assertThat(eventsFor(SubjectType.CUSTOMER, doomed.getId())).isEmpty();
    }

    // ---- the four explicit credit calls --------------------------------------------------------

    @Test
    void creditMovingOnAVoidedPaymentLeavesACustomerEventAlthoughNothingIsAuditedAgainstTheCustomer() {
        // An overpayment with nothing to pay: the whole amount becomes customer credit.
        Payment paid = paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("400.00"), "NEFT", null, List.of(),
                collections.getId(), null));
        assertThat(customerRepository.findById(acme.getId()).orElseThrow().getCreditBalance())
                .isEqualByComparingTo("400.00");
        automationEventRepository.deleteAll();
        int auditRowsBefore = actionsAgainst("CUSTOMER", acme.getId()).size();

        paymentService.voidPayment(paid.getId());

        assertThat(customerRepository.findById(acme.getId()).orElseThrow().getCreditBalance())
                .isEqualByComparingTo("0.00");
        // THE POINT OF THIS TEST: the customer's money moved and the audit log says nothing about
        // the customer at all. Without the hand-placed ChangeFeed call in
        // PaymentService.reverseAllocations this list is empty and no rule about a customer's
        // credit can ever fire (A1).
        assertThat(actionsAgainst("CUSTOMER", acme.getId())).hasSize(auditRowsBefore);
        List<AutomationEvent> events = eventsFor(SubjectType.CUSTOMER, acme.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getChange()).isEqualTo(Change.UPDATED);
        // And the payment itself, from the DEFENSIVE call in voidPayment. It is defensive because
        // a void reached through DisputeService.approve is already audited against the payment and
        // coalescing collapses the pair — but nothing audits a void reached directly, which is the
        // path this test takes, so here the call is the only source (A1).
        assertThat(eventsFor(SubjectType.PAYMENT, paid.getId()))
                .singleElement()
                .extracting(AutomationEvent::getChange)
                .isEqualTo(Change.UPDATED);
    }

    // ---- the transaction -----------------------------------------------------------------------

    @Test
    void theEventCommitsWithTheSaveAndARolledBackSaveLeavesNone() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> tx.execute(status -> {
            invoiceService.create(request(1));
            throw new IllegalStateException("deliberate, after the save and before the commit");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(invoiceRepository.count()).isZero();
        // The outbox row is written in beforeCommit, INSIDE the caller's transaction, so a
        // rollback takes it with it. Written after the commit instead, this would be an event
        // about an invoice that never existed (A5).
        assertThat(automationEventRepository.count()).isZero();
        // And nothing was nudged, because afterCompletion only nudges on STATUS_COMMITTED.
        assertThat(RecordingNudge.SEEN).isEmpty();

        Invoice kept = tx.execute(status -> invoiceService.create(request(1)));

        List<AutomationEvent> events = eventsFor(SubjectType.INVOICE, kept.getId());
        assertThat(events).hasSize(1);
        // The nudge is handed the ids that committed, after they committed (A5).
        assertThat(RecordingNudge.SEEN).containsExactly(events.get(0).getId());
    }

    /**
     * One event per ROW, not one per run: a bulk run is a loop of PROPAGATION_REQUIRES_NEW
     * transactions, and the skipped row proves the fact commits with the row rather than with the
     * click (A5).
     *
     * <p>It does NOT by itself pin where the pending map lives, because a bulk run reaches
     * BulkExecutor from a controller with no surrounding transaction, so each row is a top-level
     * transaction either way. {@code aNestedTransactionPublishesItsOwnFactsAndNotItsCallers} is
     * the one that pins that.
     */
    @Test
    void aBulkCancelPublishesOneEventPerRow() throws Exception {
        Invoice first = invoiceService.create(request(1));
        Invoice second = invoiceService.create(request(1));
        Invoice third = invoiceService.create(request(1));
        // The middle row is already cancelled, so its transaction rolls back as "did not qualify"
        // while its neighbours commit.
        invoiceService.cancel(second.getId());
        automationEventRepository.deleteAll();

        mockMvc.perform(post("/api/invoices/bulk").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BulkDtos.BulkRequest(
                                "CANCEL",
                                List.of(first.getId(), second.getId(), third.getId()),
                                false, null, List.of(), Map.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded.length()").value(2))
                .andExpect(jsonPath("$.skipped.length()").value(1));

        assertThat(subjects(SubjectType.INVOICE))
                .containsExactlyInAnyOrder(first.getId(), third.getId());
        assertThat(invoiceRepository.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
    }

    /**
     * LOAD-BEARING for the placement of the pending map (A5).
     *
     * <p>The map lives ON the transaction synchronization and not on a resource bound to this
     * bean, because {@code getSynchronizations()} is suspended and resumed with the transaction
     * while a hand-bound resource is not. Bind it to the bean instead and the inner transaction
     * publishes the OUTER one's facts as well as its own — committing a fact about a change that
     * has not committed, and clearing it so the outer never publishes it at all. That is the
     * "simplification" this class most invites, and this is the test that refuses it.
     */
    @Test
    void aNestedTransactionPublishesItsOwnFactsAndNotItsCallers() {
        Customer other = customer("Other Ltd");
        TransactionTemplate outer = new TransactionTemplate(txManager);
        TransactionTemplate inner = new TransactionTemplate(txManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.execute(o -> {
            changeFeed.changed(SubjectType.CUSTOMER, acme.getId(), Change.UPDATED);
            inner.execute(i -> {
                changeFeed.changed(SubjectType.CUSTOMER, other.getId(), Change.UPDATED);
                return null;
            });
            // The inner transaction has committed and the outer has not. Exactly one fact is on
            // the table and it is the inner's own.
            assertThat(subjects(SubjectType.CUSTOMER)).containsExactly(other.getId());
            return null;
        });

        assertThat(subjects(SubjectType.CUSTOMER))
                .containsExactlyInAnyOrder(acme.getId(), other.getId());
    }

    @Test
    void aBackfillAuditedAgainstIdZeroPublishesNothing() {
        // InvoiceSchemaUpgrade and RegionSchemaUpgrade both anchor their backfill row on id 0
        // because audit_logs.entity_id is NOT NULL. There is no record 0, so there is nothing for
        // a rule to be about (A1).
        auditService.record("INVOICE", 0L, "INVOICE_DUE_DATES_BACKFILLED", null, null,
                null, null, "Backfilled 3 invoice due dates");

        assertThat(automationEventRepository.count()).isZero();
    }

    // ---- what the request thread does not do ---------------------------------------------------

    @Test
    void noRuleIsReadOnTheRequestThread() throws Exception {
        List<String> sql = CountingStatements.capture(() ->
                mockMvc.perform(post("/api/invoices").with(as(admin))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(request(1))))
                        .andExpect(status().isOk()));

        // One insert and not one byte of reading. The request thread writes a fact and stops; a
        // design that matched rules here would put every rule's condition SQL on the critical path
        // of every save in the application (A1, A5).
        assertThat(sql.stream().filter(s -> s.startsWith("insert into automation_events")).toList())
                .hasSize(1);
        assertThat(sql.stream()
                .filter(s -> s.startsWith("select") && s.contains("automation_"))
                .toList())
                .isEmpty();
    }

    @Test
    void aFailureInTheNudgeNeverReachesTheCaller() throws Exception {
        RecordingNudge.explode = true;

        // afterCompletion and not afterCommit: Spring logs and swallows what afterCompletion
        // throws, where afterCommit rethrows into the caller. That difference is the whole of "a
        // rule can never break the user's save" (A5).
        MvcResult result = mockMvc.perform(post("/api/invoices").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(1))))
                .andExpect(status().isOk())
                .andReturn();

        Long invoiceId = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("id").asLong();
        assertThat(invoiceRepository.findById(invoiceId)).isPresent();
        // The save stood, the fact stood, and only the hurry was lost.
        assertThat(eventsFor(SubjectType.INVOICE, invoiceId)).hasSize(1);
        assertThat(RecordingNudge.SEEN).hasSize(1);
    }

    // ---- both audit paths ----------------------------------------------------------------------

    /**
     * LOAD-BEARING for the placement of the hook (A1, B2 INTEGRATION).
     *
     * <p>The second half is the half that matters. A rejection runs no mutator at all, so the ONLY
     * audit row written is {@code CHANGE_REJECTED}, and that row goes through the nine-arg
     * {@code AuditService.record} overload. A hook left in the eight-arg delegate publishes
     * nothing for it — and, in production, publishes nothing for anything maker-checker replays.
     */
    @Test
    void approvingAPendingChangeLeavesAnEventForTheRecordItChanged() throws Exception {
        User maker = user("mary.maker", makerRole().getName());
        User checker = user("carl.checker", checkerRole().getName());
        actAs(admin);
        Invoice approved = invoiceService.create(request(1));
        Invoice rejected = invoiceService.create(request(1));
        threshold("1.00");
        automationEventRepository.deleteAll();

        Long approvedChange = held(maker, approved.getId());
        Long rejectedChange = held(maker, rejected.getId());
        automationEventRepository.deleteAll();

        mockMvc.perform(post("/api/approvals/" + approvedChange + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        assertThat(eventsFor(SubjectType.INVOICE, approved.getId())).hasSize(1);
        assertThat(invoiceRepository.findById(approved.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);

        automationEventRepository.deleteAll();
        int auditRowsBefore = actionsAgainst("INVOICE", rejected.getId()).size();

        mockMvc.perform(post("/api/approvals/" + rejectedChange + "/reject").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionNotes\":\"Not this month\"}"))
                .andExpect(status().isOk());

        // Exactly one new audit row, written by the nine-arg overload, and it published.
        List<AuditLog> after = auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtDesc("INVOICE", rejected.getId());
        assertThat(after).hasSize(auditRowsBefore + 1);
        assertThat(after.get(0).getAction()).isEqualTo("CHANGE_REJECTED");
        assertThat(after.get(0).getPendingChangeId()).isEqualTo(rejectedChange);
        assertThat(eventsFor(SubjectType.INVOICE, rejected.getId())).hasSize(1);
        assertThat(invoiceRepository.findById(rejected.getId()).orElseThrow().getStatus())
                .isNotEqualTo(InvoiceStatus.CANCELLED);
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private List<AutomationEvent> eventsFor(SubjectType type, Long id) {
        return automationEventRepository.findAll().stream()
                .filter(e -> e.getSubjectType() == type && e.getSubjectId().equals(id))
                .toList();
    }

    private List<Long> subjects(SubjectType type) {
        return automationEventRepository.findAll().stream()
                .filter(e -> e.getSubjectType() == type)
                .map(AutomationEvent::getSubjectId)
                .toList();
    }

    private List<String> actionsAgainst(String entityType, Long id) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, id)
                .stream().map(AuditLog::getAction).toList();
    }

    private InvoiceDtos.CreateInvoiceRequest request(int quantity) {
        return new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), quantity, new BigDecimal("100.00"))));
    }

    private void threshold(String amount) {
        ApprovalThreshold row = approvalThresholdRepository.findByRegionId(defaultRegion().getId())
                .orElseGet(() -> ApprovalThreshold.builder().regionId(defaultRegion().getId()).build());
        row.setAmount(new BigDecimal(amount));
        row.setEnabled(true);
        approvalThresholdRepository.saveAndFlush(row);
    }

    /** A cancellation the maker asked for and nobody has decided yet. */
    private Long held(User maker, Long invoiceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/invoices/" + invoiceId + "/cancel")
                        .with(as(maker)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("PENDING_APPROVAL"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("pendingChangeId").asLong();
    }

    private Role makerRole() {
        return roleWith("TRIGGER_MAKER",
                Privileges.CUSTOMER_VIEW, Privileges.CUSTOMER_MANAGE,
                Privileges.INVOICE_VIEW, Privileges.INVOICE_MANAGE,
                Privileges.PRODUCT_VIEW, Privileges.POC_VIEW, Privileges.POC_ASSIGN,
                Privileges.SCOPE_OVERRIDE, Privileges.APPROVAL_VIEW);
    }

    private Role checkerRole() {
        return roleWith("TRIGGER_CHECKER", Privileges.APPROVAL_VIEW, Privileges.APPROVAL_APPROVE);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by AutomationTriggerTest")
                .privileges(Arrays.stream(privileges)
                        .map(p -> privilegeRepository.findByName(p).orElseThrow())
                        .collect(Collectors.toCollection(HashSet<Privilege>::new)))
                .build()));
    }
}
