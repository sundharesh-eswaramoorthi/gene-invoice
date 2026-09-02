package com.geneinvoice.creditnote;

import com.geneinvoice.auth.AppUserDetails;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.notification.NotificationRepository;
import com.geneinvoice.role.Role;
import com.geneinvoice.role.RoleRepository;
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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Post-commit admin notification fan-out against the REAL Spring proxies
 * (AC9): each successful committed issuance performs a fresh fan-out, so two
 * issued notes produce exactly two persisted notices per admin, every notice
 * carrying type CREDIT_NOTE_ISSUED, title "Credit note issued", the full
 * issuance message and the existing '/invoices' route as its link.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:creditnotiflow;DB_CLOSE_DELAY=-1")
@ActiveProfiles("h2")
class CreditNoteIssuanceNotificationFlowTest {

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

    private Long invoiceId;

    @BeforeEach
    void setUp() {
        Role adminRole = roles.findByName("ADMIN").orElseThrow(); // seeded by DataSeeder
        User issuer = users.save(User.builder()
                .username("flow-manager")
                .password("irrelevant")
                .role(adminRole)
                .build());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AppUserDetails(issuer, List.of()), null, List.of()));
        Customer customer = customers.save(Customer.builder().name("Flow Corp").build());
        invoiceId = invoices.save(Invoice.builder()
                .customer(customer)
                .invoiceNumber("INV-FLOW-1")
                .invoiceDate(Instant.parse("2024-05-01T10:00:00Z"))
                .total(new BigDecimal("100.00"))
                .build()).getId();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void twoCommittedIssuancesProduceTwoNoticesPerAdminPointingToInvoices() {
        coordinator.issue(invoiceId,
                new CreditNoteDtos.IssueCreditNoteRequest(new BigDecimal("10.00"), "First credit"));
        CreditNoteDtos.CreditNoteResponse res2 = coordinator.issue(invoiceId,
                new CreditNoteDtos.IssueCreditNoteRequest(new BigDecimal("30.00"), "Second credit"));
        // Full success carries no warning.
        assertNull(res2.warning());

        // Two admins: the seeded "admin" plus this test's issuer.
        List<User> admins = users.findByRoleName("ADMIN");
        assertEquals(2, admins.size());
        for (User admin : admins) {
            List<Notification> notices = notifications.findByUserIdOrderByCreatedAtDesc(admin.getId());
            assertEquals(2, notices.size(),
                    "two committed issuances produce two notices for " + admin.getUsername());
            for (Notification n : notices) {
                assertEquals("CREDIT_NOTE_ISSUED", n.getType());
                assertEquals("Credit note issued", n.getTitle());
                assertEquals("/invoices", n.getLink());
            }
            Set<String> messages = notices.stream()
                    .map(Notification::getMessage)
                    .collect(Collectors.toSet());
            assertEquals(Set.of(
                    "Credit note of 10.00 issued for invoice INV-FLOW-1 by flow-manager.",
                    "Credit note of 30.00 issued for invoice INV-FLOW-1 by flow-manager."), messages);
        }
    }
}
