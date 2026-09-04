package com.geneinvoice.creditnote;

import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.auth.AppUserDetails;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("h2")
class CreditNoteServiceTest {

    @Autowired CreditNoteService creditNoteService;
    @Autowired CreditNoteRepository creditNoteRepository;
    @Autowired InvoiceRepository invoiceRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired UserRepository userRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired InvoiceService invoiceService;

    private static final AtomicLong SEQ = new AtomicLong();

    private User admin;

    @BeforeEach
    void authenticateAsAdmin() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        AppUserDetails principal = new AppUserDetails(admin, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private Customer newCustomer() {
        long n = SEQ.incrementAndGet();
        return customerRepository.save(Customer.builder().name("Credit Test Customer " + n).build());
    }

    private Invoice newInvoice(Customer c, String total, String paid, InvoiceStatus status) {
        long n = SEQ.incrementAndGet();
        return invoiceRepository.save(Invoice.builder()
                .customer(c)
                .invoiceNumber("CN-TEST-" + n)
                .total(new BigDecimal(total))
                .paidAmount(new BigDecimal(paid))
                .status(status)
                .build());
    }

    private static CreditNoteDtos.IssueCreditNoteRequest req(String amount, String reason) {
        return new CreditNoteDtos.IssueCreditNoteRequest(
                amount == null ? null : new BigDecimal(amount), reason);
    }

    /** AC1 / AC5: issued facts are immutable, trimmed and server-derived. */
    @Test
    void issuePersistsTrimmedReasonAndServerDerivedFacts() {
        Invoice inv = newInvoice(newCustomer(), "100.00", "0.00", InvoiceStatus.UNPAID);
        Instant started = Instant.now();

        CreditNoteDtos.CreditNoteDto dto =
                creditNoteService.issue(inv.getId(), req("25", "  Damaged goods  "));
        Instant finished = Instant.now();

        assertThat(dto.id()).isNotNull();
        assertThat(dto.invoiceId()).isEqualTo(inv.getId());
        assertThat(dto.amount()).isEqualByComparingTo("25.00");
        assertThat(dto.reason()).isEqualTo("Damaged goods");
        assertThat(dto.issuedByUserId()).isEqualTo(admin.getId());
        assertThat(dto.issuedByName()).isEqualTo(admin.getFullName());
        assertThat(dto.issuedAt()).isBetween(started, finished);
        assertThat(dto.voided()).isFalse();

        CreditNote persisted = creditNoteRepository.findById(dto.id()).orElseThrow();
        assertThat(persisted.getAmount()).isEqualByComparingTo("25.00");
        assertThat(persisted.getReason()).isEqualTo("Damaged goods");
        assertThat(persisted.getIssuedByUserId()).isEqualTo(admin.getId());
        assertThat(persisted.getIssuedAt()).isEqualTo(dto.issuedAt());
        assertThat(persisted.isVoided()).isFalse();
    }

    /** AC2: issuance lowers outstanding by the note amount; paid+credit covering total is FULLY_PAID. */
    @Test
    void successfulIssueLowersOutstandingAndCanMarkInvoiceFullyPaid() {
        Invoice inv = newInvoice(newCustomer(), "100.00", "40.00", InvoiceStatus.PARTIALLY_PAID);

        creditNoteService.issue(inv.getId(), req("60.00", "Goodwill credit"));

        Invoice reloaded = invoiceRepository.findById(inv.getId()).orElseThrow();
        assertThat(invoiceService.creditedAmount(inv.getId())).isEqualByComparingTo("60.00");
        assertThat(InvoiceService.outstandingOf(reloaded, new BigDecimal("60.00")))
                .isEqualByComparingTo("0.00");
        assertThat(reloaded.getStatus()).isEqualTo(InvoiceStatus.FULLY_PAID);
    }

    /** AC2: a partial credit lowers outstanding by exactly the note amount. */
    @Test
    void partialCreditLowersOutstandingByTheNoteAmount() {
        Invoice inv = newInvoice(newCustomer(), "80.00", "0.00", InvoiceStatus.UNPAID);

        creditNoteService.issue(inv.getId(), req("30.00", "Partial refund"));

        Invoice reloaded = invoiceRepository.findById(inv.getId()).orElseThrow();
        assertThat(invoiceService.creditedAmount(inv.getId())).isEqualByComparingTo("30.00");
        assertThat(InvoiceService.outstandingOf(reloaded, new BigDecimal("30.00")))
                .isEqualByComparingTo("50.00");
        assertThat(reloaded.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    }

    /** AC3: each successfully issued note is retained when more notes follow. */
    @Test
    void invoiceRetainsEveryIssuedCreditNote() {
        Invoice inv = newInvoice(newCustomer(), "100.00", "0.00", InvoiceStatus.UNPAID);

        creditNoteService.issue(inv.getId(), req("10.00", "First"));
        creditNoteService.issue(inv.getId(), req("20.00", "Second"));

        List<CreditNoteDtos.CreditNoteDto> notes = creditNoteService.listForInvoice(inv.getId());
        assertThat(notes).hasSize(2);
        assertThat(notes.stream().map(n -> n.amount().toPlainString()).toList())
                .containsExactlyInAnyOrder("10.00", "20.00");
        assertThat(invoiceService.creditedAmount(inv.getId())).isEqualByComparingTo("30.00");
    }

    /** AC4: zero, negative, over-precision and over-outstanding amounts persist and audit nothing. */
    @Test
    void invalidAmountsAreRefusedWithoutNotePositionOrAuditChange() {
        Invoice inv = newInvoice(newCustomer(), "100.00", "25.00", InvoiceStatus.PARTIALLY_PAID);

        assertThatThrownBy(() -> creditNoteService.issue(inv.getId(), req("0.00", "zero")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> creditNoteService.issue(inv.getId(), req("-5.00", "negative")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> creditNoteService.issue(inv.getId(), req("10.001", "precision")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("2 decimal places");
        assertThatThrownBy(() -> creditNoteService.issue(inv.getId(), req("75.01", "too much")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("still creditable")
                .hasMessageContaining("75.00");

        assertThat(creditNoteRepository.findByInvoiceIdOrderByIssuedAtDesc(inv.getId())).isEmpty();
        assertThat(auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtDesc("INVOICE", inv.getId())).isEmpty();
        Invoice reloaded = invoiceRepository.findById(inv.getId()).orElseThrow();
        assertThat(reloaded.getPaidAmount()).isEqualByComparingTo("25.00");
        assertThat(reloaded.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    }

    /** AC5: a whitespace-only reason is refused; surrounding whitespace is stripped on store. */
    @Test
    void blankReasonIsRefusedAndPaddedReasonIsStoredTrimmed() {
        Invoice inv = newInvoice(newCustomer(), "50.00", "0.00", InvoiceStatus.UNPAID);

        assertThatThrownBy(() -> creditNoteService.issue(inv.getId(), req("10.00", "   ")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("reason is required");
        assertThat(creditNoteRepository.findByInvoiceIdOrderByIssuedAtDesc(inv.getId())).isEmpty();

        CreditNoteDtos.CreditNoteDto ok =
                creditNoteService.issue(inv.getId(), req("10.00", "  Refund for damage  "));
        assertThat(ok.reason()).isEqualTo("Refund for damage");
    }

    /** AC6: a cancelled invoice accepts no new credit and keeps its cancelled status. */
    @Test
    void cancelledInvoiceRefusesIssuanceAndStaysCancelled() {
        Invoice inv = newInvoice(newCustomer(), "100.00", "0.00", InvoiceStatus.CANCELLED);

        assertThatThrownBy(() -> creditNoteService.issue(inv.getId(), req("10.00", "late credit")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cancelled");

        assertThat(creditNoteRepository.findByInvoiceIdOrderByIssuedAtDesc(inv.getId())).isEmpty();
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
    }

    /** AC7: voiding restores the amount once and the paid state follows the recalculated position. */
    @Test
    void voidRestoresAmountOnceAndStatusFollowsInBothDirections() {
        Invoice inv = newInvoice(newCustomer(), "100.00", "0.00", InvoiceStatus.UNPAID);
        CreditNoteDtos.CreditNoteDto issued =
                creditNoteService.issue(inv.getId(), req("100.00", "Full credit"));
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.FULLY_PAID);

        CreditNoteDtos.CreditNoteDto voided = creditNoteService.voidNote(inv.getId(), issued.id());

        assertThat(voided.voided()).isTrue();
        assertThat(voided.issuedAt()).isEqualTo(issued.issuedAt());
        assertThat(voided.reason()).isEqualTo("Full credit");
        Invoice reloaded = invoiceRepository.findById(inv.getId()).orElseThrow();
        assertThat(invoiceService.creditedAmount(inv.getId())).isEqualByComparingTo("0.00");
        assertThat(InvoiceService.outstandingOf(reloaded, BigDecimal.ZERO))
                .isEqualByComparingTo("100.00");
        assertThat(reloaded.getStatus()).isEqualTo(InvoiceStatus.UNPAID);

        // the voided note stays in the retained history
        List<CreditNoteDtos.CreditNoteDto> notes = creditNoteService.listForInvoice(inv.getId());
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).voided()).isTrue();
    }

    /** AC7 + settled answer: voiding after cancellation is allowed and CANCELLED stays authoritative. */
    @Test
    void voidAfterCancellationIsAllowedAndKeepsCancelledStatus() {
        Invoice inv = newInvoice(newCustomer(), "100.00", "0.00", InvoiceStatus.UNPAID);
        CreditNoteDtos.CreditNoteDto issued =
                creditNoteService.issue(inv.getId(), req("30.00", "Before cancellation"));
        inv.setStatus(InvoiceStatus.CANCELLED);
        invoiceRepository.save(inv);

        CreditNoteDtos.CreditNoteDto voided = creditNoteService.voidNote(inv.getId(), issued.id());

        assertThat(voided.voided()).isTrue();
        Invoice reloaded = invoiceRepository.findById(inv.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(invoiceService.creditedAmount(inv.getId())).isEqualByComparingTo("0.00");
    }

    /** AC8: a second void is refused and restores nothing again. */
    @Test
    void secondVoidIsRefusedAndChangesNothing() {
        Invoice inv = newInvoice(newCustomer(), "100.00", "0.00", InvoiceStatus.UNPAID);
        CreditNoteDtos.CreditNoteDto issued =
                creditNoteService.issue(inv.getId(), req("40.00", "one-shot"));
        creditNoteService.voidNote(inv.getId(), issued.id());
        int auditCount = auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtDesc("INVOICE", inv.getId()).size();

        assertThatThrownBy(() -> creditNoteService.voidNote(inv.getId(), issued.id()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already voided");

        assertThat(invoiceService.creditedAmount(inv.getId())).isEqualByComparingTo("0.00");
        Invoice reloaded = invoiceRepository.findById(inv.getId()).orElseThrow();
        assertThat(InvoiceService.outstandingOf(reloaded, BigDecimal.ZERO))
                .isEqualByComparingTo("100.00");
        assertThat(creditNoteRepository.findByInvoiceIdOrderByIssuedAtDesc(inv.getId())).hasSize(1);
        assertThat(auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtDesc("INVOICE", inv.getId()))
                .hasSize(auditCount);
    }

    /** AC9 (commit path): each successful action has its invoice audit entry; refused actions add none. */
    @Test
    void successfulIssueAndVoidEachCommitAnInvoiceAuditEntry() {
        Invoice inv = newInvoice(newCustomer(), "100.00", "0.00", InvoiceStatus.UNPAID);
        CreditNoteDtos.CreditNoteDto issued =
                creditNoteService.issue(inv.getId(), req("10.00", "audit me"));
        creditNoteService.voidNote(inv.getId(), issued.id());

        List<AuditLog> history = auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtDesc("INVOICE", inv.getId());
        assertThat(history).hasSize(2);
        assertThat(history.stream().map(AuditLog::getAction).toList())
                .containsExactlyInAnyOrder("CREDIT_NOTE_ISSUED", "CREDIT_NOTE_VOIDED");
        assertThat(history).allSatisfy(entry -> {
            assertThat(entry.getEntityType()).isEqualTo("INVOICE");
            assertThat(entry.getEntityId()).isEqualTo(inv.getId());
            assertThat(entry.getChangedByUserId()).isEqualTo(admin.getId());
            assertThat(entry.getReason()).isEqualTo("audit me");
        });
    }
}
