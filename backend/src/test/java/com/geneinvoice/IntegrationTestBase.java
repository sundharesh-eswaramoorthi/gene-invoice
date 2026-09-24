package com.geneinvoice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.approval.ApprovalThresholdRepository;
import com.geneinvoice.approval.PendingChangeRepository;
import com.geneinvoice.auth.AppUserDetails;
import com.geneinvoice.auth.AppUserDetailsService;
import com.geneinvoice.automation.AutomationEventRepository;
import com.geneinvoice.automation.AutomationRuleRepository;
import com.geneinvoice.automation.AutomationRunRepository;
import com.geneinvoice.automation.AutomationStepRepository;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerHistoryRepository;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.dispute.DisputeHistoryRepository;
import com.geneinvoice.dispute.DisputeRepository;
import com.geneinvoice.document.DocumentRepository;
import com.geneinvoice.email.EmailRecipientRepository;
import com.geneinvoice.email.EmailRepository;
import com.geneinvoice.email.RecordingMailTransport;
import com.geneinvoice.email.connection.GmailConnectionRepository;
import com.geneinvoice.history.HistoryFloorRepository;
import com.geneinvoice.invoice.InvoiceHistoryRepository;
import com.geneinvoice.invoice.InvoiceItemHistoryRepository;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.notification.NotificationRepository;
import com.geneinvoice.payment.PaymentAllocationHistoryRepository;
import com.geneinvoice.payment.PaymentHistoryRepository;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.poc.CustomerPocHistoryRepository;
import com.geneinvoice.poc.CustomerPocRepository;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.product.ProductRepository;
import com.geneinvoice.promise.PaymentPromiseRepository;
import com.geneinvoice.promise.PromiseHistoryRepository;
import com.geneinvoice.promise.PromiseInvoiceHistoryRepository;
import com.geneinvoice.promise.PromisePaymentHistoryRepository;
import com.geneinvoice.region.CustomerRegionHistoryRepository;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionProperties;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.RegionRights;
import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.region.UserRegionGrantRepository;
import com.geneinvoice.task.TaskAssigneeRepository;
import com.geneinvoice.task.TaskHistoryRepository;
import com.geneinvoice.task.TaskRepository;
import com.geneinvoice.role.Role;
import com.geneinvoice.role.RoleRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RecordingMailTransport.Config.class)
public abstract class IntegrationTestBase {

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
    @Autowired protected RegionRepository regionRepository;
    @Autowired protected UserRegionGrantRepository userRegionGrantRepository;
    @Autowired protected CustomerRegionHistoryRepository customerRegionHistoryRepository;
    @Autowired protected RegionProperties regionProperties;
    @Autowired protected PendingChangeRepository pendingChangeRepository;
    @Autowired protected ApprovalThresholdRepository approvalThresholdRepository;
    @Autowired protected TaskRepository taskRepository;
    @Autowired protected TaskAssigneeRepository taskAssigneeRepository;
    @Autowired protected AutomationEventRepository automationEventRepository;
    @Autowired protected AutomationStepRepository automationStepRepository;
    @Autowired protected AutomationRunRepository automationRunRepository;
    @Autowired protected AutomationRuleRepository automationRuleRepository;
    // The eleven interval mirrors and the floor table. A test that drives a write now leaves
    // history behind it, and a mirror row left in the cached context would be read by the next
    // test as a version of a record that no longer exists (B3).
    @Autowired protected CustomerHistoryRepository customerHistoryRepository;
    @Autowired protected InvoiceHistoryRepository invoiceHistoryRepository;
    @Autowired protected InvoiceItemHistoryRepository invoiceItemHistoryRepository;
    @Autowired protected PaymentHistoryRepository paymentHistoryRepository;
    @Autowired protected PaymentAllocationHistoryRepository paymentAllocationHistoryRepository;
    @Autowired protected PromiseHistoryRepository promiseHistoryRepository;
    @Autowired protected PromiseInvoiceHistoryRepository promiseInvoiceHistoryRepository;
    @Autowired protected PromisePaymentHistoryRepository promisePaymentHistoryRepository;
    @Autowired protected DisputeHistoryRepository disputeHistoryRepository;
    @Autowired protected DisputeRepository disputeRepository;
    @Autowired protected CustomerPocHistoryRepository customerPocHistoryRepository;
    @Autowired protected TaskHistoryRepository taskHistoryRepository;
    @Autowired protected HistoryFloorRepository historyFloorRepository;
    @Autowired protected PasswordEncoder passwordEncoder;

    @BeforeEach
    void resetTransactionalData() {
        SecurityContextHolder.clearContext();
        mailTransport.reset();
        gmailConnectionRepository.deleteAll();
        documentRepository.deleteAll();
        emailRecipientRepository.deleteAll();
        emailRepository.deleteAll();
        // Held changes before the records they are about, and the thresholds after the customers
        // whose region they belong to, or a row left behind is read by the next test in the
        // cached context as a change somebody is still waiting on (B2).
        pendingChangeRepository.deleteAll();
        // Seats before the tasks they sit on — task_assignees.task_id is the one foreign key in
        // the programme — and tasks before the customers they name (A6).
        taskAssigneeRepository.deleteAll();
        taskRepository.deleteAll();
        // Everything automation before the customers, invoices and payments its events name: an
        // outbox row left behind is a change the next test in the cached context would be told
        // about, for a record that no longer exists (A5).
        //
        // Steps and runs before the events they came from, and the rules last of all, because a
        // step snapshots its rule's name rather than pointing at it and would otherwise be read by
        // the next test as history of a rule that has gone (A1, A5).
        automationStepRepository.deleteAll();
        automationRunRepository.deleteAll();
        automationEventRepository.deleteAll();
        automationRuleRepository.deleteAll();
        // DISPUTES, AND THIS LINE WAS MISSING UNTIL B3-RULES (B3, B2).
        //
        // It is not a tidiness fix. dispute_history has been cleared here since B3-MIRRORS while
        // the LIVE disputes table never was, so the two tables disagreed by construction the
        // moment any earlier test in the run had raised one: a live-versus-mirror comparison —
        // AsOfContractTest's 24-endpoint sweep, AsOfRegionAndApprovalTest's "a dispute raised
        // after the as-of date is not found before it" — saw disputes nothing in its own context
        // had created and no mirror row behind them. Nothing referenced them (a dispute names its
        // account, its invoice and its payment by bare id, and pending_changes is already gone
        // above), so they simply accumulated for the whole run and which test noticed depended on
        // the order the classes happened to run in.
        disputeRepository.deleteAll();
        promiseRepository.deleteAll();
        paymentRepository.deleteAll();
        invoiceRepository.deleteAll();
        customerPocRepository.deleteAll();
        notificationRepository.deleteAll();
        // Grants before the users they name, and the placements after the customers they name, so
        // nothing is left pointing at a row that has gone (B1).
        userRegionGrantRepository.deleteAll();
        customerRepository.deleteAll();
        // The mirrors LAST of all, and that is B3-WRITER's doing rather than a change of mind
        // about the order. Every delete above now fires a Hibernate delete event and leaves a
        // TOMBSTONE mirror row behind it, so clearing the mirrors before the live rows they are
        // versions of would hand the next test a table full of tombstones for records that test
        // never created. Children before parents is still the order within the block, for a
        // reader: the mirrors hold no foreign key at all, because a mirror row must outlive the
        // person, product or region it names (B3).
        invoiceItemHistoryRepository.deleteAll();
        paymentAllocationHistoryRepository.deleteAll();
        promiseInvoiceHistoryRepository.deleteAll();
        promisePaymentHistoryRepository.deleteAll();
        taskHistoryRepository.deleteAll();
        disputeHistoryRepository.deleteAll();
        customerPocHistoryRepository.deleteAll();
        promiseHistoryRepository.deleteAll();
        paymentHistoryRepository.deleteAll();
        invoiceHistoryRepository.deleteAll();
        customerHistoryRepository.deleteAll();
        historyFloorRepository.deleteAll();
        approvalThresholdRepository.deleteAll();
        customerRegionHistoryRepository.deleteAll();
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
        resetRegions();
    }

    /**
     * Regions are reference data, like roles: the default one survives every test because
     * customers.region_id is NOT NULL and customer(name) has to put a customer somewhere. Regions
     * a test made for itself do not survive, or the next test's region("WEST") would find one it
     * did not create. The two seeded logins get the grants DataSeeder gave them back, so every
     * test starts from the posture a freshly installed system has (B1).
     */
    private void resetRegions() {
        regionRepository.findAll().stream()
                .filter(r -> !r.getCode().equals(regionProperties.defaultCode()))
                .forEach(regionRepository::delete);
        Region home = defaultRegion();
        // Both wildcard levels, exactly as DataSeeder grants them: APPROVE does not cover MANAGE,
        // so an administrator holding APPROVE alone would lose every MANAGE-level privilege to the
        // authority-drop rule and could raise nothing anywhere (B1).
        grant("admin", null, RegionRight.MANAGE, RegionRight.APPROVE);
        grant("cashier", home.getId(), RegionRight.MANAGE);
    }

    private void grant(String username, Long regionId, RegionRight... rights) {
        userRepository.findByUsername(username).ifPresent(u -> {
            for (RegionRight right : rights) {
                userRegionGrantRepository.save(UserRegionGrant.builder()
                        .userId(u.getId()).regionId(regionId).right(right).build());
            }
        });
    }

    /** The region every customer(name) is placed in, and the one the backfill uses (B1). */
    protected Region defaultRegion() {
        return region(regionProperties.defaultCode());
    }

    /** Find-or-create, the role(String) shape, so a test names a region rather than building one. */
    protected Region region(String code) {
        return regionRepository.findByCode(code).orElseGet(() -> regionRepository.save(
                Region.builder().code(code).name(code + " Branch").active(true).build()));
    }

    protected Role role(String name) {
        return roleRepository.findByName(name)
                .orElseThrow(() -> new IllegalStateException("Role not seeded: " + name));
    }

    // Signature untouched, body changed: a staff user is staffed. From R3 onwards a privilege the
    // caller can exercise in no region is not in their authority set, so a fixture user with no
    // grant at all would silently lose INVOICE_MANAGE, EMAIL_SEND and the rest — a test would then
    // be asserting against a person no installation ever has. The rule is the one the migration
    // uses on every existing staff account: an administrator works everywhere, everybody else
    // works in the default region at the levels their own role implies. A test that wants an
    // unstaffed person revokes the grants explicitly and says why (B1).
    protected User user(String username, String roleName) {
        User saved = userRepository.save(User.builder()
                .username(username)
                .email(username + "@test.local")
                .fullName(username.toUpperCase())
                .password(passwordEncoder.encode("password"))
                .role(role(roleName))
                .active(true)
                .build());
        staff(saved);
        return saved;
    }

    /** The levels this person's role implies, read through the real partition rather than a name. */
    private void staff(User u) {
        Set<RegionRight> levels = new LinkedHashSet<>();
        for (Privilege p : u.getRole().getPrivileges()) {
            RegionRight needed = RegionRights.needed(p.getName());
            if (needed != null) levels.add(needed);
        }
        // A role of company-wide privileges only still sees the lists it may read (B1).
        if (levels.isEmpty()) levels.add(RegionRight.VIEW);
        boolean administrator = u.getRole().getPrivileges().stream()
                .anyMatch(p -> Privileges.USER_MANAGE.equals(p.getName()));
        Long regionId = administrator ? null : defaultRegion().getId();
        for (RegionRight right : levels) {
            userRegionGrantRepository.save(UserRegionGrant.builder()
                    .userId(u.getId()).regionId(regionId).right(right).build());
        }
    }

    /** An unstaffed person: the posture of somebody created but not yet given a branch (B1). */
    protected void revokeRegionGrants(User u) {
        userRegionGrantRepository.deleteAll(userRegionGrantRepository.findByUserId(u.getId()));
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

    // Signatures untouched, bodies changed: customers.region_id is NOT NULL from
    // RegionSchemaUpgrade onwards, so every fixture customer is placed in the default region and
    // a test that cares about a different one moves it explicitly (B1).
    protected Customer customer(String name) {
        return customerRepository.save(
                Customer.builder().name(name).region(defaultRegion()).build());
    }

    protected Customer customer(String name, String email) {
        return customerRepository.save(
                Customer.builder().name(name).email(email).region(defaultRegion()).build());
    }

    protected Product product(String name, String price) {
        return productRepository.save(Product.builder()
                .name(name).price(new BigDecimal(price)).active(true).build());
    }

    protected void actAs(User u) {
        User fresh = userRepository.findById(u.getId()).orElseThrow();
        AppUserDetails principal = new AppUserDetails(fresh, AppUserDetailsService.buildAuthorities(fresh));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    protected RequestPostProcessor as(User u) {
        User fresh = userRepository.findById(u.getId()).orElseThrow();
        AppUserDetails principal = new AppUserDetails(fresh, AppUserDetailsService.buildAuthorities(fresh));
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
