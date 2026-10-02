package com.geneinvoice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.approval.ApprovalThresholdRepository;
import com.geneinvoice.approval.ApprovalUsageRepository;
import com.geneinvoice.approval.PendingChangeRepository;
import com.geneinvoice.assignee.RecordAssigneeRepository;
import com.geneinvoice.automation.RecordingAutomationFaults;
import com.geneinvoice.automation.event.AutomationEventRepository;
import com.geneinvoice.automation.rule.AutomationRuleRepository;
import com.geneinvoice.automation.rule.AutomationRuleRevisionRepository;
import com.geneinvoice.automation.run.AutomationEffectRepository;
import com.geneinvoice.automation.run.AutomationRunRepository;
import com.geneinvoice.auth.AppUserDetails;
import com.geneinvoice.auth.AppUserDetailsService;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.dispute.DisputeRepository;
import com.geneinvoice.document.DocumentRepository;
import com.geneinvoice.email.EmailRecipientRepository;
import com.geneinvoice.email.EmailRepository;
import com.geneinvoice.email.RecordingMailTransport;
import com.geneinvoice.email.connection.GmailConnectionRepository;
import com.geneinvoice.history.HistoryReconciler;
import com.geneinvoice.history.HistoryTestHooks;
import com.geneinvoice.history.HistoryTestSupport;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.notification.NotificationRepository;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.poc.CustomerPocRepository;
import com.geneinvoice.product.Product;
import com.geneinvoice.product.ProductRepository;
import com.geneinvoice.promise.PaymentPromiseRepository;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.CustomerRegionMoveRepository;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionGrantService;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.region.TestMoveParticipant;
import com.geneinvoice.region.UserRegionGrantRepository;
import com.geneinvoice.common.scope.ViewScope;
import com.geneinvoice.role.Role;
import com.geneinvoice.role.RoleRepository;
import com.geneinvoice.task.TaskRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTestBase.TestBeans.class)
public abstract class IntegrationTestBase {

    /** The one test configuration: every extra import, bean or property would start another context. */
    @TestConfiguration(proxyBeanMethods = false)
    @Import(RecordingMailTransport.Config.class)
    public static class TestBeans {

        @Bean
        @Primary
        Clock testClock() {
            return MovableClock.SHARED;
        }

        @Bean
        @Primary
        RecordingAutomationFaults automationFaults() {
            return new RecordingAutomationFaults();
        }

        @Bean
        @Primary
        HistoryTestHooks historyFaults() {
            return new HistoryTestHooks();
        }

        @Bean
        HistoryTestSupport historyTestSupport() {
            return new HistoryTestSupport();
        }

        @Bean
        TestMoveParticipant testMoveParticipant() {
            return new TestMoveParticipant();
        }
    }

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected UserRepository userRepository;
    @Autowired protected RoleRepository roleRepository;
    @Autowired protected CustomerRepository customerRepository;
    @Autowired protected ProductRepository productRepository;
    @Autowired protected InvoiceRepository invoiceRepository;
    @Autowired protected PaymentRepository paymentRepository;
    @Autowired protected PaymentPromiseRepository promiseRepository;
    @Autowired protected CustomerPocRepository customerPocRepository;
    @Autowired protected NotificationRepository notificationRepository;
    @Autowired protected DocumentRepository documentRepository;
    @Autowired protected EmailRepository emailRepository;
    @Autowired protected EmailRecipientRepository emailRecipientRepository;
    @Autowired protected RecordingMailTransport mailTransport;
    @Autowired protected GmailConnectionRepository gmailConnectionRepository;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected DisputeRepository disputeRepository;
    @Autowired protected RegionRepository regionRepository;
    @Autowired protected UserRegionGrantRepository grantRepository;
    @Autowired protected RegionGrantService regionGrantService;
    @Autowired protected AppUserDetailsService principals;
    @Autowired protected HistoryTestHooks historyFaults;
    @Autowired protected RecordingAutomationFaults automationFaults;
    @Autowired protected HistoryTestSupport historyTestSupport;
    @Autowired protected HistoryReconciler historyReconciler;
    @Autowired protected CustomerRegionMoveRepository customerRegionMoveRepository;
    @Autowired protected TestMoveParticipant moveParticipant;
    @Autowired protected AutomationEventRepository automationEventRepository;
    @Autowired protected AutomationEffectRepository automationEffectRepository;
    @Autowired protected AutomationRunRepository automationRunRepository;
    @Autowired protected AutomationRuleRevisionRepository automationRuleRevisionRepository;
    @Autowired protected AutomationRuleRepository automationRuleRepository;
    @Autowired protected RecordAssigneeRepository recordAssigneeRepository;
    @Autowired protected TaskRepository taskRepository;
    @Autowired protected PendingChangeRepository pendingChangeRepository;
    @Autowired protected ApprovalThresholdRepository approvalThresholdRepository;
    @Autowired protected ApprovalUsageRepository approvalUsageRepository;

    @BeforeEach
    protected void resetTransactionalData() {
        SecurityContextHolder.clearContext();
        ViewScopeHolder.clear();
        WriteOrigins.clear();
        MovableClock.SHARED.reset();
        // Another context booted in this JVM would have pointed the static bridge at its own clock.
        AppClock.set(MovableClock.SHARED);
        mailTransport.reset();
        automationFaults.reset();
        historyFaults.reset();
        moveParticipant.reset();
        historyTestSupport.clearVersions();
        // First: a threshold left behind would hold the next test's changes.
        pendingChangeRepository.deleteAll();
        approvalThresholdRepository.deleteAll();
        approvalUsageRepository.deleteAll();
        recordAssigneeRepository.deleteAll();
        taskRepository.deleteAll();
        automationEffectRepository.deleteAll();
        automationRunRepository.deleteAll();
        automationEventRepository.deleteAll();
        automationRuleRevisionRepository.deleteAll();
        // A rule's regions go with it (an element collection).
        automationRuleRepository.deleteAll();
        customerRegionMoveRepository.deleteAll();
        gmailConnectionRepository.deleteAll();
        documentRepository.deleteAll();
        emailRecipientRepository.deleteAll();
        emailRepository.deleteAll();
        promiseRepository.deleteAll();
        paymentRepository.deleteAll();
        invoiceRepository.deleteAll();
        customerPocRepository.deleteAll();
        notificationRepository.deleteAll();
        // Before the customers: a dispute keeps a plain customer id, so nothing else would remove it.
        disputeRepository.deleteAll();
        customerRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.findAll().stream()
                .filter(u -> !List.of("admin", "cashier").contains(u.getUsername()))
                .forEach(userRepository::delete);
        userRepository.findAll().forEach(u -> {
            if (!u.isActive()) {
                u.setActive(true);
                userRepository.save(u);
            }
        });
        // Grants before their regions, and regions after everything that points at one.
        grantRepository.deleteByRegionCodeNot(Region.DEFAULT_CODE);
        regionRepository.deleteByCodeNot(Region.DEFAULT_CODE);
        Region defaultRegion = regionRepository.findByCode(Region.DEFAULT_CODE).orElseThrow();
        if (!defaultRegion.isActive()) {
            defaultRegion.setActive(true);
            regionRepository.save(defaultRegion);
        }
        regionRepository.clearDefaultExcept(defaultRegion.getId());
        regionRepository.markDefault(defaultRegion.getId());
        for (String seeded : List.of("admin", "cashier")) {
            userRepository.findByUsername(seeded).ifPresent(u -> {
                grantRepository.deleteAllOfUser(u.getId());
                regionGrantService.ensure(u, defaultRegion(), AccessLevel.APPROVE, RecordScope.ALL);
            });
        }
    }

    /**
     * Every write the test made through the application left history in step with the live tables: each record
     * has exactly one open version that says what the record says, and nothing gone is still open.
     */
    @AfterEach
    protected void historyIsInStep() {
        ViewScopeHolder.clear();
        historyFaults.reset();
        assertThat(historyReconciler.check().problems()).as("history out of step with the live tables").isEmpty();
    }

    /** Looked up on every call: nothing about the default region is cached between tests. */
    protected Region defaultRegion() {
        return regionRepository.findByIsDefaultTrue().orElseThrow();
    }

    /** An active region with this code, created on first use. */
    protected Region region(String code) {
        return regionRepository.findByCode(code).orElseGet(() -> regionRepository.save(Region.builder()
                .code(code)
                .name(code.charAt(0) + code.substring(1).toLowerCase())
                .active(true)
                .defaultRegion(false)
                .build()));
    }

    protected void grant(User u, Region region, AccessLevel level, RecordScope scope) {
        regionGrantService.ensure(u, region, level, scope);
    }

    /** "Which regions am I asking about" for a direct service call, as the interceptor sets it for a request. */
    protected void asking(Long... regionIds) {
        ViewScopeHolder.set(new ViewScope(Set.of(regionIds), null));
    }

    protected Role role(String name) {
        return roleRepository.findByName(name)
                .orElseThrow(() -> new IllegalStateException("Role not seeded: " + name));
    }

    /** A staff user who holds the default region at APPROVE/ALL: the role alone decides, as before regions. */
    protected User user(String username, String roleName) {
        User saved = userRepository.save(User.builder()
                .username(username)
                .email(username + "@test.local")
                .fullName(username.toUpperCase())
                .password(passwordEncoder.encode("password"))
                .role(role(roleName))
                .active(true)
                .build());
        grant(saved, defaultRegion(), AccessLevel.APPROVE, RecordScope.ALL);
        return saved;
    }

    protected User customerUser(String username, Long customerId) {
        return userRepository.save(User.builder()
                .username(username)
                .email(username + "@test.local")
                .fullName(username.toUpperCase())
                .password(passwordEncoder.encode("password"))
                .role(role("CUSTOMER"))
                .customerId(customerId)
                .active(true)
                .build());
    }

    protected Customer customer(String name) {
        return customer(name, defaultRegion());
    }

    protected Customer customer(String name, String email) {
        return customerRepository.save(Customer.builder().name(name).email(email).region(defaultRegion()).build());
    }

    protected Customer customer(String name, Region region) {
        return customerRepository.save(Customer.builder().name(name).region(region).build());
    }

    protected Product product(String name, String price) {
        return productRepository.save(Product.builder()
                .name(name).price(new BigDecimal(price)).active(true).build());
    }

    protected void actAs(User u) {
        User fresh = userRepository.findById(u.getId()).orElseThrow();
        AppUserDetails principal = principals.principalFor(fresh);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    protected RequestPostProcessor as(User u) {
        User fresh = userRepository.findById(u.getId()).orElseThrow();
        AppUserDetails principal = principals.principalFor(fresh);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    protected String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
