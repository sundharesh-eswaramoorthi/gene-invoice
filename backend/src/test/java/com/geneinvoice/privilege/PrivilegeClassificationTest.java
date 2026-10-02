package com.geneinvoice.privilege;

import com.geneinvoice.region.AccessLevel;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every privilege is either global (the role alone decides) or regional with a least grant level. A privilege
 * left out of both would silently be treated as needing MANAGE, so the catalogue is checked whole (§4.9).
 * Each slice that adds a privilege extends this.
 */
class PrivilegeClassificationTest {

    @Test
    void everyPrivilegeIsEitherGlobalOrRegionalAndNeverBoth() {
        for (String name : Privileges.ALL) {
            boolean global = Privileges.GLOBAL.contains(name);
            boolean regional = Privileges.REGION_LEVEL.containsKey(name);
            assertThat(global ^ regional).as(name + " is in exactly one of GLOBAL and REGION_LEVEL").isTrue();
        }
        Set<String> classified = new HashSet<>(Privileges.GLOBAL);
        classified.addAll(Privileges.REGION_LEVEL.keySet());
        assertThat(classified).as("nothing is classified that does not exist").containsExactlyInAnyOrderElementsOf(
                Privileges.ALL);
        assertThat(Privileges.ALL).doesNotHaveDuplicates();
    }

    @Test
    void theCatalogueMatchesTheLevelsTheDesignGivesEachPrivilege() {
        assertThat(Privileges.GLOBAL).containsExactlyInAnyOrder(
                Privileges.USER_VIEW, Privileges.USER_MANAGE, Privileges.ROLE_VIEW, Privileges.ROLE_MANAGE,
                Privileges.PRODUCT_VIEW, Privileges.PRODUCT_MANAGE, Privileges.NOTIFICATION_VIEW,
                Privileges.EXPORT_DATA, Privileges.REGION_VIEW, Privileges.REGION_MANAGE,
                Privileges.AUTOMATION_VIEW, Privileges.AUTOMATION_MANAGE, Privileges.AUTOMATION_ADMIN);
        assertThat(Privileges.REGION_LEVEL.entrySet().stream()
                .filter(e -> e.getValue() == AccessLevel.READ).map(java.util.Map.Entry::getKey))
                .containsExactlyInAnyOrder(
                        Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW, Privileges.PAYMENT_VIEW,
                        Privileges.PROMISE_VIEW, Privileges.DISPUTE_VIEW, Privileges.AUDIT_VIEW, Privileges.POC_VIEW,
                        Privileges.EMAIL_VIEW, Privileges.DOCUMENT_VIEW, Privileges.SCOPE_OVERRIDE,
                        Privileges.POC_ASSIGNABLE_SALES, Privileges.POC_ASSIGNABLE_SUCCESS,
                        Privileges.POC_ASSIGNABLE_COLLECTION, Privileges.TASK_VIEW);
        assertThat(Privileges.REGION_LEVEL.entrySet().stream()
                .filter(e -> e.getValue() == AccessLevel.MANAGE).map(java.util.Map.Entry::getKey))
                .containsExactlyInAnyOrder(
                        Privileges.CUSTOMER_MANAGE, Privileges.INVOICE_MANAGE, Privileges.PAYMENT_MANAGE,
                        Privileges.PROMISE_MANAGE, Privileges.PROMISE_OVERRIDE, Privileges.DISPUTE_MANAGE,
                        Privileges.DISPUTE_CREATE, Privileges.POC_ASSIGN, Privileges.EMAIL_SEND,
                        Privileges.DOCUMENT_MANAGE, Privileges.TASK_MANAGE);
        assertThat(Privileges.REGION_LEVEL.entrySet().stream()
                .filter(e -> e.getValue() == AccessLevel.APPROVE).map(java.util.Map.Entry::getKey))
                .containsExactlyInAnyOrder(Privileges.CHANGE_APPROVE, Privileges.APPROVAL_THRESHOLD_MANAGE);
        assertThat(Privileges.REGION_LEVEL.get(Privileges.CHANGE_APPROVE)).isEqualTo(AccessLevel.APPROVE);
    }

    @Test
    void aPrivilegeNobodyClassifiedNeedsManageSoItFailsSafe() {
        assertThat(Privileges.requiredLevel("UNKNOWN")).isEqualTo(AccessLevel.MANAGE);
        assertThat(Privileges.isRegional("UNKNOWN")).isTrue();
    }

    @Test
    void aGlobalPrivilegeNeedsNoLevelAndARegionalOneItsOwn() {
        assertThat(Privileges.requiredLevel(Privileges.USER_MANAGE)).isNull();
        assertThat(Privileges.isRegional(Privileges.USER_MANAGE)).isFalse();
        assertThat(Privileges.requiredLevel(Privileges.INVOICE_VIEW)).isEqualTo(AccessLevel.READ);
        assertThat(Privileges.requiredLevel(Privileges.INVOICE_MANAGE)).isEqualTo(AccessLevel.MANAGE);
        assertThat(Privileges.isRegional(Privileges.INVOICE_MANAGE)).isTrue();
    }

    @Test
    void accessLevelsAreOrdered() {
        assertThat(AccessLevel.APPROVE.permits(AccessLevel.MANAGE)).isTrue();
        assertThat(AccessLevel.MANAGE.permits(AccessLevel.MANAGE)).isTrue();
        assertThat(AccessLevel.READ.permits(AccessLevel.MANAGE)).isFalse();
        assertThat(AccessLevel.atLeast(AccessLevel.MANAGE)).containsExactly(AccessLevel.MANAGE, AccessLevel.APPROVE);
        assertThat(AccessLevel.atLeast(AccessLevel.READ)).containsExactly(AccessLevel.values());
    }
}
