package com.geneinvoice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.auth.AppUserDetails;
import com.geneinvoice.auth.AppUserDetailsService;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.document.DocumentRepository;
import com.geneinvoice.email.EmailRecipientRepository;
import com.geneinvoice.email.EmailRepository;
import com.geneinvoice.email.RecordingMailTransport;
import com.geneinvoice.email.connection.GmailConnectionRepository;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.notification.NotificationRepository;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.poc.CustomerPocRepository;
import com.geneinvoice.product.Product;
import com.geneinvoice.product.ProductRepository;
import com.geneinvoice.promise.PaymentPromiseRepository;
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
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Boots the real application context against an in-memory database and clears the transactional
 * tables between tests, so each test reasons about exactly the rows it created. Mail goes to
 * {@link RecordingMailTransport}, which stands in for the mail service and is reset to "not
 * configured" before each test.
 */
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
    @Autowired protected PasswordEncoder passwordEncoder;

    @BeforeEach
    void resetTransactionalData() {
        SecurityContextHolder.clearContext();
        mailTransport.reset();
        gmailConnectionRepository.deleteAll();
        documentRepository.deleteAll();
        emailRecipientRepository.deleteAll();
        emailRepository.deleteAll();
        promiseRepository.deleteAll();
        paymentRepository.deleteAll();
        invoiceRepository.deleteAll();
        customerPocRepository.deleteAll();
        notificationRepository.deleteAll();
        customerRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.findAll().stream()
                .filter(u -> !List.of("admin", "cashier").contains(u.getUsername()))
                .forEach(userRepository::delete);
        // The seeded accounts survive, so they start every test as the seeder left them: a test
        // that deactivates one — user administration has rules about the last active
        // administrator — must not leave the next test unable to assign them as a POC.
        userRepository.findAll().forEach(u -> {
            if (!u.isActive()) {
                u.setActive(true);
                userRepository.save(u);
            }
        });
    }

    // ---- fixtures --------------------------------------------------------------

    protected Role role(String name) {
        return roleRepository.findByName(name)
                .orElseThrow(() -> new IllegalStateException("Role not seeded: " + name));
    }

    protected User user(String username, String roleName) {
        return userRepository.save(User.builder()
                .username(username)
                .email(username + "@test.local")
                .fullName(username.toUpperCase())
                .password(passwordEncoder.encode("password"))
                .role(role(roleName))
                .active(true)
                .build());
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
        return customerRepository.save(Customer.builder().name(name).build());
    }

    protected Customer customer(String name, String email) {
        return customerRepository.save(Customer.builder().name(name).email(email).build());
    }

    protected Product product(String name, String price) {
        return productRepository.save(Product.builder()
                .name(name).price(new BigDecimal(price)).active(true).build());
    }

    // ---- authentication --------------------------------------------------------

    /** Puts the given user into the security context for direct service calls. */
    protected void actAs(User u) {
        User fresh = userRepository.findById(u.getId()).orElseThrow();
        AppUserDetails principal = new AppUserDetails(fresh, AppUserDetailsService.buildAuthorities(fresh));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    /** MockMvc post-processor that authenticates the request as the given user. */
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
