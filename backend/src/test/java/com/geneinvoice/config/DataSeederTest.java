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
    void emailPrivilegesAreSeededForTheRolesThatUseEmail() {
        for (String r : List.of("ADMIN", "CASHIER", "CUSTOMER", DataSeeder.ROLE_SALES_POC,
                DataSeeder.ROLE_SUCCESS_POC, DataSeeder.ROLE_COLLECTION_POC)) {
            assertThat(names(r)).as(r).contains(Privileges.EMAIL_VIEW, Privileges.EMAIL_SEND);
        }
        assertThat(names("VIEWER")).contains(Privileges.EMAIL_VIEW).doesNotContain(Privileges.EMAIL_SEND);
    }

    @Test
    void aPocRoleIsGrantedANewPrivilegeOnlyInTheBootThatCreatesIt() {
        List<String> emailPrivileges = List.of(Privileges.EMAIL_VIEW, Privileges.EMAIL_SEND);
        try {
            for (Role role : roleRepository.findAll()) {
                role.getPrivileges().removeIf(p -> emailPrivileges.contains(p.getName()));
                roleRepository.save(role);
            }
            emailPrivileges.forEach(n -> privilegeRepository.delete(privilegeRepository.findByName(n).orElseThrow()));

            seeder.run();

            for (String r : POC_ROLES) {
                assertThat(names(r)).as(r).contains(Privileges.EMAIL_VIEW, Privileges.EMAIL_SEND);
            }

            Role sales = roleRepository.findByName(DataSeeder.ROLE_SALES_POC).orElseThrow();
            sales.getPrivileges().removeIf(p -> p.getName().equals(Privileges.EMAIL_SEND));
            roleRepository.save(sales);

            seeder.run();

            assertThat(names(DataSeeder.ROLE_SALES_POC))
                    .contains(Privileges.EMAIL_VIEW).doesNotContain(Privileges.EMAIL_SEND);
        } finally {
            Role sales = roleRepository.findByName(DataSeeder.ROLE_SALES_POC).orElseThrow();
            privilegeRepository.findByName(Privileges.EMAIL_SEND).ifPresent(sales.getPrivileges()::add);
            roleRepository.save(sales);
            seeder.run();
        }
    }

    @Test
    void aCustomerLoginMaySeeAndAttachDocumentsOnItsOwnRecords() {
        assertThat(names("CUSTOMER")).contains(Privileges.DOCUMENT_VIEW, Privileges.DOCUMENT_MANAGE);
    }

    @Test
    void revokingCustomerUploadsSurvivesARestart() {
        try {
            Role customer = roleRepository.findByName("CUSTOMER").orElseThrow();
            customer.getPrivileges().removeIf(p -> p.getName().equals(Privileges.DOCUMENT_MANAGE));
            roleRepository.save(customer);

            seeder.run();

            assertThat(names("CUSTOMER")).doesNotContain(Privileges.DOCUMENT_MANAGE)
                    .contains(Privileges.DOCUMENT_VIEW);
        } finally {
            Role customer = roleRepository.findByName("CUSTOMER").orElseThrow();
            privilegeRepository.findByName(Privileges.DOCUMENT_MANAGE)
                    .ifPresent(customer.getPrivileges()::add);
            roleRepository.save(customer);
            seeder.run();
        }
    }

    private Set<String> names(String roleName) {
        return roleRepository.findByName(roleName).orElseThrow()
                .getPrivileges().stream().map(Privilege::getName).collect(Collectors.toSet());
    }
}
