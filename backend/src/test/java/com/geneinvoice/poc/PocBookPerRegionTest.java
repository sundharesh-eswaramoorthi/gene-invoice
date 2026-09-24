package com.geneinvoice.poc;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionCustodyService;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The POC book per region, with no column on customer_pocs. A seat's region IS its customer's, by
 * definition, so the book needs no denormalised copy of it — what it needs is that a seat is only
 * ever given to somebody who works there, that the picker offers nobody else, and that losing a
 * region hides those rows without rewriting the book. The most valuable thing tested here is a
 * NON-change: "the primary in region N" is just "the primary", so every path that resolves a
 * primary POC — including the email placeholders Part A builds on — inherits region-correctness
 * with no edit at all (B1).
 */
class PocBookPerRegionTest extends IntegrationTestBase {

    @Autowired PocService pocService;
    @Autowired CustomerService customerService;
    @Autowired RegionCustodyService custodyService;
    @Autowired AuditService auditService;
    @Autowired PrivilegeRepository privilegeRepository;

    User admin;
    Region home;
    Region north;
    Customer homeAccount;
    Customer northAccount;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        home = defaultRegion();
        north = region("NORTH");
        actAs(admin);
        homeAccount = opened("Home Ltd", "home.login", home);
        northAccount = opened("North Ltd", "north.login", north);
    }

    @Test
    void theAssignablePickerDoesNotOfferSomeoneWhoDoesNotWorkInTheCustomersRegion() throws Exception {
        collectionPocIn("hilda.hq", home);
        collectionPocIn("nora.north", north);
        wildcard(collectionPocIn("wanda.wild", home));

        // Asked for the NORTH account: the person who works there, and the person who works
        // everywhere. Hilda would have been offered before B1 and the assignment would then have
        // been accepted, which is how a seat that shows its holder nothing used to get written.
        assertThat(usernames(assignable("&customerId=" + northAccount.getId())))
                .contains("nora.north", "wanda.wild")
                .doesNotContain("hilda.hq");

        // Naming the branch directly is the same answer: the account is only ever a way of saying
        // which branch, and the grant editor has no account to name.
        assertThat(usernames(assignable("&regionId=" + north.getId())))
                .contains("nora.north", "wanda.wild")
                .doesNotContain("hilda.hq");

        // And the other way round for the HQ account, so this is a narrowing and not an ordering.
        assertThat(usernames(assignable("&customerId=" + homeAccount.getId())))
                .contains("hilda.hq", "wanda.wild")
                .doesNotContain("nora.north");

        // A caller who works everywhere may browse the whole directory, because for them
        // "anywhere" is an answer.
        assertThat(usernames(assignable(""))).contains("hilda.hq", "nora.north", "wanda.wild");

        // Anybody else has to say where. Their first branch would be a guess, and a picker that
        // guesses is how the wrong person gets a seat.
        User cara = staffedIn("cara.cashier", "CASHIER", Map.of(home.getId(), RegionRight.MANAGE));
        mockMvc.perform(get("/api/pocs/assignable?type=COLLECTION").with(as(cara)))
                .andExpect(status().isBadRequest());

        // Naming an account they cannot see is 404 and not 403: the picker must not become a way
        // of probing for customers (AUTH-08).
        mockMvc.perform(get("/api/pocs/assignable?type=COLLECTION&customerId=" + northAccount.getId())
                        .with(as(cara)))
                .andExpect(status().isNotFound());
    }

    @Test
    void assigningAPocFromAnotherRegionIsRefusedAndTheMessageNamesTheRegion() throws Exception {
        User hilda = collectionPocIn("hilda.hq", home);

        mockMvc.perform(post("/api/customers/" + northAccount.getId() + "/pocs").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PocDtos.AddCustomerPocRequest(
                                PocType.COLLECTION, hilda.getId(), true))))
                // 400 and not 403: the caller is being told something about the PERSON they named,
                // not about their own reach — the administrator may work in NORTH perfectly well.
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "User hilda.hq does not work in NORTH and cannot be its Collection POC"));

        actAs(admin);
        assertThat(pocService.listFor(northAccount.getId())).isEmpty();

        // The same person, the same request, against the branch she does work in.
        mockMvc.perform(post("/api/customers/" + homeAccount.getId() + "/pocs").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PocDtos.AddCustomerPocRequest(
                                PocType.COLLECTION, hilda.getId(), true))))
                .andExpect(status().isOk());
        actAs(admin);
        assertThat(pocService.listFor(homeAccount.getId())).hasSize(1);
    }

    @Test
    void losingARegionGrantHidesTheCustomersAPocStillHoldsASeatOn() throws Exception {
        User pat = staffedIn("pat.poc", bookOnlyRole().getName(),
                Map.of(home.getId(), RegionRight.MANAGE, north.getId(), RegionRight.MANAGE));
        actAs(admin);
        pocService.add(homeAccount.getId(), PocType.COLLECTION, pat.getId(), true);
        pocService.add(northAccount.getId(), PocType.COLLECTION, pat.getId(), true);

        // Two seats, two branches, two rows on her list.
        mockMvc.perform(get("/api/customers").with(as(pat)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // The region right goes away. Nothing is done to the book at all.
        actAs(admin);
        userRegionGrantRepository.deleteAll(userRegionGrantRepository.findByUserId(pat.getId())
                .stream().filter(g -> north.getId().equals(g.getRegionId())).toList());

        mockMvc.perform(get("/api/customers").with(as(pat)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(homeAccount.getId()));

        // "My book" now means "my seats, within the regions I may see": the seat row is untouched,
        // so restoring the grant restores the row without anybody rewriting the book (B1).
        actAs(admin);
        assertThat(pocService.listFor(northAccount.getId())).hasSize(1);
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(pat.getId()).regionId(north.getId()).right(RegionRight.MANAGE).build());
        mockMvc.perform(get("/api/customers").with(as(pat)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void thePrimaryPocIsUnchangedByRegions() {
        User penny = collectionPocIn("penny.primary", home, north);
        User sid = collectionPocIn("sid.second", home, north);
        actAs(admin);
        pocService.add(homeAccount.getId(), PocType.COLLECTION, penny.getId(), true);
        pocService.add(homeAccount.getId(), PocType.COLLECTION, sid.getId(), false);

        assertThat(pocService.primaryFor(homeAccount.getId(), PocType.COLLECTION)
                .map(User::getId)).contains(penny.getId());

        // A customer is in exactly one region, so uk_customer_poc_primary is ALREADY per region
        // and nothing about "the primary" has to learn what a branch is. Moving the account to a
        // branch both holders work in changes neither the seats nor which of them is primary.
        custodyService.move(homeAccount.getId(), north.getId(), null, "reorganised");

        assertThat(pocService.listFor(homeAccount.getId())).hasSize(2);
        assertThat(pocService.primaryFor(homeAccount.getId(), PocType.COLLECTION)
                .map(User::getId))
                .as("the primary in region N IS the primary")
                .contains(penny.getId());
        assertThat(pocService.listFor(homeAccount.getId()).stream()
                .filter(CustomerPoc::isPrimary).count()).isEqualTo(1);
        // And every path that resolves "the" POC still resolves it the same way, which is what
        // A4's placeholders and every EmailTargets route inherit untouched.
        assertThat(pocService.defaultAssignee(homeAccount.getId(), PocType.COLLECTION)
                .map(User::getId)).contains(penny.getId());
        assertThat(pocService.activeHolders(homeAccount.getId(), PocType.COLLECTION))
                .extracting(User::getId).containsExactly(penny.getId(), sid.getId());
    }

    @Test
    void aMoveVacatesASeatWhoseHolderCannotManageTheDestinationAndPromotesTheNextPrimary() {
        User lena = collectionPocIn("lena.leaving", home);
        User stan = collectionPocIn("stan.staying", home, north);
        actAs(admin);
        pocService.add(homeAccount.getId(), PocType.COLLECTION, lena.getId(), true);
        pocService.add(homeAccount.getId(), PocType.COLLECTION, stan.getId(), false);

        RegionCustodyService.MoveResult moved =
                custodyService.move(homeAccount.getId(), north.getId(), null, "reorganised");

        assertThat(moved.pocSeatsVacated()).isEqualTo(1);
        List<CustomerPoc> after = pocService.listFor(homeAccount.getId());
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getUser().getId()).isEqualTo(stan.getId());
        // The promotion is the half a move must not lose: an account left with no primary POC has
        // nobody for a placeholder to resolve to and nobody for the sweeper to notify (D-44).
        assertThat(after.get(0).isPrimary()).isTrue();
        assertThat(pocService.primaryFor(homeAccount.getId(), PocType.COLLECTION)
                .map(User::getId)).contains(stan.getId());

        // And it leaves exactly the trail a hand-made removal would, because it IS one: the move
        // goes through PocService.remove rather than deleting rows of its own.
        List<String> actions = auditService.historyFor(CustomerService.ENTITY, homeAccount.getId())
                .stream().map(AuditLog::getAction).toList();
        assertThat(actions).contains(PocService.AUDIT_POC_REMOVED, PocService.AUDIT_POC_PRIMARY_CHANGED);
    }

    @Test
    void theStaffDirectoryDoesNotListPeopleFromRegionsIHaveNothingIn() throws Exception {
        User carl = staffedIn("carl.caller", "CASHIER", Map.of(home.getId(), RegionRight.MANAGE));
        staffedIn("heidi.here", "CASHIER", Map.of(home.getId(), RegionRight.MANAGE));
        staffedIn("nigel.north", "CASHIER", Map.of(north.getId(), RegionRight.MANAGE));

        // Before B1 any staff user name-searched the whole company. The directory is the last
        // read of PEOPLE that the query funnel never covered, so it carries the clause by hand.
        assertThat(usernames(getJson("/api/emails/people", carl)))
                .contains("heidi.here", "admin")
                .doesNotContain("nigel.north");

        // Not vacuous: the person really is there, for somebody who may see that branch.
        assertThat(usernames(getJson("/api/emails/people", admin))).contains("nigel.north");

        // And it narrows the moment the grant does, with nothing else changing.
        actAs(admin);
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(carl.getId()).regionId(north.getId()).right(RegionRight.VIEW).build());
        assertThat(usernames(getJson("/api/emails/people", carl))).contains("nigel.north");
    }

    @Test
    void aUserWithNoGrantsAtAllIsStillVisibleToTheAdminWhoCreatedThem() throws Exception {
        User nula = user("nula.new", DataSeeder.ROLE_COLLECTION_POC);
        revokeRegionGrants(nula);

        // The administrator has just made this person and has not staffed them yet. If an
        // unstaffed person were invisible the screen you create them on could not show them back,
        // which is a bug and not a policy.
        assertThat(usernames(getJson("/api/users", admin).get("content"))).contains("nula.new");
        assertThat(usernames(getJson("/api/emails/people", admin))).contains("nula.new");

        // Visible is not the same as assignable, and this is the line between them: somebody who
        // works nowhere may be seen, named on an email and edited, and may hold a seat nowhere.
        assertThat(usernames(assignable("&regionId=" + home.getId()))).doesNotContain("nula.new");
        assertThat(usernames(assignable(""))).doesNotContain("nula.new");
        mockMvc.perform(post("/api/customers/" + homeAccount.getId() + "/pocs").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PocDtos.AddCustomerPocRequest(
                                PocType.COLLECTION, nula.getId(), true))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void namingABranchYouHoldNothingInIsNotFoundRatherThanADirectoryOfItsStaff() throws Exception {
        collectionPocIn("hilda.hq", home);
        collectionPocIn("nora.north", north);
        wildcard(collectionPocIn("wanda.wild", home));
        User cara = staffedIn("cara.cashier", "CASHIER", Map.of(home.getId(), RegionRight.MANAGE));

        // ?customerId= has always gone through the scoped read and 404s (above). ?regionId= was
        // returned verbatim, so naming any branch handed back its POC-eligible staff directory —
        // id, username, full name, email, role — to somebody who holds nothing there. The axis
        // that closes the three sibling reads of the same people (GET /api/users, /api/users/{id},
        // /api/emails/people) lives in the query funnel, and findAssignableInRegion is a
        // hand-written @Query that never enters it (B1).
        mockMvc.perform(get("/api/pocs/assignable?type=COLLECTION&regionId=" + north.getId())
                        .with(as(cara)))
                .andExpect(status().isNotFound());

        // 404 and not 403, for the same reason the customerId arm answers 404: an id space is
        // being probed one small integer at a time, and the refusal must not become a way of
        // asking which branches exist (AUTH-08).
        mockMvc.perform(get("/api/pocs/assignable?type=COLLECTION&regionId=" + north.getId()
                        + "&q=nor").with(as(cara)))
                .andExpect(status().isNotFound());

        // And her own branch still answers, so this is a narrowing and not a removal.
        assertThat(usernames(getJson("/api/pocs/assignable?type=COLLECTION&regionId="
                + home.getId(), cara))).contains("hilda.hq", "wanda.wild");
    }

    @Test
    void aWildcardHolderMayStillNameAnyBranch() throws Exception {
        collectionPocIn("nora.north", north);
        User wanda = staffedIn("wanda.wild", "CASHIER", Map.of());
        wildcard(wanda);

        // A null-region grant covers every branch, including ones opened after it was given, so
        // the picker keeps working for the grant editor and the new-account screen (B1).
        assertThat(usernames(getJson("/api/pocs/assignable?type=COLLECTION&regionId="
                + north.getId(), wanda))).contains("nora.north");
    }

    /**
     * ONE primary seat per account per type, whichever route puts a second one there (CP-02, B1).
     *
     * <p>uk_customer_poc_primary is a PARTIAL unique index on
     * {@code customer_pocs (customer_id, poc_type) where is_primary} and exists on Postgres only,
     * so on H2 nothing but a test can see the invariant at all and on Postgres the database
     * answers. Both routes that write a primary seat onto an account that already has one are
     * here: "add this person and make them primary", and the primary chip. A fixture that wrote
     * is_primary on every seat it made is how TaskAccessTest came to hold two, which H2 took and
     * Postgres refused — this test is the product side of that, and it is the half that would
     * catch clearPrimary losing its flush, where the demotion would reach the database after the
     * promotion it has to precede (B1, D-44).
     */
    @Test
    void anAccountNeverHoldsTwoPrimarySeatsOfOneTypeHoweverTheSecondOneArrives() {
        User penny = collectionPocIn("penny.first", home);
        User sid = collectionPocIn("sid.later", home);
        actAs(admin);

        CustomerPoc pennysSeat =
                pocService.add(homeAccount.getId(), PocType.COLLECTION, penny.getId(), true);
        assertThat(primarySeatHolders()).containsExactly(penny.getId());

        // "Add them AND make them primary" on an account that already has one: the seat arrives,
        // the old primary is demoted, and exactly one row is left carrying the flag.
        pocService.add(homeAccount.getId(), PocType.COLLECTION, sid.getId(), true);
        assertThat(primarySeatHolders()).containsExactly(sid.getId());

        // And the chip the other way round, which is the same write from the other screen.
        pocService.setPrimary(homeAccount.getId(), pennysSeat.getId());
        assertThat(primarySeatHolders()).containsExactly(penny.getId());
        assertThat(pocService.listFor(homeAccount.getId())).hasSize(2);
    }

    // ---- fixtures ---------------------------------------------------------------------------

    /** Who holds the primary COLLECTION seat on the home account — a LIST, so two would show. */
    private List<Long> primarySeatHolders() {
        return pocService.listFor(homeAccount.getId()).stream()
                .filter(CustomerPoc::isPrimary)
                .map(seat -> seat.getUser().getId())
                .toList();
    }

    /** An account opened the way the application opens one, so it has its opening placement. */
    private Customer opened(String name, String username, Region where) {
        return customerService.create(new CustomerDtos.CustomerCreateRequest(
                name, null, null, null, null, where.getId(), username, "Password1!"));
    }

    /** Somebody who may be a Collection POC, and who works in exactly the branches named. */
    private User collectionPocIn(String username, Region... where) {
        return staffedIn(username, DataSeeder.ROLE_COLLECTION_POC,
                Stream.of(where).collect(Collectors.toMap(Region::getId, r -> RegionRight.MANAGE)));
    }

    /** Somebody staffed in exactly the branches named, at exactly the levels named, and nowhere else. */
    private User staffedIn(String username, String roleName, Map<Long, RegionRight> where) {
        User u = user(username, roleName);
        revokeRegionGrants(u);
        where.forEach((regionId, right) -> userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(u.getId()).regionId(regionId).right(right).build()));
        return u;
    }

    /** The null-region grant: works in every branch, including ones opened after it was given. */
    private void wildcard(User u) {
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(u.getId()).regionId(null).right(RegionRight.MANAGE).build());
    }

    /**
     * A POC with no SCOPE_OVERRIDE, so their list really is their book: the region axis and the
     * book axis are ANDed, and only a role like this one can tell the two apart.
     */
    private Role bookOnlyRole() {
        return roleRepository.findByName("POC_BOOK_ONLY").orElseGet(() -> roleRepository.save(
                Role.builder()
                        .name("POC_BOOK_ONLY")
                        .description("Sees the customers they hold a seat on, and no others")
                        .privileges(Stream.of(Privileges.CUSTOMER_VIEW, Privileges.POC_VIEW,
                                        Privileges.POC_ASSIGNABLE_COLLECTION)
                                .map(n -> privilegeRepository.findByName(n).orElseThrow())
                                .collect(Collectors.toCollection(HashSet::new)))
                        .build()));
    }

    private JsonNode assignable(String extra) throws Exception {
        return getJson("/api/pocs/assignable?type=COLLECTION" + extra, admin);
    }

    private JsonNode getJson(String path, User caller) throws Exception {
        String body = mockMvc.perform(get(path).with(as(caller)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // The filter chain clears the SecurityContext it set up, so the test thread's own
        // principal is restored for whatever this test calls a service with next.
        actAs(admin);
        return objectMapper.readTree(body);
    }

    private List<String> usernames(JsonNode rows) {
        List<String> out = new ArrayList<>();
        rows.forEach(row -> out.add(row.get("username").asText()));
        return out;
    }
}
