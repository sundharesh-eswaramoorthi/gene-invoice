package com.geneinvoice.creditnote;

import com.geneinvoice.auth.AppUserDetails;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.notification.NotificationRepository;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.role.Role;
import com.geneinvoice.role.RoleRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;

/**
 * AC10 with the REAL Spring proxies: one admin's single-recipient notification
 * {@link NotificationService#notify} is made to fail. The already-committed
 * credit note, invoice balance effect and INVOICE audit entry must remain
 * persisted, the caller must receive success-with-warning, the still-working
 * recipient must retain exactly one committed notice, and every admin is
 * attempted exactly once — no retry, no queue.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:creditnotifail;DB_CLOSE_DELAY=-1")
@ActiveProfiles("h2")
class CreditNoteIssuanceNotificationFailureTest {

    @Autowired
    CreditNoteIssuanceCoordinator coordinator;

    @Autowired
    UserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    CustomerRepository customers;

    @Autowired
    InvoiceRepository invoices;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    AuditLogRepository auditLogs;

    @Autowired
    CreditNoteRepository creditNotes;
    @Autowired
    org.springframework.transaction.PlatformTransactionManager txManager;

    @SpyBean
    NotificationService notificationService;

    private User failingAdmin;
    private Long invoiceId;

    @BeforeEach
    void setUp() {
        Role adminRole = roles.findByName("ADMIN").orElseThrow(); // seeded by DataSeeder
        failingAdmin = users.save(User.builder()
                .username("failing-admin")
                .password("irrelevant")
                .role(adminRole)
                .build());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AppUserDetails(failingAdmin, List.of()), null, List.of()));
        Customer customer = customers.save(Customer.builder().name("Fail Corp").build());
        invoiceId = invoices.save(Invoice.builder()
                .customer(customer)
                .invoiceNumber("INV-FAIL-1")
                .invoiceDate(Instant.parse("2024-05-01T10:00:00Z"))
                .total(new BigDecimal("100.00"))
                .build()).getId();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        Mockito.reset(notificationService);
    }

    @Test
    void failedNotificationLeavesNoteBalanceAndAuditCommittedWithWarning() {
        // Only this admin's single-recipient save fails; the seeded "admin" is
        // still delivered through its own independent proxied transaction.
        doThrow(new RuntimeException("notification store unavailable"))
                .when(notificationService)
                .notify(eq(failingAdmin.getId()), any(), any(), any(), any());

        CreditNoteDtos.CreditNoteResponse res = coordinator.issue(invoiceId,
                new CreditNoteDtos.IssueCreditNoteRequest(new BigDecimal("40.00"), "Damaged goods"));

        // Success-with-warning: the exact warning text carried to the caller.
        assertEquals("Credit note issued, but at least one admin notification could not be delivered",
                res.warning());

        // The committed accounting state remains: note row, balance effect, audit entry.
        CreditNote note = creditNotes.findByInvoiceIdOrderByIssuedAtDesc(invoiceId).stream()
                .filter(cn -> cn.getId().equals(res.id()))
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("40.00"), note.getAmount());
        assertEquals("Damaged goods", note.getReason());
        BigDecimal balance = new TransactionTemplate(txManager).execute(status -> {
            Invoice managed = invoices.findById(invoiceId).orElseThrow();
            return managed.getBalance();
        });
        assertEquals(0, balance.compareTo(new BigDecimal("60.00")));
        List<AuditLog> audit = auditLogs
                .findByEntityTypeAndEntityIdOrderByCreatedAtDesc("INVOICE", invoiceId);
        assertEquals(1, audit.size());
        assertEquals("CREDIT_NOTE_ISSUED", audit.get(0).getAction());

        // The unaffected admin keeps its one committed notice; the failed admin
        // has none and was attempted exactly once — no retry, no queue.
        User seededAdmin = users.findByUsername("admin").orElseThrow();
        assertEquals(1, notifications.findByUserIdOrderByCreatedAtDesc(seededAdmin.getId()).size());
        assertTrue(notifications.findByUserIdOrderByCreatedAtDesc(failingAdmin.getId()).isEmpty());
        Mockito.verify(notificationService, times(1)).notify(eq(failingAdmin.getId()), any(), any(), any(), any());
        Mockito.verify(notificationService, times(1)).notify(eq(seededAdmin.getId()), any(), any(), any(), any());
        Mockito.verify(notificationService, times(2)).notify(any(), any(), any(), any(), any());
    }

}
