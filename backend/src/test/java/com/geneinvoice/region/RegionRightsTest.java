package com.geneinvoice.region;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.geneinvoice.privilege.Privileges.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The effective-rights formula (§4.2), on plain data: a role, and a grant per region. */
class RegionRightsTest {

    static final Long NORTH = 1L;
    static final Long SOUTH = 2L;
    static final Long WEST = 3L;

    static final Set<String> CASHIER = Set.of(CUSTOMER_VIEW, CUSTOMER_MANAGE, INVOICE_VIEW, INVOICE_MANAGE,
            PAYMENT_VIEW, PAYMENT_MANAGE, SCOPE_OVERRIDE, POC_ASSIGN, PRODUCT_VIEW, EXPORT_DATA);

    /** Rita: NORTH READ/ALL, SOUTH MANAGE/ALL, nothing in WEST. */
    static RegionRights rita() {
        return RegionRights.forStaff(CASHIER, List.of(
                new GrantRow(NORTH, AccessLevel.READ, RecordScope.ALL),
                new GrantRow(SOUTH, AccessLevel.MANAGE, RecordScope.ALL)));
    }

    @Test
    void aReadGrantAllowsTheRolesReadPrivilegesThereAndNothingMore() {
        RegionRights r = rita();

        assertThat(r.has(INVOICE_VIEW, NORTH)).isTrue();
        assertThat(r.has(INVOICE_MANAGE, NORTH)).isFalse();
        assertThat(r.has(POC_ASSIGN, NORTH)).as("POC_ASSIGN needs MANAGE").isFalse();
    }

    @Test
    void aManageGrantAllowsTheRolesManagePrivilegesThere() {
        RegionRights r = rita();

        assertThat(r.has(INVOICE_VIEW, SOUTH)).isTrue();
        assertThat(r.has(INVOICE_MANAGE, SOUTH)).isTrue();
        assertThat(r.has(POC_ASSIGN, SOUTH)).isTrue();
    }

    @Test
    void anApproveGrantIncludesManageAndRead() {
        RegionRights r = RegionRights.forStaff(CASHIER, List.of(new GrantRow(NORTH, AccessLevel.APPROVE, RecordScope.ALL)));

        assertThat(r.has(INVOICE_VIEW, NORTH)).isTrue();
        assertThat(r.has(INVOICE_MANAGE, NORTH)).isTrue();
    }

    @Test
    void noGrantMeansNothingInThatRegion() {
        RegionRights r = rita();

        assertThat(r.has(INVOICE_VIEW, WEST)).isFalse();
        assertThat(r.holdsAnyIn(WEST)).isFalse();
        assertThat(r.grantIn(WEST)).isEmpty();
        assertThat(r.holdsAnyIn(NORTH)).isTrue();
        assertThat(r.grantIn(SOUTH)).contains(new GrantRow(SOUTH, AccessLevel.MANAGE, RecordScope.ALL));
    }

    @Test
    void aGrantNeverAddsAPrivilegeTheRoleLacks() {
        RegionRights r = RegionRights.forStaff(Set.of(INVOICE_VIEW),
                List.of(new GrantRow(NORTH, AccessLevel.APPROVE, RecordScope.ALL)));

        assertThat(r.has(INVOICE_MANAGE, NORTH)).isFalse();
        assertThat(r.anywhere(INVOICE_MANAGE)).isFalse();
    }

    @Test
    void scopeOverrideNeedsAGrantThatReachesAllRecords() {
        RegionRights r = RegionRights.forStaff(CASHIER, List.of(
                new GrantRow(NORTH, AccessLevel.APPROVE, RecordScope.BOOK),
                new GrantRow(SOUTH, AccessLevel.READ, RecordScope.ALL)));

        assertThat(r.has(SCOPE_OVERRIDE, NORTH)).as("a BOOK grant never overrides the book").isFalse();
        assertThat(r.has(INVOICE_MANAGE, NORTH)).as("the scope limits only SCOPE_OVERRIDE").isTrue();
        assertThat(r.has(SCOPE_OVERRIDE, SOUTH)).as("READ is enough, with ALL").isTrue();
        assertThat(r.regionsWith(SCOPE_OVERRIDE)).containsExactly(SOUTH);
    }

    @Test
    void aGlobalPrivilegeIsTheRolesAloneWhateverTheRegion() {
        RegionRights r = rita();

        assertThat(r.has(PRODUCT_VIEW, WEST)).isTrue();
        assertThat(r.has(PRODUCT_VIEW, null)).isTrue();
        assertThat(r.anywhere(EXPORT_DATA)).isTrue();
        assertThat(r.anywhere(USER_MANAGE)).as("not in the role").isFalse();
        assertThatThrownBy(() -> r.regionsWith(PRODUCT_VIEW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingRegionAnswersFalseForARegionalPrivilege() {
        RegionRights r = rita();

        assertThat(r.has(INVOICE_VIEW, null)).isFalse();
        assertThat(r.holdsAnyIn(null)).isFalse();
        assertThat(r.grantIn(null)).isEmpty();
    }

    @Test
    void aPrivilegeNobodyClassifiedNeedsManage() {
        RegionRights r = RegionRights.forStaff(Set.of("CUSTOM_THING"), List.of(
                new GrantRow(NORTH, AccessLevel.READ, RecordScope.ALL),
                new GrantRow(SOUTH, AccessLevel.MANAGE, RecordScope.ALL)));

        assertThat(r.regionsWith("CUSTOM_THING")).containsExactly(SOUTH);
    }

    @Test
    void regionsWithListsWhereAPrivilegeIsEffective() {
        RegionRights r = rita();

        assertThat(r.regionsWith(INVOICE_VIEW)).containsExactly(NORTH, SOUTH);
        assertThat(r.regionsWith(INVOICE_MANAGE)).containsExactly(SOUTH);
        assertThat(r.grantedRegions()).containsExactly(NORTH, SOUTH);
    }

    @Test
    void theUnionIsWhatHoldsSomewhereAndEachRegionListsItsOwn() {
        RegionRights r = rita();

        assertThat(r.effectiveAnywhere()).containsExactlyInAnyOrderElementsOf(CASHIER);
        assertThat(r.effectiveIn(NORTH)).containsExactlyInAnyOrder(CUSTOMER_VIEW, INVOICE_VIEW, PAYMENT_VIEW,
                SCOPE_OVERRIDE);
        assertThat(r.effectiveIn(SOUTH)).containsExactlyInAnyOrder(CUSTOMER_VIEW, CUSTOMER_MANAGE, INVOICE_VIEW,
                INVOICE_MANAGE, PAYMENT_VIEW, PAYMENT_MANAGE, SCOPE_OVERRIDE, POC_ASSIGN);
        assertThat(r.effectiveIn(WEST)).isEmpty();

        RegionRights readOnly = RegionRights.forStaff(CASHIER, List.of(new GrantRow(NORTH, AccessLevel.READ, RecordScope.BOOK)));
        assertThat(readOnly.effectiveAnywhere())
                .as("manage privileges held nowhere drop out of the union, so @PreAuthorize refuses them")
                .containsExactlyInAnyOrder(CUSTOMER_VIEW, INVOICE_VIEW, PAYMENT_VIEW, PRODUCT_VIEW, EXPORT_DATA);
    }

    @Test
    void aCustomerLoginHoldsItsRoleInItsOwnCustomersRegionOnly() {
        RegionRights r = RegionRights.forCustomer(Set.of(INVOICE_VIEW, DISPUTE_CREATE, NOTIFICATION_VIEW), SOUTH);

        assertThat(r.isCustomer()).isTrue();
        assertThat(r.has(INVOICE_VIEW, SOUTH)).isTrue();
        assertThat(r.has(DISPUTE_CREATE, SOUTH)).as("no grant level applies to a customer").isTrue();
        assertThat(r.has(INVOICE_VIEW, NORTH)).isFalse();
        assertThat(r.grantedRegions()).containsExactly(SOUTH);
        assertThat(r.grantIn(SOUTH)).isEmpty();
        assertThat(r.effectiveAnywhere()).containsExactlyInAnyOrder(INVOICE_VIEW, DISPUTE_CREATE, NOTIFICATION_VIEW);

        RegionRights orphan = RegionRights.forCustomer(Set.of(INVOICE_VIEW, NOTIFICATION_VIEW), null);
        assertThat(orphan.grantedRegions()).isEmpty();
        assertThat(orphan.effectiveAnywhere()).containsExactly(NOTIFICATION_VIEW);
    }

    @Test
    void noneHoldsNothingAnywhere() {
        RegionRights r = RegionRights.none();

        assertThat(r.isCustomer()).isFalse();
        assertThat(r.has(INVOICE_VIEW, NORTH)).isFalse();
        assertThat(r.has(PRODUCT_VIEW, NORTH)).isFalse();
        assertThat(r.anywhere(PRODUCT_VIEW)).isFalse();
        assertThat(r.grantedRegions()).isEmpty();
        assertThat(r.effectiveAnywhere()).isEmpty();
    }
}
