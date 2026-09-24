package com.geneinvoice.asof;

import com.geneinvoice.CountingStatements;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.approval.ApprovalSchemas;
import com.geneinvoice.approval.PendingAction;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.approval.PendingChangeStatus;
import com.geneinvoice.approval.PendingTargetType;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.asof.AsOf;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.asof.AsOfDates;
import com.geneinvoice.common.asof.AsOfEndpoints;
import com.geneinvoice.common.asof.AsOfInfo;
import com.geneinvoice.common.asof.AsOfInterceptor;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.CustomerRegionHistory;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The read-side clock, the ?asOf wire contract and the refusal-by-default allowlist.
 *
 * <p>WHAT SHIPPED HERE WAS AN INTERMEDIATE STATE AND IT IS NO LONGER INTERMEDIATE. When this class
 * was written AsOfEndpoints.AS_OF_CAPABLE was empty, so every request carrying ?asOf was a 400 and
 * the assertions below could use any endpoint as the example of one that refuses. B3-SLICE-INVOICE
 * allowlisted the four invoice endpoints, so the examples moved to endpoints that are still not
 * allowlisted and the invoice ones are now asserted from the OTHER side — admitted by the
 * allowlist, and then refused or served by the parser. That change of answer is the whole of what
 * the slice did to this file (B3).
 *
 * <p>THE WIRE CONTRACT tests drive the real servlet: refusal on a GET, refusal on a POST, refusal
 * on the endpoint that describes the feature, and no date left on the pooled thread afterwards.
 * They cannot reach the parser, because the allowlist refuses first and by design; the parser's
 * own refusals are therefore asserted directly on AsOfDates, and the ORDER of the two is asserted
 * too so that nobody later "fixes" it by parsing first.
 *
 * <p>THE SEAM tests open a context by hand, the way B3-SLICE-INVOICE's source() switch will once
 * an endpoint is allowlisted, and they are the load-bearing ones: three one-line changes in three
 * different packages turn a lot of already-written code from dead into live, and until this unit
 * NONE of it had ever executed. In particular B1's as-of region path — RegionPredicates.asOf over
 * customer_region_history, and the two-clause leak guard composed on top of it — shipped in R4
 * with zero runtime coverage, and this is the first place it runs at all (B3).
 */
class AsOfContextTest extends IntegrationTestBase {

    @Autowired CustomerService customerService;
    @Autowired InvoiceService invoiceService;
    @Autowired TableQueryExecutor queryExecutor;
    @Autowired com.geneinvoice.poc.ScopeResolver scopeResolver;
    @Autowired AsOfInterceptor interceptor;

    User admin;
    LocalDate today;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        today = LocalDate.now(ZoneOffset.UTC);
        actAs(admin);
    }

    /** No test may finish holding a date: the next one runs on this very thread (B3). */
    @AfterEach
    void closeAnyContext() {
        AsOfContext.clear();
    }

    // ---------------------------------------------------------------- the wire contract

    /**
     * The whole of the compatibility claim: a client that sends no asOf gets exactly the request
     * it sends today, answered by exactly the path it is answered by today. Nothing is opened, the
     * envelope's new field is null, and — the part that could not be asserted by reading the JSON —
     * not one statement of as-of SQL is added, which for a caller who is region-narrowed means no
     * subquery over customer_region_history.
     *
     * <p>The one honest qualification to the name: the response gains the key "asOf", whose value
     * is null. Flutter's hand-written PagedResult.fromJson ignores unknown keys, so the shipped
     * client is unaffected, and no other key moves (B3).
     */
    @Test
    void aRequestWithoutAsOfIsByteIdenticalToTheOneTheClientSendsToday() throws Exception {
        User wendy = narrowedTo(region("WEST"));
        placed("Stayed West", region("WEST"), List.of(placement(region("WEST"), today.minusDays(30), null)));

        List<String> sql = CountingStatements.capture(() ->
                mockMvc.perform(get("/api/customers").with(as(wendy)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalElements").value(1))
                        .andExpect(jsonPath("$.content[0].name").value("Stayed West"))
                        .andExpect(jsonPath("$.lockedFilters[0]")
                                .value("regionId:in:" + region("WEST").getId()))
                        .andExpect(jsonPath("$.asOf").doesNotExist()));

        assertThat(sql).as("the live read is still narrowed by the region column")
                .anySatisfy(s -> assertThat(s).contains("from customers"));
        assertThat(sql).as("and asks the placement ledger nothing at all")
                .noneSatisfy(s -> assertThat(s).contains("customer_region_history"));
        assertThat(AsOfContext.isActive()).isFalse();
    }

    /**
     * Refusal is the DEFAULT, and that is what is asserted here — not that everything refuses. A
     * read whose endpoint has not been allowlisted answers a date it cannot honour by saying so
     * rather than by quietly serving today, which is the one outcome the feature exists to make
     * impossible. Since B3-SLICE-INVOICE the invoice family is on the other side of that line and
     * is asserted as such below (B3).
     */
    @Test
    void asOfOnAnEndpointThatCannotAnswerAsOfADateIsRejected() throws Exception {
        // The three entity lists this loop used to name — customers, payments and promises — are
        // on the OTHER side of the line since B3-SLICE-REST, together with disputes, tasks and the
        // approval queue. These five are what is left on this side: two of them describe the
        // application rather than a record, and three are records that are not mirrored at all
        // (contract a.2). A sixth, /api/tasks/count, is a task read that is deliberately NOT
        // as-of capable — it is a sidebar badge about work outstanding NOW (B3).
        for (String path : List.of("/api/products", "/api/users", "/api/tasks/count",
                "/api/dashboard/summary", "/api/table-schemas/invoices")) {
            mockMvc.perform(get(path + "?asOf=" + today.minusDays(1)).with(as(admin)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("This endpoint cannot be asked as of a date"));
        }
        // Including the endpoint that publishes the contract: it describes the feature and names
        // no record, so it is in NOT_AS_OF and is answered as of now or not at all.
        mockMvc.perform(get("/api/as-of?asOf=" + today.minusDays(1)).with(as(admin)))
                .andExpect(status().isBadRequest());
        assertThat(AsOfEndpoints.NOT_AS_OF).containsKey("GET /api/as-of");

        // And the four the slice allowlisted, so this test cannot quietly become vacuous: the
        // allowlist is a line with two sides and both are asserted, the refusals above being the
        // other side. `contains` and no longer `containsExactlyInAnyOrder` since B3-DASHBOARD,
        // because the set GROWS one unit at a time — the five dashboard figures joined it here and
        // the rest of the entity families join it next — and the endpoints named here are the ones
        // whose admission this test is actually about. Each unit asserts ITS OWN entries verbatim
        // in its own test class (AsOfFiguresTest does), and B3-CLOSE replaces both with the
        // reflective union over every mapping in the application (B3).
        assertThat(AsOfEndpoints.AS_OF_CAPABLE).contains(
                "GET /api/invoices", "GET /api/invoices/summary", "GET /api/invoices/{id}",
                "POST /api/invoices/export");
        mockMvc.perform(get("/api/invoices?asOf=" + today.minusDays(1)).with(as(admin)))
                .andExpect(status().isOk());
        // And one of B3-SLICE-REST's, chosen because it is the read with no mirror table behind
        // it at all: the approval queue answers "what was outstanding then" off B2's own decision
        // log (B2, B3).
        mockMvc.perform(get("/api/approvals?asOf=" + today.minusDays(1)).with(as(admin)))
                .andExpect(status().isOk());
    }

    /**
     * The past is read only, and the refusal happens BEFORE the handler: the customer is not
     * created. A CSV export is a read but is a POST in this repo, so the guard is the allowlist
     * itself rather than a blanket "GET only" test — which is why the second half of this test
     * names an export that is NOT yet allowlisted. B3-SLICE-INVOICE allowlisted the invoice one
     * deliberately, and AsOfListTest asserts that it serves history rather than refusing (B3).
     */
    @Test
    void aPostCarryingAsOfIsRejectedWithThePastIsReadOnly() throws Exception {
        long before = customerRepository.count();

        mockMvc.perform(post("/api/customers?asOf=" + today.minusDays(1))
                        .contentType("application/json")
                        .content(json(new CustomerDtos.CustomerCreateRequest("Backdated Ltd", null,
                                null, null, null, defaultRegion().getId(), "backdated", "password1")))
                        .with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The past is read only"));
        assertThat(customerRepository.count()).as("the write never ran").isEqualTo(before);

        // The customers export joined the allowlist at B3-SLICE-REST, so the export named here is
        // now the approvals one — the single export in the application that is deliberately NOT
        // as-of capable, although the queue it exports IS. That keeps this half of the test about
        // what it has always been about: an export is a POST, and a POST is refused unless its
        // own pattern is allowlisted (B3).
        mockMvc.perform(post("/api/approvals/export?asOf=" + today.minusDays(1))
                        .contentType("application/json")
                        .content("{\"action\":\"export\",\"selectAllMatchingFilter\":true}")
                        .with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The past is read only"));
    }

    /**
     * THE ONE EXEMPTION, AND THE REASON IT IS AN EXEMPTION AND NOT AN ALLOWLIST ENTRY. Part A
     * shipped ?asOf on the manual rule run before this feature existed, where it means the date
     * the RULE is evaluated at: a dry run as of a past date is allowed and an APPLY as of a past
     * date is refused by the engine itself. The interceptor must step aside rather than answer
     * for it — and must not open a context, because A's guard compares asOf against today(),
     * which under an open context would be the very date it is guarding against, and a back-dated
     * apply would start creating real Tasks dated from a replayed past (A5, B3 INTEGRATION).
     */
    @Test
    void theRuleRunKeepsItsOwnAsOfBecauseItsHandlerOwnsTheParameter() {
        assertThat(AsOfEndpoints.HANDLER_OWNED)
                .containsOnlyKeys("POST /api/automation/rules/{id}/run");
        assertThat(AsOfEndpoints.handlerOwned("POST", "/api/automation/rules/{id}/run")).isTrue();
        assertThat(AsOfEndpoints.handlerOwned("POST", "/api/invoices/export")).isFalse();
        assertThat(AsOfEndpoints.capable("POST", "/api/automation/rules/{id}/run"))
                .as("exempt is not allowlisted: nothing opens a context for a run")
                .isFalse();
    }

    /**
     * yyyy-MM-dd or nothing. Asserted on the parser, because the allowlist refuses first and by
     * design — and the last two assertions pin that order, so a later unit that moves the parse
     * above the allowlist check (and so starts leaking "which dates are valid" from an endpoint
     * that cannot answer any of them) turns this red (B3).
     */
    @Test
    void asOfThatIsNotADateIsRejected() throws Exception {
        for (String raw : List.of("yesterday", "2026-1-5", "31/01/2026", "2026-13-01",
                "2026-02-30", "last week")) {
            assertThatThrownBy(() -> AsOfDates.parse(raw, today, null, AsOfDates.POLICY_SEEDED))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("asOf must be a date in the form yyyy-MM-dd");
        }

        // /api/products and not /api/customers since B3-SLICE-REST: the customers list CAN be
        // asked as of a date now, and this half of the test needs an endpoint that cannot, so
        // that "which check ran first" is still what it measures. Products are not mirrored at
        // all — contract a.2 — so this pattern is on the refusing side for good (B3).
        mockMvc.perform(get("/api/products?asOf=yesterday").with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This endpoint cannot be asked as of a date"));

        // The other side of the same order, which only became assertable once something was
        // allowlisted: an endpoint that CAN be asked as of a date gets past the allowlist and is
        // then refused by the PARSER, with the parser's own message. Two different refusals from
        // one parameter, and which one you get tells you which check ran first (B3).
        mockMvc.perform(get("/api/invoices?asOf=yesterday").with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("asOf must be a date in the form yyyy-MM-dd"));
    }

    /**
     * One temporal axis, at whole-day grain: two changes on the same day are indistinguishable, so
     * a time of day is refused rather than truncated. Truncating would answer a question the
     * contract cannot answer, by handing back changes made after the moment asked for (B3).
     */
    @Test
    void asOfWithATimeOfDayIsRejected() {
        for (String raw : List.of("2026-01-31T09:00:00Z", "2026-01-31T09:00", "2026-01-31 09:00")) {
            assertThatThrownBy(() -> AsOfDates.parse(raw, today, null, AsOfDates.POLICY_SEEDED))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("asOf is a whole day and cannot carry a time of day");
        }
    }

    /** Today and the future are the LIVE path, not a reconstruction of it (B3). */
    @Test
    void asOfTodayOrLaterIsServedLiveAndEchoesNoDate() {
        assertThat(AsOfDates.parse(today.toString(), today, null, AsOfDates.POLICY_SEEDED)).isNull();
        assertThat(AsOfDates.parse(today.plusDays(1).toString(), today, null,
                AsOfDates.POLICY_SEEDED)).isNull();
        assertThat(AsOfDates.parse(today.minusDays(1).toString(), today, null,
                AsOfDates.POLICY_SEEDED)).isNotNull();
    }

    /**
     * A date before the floor is 200 and the truth, not a refusal and not a silent answer: origin
     * SEEDED, exact false, and the standing note that says which half of the answer is trustworthy.
     * Under the 'reject' policy it is a 400 that names the floor instead (B3).
     */
    @Test
    void aDateBeforeTheHistoryFloorIsAnsweredFromTheSeedRowsAndSaysSo() {
        LocalDate floor = today.minusDays(10);
        LocalDate asked = today.minusDays(40);

        AsOfContext.State seeded = AsOfDates.parse(asked.toString(), today, floor,
                AsOfDates.POLICY_SEEDED);
        assertThat(seeded.origin()).isEqualTo(AsOfContext.ORIGIN_SEEDED);
        assertThat(seeded.exact()).isFalse();
        assertThat(seeded.notes()).singleElement().satisfies(note -> {
            assertThat(note).contains("is before the history floor " + floor);
            assertThat(note).contains("Which records existed on that date is exact");
            assertThat(note).contains("are not what was true then");
        });

        // On or after the floor the answer is a reconstruction and says nothing.
        AsOfContext.State exact = AsOfDates.parse(floor.toString(), today, floor,
                AsOfDates.POLICY_SEEDED);
        assertThat(exact.origin()).isEqualTo(AsOfContext.ORIGIN_RECONSTRUCTED);
        assertThat(exact.exact()).isTrue();
        assertThat(exact.notes()).isEmpty();

        assertThatThrownBy(() -> AsOfDates.parse(asked.toString(), today, floor,
                AsOfDates.POLICY_REJECT))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("is before the history floor " + floor);
        // The shipped application.yml documents the refusing policy as "none"; both spellings
        // normalise to one value so a configuration typo cannot silently mean the opposite.
        assertThat(AsOfDates.policy("none")).isEqualTo(AsOfDates.POLICY_REJECT);
        assertThat(AsOfDates.policy(null)).isEqualTo(AsOfDates.POLICY_SEEDED);
        assertThatThrownBy(() -> AsOfDates.policy("yes-please"))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Tomcat pools its threads, so a date left behind would answer the NEXT person's request from
     * the past. Driven through the interceptor's own two methods rather than through a second
     * MockMvc call, because that is the pair that has to hold: afterCompletion clears, and a
     * refusal never opened anything to leak (B3).
     */
    @Test
    void anAsOfRequestDoesNotLeakItsDateIntoTheNextRequestOnTheSameThread() throws Exception {
        AsOfContext.open(today.minusDays(3));
        assertThat(AsOfContext.isActive()).isTrue();
        interceptor.afterCompletion(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new Object(), null);
        assertThat(AsOfContext.date()).as("afterCompletion hands back a clean thread").isNull();

        // And the refusal path, which never reaches afterCompletion at all because preHandle threw.
        // /api/products and not /api/customers since B3-SLICE-REST, which put the customers list on
        // the answering side of the allowlist; what this half needs is a pattern that is refused
        // (B3).
        MockHttpServletRequest refused = new MockHttpServletRequest("GET", "/api/products");
        refused.setParameter("asOf", today.minusDays(3).toString());
        refused.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/products");
        assertThatThrownBy(() -> interceptor.preHandle(refused, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BadRequestException.class);
        assertThat(AsOfContext.date()).as("nothing was opened to leak").isNull();

        // The real servlet, end to end: a 400 must leave the thread as it found it.
        mockMvc.perform(get("/api/products?asOf=" + today.minusDays(3)).with(as(admin)))
                .andExpect(status().isBadRequest());
        assertThat(AsOfContext.isActive()).isFalse();

        // AND THE CASE THAT ONLY BECAME REACHABLE AT B3-SLICE-INVOICE, which is the one this test
        // was really written for: a request that SUCCEEDS really did open a context, and must
        // still hand the pooled thread back clean (B3).
        mockMvc.perform(get("/api/invoices?asOf=" + today.minusDays(3)).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf.date").value(today.minusDays(3).toString()));
        assertThat(AsOfContext.isActive())
                .as("an ANSWERED as-of request leaves no date behind either").isFalse();
    }

    /**
     * Restoring and not clearing, the rule RegionScope.within already follows: a backtest that
     * asks two dates in turn, or any nested open at all, must give the outer one back or the rest
     * of the outer read is silently answered live (B3).
     */
    @Test
    void openRestoresThePreviousDateRatherThanClearingTheThread() {
        LocalDate outer = today.minusDays(30);
        LocalDate inner = today.minusDays(2);

        try (AsOfContext.Handle a = AsOfContext.open(outer)) {
            assertThat(AsOfContext.date()).isEqualTo(outer);
            try (AsOfContext.Handle b = AsOfContext.open(inner)) {
                assertThat(AsOfContext.date()).isEqualTo(inner);
            }
            assertThat(AsOfContext.date()).as("the outer date came back").isEqualTo(outer);
        }
        assertThat(AsOfContext.isActive()).as("and the thread is clean again").isFalse();
    }

    /** GET /api/as-of publishes the contract rather than documenting it (B3). */
    @Test
    void theAsOfEndpointNamesTheFloorTodayAndThePreFloorPolicy() throws Exception {
        mockMvc.perform(get("/api/as-of").with(as(admin)))
                .andExpect(status().isOk())
                // B3-UPGRADES installed the floor: HistorySeedUpgrade writes history_floor once
                // on first boot and HistoryFloorService publishes it through AsOfFloor, so this
                // context's floor is the UTC day it started. Before that bean existed the key was
                // absent, and the change of answer is the whole of what supplying AsOfFloor
                // does (B3).
                .andExpect(jsonPath("$.floor").value(java.time.LocalDate.now(
                        java.time.ZoneOffset.UTC).toString()))
                .andExpect(jsonPath("$.today").value(InvoiceDates.today().toString()))
                .andExpect(jsonPath("$.preFloor").value("seeded"))
                // B3-SCHEMAS supplied the AsOfSupport implementation, so this list stopped being
                // empty. It was empty on purpose until then — nothing is advertised before it
                // works — and the change of answer is the whole of what supplying that port does,
                // exactly as the floor above was the whole of what AsOfFloor did. AS_OF_CAPABLE is
                // a DIFFERENT claim and is deliberately not wired to this one: this says the table
                // has a mirror schema behind it, that one says the endpoint will serve ?asOf. Six
                // tables have a twin; since B3-SLICE-INVOICE one of them has an endpoint (B3).
                .andExpect(jsonPath("$.entities").value(
                        org.hamcrest.Matchers.contains("invoices", "customers", "payments",
                                "promises", "disputes", "tasks")));

        // Authentication and no privilege: a customer login holds almost none and still gets the
        // answer, because it describes the feature and names no record. Whether an UNauthenticated
        // caller is refused is not asserted here — spring-security-test copies whatever the test
        // thread is holding into every request, so this harness cannot pose as nobody; the
        // refusal comes from SecurityConfig's anyRequest().authenticated(), which /api/as-of does
        // not opt out of.
        Customer c = customer("Acme Ltd");
        User theirs = customerUser("acme.login", c.getId());
        mockMvc.perform(get("/api/as-of").with(as(theirs)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preFloor").value("seeded"));
    }

    // ---------------------------------------------------------------- the seam

    /**
     * THE CLOCK SEAM, AND THE HALF OF IT THAT MUST NOT MOVE. today() follows the reader into the
     * past; todayForWrite() never does. S4 split them precisely so this edit could not move a
     * write, and PaymentPromiseService.targetStatus and sweepOverdue — both write paths that read
     * a date — are what it protects (B3).
     */
    @Test
    void todayForWriteStillReadsTheWallClockInsideAnOpenContext() {
        LocalDate asked = today.minusDays(400);
        assertThat(InvoiceDates.today()).isEqualTo(today);

        try (AsOfContext.Handle h = AsOfContext.open(asked)) {
            assertThat(InvoiceDates.today()).as("a read follows the date asked for").isEqualTo(asked);
            assertThat(InvoiceDates.todayForWrite()).as("a write never does").isEqualTo(today);
        }
        assertThat(InvoiceDates.today()).isEqualTo(today);
    }

    /**
     * The boundary instant is the END of the asked-for UTC day, inclusive — the same whole-day
     * convention FilterPredicates.atMost already uses for a date-only lte on an Instant. A change
     * committed at 23:59:59 on that day is in; one committed a nanosecond later is not (B3).
     */
    @Test
    void theAsOfInstantIsTheLastNanosecondOfTheAskedForUtcDay() {
        LocalDate asked = LocalDate.of(2026, 1, 31);
        try (AsOfContext.Handle h = AsOfContext.open(asked)) {
            assertThat(AsOfContext.instant())
                    .isEqualTo(Instant.parse("2026-02-01T00:00:00Z").minusNanos(1));
            assertThat(AsOfContext.instantOrNow()).isEqualTo(AsOfContext.instant());
        }
        assertThat(AsOfContext.instantOrNow()).isAfter(Instant.now().minusSeconds(5));
        assertThatThrownBy(AsOfContext::instant).isInstanceOf(IllegalStateException.class);
    }

    /**
     * THE FIRST TIME B1's AS-OF REGION PATH EVER RUNS. RegionScope.effectiveAsOf was the one line
     * R4 left for this unit, and it is the whole of B3's region story: the executor's single
     * mandatory call now resolves the placement as of the date this thread is answering, for every
     * axis, with no second injection point anywhere.
     *
     * <p>BOTH clauses, which is B3-04's leak guard: a record is visible under ?asOf only when the
     * caller may see where it was THEN and where it is NOW. Reading the past must not hand over a
     * row that has since moved out of reach, and must not hide one the caller can see today (B1, B3).
     */
    @Test
    void underAnAsOfDateTheRegionAxisResolvesWhereTheCustomerWasThenAndWhereItIsNow() throws Exception {
        Region west = region("WEST");
        Region home = defaultRegion();
        User wendy = narrowedTo(west);

        // Here all along: visible live and visible then.
        placed("Stayed West", west, List.of(placement(west, today.minusDays(30), null)));
        // Arrived five days ago: visible live, and NOT visible as of ten days ago, when it was
        // somebody else's account.
        placed("Arrived Last Week", west, List.of(
                placement(home, today.minusDays(30), today.minusDays(5)),
                placement(west, today.minusDays(5), null)));
        // Left five days ago: it WAS wendy's then, and reading the past must not hand back a row
        // that has since moved out of her reach.
        placed("Left Last Week", home, List.of(
                placement(west, today.minusDays(30), today.minusDays(5)),
                placement(home, today.minusDays(5), null)));

        actAs(wendy);
        assertThat(names(customerService.page(customers()))).containsExactlyInAnyOrder(
                "Stayed West", "Arrived Last Week");

        List<String> sql;
        try (AsOfContext.Handle h = AsOfContext.open(today.minusDays(10))) {
            // THE LIVE ROOT ON PURPOSE, and it has to be spelled out since B3-SLICE-REST:
            // customerService.page now switches to the account MIRROR under an open context, and
            // these three fixtures were saved through the repository at the wall clock, so their
            // only mirror versions open TODAY and a date ten days ago finds none of them. What is
            // under test here is the region AXIS — that RegionScope.effectiveAsOf resolves the
            // placement as of the date being answered, on whatever root it is given — so it is
            // asked of the root that still holds these rows. The end-to-end version over the
            // mirror, with a real timeline behind it, is
            // AsOfRegionAndApprovalTest#aRecordThatMovedIntoARegionICannotReadIsHiddenFromMyAsOfRead
            // and #aRegionIMayReadTodayBoundsAnAsOfListEvenForARegionIHeldThen (B3).
            sql = CountingStatements.capture(() ->
                    assertThat(queryExecutor.run(Customer.class, TableSchemas.CUSTOMERS,
                                    customers(), scopeResolver.forCustomers().predicates(), List.of())
                            .content().stream().map(Customer::getName).toList())
                            .containsExactly("Stayed West"));
        }
        assertThat(sql).as("the placement ledger is what answered it")
                .anySatisfy(s -> assertThat(s).contains("customer_region_history"));
    }

    /**
     * The second seam, and the only date preset in the codebase that bypassed the chokepoint:
     * relative:last7Days under ?asOf means the seven days ending on the date asked for, not the
     * seven days ending today. Without the change FilterPredicates would still be reading the wall
     * clock and an as-of ageing report would be seven days of the wrong week (B3).
     */
    @Test
    void relativeDatePresetsAreTheWindowEndingOnTheAsOfDateAndNotOnToday() {
        Product widget = product("Widget", "100.00");
        Customer acme = customer("Acme Ltd");
        User sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        actAs(admin);
        Invoice recent = invoiceOn(acme, widget, sam, today.minusDays(3));
        Invoice ancient = invoiceOn(acme, widget, sam, today.minusDays(63));

        TableQuery lastWeek = TableQuery.parseUnpaged(TableSchemas.INVOICES, null,
                List.of("invoiceDate:relative:last7Days"));
        assertThat(invoiceService.idsMatching(lastWeek, 100)).containsExactly(recent.getId());

        try (AsOfContext.Handle h = AsOfContext.open(today.minusDays(60))) {
            // THE LIVE ROOT ON PURPOSE, and it has to be spelled out since B3-SLICE-INVOICE:
            // invoiceService.idsMatching now switches to the invoice MIRROR under an open context,
            // and these two fixtures were saved at the wall clock, so their only mirror versions
            // open today and no date sixty days ago has anything to find. What is under test here
            // is the FILTER — that FilterPredicates.relative reads the as-of clock rather than the
            // wall clock — so it is asked of the root that still holds these rows. The end-to-end
            // version over the mirror is AsOfListTest#aRelativeDateFilterAsOfAPastDateIsRelative-
            // ToThatDate (B3).
            assertThat(queryExecutor.ids(Invoice.class, TableSchemas.INVOICES, lastWeek,
                    List.of(), 100))
                    .as("the window moved back with the reader")
                    .containsExactly(ancient.getId());
        }
    }

    /**
     * A list served as of a date cannot forget to say so, because PageResponse.of reads the thread
     * rather than taking a sixth argument — and map() carries it, without which every service that
     * maps a page loses the one field that says the page is historical (B3).
     */
    @Test
    void everyPageSaysWhichDateItWasAnsweredAsOf() {
        customer("Acme Ltd");

        PageResponse<CustomerDtos.CustomerDto> live = customerService.page(customers());
        assertThat(live.asOf()).isNull();

        LocalDate asked = today.minusDays(9);
        PageResponse<CustomerDtos.CustomerDto> past;
        try (AsOfContext.Handle h = AsOfContext.open(asked)) {
            past = customerService.page(customers());
        }
        AsOfInfo info = past.asOf();
        assertThat(info).isNotNull();
        assertThat(info.date()).isEqualTo(asked.toString());
        assertThat(info.exact()).isTrue();
        assertThat(info.origin()).isEqualTo(AsOfContext.ORIGIN_RECONSTRUCTED);
        // Fixed, and on the wire: what is reconstructed is RECORDS. Rights, privileges and
        // customer-login identity are always today's, and the contract says so here rather than
        // leaving it to be assumed.
        assertThat(info.appliesTo()).isEqualTo("records");
        assertThat(info.floor()).isNull();
        assertThat(info.notes()).isEmpty();

        assertThat(past.map(c -> c.name()).asOf()).as("map carries it").isEqualTo(info);
    }

    /** A downgraded answer says so once, however many tables reported drift (B3). */
    @Test
    void markingAnAnswerInexactAddsItsReasonAndNeverFlipsBack() {
        try (AsOfContext.Handle h = AsOfContext.open(today.minusDays(5))) {
            AsOfContext.markInexact("invoices held a drifted row");
            AsOfContext.markInexact("invoices held a drifted row");
            AsOfContext.markInexact("payments held a drifted row");
            AsOfContext.markOmittedDeleted(3);

            AsOfInfo info = AsOfContext.info();
            assertThat(info.exact()).isFalse();
            assertThat(info.notes()).containsExactly(
                    "invoices held a drifted row", "payments held a drifted row");
            assertThat(info.omittedDeleted()).isEqualTo(3);
        }
        // Outside a context it is a no-op rather than a null pointer: a background sweep that
        // reports drift is not answering anybody's as-of read.
        AsOfContext.markInexact("nobody is listening");
        assertThat(AsOfContext.info()).isNull();
    }

    /**
     * Which approvals were outstanding THEN, from B2's already interval-shaped pending_changes and
     * not from a mirror table of its own — half of the PRD clause this part has to deliver. The
     * coalesce onto the OPEN sentinel is the part worth testing rather than reasoning about: an
     * undecided change and one decided after the date asked for have to answer identically (B2, B3).
     */
    @Test
    void outstandingApprovalsAsOfADateAreTheOnesRaisedByThenAndNotYetDecided() {
        Customer acme = customer("Acme Ltd");
        held(acme, 1L, today.minusDays(30), today.minusDays(20));   // decided before
        held(acme, 2L, today.minusDays(20), today.minusDays(1));    // decided after
        held(acme, 3L, today.minusDays(3), null);                   // still waiting
        held(acme, 4L, today.minusDays(20), null);                  // still waiting, older

        assertThat(outstandingAt(today.minusDays(10))).isEqualTo(2);
        assertThat(outstandingAt(today)).isEqualTo(2);
        assertThat(outstandingAt(today.minusDays(25))).isEqualTo(1);
        assertThat(outstandingAt(today.minusDays(40))).isZero();

        assertThat(AsOf.OPEN).isEqualTo(Instant.parse("9999-12-31T00:00:00Z"));
    }

    // ---------------------------------------------------------------- fixtures

    private TableQuery customers() {
        return TableQuery.parse(TableSchemas.CUSTOMERS, 0, 50, "name,asc", List.of());
    }

    private static List<String> names(PageResponse<CustomerDtos.CustomerDto> page) {
        return page.content().stream().map(CustomerDtos.CustomerDto::name).toList();
    }

    /** A cashier (SCOPE_OVERRIDE, so no POC book narrows the list) who works in one branch only. */
    private User narrowedTo(Region only) {
        User u = user("wendy.west", "CASHIER");
        revokeRegionGrants(u);
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(u.getId()).regionId(only.getId()).right(RegionRight.VIEW).build());
        return u;
    }

    /**
     * A customer sitting where it sits now, with the placement ledger written by hand.
     * IntegrationTestBase.customer(name) saves through the repository and writes NO placement row —
     * only CustomerService.create and the schema upgrade do — so an as-of read of a fixture
     * customer sees nothing until its placements exist (B3).
     */
    private Customer placed(String name, Region now, List<CustomerRegionHistory> placements) {
        Customer c = customerRepository.save(Customer.builder().name(name).region(now).build());
        for (CustomerRegionHistory p : placements) {
            p.setCustomerId(c.getId());
            customerRegionHistoryRepository.save(p);
        }
        return c;
    }

    private static CustomerRegionHistory placement(Region region, LocalDate from, LocalDate to) {
        return CustomerRegionHistory.builder()
                .regionId(region.getId()).validFrom(from).validTo(to).build();
    }

    private Invoice invoiceOn(Customer c, Product p, User salesPoc, LocalDate date) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(),
                date.atStartOfDay(ZoneOffset.UTC).toInstant(), null, null, null, salesPoc.getId(),
                List.of(new InvoiceDtos.LineInput(p.getId(), 1, new BigDecimal("100.00")))));
    }

    private void held(Customer c, Long targetId, LocalDate requested, LocalDate decided) {
        pendingChangeRepository.saveAndFlush(PendingChange.builder()
                .action(PendingAction.INVOICE_CANCEL)
                .targetType(PendingTargetType.INVOICE)
                .targetId(targetId)
                .customerId(c.getId())
                .regionId(c.getRegion().getId())
                .exposure(new BigDecimal("1000.00"))
                .thresholdApplied(new BigDecimal("100.00"))
                .payloadJson("{}")
                .summary("Cancel invoice " + targetId)
                .status(decided == null ? PendingChangeStatus.PENDING : PendingChangeStatus.APPROVED)
                .requestedByUserId(admin.getId())
                .requestedAt(requested.atStartOfDay(ZoneOffset.UTC).toInstant())
                .decidedAt(decided == null ? null : decided.atStartOfDay(ZoneOffset.UTC).toInstant())
                .build());
    }

    private long outstandingAt(LocalDate date) {
        Instant t = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
        return queryExecutor.count(PendingChange.class, ApprovalSchemas.APPROVALS,
                TableQuery.parseUnpaged(ApprovalSchemas.APPROVALS, null, List.of()),
                List.of(AsOf.outstandingAt(t)));
    }
}
