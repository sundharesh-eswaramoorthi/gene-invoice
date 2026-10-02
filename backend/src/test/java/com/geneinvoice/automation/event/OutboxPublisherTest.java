package com.geneinvoice.automation.event;

import com.geneinvoice.CountingStatements;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.MovableClock;
import com.geneinvoice.auth.AppUserDetails;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.history.VersionRow;
import com.geneinvoice.history.types.InvoiceHistory;
import com.geneinvoice.history.versions.CustomerPocVersion;
import com.geneinvoice.history.versions.CustomerVersion;
import com.geneinvoice.history.versions.HistoryTransactionRow;
import com.geneinvoice.history.versions.HistoryTransactionRowRepository;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A save publishes what it did to Customers, Invoices and Payments into the outbox, in its own transaction and
 * only if it commits (A1, A5, §5.8): deduplicated per record, attributed to whoever the write was for when it was
 * published, and tied to the instant history gives the transaction. Publishing never reads a rule.
 */
class OutboxPublisherTest extends IntegrationTestBase {

    @Autowired AutomationEvents automationEvents;
    @Autowired HistoryTransactionRowRepository historyTransactionRows;
    @Autowired CustomerService customerService;
    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired PocService pocService;
    @Autowired TransactionTemplate tx;
    @Autowired PlatformTransactionManager transactionManager;

    User admin;
    User sam;
    User cara;
    Product widget;
    Customer acme;

    @BeforeEach
    void family() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        cara = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        actAs(admin);
        widget = product("Widget", "100");
        acme = customer("Acme Ltd");
    }

    @Test
    void theSaveReadsNoRules() throws Exception {
        long reads = CountingStatements.reads("automation_", () ->
                mockMvc.perform(post("/api/invoices").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("customerId", acme.getId(), "salesPocUserId", sam.getId(),
                                        "items", List.of(Map.of("productId", widget.getId(), "quantity", 1))))))
                        .andExpect(status().isOk()));

        assertThat(reads).as("selects against any automation table while saving").isZero();
        assertThat(events()).extracting(AutomationEvent::getKind, AutomationEvent::getEntityType)
                .containsExactly(tuple("CREATED", "INVOICE"), tuple("UPDATED", "CUSTOMER"));
    }

    @Test
    void aSaveThatRollsBackLeavesNoEvent() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            customerService.update(acme.getId(), rename("Never saved"));
            throw new IllegalStateException("fails after publishing");
        })).isInstanceOf(IllegalStateException.class);
        tx.executeWithoutResult(status -> {
            customerService.update(acme.getId(), rename("Rolled back"));
            status.setRollbackOnly();
        });

        assertThat(events()).isEmpty();
        assertThat(historyTransactionRows.count()).isZero();
        assertThat(customerRepository.findById(acme.getId()).orElseThrow().getName()).isEqualTo("Acme Ltd");
    }

    @Test
    void aRecomputeDryRunLeavesNoEvent() {
        Invoice inv = invoice();
        promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(), new BigDecimal("50.00"),
                Today.date().plusDays(5), cara.getId(), null, List.of(inv.getId())));
        automationEventRepository.deleteAll();

        promiseService.recomputeAll(false);

        assertThat(events()).isEmpty();
    }

    @Test
    void aReadOnlyTransactionDropsWhatItPublished() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        readOnly.executeWithoutResult(status -> automationEvents.updated(TriggerEntity.CUSTOMER, acme.getId(), "X"));

        assertThat(events()).isEmpty();
        assertThat(historyTransactionRows.count()).isZero();
    }

    @Test
    void publishingOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> automationEvents.updated(TriggerEntity.CUSTOMER, acme.getId(), "DETAILS"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> automationEvents.children(acme.getId(), "REGION"))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(events()).isEmpty();
    }

    @Test
    void anInvoicePaidFromCreditIsOneInvoiceCreatedAndOneCustomerUpdated() {
        Payment credit = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("30.00"), "Cash", null, null, cara.getId(), null));
        automationEventRepository.deleteAll();

        Invoice inv = invoice();

        List<AutomationEvent> events = events();
        assertThat(events).extracting(AutomationEvent::getKind, AutomationEvent::getEntityType,
                        AutomationEvent::getEntityId, AutomationEvent::getChanges)
                .containsExactlyInAnyOrder(
                        tuple("CREATED", "INVOICE", inv.getId(), "PAYMENT"),
                        tuple("UPDATED", "CUSTOMER", acme.getId(), "CREDIT,OUTSTANDING"),
                        tuple("UPDATED", "PAYMENT", credit.getId(), "ALLOCATION"));
        assertThat(events).extracting(AutomationEvent::getHistoryTxId).containsOnly(txOf(events.get(0)));
        assertThat(events).allSatisfy(e -> {
            assertThat(e.getStatus()).isEqualTo(AutomationEvent.PENDING);
            assertThat(e.getOrigin()).isEqualTo("USER");
            assertThat(e.getActorUserId()).isEqualTo(admin.getId());
            assertThat(e.getDepth()).isZero();
            assertThat(e.getAttempts()).isZero();
            assertThat(e.getClaims()).isZero();
            assertThat(e.getCausedByRuleId()).isNull();
            assertThat(e.getOccurredAt()).isNotNull();
        });
    }

    @Test
    void aPaymentPublishesEachInvoiceItPaysOnceAndItselfCreated() {
        Invoice a = invoice();
        Invoice b = invoice();
        automationEventRepository.deleteAll();

        Payment paid = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("250.00"), "Cash", null, List.of(a.getId(), b.getId()), cara.getId(), null));

        assertThat(events()).extracting(AutomationEvent::getKind, AutomationEvent::getEntityType,
                        AutomationEvent::getEntityId, AutomationEvent::getChanges)
                .containsExactlyInAnyOrder(
                        tuple("UPDATED", "INVOICE", a.getId(), "PAYMENT"),
                        tuple("UPDATED", "INVOICE", b.getId(), "PAYMENT"),
                        tuple("UPDATED", "CUSTOMER", acme.getId(), "OUTSTANDING,CREDIT"),
                        tuple("CREATED", "PAYMENT", paid.getId(), null));
    }

    @Test
    void eachRowOfABulkCancelPublishesInItsOwnTransactionAndOnlyIfItCommits() throws Exception {
        Invoice a = invoice();
        Invoice paid = invoice();
        Invoice failing = invoice();
        Invoice b = invoice();
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(), new BigDecimal("10.00"), "Cash",
                null, List.of(paid.getId()), cara.getId(), null));
        automationEventRepository.deleteAll();
        // This row's transaction publishes, then fails as it commits (strict history): its events must go with it.
        historyFaults.failSnapshotOf(InvoiceHistory.CODE, failing.getId());

        mockMvc.perform(post("/api/invoices/bulk").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "CANCEL",
                                "ids", List.of(a.getId(), paid.getId(), failing.getId(), b.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded.length()").value(2))
                .andExpect(jsonPath("$.failed[0].id").value(failing.getId()));

        List<AutomationEvent> events = events();
        assertThat(events).filteredOn(e -> e.getEntityType().equals("INVOICE"))
                .extracting(AutomationEvent::getEntityId, AutomationEvent::getChanges)
                .containsExactly(tuple(a.getId(), "STATUS"), tuple(b.getId(), "STATUS"));
        assertThat(events).filteredOn(e -> e.getEntityType().equals("CUSTOMER"))
                .extracting(AutomationEvent::getChanges).containsExactly("OUTSTANDING", "OUTSTANDING");
        assertThat(events.stream().map(AutomationEvent::getHistoryTxId).distinct()).hasSize(2);
        assertThat(invoiceRepository.findById(failing.getId()).orElseThrow().getStatus())
                .isNotEqualTo(InvoiceStatus.CANCELLED);
    }

    @Test
    void oneTransactionPublishesEachRecordOnceWithEveryChange() {
        tx.executeWithoutResult(status -> {
            automationEvents.updated(TriggerEntity.INVOICE, 901L, "DETAILS");
            automationEvents.created(TriggerEntity.INVOICE, 901L);
            automationEvents.updated(TriggerEntity.INVOICE, 901L, "PAYMENT");
            automationEvents.created(TriggerEntity.PAYMENT, 902L);
            automationEvents.updated(TriggerEntity.PAYMENT, 902L, "ALLOCATION");
            automationEvents.updated(TriggerEntity.CUSTOMER, acme.getId(), "DETAILS");
            automationEvents.updated(TriggerEntity.CUSTOMER, acme.getId(), "DETAILS");
            automationEvents.children(acme.getId(), "CUSTOMER_NAME");
            automationEvents.children(acme.getId(), "REGION");
            // A record not saved yet has no id to publish (S7).
            automationEvents.created(TriggerEntity.PAYMENT, null);
            automationEvents.updated(TriggerEntity.PAYMENT, 0L, "DETAILS");
        });

        assertThat(events()).extracting(AutomationEvent::getKind, AutomationEvent::getEntityType,
                        AutomationEvent::getEntityId, AutomationEvent::getChanges)
                .containsExactly(
                        tuple("CREATED", "INVOICE", 901L, "DETAILS,PAYMENT"),
                        tuple("CREATED", "PAYMENT", 902L, "ALLOCATION"),
                        tuple("UPDATED", "CUSTOMER", acme.getId(), "DETAILS"),
                        tuple("CHILDREN", "CUSTOMER", acme.getId(), "CUSTOMER_NAME,REGION"));
    }

    @Test
    void changesLongerThanTheirColumnAreFitted() {
        tx.executeWithoutResult(status -> {
            for (int i = 0; i < 60; i++) automationEvents.updated(TriggerEntity.CUSTOMER, acme.getId(), "LABEL_" + i);
            automationEvents.updated(TriggerEntity.INVOICE, 7L, "X".repeat(1000));
        });

        List<AutomationEvent> events = events();
        assertThat(events).extracting(AutomationEvent::getChanges).allSatisfy(changes -> {
            assertThat(changes).hasSize(AutomationEvent.CHANGES_LENGTH);
            assertThat(changes).endsWith("…");
        });
        assertThat(events.get(0).getChanges()).startsWith("LABEL_0,LABEL_1,");
    }

    @Test
    void everyRowPointsAtItsTransactionsInstantEvenWhenOnlySeatsOrNothingVersionedChanged() {
        CustomerPoc seat = pocService.add(acme.getId(), PocType.COLLECTION, cara.getId(), true);

        AutomationEvent seated = events().get(0);
        assertThat(seated.getChanges()).isEqualTo("POC_SEATS");
        CustomerPocVersion version = historyTestSupport.versionsOf(CustomerPocVersion.class, seat.getId()).get(0);
        assertThat(seated.getHistoryTxId()).isEqualTo(version.getTxId());
        assertThat(instantOf(seated)).isEqualTo(version.getValidFrom());

        // Saved with what it already had: no version at all, and still an instant for the event.
        automationEventRepository.deleteAll();
        int versionsBefore = historyTestSupport.versionsOf(CustomerVersion.class, acme.getId()).size();
        customerService.update(acme.getId(), rename("Acme Ltd"));

        AutomationEvent unchanged = events().get(0);
        assertThat(unchanged.getChanges()).isEqualTo("DETAILS");
        assertThat(historyTestSupport.versionsOf(CustomerVersion.class, acme.getId())).hasSize(versionsBefore);
        assertThat(instantOf(unchanged)).isNotNull();
        assertThat(historyTransactionRows.findAll())
                .extracting(HistoryTransactionRow::getTxId)
                .containsExactlyInAnyOrder(seated.getHistoryTxId(), unchanged.getHistoryTxId());
    }

    @Test
    void theEventPointSeesTheVersionsItsTransactionWroteWhenTheClockIsBehind() {
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MICROS);
        MovableClock.SHARED.set(t0);
        customerService.update(acme.getId(), rename("Acme A"));
        // Another instance whose clock is 5 ms behind saves the same customer: its version starts after the last one.
        MovableClock.SHARED.set(t0.minus(Duration.ofMillis(5)));
        automationEventRepository.deleteAll();
        customerService.update(acme.getId(), rename("Acme B"));

        AutomationEvent event = events().get(0);
        Instant point = instantOf(event);
        List<CustomerVersion> versions = historyTestSupport.versionsOf(CustomerVersion.class, acme.getId());
        CustomerVersion mine = versions.stream().filter(v -> v.getTxId().equals(event.getHistoryTxId()))
                .findFirst().orElseThrow();
        assertThat(mine.getValidFrom()).isAfter(t0.minus(Duration.ofMillis(5)));
        assertThat(point).as("the event point").isEqualTo(mine.getValidFrom());
        assertThat(versions).filteredOn(v -> !v.getValidFrom().isAfter(point) && v.getValidTo().isAfter(point))
                .singleElement().extracting(CustomerVersion::getName).isEqualTo("Acme B");
    }

    @Test
    void anAutomationWriteIsStampedWithItsRuleAndDepth() {
        WriteOrigins.run(WriteOrigin.automation(55L, 7L, 9L, 2), () ->
                tx.executeWithoutResult(status -> customerService.update(acme.getId(), rename("Acme Automated"))));

        assertThat(events()).allSatisfy(e -> {
            assertThat(e.getOrigin()).isEqualTo("AUTOMATION");
            assertThat(e.getCausedByRuleId()).isEqualTo(55L);
            assertThat(e.getDepth()).isEqualTo(2);
            assertThat(e.getActorUserId()).isEqualTo(admin.getId());
        }).hasSize(2);
        CustomerVersion version = open(CustomerVersion.class, acme.getId());
        assertThat(version.getOrigin()).isEqualTo("AUTOMATION");
        assertThat(version.getTxId()).isEqualTo(events().get(0).getHistoryTxId());
    }

    @Test
    void provenanceIsWhatItWasWhenTheRecordWasPublishedNotAtCommit() {
        Customer globex = customer("Globex");
        Authentication signedIn = SecurityContextHolder.getContext().getAuthentication();

        tx.executeWithoutResult(status -> {
            // As a run-as does: another principal and origin for part of the work, restored before the commit.
            AppUserDetails owner = principals.principalFor(userRepository.findById(sam.getId()).orElseThrow());
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities()));
            try {
                WriteOrigins.run(WriteOrigin.automation(66L, null, null, 1),
                        () -> automationEvents.updated(TriggerEntity.CUSTOMER, acme.getId(), "DETAILS"));
            } finally {
                SecurityContextHolder.getContext().setAuthentication(signedIn);
            }
            // The same record again after the run-as ended keeps the first attribution; another record gets today's.
            automationEvents.updated(TriggerEntity.CUSTOMER, acme.getId(), "CREDIT");
            automationEvents.updated(TriggerEntity.CUSTOMER, globex.getId(), "DETAILS");
        });

        assertThat(events()).extracting(AutomationEvent::getEntityId, AutomationEvent::getOrigin,
                        AutomationEvent::getCausedByRuleId, AutomationEvent::getDepth, AutomationEvent::getActorUserId,
                        AutomationEvent::getChanges)
                .containsExactly(
                        tuple(acme.getId(), "AUTOMATION", 66L, 1, sam.getId(), "DETAILS,CREDIT"),
                        tuple(globex.getId(), "USER", null, 0, admin.getId(), "DETAILS"));
    }

    @Test
    void anApprovedChangeIsStampedApprovalAndAttributedToItsMaker() {
        // How an approval applies a held change: the origin is bound around the transaction, naming the maker.
        WriteOrigins.run(WriteOrigin.approval(77L, admin.getId()).withActor(sam.getId()), () ->
                tx.executeWithoutResult(status -> invoiceService.update(invoice().getId(),
                        new InvoiceDtos.UpdateInvoiceRequest("Approved notes", null))));

        assertThat(events()).isNotEmpty().allSatisfy(e -> {
            assertThat(e.getOrigin()).isEqualTo("APPROVAL");
            assertThat(e.getActorUserId()).isEqualTo(sam.getId());
            assertThat(e.getCausedByRuleId()).isNull();
        });
    }

    @Test
    void aSystemWriteWithNobodySignedInIsStampedSystem() {
        SecurityContextHolder.clearContext();

        tx.executeWithoutResult(status -> automationEvents.updated(TriggerEntity.CUSTOMER, acme.getId(), "CREDIT"));

        assertThat(events()).singleElement().satisfies(e -> {
            assertThat(e.getOrigin()).isEqualTo("SYSTEM");
            assertThat(e.getActorUserId()).isNull();
            assertThat(instantOf(e)).isNotNull();
        });
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private Invoice invoice() {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, sam.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
    }

    private static CustomerDtos.CustomerUpdateRequest rename(String name) {
        return new CustomerDtos.CustomerUpdateRequest(name, null, null, null, null, null);
    }

    private List<AutomationEvent> events() {
        return automationEventRepository.findAll(Sort.by("id"));
    }

    /** The event's transaction id, which must have its instant recorded once the transaction committed. */
    private String txOf(AutomationEvent e) {
        assertThat(instantOf(e)).isNotNull();
        return e.getHistoryTxId();
    }

    private Instant instantOf(AutomationEvent e) {
        assertThat(e.getHistoryTxId()).as("history tx of " + e.getKind() + " " + e.getEntityType()).isNotNull();
        return historyTransactionRows.findFirstByTxId(e.getHistoryTxId())
                .map(HistoryTransactionRow::getInstant).orElse(null);
    }

    private <V extends VersionRow> V open(Class<V> type, Long id) {
        return historyTestSupport.versionsOf(type, id).stream().filter(VersionRow::isOpen).findFirst().orElseThrow();
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }
}
