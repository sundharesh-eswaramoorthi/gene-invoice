package com.geneinvoice.creditnote;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.AppUserDetails;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * FAIL5 / AC9: the audit write joins the lifecycle transaction, so when it cannot be persisted,
 * neither a credit-note row nor an invoice position change may survive.
 */
@SpringBootTest
@ActiveProfiles("h2")
class CreditNoteAuditRollbackTest {

    @Autowired CreditNoteService creditNoteService;
    @Autowired CreditNoteRepository creditNoteRepository;
    @Autowired InvoiceRepository invoiceRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired UserRepository userRepository;
    @Autowired InvoiceService invoiceService;

    @MockBean AuditService auditService;

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

    private Invoice newInvoice(String total, String paid) {
        long n = SEQ.incrementAndGet();
        Customer c = customerRepository.save(
                Customer.builder().name("Audit Rollback Customer " + n).build());
        return invoiceRepository.save(Invoice.builder()
                .customer(c)
                .invoiceNumber("CN-AUDIT-" + n)
                .total(new BigDecimal(total))
                .paidAmount(new BigDecimal(paid))
                .status(InvoiceStatus.UNPAID)
                .build());
    }

    @Test
    void auditFailureRollsBackIssuedNoteAndInvoicePosition() {
        doThrow(new IllegalStateException("audit store down")).when(auditService)
                .record(anyString(), any(), anyString(), any(), any(), any(), any(), any());
        Invoice inv = newInvoice("100.00", "0.00");

        assertThatThrownBy(() -> creditNoteService.issue(inv.getId(),
                new CreditNoteDtos.IssueCreditNoteRequest(new BigDecimal("25.00"), "will not persist")))
                .hasMessageContaining("audit store down");

        assertThat(creditNoteRepository.findByInvoiceIdOrderByIssuedAtDesc(inv.getId())).isEmpty();
        Invoice reloaded = invoiceRepository.findById(inv.getId()).orElseThrow();
        assertThat(reloaded.getPaidAmount()).isEqualByComparingTo("0.00");
        assertThat(reloaded.getStatus()).isEqualTo(InvoiceStatus.UNPAID);
        assertThat(invoiceService.creditedAmount(inv.getId())).isEqualByComparingTo("0.00");
    }

    @Test
    void auditFailureRollsBackVoidTransition() {
        Invoice inv = newInvoice("100.00", "0.00");
        CreditNoteDtos.CreditNoteDto issued = creditNoteService.issue(inv.getId(),
                new CreditNoteDtos.IssueCreditNoteRequest(new BigDecimal("25.00"), "stays active"));
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.PARTIALLY_PAID);

        doThrow(new IllegalStateException("audit store down")).when(auditService)
                .record(anyString(), any(), anyString(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> creditNoteService.voidNote(inv.getId(), issued.id()))
                .hasMessageContaining("audit store down");

        CreditNote note = creditNoteRepository.findById(issued.id()).orElseThrow();
        assertThat(note.isVoided()).isFalse();
        assertThat(invoiceService.creditedAmount(inv.getId())).isEqualByComparingTo("25.00");
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    }
}
