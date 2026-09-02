package com.geneinvoice.creditnote;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Real-JPA verification of the credit-note mappings and the credit-aware
 * derivation: active credits reduce outstanding, voiding restores it, and the
 * retained record is never deleted.
 */
// The workspace's purpose-built h2 profile (in-memory H2 + H2Dialect +
// create-drop); without it the default dev profile would force the PostgreSQL
// dialect onto the embedded test database.
@DataJpaTest
@ActiveProfiles("h2")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CreditNotePersistenceTest {

    @Autowired
    TestEntityManager em;

    @Autowired
    CustomerRepository customers;

    @Autowired
    InvoiceRepository invoices;

    @Autowired
    CreditNoteRepository creditNotes;

    private static final User ISSUER = User.builder().id(9L).username("manager1").build();

    private Invoice newInvoice(Customer customer, String number, String total) {
        return invoices.save(Invoice.builder()
                .customer(customer)
                .invoiceNumber(number)
                .invoiceDate(Instant.parse("2024-05-01T10:00:00Z"))
                .total(new BigDecimal(total))
                .build());
    }

    @Test
    void activeCreditReducesOutstandingAndVoidRestoresItWhileRetainingTheRecord() {
        Customer customer = customers.save(Customer.builder().name("Acme Corp").build());
        Invoice invoice = newInvoice(customer, "INV-0001", "100.00");
        creditNotes.save(CreditNote.issue(invoice, new BigDecimal("25.00"), "Damaged goods", ISSUER));
        em.flush();
        em.clear();

        Invoice active = invoices.findById(invoice.getId()).orElseThrow();
        assertEquals(0, active.getActiveCreditedTotal().compareTo(new BigDecimal("25.00")));
        assertEquals(0, active.getBalance().compareTo(new BigDecimal("75.00")));
        assertEquals(0, active.getPaidAmount().compareTo(new BigDecimal("0.00")));

        CreditNote reloaded = creditNotes.findByInvoiceIdOrderByIssuedAtDesc(invoice.getId()).get(0);
        assertEquals("Damaged goods", reloaded.getReason());
        assertEquals("manager1", reloaded.getIssuedByUsername());
        assertNotNull(reloaded.getIssuedAt());
        // AC1: exactly one new record carries the amount, trimmed reason,
        // authenticated issuer identity and server issue time of the issuance.
        assertEquals(1, creditNotes.findByInvoiceIdOrderByIssuedAtDesc(invoice.getId()).size());
        assertEquals(0, reloaded.getAmount().compareTo(new BigDecimal("25.00")));
        assertEquals(9L, reloaded.getIssuedByUserId());
        reloaded.markVoided();
        creditNotes.save(reloaded);
        em.flush();
        em.clear();

        Invoice afterVoid = invoices.findById(invoice.getId()).orElseThrow();
        // Amount restored; the voided row is retained, plainly marked.
        assertEquals(0, afterVoid.getBalance().compareTo(new BigDecimal("100.00")));
        assertEquals(0, afterVoid.getActiveCreditedTotal().compareTo(new BigDecimal("0.00")));
        assertEquals(1, afterVoid.getCreditNotes().size());
        assertEquals(CreditNoteStatus.VOIDED, afterVoid.getCreditNotes().get(0).getStatus());
        assertEquals("Damaged goods", afterVoid.getCreditNotes().get(0).getReason());
        // AC1: the later void action changed only the one-way state — every
        // immutable issuance field still holds its post-round-trip value.
        CreditNote voidedNote = afterVoid.getCreditNotes().get(0);
        assertEquals(0, voidedNote.getAmount().compareTo(new BigDecimal("25.00")));
        assertEquals("manager1", voidedNote.getIssuedByUsername());
        assertEquals(9L, voidedNote.getIssuedByUserId());
        assertEquals(reloaded.getIssuedAt(), voidedNote.getIssuedAt());
    }

    @Test
    void longTrimmedFreeTextReasonIsRetainedVerbatimInComparableTextStorage() {
        Customer customer = customers.save(Customer.builder().name("Acme Corp").build());
        Invoice invoice = newInvoice(customer, "INV-0003", "100.00");
        // The reason column follows the workspace's @Lob TEXT convention, so a long
        // free-text explanation (longer than any bounded VARCHAR field here) must be
        // stored verbatim — preserved, never shortened.
        String reason = "Customer returned several damaged pallets after the QA review; "
                + "credit per return authorisation RA-1042, agreed by phone on 2024-05-01.\n"
                + "line item detail: pallet number 7, boxes 12 through 40, mixed goods.\n"
                + "detail line ".repeat(400);
        String expected = reason; // an independent copy, so equality cannot short-circuit

        creditNotes.save(CreditNote.issue(invoice, new BigDecimal("10.00"), reason, ISSUER));
        em.flush();
        em.clear();

        CreditNote reloaded = creditNotes.findByInvoiceIdOrderByIssuedAtDesc(invoice.getId()).get(0);
        assertEquals(expected, reloaded.getReason());
    }

    @Test
    void twoIssuancesAreTwoIndependentlyRetainedNotes() {
        Customer customer = customers.save(Customer.builder().name("Acme Corp").build());
        Invoice invoice = newInvoice(customer, "INV-0002", "100.00");
        creditNotes.save(CreditNote.issue(invoice, new BigDecimal("10.00"), "First", ISSUER));
        creditNotes.save(CreditNote.issue(invoice, new BigDecimal("15.00"), "Second", ISSUER));
        em.flush();
        em.clear();

        Invoice reloaded = invoices.findById(invoice.getId()).orElseThrow();
        assertEquals(2, reloaded.getCreditNotes().size());
        assertEquals(0, reloaded.getActiveCreditedTotal().compareTo(new BigDecimal("25.00")));
        assertEquals(0, reloaded.getBalance().compareTo(new BigDecimal("75.00")));
    }

    @Test
    void lockedInvoiceQueriesAcquireInCanonicalInvoiceDateThenIdOrder() {
        Customer customer = customers.save(Customer.builder().name("Acme Corp").build());
        Invoice later = newInvoice(customer, "INV-0102", "20.00");
        Invoice earlier = newInvoice(customer, "INV-0101", "10.00");
        // Force distinct dates: earlier row created second but dated first.
        later.setInvoiceDate(Instant.parse("2024-05-02T10:00:00Z"));
        earlier.setInvoiceDate(Instant.parse("2024-05-01T10:00:00Z"));
        em.flush();
        em.clear();

        assertEquals(earlier.getId(), invoices.findByIdForUpdate(earlier.getId()).orElseThrow().getId());

        List<Invoice> locked = invoices.findByCustomerIdForUpdate(customer.getId());
        assertEquals(List.of(earlier.getId(), later.getId()), locked.stream().map(Invoice::getId).toList());

        List<Invoice> subset = invoices.findAllByIdInForUpdate(List.of(later.getId(), earlier.getId()));
        assertEquals(List.of(earlier.getId(), later.getId()), subset.stream().map(Invoice::getId).toList());
    }
}
