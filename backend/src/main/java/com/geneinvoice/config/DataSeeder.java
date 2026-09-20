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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.geneinvoice.privilege.Privileges.*;

/**
 * Idempotent seeding of privileges, roles and the bootstrap accounts.
 *
 * <p>The four built-in roles are kept in step with the code on every boot, so a new privilege
 * added here reaches them on upgrade. The three POC roles are created once and then left alone —
 * an admin who tailors them keeps their edits across restarts (AC-A1). The one exception is a
 * privilege that did not exist before: in the boot that first creates it, each POC role whose
 * default set includes it is granted it once, and later edits stand (E14). {@code DOCUMENT_MANAGE}
 * on the CUSTOMER role is seeded that way too, so customer uploads can be turned off for good
 * (§4.5).
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
        Set<String> createdNow = new HashSet<>();
        for (String name : Privileges.ALL) {
            Privilege p = privilegeRepository.findByName(name).orElseGet(() -> {
                createdNow.add(name);
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
                EMAIL_VIEW, EMAIL_SEND,
                DOCUMENT_VIEW, DOCUMENT_MANAGE,
                EXPORT_DATA
        ));
        upsertRole("VIEWER", "Read-only access", pickByNames(
                CUSTOMER_VIEW, PRODUCT_VIEW, INVOICE_VIEW, PAYMENT_VIEW,
                NOTIFICATION_VIEW,
                POC_VIEW,
                PROMISE_VIEW,
                SCOPE_OVERRIDE,
                EMAIL_VIEW,
                DOCUMENT_VIEW
        ));
        // A customer account never receives POC_VIEW: POC identity is invisible to them (AC-A8).
        Set<Privilege> customerPrivileges = pickByNames(
                CUSTOMER_VIEW,
                INVOICE_VIEW,
                PAYMENT_VIEW,
                DISPUTE_CREATE, DISPUTE_VIEW,
                NOTIFICATION_VIEW,
                AUDIT_VIEW,
                PROMISE_VIEW,
                // Customer logins take part in email, from themselves only (E13).
                EMAIL_VIEW, EMAIL_SEND,
                // And see what is shared with them on their own records (§4.5).
                DOCUMENT_VIEW
        );
        // Letting an untrusted outside account attach files is a capability an operator may want
        // back: DOCUMENT_MANAGE is granted to CUSTOMER on the boot that creates the privilege and
        // never forced on it again, so revoking it on the roles screen survives a restart (§4.5).
        customerPrivileges.addAll(keptOrNew("CUSTOMER", createdNow, pickByNames(DOCUMENT_MANAGE)));
        upsertRole("CUSTOMER", "Customer self-service", customerPrivileges);

        // A Sales POC deliberately lacks SCOPE_OVERRIDE: their book filter is shown locked.
        createRoleIfAbsent(ROLE_SALES_POC, "Salesperson who owns invoices", createdNow, pickByNames(
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
                EMAIL_VIEW, EMAIL_SEND,
                DOCUMENT_VIEW, DOCUMENT_MANAGE,
                EXPORT_DATA
        ));
        createRoleIfAbsent(ROLE_SUCCESS_POC, "Customer success contact for an account", createdNow, pickByNames(
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
                EMAIL_VIEW, EMAIL_SEND,
                DOCUMENT_VIEW, DOCUMENT_MANAGE,
                EXPORT_DATA
        ));
        createRoleIfAbsent(ROLE_COLLECTION_POC, "Collections contact for an account", createdNow, pickByNames(
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
                EMAIL_VIEW, EMAIL_SEND,
                DOCUMENT_VIEW, DOCUMENT_MANAGE,
                EXPORT_DATA
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
     * POC role: created once, then never reset, so admin customisations survive an upgrade. A
     * privilege from its default set that this boot created did not exist for an admin to have
     * withheld, so the existing role gets it now — and only now.
     */
    private Role createRoleIfAbsent(String name, String description, Set<String> privilegesCreatedNow,
                                    Set<Privilege> privs) {
        Role existing = roleRepository.findByName(name).orElse(null);
        if (existing == null) {
            log.info("Seeding POC role {}", name);
            return roleRepository.save(Role.builder()
                    .name(name).description(description).privileges(privs).build());
        }
        List<Privilege> added = privs.stream()
                .filter(p -> privilegesCreatedNow.contains(p.getName()))
                .filter(p -> existing.getPrivileges().stream().noneMatch(held -> held.getName().equals(p.getName())))
                .toList();
        if (added.isEmpty()) return existing;
        log.info("Granting POC role {} its new default privilege(s) {}", name,
                added.stream().map(Privilege::getName).toList());
        existing.getPrivileges().addAll(added);
        return roleRepository.save(existing);
    }

    /**
     * The privileges of {@code privs} a built-in role should still hold: the ones this boot
     * created, which nobody can have withheld yet, and the ones the role already has. A privilege
     * an administrator has taken away is not in either, so the next restart leaves it taken away —
     * the same rule {@link #createRoleIfAbsent} applies to a whole POC role.
     */
    private Set<Privilege> keptOrNew(String roleName, Set<String> privilegesCreatedNow,
                                     Set<Privilege> privs) {
        Set<Privilege> held = roleRepository.findByName(roleName)
                .map(Role::getPrivileges)
                .orElse(Set.of());
        return privs.stream()
                .filter(p -> privilegesCreatedNow.contains(p.getName())
                        || held.stream().anyMatch(h -> h.getName().equals(p.getName())))
                .collect(Collectors.toCollection(HashSet::new));
    }

    private Set<Privilege> pickByNames(String... names) {
        Set<Privilege> set = new HashSet<>();
        for (String n : names) {
            privilegeRepository.findByName(n).ifPresent(set::add);
        }
        return set;
    }
}
