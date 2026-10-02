package com.geneinvoice.history;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.MovableClock;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.history.types.InvoiceHistory;
import com.geneinvoice.history.versions.CustomerPocVersion;
import com.geneinvoice.history.versions.CustomerVersion;
import com.geneinvoice.history.versions.DisputeVersion;
import com.geneinvoice.history.versions.EntityLinkVersion;
import com.geneinvoice.history.versions.InvoiceVersion;
import com.geneinvoice.history.versions.PaymentAllocationVersion;
import com.geneinvoice.history.versions.PaymentVersion;
import com.geneinvoice.history.versions.ProductVersion;
import com.geneinvoice.history.versions.PromiseVersion;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceItem;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.payment.PaymentStatus;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every write path leaves versions in the same transaction (§5.7): the version before closes at the
 * transaction's instant, exactly one new version opens from it with what the record now says, and every
 * version the transaction wrote carries its one {@code txId}, the origin and the person who wrote it. The clock
 * is fixed and moved on a minute before each write, so "the transaction's instant" is known exactly.
 */
class HistoryCaptureTest extends IntegrationTestBase {

    static final List<Class<? extends VersionRow>> TABLES = new ArrayList<>();

    static {
        HistoryModel.types().forEach(t -> TABLES.add(t.versionType()));
        TABLES.add(EntityLinkVersion.class);
    }

    @Autowired CustomerService customerService;
    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired DisputeService disputeService;
    @Autowired PocService pocService;
    @Autowired TransactionTemplate tx;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired HistoryTracker tracker;
    @PersistenceContext EntityManager em;

    User admin;
    User sam;
    User cara;
    User colin;
    Product widget;
    Customer acme;

    private final Map<Class<? extends VersionRow>, Long> seen = new HashMap<>();

    @BeforeEach
    void family() {
        MovableClock.SHARED.set(MovableClock.SHARED.instant().truncatedTo(ChronoUnit.SECONDS));
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
    void aCustomerIsVersionedWhenCreatedChangedAndDeleted() {
        Instant t = next();
        Customer made = customerService.create(new CustomerDtos.CustomerCreateRequest("Globex", "555-0101",
                "ap@globex.test", "2 High St", PaymentTerm.NET_15, "globex.login", "Password1!"));
        oneTransaction(t, admin);
        CustomerVersion created = now(CustomerVersion.class, made.getId(), t);
        assertThat(created.getName()).isEqualTo("Globex");
        assertThat(created.getPaymentTerm()).isEqualTo(PaymentTerm.NET_15);
        assertThat(created.getRegionId()).isEqualTo(defaultRegion().getId());

        t = next();
        customerService.update(made.getId(), new CustomerDtos.CustomerUpdateRequest("Globex Corp", "555-0102",
                "ap@globex.test", "2 High St", PaymentTerm.NET_30, null));
        oneTransaction(t, admin);
        CustomerVersion changed = now(CustomerVersion.class, made.getId(), t);
        assertThat(changed.getName()).isEqualTo("Globex Corp");
        assertThat(changed.getPhone()).isEqualTo("555-0102");
        assertThat(changed.getPaymentTerm()).isEqualTo(PaymentTerm.NET_30);

        pocService.add(made.getId(), PocType.COLLECTION, cara.getId(), true);
        Long seat = customerPocRepository.findAll().get(0).getId();
        t = next();
        customerService.delete(made.getId());
        assertThat(written().rows()).isEmpty();
        gone(CustomerVersion.class, made.getId(), t);
        gone(CustomerPocVersion.class, seat, t);
    }

    @Test
    void seatsAreVersionedAsTheyComeGoAndBecomePrimary() {
        Instant t = next();
        CustomerPoc first = pocService.add(acme.getId(), PocType.COLLECTION, cara.getId(), true);
        oneTransaction(t, admin);
        CustomerPocVersion seated = now(CustomerPocVersion.class, first.getId(), t);
        assertThat(seated.getUserId()).isEqualTo(cara.getId());
        assertThat(seated.getPocType()).isEqualTo(PocType.COLLECTION);
        assertThat(seated.getPrimary()).isTrue();

        t = next();
        CustomerPoc second = pocService.add(acme.getId(), PocType.COLLECTION, colin.getId(), false);
        oneTransaction(t, admin);
        assertThat(now(CustomerPocVersion.class, second.getId(), t).getPrimary()).isFalse();

        t = next();
        pocService.setPrimary(acme.getId(), second.getId());
        String txId = oneTransaction(t, admin);
        assertThat(now(CustomerPocVersion.class, second.getId(), t).getPrimary()).isTrue();
        CustomerPocVersion demoted = now(CustomerPocVersion.class, first.getId(), t);
        assertThat(demoted.getPrimary()).isFalse();
        assertThat(demoted.getTxId()).isEqualTo(txId);

        t = next();
        pocService.remove(acme.getId(), first.getId());
        gone(CustomerPocVersion.class, first.getId(), t);
    }

    // ---- invoices --------------------------------------------------------------------------------------------

    @Test
    void anInvoiceIsVersionedWhenCreatedChangedAndCancelled() {
        Instant t = next();
        Invoice inv = invoice("2");
        String txId = oneTransaction(t, admin);
        InvoiceVersion created = now(InvoiceVersion.class, inv.getId(), t);
        assertThat(created.getTotal()).isEqualByComparingTo("200");
        assertThat(created.getStatus()).isEqualTo(InvoiceStatus.UNPAID);
        assertThat(created.getSalesPocId()).isEqualTo(sam.getId());
        assertThat(created.getCustomerId()).isEqualTo(acme.getId());
        assertThat(InvoiceHistory.items(created.getItemsJson())).singleElement()
                .satisfies(line -> assertThat(line.quantity()).isEqualTo(2));
        assertThat(created.getTxId()).isEqualTo(txId);

        t = next();
        invoiceService.update(inv.getId(), new InvoiceDtos.UpdateInvoiceRequest("Ring first", null));
        oneTransaction(t, admin);
        assertThat(now(InvoiceVersion.class, inv.getId(), t).getNotes()).isEqualTo("Ring first");

        t = next();
        invoiceService.cancel(inv.getId());
        oneTransaction(t, admin);
        assertThat(now(InvoiceVersion.class, inv.getId(), t).getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
    }

    /** A line saved on its own writes no invoice row: the writer locks the invoice and versions it anyway. */
    @Test
    void aLineChangedOnItsOwnVersionsItsInvoice() {
        Invoice inv = invoice("2");
        Instant t = next();
        tx.executeWithoutResult(status -> {
            InvoiceItem line = invoiceRepository.findById(inv.getId()).orElseThrow().getItems().get(0);
            line.setQuantity(3);
            line.setLineTotal(new BigDecimal("300.00"));
        });
        oneTransaction(t, admin);
        InvoiceVersion changed = now(InvoiceVersion.class, inv.getId(), t);
        assertThat(InvoiceHistory.items(changed.getItemsJson())).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualTo(3);
            assertThat(line.lineTotal()).isEqualByComparingTo("300");
        });
        assertThat(changed.getTotal()).as("the invoice row itself was not written").isEqualByComparingTo("200");
    }

    @Test
    void spendingCreditOnANewInvoiceVersionsTheInvoiceTheCreditPaymentItsAllocationAndTheCustomer() {
        Payment credit = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("500.00"), "Cash", null, List.of(), cara.getId(), null));
        assertThat(now(CustomerVersion.class, acme.getId()).getCreditBalance()).isEqualByComparingTo("500");

        Instant t = next();
        Invoice inv = invoice("1");
        String txId = oneTransaction(t, admin);
        InvoiceVersion created = now(InvoiceVersion.class, inv.getId(), t);
        assertThat(created.getPaidAmount()).isEqualByComparingTo("100");
        assertThat(created.getStatus()).isEqualTo(InvoiceStatus.FULLY_PAID);
        assertThat(now(PaymentVersion.class, credit.getId(), t).getCreditApplied()).isEqualByComparingTo("400");
        assertThat(now(CustomerVersion.class, acme.getId(), t).getCreditBalance()).isEqualByComparingTo("400");
        PaymentAllocationVersion allocation = written().of(PaymentAllocationVersion.class).get(0);
        assertThat(allocation.getInvoiceId()).isEqualTo(inv.getId());
        assertThat(allocation.getPaymentId()).isEqualTo(credit.getId());
        assertThat(allocation.getAmount()).isEqualByComparingTo("100");
        assertThat(allocation.getTxId()).isEqualTo(txId);
    }

    @Test
    void replacingLinesAndCancellingWithARefundAreVersionedWithTheCreditTheyGiveBack() {
        Invoice inv = invoice("3");
        Payment paid = pay("300.00", inv);
        Long allocationId = only(PaymentAllocationVersion.class).getId();

        Instant t = next();
        invoiceService.replaceItems(inv.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null)),
                "One is enough");
        oneTransaction(t, admin);
        InvoiceVersion replaced = now(InvoiceVersion.class, inv.getId(), t);
        assertThat(replaced.getTotal()).isEqualByComparingTo("100");
        assertThat(replaced.getPaidAmount()).isEqualByComparingTo("100");
        assertThat(replaced.getNotes()).isEqualTo("One is enough");
        assertThat(InvoiceHistory.items(replaced.getItemsJson())).singleElement()
                .satisfies(line -> assertThat(line.quantity()).isEqualTo(1));
        assertThat(now(PaymentAllocationVersion.class, allocationId, t).getAmount()).isEqualByComparingTo("100");
        assertThat(now(PaymentVersion.class, paid.getId(), t).getCreditApplied()).isEqualByComparingTo("200");
        assertThat(now(CustomerVersion.class, acme.getId(), t).getCreditBalance()).isEqualByComparingTo("200");

        t = next();
        invoiceService.cancelWithRefund(inv.getId());
        oneTransaction(t, admin);
        InvoiceVersion cancelled = now(InvoiceVersion.class, inv.getId(), t);
        assertThat(cancelled.getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(cancelled.getPaidAmount()).isEqualByComparingTo("0");
        gone(PaymentAllocationVersion.class, allocationId, t);
        assertThat(now(PaymentVersion.class, paid.getId(), t).getCreditApplied()).isEqualByComparingTo("300");
        assertThat(now(CustomerVersion.class, acme.getId(), t).getCreditBalance()).isEqualByComparingTo("300");
    }

    // ---- payments --------------------------------------------------------------------------------------------

    @Test
    void aPaymentIsVersionedWhenRecordedChangedResizedAndVoided() {
        Invoice first = invoice("1");
        Invoice second = invoice("1");

        Instant t = next();
        Payment p = pay("150.00", first, second);
        String txId = oneTransaction(t, admin);
        PaymentVersion recorded = now(PaymentVersion.class, p.getId(), t);
        assertThat(recorded.getAmount()).isEqualByComparingTo("150");
        assertThat(recorded.getStatus()).isEqualTo(PaymentStatus.ACTIVE);
        assertThat(recorded.getCollectionPocId()).isEqualTo(cara.getId());
        assertThat(now(InvoiceVersion.class, first.getId(), t).getPaidAmount()).isEqualByComparingTo("100");
        assertThat(now(InvoiceVersion.class, second.getId(), t).getPaidAmount()).isEqualByComparingTo("50");
        assertThat(written().of(PaymentAllocationVersion.class)).hasSize(2)
                .allSatisfy(a -> assertThat(a.getTxId()).isEqualTo(txId));

        t = next();
        paymentService.update(p.getId(), new PaymentDtos.UpdatePaymentRequest("Cheque 12", colin.getId()));
        oneTransaction(t, admin);
        PaymentVersion changed = now(PaymentVersion.class, p.getId(), t);
        assertThat(changed.getNotes()).isEqualTo("Cheque 12");
        assertThat(changed.getCollectionPocId()).isEqualTo(colin.getId());

        List<Long> allocations = allVersions(PaymentAllocationVersion.class).stream().map(VersionRow::getId).toList();
        Instant resized = next();
        paymentService.updateAmount(p.getId(), new BigDecimal("80.00"), "Card", null);
        oneTransaction(resized, admin);
        assertThat(now(PaymentVersion.class, p.getId(), resized).getAmount()).isEqualByComparingTo("80");
        assertThat(now(PaymentVersion.class, p.getId(), resized).getMethod()).isEqualTo("Card");
        allocations.forEach(id -> gone(PaymentAllocationVersion.class, id, resized));
        assertThat(now(InvoiceVersion.class, first.getId(), resized).getPaidAmount()).isEqualByComparingTo("80");
        assertThat(now(InvoiceVersion.class, second.getId(), resized).getPaidAmount()).isEqualByComparingTo("0");
        PaymentAllocationVersion reallocated = written().of(PaymentAllocationVersion.class).get(0);
        assertThat(reallocated.getAmount()).isEqualByComparingTo("80");

        Instant voided = next();
        paymentService.voidPayment(p.getId());
        oneTransaction(voided, admin);
        assertThat(now(PaymentVersion.class, p.getId(), voided).getStatus()).isEqualTo(PaymentStatus.VOIDED);
        gone(PaymentAllocationVersion.class, reallocated.getId(), voided);
        assertThat(now(InvoiceVersion.class, first.getId(), voided).getPaidAmount()).isEqualByComparingTo("0");
    }

    // ---- promises --------------------------------------------------------------------------------------------

    @Test
    void aPromiseAndItsLinksAreVersionedThroughItsLife() {
        Invoice first = invoice("1");
        Invoice second = invoice("1");

        Instant t = next();
        Long id = promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(), new BigDecimal("80.00"),
                Today.date().plusDays(5), cara.getId(), "Friday", List.of(first.getId()))).id();
        String txId = oneTransaction(t, admin);
        PromiseVersion made = now(PromiseVersion.class, id, t);
        assertThat(made.getAmount()).isEqualByComparingTo("80");
        assertThat(made.getStatus()).isEqualTo(PromiseStatus.OPEN);
        assertThat(made.getCreatedByUserId()).isEqualTo(admin.getId());
        assertThat(openTargets(LinkHistory.PROMISE_INVOICE, id)).containsExactly(first.getId());
        assertThat(historyTestSupport.linksOf(LinkHistory.PROMISE_INVOICE, id)).singleElement()
                .satisfies(l -> assertThat(l.getTxId()).isEqualTo(txId));

        t = next();
        promiseService.update(id, new PromiseDtos.UpdatePromiseRequest(new BigDecimal("90.00"),
                Today.date().plusDays(6), cara.getId(), "Monday", List.of(second.getId())));
        oneTransaction(t, admin);
        PromiseVersion changed = now(PromiseVersion.class, id, t);
        assertThat(changed.getAmount()).isEqualByComparingTo("90");
        assertThat(changed.getNotes()).isEqualTo("Monday");
        assertThat(openTargets(LinkHistory.PROMISE_INVOICE, id)).containsExactly(second.getId());
        List<EntityLinkVersion> links = historyTestSupport.linksOf(LinkHistory.PROMISE_INVOICE, id);
        assertThat(links).hasSize(2);
        assertThat(links.get(0).getId()).isEqualTo(first.getId());
        assertThat(links.get(0).getValidTo()).isEqualTo(t);
        assertThat(links.get(1).getValidFrom()).isEqualTo(t);

        t = next();
        promiseService.override(id, PromiseStatus.KEPT, "Paid by phone");
        oneTransaction(t, admin);
        PromiseVersion overridden = now(PromiseVersion.class, id, t);
        assertThat(overridden.getStatus()).isEqualTo(PromiseStatus.KEPT);
        assertThat(overridden.getStatusOverridden()).isTrue();
        assertThat(overridden.getOverrideReason()).isEqualTo("Paid by phone");
        assertThat(overridden.getOverriddenByUserId()).isEqualTo(admin.getId());

        t = next();
        promiseService.clearOverride(id);
        oneTransaction(t, admin);
        PromiseVersion cleared = now(PromiseVersion.class, id, t);
        assertThat(cleared.getStatusOverridden()).isFalse();
        assertThat(cleared.getStatus()).isEqualTo(PromiseStatus.OPEN);

        pay("50.00", second);
        assertThat(openTargets(LinkHistory.PROMISE_PAYMENT, id)).hasSize(1);
        t = next();
        promiseService.cancel(id, "Raised in error");
        oneTransaction(t, admin);
        assertThat(now(PromiseVersion.class, id, t).getStatus()).isEqualTo(PromiseStatus.CANCELLED);
        assertThat(openTargets(LinkHistory.PROMISE_PAYMENT, id)).isEmpty();
    }

    @Test
    void aPaymentThatOnlyJoinsAKeptPromiseWritesALinkAndNoPromiseVersion() {
        Invoice inv = invoice("1");
        Long id = promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(), new BigDecimal("50.00"),
                Today.date().plusDays(5), cara.getId(), null, List.of(inv.getId()))).id();
        Payment first = pay("50.00", inv);
        assertThat(now(PromiseVersion.class, id).getStatus()).isEqualTo(PromiseStatus.KEPT);
        int promiseVersions = historyTestSupport.versionsOf(PromiseVersion.class, id).size();

        Instant t = next();
        Payment second = pay("20.00", inv);
        String txId = oneTransaction(t, admin);
        assertThat(historyTestSupport.versionsOf(PromiseVersion.class, id)).hasSize(promiseVersions);
        assertThat(openTargets(LinkHistory.PROMISE_PAYMENT, id)).containsExactlyInAnyOrder(first.getId(), second.getId());
        EntityLinkVersion joined = historyTestSupport.linksOf(LinkHistory.PROMISE_PAYMENT, id).stream()
                .filter(l -> l.getId().equals(second.getId())).findFirst().orElseThrow();
        assertThat(joined.getValidFrom()).isEqualTo(t);
        assertThat(joined.getTxId()).isEqualTo(txId);
    }

    /** The sweep runs on the scheduler's thread, where nobody is signed in: its versions are the system's. */
    @Test
    void theSweepVersionsAPromiseItBreaksInATransactionOfItsOwn() {
        Long id = promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(), new BigDecimal("50.00"),
                Today.date().plusDays(1), cara.getId(), null, List.of(invoice("1").getId()))).id();

        MovableClock.SHARED.advance(Duration.ofDays(3));
        Instant t = next();
        SecurityContextHolder.clearContext();
        assertThat(promiseService.sweepOverdue()).isEqualTo(1);
        actAs(admin);
        PromiseVersion broken = now(PromiseVersion.class, id, t);
        assertThat(broken.getStatus()).isEqualTo(PromiseStatus.BROKEN);
        assertThat(written().rows()).allSatisfy(v -> {
            assertThat(v.getTxId()).isEqualTo(broken.getTxId());
            assertThat(v.getOrigin()).isEqualTo("SYSTEM");
            assertThat(v.getOriginRefId()).isNull();
            assertThat(v.getActorUserId()).isNull();
        });
    }

    /**
     * A promise held since before its region moved, saved after the move committed: its row keeps the new region
     * (the ORM never writes {@code region_id}), and so does the version the save writes.
     */
    @Test
    void aPromiseSavedFromACopyOlderThanARegionMoveIsVersionedInItsNewRegion() {
        Long id = promise("50.00");
        Region south = region("SOUTH");
        TransactionTemplate move = new TransactionTemplate(transactionManager);
        move.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        tx.executeWithoutResult(status -> {
            PaymentPromise held = promiseRepository.findById(id).orElseThrow();
            move.executeWithoutResult(inner -> {
                jdbc.update("update payment_promises set region_id = ? where id = ?", south.getId(), id);
                tracker.touch(PaymentPromise.class, List.of(id), false);
            });
            held.setNotes("Saved after the move");
            promiseRepository.saveAndFlush(held);
        });

        assertThat(jdbc.queryForObject("select region_id from payment_promises where id = ?", Long.class, id))
                .isEqualTo(south.getId());
        PromiseVersion saved = now(PromiseVersion.class, id);
        assertThat(saved.getNotes()).isEqualTo("Saved after the move");
        assertThat(saved.getRegionId()).isEqualTo(south.getId());
        assertThat(historyTestSupport.versionsOf(PromiseVersion.class, id)).extracting(PromiseVersion::getRegionId)
                .containsExactly(defaultRegion().getId(), south.getId(), south.getId());
    }

    // ---- provenance ------------------------------------------------------------------------------------------

    /**
     * An origin bound around the transaction is still bound when it commits: an approved change is stamped with
     * its pending change and attributed to the maker it names, not to the checker signed in; a rule's write with
     * the rule, and to the signed-in user when it names nobody.
     */
    @Test
    void aBoundOriginIsStampedWithItsReferenceAndTheActorItNames() {
        Instant t = next();
        WriteOrigins.callWith(WriteOrigin.approval(77L, admin.getId()).withActor(sam.getId()), () -> tx.execute(s -> {
            Customer c = customerRepository.findById(acme.getId()).orElseThrow();
            c.setName("Acme Approved");
            return customerRepository.saveAndFlush(c);
        }));
        CustomerVersion approved = now(CustomerVersion.class, acme.getId(), t);
        assertThat(approved.getName()).isEqualTo("Acme Approved");
        assertThat(approved.getOrigin()).isEqualTo("APPROVAL");
        assertThat(approved.getOriginRefId()).isEqualTo(77L);
        assertThat(approved.getActorUserId()).isEqualTo(sam.getId());

        t = next();
        WriteOrigins.callWith(WriteOrigin.automation(55L, 9L, 8L, 1), () -> tx.execute(s -> {
            Customer c = customerRepository.findById(acme.getId()).orElseThrow();
            c.setName("Acme Automated");
            return customerRepository.saveAndFlush(c);
        }));
        CustomerVersion automated = now(CustomerVersion.class, acme.getId(), t);
        assertThat(automated.getName()).isEqualTo("Acme Automated");
        assertThat(automated.getOrigin()).isEqualTo("AUTOMATION");
        assertThat(automated.getOriginRefId()).isEqualTo(55L);
        assertThat(automated.getActorUserId()).isEqualTo(admin.getId());
    }

    // ---- disputes --------------------------------------------------------------------------------------------

    @Test
    void aDisputeIsVersionedWhenOpenedApprovedAndDeniedWithItsNotes() {
        Invoice inv = invoice("1");
        Invoice other = invoice("1");
        User login = customerUser("acme.login", acme.getId());

        actAs(login);
        Instant t = next();
        Dispute opened = disputeService.open(new DisputeDtos.CreateDisputeRequest(DisputeTargetType.INVOICE,
                inv.getId(), "Wrong note", "{\"action\":\"update_notes\",\"notes\":\"Corrected\"}"));
        oneTransaction(t, login);
        DisputeVersion made = now(DisputeVersion.class, opened.getId(), t);
        assertThat(made.getStatus()).isEqualTo(DisputeStatus.PENDING);
        assertThat(made.getOpenedByUserId()).isEqualTo(login.getId());
        assertThat(made.getRegionId()).isEqualTo(defaultRegion().getId());
        Dispute second = disputeService.open(new DisputeDtos.CreateDisputeRequest(DisputeTargetType.INVOICE,
                other.getId(), "Also wrong", null));

        actAs(admin);
        t = next();
        disputeService.approve(opened.getId(), new DisputeDtos.ResolveDisputeRequest("Agreed", null));
        String txId = oneTransaction(t, admin);
        DisputeVersion approved = now(DisputeVersion.class, opened.getId(), t);
        assertThat(approved.getStatus()).isEqualTo(DisputeStatus.APPROVED);
        assertThat(approved.getAdminNotes()).isEqualTo("Agreed");
        assertThat(approved.getResolvedByUserId()).isEqualTo(admin.getId());
        InvoiceVersion corrected = now(InvoiceVersion.class, inv.getId(), t);
        assertThat(corrected.getNotes()).isEqualTo("Corrected");
        assertThat(corrected.getTxId()).isEqualTo(txId);

        t = next();
        disputeService.deny(second.getId(), new DisputeDtos.ResolveDisputeRequest("Nothing to change", null));
        oneTransaction(t, admin);
        DisputeVersion denied = now(DisputeVersion.class, second.getId(), t);
        assertThat(denied.getStatus()).isEqualTo(DisputeStatus.DENIED);
        assertThat(denied.getAdminNotes()).isEqualTo("Nothing to change");
    }

    // ---- products --------------------------------------------------------------------------------------------

    @Test
    void aProductIsVersionedAndItsRenameWritesNoVersionOfTheInvoicesUsingIt() throws Exception {
        Instant t = next();
        String body = mockMvc.perform(post("/api/products").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Gadget", "description", "Small", "price", "25.50"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long gadget = objectMapper.readTree(body).get("id").asLong();
        actAs(admin);
        oneTransaction(t, admin);
        ProductVersion made = now(ProductVersion.class, gadget, t);
        assertThat(made.getName()).isEqualTo("Gadget");
        assertThat(made.getPrice()).isEqualByComparingTo("25.50");
        assertThat(made.getActive()).isTrue();

        Invoice inv = invoice("1");
        int invoiceVersions = historyTestSupport.versionsOf(InvoiceVersion.class, inv.getId()).size();
        t = next();
        mockMvc.perform(put("/api/products/{id}", widget.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Widget Mk II", "price", "100.00"))))
                .andExpect(status().isOk());
        oneTransaction(t, admin);
        assertThat(now(ProductVersion.class, widget.getId(), t).getName()).isEqualTo("Widget Mk II");
        assertThat(written().of(InvoiceVersion.class)).isEmpty();
        assertThat(historyTestSupport.versionsOf(InvoiceVersion.class, inv.getId())).hasSize(invoiceVersions);
        assertThat(historyReconciler.check().problems()).isEmpty();

        t = next();
        mockMvc.perform(delete("/api/products/{id}", gadget).with(as(admin))).andExpect(status().isOk());
        gone(ProductVersion.class, gadget, t);
    }

    // ---- bulk actions ----------------------------------------------------------------------------------------

    @Test
    void eachRowOfABulkActionIsVersionedInATransactionOfItsOwn() throws Exception {
        Invoice a = invoice("1");
        Invoice b = invoice("1");
        Instant t = next();
        bulk("/api/invoices/bulk", "REASSIGN_SALES_POC", List.of(a.getId(), b.getId()), Map.of("userId", sam.getId()));
        // Already Sam's: nothing changed, so nothing is written.
        assertThat(written().rows()).isEmpty();
        User sid = user("sid.sales", DataSeeder.ROLE_SALES_POC);
        t = next();
        bulk("/api/invoices/bulk", "REASSIGN_SALES_POC", List.of(a.getId(), b.getId()), Map.of("userId", sid.getId()));
        assertThat(now(InvoiceVersion.class, a.getId(), t).getSalesPocId()).isEqualTo(sid.getId());
        assertThat(now(InvoiceVersion.class, b.getId(), t).getSalesPocId()).isEqualTo(sid.getId());
        rowPerTransaction(2);
        t = next();
        bulk("/api/invoices/bulk", "CANCEL", List.of(a.getId(), b.getId()), Map.of());
        assertThat(now(InvoiceVersion.class, a.getId(), t).getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(now(InvoiceVersion.class, b.getId(), t).getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
        rowPerTransaction(2);

        Invoice c = invoice("1");
        Payment p = pay("10.00", c);
        Payment q = pay("10.00", c);
        t = next();
        bulk("/api/payments/bulk", "REASSIGN_COLLECTION_POC", List.of(p.getId(), q.getId()),
                Map.of("userId", colin.getId()));
        assertThat(now(PaymentVersion.class, p.getId(), t).getCollectionPocId()).isEqualTo(colin.getId());
        assertThat(now(PaymentVersion.class, q.getId(), t).getCollectionPocId()).isEqualTo(colin.getId());
        rowPerTransaction(2);

        Long x = promise("30.00");
        Long y = promise("40.00");
        t = next();
        bulk("/api/promises/bulk", "REASSIGN_COLLECTION_POC", List.of(x, y), Map.of("userId", colin.getId()));
        assertThat(now(PromiseVersion.class, x, t).getCollectionPocId()).isEqualTo(colin.getId());
        assertThat(now(PromiseVersion.class, y, t).getCollectionPocId()).isEqualTo(colin.getId());
        rowPerTransaction(2);
        t = next();
        bulk("/api/promises/bulk", "CANCEL", List.of(x, y), Map.of("reason", "Tidy up"));
        assertThat(now(PromiseVersion.class, x, t).getStatus()).isEqualTo(PromiseStatus.CANCELLED);
        assertThat(now(PromiseVersion.class, y, t).getStatus()).isEqualTo(PromiseStatus.CANCELLED);
        rowPerTransaction(2);

        Customer globex = customer("Globex");
        Instant seated = next();
        bulk("/api/customers/bulk", "ADD_POC", List.of(acme.getId(), globex.getId()),
                Map.of("userId", cara.getId(), "pocType", "COLLECTION", "primary", "true"));
        assertThat(written().of(CustomerPocVersion.class)).hasSize(2)
                .allSatisfy(seat -> {
                    assertThat(seat.getUserId()).isEqualTo(cara.getId());
                    assertThat(now(CustomerPocVersion.class, seat.getId(), seated).getVersionId())
                            .isEqualTo(seat.getVersionId());
                });
        rowPerTransaction(2);

        Product gizmo = product("Gizmo", "5");
        t = next();
        bulk("/api/products/bulk", "DEACTIVATE", List.of(widget.getId(), gizmo.getId()), Map.of());
        assertThat(now(ProductVersion.class, widget.getId(), t).getActive()).isFalse();
        assertThat(now(ProductVersion.class, gizmo.getId(), t).getActive()).isFalse();
        rowPerTransaction(2);
        t = next();
        bulk("/api/products/bulk", "ACTIVATE", List.of(widget.getId()), Map.of());
        assertThat(now(ProductVersion.class, widget.getId(), t).getActive()).isTrue();
        rowPerTransaction(1);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private Invoice invoice(String quantityOfWidgets) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, sam.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), Integer.parseInt(quantityOfWidgets), null))));
    }

    private Payment pay(String amount, Invoice... invoices) {
        return paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(), new BigDecimal(amount),
                "Cash", null, java.util.Arrays.stream(invoices).map(Invoice::getId).toList(), cara.getId(), null));
    }

    private Long promise(String amount) {
        return promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(), new BigDecimal(amount),
                Today.date().plusDays(5), cara.getId(), null, List.of())).id();
    }

    private void bulk(String url, String action, List<Long> ids, Map<String, Object> params) throws Exception {
        mockMvc.perform(post(url).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", action, "ids", ids, "params", params))))
                .andExpect(status().isOk());
        actAs(admin);   // MockMvc leaves the thread signed out
    }

    /**
     * The clock moves on a minute and is held there; everything written so far is "before". Returns the instant
     * the next transaction binds.
     */
    private Instant next() {
        MovableClock.SHARED.advance(Duration.ofMinutes(1));
        for (Class<? extends VersionRow> table : TABLES) seen.put(table, maxVersionId(table));
        return MovableClock.SHARED.instant();
    }

    /** The version rows written since {@link #next()}. */
    private Written written() {
        List<VersionRow> rows = new ArrayList<>();
        for (Class<? extends VersionRow> table : TABLES) {
            rows.addAll(tx.execute(status -> em.createQuery("select v from " + table.getSimpleName()
                            + " v where v.versionId > :seen order by v.versionId", table)
                    .setParameter("seen", seen.getOrDefault(table, 0L))
                    .getResultList()));
        }
        return new Written(rows);
    }

    record Written(List<VersionRow> rows) {
        <V extends VersionRow> List<V> of(Class<V> type) {
            return rows.stream().filter(type::isInstance).map(type::cast).toList();
        }

        boolean isEmpty() {
            return rows.isEmpty();
        }

        Map<String, List<VersionRow>> byTransaction() {
            return rows.stream().collect(Collectors.groupingBy(VersionRow::getTxId));
        }
    }

    private long maxVersionId(Class<? extends VersionRow> table) {
        Long max = tx.execute(status -> em.createQuery("select max(v.versionId) from " + table.getSimpleName() + " v",
                Long.class).getSingleResult());
        return max == null ? 0L : max;
    }

    /**
     * One transaction wrote everything since {@link #next()}: one {@code txId}, every new version opening at
     * {@code t}, and each stamped as {@code actor}'s own write. Returns the transaction's id.
     */
    private String oneTransaction(Instant t, User actor) {
        Written w = written();
        assertThat(w.rows()).as("versions written").isNotEmpty();
        String txId = w.rows().get(0).getTxId();
        assertThat(txId).hasSize(36);
        assertThat(w.rows()).allSatisfy(v -> {
            assertThat(v.getTxId()).isEqualTo(txId);
            assertThat(v.getValidFrom()).isEqualTo(t);
            assertThat(v.isOpen()).isTrue();
            assertThat(v.getOrigin()).isEqualTo("USER");
            assertThat(v.getOriginRefId()).isNull();
            assertThat(v.getActorUserId()).isEqualTo(actor.getId());
        });
        return txId;
    }

    /** Each row of a bulk action committed on its own: one transaction per row, never one shared. */
    private void rowPerTransaction(int rows) {
        Map<String, List<VersionRow>> byTx = written().byTransaction();
        assertThat(byTx).hasSize(rows);
        assertThat(written().rows()).allSatisfy(v -> {
            assertThat(v.getOrigin()).isEqualTo("USER");
            assertThat(v.getActorUserId()).isEqualTo(admin.getId());
        });
    }

    /**
     * The record's history is contiguous, its one open version opened at {@code t}, and the version before it (if
     * any) closed exactly then.
     */
    private <V extends VersionRow> V now(Class<V> type, Long id, Instant t) {
        V open = now(type, id);
        assertThat(open.getValidFrom()).as(type.getSimpleName() + " " + id + " opened").isEqualTo(t);
        List<V> all = historyTestSupport.versionsOf(type, id);
        if (all.size() > 1) {
            assertThat(all.get(all.size() - 2).getValidTo()).as(type.getSimpleName() + " " + id + " closed")
                    .isEqualTo(t);
        }
        return open;
    }

    /** The record's one open version, its history contiguous. */
    private <V extends VersionRow> V now(Class<V> type, Long id) {
        List<V> all = historyTestSupport.versionsOf(type, id);
        assertThat(all).as(type.getSimpleName() + " " + id).isNotEmpty();
        assertThat(all.stream().filter(VersionRow::isOpen)).as("open versions of " + type.getSimpleName() + " " + id)
                .hasSize(1);
        for (int i = 1; i < all.size(); i++) {
            assertThat(all.get(i - 1).getValidTo()).isEqualTo(all.get(i).getValidFrom());
        }
        V open = all.get(all.size() - 1);
        assertThat(open.isOpen()).isTrue();
        return open;
    }

    /** The record is gone: its last version closed at {@code t}, and none is open. */
    private void gone(Class<? extends VersionRow> type, Long id, Instant t) {
        List<? extends VersionRow> all = historyTestSupport.versionsOf(type, id);
        assertThat(all).as(type.getSimpleName() + " " + id).isNotEmpty().noneMatch(VersionRow::isOpen);
        assertThat(all.get(all.size() - 1).getValidTo()).isEqualTo(t);
    }

    private <V extends VersionRow> V only(Class<V> type) {
        List<V> all = allVersions(type);
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private <V extends VersionRow> List<V> allVersions(Class<V> type) {
        return historyTestSupport.allVersions(type);
    }

    private Set<Long> openTargets(String kind, Long owner) {
        return historyTestSupport.linksOf(kind, owner).stream().filter(VersionRow::isOpen)
                .map(VersionRow::getId).collect(Collectors.toSet());
    }
}
