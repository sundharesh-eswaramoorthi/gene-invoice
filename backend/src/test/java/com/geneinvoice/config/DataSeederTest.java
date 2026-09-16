package com.geneinvoice.config;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.role.Role;
import com.geneinvoice.role.RoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** AC-A1: the three POC roles exist after boot, and re-running the seeder neither duplicates nor resets them. */
class DataSeederTest extends IntegrationTestBase {

    @Autowired DataSeeder seeder;
    @Autowired RoleRepository roleRepository;
    @Autowired PrivilegeRepository privilegeRepository;

    private static final List<String> POC_ROLES = List.of(
            DataSeeder.ROLE_SALES_POC, DataSeeder.ROLE_SUCCESS_POC, DataSeeder.ROLE_COLLECTION_POC);

    @Test
    void seedsThreePocRolesWithTheirAssignabilityPrivileges() {
        assertThat(names(DataSeeder.ROLE_SALES_POC)).contains(Privileges.POC_ASSIGNABLE_SALES);
        assertThat(names(DataSeeder.ROLE_SUCCESS_POC)).contains(Privileges.POC_ASSIGNABLE_SUCCESS);
        assertThat(names(DataSeeder.ROLE_COLLECTION_POC)).contains(Privileges.POC_ASSIGNABLE_COLLECTION);
    }

    @Test
    void adminIsAssignableAsAllThreeKinds() {
        assertThat(names("ADMIN")).contains(
                Privileges.POC_ASSIGNABLE_SALES,
                Privileges.POC_ASSIGNABLE_SUCCESS,
                Privileges.POC_ASSIGNABLE_COLLECTION);
    }

    @Test
    void cashierKeepsEverythingItCouldDoBefore() {
        assertThat(names("CASHIER")).contains(
                Privileges.CUSTOMER_VIEW, Privileges.CUSTOMER_MANAGE,
                Privileges.PRODUCT_VIEW,
                Privileges.INVOICE_VIEW, Privileges.INVOICE_MANAGE,
                Privileges.PAYMENT_VIEW, Privileges.PAYMENT_MANAGE,
                Privileges.NOTIFICATION_VIEW);
    }

    @Test
    void aCustomerAccountNeverSeesPocIdentity() {
        assertThat(names("CUSTOMER")).doesNotContain(Privileges.POC_VIEW, Privileges.POC_ASSIGN);
    }

    @Test
    void rerunningTheSeederDoesNotDuplicateRolesOrPrivileges() {
        long rolesBefore = roleRepository.count();
        long privsBefore = privilegeRepository.count();

        seeder.run();
        seeder.run();

        assertThat(roleRepository.count()).isEqualTo(rolesBefore);
        assertThat(privilegeRepository.count()).isEqualTo(privsBefore);
        for (String r : POC_ROLES) {
            assertThat(roleRepository.findByName(r)).isPresent();
        }
    }

    @Test
    void rerunningTheSeederDoesNotResetAnAdminsCustomisationOfAPocRole() {
        Role sales = roleRepository.findByName(DataSeeder.ROLE_SALES_POC).orElseThrow();
        Privilege extra = privilegeRepository.findByName(Privileges.PROMISE_MANAGE).orElseThrow();
        sales.getPrivileges().add(extra);
        roleRepository.save(sales);

        seeder.run();

        assertThat(names(DataSeeder.ROLE_SALES_POC)).contains(Privileges.PROMISE_MANAGE);
    }

    @Test
    void aPrivilegeNewToTheDatabaseReachesThePocRolesSeededWithIt() {
        // Stands in for an upgrade: the database has POC roles but has never had EMAIL_SEND.
        for (Role r : roleRepository.findAll()) {
            if (r.getPrivileges().removeIf(p -> Privileges.EMAIL_SEND.equals(p.getName()))) {
                roleRepository.save(r);
            }
        }
        privilegeRepository.delete(privilegeRepository.findByName(Privileges.EMAIL_SEND).orElseThrow());

        seeder.run();

        for (String r : POC_ROLES) {
            assertThat(names(r)).contains(Privileges.EMAIL_SEND);
        }
        assertThat(names("ADMIN")).contains(Privileges.EMAIL_SEND);
        assertThat(names("VIEWER")).doesNotContain(Privileges.EMAIL_SEND);
        assertThat(names("CUSTOMER")).doesNotContain(Privileges.EMAIL_SEND);
    }

    @Test
    void aPrivilegeAnAdminTookFromAPocRoleStaysTaken() {
        Role success = roleRepository.findByName(DataSeeder.ROLE_SUCCESS_POC).orElseThrow();
        success.getPrivileges().removeIf(p -> Privileges.EMAIL_VIEW.equals(p.getName()));
        roleRepository.save(success);
        try {
            seeder.run();
            assertThat(names(DataSeeder.ROLE_SUCCESS_POC)).doesNotContain(Privileges.EMAIL_VIEW);
        } finally {
            Role restored = roleRepository.findByName(DataSeeder.ROLE_SUCCESS_POC).orElseThrow();
            restored.getPrivileges().add(privilegeRepository.findByName(Privileges.EMAIL_VIEW).orElseThrow());
            roleRepository.save(restored);
        }
    }

    private Set<String> names(String roleName) {
        return roleRepository.findByName(roleName).orElseThrow()
                .getPrivileges().stream().map(Privilege::getName).collect(Collectors.toSet());
    }
}
