package com.geneinvoice.automation.event;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.history.VersionRow;
import com.geneinvoice.history.versions.CustomerPocVersion;
import com.geneinvoice.history.versions.CustomerVersion;
import com.geneinvoice.history.versions.HistoryTransactionRowRepository;
import com.geneinvoice.history.versions.InvoiceVersion;
import com.geneinvoice.history.versions.PaymentAllocationVersion;
import com.geneinvoice.history.versions.PaymentVersion;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every write that changes what a customer, invoice or payment shows publishes an event for it (§5.8.3, R6). The
 * oracle is history itself: each version a scenario opens must have an event for its record in the same
 * transaction, so a write path that forgets to publish fails here without a list of paths to keep up to date.
 * <ul>
 *   <li>customer and seat versions → the customer;</li>
 *   <li>invoice and payment versions → the record, or one CHILDREN event for its customer;</li>
 *   <li>allocation versions → both the invoice and the payment;</li>
 *   <li>an invoice version whose total, paid amount or status changed → its customer's OUTSTANDING too;</li>
 *   <li>a customer version whose name changed → a CHILDREN event (the name is shown on invoices and payments).</li>
 * </ul>
 */
class TriggerCoverageTest extends IntegrationTestBase {

    @Autowired CustomerService customerService;
    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired DisputeService disputeService;
    @Autowired PocService pocService;
    @Autowired HistoryTransactionRowRepository historyTransactionRows;

    User admin;
    User sam;
    User cara;
    User colin;
    Product widget;
    Customer acme;

    private final Map<Class<? extends VersionRow>, Long> seen = new HashMap<>();

    @BeforeEach
    void family() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        cara = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        colin = user("colin.collections", DataSeeder.ROLE_COLLECTION_POC);
        actAs(admin);
        widget = product("Widget", "100");
        acme = customer("Acme Ltd");
    }

    // ---- customers and seats ---------------------------------------------------------------------------------

    @Test
    void aCustomerCreatedAndUpdatedIsPublished() {
        Customer made = scenario(() -> customerService.create(new CustomerDtos.CustomerCreateRequest("Globex",
                "555-0101", "ap@globex.test", null, null, "globex.login", "Password1!")));
        assertThat(single()).satisfies(e -> assertEvent(e, "CREATED", "CUSTOMER", made.getId()));

        scenario(() -> customerService.update(made.getId(), new CustomerDtos.CustomerUpdateRequest("Globex",
                "555-0102", "ap@globex.test", null, null, null)));
        assertThat(single()).satisfies(e -> assertEvent(e, "UPDATED", "CUSTOMER", made.getId()));
    }

    @Test
    void aRenamedCustomerPublishesOneEventForAllOfItsInvoicesAndPayments() {
        invoice();
        invoice();
        paymentService.record(payment("30.00", null));

        scenario(() -> customerService.update(acme.getId(), new CustomerDtos.CustomerUpdateRequest("Acme Group",
                null, null, null, null, null)));

        assertThat(events()).extracting(AutomationEvent::getKind, AutomationEvent::getEntityType,
                        AutomationEvent::getEntityId, AutomationEvent::getChanges)
                .containsExactly(
                        tuple("UPDATED", "CUSTOMER", acme.getId(), "DETAILS"),
                        tuple("CHILDREN", "CUSTOMER", acme.getId(), "CUSTOMER_NAME"));
    }

    @Test
    void seatsAddedMadePrimaryAndRemovedArePublishedOnTheirCustomer() {
        CustomerPoc first = scenario(() -> pocService.add(acme.getId(), PocType.COLLECTION, cara.getId(), true));
        CustomerPoc second = scenario(() -> pocService.add(acme.getId(), PocType.COLLECTION, colin.getId(), false));

        scenario(() -> pocService.setPrimary(acme.getId(), second.getId()));
        assertThat(opened(CustomerPocVersion.class)).hasSize(2);
        assertThat(single()).satisfies(e -> assertEvent(e, "UPDATED", "CUSTOMER", acme.getId()));

        // Removing the primary promotes the other seat, which is a new version of it.
        scenario(() -> {
            pocService.remove(acme.getId(), second.getId());
            return null;
        });
        assertThat(opened(CustomerPocVersion.class)).extracting(VersionRow::getId).containsExactly(first.getId());
        assertThat(single().getChanges()).isEqualTo("POC_SEATS");
    }

    // ---- invoices --------------------------------------------------------------------------------------------

    @Test
    void anInvoiceCreatedWithCreditUpdatedAndCancelledIsPublished() {
        paymentService.record(payment("30.00", null));

        Invoice fromCredit = scenario(this::invoice);
        assertThat(opened(PaymentAllocationVersion.class)).hasSize(1);
        assertThat(events()).extracting(AutomationEvent::getEntityType).containsExactlyInAnyOrder(
                "CUSTOMER", "PAYMENT", "INVOICE");

        scenario(() -> invoiceService.update(fromCredit.getId(), new InvoiceDtos.UpdateInvoiceRequest("Ring first", null)));
        assertThat(single()).satisfies(e -> assertEvent(e, "UPDATED", "INVOICE", fromCredit.getId()));

        Invoice unpaid = invoice();
        scenario(() -> invoiceService.cancel(unpaid.getId()));
        assertThat(events()).extracting(AutomationEvent::getEntityType, AutomationEvent::getChanges)
                .containsExactly(tuple("INVOICE", "STATUS"), tuple("CUSTOMER", "OUTSTANDING"));
    }

    @Test
    void anInvoiceCancelledWithARefundThroughADisputeIsPublished() {
        Invoice inv = invoice();
        Payment paid = paymentService.record(payment("60.00", List.of(inv.getId())));
        Dispute dispute = openDispute(DisputeTargetType.INVOICE, inv.getId(), "{\"action\":\"cancel\"}");

        scenario(() -> disputeService.approve(dispute.getId(), new DisputeDtos.ResolveDisputeRequest("Agreed", null)));

        assertThat(opened(PaymentAllocationVersion.class)).isEmpty();   // closed, not opened
        assertThat(opened(PaymentVersion.class)).extracting(VersionRow::getId).containsExactly(paid.getId());
        assertThat(events()).extracting(AutomationEvent::getEntityType, AutomationEvent::getEntityId)
                .contains(tuple("INVOICE", inv.getId()), tuple("PAYMENT", paid.getId()),
                        tuple("CUSTOMER", acme.getId()));
    }

    @Test
    void anInvoicesLinesReplacedThroughADisputeArePublished() {
        Invoice inv = invoice();
        paymentService.record(payment("80.00", List.of(inv.getId())));
        Dispute dispute = openDispute(DisputeTargetType.INVOICE, inv.getId(), """
                {"action":"replace_items","items":[{"productId":%d,"quantity":1,"unitPrice":50}]}"""
                .formatted(widget.getId()));

        scenario(() -> disputeService.approve(dispute.getId(), new DisputeDtos.ResolveDisputeRequest("Agreed", null)));

        assertThat(opened(InvoiceVersion.class)).singleElement()
                .satisfies(v -> assertThat(v.getTotal()).isEqualByComparingTo("50"));
        assertThat(events()).filteredOn(e -> e.getEntityType().equals("INVOICE"))
                .extracting(AutomationEvent::getChanges).containsExactly("PAYMENT,ITEMS");
    }

    @Test
    void anInvoicesNotesCorrectedThroughADisputeArePublished() {
        Invoice inv = invoice();
        Dispute dispute = openDispute(DisputeTargetType.INVOICE, inv.getId(),
                "{\"action\":\"update_notes\",\"notes\":\"Corrected\"}");

        scenario(() -> disputeService.approve(dispute.getId(), new DisputeDtos.ResolveDisputeRequest("Agreed", null)));

        assertThat(single()).satisfies(e -> assertEvent(e, "UPDATED", "INVOICE", inv.getId()));
    }

    // ---- payments --------------------------------------------------------------------------------------------

    @Test
    void aPaymentRecordedWithExcessUpdatedAndVoidedIsPublished() {
        Invoice a = invoice();
        Invoice b = invoice();

        Payment paid = scenario(() -> paymentService.record(payment("250.00", List.of(a.getId(), b.getId()))));
        assertThat(opened(PaymentAllocationVersion.class)).hasSize(2);
        assertThat(opened(CustomerVersion.class)).hasSize(1);

        scenario(() -> paymentService.update(paid.getId(), new PaymentDtos.UpdatePaymentRequest("Cheque 12", null)));
        assertThat(single()).satisfies(e -> assertEvent(e, "UPDATED", "PAYMENT", paid.getId()));

        scenario(() -> paymentService.voidPayment(paid.getId()));
        assertThat(events()).extracting(AutomationEvent::getEntityType, AutomationEvent::getEntityId)
                .containsExactlyInAnyOrder(tuple("INVOICE", a.getId()), tuple("INVOICE", b.getId()),
                        tuple("CUSTOMER", acme.getId()), tuple("PAYMENT", paid.getId()));
    }

    @Test
    void aPaymentsAmountChangedThroughADisputeIsPublished() {
        Invoice a = invoice();
        Invoice b = invoice();
        Payment paid = paymentService.record(payment("120.00", List.of(a.getId(), b.getId())));
        Dispute dispute = openDispute(DisputeTargetType.PAYMENT, paid.getId(),
                "{\"action\":\"update_amount\",\"amount\":90}");

        scenario(() -> disputeService.approve(dispute.getId(), new DisputeDtos.ResolveDisputeRequest("Agreed", null)));

        assertThat(opened(PaymentAllocationVersion.class)).isNotEmpty();
        assertThat(events()).filteredOn(e -> e.getEntityType().equals("PAYMENT"))
                .extracting(AutomationEvent::getChanges).containsExactly("AMOUNT");
    }

    @Test
    void aPaymentsDetailsCorrectedThroughADisputeArePublished() {
        Payment paid = paymentService.record(payment("20.00", null));
        Dispute dispute = openDispute(DisputeTargetType.PAYMENT, paid.getId(),
                "{\"action\":\"update_meta\",\"method\":\"Bank transfer\"}");

        scenario(() -> disputeService.approve(dispute.getId(), new DisputeDtos.ResolveDisputeRequest("Agreed", null)));

        assertThat(single()).satisfies(e -> assertEvent(e, "UPDATED", "PAYMENT", paid.getId()));
    }

    // ---- region move -----------------------------------------------------------------------------------------

    @Test
    void aRegionMoveWritesExactlyTwoEventsHoweverManyRecordsMove() throws Exception {
        Region south = region("SOUTH");
        grant(admin, south, AccessLevel.APPROVE, RecordScope.ALL);
        grant(sam, south, AccessLevel.READ, RecordScope.ALL);
        grant(cara, south, AccessLevel.READ, RecordScope.ALL);
        List<Long> invoiceIds = new ArrayList<>();
        for (int i = 0; i < 20; i++) invoiceIds.add(invoice().getId());
        for (int i = 0; i < 5; i++) paymentService.record(payment("10.00", List.of(invoiceIds.get(i))));

        scenario(() -> {
            try {
                return mockMvc.perform(post("/api/customers/" + acme.getId() + "/move-region").with(as(admin))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("regionId", south.getId()))))
                        .andExpect(status().isOk());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(opened(InvoiceVersion.class)).hasSize(20);
        assertThat(opened(PaymentVersion.class)).hasSize(5);
        assertThat(automationEventRepository.count()).isEqualTo(2);
        assertThat(events()).extracting(AutomationEvent::getKind, AutomationEvent::getEntityType,
                        AutomationEvent::getEntityId, AutomationEvent::getChanges)
                .containsExactly(
                        tuple("UPDATED", "CUSTOMER", acme.getId(), "REGION"),
                        tuple("CHILDREN", "CUSTOMER", acme.getId(), "REGION"));
    }

    // ---- the oracle ------------------------------------------------------------------------------------------

    /**
     * Runs one operation on a clean outbox, then checks that every version it opened has its event in the same
     * transaction, and that every event it wrote points at a recorded history instant.
     */
    private <T> T scenario(java.util.function.Supplier<T> operation) {
        for (Class<? extends VersionRow> type : TRACKED) seen.put(type, maxVersionId(type));
        automationEventRepository.deleteAll();

        T result = operation.get();

        List<AutomationEvent> events = events();
        List<String> problems = new ArrayList<>();
        int opened = 0;
        for (CustomerVersion v : opened(CustomerVersion.class)) {
            opened++;
            expect(problems, v, events, entity("CUSTOMER", v.getId()), "its customer");
            CustomerVersion before = previous(CustomerVersion.class, v);
            if (before != null && !Objects.equals(before.getName(), v.getName())) {
                expect(problems, v, events, children(v.getId()), "a CHILDREN event for the rename");
            }
        }
        for (CustomerPocVersion v : opened(CustomerPocVersion.class)) {
            opened++;
            expect(problems, v, events, entity("CUSTOMER", v.getCustomerId()), "its customer");
        }
        for (InvoiceVersion v : opened(InvoiceVersion.class)) {
            opened++;
            expect(problems, v, events, entity("INVOICE", v.getId()).or(children(v.getCustomerId())),
                    "the invoice, or its customer's CHILDREN");
            InvoiceVersion before = previous(InvoiceVersion.class, v);
            if (before == null || !sameMoney(before, v)) {
                expect(problems, v, events, entity("CUSTOMER", v.getCustomerId())
                        .and(e -> e.getChanges() != null && e.getChanges().contains("OUTSTANDING")),
                        "its customer's OUTSTANDING");
            }
        }
        for (PaymentVersion v : opened(PaymentVersion.class)) {
            opened++;
            expect(problems, v, events, entity("PAYMENT", v.getId()).or(children(v.getCustomerId())),
                    "the payment, or its customer's CHILDREN");
        }
        for (PaymentAllocationVersion v : opened(PaymentAllocationVersion.class)) {
            opened++;
            expect(problems, v, events, entity("INVOICE", v.getInvoiceId()), "its invoice");
            expect(problems, v, events, entity("PAYMENT", v.getPaymentId()), "its payment");
        }
        for (AutomationEvent e : events) {
            if (e.getHistoryTxId() == null || historyTransactionRows.findFirstByTxId(e.getHistoryTxId()).isEmpty()) {
                problems.add("event " + e.getKind() + " " + e.getEntityType() + " " + e.getEntityId()
                        + " has no recorded history instant");
            }
        }
        assertThat(opened).as("the scenario changed a trigger record").isPositive();
        assertThat(problems).as("versions without their event").isEmpty();
        return result;
    }

    private static final List<Class<? extends VersionRow>> TRACKED = List.of(CustomerVersion.class,
            CustomerPocVersion.class, InvoiceVersion.class, PaymentVersion.class, PaymentAllocationVersion.class);

    private static void expect(List<String> problems, VersionRow v, List<AutomationEvent> events,
                               Predicate<AutomationEvent> wanted, String what) {
        boolean found = events.stream().anyMatch(e -> v.getTxId().equals(e.getHistoryTxId()) && wanted.test(e));
        if (!found) problems.add(v.getClass().getSimpleName() + " " + v.getId() + " has no event for " + what);
    }

    private static Predicate<AutomationEvent> entity(String type, Long id) {
        return e -> type.equals(e.getEntityType()) && Objects.equals(id, e.getEntityId())
                && (e.getKind().equals("CREATED") || e.getKind().equals("UPDATED"));
    }

    private static Predicate<AutomationEvent> children(Long customerId) {
        return e -> e.getKind().equals("CHILDREN") && Objects.equals(customerId, e.getEntityId());
    }

    private static boolean sameMoney(InvoiceVersion a, InvoiceVersion b) {
        return a.getTotal().compareTo(b.getTotal()) == 0 && a.getPaidAmount().compareTo(b.getPaidAmount()) == 0
                && a.getStatus() == b.getStatus();
    }

    /** The versions of this type the last scenario opened. */
    private <V extends VersionRow> List<V> opened(Class<V> type) {
        long after = seen.getOrDefault(type, 0L);
        return historyTestSupport.allVersions(type).stream().filter(v -> v.getVersionId() > after).toList();
    }

    private <V extends VersionRow> V previous(Class<V> type, V v) {
        return historyTestSupport.versionsOf(type, v.getId()).stream()
                .filter(o -> o.getVersionId() < v.getVersionId())
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private long maxVersionId(Class<? extends VersionRow> type) {
        return historyTestSupport.allVersions(type).stream().mapToLong(VersionRow::getVersionId).max().orElse(0L);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private List<AutomationEvent> events() {
        return automationEventRepository.findAll(Sort.by("id"));
    }

    private AutomationEvent single() {
        List<AutomationEvent> events = events();
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    private static void assertEvent(AutomationEvent e, String kind, String type, Long id) {
        assertThat(e.getKind()).isEqualTo(kind);
        assertThat(e.getEntityType()).isEqualTo(type);
        assertThat(e.getEntityId()).isEqualTo(id);
    }

    private Invoice invoice() {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, sam.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
    }

    private PaymentDtos.CreatePaymentRequest payment(String amount, List<Long> invoiceIds) {
        return new PaymentDtos.CreatePaymentRequest(acme.getId(), new BigDecimal(amount), "Cash", null, invoiceIds,
                cara.getId(), null);
    }

    /** Opened by the customer's own login, then decided by admin. */
    private Dispute openDispute(DisputeTargetType type, Long targetId, String change) {
        User login = userRepository.findByCustomerId(acme.getId())
                .orElseGet(() -> customerUser("acme.login", acme.getId()));
        actAs(login);
        try {
            return disputeService.open(new DisputeDtos.CreateDisputeRequest(type, targetId, "Please change", change));
        } finally {
            actAs(admin);
        }
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }
}
