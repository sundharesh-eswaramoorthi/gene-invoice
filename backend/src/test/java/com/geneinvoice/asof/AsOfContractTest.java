package com.geneinvoice.asof;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.asof.AsOfDates;
import com.geneinvoice.common.asof.AsOfEndpoints;
import com.geneinvoice.common.asof.AsOfFloor;
import com.geneinvoice.common.asof.AsOfInterceptor;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.common.query.ColumnType;
import com.geneinvoice.common.query.FilterOperator;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.FixedHistoryClock;
import com.geneinvoice.history.HistorySchemas;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.CustomerRegionHistory;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE CONTRACT ROUND THE EDGE OF THE FEATURE: what {@code ?asOf} does when it names today, when it
 * names the future, what a response that IS historical is obliged to say about itself — and, from
 * B3-CLOSE, the two architecture tests that make an endpoint which silently ignores {@code ?asOf}
 * impossible rather than merely absent (B3).
 *
 * <p>The first of these is the safety net every later slice inherits. "As of today equals live" is
 * a cheap claim to make and an easy one to break: the mirror and the live table are two different
 * stores answered by two different roots, and if the twin ever disagreed with its live schema
 * about a column — a filter that means something else, a label read from somewhere else, a row the
 * interval predicate drops — this is the test that says so, for every allowlisted list, tile,
 * figure and export at once, by comparing the two JSON documents rather than by asserting a list
 * of fields somebody chose.
 *
 * <p>The second is the compatibility claim in the other direction: a client whose clock is a day
 * ahead of the server's must not fall off the live path, so today AND the future are served live
 * and the envelope echoes {@code asOf: null} rather than reconstructing a present that has not
 * happened yet.
 *
 * <p>The third is the disclosure rule, and it covers BOTH origins — the ordinary reconstructed one
 * and the pre-floor seeded one, which until B3-SLICE-INVOICE had never been produced by a real
 * request at all: {@link TestHistoryFloor} is moved forward inside the test so the floor branch
 * runs through the interceptor exactly as it would on a two-week-old installation.
 *
 * <p>THE FOURTH IS THE COVERAGE PROOF, AND IT IS WHY THIS UNIT IS THE LAST BACKEND UNIT IN THE
 * PROGRAMME. {@code everyGetMappingIsEitherAsOfCapableOrDeclaredNotAsOfWithAReason} reflects over
 * the running {@code RequestMappingHandlerMapping} and asserts an EQUALITY, not a containment:
 * every GET this application declares is in {@code AS_OF_CAPABLE} or in {@code NOT_AS_OF}, never in
 * both and never in neither. Enumerating endpoints in a document proves nothing a year from now;
 * this fails the build the day somebody adds a read endpoint and does not decide what it means to
 * ask it about January.
 */
@Import({FixedHistoryClock.Config.class, TestHistoryFloor.Config.class})
class AsOfContractTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired AuditService auditService;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired FixedHistoryClock clock;
    @Autowired TestHistoryFloor floor;
    @Autowired ObjectProvider<AsOfFloor> floorPort;
    @Autowired JdbcTemplate jdbcTemplate;

    @Autowired @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    User admin;
    Customer acme;
    Product widget;
    LocalDate today;

    /**
     * The list endpoint behind each mirrored table, so the per-column gate can drive a REAL request
     * for every column of every twin. Asserted against {@link HistorySchemas#entities()} rather
     * than trusted: a seventh twin with no line here fails the build instead of quietly not being
     * checked, which is the difference between a gate and a decoration (B3).
     */
    private static final Map<String, String> LIST_URL = Map.of(
            "invoices", "/api/invoices",
            "customers", "/api/customers",
            "payments", "/api/payments",
            "promises", "/api/promises",
            "disputes", "/api/disputes",
            "tasks", "/api/tasks");

    /**
     * The six CSV exports. B3-RULES added a seventh non-GET pair to the allowlist — the rule
     * backtest — so these are no longer the whole of it; the rule that holds is that a capable
     * non-GET writes nothing (B3).
     */
    private static final List<String> EXPORT_BASES =
            List.of("invoices", "customers", "payments", "promises", "disputes", "tasks");

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        widget = product("Widget", "100.00");
        acme = customer("Acme Ltd");
        today = LocalDate.now(ZoneOffset.UTC);
        // THE OPENING PLACEMENT, WRITTEN BY HAND BECAUSE THE FIXTURE SHORTCUT SKIPS IT.
        // IntegrationTestBase.customer(name) saves through the repository; CustomerService.create
        // — the production path — also writes the opening customer_region_history row, and
        // RegionSchemaUpgrade backfilled one for every account that predates B1. So a real account
        // always has a placement in force, and without one the as-of region LABEL (read from the
        // ledger at T) is null while the live label (read from customers.region_id) is not.
        // Back-dated a year so every date these tests ask about is inside it (B1, B3).
        place(acme, today.minusDays(400));
        actAs(admin);
    }

    @AfterEach
    void putEverythingBack() {
        clock.release();
        floor.reset();
        AsOfContext.clear();
    }

    // ============================================================== the coverage proof

    /**
     * THE EQUALITY THAT MAKES SILENT IGNORING IMPOSSIBLE (B3).
     *
     * <p>Refusal is already the default — {@code AsOfInterceptor} 400s any pair not in
     * {@code AS_OF_CAPABLE}, so an unclassified endpoint cannot answer today's values under a
     * January banner. What refusal-by-default does NOT give is completeness: an endpoint can sit
     * outside both collections for ever, refusing a parameter nobody thought about. This asserts
     * that the union is EXACTLY the set of GET mappings, so the day a read endpoint is added its
     * author has to write down which side of the temporal boundary it is on, in one line, beside
     * every other such decision.
     *
     * <p>Three collections, not two, and the third is orthogonal by construction:
     * {@code HANDLER_OWNED} is the one pair Part A shipped {@code ?asOf} on before this feature
     * existed, and it is asserted to hold no GET — if it ever did, that GET would be in the union
     * and in neither of the two sides, and the equality here would be answering a different
     * question from the one it looks like it is answering.
     *
     * <p>Only mappings declared by this application are counted. A framework mapping — Spring
     * Boot's own {@code /error} handler — is not somebody's read endpoint and classifying it would
     * be noise, so the filter is the handler's package and it is stated rather than assumed.
     */
    @Test
    void everyGetMappingIsEitherAsOfCapableOrDeclaredNotAsOfWithAReason() {
        Set<String> declared = new TreeSet<>();
        for (Mapping mapping : mappings()) {
            if ("GET".equals(mapping.method())) declared.add(mapping.key());
        }
        assertThat(declared)
                .describedAs("reflection found no GET mappings, so this test proves nothing")
                .hasSizeGreaterThan(50);

        Set<String> capable = new TreeSet<>(AsOfEndpoints.AS_OF_CAPABLE.stream()
                .filter(key -> key.startsWith("GET ")).toList());
        Set<String> refused = new TreeSet<>(AsOfEndpoints.NOT_AS_OF.keySet());

        // Neither side may claim a pair the other one has: a pattern in both would read as
        // classified while behaving as whichever collection the interceptor consults first.
        assertThat(capable).doesNotContainAnyElementsOf(refused);

        Set<String> union = new TreeSet<>(capable);
        union.addAll(refused);
        assertThat(union)
                .describedAs("every GET must be as-of capable or declared not-as-of with a reason; "
                        + "add the new one to AsOfEndpoints (B3)")
                .isEqualTo(declared);

        // A reason that is blank, or a pattern that is not a GET, makes NOT_AS_OF a list of
        // strings rather than a record of decisions.
        AsOfEndpoints.NOT_AS_OF.forEach((pattern, why) -> {
            assertThat(pattern).describedAs("NOT_AS_OF is keyed 'METHOD pattern'").startsWith("GET /");
            assertThat(why).describedAs("%s is refused for no stated reason", pattern).isNotBlank();
        });

        // The third collection stays out of the GET key space, or the union above is not a union.
        assertThat(AsOfEndpoints.HANDLER_OWNED.keySet())
                .describedAs("a GET that owns its own asOf would be outside both sides of the line")
                .noneMatch(key -> key.startsWith("GET "));

        // And every non-GET pair that is capable today WRITES NOTHING: a mutating mapping is 400
        // by construction precisely because nothing names it here.
        //
        // B3-CLOSE LEFT THIS LINE AS AN ARGUMENT SOMEBODY HAD TO MAKE OUT LOUD, AND B3-RULES MADE
        // IT. AsOfEndpoints' javadoc said the only non-GET pairs that may ever be added are the
        // CSV export posts; B3-CONTEXT's handover said B3-RULES adds
        // POST /api/automation/rules/{id}/simulate. The two are reconciled here, and the rule that
        // survives is not "exports only" but "reads only": an export is a read that carries its
        // query in a body, and a simulate is a read that returns a computation over a population.
        // Neither writes a row. The endpoint that DOES write — POST .../replay, which opens a real
        // run and plans real steps — is deliberately absent and is driven with ?asOf by
        // aPostCarryingAsOfIsRejectedAcrossEveryMutatingMappingAndEveryBulk below, where it must
        // answer "The past is read only" like every other mutating mapping (B3).
        List<String> capableWrites = new ArrayList<>(
                EXPORT_BASES.stream().map(base -> "POST /api/" + base + "/export").toList());
        capableWrites.add("POST /api/automation/rules/{id}/simulate");
        assertThat(AsOfEndpoints.AS_OF_CAPABLE.stream().filter(key -> !key.startsWith("GET ")).toList())
                .describedAs("a non-GET joined the allowlist; it must WRITE NOTHING, say why in "
                        + "AsOfEndpoints' javadoc, and be added here (B3)")
                .containsExactlyInAnyOrderElementsOf(capableWrites);
    }

    /**
     * A RENAMED PATH MUST FAIL LOUDLY, NOT DROP QUIETLY OUT OF THE ALLOWLIST (B3).
     *
     * <p>{@code AS_OF_CAPABLE} is consulted by string. Rename {@code /api/promises} to
     * {@code /api/payment-promises} and the allowlist entry stops matching anything: every as-of
     * request against the renamed list starts answering 400, the equality above still holds
     * because the new pattern is simply unclassified — no, it does not, and that is the point of
     * having both tests. This one catches the other half: a pattern named here that resolves to no
     * mapping at all. All three collections are checked, because a stale {@code NOT_AS_OF} entry
     * hides the fact that its endpoint has gone just as effectively.
     */
    @Test
    void everyAsOfCapablePatternResolvesToARealMapping() {
        Set<String> declared = new TreeSet<>();
        mappings().forEach(mapping -> declared.add(mapping.key()));

        assertThat(declared).containsAll(AsOfEndpoints.AS_OF_CAPABLE);
        assertThat(declared).containsAll(AsOfEndpoints.NOT_AS_OF.keySet());
        assertThat(declared).containsAll(AsOfEndpoints.HANDLER_OWNED.keySet());
    }

    /**
     * EVERY COLUMN OF EVERY TWIN, DRIVEN AS A REAL REQUEST (B3).
     *
     * <p>{@code AsOfSchemaCheck} already refuses to boot when an as-of {@code ColumnDef} names an
     * attribute its mirror does not have. That is a check of the criteria PATH; it says nothing
     * about whether the column can be sorted by, filtered on, or read at all through the servlet.
     * This drives {@code ?asOf=…&sort=<col>,asc} and {@code ?asOf=…&filter=<col>:<op>:<sample>}
     * against the real list endpoint, once per column of all six tables, and asserts 200.
     *
     * <p>A ColumnDef added to a mirrored table next quarter therefore fails on the day it is
     * added, in the test that names the column, rather than the first time a user sorts by it
     * inside an as-of view. The operator is the FIRST the column's type supports — the one a
     * client is most likely to offer — and the sample value is chosen from the column's own
     * declared enum values where it has them, so an enum filter is a real constant and not a
     * string the coercion would reject before touching the mirror.
     *
     * <p>Both halves of the coverage are asserted at the end: every table with a twin was driven,
     * and every column was exercised at least one way. A column that is neither sortable nor
     * filterable would be exercised by nothing at all, and is named here rather than passed over.
     *
     * <p>The size of it, measured rather than guessed: 87 columns across the six twins, 142 real
     * as-of requests. The floor asserted below is deliberately loose, because a column added next
     * quarter should make this test do MORE work rather than make it red for arithmetic.
     */
    @Test
    void everyTableSchemaColumnCanBeResolvedAsOfADate() throws Exception {
        LocalDate asked = today.minusDays(1);
        raise(acme, today.minusDays(60), today.minusDays(30), "1000.00");

        assertThat(LIST_URL.keySet())
                .describedAs("a table gained an as-of twin and no list endpoint to drive it through")
                .isEqualTo(new LinkedHashSet<>(HistorySchemas.entities()));

        List<String> unexercised = new ArrayList<>();
        int columns = 0;
        for (String entity : TableSchemas.entities()) {
            String url = LIST_URL.get(entity);
            if (url == null) {
                assertThat(HistorySchemas.byEntity(entity))
                        .describedAs("%s has a twin but no URL above", entity).isNull();
                continue;
            }
            for (ColumnDef column : TableSchemas.byEntity(entity).ordered()) {
                columns++;
                boolean touched = false;
                if (column.sortable()) {
                    touched = true;
                    expect(get(url).param("asOf", asked.toString())
                                    .param("sort", column.name() + ",asc").with(as(admin)), 200,
                            "sorting " + entity + " by " + column.name() + " as of a date");
                }
                if (column.filterable()) {
                    touched = true;
                    expect(get(url).param("asOf", asked.toString())
                                    .param("filter", sampleFilter(column)).with(as(admin)), 200,
                            "filtering " + entity + " as of a date by " + sampleFilter(column));
                }
                if (!touched) unexercised.add(entity + "." + column.name());
            }
        }

        assertThat(unexercised)
                .describedAs("neither sortable nor filterable, so nothing here reads it as of a date")
                .isEmpty();
        assertThat(columns).describedAs("no columns were driven at all").isGreaterThan(50);
    }

    // ============================================================== the two refusals

    /**
     * EVERY read this application declares not-as-of really does refuse the parameter, and refuses
     * it with the sentence the error contract fixes — driven as a real request, one per pattern,
     * so the classification above is a behaviour and not a comment (B3).
     *
     * <p>Path variables are filled with 1. Nothing is looked up: the interceptor runs before the
     * handler, so a 400 here is the refusal and never a missing record, which is exactly the
     * property that makes an unclassified endpoint safe in the first place.
     */
    @Test
    void anEndpointThatCannotAnswerAsOfADateRejectsTheParameter() throws Exception {
        String asked = today.minusDays(10).toString();
        for (String key : new TreeSet<>(AsOfEndpoints.NOT_AS_OF.keySet())) {
            String url = concrete(key.substring("GET ".length()));
            String refusal = expect(get(url).param("asOf", asked).with(as(admin)), 400,
                    key + " must refuse ?asOf");
            assertThat(messageOf(refusal)).isEqualTo("This endpoint cannot be asked as of a date");
        }
        assertThat(AsOfEndpoints.NOT_AS_OF).hasSizeGreaterThan(20);
    }

    /**
     * THE PAST IS READ ONLY, asserted over every mutating mapping in the application rather than
     * over a handful somebody picked (B3).
     *
     * <p>Every POST, PUT, PATCH and DELETE this application declares — including all six
     * {@code /bulk} endpoints and {@code POST /api/promises/recompute} — is driven with
     * {@code ?asOf} and must answer 400 "The past is read only". The exceptions are stated in code:
     * the six CSV exports, which are reads that carry their query in a body, B3-RULES' rule
     * backtest, which is a read that returns a computation, and the one handler-owned pair Part A
     * shipped before this feature existed. {@code POST .../rules/{id}/replay} is NOT an exception
     * and is driven here: it opens a real run, so it refuses a date in the query string exactly as
     * every other write does, and takes the day it replays in its body instead (B3).
     *
     * <p>The bodies are empty and the content types are whatever each mapping consumes, because
     * nothing is being asked to succeed: the refusal happens in {@code preHandle}, before argument
     * resolution, which is why a missing required field cannot mask it.
     */
    @Test
    void aPostCarryingAsOfIsRejectedAcrossEveryMutatingMappingAndEveryBulk() throws Exception {
        String asked = today.minusDays(10).toString();
        int refused = 0;
        int bulk = 0;
        for (Mapping mapping : mappings()) {
            if ("GET".equals(mapping.method())) continue;
            if (AsOfEndpoints.AS_OF_CAPABLE.contains(mapping.key())) continue;
            if (AsOfEndpoints.HANDLER_OWNED.containsKey(mapping.key())) continue;

            String refusal = expect(mutating(mapping).param("asOf", asked).with(as(admin)), 400,
                    mapping.key() + " must refuse ?asOf");
            assertThat(messageOf(refusal))
                    .describedAs("%s", mapping.key()).isEqualTo("The past is read only");
            refused++;
            if (mapping.pattern().endsWith("/bulk")) bulk++;
        }

        assertThat(refused).describedAs("no mutating mapping was driven").isGreaterThan(50);
        // ELEVEN, NOT THE SIX B3'S COVERAGE ARGUMENT COUNTS. That count predates Part A and the
        // five bulk endpoints outside the money path (products, users, emails, inbox,
        // notifications, automation rules); the conclusion is unchanged and is now measured here
        // rather than quoted (B3).
        assertThat(bulk).describedAs("every /bulk POST is a write and must refuse a date").isEqualTo(11);
    }

    // ============================================================== as of today

    /**
     * THE SAFETY NET, over EVERY allowlisted list, tile, figure and export rather than over the
     * invoice family alone — and it is taken twice, because the two readings prove different
     * things (B3).
     *
     * <p>The WIRE reading, {@code ?asOf=<today>}, is literally the same code answering twice:
     * {@code AsOfDates.parse} short-circuits any date that is not in the past to the live path, so
     * today cannot drift. What it proves is that every allowlisted pattern is reachable, is a
     * pattern the mapping really has, and does not fall over on the parameter.
     *
     * <p>The MIRROR reading is the one with teeth. {@code AsOfContext.open(today)} round the
     * request makes the whole servlet path read the mirror at the end of today — the interceptor
     * steps aside because no {@code asOf} parameter is present, and every source switch in the
     * application takes its as-of branch. The two answers are then compared whole, with the as-of
     * stamp and the as-of locked chip removed because those are the two fields that are SUPPOSED
     * to differ. A twin that dropped a row, read a label from the wrong place or filtered on a
     * different column shows up here as a diff, for every family at once.
     *
     * <p>{@code GET /api/audit} is allowlisted and is deliberately NOT in this sweep: its as-of
     * answer is not supposed to equal its live answer, because the fabricated entries are
     * suppressed. {@code theAuditTimelineAsOfADateIsTruncatedAndShowsNoDerivedEntries} is where
     * that endpoint is proved, and the exclusion is by name so a second exclusion cannot creep in.
     */
    @Test
    void asOfTodayReturnsExactlyWhatALiveReadReturnsForEveryAllowlistedListTileFigureAndExport()
            throws Exception {
        Invoice invoice = raise(acme, today.minusDays(60), today.minusDays(30), "1000.00");
        pay(acme, invoice, "400.00", today.minusDays(20));
        raise(acme, today.minusDays(5), today.plusDays(25), "250.00");

        List<String> swept = new ArrayList<>();
        for (String key : new TreeSet<>(AsOfEndpoints.AS_OF_CAPABLE)) {
            if (!key.startsWith("GET ")) continue;
            String pattern = key.substring("GET ".length());
            if (pattern.contains("{")) continue;              // single records, covered by their own slices
            if ("/api/audit".equals(pattern)) continue;       // see the javadoc: it must differ
            swept.add(pattern);

            JsonNode live = tree(body(get(pattern).with(as(admin))));
            assertThat(tree(body(get(pattern).param("asOf", today.toString()).with(as(admin)))))
                    .describedAs("%s?asOf=<today> on the wire", pattern).isEqualTo(live);
            assertThat(tree(asOfBody(pattern)))
                    .describedAs("%s read off the mirror at today", pattern).isEqualTo(live);
        }

        for (String base : EXPORT_BASES) {
            String url = "/api/" + base + "/export";
            String live = csv(url, null);
            assertThat(csv(url, today)).describedAs("%s?asOf=<today> on the wire", url)
                    .isEqualTo(live);
            assertThat(withoutCaveat(asOfCsv(url)))
                    .describedAs("%s read off the mirror at today", url).isEqualTo(live);
            swept.add(url);
        }

        // Not vacuous, in both directions: the sweep really covered every family, and the
        // documents it compared have content in them.
        assertThat(swept).hasSize(24);
        assertThat(tree(body(get("/api/invoices").with(as(admin)))).get("totalElements").asInt())
                .isEqualTo(2);
    }

    /**
     * Four endpoints of the invoice family, each asked twice — once plainly and once with
     * {@code ?asOf=<today>} — and the two answers compared whole, the single record included.
     *
     * <p>Kept beside the sweep above rather than folded into it because it is the only place the
     * SINGLE RECORD reading is compared: {@code GET /api/invoices/{id}} needs an id, so a sweep
     * driven from patterns cannot reach it.
     */
    @Test
    void asOfTodayReturnsExactlyWhatALiveReadReturnsForEveryInvoiceEndpoint() throws Exception {
        Invoice invoice = raise(acme, today.minusDays(60), today.minusDays(30), "1000.00");
        pay(acme, invoice, "400.00", today.minusDays(20));
        raise(acme, today.minusDays(5), today.plusDays(25), "250.00");

        assertThat(json(get("/api/invoices"), null))
                .isEqualTo(json(get("/api/invoices"), today));
        assertThat(json(get("/api/invoices/summary"), null))
                .isEqualTo(json(get("/api/invoices/summary"), today));
        assertThat(json(get("/api/invoices/" + invoice.getId()), null))
                .isEqualTo(json(get("/api/invoices/" + invoice.getId()), today));
        assertThat(csv("/api/invoices/export", null)).isEqualTo(csv("/api/invoices/export", today));

        // Not vacuous: the documents being compared have content in them.
        assertThat(json(get("/api/invoices"), today).get("totalElements").asInt()).isEqualTo(2);
    }

    /**
     * A date at or after today is served LIVE and the envelope says {@code asOf: null} — never a
     * reconstruction of a present that has not happened. The clock skew case is the reason: a
     * browser a day ahead of the server would otherwise drop off the live path silently, which is
     * worse than any answer it could give (B3).
     */
    @Test
    void asOfAFutureDateIsServedLiveAndEchoedAsNull() throws Exception {
        raise(acme, today.minusDays(60), today.minusDays(30), "1000.00");

        mockMvc.perform(get("/api/invoices").param("asOf", today.plusDays(30).toString())
                        .with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.asOf").doesNotExist())
                .andExpect(jsonPath("$.lockedFilters").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.startsWith("asOf:")))));

        mockMvc.perform(get("/api/invoices").param("asOf", today.toString()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").doesNotExist());
    }

    /**
     * A list served as of a date cannot forget to say so, and it says more than the date: whether
     * the answer is exact, where it came from, what it applies to, and how many records it could
     * not show at all.
     *
     * <p>BOTH ORIGINS ARE EXERCISED HERE. Above the floor the answer is RECONSTRUCTED and exact
     * with no notes. Below it — the floor is moved forward mid-test, which is what a two-week-old
     * installation looks like — it is SEEDED, not exact, and carries the standing caveat verbatim,
     * which the CSV then repeats inside the file. {@code omittedDeleted} is 0 and stays 0 in this
     * version: a record hard-deleted before the floor never got a mirror row, so there is nothing
     * left to count it with. That is a decision, it is on the wire, and the note above says what
     * it means (B3).
     */
    @Test
    void aListServedAsOfADateAlwaysSaysSo() throws Exception {
        LocalDate asOf = today.minusDays(40);
        raise(acme, today.minusDays(60), today.minusDays(30), "1000.00");

        mockMvc.perform(get("/api/invoices").param("asOf", asOf.toString()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf.date").value(asOf.toString()))
                .andExpect(jsonPath("$.asOf.floor").value(TestHistoryFloor.DEFAULT.toString()))
                .andExpect(jsonPath("$.asOf.exact").value(true))
                .andExpect(jsonPath("$.asOf.origin").value(AsOfContext.ORIGIN_RECONSTRUCTED))
                .andExpect(jsonPath("$.asOf.appliesTo").value(AsOfContext.APPLIES_TO))
                .andExpect(jsonPath("$.asOf.omittedDeleted").value(0))
                .andExpect(jsonPath("$.asOf.notes.length()").value(0))
                .andExpect(jsonPath("$.lockedFilters").value(
                        org.hamcrest.Matchers.hasItem("asOf:eq:" + asOf)));

        // The tiles and the single record say it too — a figure that did not disclose its date
        // beside a list that did would be the worst of the three answers.
        mockMvc.perform(get("/api/invoices/summary").param("asOf", asOf.toString()).with(as(admin)))
                .andExpect(status().isOk());

        // ---- and now the same request against an installation whose history starts last week.
        LocalDate floorDay = today.minusDays(7);
        floor.at(floorDay);

        mockMvc.perform(get("/api/invoices").param("asOf", asOf.toString()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf.date").value(asOf.toString()))
                .andExpect(jsonPath("$.asOf.floor").value(floorDay.toString()))
                .andExpect(jsonPath("$.asOf.exact").value(false))
                .andExpect(jsonPath("$.asOf.origin").value(AsOfContext.ORIGIN_SEEDED))
                .andExpect(jsonPath("$.asOf.notes[0]")
                        .value(AsOfDates.preFloorNote(asOf, floorDay)));

        assertThat(csv("/api/invoices/export", asOf))
                .as("the caveat travels inside the file, because a CSV outlives the banner")
                .contains("As of " + asOf)
                .contains("Some values are not exact.")
                .contains(AsOfDates.preFloorNote(asOf, floorDay));
    }

    // ============================================================== below the floor

    /**
     * BELOW THE FLOOR, THE VALUES ARE THE VALUES AS FIRST RECORDED, AND THE ANSWER SAYS SO (B3).
     *
     * <p>An invoice is raised for 1000 sixty days ago and 400 is paid against it twenty days ago,
     * so the mirror holds two versions of it. The floor is then moved to last week, which is what
     * an installation two weeks old looks like, and the list is asked for a date forty days back —
     * below the floor, and between the two versions.
     *
     * <p>The answer is the EARLIEST recorded version: balance 1000, not today's 600. That is the
     * contract's pre-floor promise read literally — "their values are the values as first
     * recorded" — and it is also, here, the honest reconstruction, because on a real installation
     * the earliest row a pre-floor date can reach IS the install-time seed row. What this test
     * cannot do is manufacture a real seed row; the floor is fabricated and the rows underneath it
     * are ordinary interval rows. That limit is recorded rather than glossed: what is proved is
     * that the pre-floor branch answers with the earliest version it has, discloses the origin, the
     * inexactness and the standing note on the wire, and repeats all three inside the CSV.
     */
    @Test
    void asOfBeforeTheFloorAnswersWithTheValuesAsFirstRecordedAndSaysSo() throws Exception {
        Invoice invoice = raise(acme, today.minusDays(60), today.minusDays(30), "1000.00");
        pay(acme, invoice, "400.00", today.minusDays(20));

        LocalDate floorDay = today.minusDays(7);
        LocalDate asked = today.minusDays(40);
        floor.at(floorDay);

        JsonNode page = tree(body(get("/api/invoices").param("asOf", asked.toString())
                .with(as(admin))));
        assertThat(page.get("totalElements").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(page.get("content").get(0).get("balance").asText()))
                .describedAs("the values as first recorded, not the balance it has today")
                .isEqualByComparingTo("1000.00");
        assertThat(new BigDecimal(page.get("content").get(0).get("paidAmount").asText()))
                .isEqualByComparingTo("0.00");

        JsonNode stamp = objectMapper.readTree(body(get("/api/invoices")
                .param("asOf", asked.toString()).with(as(admin)))).get("asOf");
        assertThat(stamp.get("origin").asText()).isEqualTo(AsOfContext.ORIGIN_SEEDED);
        assertThat(stamp.get("exact").asBoolean()).isFalse();
        assertThat(stamp.get("floor").asText()).isEqualTo(floorDay.toString());
        assertThat(stamp.get("notes").get(0).asText())
                .isEqualTo(AsOfDates.preFloorNote(asked, floorDay));

        // Not vacuous: today's answer really is different, so "as first recorded" is a claim with
        // something to be wrong about.
        assertThat(new BigDecimal(tree(body(get("/api/invoices").with(as(admin))))
                .get("content").get(0).get("balance").asText())).isEqualByComparingTo("600.00");
    }

    /**
     * EXISTENCE IS EXACT BEFORE THE FLOOR, and that half of the pre-floor promise is the half a
     * reader can rely on (B3).
     *
     * <p>The same invoice, the same fabricated floor, asked twice: once for a date BEFORE it was
     * raised and once for a date after. It is absent from the first answer and present in the
     * second — the interval predicate is doing the work, not the floor, which is precisely why the
     * contract can say existence is exact while values are not. Both answers still declare
     * themselves SEEDED and inexact, because the VALUES in the second one are not what was true
     * that day.
     */
    @Test
    void existenceIsExactBeforeTheFloor() throws Exception {
        raise(acme, today.minusDays(60), today.minusDays(30), "1000.00");
        floor.at(today.minusDays(7));

        JsonNode before = tree(body(get("/api/invoices")
                .param("asOf", today.minusDays(70).toString()).with(as(admin))));
        assertThat(before.get("totalElements").asInt())
                .describedAs("a record is not present before it existed, floor or no floor")
                .isZero();

        JsonNode after = tree(body(get("/api/invoices")
                .param("asOf", today.minusDays(40).toString()).with(as(admin))));
        assertThat(after.get("totalElements").asInt()).isEqualTo(1);

        // Both are still honest about being seeded: existence is exact, values are not.
        for (LocalDate asked : List.of(today.minusDays(70), today.minusDays(40))) {
            JsonNode stamp = objectMapper.readTree(body(get("/api/invoices")
                    .param("asOf", asked.toString()).with(as(admin)))).get("asOf");
            assertThat(stamp.get("origin").asText()).isEqualTo(AsOfContext.ORIGIN_SEEDED);
            assertThat(stamp.get("exact").asBoolean()).isFalse();
        }
    }

    /**
     * An operator who would rather have no number than a caveated one sets
     * {@code app.history.pre-floor=reject}, and a date below the floor is then a 400 that names
     * the floor instead of an answer that apologises for itself (B3).
     *
     * <p>DRIVEN THROUGH A REAL INTERCEPTOR AND NOT THROUGH A REAL DISPATCH, and the difference is
     * stated rather than hidden. The policy is read from configuration into a final field at
     * construction — deliberately, so a misspelled setting fails the boot rather than every
     * pre-floor request — which means exercising it over HTTP needs a second Spring context for
     * one assertion. Instead both policies are driven through the production {@link AsOfInterceptor}
     * itself, with the production floor port, the production allowlist and the production parser:
     * everything the servlet would do except the dispatch. What is NOT covered is that the
     * resulting exception maps to a 400 body, which is {@code GlobalExceptionHandler}'s one job and
     * is exercised by the other refusal tests in this class.
     */
    @Test
    void asOfBeforeTheFloorIsRejectedWhenThePreFloorPolicyIsReject() {
        LocalDate floorDay = today.minusDays(7);
        LocalDate asked = today.minusDays(40);
        floor.at(floorDay);

        MockHttpServletRequest asking = new MockHttpServletRequest("GET", "/api/invoices");
        asking.setParameter(AsOfInterceptor.PARAM, asked.toString());
        asking.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/invoices");

        AsOfInterceptor refusing = new AsOfInterceptor(floorPort, AsOfDates.POLICY_REJECT);
        assertThatThrownBy(() -> refusing.preHandle(asking, new MockHttpServletResponse(), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(asked.toString())
                .hasMessageContaining("before the history floor " + floorDay);
        assertThat(AsOfContext.isActive())
                .describedAs("a refused date must leave nothing open on the thread").isFalse();

        // The default policy answers the same request instead of refusing it, which is what makes
        // the refusal above a policy rather than a bug.
        AsOfInterceptor seeding = new AsOfInterceptor(floorPort, AsOfDates.POLICY_SEEDED);
        assertThat(seeding.preHandle(asking, new MockHttpServletResponse(), null)).isTrue();
        assertThat(AsOfContext.state().origin()).isEqualTo(AsOfContext.ORIGIN_SEEDED);
        AsOfContext.clear();

        // And a date ABOVE the floor is unaffected by the policy: refusing is about the floor, not
        // about as-of.
        MockHttpServletRequest recent = new MockHttpServletRequest("GET", "/api/invoices");
        recent.setParameter(AsOfInterceptor.PARAM, today.minusDays(1).toString());
        recent.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/invoices");
        assertThat(refusing.preHandle(recent, new MockHttpServletResponse(), null)).isTrue();
        assertThat(AsOfContext.state().origin()).isEqualTo(AsOfContext.ORIGIN_RECONSTRUCTED);
        AsOfContext.clear();
    }

    // ============================================================== the audit timeline

    /**
     * THE ONE AS-OF READ WITH NO MIRROR BEHIND IT, AND THE ONE WHERE AS-OF MEANS SUBTRACTION (B3).
     *
     * <p>{@code audit_logs} is append-only, so the trail as it stood on a date is the trail with
     * everything recorded after that instant removed. Two things are asserted and they are
     * different claims.
     *
     * <p>TRUNCATION: an entry written before the date survives, an entry written after it is gone.
     * The live answer holds both, which is what stops "the past has fewer rows" being true by
     * accident.
     *
     * <p>NO DERIVED ENTRIES, AND THIS IS THE ONE THAT MATTERS. {@code derived()} fabricates
     * CUSTOMER_CREATED and its siblings from the record as it stands NOW and stamps them with a
     * date in the past — here, the account's own {@code createdAt}. Under a date they are
     * suppressed ENTIRELY rather than truncated, because a fabricated entry that survives
     * truncation reads as contemporaneous evidence of a value nobody ever recorded. The fixture is
     * built so that the derived entry WOULD survive: the account's creation is back-dated well
     * before the date being asked about, so its absence is the suppression and not the clock.
     *
     * <p>THE TIMESTAMPS ARE WRITTEN BY HAND, AND THAT IS UNAVOIDABLE HERE. There is no clock seam
     * for the audit trail — {@code AuditLog.@PrePersist} reads the wall clock and nothing overrides
     * it — so a test run cannot produce an audit row dated last month through any production path.
     * The rows themselves ARE written by the production {@code AuditService.record}; only their
     * {@code createdAt} is moved afterwards, which is the same concession the region tests make for
     * {@code customer_region_history}.
     */
    @Test
    void theAuditTimelineAsOfADateIsTruncatedAndShowsNoDerivedEntries() throws Exception {
        // THE ACCOUNT WAS OPENED LONG AGO, so the entry DERIVED from it is dated before T and
        // would survive the truncation. That is what makes its absence below the suppression
        // rather than the clock: with the account's creation left at the wall clock, the derived
        // entry is dated today, truncation alone removes it, and the assertion proves nothing.
        // Verified by mutation — restoring derived() under a date left this test green until the
        // line below was added (B3).
        //
        // Through JDBC for the same reason the audit rows are: customers.created_at is
        // @Column(updatable = false), because when an account was opened is not editable.
        backdateCustomer(acme, instantOn(today.minusDays(90)));

        backdate(auditService.record("CUSTOMER", acme.getId(), "CUSTOMER_PHONE_CHANGED",
                Map.of("phone", "old"), Map.of("phone", "new"), admin.getId(), null, null),
                instantOn(today.minusDays(50)));
        backdate(auditService.record("CUSTOMER", acme.getId(), "CUSTOMER_EMAIL_CHANGED",
                Map.of("email", "old"), Map.of("email", "new"), admin.getId(), null, null),
                instantOn(today.minusDays(10)));

        String url = "/api/audit?entityType=CUSTOMER&entityId=" + acme.getId();
        JsonNode live = objectMapper.readTree(body(get(url).with(as(admin))));
        assertThat(actions(live)).contains("CUSTOMER_PHONE_CHANGED", "CUSTOMER_EMAIL_CHANGED");
        assertThat(derivedActions(live))
                .describedAs("the live trail must contain a fabricated entry, or the assertion "
                        + "below that the as-of trail contains none is vacuous")
                .contains("CUSTOMER_CREATED");

        JsonNode past = objectMapper.readTree(body(
                get(url + "&asOf=" + today.minusDays(30)).with(as(admin))));
        assertThat(actions(past))
                .describedAs("truncated at the date, in both directions")
                .containsExactly("CUSTOMER_PHONE_CHANGED");
        assertThat(derivedActions(past))
                .describedAs("a derived entry is fabricated from today's record and is never "
                        + "historical evidence")
                .isEmpty();
    }

    // ---------------------------------------------------------------- reflection

    private record Mapping(String method, String pattern, Set<MediaType> consumes) {
        String key() {
            return AsOfEndpoints.key(method, pattern);
        }
    }

    /**
     * Every mapping this application declares, as "METHOD pattern" pairs. Read off the RUNNING
     * handler mapping rather than off the source, because a pattern is what Spring matched and not
     * what somebody typed — which is the whole point of the allowlist being keyed on it.
     */
    private List<Mapping> mappings() {
        List<Mapping> out = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            // Spring Boot's own /error handler is not somebody's read endpoint, and classifying it
            // would put framework noise in a file that records product decisions.
            if (!handler.getBeanType().getName().startsWith("com.geneinvoice.")) return;
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            assertThat(methods)
                    .describedAs("%s declares no HTTP method, so it answers GET as well and cannot "
                            + "be classified", handler).isNotEmpty();
            for (RequestMethod method : methods) {
                for (String pattern : patternsOf(info)) {
                    out.add(new Mapping(method.name(), pattern,
                            info.getConsumesCondition().getConsumableMediaTypes()));
                }
            }
        });
        return out;
    }

    private static Set<String> patternsOf(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatternValues();
        }
        return info.getPatternsCondition().getPatterns();
    }

    /** A pattern with its variables filled. Nothing is looked up: the refusal precedes the handler. */
    private static String concrete(String pattern) {
        return pattern.replaceAll("\\{[^}]+}", "1");
    }

    /**
     * A request for a mutating mapping, carrying whatever that mapping consumes. A content type it
     * does not accept is a 415 raised during handler LOOKUP, before any interceptor runs, and would
     * silently pass for the wrong reason.
     */
    private MockHttpServletRequestBuilder mutating(Mapping mapping) {
        String url = concrete(mapping.pattern());
        if (mapping.consumes().stream().anyMatch(MediaType.MULTIPART_FORM_DATA::includes)) {
            return multipart(org.springframework.http.HttpMethod.valueOf(mapping.method()), url);
        }
        return request(org.springframework.http.HttpMethod.valueOf(mapping.method()), url)
                .contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    // ---------------------------------------------------------------- filters

    /** The first operator this column's type supports, with a value that operator can carry. */
    private static String sampleFilter(ColumnDef column) {
        FilterOperator op = column.type().operators().get(0);
        return column.name() + ":" + op.wire() + ":" + sample(column);
    }

    private static String sample(ColumnDef column) {
        if (column.type() == ColumnType.ENUM) {
            assertThat(column.enumValues())
                    .describedAs("%s is an ENUM column with no values to filter by", column.name())
                    .isNotEmpty();
            return column.enumValues().get(0);
        }
        return switch (column.type()) {
            case TEXT -> "a";
            case BOOLEAN -> "true";
            case DATE -> "2000-01-01";
            default -> "1";
        };
    }

    // ---------------------------------------------------------------- fixtures

    private static Instant noon(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant();
    }

    private static Instant instantOn(LocalDate day) {
        return noon(day);
    }

    private Invoice raise(Customer c, LocalDate on, LocalDate dueOn, String total) {
        clock.freezeAt(noon(on));
        int quantity = new BigDecimal(total).divide(new BigDecimal("100.00")).intValue();
        Invoice invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                c.getId(), noon(on), dueOn, PaymentTerm.CUSTOM, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), quantity,
                        new BigDecimal("100.00")))));
        clock.release();
        return invoice;
    }

    private void pay(Customer c, Invoice invoice, String amount, LocalDate on) {
        clock.freezeAt(noon(on));
        paymentService.record(new PaymentDtos.CreatePaymentRequest(c.getId(),
                new BigDecimal(amount), "CASH", null, List.of(invoice.getId()), admin.getId(),
                null));
        clock.release();
    }

    /**
     * Move an audit row's timestamp. The row itself is written by the production AuditService;
     * only WHEN it happened is moved, because there is no clock seam for the trail (B3).
     *
     * <p>Through JDBC and not through the repository, because {@code AuditLog.createdAt} is
     * {@code @Column(updatable = false)} — a trail row's timestamp is not editable, which is
     * exactly right for the product and is why this has to go round it. The round trip is asserted
     * rather than assumed, so a timezone difference between the raw bind and Hibernate's own would
     * fail here in the fixture instead of silently shifting the truncation boundary.
     */
    private void backdate(AuditLog entry, Instant when) {
        jdbcTemplate.update("update audit_logs set created_at = ? where id = ?",
                Timestamp.from(when), entry.getId());
        assertThat(auditLogRepository.findById(entry.getId()).orElseThrow().getCreatedAt())
                .describedAs("the back-dated timestamp did not survive the round trip")
                .isEqualTo(when);
    }

    /** When the account was opened, moved for the same reason and by the same route (B3). */
    private void backdateCustomer(Customer c, Instant when) {
        jdbcTemplate.update("update customers set created_at = ? where id = ?",
                Timestamp.from(when), c.getId());
        assertThat(customerRepository.findById(c.getId()).orElseThrow().getCreatedAt())
                .describedAs("the back-dated opening date did not survive the round trip")
                .isEqualTo(when);
    }

    /** The opening placement CustomerService.create writes and the fixture shortcut does not (B1). */
    private void place(Customer c, LocalDate from) {
        customerRegionHistoryRepository.saveAndFlush(CustomerRegionHistory.builder()
                .customerId(c.getId()).regionId(c.getRegion().getId()).validFrom(from).build());
    }

    private static List<String> actions(JsonNode timeline) {
        List<String> out = new ArrayList<>();
        timeline.forEach(entry -> out.add(entry.get("action").asText()));
        return out;
    }

    private static List<String> derivedActions(JsonNode timeline) {
        List<String> out = new ArrayList<>();
        timeline.forEach(entry -> {
            if (entry.get("derived").asBoolean()) out.add(entry.get("action").asText());
        });
        return out;
    }

    // ---------------------------------------------------------------- requests

    /**
     * Status asserted with the response body in the failure message, which a status matcher cannot
     * give — and the body handed back, so a caller that also cares WHAT was said does not have to
     * send the request twice.
     */
    private String expect(MockHttpServletRequestBuilder builder, int status, String what)
            throws Exception {
        MvcResult result = mockMvc.perform(builder).andReturn();
        String answered = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus())
                .describedAs("%s -> %s", what, answered).isEqualTo(status);
        return answered;
    }

    /** The one-line message a refusal carries, read off a response already in hand. */
    private String messageOf(String body) throws Exception {
        return objectMapper.readTree(body).get("message").asText();
    }

    private String body(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * One request answered off the MIRROR at today. The interceptor steps aside because no
     * {@code asOf} parameter is present, so the whole servlet path — every source switch, every
     * DTO factory, the envelope — runs its as-of branch. One request per open, because
     * afterCompletion clears the thread (B3-DASHBOARD's technique, reused).
     */
    private String asOfBody(String url) throws Exception {
        RequestPostProcessor principal = as(admin);
        try (AsOfContext.Handle handle = AsOfContext.open(today)) {
            return body(get(url).with(principal));
        }
    }

    private String asOfCsv(String url) throws Exception {
        RequestPostProcessor principal = as(admin);
        String request = json(new BulkDtos.BulkRequest("EXPORT", null, true, null, null, null));
        try (AsOfContext.Handle handle = AsOfContext.open(today)) {
            return body(post(url).with(principal)
                    .contentType(MediaType.APPLICATION_JSON).content(request));
        }
    }

    private JsonNode json(MockHttpServletRequestBuilder builder, LocalDate asOf) throws Exception {
        if (asOf != null) builder = builder.param("asOf", asOf.toString());
        return objectMapper.readTree(body(builder.with(as(admin))));
    }

    private String csv(String url, LocalDate asOf) throws Exception {
        String request = json(new BulkDtos.BulkRequest("EXPORT", null, true, null, null, null));
        var builder = post(url).with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content(request);
        if (asOf != null) builder = builder.param("asOf", asOf.toString());
        return body(builder);
    }

    /** A CSV without its leading caveat cell — the one row an as-of file has and a live one does not. */
    private static String withoutCaveat(String csv) {
        int firstLine = csv.indexOf('\n');
        return firstLine < 0 ? csv : csv.substring(firstLine + 1);
    }

    /**
     * The payload without the two fields that are SUPPOSED to differ between a live answer and the
     * same answer read off the mirror: the as-of stamp, and the locked chip that announces it.
     */
    private JsonNode tree(String body) throws Exception {
        JsonNode node = objectMapper.readTree(body);
        if (node instanceof ObjectNode object) {
            object.remove("asOf");
            JsonNode locked = object.get("lockedFilters");
            if (locked != null && locked.isArray()) {
                List<JsonNode> kept = new ArrayList<>();
                locked.forEach(chip -> {
                    if (!chip.asText().startsWith("asOf:")) kept.add(chip);
                });
                object.putArray("lockedFilters").addAll(kept);
            }
        }
        return node;
    }
}
