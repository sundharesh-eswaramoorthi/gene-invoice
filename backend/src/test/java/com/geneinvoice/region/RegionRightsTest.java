package com.geneinvoice.region;

import com.geneinvoice.CountingStatements;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.auth.AppUserDetailsService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.auth.dto.LoginRequest;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rule every other region unit is built on: a global privilege says WHAT and a region right
 * says WHERE, and a privilege exercisable in no region is dropped from the authority set — so all
 * 109 @PreAuthorize annotations keep their exact text and now read as "somewhere" (B1).
 *
 * <p>The drop rule has to be provably right before anything depends on it, which is why this test
 * lands with the rule rather than after the endpoints that will lean on it.
 */
class RegionRightsTest extends IntegrationTestBase {

    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired RegionAccess regionAccess;
    @Autowired CurrentUser currentUser;

    User admin;
    Region north;
    Region west;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        north = region("NORTH");
        west = region("WEST");
    }

    @Test
    void aPrivilegeExercisableInNoRegionIsNotInTheAuthoritySet() throws Exception {
        User cara = staffedOnlyIn("cara.cashier", "CASHIER", north.getId(), RegionRight.VIEW);

        // She may read her branch's accounts and may change none of them, anywhere.
        assertThat(authoritiesOf(cara))
                .contains(Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW, Privileges.PAYMENT_VIEW)
                .doesNotContain(Privileges.CUSTOMER_MANAGE, Privileges.INVOICE_MANAGE,
                        Privileges.PAYMENT_MANAGE, Privileges.EMAIL_SEND, Privileges.DOCUMENT_MANAGE);

        // And the annotation that refuses her is the one that was already there, unedited: this is
        // the whole trick, because editing 109 of them would have been 109 chances to get it wrong.
        mockMvc.perform(get("/api/customers").with(as(cara)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CustomerDtos.CustomerCreateRequest("Nova Ltd", null, null,
                                null, null, null, "nova.login", "Password1!")))
                        .with(as(cara)))
                .andExpect(status().isForbidden());

        // A company-wide privilege is never gated by a region, however little she holds.
        assertThat(authoritiesOf(cara)).contains(Privileges.SCOPE_OVERRIDE, Privileges.PRODUCT_VIEW);
    }

    @Test
    void aCallerWithNoGrantAtAllKeepsItsViewPrivileges() throws Exception {
        User nina = user("nina.nowhere", "CASHIER");
        revokeRegionGrants(nina);

        // Not a 403 and not a locked-out account: somebody nobody has staffed yet still signs in,
        // still reaches every list they could read, and meets an empty one. The list only becomes
        // empty when R4 injects the region predicate; today it is the 200 that matters (B1).
        assertThat(authoritiesOf(nina))
                .contains(Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW, Privileges.PROMISE_VIEW,
                        Privileges.EXPORT_DATA, Privileges.NOTIFICATION_VIEW)
                .doesNotContain(Privileges.CUSTOMER_MANAGE, Privileges.INVOICE_MANAGE,
                        Privileges.PAYMENT_MANAGE, Privileges.POC_ASSIGN);

        mockMvc.perform(get("/api/customers").with(as(nina)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/invoices").with(as(nina)))
                .andExpect(status().isOk());
    }

    @Test
    void approveIsItsOwnRightAndManageDoesNotImplyIt() {
        Role role = roleWith("BRANCH_APPROVER", Privileges.INVOICE_VIEW, Privileges.INVOICE_MANAGE,
                Privileges.APPROVAL_VIEW, Privileges.APPROVAL_APPROVE);
        User maker = staffedOnlyIn("mia.maker", role.getName(), north.getId(), RegionRight.MANAGE);
        User checker = staffedOnlyIn("chad.checker", role.getName(), north.getId(), RegionRight.APPROVE);

        // Four-eyes is not satisfiable by one grant: the person who may make the change does not
        // thereby become the person who may sign it off, and vice versa (B1, B2).
        assertThat(authoritiesOf(maker))
                .contains(Privileges.INVOICE_MANAGE, Privileges.APPROVAL_VIEW)
                .doesNotContain(Privileges.APPROVAL_APPROVE);
        assertThat(authoritiesOf(checker))
                .contains(Privileges.APPROVAL_APPROVE, Privileges.APPROVAL_VIEW)
                .doesNotContain(Privileges.INVOICE_MANAGE);

        actAs(maker);
        assertThat(currentUser.has(Privileges.APPROVAL_APPROVE, north.getId())).isFalse();
        assertThatThrownBy(() -> regionAccess.require(north.getId(), RegionRight.APPROVE))
                .isInstanceOf(AccessDeniedException.class);

        actAs(checker);
        assertThat(currentUser.has(Privileges.INVOICE_MANAGE, north.getId())).isFalse();
        assertThat(currentUser.has(Privileges.APPROVAL_APPROVE, north.getId())).isTrue();
        assertThatThrownBy(() -> regionAccess.requireManage(north.getId()))
                .isInstanceOf(AccessDeniedException.class);
        // Both cover VIEW, so neither of them loses sight of the branch they work in.
        assertThatCode(() -> regionAccess.require(north.getId(), RegionRight.VIEW))
                .doesNotThrowAnyException();
    }

    @Test
    void holdingManageInOneRegionAndViewInAnotherLetsYouEditOnlyTheFirst() {
        User rita = user("rita.region", DataSeeder.ROLE_SALES_POC);
        revokeRegionGrants(rita);
        grantTo(rita, north.getId(), RegionRight.MANAGE);
        grantTo(rita, west.getId(), RegionRight.VIEW);
        actAs(rita);

        // has(X) is unchanged and means "somewhere", which is what the navigation asks; has(X, r)
        // is the new per-record question, asked where the button is rather than where the page is.
        assertThat(currentUser.has(Privileges.INVOICE_MANAGE)).isTrue();
        assertThat(currentUser.has(Privileges.INVOICE_MANAGE, north.getId())).isTrue();
        assertThat(currentUser.has(Privileges.INVOICE_MANAGE, west.getId())).isFalse();
        assertThat(currentUser.has(Privileges.INVOICE_VIEW, west.getId())).isTrue();
        // A company-wide privilege answers the same in every region, including one she is in.
        assertThat(currentUser.has(Privileges.PRODUCT_VIEW, west.getId())).isTrue();

        assertThatCode(() -> regionAccess.requireManage(north.getId())).doesNotThrowAnyException();
        assertThatThrownBy(() -> regionAccess.requireManage(west.getId()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("manage");
    }

    @Test
    void namingARegionYouCannotManageOnACreateIsForbiddenAndNotMissing() {
        User rita = staffedOnlyIn("rita.region", DataSeeder.ROLE_SALES_POC, north.getId(), RegionRight.MANAGE);
        actAs(rita);

        // 403 and never 404: the caller NAMED the branch, so no id space is being probed and there
        // is nothing to hide by pretending it does not exist. A record she merely REACHED is the
        // other case and is emptied by the read predicate instead (B1, D-46, AUTH-08). The
        // endpoints that name a region arrive with R7 and R9; the decision is made here.
        assertThatThrownBy(() -> regionAccess.requireManage(west.getId()))
                .isInstanceOf(AccessDeniedException.class)
                .isNotInstanceOf(NotFoundException.class)
                .hasMessageContaining("no manage access");

        // An unplaced record is not "every region": a null region is refused, not waved through.
        assertThatThrownBy(() -> regionAccess.requireManage(null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void loginWorksForAUserWithNoRegionGrantsAtAll() throws Exception {
        User nora = user("nora.nowhere", "CASHIER");
        revokeRegionGrants(nora);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest("nora.nowhere", "password"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                // Signing in is not where a missing grant is reported: she gets in, and every list
                // she opens tells her it covers nothing rather than the door refusing her (B1).
                .andExpect(jsonPath("$.user.allRegions").value(false))
                .andExpect(jsonPath("$.user.regions").isEmpty())
                .andExpect(jsonPath("$.user.privileges[?(@ == '" + Privileges.CUSTOMER_VIEW + "')]").exists())
                .andExpect(jsonPath("$.user.privileges[?(@ == '" + Privileges.CUSTOMER_MANAGE + "')]").doesNotExist());

        mockMvc.perform(get("/api/auth/me").with(as(nora)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allRegions").value(false))
                .andExpect(jsonPath("$.regions").isEmpty());
    }

    @Test
    void theAuthPayloadNamesEachRegionTheCallerWorksInAndTheWildcardSeparately() throws Exception {
        User rita = user("rita.region", DataSeeder.ROLE_SALES_POC);
        revokeRegionGrants(rita);
        grantTo(rita, north.getId(), RegionRight.MANAGE);
        grantTo(rita, west.getId(), RegionRight.VIEW);
        grantTo(rita, west.getId(), RegionRight.APPROVE);

        mockMvc.perform(get("/api/auth/me").with(as(rita)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allRegions").value(false))
                .andExpect(jsonPath("$.regions.length()").value(2))
                .andExpect(jsonPath("$.regions[0].code").value("NORTH"))
                .andExpect(jsonPath("$.regions[0].name").value("NORTH Branch"))
                .andExpect(jsonPath("$.regions[0].rights.length()").value(1))
                .andExpect(jsonPath("$.regions[0].rights[0]").value("MANAGE"))
                // In the ladder's own order, so the payload does not vary between two sign-ins.
                .andExpect(jsonPath("$.regions[1].code").value("WEST"))
                .andExpect(jsonPath("$.regions[1].rights[0]").value("VIEW"))
                .andExpect(jsonPath("$.regions[1].rights[1]").value("APPROVE"));

        // The wildcard is reported as allRegions and NOT expanded into a list of every branch:
        // that list would be a query per sign-in and would go stale the day one is opened (B1).
        mockMvc.perform(get("/api/auth/me").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allRegions").value(true))
                .andExpect(jsonPath("$.regions").isEmpty())
                .andExpect(jsonPath("$.privileges[?(@ == '" + Privileges.CUSTOMER_MANAGE + "')]").exists());
    }

    @Test
    void aCustomerLoginKeepsEveryPrivilegeItsRoleGivesBecauseItsReachIsItsOwnAccount() {
        Customer acme = customer("Acme Ltd");
        User login = customerUser("acme.login", acme.getId());

        // A customer holds no grants by design — customer_id and the POC book already decide what
        // it can reach — so the drop rule must not apply to it, or raising a dispute, sending a
        // message and uploading a document would all stop working on the deploy that added
        // regions, for every customer at once (B1).
        assertThat(userRegionGrantRepository.findByUserId(login.getId())).isEmpty();
        assertThat(authoritiesOf(login))
                .contains(Privileges.DISPUTE_CREATE, Privileges.EMAIL_SEND, Privileges.DOCUMENT_MANAGE,
                        Privileges.INVOICE_VIEW);

        actAs(login);
        assertThatCode(() -> regionAccess.requireManage(west.getId())).doesNotThrowAnyException();
    }

    @Test
    void theLastAdministratorGuardStaysCompanyWide() throws Exception {
        Role role = roleWith("REGIONLESS_ADMIN", Privileges.USER_VIEW, Privileges.USER_MANAGE,
                Privileges.CUSTOMER_VIEW, Privileges.CUSTOMER_MANAGE);
        User andy = user("andy.admin", role.getName());
        revokeRegionGrants(andy);

        // Managing people is the company's business and not a branch's, so USER_MANAGE survives
        // with no grant at all while CUSTOMER_MANAGE, which is a branch's business, does not.
        assertThat(RegionRights.needed(Privileges.USER_MANAGE)).isNull();
        assertThat(authoritiesOf(andy))
                .contains(Privileges.USER_MANAGE, Privileges.USER_VIEW)
                .doesNotContain(Privileges.CUSTOMER_MANAGE);

        // And the guard counts him: an administrator who works in no region is still an
        // administrator, so the company is not held hostage by a grant nobody has given yet.
        assertThat(userRepository.countActiveHolders(Privileges.USER_MANAGE, admin.getId(), null))
                .isEqualTo(1);
        mockMvc.perform(put("/api/users/" + admin.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}")
                        .with(as(andy)))
                .andExpect(status().isOk());
        assertThat(userRepository.findById(admin.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    void anAdministratorHoldsBothWildcardLevelsBecauseApproveDoesNotCoverManage() {
        // The seeded administrator is the one account that must never be locked out, and one
        // wildcard row at APPROVE would have locked them out of every write in the application:
        // APPROVE does not cover MANAGE (B1).
        assertThat(userRegionGrantRepository.findByUserId(admin.getId()))
                .extracting(UserRegionGrant::getRegionId, UserRegionGrant::getRight)
                .containsExactlyInAnyOrder(tuple(null, RegionRight.MANAGE), tuple(null, RegionRight.APPROVE));
        assertThat(authoritiesOf(admin))
                .contains(Privileges.CUSTOMER_MANAGE, Privileges.INVOICE_MANAGE,
                        Privileges.APPROVAL_APPROVE, Privileges.EMAIL_SEND);

        actAs(admin);
        // Including a branch opened after the grant was given, which is what the wildcard buys.
        Region opened = region("LATER");
        assertThat(currentUser.has(Privileges.INVOICE_MANAGE, opened.getId())).isTrue();
        assertThatCode(() -> regionAccess.requireManage(opened.getId())).doesNotThrowAnyException();
    }

    @Test
    void aUserPageReadsTheGrantTableABoundedNumberOfTimesRatherThanOncePerPerson() throws Exception {
        for (int i = 0; i < 6; i++) {
            user("person" + i, "VIEWER");
        }

        long reads = CountingStatements.reads("user_region_grants", () ->
                mockMvc.perform(get("/api/users").with(as(admin))).andExpect(status().isOk()));

        // Grants are EAGER because they are read on every request through the principal, and an
        // EAGER collection with no @BatchSize is one select per person on every page of the staff
        // directory. Two reads here: one to build the caller's own principal, one for the page.
        assertThat(reads).isLessThanOrEqualTo(2);
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private Set<String> authoritiesOf(User u) {
        User fresh = userRepository.findById(u.getId()).orElseThrow();
        return AppUserDetailsService.buildAuthorities(fresh).stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    /** Somebody staffed in exactly one region at exactly one level, and nowhere else. */
    private User staffedOnlyIn(String username, String roleName, Long regionId, RegionRight right) {
        User u = user(username, roleName);
        revokeRegionGrants(u);
        grantTo(u, regionId, right);
        return u;
    }

    private void grantTo(User u, Long regionId, RegionRight right) {
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(u.getId()).regionId(regionId).right(right).build());
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by RegionRightsTest")
                .privileges(Arrays.stream(privileges)
                        .map(p -> privilegeRepository.findByName(p).orElseThrow())
                        .collect(Collectors.toCollection(HashSet<Privilege>::new)))
                .build()));
    }
}
