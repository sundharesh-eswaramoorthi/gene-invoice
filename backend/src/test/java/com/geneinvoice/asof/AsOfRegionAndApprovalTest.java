package com.geneinvoice.asof;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.approval.ApprovalDtos;
import com.geneinvoice.approval.ApprovalService;
import com.geneinvoice.approval.ApprovalThreshold;
import com.geneinvoice.approval.PendingAction;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.approval.PendingChangeStatus;
import com.geneinvoice.approval.PendingTargetType;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.asof.AsOfEndpoints;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.history.FixedHistoryClock;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.region.CustomerRegionHistory;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.task.TaskDtos;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.task.TaskService;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE OTHER FIVE FAMILIES, AND THE TWO HALVES OF THE PRD CLAUSE THAT ONLY THEY CAN PROVE (B3).
 *
 * <p>B3-SLICE-INVOICE made one list answerable as of a date. This class is about what the invoice
 * slice could not reach: the region a record belonged to THEN, read off R7's placement ledger
 * rather than off any mirror column; the approvals that were OUTSTANDING then, read off B2's
 * pending_changes as the interval it already is; the POC book as it stood then; and the four fat
 * reads — a customer's seats and money, a payment's allocations, a promise's coverage, a dispute's
 * target — that a mirror row cannot answer for itself because a mirror never walks to a live row
 * of a mirrored entity.
 *
 * <p>EVERY TIMELINE HERE IS REAL. Each version these tests read was written by the production
 * history writer from a real create, a real payment or a real edit, with {@link FixedHistoryClock}
 * holding the write-side clock at the day in question. Nothing inserts a {@code *History} row by
 * hand. The two things that ARE written by hand are the placement ledger (R7's rows carry their
 * own {@code validFrom}/{@code validTo} dates, so back-dating them is how the real table looks
 * after a year of moves) and, in three approval tests, the {@code pending_changes} rows whose
 * {@code requestedAt}/{@code decidedAt} have to be in the past.
 *
 * <p>{@link TestHistoryFloor} pushes the history floor back to 2000, so these answers are the
 * ordinary RECONSTRUCTED ones rather than the pre-floor SEEDED branch every test would otherwise
 * get — the floor a boot-time seed installs in a test run is today.
 *
 * <p>THE LOAD-BEARING ONES ARE MARKED IN THEIR OWN JAVADOC. Several tests here are regression pins
 * on the live path, and they say so rather than being counted as proofs of this unit.
 */
@Import({FixedHistoryClock.Config.class, TestHistoryFloor.Config.class})
class AsOfRegionAndApprovalTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired CustomerService customerService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired DisputeService disputeService;
    @Autowired TaskService taskService;
    @Autowired ApprovalService approvalService;
    @Autowired PocService pocService;
    @Autowired FixedHistoryClock clock;
    @Autowired TestHistoryFloor floor;

    User admin;
    /** A second administrator, because a maker may never approve their own change (B2). */
    User checker;
    Product widget;

    LocalDate today;
    LocalDate d60;
    LocalDate d40;
    LocalDate d30;
    LocalDate d20;
    LocalDate d10;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        checker = user("charlie.checker", "ADMIN");
        widget = product("Widget", "100.00");
        today = LocalDate.now(ZoneOffset.UTC);
        d60 = today.minusDays(60);
        d40 = today.minusDays(40);
        d30 = today.minusDays(30);
        d20 = today.minusDays(20);
        d10 = today.minusDays(10);
        actAs(admin);
    }

    /**
     * The clock and the floor are singletons in a cached context: a test that moved either and did
     * not put it back would date every later test's mirror rows at its own instant, or floor every
     * later answer at its own day (B3).
     */
    @AfterEach
    void putEverythingBack() {
        clock.release();
        floor.reset();
        AsOfContext.clear();
    }

    // ------------------------------------------------------------------ the region it was in then

    /**
     * THE FIRST HALF OF THE PRD CLAUSE, MADE TRUE OF A REAL REQUEST: "including which region a
     * record belonged to then". The account moved from WEST to the default branch twenty days ago;
     * an invoice raised before that renders under WEST on a page asked as of a date before the
     * move, and under the default branch on today's page. Same invoice, same row, two correct
     * answers (B1, B3).
     *
     * <p>The label comes from R7's customer_region_history and NOT from any column on
     * invoice_history — no mirror carries region_id, which is blueprint conflict 1 — so this is
     * also the proof that RegionPlacements is actually wired into the other five slices.
     */
    @Test
    void anInvoiceThatMovedRegionIsListedUnderTheRegionItWasInOnTheAsOfDate() throws Exception {
        Region west = region("WEST");
        // Filed in the default branch TODAY — which is where customers.region_id says it is — and
        // in WEST until twenty days ago, which is what the ledger says and what an as-of read has
        // to follow. The account row itself is never mutated here: Customer.setRegion is
        // package-private on purpose, because a move is RegionCustodyService's act and not a
        // setter's (B1).
        Customer moved = account("Moved Ltd", defaultRegion(), d60);
        place(moved, west, d60, d20);
        place(moved, defaultRegion(), d20, null);
        Invoice invoice = raise(moved, d40, d10, "1000.00");

        pay(moved, invoice, "100.00", d40);
        clock.freezeAt(noon(d40));
        promiseService.create(new PromiseDtos.CreatePromiseRequest(moved.getId(),
                new BigDecimal("900.00"), today.plusDays(20), admin.getId(), null,
                List.of(invoice.getId())));
        taskService.create(new TaskDtos.CreateTaskRequest(TaskEntityType.INVOICE, invoice.getId(),
                "Chase it", null, today.plusDays(5), List.of(admin.getId())));
        clock.release();
        actAs(admin);

        // ALL FOUR SLICES THAT CARRY A REGION LABEL, in one test, because each of them fills those
        // two slots from the placement ledger in its own method and a unit that wired three of the
        // four would look green everywhere else (B3).
        for (String url : List.of("/api/invoices", "/api/payments", "/api/promises", "/api/tasks")) {
            asOf(url, d30)
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].regionId").value(west.getId()))
                    .andExpect(jsonPath("$.content[0].regionName").value(west.getName()));
            live(url)
                    .andExpect(jsonPath("$.content[0].regionId").value(defaultRegion().getId()))
                    .andExpect(jsonPath("$.content[0].regionName").value(defaultRegion().getName()));
        }
        asOf("/api/invoices", d30).andExpect(jsonPath("$.content[0].id").value(invoice.getId()));
    }

    /**
     * B3-04's FIRST CLAUSE, over a real mirror for the first time: an as-of row is visible only if
     * the region it belonged to THEN is one the reader may read NOW. The account was somebody
     * else's in January, so a person who only works in WEST does not get to read its January —
     * even though the account is in WEST today and they read it happily on today's page (B1, B3).
     *
     * <p>LOAD-BEARING. Nothing else in this class fails if the region axis stops following the
     * reader into the past on a mirror root.
     */
    @Test
    void aRecordThatMovedIntoARegionICannotReadIsHiddenFromMyAsOfRead() throws Exception {
        Region west = region("WEST");
        User wendy = narrowedTo(west);
        Customer arrived = account("Arrived Last Week", west, d60);
        place(arrived, defaultRegion(), d60, d20);
        place(arrived, west, d20, null);

        assertThat(names(live("/api/customers", wendy)))
                .as("hers today").contains("Arrived Last Week");
        assertThat(names(asOf("/api/customers", d30, wendy)))
                .as("somebody else's then").doesNotContain("Arrived Last Week");

        // And the row really is there to be read: the wildcard holder sees it as of that date,
        // under the branch it was in then. Without this the test would pass on an empty mirror.
        assertThat(names(asOf("/api/customers", d30, admin))).contains("Arrived Last Week");
        asOf("/api/customers", d30, admin)
                .andExpect(jsonPath("$.content[?(@.name == 'Arrived Last Week')].regionId")
                        .value(org.hamcrest.Matchers.hasItem(defaultRegion().getId().intValue())));
    }

    /**
     * B3-04's SECOND CLAUSE, the leak guard, and the reason the rule has two halves: the rights
     * that bound an as-of answer are the rights the reader holds TODAY, and they bound it through
     * the region the record is in TODAY as well as the one it was in then. An account that was
     * hers in January and has since moved to a branch she holds nothing in is not readable —
     * otherwise reading the past would hand over the history a record accumulated inside a branch
     * where B1 says she holds nothing (B1, B3, contract a.1).
     *
     * <p>LOAD-BEARING. The escape B1 designed for it is the wildcard grant, and the second half of
     * this test is that escape working: the auditor sees exactly what she cannot.
     */
    @Test
    void aRegionIMayReadTodayBoundsAnAsOfListEvenForARegionIHeldThen() throws Exception {
        Region west = region("WEST");
        Region north = region("NORTH");
        User wendy = narrowedTo(west);
        Customer left = account("Left Last Week", north, d60);
        place(left, west, d60, d20);
        place(left, north, d20, null);

        assertThat(names(asOf("/api/customers", d30, wendy)))
                .as("it was hers then, and it is out of her reach now")
                .doesNotContain("Left Last Week");
        assertThat(names(live("/api/customers", wendy))).doesNotContain("Left Last Week");

        // The wildcard grant is B1's cross-region read right and it drops the second clause: the
        // auditor reads the January the branch manager may not.
        assertThat(names(asOf("/api/customers", d30, admin))).contains("Left Last Week");
    }

    // ------------------------------------------------------------------- the book I had then

    /**
     * THE POC BOOK AS OF A DATE, which is what B3-BOOKROOT's root-agnostic predicates were for:
     * the invoice was Sam's in January and is Pat's now, so Sam's January list has it and Sam's
     * list today does not. The book is THEN's; the privileges and region rights that judge it are
     * NOW's, which is contract a.1 and is why this test acts as Sam rather than as an auditor (B3).
     *
     * <p>LOAD-BEARING. It fails if {@code invoiceSource()}'s as-of branch drops the book, and it
     * fails if the book predicate goes back to walking {@code salesPoc.id} — which does not
     * resolve on a mirror root at all.
     */
    @Test
    void myBookAsOfAPastDateIsTheBookIHadThenJudgedWithTheRightsIHoldNow() throws Exception {
        User sam = user("sam.sales", com.geneinvoice.config.DataSeeder.ROLE_SALES_POC);
        User pat = user("pat.sales", com.geneinvoice.config.DataSeeder.ROLE_SALES_POC);
        Customer acme = account("Acme Ltd", defaultRegion(), d60);
        place(acme, defaultRegion(), d60, null);
        Invoice invoice = raise(acme, d40, d10, "1000.00", sam);

        clock.freezeAt(noon(d20));
        actAs(admin);
        invoiceService.reassignSalesPoc(invoice.getId(), pat.getId());
        clock.release();

        assertThat(ids(asOf("/api/invoices", d30, sam)))
                .as("it was Sam's in January").containsExactly(invoice.getId().intValue());
        assertThat(ids(live("/api/invoices", sam))).as("and is not now").isEmpty();
        assertThat(ids(live("/api/invoices", pat))).containsExactly(invoice.getId().intValue());
        assertThat(ids(asOf("/api/invoices", d30, pat)))
                .as("and was not Pat's then").isEmpty();
    }

    /**
     * THE SEATS THAT WERE HELD THEN. "Who was the primary collection POC on 31 January" is a
     * question the live table cannot answer at all — a vacated seat leaves no row behind it — so
     * GET /api/customers/{id}/pocs reads the seat mirror under {@code ?asOf} (B3).
     *
     * <p>LOAD-BEARING for the first of the three plumbed reads.
     */
    @Test
    void theCustomerPocsAsOfAPastDateAreTheSeatsThatWereHeldThen() throws Exception {
        Customer acme = account("Acme Ltd", defaultRegion(), d60);
        place(acme, defaultRegion(), d60, null);

        clock.freezeAt(noon(d40));
        var seat = pocService.add(acme.getId(), PocType.COLLECTION, admin.getId(), true);
        clock.release();

        clock.freezeAt(noon(d10));
        pocService.remove(acme.getId(), seat.getId());
        clock.release();

        mockMvc.perform(get("/api/customers/" + acme.getId() + "/pocs")
                        .param("asOf", d30.toString()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].pocType").value("COLLECTION"))
                .andExpect(jsonPath("$[0].primary").value(true))
                .andExpect(jsonPath("$[0].user.username").value("admin"));

        mockMvc.perform(get("/api/customers/" + acme.getId() + "/pocs").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // And the same seats on the customer row itself, which reads the same mirror through the
        // same repository method rather than a second spelling of the question.
        asOf("/api/customers", d30)
                .andExpect(jsonPath("$.content[?(@.name == 'Acme Ltd')].collectionPocs.length()")
                        .value(org.hamcrest.Matchers.hasItem(1)));
    }

    /**
     * A TOMBSTONE IS SERVED, NOT MERELY DISCLOSED. An account deleted this morning was a real
     * account in January and is listed as of January, with the values it had then; today it is
     * gone from both the list and the single-record GET (B3).
     *
     * <p>Deleting a customer is {@code alwaysChecked} maker-checker, so this also exercises the
     * whole B2 path end to end: the maker is refused with 202, the checker approves, and the
     * applier runs the real deletion (B2).
     */
    @Test
    void aDeletedCustomerIsStillListedAsOfBeforeItWasDeleted() throws Exception {
        Customer doomed = account("Doomed Ltd", defaultRegion(), d60);
        place(doomed, defaultRegion(), d60, null);

        mockMvc.perform(delete("/api/customers/" + doomed.getId()).with(as(admin)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("PENDING_APPROVAL"));
        approveTheOnlyWaitingChange();

        assertThat(customerRepository.findById(doomed.getId())).isEmpty();
        assertThat(names(live("/api/customers", admin))).doesNotContain("Doomed Ltd");
        mockMvc.perform(get("/api/customers/" + doomed.getId()).with(as(admin)))
                .andExpect(status().isNotFound());

        assertThat(names(asOf("/api/customers", d30, admin))).contains("Doomed Ltd");
        mockMvc.perform(get("/api/customers/" + doomed.getId())
                        .param("asOf", d30.toString()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Doomed Ltd"));
    }

    /**
     * SORTING BY WHAT AN ACCOUNT OWED THEN, which is the one column on the customers twin whose
     * path is a correlated subquery over ANOTHER mirror. Both halves are asserted: the ORDER the
     * page comes back in, which is the sortable column B3-SCHEMAS re-rooted onto invoice_history,
     * and the NUMBER each row renders, which is this unit's batched sum. A build where only one of
     * the two moved would sort a page by January and print today beside it (B3).
     *
     * <p>LOAD-BEARING.
     */
    @Test
    void sortingByOutstandingAsOfADateUsesThatDatesInvoices() throws Exception {
        Customer big = account("Big Ltd", defaultRegion(), d60);
        Customer small = account("Small Ltd", defaultRegion(), d60);
        place(big, defaultRegion(), d60, null);
        place(small, defaultRegion(), d60, null);
        Invoice bigInvoice = raise(big, d40, d10, "1000.00");
        raise(small, d40, d10, "500.00");
        pay(big, bigInvoice, "900.00", d10);
        // A SECOND VERSION of one account, so that "the page counts accounts and not edits" is
        // under test here too: without AsOf.at(T) leading the scope list, an as-of page over
        // customer_history returns every version of every row and this list comes back with three
        // entries rather than two (B3).
        clock.freezeAt(noon(d20));
        big.setPhone("0800 000000");
        customerRepository.saveAndFlush(big);
        clock.release();

        JsonNode past = tree(asOfSorted("/api/customers", d30, "outstanding,desc"));
        assertThat(nameList(past)).containsExactly("Big Ltd", "Small Ltd");
        assertThat(tree(asOfSorted("/api/customers", d30, "outstanding,desc"))
                .get("totalElements").asInt()).isEqualTo(2);
        assertThat(past.get("content").get(0).get("outstanding").decimalValue())
                .isEqualByComparingTo("1000.00");
        assertThat(past.get("content").get(1).get("outstanding").decimalValue())
                .isEqualByComparingTo("500.00");

        JsonNode now = tree(liveSorted("/api/customers", "outstanding,desc"));
        assertThat(nameList(now)).containsExactly("Small Ltd", "Big Ltd");
        assertThat(now.get("content").get(1).get("outstanding").decimalValue())
                .isEqualByComparingTo("100.00");
    }

    // --------------------------------------------------------------- which approvals were waiting

    /**
     * THE SECOND HALF OF THE PRD CLAUSE: "and which approvals were outstanding" then. A change
     * raised in January and approved in February was still waiting on 31 January, and the live
     * queue has no way to say so — its status is APPROVED and its pending_key is long released.
     * pending_changes is its own history, so AsOf.outstandingAt reads it as the interval it already
     * is, with no mirror table anywhere (B2, B3).
     *
     * <p>LOAD-BEARING.
     */
    @Test
    void anApprovalDecidedAfterTheAsOfDateIsStillOutstanding() throws Exception {
        Customer acme = account("Acme Ltd", defaultRegion(), d60);
        place(acme, defaultRegion(), d60, null);
        Invoice invoice = raise(acme, d40, d10, "1000.00");
        Long change = held(acme, invoice.getId(), d40, d20);

        assertThat(ids(asOf("/api/approvals", d30)))
                .as("waiting on the thirtieth").containsExactly(change.intValue());
        assertThat(ids(asOf("/api/approvals", d10)))
                .as("decided by the tenth").isEmpty();

        mockMvc.perform(get("/api/approvals/" + change).param("asOf", d30.toString())
                        .with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(change));
        mockMvc.perform(get("/api/approvals/" + change).param("asOf", d10.toString())
                        .with(as(admin)))
                .andExpect(status().isNotFound());

        asOf("/api/approvals/summary", d30).andExpect(jsonPath("$.count").value(1));
        asOf("/api/approvals/summary", d10).andExpect(jsonPath("$.count").value(0));
    }

    /**
     * The other end of the same interval, and the one a naive "status was not PENDING yet" reading
     * would get wrong: a change raised in February was not waiting in January, and the January
     * answer must not contain it (B2, B3).
     */
    @Test
    void anApprovalRequestedAfterTheAsOfDateIsNotOutstanding() throws Exception {
        Customer acme = account("Acme Ltd", defaultRegion(), d60);
        place(acme, defaultRegion(), d60, null);
        Invoice invoice = raise(acme, d40, d10, "1000.00");
        Long change = held(acme, invoice.getId(), d20, null);

        assertThat(ids(asOf("/api/approvals", d30))).isEmpty();
        assertThat(ids(asOf("/api/approvals", d10))).containsExactly(change.intValue());
        assertThat(ids(live("/api/approvals", admin)))
                .as("still waiting today").containsExactly(change.intValue());
    }

    /**
     * THE RIDER THE WHOLE APPROVAL ANSWER RESTS ON, asserted for all four terminal transitions.
     * {@code AsOf.outstandingAt} coalesces a null {@code decided_at} onto the OPEN sentinel, so a
     * terminal row that never set it reports as outstanding FOR EVER — and the live answer would
     * never move to say so, which is what makes that failure silent. Approve, reject, withdraw and
     * supersede all go through a real service call here rather than being asserted from the
     * design (B2, B3 INTEGRATION).
     *
     * <p>LOAD-BEARING.
     */
    @Test
    void everyTerminalTransitionSetsDecidedAtSoNothingIsOutstandingForEver() throws Exception {
        Customer acme = account("Acme Ltd", defaultRegion(), d60);
        place(acme, defaultRegion(), d60, null);
        // The four invoices FIRST and the limit afterwards: raising a thousand-pound invoice under
        // a hundred-pound limit is itself a change that needs a second pair of eyes, so a fixture
        // built the other way round never gets as far as the cancel it is about (B2).
        List<Invoice> invoices = new ArrayList<>();
        for (int i = 0; i < 4; i++) invoices.add(raise(acme, d40, d10, "1000.00"));
        seedThreshold(defaultRegion().getId(), "100.00", true);

        Long approved = raiseHeldCancel(invoices.get(0));
        // A maker may never approve their own change, so the decision is the checker's (B2).
        actAs(checker);
        approvalService.approve(approved, null);
        actAs(admin);

        Long rejected = raiseHeldCancel(invoices.get(1));
        actAs(checker);
        approvalService.reject(rejected, new ApprovalDtos.RejectRequest("no"));
        actAs(admin);

        Long withdrawn = raiseHeldCancel(invoices.get(2));
        approvalService.withdraw(withdrawn, null);

        Long superseded = raiseHeldCancel(invoices.get(3));
        // A move supersedes every change still waiting on the account, because the record an
        // approver would be deciding on is not the record the change was composed against (B2, B1).
        mockMvc.perform(post("/api/customers/" + acme.getId() + "/region")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new com.geneinvoice.region.RegionCustodyService
                                .MoveRegionRequest(region("WEST").getId(), null, "reorganised")))
                        .with(as(admin)))
                .andExpect(status().isOk());

        assertThat(List.of(approved, rejected, withdrawn, superseded)).allSatisfy(id -> {
            PendingChange pc = pendingChangeRepository.findById(id).orElseThrow();
            assertThat(pc.getStatus()).isNotEqualTo(PendingChangeStatus.PENDING);
            assertThat(pc.getDecidedAt())
                    .as("%s left decided_at null and is outstanding for ever", pc.getStatus())
                    .isNotNull();
        });

        // And the answer that rider exists to protect: nothing is outstanding today.
        assertThat(ids(asOf("/api/approvals", today.minusDays(1)))).isEmpty();
    }

    /**
     * A PENDING CHANGE DOES NOT MUTATE THE RECORD, so the record's as-of values are the UN-APPLIED
     * ones and that falls out of the storage model rather than being special-cased. The cancel was
     * approved today; on a January page the invoice is still UNPAID, and on today's page it is
     * CANCELLED (B2, B3).
     */
    @Test
    void aChangeApprovedAfterTheAsOfDateHasNotTakenEffectInTheAsOfView() throws Exception {
        Customer acme = account("Acme Ltd", defaultRegion(), d60);
        place(acme, defaultRegion(), d60, null);
        Invoice invoice = raise(acme, d40, d10, "1000.00");
        seedThreshold(defaultRegion().getId(), "100.00", true);

        Long change = raiseHeldCancel(invoice);
        asOf("/api/invoices", d30)
                .andExpect(jsonPath("$.content[0].status").value("UNPAID"))
                .andExpect(jsonPath("$.content[0].approvalPending").value(false));

        actAs(checker);
        approvalService.approve(change, null);
        actAs(admin);

        live("/api/invoices").andExpect(jsonPath("$.content[0].status").value("CANCELLED"));
        asOf("/api/invoices", d30)
                .andExpect(jsonPath("$.content[0].status")
                        .value("UNPAID"))
                .andExpect(jsonPath("$.content[0].balance").value(1000.00));
    }

    /**
     * THE approvalPending FILTER, ASKED AS OF A DATE — the column B3-SCHEMAS made as-of aware
     * rather than twinning, driven here through a real request for the first time. "Which invoices
     * were awaiting a second pair of eyes at month end" is a question the sentinel column cannot
     * answer, because a decided change releases its key (B2, B3).
     *
     * <p>LOAD-BEARING: it is the only test that fails if ApprovalSchemas.existsOpenPending stops
     * switching to the decision log under a context.
     */
    @Test
    void filteringByPendingApprovalsAsOfAPastDateFindsTheRecordsAwaitingASecondPairOfEyesThen()
            throws Exception {
        Customer acme = account("Acme Ltd", defaultRegion(), d60);
        place(acme, defaultRegion(), d60, null);
        Invoice held = raise(acme, d40, d10, "1000.00");
        Invoice quiet = raise(acme, d40, d10, "200.00");
        held(acme, held.getId(), d40, d20);

        assertThat(ids(asOf("/api/invoices", d30, admin, "approvalPending:eq:true")))
                .containsExactly(held.getId().intValue());
        assertThat(ids(asOf("/api/invoices", d30, admin, "approvalPending:eq:false")))
                .containsExactly(quiet.getId().intValue());
        assertThat(ids(live("/api/invoices", admin, "approvalPending:eq:true")))
                .as("nothing is waiting today").isEmpty();

        asOf("/api/invoices/summary", d30).andExpect(jsonPath("$.awaitingApprovalCount").value(1));
        live("/api/invoices/summary").andExpect(jsonPath("$.awaitingApprovalCount").value(0));
    }

    // ------------------------------------------------------------------ the fat reads

    /**
     * A PAYMENT CARRIES THE INVOICES IT WAS APPLIED TO, and on a past page those invoices have to
     * be the invoices as they stood then — their balance, their status — not as they stand today.
     * The mirror maps a mirrored foreign key as a flat Long with nothing to walk to, so this is
     * the batched read this unit added rather than an association load (B3).
     *
     * <p>LOAD-BEARING for the payments slice: nothing else here reads payment_allocation_history.
     */
    @Test
    void anAsOfPaymentShowsTheInvoicesItWasAppliedToAsTheyStoodThen() throws Exception {
        Customer acme = account("Acme Ltd", defaultRegion(), d60);
        place(acme, defaultRegion(), d60, null);
        Invoice invoice = raise(acme, d40, d10, "1000.00");
        pay(acme, invoice, "400.00", d30);
        // A second payment settles the rest AFTER the date being asked about, so the invoice the
        // first payment names looks different then and now.
        pay(acme, invoice, "600.00", d10);

        asOf("/api/payments", d20)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].amount").value(400.00))
                .andExpect(jsonPath("$.content[0].invoices.length()").value(1))
                .andExpect(jsonPath("$.content[0].invoices[0].paidAmount").value(400.00))
                .andExpect(jsonPath("$.content[0].invoices[0].balance").value(600.00))
                .andExpect(jsonPath("$.content[0].invoices[0].status").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.content[0].invoices[0].allocatedAmount").value(400.00));

        live("/api/payments", admin, "id:eq:" + firstPaymentId())
                .andExpect(jsonPath("$.content[0].invoices[0].paidAmount").value(1000.00))
                .andExpect(jsonPath("$.content[0].invoices[0].status").value("FULLY_PAID"));
    }

    /**
     * WHAT AN ACCOUNT COULD SPEND THEN. GET /api/payments/credits/{customerId} is the one read in
     * the application that would otherwise MISSTATE money rather than merely omit a badge, and
     * under {@code ?asOf} all of it moves together: the balance the account held then, and whether
     * a change was waiting on it then (B2, B3).
     *
     * <p>LOAD-BEARING for the second of the three plumbed reads.
     */
    @Test
    void theCreditBalanceAsOfAPastDateIsTheBalanceTheAccountHeldThen() throws Exception {
        Customer acme = account("Acme Ltd", defaultRegion(), d60);
        place(acme, defaultRegion(), d60, null);
        // An unallocated payment becomes credit on the account.
        clock.freezeAt(noon(d40));
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("250.00"), "CASH", null, List.of(), admin.getId(), null));
        clock.release();

        Invoice invoice = raise(acme, d20, today.plusDays(10), "100.00");
        clock.freezeAt(noon(d10));
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("0.00").add(BigDecimal.ZERO), "CASH", null,
                List.of(invoice.getId()), admin.getId(), null));
        clock.release();

        mockMvc.perform(get("/api/payments/credits/" + acme.getId())
                        .param("asOf", d30.toString()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditBalance").value(250.00))
                .andExpect(jsonPath("$.approvalPendingOnCustomer").value(false));

        mockMvc.perform(get("/api/payments/credits/" + acme.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditBalance").value(150.00));
    }

    /**
     * A PROMISE COVERS THE INVOICES IT COVERED THEN, read off the two LINK mirrors: the promise
     * stopped covering the second invoice last week and still covered it in January. The link
     * mirror's {@code id} is the promise id and the other end is a flat Long beside it, which is
     * why both the rendered list and the {@code invoiceId} filter read one table with no join (B3).
     *
     * <p>LOAD-BEARING for the promises slice.
     */
    @Test
    void anAsOfPromiseCoversTheInvoicesItCoveredThen() throws Exception {
        Customer acme = account("Acme Ltd", defaultRegion(), d60);
        place(acme, defaultRegion(), d60, null);
        Invoice kept = raise(acme, d40, today.plusDays(10), "1000.00");
        Invoice dropped = raise(acme, d40, today.plusDays(10), "500.00");

        clock.freezeAt(noon(d40));
        PromiseDtos.PromiseDto promise = promiseService.create(new PromiseDtos.CreatePromiseRequest(
                acme.getId(), new BigDecimal("1500.00"), today.plusDays(20), admin.getId(), null,
                List.of(kept.getId(), dropped.getId())));
        clock.release();

        clock.freezeAt(noon(d10));
        promiseService.update(promise.id(), new PromiseDtos.UpdatePromiseRequest(
                new BigDecimal("1500.00"), today.plusDays(20), admin.getId(), null,
                List.of(kept.getId())));
        clock.release();

        asOf("/api/promises", d30)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].invoices.length()").value(2));
        live("/api/promises")
                .andExpect(jsonPath("$.content[0].invoices.length()").value(1));

        // The filter reads the same link mirror the rendered list does, so a promise that covered
        // an invoice THEN is findable by it then and not now.
        assertThat(ids(asOf("/api/promises", d30, admin, "invoiceId:eq:" + dropped.getId())))
                .containsExactly(promise.id().intValue());
        assertThat(ids(live("/api/promises", admin, "invoiceId:eq:" + dropped.getId()))).isEmpty();
    }

    /**
     * A DISPUTE AND A TASK AS OF A DATE, which is what R9's and A6's lists become here. Both are
     * thin rows over a flat customer_id, so the only thing that had to move for either was the
     * source switch — and the proof that it did is that a record raised after the date asked about
     * is in neither the list nor the single-record GET, which answers 404 and never 403 (B3,
     * AUTH-08).
     */
    @Test
    void aDisputeOrTaskRaisedAfterTheAsOfDateIsNotFoundAsOfBeforeIt() throws Exception {
        Customer acme = account("Acme Ltd", defaultRegion(), d60);
        place(acme, defaultRegion(), d60, null);
        Invoice invoice = raise(acme, d40, d10, "1000.00");

        clock.freezeAt(noon(d20));
        Dispute dispute = disputeService.openAs(admin.getId(), acme.getId(),
                DisputeTargetType.INVOICE, invoice.getId(), "Wrong amount");
        TaskDtos.TaskDto task = taskService.create(new TaskDtos.CreateTaskRequest(
                TaskEntityType.INVOICE, invoice.getId(), "Chase it", null, today.plusDays(5),
                List.of(admin.getId())));
        clock.release();

        assertThat(ids(asOf("/api/disputes", d30))).isEmpty();
        assertThat(ids(asOf("/api/tasks", d30))).isEmpty();
        assertThat(ids(live("/api/disputes", admin))).containsExactly(dispute.getId().intValue());
        assertThat(ids(live("/api/tasks", admin))).containsExactly(task.id().intValue());

        mockMvc.perform(get("/api/disputes/" + dispute.getId())
                        .param("asOf", d30.toString()).with(as(admin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tasks/" + task.id())
                        .param("asOf", d30.toString()).with(as(admin)))
                .andExpect(status().isNotFound());

        // And as of a date AFTER they were raised, both are there and the dispute describes the
        // invoice as it stood then rather than as it stands today.
        assertThat(ids(asOf("/api/disputes", d10))).containsExactly(dispute.getId().intValue());
        mockMvc.perform(get("/api/disputes/" + dispute.getId())
                        .param("asOf", d10.toString()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetAmount").value(1000.00))
                .andExpect(jsonPath("$.customerName").value("Acme Ltd"));
    }

    // ------------------------------------------------------------------ the wire

    /**
     * EVERY LIST THIS UNIT OPENED SAYS SO, in the two places the contract puts it: the envelope's
     * {@code asOf} block and a locked chip beside the book and region chips. A page that answered
     * from a mirror and did not say so is the one outcome the whole feature exists to prevent (B3).
     */
    @Test
    void everyListThisUnitOpenedSaysWhichDateItWasAnsweredAsOf() throws Exception {
        Customer acme = account("Acme Ltd", defaultRegion(), d60);
        place(acme, defaultRegion(), d60, null);

        for (String url : List.of("/api/customers", "/api/payments", "/api/promises",
                "/api/disputes", "/api/tasks", "/api/approvals")) {
            asOf(url, d30)
                    .andExpect(jsonPath("$.asOf.date").value(d30.toString()))
                    .andExpect(jsonPath("$.asOf.appliesTo").value("records"))
                    .andExpect(jsonPath("$.asOf.exact").value(true))
                    .andExpect(jsonPath("$.lockedFilters")
                            .value(org.hamcrest.Matchers.hasItem("asOf:eq:" + d30)))
                    .andExpect(jsonPath("$.asOf.origin").value("RECONSTRUCTED"));
            live(url, admin).andExpect(jsonPath("$.asOf").doesNotExist());
        }
    }

    /**
     * A CSV OUTLIVES THE BANNER THAT FRAMED IT, so the caveat travels inside the file and the date
     * travels in its name — for all five exports this unit opened, not only the invoice one (B3).
     */
    @Test
    void everyAsOfExportThisUnitOpenedCarriesTheCaveatAndTheDateInItsName() throws Exception {
        Customer acme = account("Acme Ltd", defaultRegion(), d60);
        place(acme, defaultRegion(), d60, null);
        raise(acme, d40, d10, "1000.00");

        record Export(String url, String base) {}
        for (Export export : List.of(
                new Export("/api/customers/export", "customers"),
                new Export("/api/payments/export", "payments"),
                new Export("/api/promises/export", "payment-promises"),
                new Export("/api/disputes/export", "disputes"),
                new Export("/api/tasks/export", "tasks"))) {
            var response = mockMvc.perform(post(export.url()).param("asOf", d30.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new BulkDtos.BulkRequest("EXPORT", null, true, null, null, null)))
                            .with(as(admin)))
                    .andExpect(status().isOk()).andReturn().getResponse();
            assertThat(response.getHeader("Content-Disposition"))
                    .contains(export.base() + "-as-of-" + d30 + ".csv");
            assertThat(response.getContentAsString())
                    .startsWith("\"As of " + d30 + " — this file shows history, not today.\"");

            var liveResponse = mockMvc.perform(post(export.url())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new BulkDtos.BulkRequest("EXPORT", null, true, null, null, null)))
                            .with(as(admin)))
                    .andExpect(status().isOk()).andReturn().getResponse();
            assertThat(liveResponse.getHeader("Content-Disposition"))
                    .contains(export.base() + ".csv");
            assertThat(liveResponse.getContentAsString()).doesNotContain("this file shows history");
        }
    }

    /**
     * THE ALLOWLIST ENTRIES THIS UNIT ADDED, ASSERTED VERBATIM — the convention B3-DASHBOARD set,
     * so that each unit's own entries are pinned by its own test and B3-CLOSE's reflective union
     * has something to disagree with rather than a set that quietly grew (B3).
     *
     * <p>The approvals EXPORT is asserted to be ABSENT on purpose. The unit registers the three
     * approval GETs and no fourth pattern, so a download of the January queue is a 400 — a decision
     * somebody should make deliberately rather than inherit.
     */
    @Test
    void theEndpointsThisUnitOpenedAreTheOnesTheAllowlistNames() {
        assertThat(AsOfEndpoints.AS_OF_CAPABLE).contains(
                "GET /api/customers", "GET /api/customers/summary", "GET /api/customers/{id}",
                "POST /api/customers/export", "GET /api/customers/{id}/pocs",
                "GET /api/payments", "GET /api/payments/summary", "GET /api/payments/{id}",
                "POST /api/payments/export", "GET /api/payments/credits/{customerId}",
                "GET /api/promises", "GET /api/promises/summary", "GET /api/promises/{id}",
                "POST /api/promises/export",
                "GET /api/disputes", "GET /api/disputes/{id}", "POST /api/disputes/export",
                "GET /api/tasks", "GET /api/tasks/summary", "GET /api/tasks/{id}",
                "POST /api/tasks/export",
                "GET /api/approvals", "GET /api/approvals/summary", "GET /api/approvals/{id}");
        assertThat(AsOfEndpoints.AS_OF_CAPABLE)
                .as("the approvals export is deliberately not capable")
                .doesNotContain("POST /api/approvals/export");
        assertThat(AsOfEndpoints.AS_OF_CAPABLE)
                .as("disputes have no /summary endpoint to register")
                .doesNotContain("GET /api/disputes/summary");
    }

    /**
     * THE LIVE PATH IS UNTOUCHED, measured rather than argued: a live customers list asks neither
     * mirror table nor the placement ledger, because {@code customerSource()} returns the
     * pre-existing triple and both {@code placeRegions} and {@code markDrift} return on
     * {@code isActive()} before doing any work. The as-of list is measured in the same breath so
     * that the absence means something (B3).
     */
    @Test
    void theLiveCustomerListAsksTheMirrorsAndThePlacementLedgerNothingAtAll() throws Exception {
        Customer acme = account("Acme Ltd", defaultRegion(), d60);
        place(acme, defaultRegion(), d60, null);
        raise(acme, d40, d10, "1000.00");

        List<String> liveSql = com.geneinvoice.CountingStatements.capture(() ->
                live("/api/customers").andExpect(jsonPath("$.totalElements").value(1)));
        assertThat(liveSql).anySatisfy(sql -> assertThat(sql).contains("from customers"));
        assertThat(liveSql).noneSatisfy(sql -> assertThat(sql).contains("customer_history"));
        assertThat(liveSql).noneSatisfy(sql -> assertThat(sql).contains("customer_poc_history"));
        assertThat(liveSql).noneSatisfy(sql -> assertThat(sql).contains("invoice_history"));
        assertThat(liveSql).noneSatisfy(sql -> assertThat(sql).contains("customer_region_history"));

        List<String> pastSql = com.geneinvoice.CountingStatements.capture(() ->
                asOf("/api/customers", d30).andExpect(jsonPath("$.totalElements").value(1)));
        assertThat(pastSql).as("and the as-of list really does read them")
                .anySatisfy(sql -> assertThat(sql).contains("customer_history"))
                .anySatisfy(sql -> assertThat(sql).contains("invoice_history"));
    }

    // ------------------------------------------------------------------ fixtures

    /** Noon of a day, so a version opened "on" a date is comfortably inside it at both ends. */
    private static Instant noon(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant();
    }

    /**
     * An account that already existed on {@code on}. The repository save runs in its own
     * transaction, so the history writer drains at its commit and dates the mirror row at the
     * frozen write-side clock — which is how a test gets a record with a real past without
     * inserting a *History row by hand (B3).
     */
    private Customer account(String name, Region region, LocalDate on) {
        clock.freezeAt(noon(on));
        Customer c = customerRepository.saveAndFlush(
                Customer.builder().name(name).region(region).build());
        clock.release();
        return c;
    }

    /**
     * A placement, written by hand. R7's rows carry their own validFrom/validTo DATES rather than
     * being stamped by a clock, so back-dating them is exactly what the real table looks like
     * after a year of moves — and IntegrationTestBase.customer() writes none at all, which is the
     * landmine B3-CONTEXT named (B1, B3).
     */
    private void place(Customer c, Region region, LocalDate from, LocalDate to) {
        customerRegionHistoryRepository.saveAndFlush(CustomerRegionHistory.builder()
                .customerId(c.getId()).regionId(region.getId()).validFrom(from).validTo(to).build());
    }

    private Invoice raise(Customer c, LocalDate on, LocalDate dueOn, String total) {
        return raise(c, on, dueOn, total, admin);
    }

    /** A real create through the real service, with the write-side clock held at that day. */
    private Invoice raise(Customer c, LocalDate on, LocalDate dueOn, String total, User salesPoc) {
        User was = currentUser();
        actAs(admin);
        clock.freezeAt(noon(on));
        int quantity = new BigDecimal(total).divide(new BigDecimal("100.00")).intValue();
        Invoice invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                c.getId(), noon(on), dueOn, PaymentTerm.CUSTOM, null, salesPoc.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), quantity,
                        new BigDecimal("100.00")))));
        clock.release();
        if (was != null) actAs(was);
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
     * A change raised on {@code requested} and decided on {@code decided} (null for still
     * waiting), written directly so the dates can be in the past. A decided change has released
     * its pending_key, which is why the live answer says nothing was waiting while the log still
     * says it was (B2, B3).
     */
    private Long held(Customer c, Long targetId, LocalDate requested, LocalDate decided) {
        return pendingChangeRepository.saveAndFlush(PendingChange.builder()
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
                .build()).getId();
    }

    /** A real held change, raised through the gate by a maker who cannot approve their own (B2). */
    private Long raiseHeldCancel(Invoice invoice) throws Exception {
        mockMvc.perform(post("/api/invoices/" + invoice.getId() + "/cancel").with(as(admin)));
        // spring-security-test clears the thread's SecurityContext after every MockMvc request, so
        // a service called DIRECTLY on the next line would find nobody logged in. Put the maker
        // back rather than leaving the next caller to discover it.
        actAs(admin);
        return pendingChangeRepository.findByTargetTypeAndTargetIdAndStatus(
                        PendingTargetType.INVOICE, invoice.getId(), PendingChangeStatus.PENDING)
                .stream().map(PendingChange::getId).findFirst().orElseThrow();
    }

    private void approveTheOnlyWaitingChange() throws Exception {
        PendingChange waiting = pendingChangeRepository.findAll().stream()
                .filter(pc -> pc.getStatus() == PendingChangeStatus.PENDING)
                .findFirst().orElseThrow();
        actAs(checker);
        approvalService.approve(waiting.getId(), null);
        actAs(admin);
    }

    /** A row written straight to the table, which is what a limit already in force looks like. */
    private void seedThreshold(Long regionId, String amount, boolean enabled) {
        ApprovalThreshold row = approvalThresholdRepository.findByRegionId(regionId)
                .orElseGet(() -> ApprovalThreshold.builder().regionId(regionId).build());
        row.setAmount(new BigDecimal(amount));
        row.setEnabled(enabled);
        approvalThresholdRepository.saveAndFlush(row);
    }

    /** A cashier (SCOPE_OVERRIDE, so no POC book narrows the list) who works in one branch only. */
    private User narrowedTo(Region only) {
        User u = user("wendy.west", "CASHIER");
        revokeRegionGrants(u);
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(u.getId()).regionId(only.getId()).right(RegionRight.VIEW).build());
        return u;
    }

    private Long firstPaymentId() {
        return paymentRepository.findAll().stream().map(p -> p.getId()).sorted().findFirst()
                .orElseThrow();
    }

    private User currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        if (auth == null) return null;
        return userRepository.findByUsername(auth.getName()).orElse(null);
    }

    // ------------------------------------------------------------------ requests

    private ResultActions asOf(String url, LocalDate date) throws Exception {
        return asOf(url, date, admin);
    }

    private ResultActions asOf(String url, LocalDate date, User who, String... filters)
            throws Exception {
        var request = get(url).param("asOf", date.toString()).with(as(who));
        for (String filter : filters) request = request.param("filter", filter);
        return mockMvc.perform(request).andExpect(status().isOk());
    }

    private ResultActions asOfSorted(String url, LocalDate date, String sort) throws Exception {
        return mockMvc.perform(get(url).param("asOf", date.toString()).param("sort", sort)
                .with(as(admin))).andExpect(status().isOk());
    }

    private ResultActions live(String url) throws Exception {
        return live(url, admin);
    }

    private ResultActions live(String url, User who, String... filters) throws Exception {
        var request = get(url).with(as(who));
        for (String filter : filters) request = request.param("filter", filter);
        return mockMvc.perform(request).andExpect(status().isOk());
    }

    private ResultActions liveSorted(String url, String sort) throws Exception {
        return mockMvc.perform(get(url).param("sort", sort).with(as(admin)))
                .andExpect(status().isOk());
    }

    private static JsonNode tree(ResultActions actions) throws Exception {
        return new ObjectMapper().readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static List<Integer> ids(ResultActions actions) throws Exception {
        List<Integer> ids = new ArrayList<>();
        tree(actions).get("content").forEach(row -> ids.add(row.get("id").intValue()));
        return ids;
    }

    private static List<String> names(ResultActions actions) throws Exception {
        return nameList(tree(actions));
    }

    private static List<String> nameList(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("content").forEach(row -> names.add(row.get("name").asText()));
        return names;
    }
}
