package com.geneinvoice.config;

import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionProperties;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.region.UserRegionGrantRepository;
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
    private final RegionRepository regionRepository;
    private final UserRegionGrantRepository userRegionGrantRepository;
    private final RegionProperties regionProperties;
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

        Set<Privilege> cashierPrivileges = pickByNames(
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
        );
        // Through keptOrNew, not the list above: a cashier gets these on the one boot that creates
        // them and never again, so an operator who takes TASK_MANAGE away from CASHIER keeps it
        // away. Seeing the queue comes with seeing the record it is waiting on (B2, A6).
        cashierPrivileges.addAll(keptOrNew("CASHIER", createdNow, pickByNames(
                APPROVAL_VIEW,
                TASK_VIEW, TASK_MANAGE,
                AUTOMATION_VIEW)));
        Role cashier = upsertRole("CASHIER", "Day-to-day billing operations", cashierPrivileges);

        Set<Privilege> viewerPrivileges = pickByNames(
                CUSTOMER_VIEW, PRODUCT_VIEW, INVOICE_VIEW, PAYMENT_VIEW,
                NOTIFICATION_VIEW,
                POC_VIEW,
                PROMISE_VIEW,
                SCOPE_OVERRIDE,
                EMAIL_VIEW,
                DOCUMENT_VIEW
        );
        // A reader may see what is pending and what is outstanding, and decide neither (B2, A6).
        viewerPrivileges.addAll(keptOrNew("VIEWER", createdNow, pickByNames(APPROVAL_VIEW, TASK_VIEW)));
        upsertRole("VIEWER", "Read-only access", viewerPrivileges);

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

        // A database born with regions has nothing for RegionSchemaUpgrade to backfill — there is
        // no customer to place and no staff user to staff — so the default region and the two
        // seeded logins' grants are made here instead, where the users have just been created
        // (this runs after every InitializingBean, DataSeeder being a CommandLineRunner) (B1).
        Region defaultRegion = ensureDefaultRegion();
        // The administrator holds the wildcard so opening a branch next year cannot lock the
        // company out of it; everyone else is staffed in the default region only (B1).
        //
        // TWO wildcard rows, not one: the ladder is deliberately not a total order, so APPROVE
        // does not cover MANAGE. An administrator holding APPROVE alone could sign changes off
        // everywhere and raise an invoice nowhere — the authority-drop rule would take every
        // MANAGE-level privilege off them the moment regions arrived (B1, R3 INTEGRATION).
        grantIfUnstaffed("admin", null, RegionRight.MANAGE, RegionRight.APPROVE);
        // Null when this installation has branches of its own and none of them carries the
        // configured code any more. Nobody is staffed into a branch that was picked for them by a
        // stale line of yaml; an unstaffed cashier sees nothing, which is the discoverable answer
        // and the one the migration already gives a staff user it cannot place (B1).
        if (defaultRegion != null) {
            grantIfUnstaffed("cashier", defaultRegion.getId(), RegionRight.MANAGE);
        }
    }

    /**
     * The default region — seeded ONLY when this installation has no region at all, which is the
     * same condition RegionSchemaUpgrade.seedDefaultRegion inserts under.
     *
     * <p>Find-or-create on the CODE would resurrect it. This runs on every boot, so the first
     * restart after an administrator renames the default branch through the shipped
     * PUT /api/regions/{id} would find no row carrying the old code and make a second, empty,
     * active one under it — in GET /api/regions, in every branch picker, and competing to be the
     * branch a new account is filed into. application.yml states the contract in the same words:
     * renaming it later is a data change and not a config change, BECAUSE the row is seeded once
     * by code (B1).
     */
    private Region ensureDefaultRegion() {
        Region existing = regionRepository.findByCode(regionProperties.defaultCode()).orElse(null);
        if (existing != null) return existing;
        if (regionRepository.count() > 0) {
            log.info("No region has the code {}, and this installation already has its own"
                            + " branches, so none is seeded",
                    regionProperties.defaultCode());
            return null;
        }
        log.info("Seeding the default region {} ({})",
                regionProperties.defaultCode(), regionProperties.defaultName());
        return regionRepository.save(Region.builder()
                .code(regionProperties.defaultCode())
                .name(regionProperties.defaultName())
                .active(true)
                .build());
    }

    // "Has no grant at all" rather than "has no grant like this one", the same guard the schema
    // upgrade uses: an operator who narrows admin to two regions tomorrow must not have the
    // wildcard handed back on the next restart (B1).
    private void grantIfUnstaffed(String username, Long regionId, RegionRight... rights) {
        userRepository.findByUsername(username).ifPresent(u -> {
            if (!userRegionGrantRepository.findByUserId(u.getId()).isEmpty()) return;
            for (RegionRight right : rights) {
                userRegionGrantRepository.save(UserRegionGrant.builder()
                        .userId(u.getId()).regionId(regionId).right(right).build());
                log.info("Granted {} {} in {}", username, right,
                        regionId == null ? "every region" : "the default region");
            }
        });
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
