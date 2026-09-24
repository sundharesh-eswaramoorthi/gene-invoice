package com.geneinvoice.region;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The region package's value types, before anything is wired to them. The ladder and the
 * privilege partition are the two things every later region unit reads, so they are pinned here
 * against the real schema rather than trusted (B1).
 */
class RegionRightLadderTest extends IntegrationTestBase {

    @Autowired RegionRepository regionRepository;
    @Autowired UserRegionGrantRepository grantRepository;
    @Autowired CustomerRegionHistoryRepository historyRepository;

    @BeforeEach
    void clearRegionTables() {
        grantRepository.deleteAll();
        historyRepository.deleteAll();
        regionRepository.deleteAll();
    }

    @Test
    void manageDoesNotCoverApproveAndApproveDoesNotCoverManage() {
        assertThat(RegionRight.MANAGE.covers(RegionRight.APPROVE)).isFalse();
        assertThat(RegionRight.APPROVE.covers(RegionRight.MANAGE)).isFalse();
    }

    @Test
    void everyRightCoversViewAndItselfAndNothingElse() {
        for (RegionRight r : RegionRight.values()) {
            assertThat(r.covers(RegionRight.VIEW)).isTrue();
            assertThat(r.covers(r)).isTrue();
        }
        assertThat(RegionRight.VIEW.covers(RegionRight.MANAGE)).isFalse();
        assertThat(RegionRight.VIEW.covers(RegionRight.APPROVE)).isFalse();
    }

    @Test
    void aPrivilegeInNeitherHalfOfThePartitionRefusesToGuessInsteadOfDefaultingToAnywhere() {
        assertThatThrownBy(() -> RegionRights.needed("INVENTED_NEXT_YEAR"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INVENTED_NEXT_YEAR")
                .hasMessageContaining("RegionRights.LEVEL");
        assertThatThrownBy(() -> RegionRights.needed(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyPrivilegeInTheCatalogueIsClassifiedAndTheRegionGatedOnesAreTheBusinessRecords() {
        for (String p : Privileges.ALL) {
            RegionRights.needed(p);
        }
        assertThat(RegionRights.needed(Privileges.INVOICE_VIEW)).isEqualTo(RegionRight.VIEW);
        assertThat(RegionRights.needed(Privileges.INVOICE_MANAGE)).isEqualTo(RegionRight.MANAGE);
        assertThat(RegionRights.needed(Privileges.APPROVAL_APPROVE)).isEqualTo(RegionRight.APPROVE);
        assertThat(RegionRights.needed(Privileges.PROMISE_OVERRIDE)).isEqualTo(RegionRight.MANAGE);
        // SCOPE_OVERRIDE turns the POC book off and never widens the region set (B1).
        assertThat(RegionRights.needed(Privileges.SCOPE_OVERRIDE)).isNull();
        assertThat(RegionRights.needed(Privileges.USER_MANAGE)).isNull();
        assertThat(RegionRights.needed(Privileges.APPROVAL_APPROVE_ANY)).isNull();
        assertThat(RegionRights.needed(Privileges.AUTOMATION_MANAGE)).isNull();
    }

    @Test
    void parsingARightIgnoresCaseAndRejectsAnythingElse() {
        assertThat(RegionRight.parse(" approve ")).isEqualTo(RegionRight.APPROVE);
        assertThat(RegionRight.parse("Manage")).isEqualTo(RegionRight.MANAGE);
        assertThatThrownBy(() -> RegionRight.parse("OWNER")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RegionRight.parse(null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void aGrantWithNoRegionIsEveryRegionAndANamedGrantIsOnlyThatOne() {
        RegionGrants g = RegionGrants.of(List.of(
                UserRegionGrant.builder().userId(1L).regionId(3L).right(RegionRight.MANAGE).build(),
                UserRegionGrant.builder().userId(1L).regionId(7L).right(RegionRight.VIEW).build()));

        assertThat(g.may(3L, RegionRight.MANAGE)).isTrue();
        assertThat(g.may(3L, RegionRight.VIEW)).isTrue();
        assertThat(g.may(3L, RegionRight.APPROVE)).isFalse();
        assertThat(g.may(7L, RegionRight.MANAGE)).isFalse();
        assertThat(g.may(99L, RegionRight.VIEW)).isFalse();
        assertThat(g.may(null, RegionRight.VIEW)).isFalse();
        assertThat(g.allRegions(RegionRight.VIEW)).isFalse();
        assertThat(g.holdsAnywhere(RegionRight.MANAGE)).isTrue();
        assertThat(g.holdsAnywhere(RegionRight.APPROVE)).isFalse();
        assertThat(g.with(RegionRight.VIEW)).containsExactlyInAnyOrder(3L, 7L);
        assertThat(g.with(RegionRight.MANAGE)).containsExactly(3L);

        RegionGrants wildcard = RegionGrants.of(List.of(
                UserRegionGrant.builder().userId(2L).right(RegionRight.APPROVE).build()));
        assertThat(wildcard.allRegions(RegionRight.VIEW)).isTrue();
        assertThat(wildcard.allRegions(RegionRight.MANAGE)).isFalse();
        assertThat(wildcard.may(12345L, RegionRight.APPROVE)).isTrue();
    }

    @Test
    void aWildcardHolderGetsAnEmptyIdListSoNobodyEverBuildsAnInWithNoValues() {
        RegionGrants wildcard = RegionGrants.of(List.of(
                UserRegionGrant.builder().userId(2L).right(RegionRight.VIEW).build()));
        assertThat(wildcard.allRegions(RegionRight.VIEW)).isTrue();
        assertThat(wildcard.with(RegionRight.VIEW)).isEmpty();

        RegionGrants nobody = RegionGrants.of(List.of());
        assertThat(nobody.isEmpty()).isTrue();
        assertThat(nobody.allRegions(RegionRight.VIEW)).isFalse();
        assertThat(nobody.with(RegionRight.VIEW)).isEmpty();
        assertThat(RegionGrants.none()).isEqualTo(nobody);
    }

    @Test
    void twoRegionsCannotShareACodeBecauseTheCodeIsWhatEveryGrantAndImportNamesThemBy() {
        regionRepository.save(Region.builder().code("NORTH").name("North Branch").build());
        assertThatThrownBy(() -> regionRepository
                .saveAndFlush(Region.builder().code("NORTH").name("North Again").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theThreeRegionTablesPersistAndReadBackAgainstTheRealSchema() {
        Region north = regionRepository.save(Region.builder().code("NORTH").name("North Branch").build());
        Region west = regionRepository.save(Region.builder().code("WEST").name("West Branch").build());

        assertThat(north.getId()).isNotNull();
        assertThat(north.isActive()).isTrue();
        assertThat(north.getCreatedAt()).isNotNull();
        assertThat(regionRepository.findByCode("NORTH")).map(Region::getId).contains(north.getId());
        assertThat(regionRepository.codeOf(west.getId())).isEqualTo("WEST");

        User rita = user("rita.region", DataSeeder.ROLE_SALES_POC);
        // The fixture staffs whoever it creates; this test asserts on an exact set of grants.
        revokeRegionGrants(rita);
        grantRepository.save(UserRegionGrant.builder()
                .userId(rita.getId()).regionId(north.getId()).right(RegionRight.MANAGE).build());

        assertThat(grantRepository.covers(rita.getId(), north.getId(), RegionRight.MANAGE)).isTrue();
        assertThat(grantRepository.covers(rita.getId(), north.getId(), RegionRight.VIEW)).isTrue();
        // MANAGE is not APPROVE in the query either, or four-eyes would be satisfiable by one person.
        assertThat(grantRepository.covers(rita.getId(), north.getId(), RegionRight.APPROVE)).isFalse();
        assertThat(grantRepository.covers(rita.getId(), west.getId(), RegionRight.VIEW)).isFalse();

        grantRepository.save(UserRegionGrant.builder()
                .userId(rita.getId()).right(RegionRight.APPROVE).build());
        // The wildcard row reaches a region nobody named on it, including WEST.
        assertThat(grantRepository.covers(rita.getId(), west.getId(), RegionRight.APPROVE)).isTrue();
        assertThat(grantRepository.covers(rita.getId(), west.getId(), RegionRight.MANAGE)).isFalse();

        assertThat(RegionGrants.of(grantRepository.findByUserId(rita.getId())))
                .isEqualTo(new RegionGrants(
                        java.util.Map.of(north.getId(), Set.of(RegionRight.MANAGE)),
                        Set.of(RegionRight.APPROVE)));

        Long customerId = customer("Acme Ltd").getId();
        historyRepository.save(CustomerRegionHistory.builder()
                .customerId(customerId).regionId(north.getId())
                .validFrom(LocalDate.of(2024, 1, 1)).validTo(LocalDate.of(2026, 3, 1))
                .reason("opened in the north").build());
        historyRepository.save(CustomerRegionHistory.builder()
                .customerId(customerId).regionId(west.getId())
                .validFrom(LocalDate.of(2026, 3, 1)).build());

        assertThat(historyRepository.findOpen(customerId))
                .map(CustomerRegionHistory::getRegionId).contains(west.getId());
        assertThat(historyRepository.findByCustomerIdOrderByValidFromAsc(customerId))
                .extracting(CustomerRegionHistory::getRegionId)
                .containsExactly(north.getId(), west.getId());
        // A headless move leaves no actor behind, which is why moved_by_user_id is nullable (B1).
        assertThat(historyRepository.findOpen(customerId).orElseThrow().getMovedByUserId()).isNull();
    }
}
