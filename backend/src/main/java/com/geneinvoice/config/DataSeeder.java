package com.geneinvoice.config;

import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.role.Role;
import com.geneinvoice.role.RoleRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static com.geneinvoice.privilege.Privileges.*;

/**
 * Idempotent seeding of privileges, roles and the bootstrap accounts.
 *
 * <p>The four built-in roles are kept in step with the code on every boot, so a new privilege
 * added here reaches them on upgrade. The three POC roles are created once and then left alone —
 * an admin who tailors them keeps their edits across restarts (AC-A1) — except that a privilege
 * this database has never had before is added to the POC roles seeded with it. No admin can have
 * taken away a privilege that did not exist yet, so that reaches them without undoing an edit.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    public static final String ROLE_SALES_POC = "SALES_POC";
    public static final String ROLE_SUCCESS_POC = "CUSTOMER_SUCCESS_POC";
    public static final String ROLE_COLLECTION_POC = "COLLECTION_POC";

    private final PrivilegeRepository privilegeRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        int blankEmails = userRepository.clearBlankEmails();
        if (blankEmails > 0) log.info("Cleared {} blank user email(s) to null", blankEmails);

        Set<Privilege> all = new HashSet<>();
        Set<String> created = new HashSet<>();
        for (String name : Privileges.ALL) {
            Privilege p = privilegeRepository.findByName(name).orElseGet(() -> {
                created.add(name);
                return privilegeRepository.save(Privilege.builder().name(name).description(name).build());
            });
            all.add(p);
        }

        Role admin = upsertRole("ADMIN", "Full system access", all);

        // CASHIER keeps everything it could do before, plus the POC and promise reads that the
        // billing screens now need.
        Role cashier = upsertRole("CASHIER", "Day-to-day billing operations", pickByNames(
                CUSTOMER_VIEW, CUSTOMER_MANAGE,
                PRODUCT_VIEW,
                INVOICE_VIEW, INVOICE_MANAGE,
                PAYMENT_VIEW, PAYMENT_MANAGE,
                NOTIFICATION_VIEW,
                POC_VIEW, POC_ASSIGN,
                PROMISE_VIEW,
                SCOPE_OVERRIDE,
                EXPORT_DATA,
                EMAIL_VIEW, EMAIL_SEND
        ));
        upsertRole("VIEWER", "Read-only access", pickByNames(
                CUSTOMER_VIEW, PRODUCT_VIEW, INVOICE_VIEW, PAYMENT_VIEW,
                NOTIFICATION_VIEW,
                POC_VIEW,
                PROMISE_VIEW,
                SCOPE_OVERRIDE,
                EMAIL_VIEW
        ));
        // A customer account never receives POC_VIEW: POC identity is invisible to them (AC-A8).
        // Nor any EMAIL_* privilege: emails are staff correspondence about the account.
        upsertRole("CUSTOMER", "Customer self-service", pickByNames(
                CUSTOMER_VIEW,
                INVOICE_VIEW,
                PAYMENT_VIEW,
                DISPUTE_CREATE, DISPUTE_VIEW,
                NOTIFICATION_VIEW,
                AUDIT_VIEW,
                PROMISE_VIEW
        ));

        // A Sales POC deliberately lacks SCOPE_OVERRIDE: their book filter is shown locked.
        createRoleIfAbsent(ROLE_SALES_POC, "Salesperson who owns invoices", created, pickByNames(
                CUSTOMER_VIEW,
                PRODUCT_VIEW,
                INVOICE_VIEW, INVOICE_MANAGE,
                PAYMENT_VIEW,
                DISPUTE_VIEW,
                NOTIFICATION_VIEW,
                AUDIT_VIEW,
                POC_VIEW, POC_ASSIGN,
                POC_ASSIGNABLE_SALES,
                PROMISE_VIEW,
                EXPORT_DATA,
                EMAIL_VIEW, EMAIL_SEND
        ));
        createRoleIfAbsent(ROLE_SUCCESS_POC, "Customer success contact for an account", created, pickByNames(
                CUSTOMER_VIEW, CUSTOMER_MANAGE,
                PRODUCT_VIEW,
                INVOICE_VIEW,
                PAYMENT_VIEW,
                DISPUTE_VIEW,
                NOTIFICATION_VIEW,
                AUDIT_VIEW,
                POC_VIEW, POC_ASSIGN,
                POC_ASSIGNABLE_SUCCESS,
                PROMISE_VIEW,
                SCOPE_OVERRIDE,
                EXPORT_DATA,
                EMAIL_VIEW, EMAIL_SEND
        ));
        createRoleIfAbsent(ROLE_COLLECTION_POC, "Collections contact for an account", created, pickByNames(
                CUSTOMER_VIEW,
                INVOICE_VIEW,
                PAYMENT_VIEW, PAYMENT_MANAGE,
                DISPUTE_VIEW,
                NOTIFICATION_VIEW,
                AUDIT_VIEW,
                POC_VIEW, POC_ASSIGN,
                POC_ASSIGNABLE_COLLECTION,
                PROMISE_VIEW, PROMISE_MANAGE, PROMISE_OVERRIDE,
                SCOPE_OVERRIDE,
                EXPORT_DATA,
                EMAIL_VIEW, EMAIL_SEND
        ));

        if (!userRepository.existsByUsername("admin")) {
            User u = User.builder()
                    .username("admin")
                    .email("admin@geneinvoice.local")
                    .fullName("System Administrator")
                    .password(passwordEncoder.encode("admin123"))
                    .role(admin)
                    .active(true)
                    .build();
            userRepository.save(u);
            log.info("Seeded admin user: admin / admin123");
        }

        if (!userRepository.existsByUsername("cashier")) {
            User u = User.builder()
                    .username("cashier")
                    .email("cashier@geneinvoice.local")
                    .fullName("Default Cashier")
                    .password(passwordEncoder.encode("cashier123"))
                    .role(cashier)
                    .active(true)
                    .build();
            userRepository.save(u);
            log.info("Seeded cashier user: cashier / cashier123");
        }
    }

    /** Built-in role: kept in step with the code on every boot. */
    private Role upsertRole(String name, String description, Set<Privilege> privs) {
        Role role = roleRepository.findByName(name).orElseGet(() ->
                Role.builder().name(name).description(description).privileges(new HashSet<>()).build());
        role.setDescription(description);
        role.setPrivileges(privs);
        return roleRepository.save(role);
    }

    /**
     * POC role: created once, then never reset, so admin customisations survive an upgrade. Only a
     * privilege created by this run — one the database did not have, so no admin can have removed
     * it — is added to a role that already exists.
     */
    private Role createRoleIfAbsent(String name, String description, Set<String> createdPrivileges,
                                    Set<Privilege> privs) {
        return roleRepository.findByName(name).map(role -> {
            Set<Privilege> arriving = privs.stream()
                    .filter(p -> createdPrivileges.contains(p.getName()))
                    .collect(Collectors.toSet());
            if (arriving.isEmpty()) return role;
            log.info("Adding new privilege(s) {} to POC role {}",
                    arriving.stream().map(Privilege::getName).sorted().toList(), name);
            role.getPrivileges().addAll(arriving);
            return roleRepository.save(role);
        }).orElseGet(() -> {
            log.info("Seeding POC role {}", name);
            return roleRepository.save(Role.builder()
                    .name(name).description(description).privileges(privs).build());
        });
    }

    private Set<Privilege> pickByNames(String... names) {
        Set<Privilege> set = new HashSet<>();
        for (String n : names) {
            privilegeRepository.findByName(n).ifPresent(set::add);
        }
        return set;
    }
}
