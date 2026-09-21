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
                DOCUMENT_VIEW
        );
        customerPrivileges.addAll(keptOrNew("CUSTOMER", createdNow, pickByNames(DOCUMENT_MANAGE)));
        upsertRole("CUSTOMER", "Customer self-service", customerPrivileges);

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

    private Role upsertRole(String name, String description, Set<Privilege> privs) {
        Role role = roleRepository.findByName(name).orElseGet(() ->
                Role.builder().name(name).description(description).privileges(new HashSet<>()).build());
        role.setDescription(description);
        role.setPrivileges(privs);
        return roleRepository.save(role);
    }

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
