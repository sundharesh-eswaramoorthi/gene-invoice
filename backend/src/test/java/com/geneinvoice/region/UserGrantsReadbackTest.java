package com.geneinvoice.region;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/users/{id}/regions — READING somebody else's grants, which until this unit nothing in
 * the application could do.
 *
 * <p>WHY THESE TESTS EXIST, AND WHY THEY CROSS THE CLIENT/SERVER BOUNDARY DELIBERATELY. The only
 * read of a person's grants was GET /api/regions/my, which answers for the CALLER off the
 * principal. The editor that sets somebody's grants therefore had nothing to show and said so in
 * its own words — "their current branches cannot be read back from here, so compose the whole
 * set" — while PUT /api/users/{id}/regions REPLACES every row. An administrator who opened it to
 * add one branch stripped the rest, the wildcard included, and that reach is not something the
 * stripped person can give back to themselves: requireNotSelfWidening forbids exactly that. The
 * writer could destroy reach it could not show (B1).
 *
 * <p>So the load-bearing assertion in this class is not "200 with some JSON". It is that the
 * answer is about the SUBJECT and not about the caller, asserted by making the two visibly
 * different: every read here is performed by a wildcard holder — allRegions true, no named rows —
 * against somebody with named rows and no wildcard, so an endpoint that answered for the caller
 * would return the exact opposite of the truth and pass nothing.
 */
class UserGrantsReadbackTest extends IntegrationTestBase {

    @Autowired PrivilegeRepository privilegeRepository;

    User admin;
    User cashier;
    Region home;
    Region north;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        home = defaultRegion();
        // A branch opened after the administrator's wildcard was written, as everywhere in B1.
        north = region("NORTH");
        cashier = userRepository.findByUsername("cashier").orElseThrow();
    }

    /**
     * THE ROSTER OF SOMEBODY ELSE READS BACK EXACTLY WHAT WAS GRANTED (B1).
     *
     * <p>Three claims in one test, because they are one claim: the reader answers for the subject,
     * it answers in the shape GET /api/regions/my already answers, and it answers the same bytes
     * the writer beside it answers — which is what makes it safe for an editor to show the reader's
     * answer and then submit it back. Both go through one builder, so they cannot drift.
     *
     * <p>The caller is the seeded administrator: a WILDCARD holder, allRegions true and not one
     * named row. The subject holds two named branches and no wildcard. An implementation that read
     * the principal instead of the table passes no assertion below.
     */
    @Test
    void theGrantsOfSomebodyElseReadBackExactlyWhatWasGranted() throws Exception {
        User raj = user("raj.rover", "CASHIER");
        // The caller and the subject are opposites, which is the whole point.
        assertThat(userRegionGrantRepository.findByUserId(admin.getId()))
                .allSatisfy(g -> assertThat(g.getRegionId()).isNull());

        String body = json(new RegionController.SetGrantsRequest(List.of(
                new RegionController.GrantInput(home.getId(), "MANAGE"),
                new RegionController.GrantInput(north.getId(), "VIEW"),
                new RegionController.GrantInput(north.getId(), "APPROVE"))));
        String written = mockMvc.perform(put("/api/users/" + raj.getId() + "/regions").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String read = mockMvc.perform(get("/api/users/" + raj.getId() + "/regions").with(as(admin)))
                .andExpect(status().isOk())
                // The subject's answer, not the caller's: the caller IS a wildcard holder.
                .andExpect(jsonPath("$.allRegions").value(false))
                .andReturn().getResponse().getContentAsString();

        RegionController.MyRegionsDto roster =
                objectMapper.readValue(read, RegionController.MyRegionsDto.class);
        assertThat(roster.allRegions()).isFalse();
        assertThat(roster.regions())
                .extracting(RegionDtos.RegionGrantDto::id, RegionDtos.RegionGrantDto::code,
                        RegionDtos.RegionGrantDto::name, RegionDtos.RegionGrantDto::rights)
                .containsExactlyInAnyOrder(
                        tuple(home.getId(), home.getCode(), home.getName(), List.of("MANAGE")),
                        // In the ladder's own order, VIEW before APPROVE, however the grants were
                        // written — the payload an editor renders has to be stable.
                        tuple(north.getId(), "NORTH", north.getName(), List.of("VIEW", "APPROVE")));

        // THE SAME BYTES THE WRITER ANSWERED. One builder behind both, so an editor can show what
        // it read and submit what it shows without the two disagreeing.
        assertThat(read).isEqualTo(written);

        // AND THE SAME BYTES THE SUBJECT READS ABOUT THEMSELVES. Same DTO, same shape, so a client
        // that already parses /api/regions/my needs no second parser (B1).
        assertThat(read).isEqualTo(mockMvc.perform(get("/api/regions/my").with(as(raj)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        // Read from the TABLE and not from anything cached on a principal: a grant written behind
        // the endpoint's back is in the next answer.
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(raj.getId()).regionId(north.getId()).right(RegionRight.MANAGE).build());
        RegionController.MyRegionsDto again = objectMapper.readValue(
                mockMvc.perform(get("/api/users/" + raj.getId() + "/regions").with(as(admin)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                RegionController.MyRegionsDto.class);
        assertThat(again.regions())
                .extracting(RegionDtos.RegionGrantDto::code, RegionDtos.RegionGrantDto::rights)
                .contains(tuple("NORTH", List.of("VIEW", "MANAGE", "APPROVE")));
    }

    /**
     * EVERYWHERE AND NOWHERE BOTH HAVE AN EMPTY LIST, AND THE FLAG IS WHAT TELLS THEM APART (B1).
     *
     * <p>A wildcard holder reports allRegions true with NO named rows, because expanding the
     * wildcard would be a query per read and would go stale the day a branch is opened. Somebody
     * created but not yet given a branch reports allRegions false with no named rows either. The
     * two answers differ in one boolean, and an editor that ignored it would show the company's
     * administrator as having no reach at all — and then save that.
     */
    @Test
    void aWildcardHolderReadsBackEveryBranchAndSomebodyUnstaffedReadsBackNothing() throws Exception {
        // A second administrator, so the wildcard case is read by somebody who is not the subject.
        User deputy = user("dee.deputy", "ADMIN");
        assertThat(userRegionGrantRepository.findByUserId(deputy.getId()))
                .isNotEmpty()
                .allSatisfy(g -> assertThat(g.getRegionId()).isNull());

        mockMvc.perform(get("/api/users/" + deputy.getId() + "/regions").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allRegions").value(true))
                .andExpect(jsonPath("$.regions").isEmpty());

        // And the other way round, which is the pair that proves the reader is not reading itself.
        mockMvc.perform(get("/api/users/" + admin.getId() + "/regions").with(as(deputy)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allRegions").value(true))
                .andExpect(jsonPath("$.regions").isEmpty());

        User nithin = user("nithin.new", "CASHIER");
        revokeRegionGrants(nithin);
        mockMvc.perform(get("/api/users/" + nithin.getId() + "/regions").with(as(admin)))
                .andExpect(status().isOk())
                // Same empty list as the wildcard holder above, opposite flag.
                .andExpect(jsonPath("$.allRegions").value(false))
                .andExpect(jsonPath("$.regions").isEmpty());
    }

    /**
     * A CUSTOMER LOGIN READS BACK NOTHING, AND THAT IS THE TRUTH RATHER THAN A GAP (B1).
     *
     * <p>A customer login holds no region grants by design: its reach is its own account, decided
     * by customer_id and the POC book, and AppUserDetailsService does not region-gate it at all.
     * So the honest answer is the empty roster, and the endpoint invents nothing — no wildcard
     * standing in for "sees its own invoices", which would read as company-wide reach.
     */
    @Test
    void aCustomerLoginReadsBackNoGrantsAtAll() throws Exception {
        Customer acme = customer("Acme Ltd");
        User login = customerUser("acme.login", acme.getId());
        assertThat(userRegionGrantRepository.findByUserId(login.getId())).isEmpty();

        mockMvc.perform(get("/api/users/" + login.getId() + "/regions").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allRegions").value(false))
                .andExpect(jsonPath("$.regions").isEmpty());
    }

    /**
     * An id that is nobody is not found, exactly as the writer beside it answers, so a client that
     * may reach one of the pair cannot probe the id space through the other (B1, AUTH-08).
     */
    @Test
    void anIdThatIsNobodyIsNotFound() throws Exception {
        mockMvc.perform(get("/api/users/999999/regions").with(as(admin)))
                .andExpect(status().isNotFound());

        // And the gate runs BEFORE the lookup, so somebody who may not read grants at all learns
        // nothing about which ids exist: the same 403 for a real person and for nobody (D-46).
        mockMvc.perform(get("/api/users/999999/regions").with(as(cashier)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/" + admin.getId() + "/regions").with(as(cashier)))
                .andExpect(status().isForbidden());
    }

    /**
     * READING WHO MAY WORK WHERE NEEDS BOTH HALVES OF THE PAIR (B1).
     *
     * <p>The VIEW pair and not the MANAGE pair the writer asks for, because seeing is strictly less
     * than deciding and every role in this application that holds USER_MANAGE and REGION_MANAGE
     * holds the two VIEW privileges as well — ADMIN is the only shipped role with either pair, and
     * it holds every privilege there is. Half the pair is not enough in either direction: this is
     * a person AND it is region reach, which is the same argument the writer's pair rests on.
     */
    @Test
    void readingSomebodysGrantsNeedsBothUserViewAndRegionView() throws Exception {
        User raj = user("raj.rover", "CASHIER");
        String path = "/api/users/" + raj.getId() + "/regions";

        // Neither privilege: a cashier administers nobody.
        mockMvc.perform(get(path).with(as(cashier))).andExpect(status().isForbidden());

        // People but not branches.
        User personnel = person("pat.personnel", "PERSONNEL_READER", Privileges.USER_VIEW);
        mockMvc.perform(get(path).with(as(personnel))).andExpect(status().isForbidden());

        // Branches but not people.
        User mapper = person("mia.mapper", "BRANCH_MAP_READER", Privileges.REGION_VIEW);
        mockMvc.perform(get(path).with(as(mapper))).andExpect(status().isForbidden());

        // BOTH, and no MANAGE of anything: an account that may look and not touch reads the roster.
        User both = person("vic.viewer", "ROSTER_READER",
                Privileges.USER_VIEW, Privileges.REGION_VIEW);
        mockMvc.perform(get(path).with(as(both)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allRegions").value(false))
                // A named row the reader themselves holds nothing in: a person is company-wide, so
                // the roster is not narrowed to the reader's own branches (B1).
                .andExpect(jsonPath("$.regions[0].code").value(home.getCode()));

        // And they still may not WRITE it, which is the difference the two pairs draw.
        mockMvc.perform(put(path).with(as(both))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegionController.SetGrantsRequest(List.of(
                                new RegionController.GrantInput(north.getId(), "MANAGE"))))))
                .andExpect(status().isForbidden());
    }

    /** A person on a role holding exactly the named privileges — the regionReader() idiom (B1). */
    private User person(String username, String roleName, String... privileges) {
        Role role = roleRepository.findByName(roleName).orElseGet(() -> roleRepository.save(
                Role.builder().name(roleName).description(roleName)
                        .privileges(Stream.of(privileges)
                                .map(n -> privilegeRepository.findByName(n).orElseThrow())
                                .collect(Collectors.toCollection(HashSet::new)))
                        .build()));
        return user(username, role.getName());
    }
}
