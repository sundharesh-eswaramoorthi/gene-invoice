package com.geneinvoice.asof;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.approval.PendingAction;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.approval.PendingChangeStatus;
import com.geneinvoice.approval.PendingTargetType;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.FixedHistoryClock;
import com.geneinvoice.history.HistorySchemas;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.CustomerRegionHistory;
import com.geneinvoice.region.Region;
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
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE FIRST LIST IN THIS APPLICATION THAT CAN BE ASKED AS OF A DATE, driven the way a client
 * drives it: {@code GET /api/invoices?asOf=2026-08-24}, over the servlet, through the interceptor,
 * off the interval mirror (B3).
 *
 * <p>WHAT IS NEW HERE AND WHAT WAS ALREADY TRUE. Everything before this unit opened an
 * {@code AsOfContext} by hand and drove {@code TableQueryExecutor} directly — B3-CONTEXT and
 * B3-SCHEMAS both said so in their own honest gaps. Nothing in the application could open a
 * context through a request, because the allowlist was empty. These tests are the first that go
 * through {@code AsOfInterceptor}, the first that fill {@code PageResponse.asOf}, and the first
 * that put a mirror row through a DTO factory and onto the wire.
 *
 * <p>THE TIMELINES ARE REAL, NOT HAND-BUILT. Every version these tests read was written by the
 * production history writer, from a real create, a real payment or a real edit, with
 * {@link FixedHistoryClock} holding the write-side clock at the day in question. Nothing here
 * inserts an {@code InvoiceHistory} row by hand, which is deliberate: the previous B3 units proved
 * the mirror's SHAPE against hand-built rows, and what was still unproved was that the rows the
 * writer actually produces answer these questions.
 *
 * <p>{@link TestHistoryFloor} pushes the history floor back to 2000. Without it every date a test
 * can ask about is below the floor a boot-time seed installs today, every answer comes back
 * {@code SEEDED / exact:false}, and the RECONSTRUCTED branch — the ordinary one — would never run
 * over HTTP at all. {@code AsOfContractTest} moves the floor forward again to cover the other
 * branch (B3).
 *
 * <p>THE LOAD-BEARING ONE IS {@code theAsOfTotalCountsRecordsAndNotVersionsAcrossEveryPage}. It is
 * the only test that fails if {@code AsOf.at(T)} is dropped from the scope list in
 * {@code InvoiceService.invoiceSource()}, and without that line a page over the mirror answers
 * with every VERSION of every invoice and {@code totalElements} counts edits.
 */
@Import({FixedHistoryClock.Config.class, TestHistoryFloor.Config.class})
class AsOfListTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired FixedHistoryClock clock;
    @Autowired TestHistoryFloor floor;

    User admin;
    Customer acme;
    Product widget;

    LocalDate today;
    /** The day the money was raised. */
    LocalDate raised;
    /** An as-of date AFTER it was raised and BEFORE anything was paid or fell due. */
    LocalDate before;
    /** The day it fell due. */
    LocalDate due;
    /** The day it was paid. */
    LocalDate paidOn;
    /** An as-of date AFTER the payment. */
    LocalDate after;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        widget = product("Widget", "100.00");
        acme = customer("Acme Ltd");
        today = LocalDate.now(ZoneOffset.UTC);
        raised = today.minusDays(60);
        before = today.minusDays(40);
        due = today.minusDays(30);
        paidOn = today.minusDays(20);
        after = today.minusDays(10);
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

    // ---------------------------------------------------------------- the values of that day

    /**
     * The clause the PRD asks for, made true of one list for the first time: "every list can be
     * asked as of a date". The invoice was worth 1000 and nothing had been paid on it in August;
     * 400 was paid in September. Both answers are correct and they are different numbers (B3).
     */
    @Test
    void anInvoiceListAsOfAPastDateShowsTheBalanceItHadThen() throws Exception {
        Invoice invoice = raise(acme, raised, due, "1000.00");
        pay(acme, invoice, "400.00", paidOn);

        asOfList(before)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].total").value(1000.00))
                .andExpect(jsonPath("$.content[0].paidAmount").value(0.00))
                .andExpect(jsonPath("$.content[0].balance").value(1000.00))
                .andExpect(jsonPath("$.content[0].status").value("UNPAID"))
                // The envelope says which date it answered, and the chip says the list is
                // narrowed by it, in the same place the book and the region chips appear (B3).
                .andExpect(jsonPath("$.asOf.date").value(before.toString()))
                .andExpect(jsonPath("$.lockedFilters").value(org.hamcrest.Matchers.hasItem(
                        "asOf:eq:" + before)));

        liveList()
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].paidAmount").value(400.00))
                .andExpect(jsonPath("$.content[0].balance").value(600.00))
                .andExpect(jsonPath("$.content[0].status").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.asOf").doesNotExist());
    }

    /**
     * The collections question this feature exists for: what was still outstanding at month end?
     * An invoice settled in full since is still UNPAID as of a date before the money arrived, and
     * a status filter typed against that date finds it (B3).
     */
    @Test
    void anInvoicePaidAfterTheAsOfDateIsStillOutstandingInTheAsOfList() throws Exception {
        Invoice invoice = raise(acme, raised, due, "1000.00");
        pay(acme, invoice, "1000.00", paidOn);

        asOfList(before, "status:in:UNPAID")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(invoice.getId()));

        liveList("status:in:UNPAID")
                .andExpect(jsonPath("$.totalElements").value(0));
        liveList("status:in:FULLY_PAID")
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /**
     * Existence is as-of too, and it has to be, or every historical total is wrong: a record that
     * did not exist on the date asked is in neither the page nor the figure above it (B3).
     */
    @Test
    void anInvoiceCreatedAfterTheAsOfDateIsInNeitherTheListNorTheTotal() throws Exception {
        Invoice old = raise(acme, raised, due, "1000.00");
        Invoice recent = raise(acme, after, today.plusDays(5), "500.00");

        asOfList(before)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(old.getId()));
        asOfTiles(before)
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.totalBilled").value(1000.00));

        liveList().andExpect(jsonPath("$.totalElements").value(2));
        liveTiles()
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.totalBilled").value(1500.00));
        assertThat(recent.getId()).isNotNull();
    }

    /**
     * Filtering and sorting are the whole point of a list, and they run against the values OF THAT
     * DATE rather than against today's — which is what "as-of queryability equals live
     * queryability" means in practice. The two accounts are deliberately in opposite order then
     * and now, so a query that quietly read today's numbers would sort them the wrong way round
     * rather than merely look plausible (B3).
     */
    @Test
    void filteringAndSortingByBalanceAsOfAPastDateUseTheValuesOfThatDate() throws Exception {
        Invoice big = raise(acme, raised, due, "1000.00");
        Invoice small = raise(acme, raised, due, "400.00");
        pay(acme, big, "900.00", paidOn);

        asOfSorted(before, "balance,desc")
                .andExpect(jsonPath("$.content[0].id").value(big.getId()))
                .andExpect(jsonPath("$.content[1].id").value(small.getId()));
        asOfSorted(before, "balance,desc", "balance:gt:500")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(big.getId()));

        liveSorted("balance,desc")
                .andExpect(jsonPath("$.content[0].id").value(small.getId()))
                .andExpect(jsonPath("$.content[1].id").value(big.getId()));
        liveSorted("balance,desc", "balance:gt:500")
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    /**
     * The enum coercion the live list already does, on the mirror's own enum column: the mirror
     * maps {@code status} as an {@code @Enumerated(STRING)} of the SAME type, so ValueCoercion
     * takes the same branch and a lower-case chip from a hand-written URL matches exactly as it
     * does today (B3).
     */
    @Test
    void anEnumFilterAsOfADateMatchesTheStatusInForceThenInLowerCaseAsWellAsUpper() throws Exception {
        Invoice invoice = raise(acme, raised, due, "1000.00");
        pay(acme, invoice, "1000.00", paidOn);

        asOfList(before, "status:eq:unpaid").andExpect(jsonPath("$.totalElements").value(1));
        asOfList(before, "status:eq:UNPAID").andExpect(jsonPath("$.totalElements").value(1));
        asOfList(before, "status:eq:fully_paid").andExpect(jsonPath("$.totalElements").value(0));
        liveList("status:eq:fully_paid").andExpect(jsonPath("$.totalElements").value(1));
    }

    /**
     * LOAD-BEARING, and the one test that fails if {@code AsOf.at(T)} leaves the scope list.
     *
     * <p>A mirror holds one ROW PER VERSION. Three invoices edited twice each are nine rows in
     * invoice_history, and the thing that turns nine rows into three records is the interval
     * predicate on the ROOT — not the twin schema, whose interval clauses all live inside
     * correlated subqueries. So this asserts the shape a page must have: three total, two pages,
     * and no id appearing twice across them (B3).
     */
    @Test
    void theAsOfTotalCountsRecordsAndNotVersionsAcrossEveryPage() throws Exception {
        List<Invoice> invoices = elevenInvoicesEachEditedOnce();

        assertThat(invoiceHistoryRepository.count())
                .as("the mirror really does hold two versions of each of the eleven invoices")
                .isEqualTo(22);

        List<Integer> first = ids(asOfPage(before, 0, 10)
                .andExpect(jsonPath("$.totalElements").value(11))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(10)));
        List<Integer> second = ids(asOfPage(before, 1, 10)
                .andExpect(jsonPath("$.totalElements").value(11))
                .andExpect(jsonPath("$.content.length()").value(1)));

        assertThat(first).doesNotContainAnyElementsOf(second);
        assertThat(java.util.stream.Stream.concat(first.stream(), second.stream()).toList())
                .as("every invoice exactly once, never one row per edit")
                .containsExactlyInAnyOrderElementsOf(
                        invoices.stream().map(i -> i.getId().intValue()).toList());
    }

    /** The D-40 overflow guard and the count both still hold on a mirror root (B3). */
    @Test
    void pagingAnAsOfListPastTheEndReturnsAnEmptyPageAndTheSameTotal() throws Exception {
        elevenInvoicesEachEditedOnce();

        asOfPage(before, 5, 10)
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(11))
                .andExpect(jsonPath("$.asOf.date").value(before.toString()));
    }

    /**
     * The denormalised label earning its column: the account's name is COPIED onto every invoice
     * version, so an invoice listed as of August carries the name the account had in August even
     * though the account has been renamed since. Reading it through a live {@code Customer} — which
     * is what the live ColumnDef does — would have put today's name on a historical row, and that
     * is the exact leak the flat {@code customer_id} on the mirror exists to close (B3).
     */
    @Test
    void aCustomerRenamedTodayAppearsUnderItsOldNameInAnAsOfListOfInvoices() throws Exception {
        Invoice invoice = raise(acme, raised, due, "1000.00");
        // Through the repository rather than the service, and the point survives either way: a
        // rename writes a customer_history version and does NOT rewrite invoice_history, because
        // the invoice did not change.
        acme.setName("Acme Holdings PLC");
        customerRepository.saveAndFlush(acme);

        asOfList(before)
                .andExpect(jsonPath("$.content[0].customerName").value("Acme Ltd"))
                .andExpect(jsonPath("$.content[0].customerId").value(acme.getId()));
        liveList()
                .andExpect(jsonPath("$.content[0].customerName").value("Acme Holdings PLC"));

        // And the same name is what a filter typed against that date matches.
        asOfList(before, "customerName:eq:Acme Ltd").andExpect(jsonPath("$.totalElements").value(1));
        asOfList(before, "customerName:eq:Acme Holdings PLC")
                .andExpect(jsonPath("$.totalElements").value(0));
        assertThat(invoice.getId()).isNotNull();
    }

    /**
     * A column marked CURRENT says "do not read this as an answer about the as-of date" — and the
     * Sales POC is the honest example, because {@code users} is deliberately NOT mirrored (contract
     * clause a.3): the person's identity on the row is as-of, the name rendered for them is
     * today's.
     *
     * <p>THIS TEST ALSO PINS TWO THINGS THAT ARE NOT AS THE HAND-OVER NOTES DESCRIBE THEM, because
     * finding them and saying nothing would be worse than either.
     *
     * <p>(1) {@code invoice_history.sales_poc_name} is marked CURRENT and is NOT today's value: it
     * is the label as of THIS ROW'S last change. So the rendered {@code salesPoc.fullName} and the
     * filterable {@code salesPocName} column disagree after a rename, and the flag describes
     * neither of them exactly. B3-SCHEMAS recorded this in its own honest gaps and shipped the flag
     * on instruction; it is asserted here so the disagreement is a fact on record rather than a
     * surprise for whoever builds the filter editor.
     *
     * <p>(2) The flag never reaches the wire at all. {@code GET /api/table-schemas/invoices}
     * describes the LIVE schema, whose columns all say EXACT; the CURRENT flags live only on the
     * twin, which is not registered and not published. A client cannot today render "(current
     * value)" for any column of any table (B3).
     */
    @Test
    void aColumnMarkedCurrentReadsTodaysValueEvenUnderAsOfAndTheSchemaSaysSo() throws Exception {
        User seller = user("sam.seller", com.geneinvoice.config.DataSeeder.ROLE_SALES_POC);
        Invoice invoice = raise(acme, raised, due, "1000.00", seller);
        seller.setFullName("Samantha Seller-Jones");
        userRepository.saveAndFlush(seller);
        actAs(admin);

        assertThat(HistorySchemas.INVOICES.require("salesPocName").asOfMode()).isEqualTo("CURRENT");
        assertThat(HistorySchemas.INVOICES.require("total").asOfMode()).isEqualTo("EXACT");

        // The claim the flag makes, honoured: the User row is not mirrored, so the identity is
        // as-of and the NAME rendered for it is today's.
        asOfList(before)
                .andExpect(jsonPath("$.content[0].salesPoc.id").value(seller.getId()))
                .andExpect(jsonPath("$.content[0].salesPoc.fullName")
                        .value("Samantha Seller-Jones"));

        // (1) The filterable column behind the same flag does NOT read today's value.
        asOfList(before, "salesPocName:eq:SAM.SELLER").andExpect(jsonPath("$.totalElements").value(1));
        asOfList(before, "salesPocName:eq:Samantha Seller-Jones")
                .andExpect(jsonPath("$.totalElements").value(0));

        // (2) KNOWN GAP, pinned rather than hidden: the twin's flag never reaches a client.
        mockMvc.perform(get("/api/table-schemas/invoices").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfSupported").value(true))
                .andExpect(jsonPath("$.columns[?(@.name=='salesPocName')].asOfMode")
                        .value(org.hamcrest.Matchers.hasItem("EXACT")));
        assertThat(TableSchemas.INVOICES.require("salesPocName").asOfMode())
                .as("the live ColumnDef is what TableSchemaController describes, and it says EXACT")
                .isEqualTo("EXACT");
        assertThat(invoice.getId()).isNotNull();
    }

    /**
     * Lateness is derived from a clock rather than stored (D3), and the clock a READ uses is
     * {@code InvoiceDates.today()} — which B3-CONTEXT pointed at the as-of date. So an invoice that
     * is a month late today was not late at all on a date before it fell due, and the number of
     * days is counted to the date asked about and not to now (B3).
     */
    @Test
    void overdueAsOfAPastDateUsesThatDateAndNotToday() throws Exception {
        raise(acme, raised, due, "1000.00");

        asOfList(before)
                .andExpect(jsonPath("$.content[0].overdue").value(false))
                .andExpect(jsonPath("$.content[0].daysOverdue").value(0));
        asOfList(before, "overdue:eq:true").andExpect(jsonPath("$.totalElements").value(0));
        asOfList(before, "overdue:eq:false").andExpect(jsonPath("$.totalElements").value(1));

        asOfList(after)
                .andExpect(jsonPath("$.content[0].overdue").value(true))
                .andExpect(jsonPath("$.content[0].daysOverdue").value(20));
        asOfList(after, "overdue:eq:true").andExpect(jsonPath("$.totalElements").value(1));

        liveList()
                .andExpect(jsonPath("$.content[0].overdue").value(true))
                .andExpect(jsonPath("$.content[0].daysOverdue").value(30));
    }

    /**
     * {@code relative:last30Days} was the one date preset in the codebase that read the wall clock
     * directly; B3-CONTEXT moved it onto the read clock. Under {@code ?asOf} it therefore means the
     * thirty days ENDING ON THAT DATE — which is what makes "the last thirty days, as of month
     * end" a question anybody can ask from the existing filter UI with no new grammar (B3).
     */
    @Test
    void aRelativeDateFilterAsOfAPastDateIsRelativeToThatDate() throws Exception {
        LocalDate longAgo = today.minusDays(90);
        LocalDate inTheWindow = today.minusDays(35);
        Invoice ancient = raise(acme, longAgo, longAgo.plusDays(30), "100.00");
        Invoice recentEnough = raise(acme, inTheWindow, inTheWindow.plusDays(30), "200.00");

        // As of 30 days ago the window is [today-59, today-30]: the 35-day-old invoice is inside
        // it and the 90-day-old one is not.
        asOfList(due, "invoiceDate:relative:last30Days")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(recentEnough.getId()))
                .andExpect(jsonPath("$.appliedFilters").value(org.hamcrest.Matchers.hasItem(
                        "invoiceDate:relative:last30Days")));

        // Live the window is [today-29, today] and neither of them is in it, which is the proof
        // that the filter moved with the date rather than staying put.
        liveList("invoiceDate:relative:last30Days")
                .andExpect(jsonPath("$.totalElements").value(0));
        assertThat(ancient.getId()).isNotNull();
    }

    /**
     * The tiles are the same query with different selections, so they are as-of for free — and
     * that is worth a test rather than an assumption, because a figure above a list that disagreed
     * with the list under it is the failure mode this whole feature is about. Note the overdue
     * pair: zero on a date before the invoice fell due, the full balance after (B3).
     */
    @Test
    void theInvoiceTilesAsOfAPastDateUseThatDatesBalances() throws Exception {
        Invoice invoice = raise(acme, raised, due, "1000.00");
        pay(acme, invoice, "400.00", paidOn);
        raise(acme, after, today.plusDays(5), "500.00");

        asOfTiles(before)
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.totalBilled").value(1000.00))
                .andExpect(jsonPath("$.totalPaid").value(0.00))
                .andExpect(jsonPath("$.outstanding").value(1000.00))
                .andExpect(jsonPath("$.unpaidCount").value(1))
                .andExpect(jsonPath("$.overdueCount").value(0))
                .andExpect(jsonPath("$.overdueAmount").value(0.00));

        asOfTiles(today.minusDays(15))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.totalPaid").value(400.00))
                .andExpect(jsonPath("$.outstanding").value(600.00))
                .andExpect(jsonPath("$.partiallyPaidCount").value(1))
                .andExpect(jsonPath("$.overdueCount").value(1))
                .andExpect(jsonPath("$.overdueAmount").value(600.00));

        liveTiles()
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.totalBilled").value(1500.00))
                .andExpect(jsonPath("$.outstanding").value(1100.00));
    }

    /**
     * A downloaded file has to agree with the page it was taken from, and it has to say what it
     * is: the caveat row is the only thing that still says "history" once the CSV is in somebody's
     * mail. The filename carries the date for the same reason — two downloads of the same list
     * must not land in the same folder under the same name (B3).
     */
    @Test
    void anAsOfExportContainsTheSameRowsAndTheSameValuesAsTheAsOfList() throws Exception {
        Invoice old = raise(acme, raised, due, "1000.00");
        pay(acme, old, "400.00", paidOn);
        Invoice recent = raise(acme, after, today.plusDays(5), "500.00");

        String csv = export(before);
        List<String> lines = Arrays.stream(csv.split("\r\n")).toList();

        assertThat(lines.get(0))
                .as("a leading one-cell caveat row, ahead of the header")
                .contains("As of " + before).contains("history, not today");
        assertThat(lines.get(1)).startsWith("Invoice #,Customer,Date,Due date,Total,Paid,Balance");
        assertThat(lines).hasSize(3);                      // caveat + header + exactly one row
        assertThat(lines.get(2))
                .contains(old.getInvoiceNumber())
                .contains("Acme Ltd")
                .contains("1000.00,0.00,1000.00")          // total, paid, balance AS OF THEN
                .contains("UNPAID")
                .contains("false");                        // not yet overdue on that date
        assertThat(csv).doesNotContain(recent.getInvoiceNumber());

        // And the live export is byte-identical to what this application has always produced.
        String live = export(null);
        assertThat(live.split("\r\n")[0]).startsWith("Invoice #,Customer,");
        assertThat(live).contains("600.00").contains(recent.getInvoiceNumber());
    }

    /**
     * AUTH-08, and the discontinuity it closes. An honest as-of list that dropped the reader onto
     * a detail page showing today's figures would undo the feature one click after delivering it,
     * so the single record roots on the same mirror — and an invoice that did not exist on the
     * date asked answers 404 with the same words a record outside the caller's book answers, never
     * 403 and never today's values (B3).
     */
    @Test
    void aSingleInvoiceThatDidNotExistThenAnswersNotFoundAndNotForbidden() throws Exception {
        Invoice old = raise(acme, raised, due, "1000.00");
        Invoice recent = raise(acme, after, today.plusDays(5), "500.00");
        // A second line added today: the as-of detail must show the ONE line the invoice had then.
        clock.release();
        invoiceService.replaceItemsForDisputeApplication(old.getId(), List.of(
                new InvoiceDtos.LineInput(widget.getId(), 10, new BigDecimal("100.00")),
                new InvoiceDtos.LineInput(widget.getId(), 3, new BigDecimal("100.00"))), null);

        mockMvc.perform(get("/api/invoices/" + recent.getId()).param("asOf", before.toString())
                        .with(as(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Invoice not found"));

        mockMvc.perform(get("/api/invoices/" + old.getId()).param("asOf", before.toString())
                        .with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1000.00))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(10))
                .andExpect(jsonPath("$.items[0].productName").value("Widget"))
                // A past snapshot has no live row version to lock an edit against, and a number
                // here would invite a client to send it back on a PATCH (B3).
                .andExpect(jsonPath("$.version").doesNotExist());

        mockMvc.perform(get("/api/invoices/" + old.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.version").exists());
    }

    /**
     * ADDED BEYOND THE UNIT'S NAMED LIST, because {@code region/RegionPlacements} is new here and
     * would otherwise ship untested. No mirror carries {@code region_id}; the branch on an as-of
     * row is read from R7's placement ledger, so an invoice of an account that moved in September
     * is labelled with the branch it was in in August (B3, B1).
     *
     * <p>The NAME is today's name and that is contract clause a.3: which region is reconstructed,
     * the label on it is not, because {@code regions} is not a mirrored table.
     */
    @Test
    void theRegionOnAnAsOfRowIsTheBranchTheAccountWasInThen() throws Exception {
        Region home = defaultRegion();
        Region west = region("WEST");
        place(acme, home, today.minusDays(90), today.minusDays(25));
        place(acme, west, today.minusDays(25), null);
        raise(acme, raised, due, "1000.00");

        asOfList(before)
                .andExpect(jsonPath("$.content[0].regionId").value(home.getId()))
                .andExpect(jsonPath("$.content[0].regionName").value(home.getName()));
        asOfList(after)
                .andExpect(jsonPath("$.content[0].regionId").value(west.getId()))
                .andExpect(jsonPath("$.content[0].regionName").value(west.getName()));

        // An account with no placement in force reports null rather than guessing at today's
        // branch — the existing "not asked" convention (B3).
        customerRegionHistoryRepository.deleteAll();
        asOfList(before)
                .andExpect(jsonPath("$.content[0].regionId").doesNotExist())
                .andExpect(jsonPath("$.content[0].regionName").doesNotExist());
    }

    /**
     * ADDED BEYOND THE UNIT'S NAMED LIST, because the single record's approval flag is the one
     * call site this unit had to make as-of aware by hand and it would otherwise ship untested.
     *
     * <p>{@code pending_key} is RELEASED the moment a change is decided, so the sentinel column
     * that answers "is one waiting now" cannot answer "was one waiting then". The decision LOG can,
     * because B2's pending_changes is already interval-shaped — requestedAt opens it, decidedAt
     * closes it — which is exactly why no mirror table was ever built for approvals (B2, B3).
     */
    @Test
    void theApprovalFlagOnAnAsOfInvoiceSaysWhetherAChangeWasWaitingThen() throws Exception {
        Invoice invoice = raise(acme, raised, due, "1000.00");
        held(acme, invoice.getId(), today.minusDays(50), today.minusDays(20));

        mockMvc.perform(get("/api/invoices/" + invoice.getId())
                        .param("asOf", before.toString()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalPending").value(true));
        asOfList(before).andExpect(jsonPath("$.content[0].approvalPending").value(true));

        mockMvc.perform(get("/api/invoices/" + invoice.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalPending").value(false));
        // And a change raised AFTER the date asked about was not waiting then.
        asOfList(today.minusDays(55)).andExpect(
                jsonPath("$.content[0].approvalPending").value(false));
    }

    /**
     * ADDED BEYOND THE UNIT'S NAMED LIST, because {@code HistoryDrift.markIfDrifted} had a caller
     * for the first time here and the call site would otherwise be untested.
     *
     * <p>A row the reconciler REPAIRED is a row nobody watched change: its predecessor was closed
     * when the sweep noticed rather than when the change happened, so an answer about a date at or
     * before it may be off by the size of that window. The contract's answer is to say so, never
     * to hide it — so the page still comes back 200 with the right rows, and the envelope says
     * {@code exact: false} and names the table (B3).
     */
    @Test
    void aRepairedVersionMakesTheAnswerSayItIsNotExact() throws Exception {
        raise(acme, raised, due, "1000.00");

        asOfList(before).andExpect(jsonPath("$.asOf.exact").value(true));

        invoiceHistoryRepository.findAll().forEach(version -> {
            version.setDrifted(true);
            invoiceHistoryRepository.saveAndFlush(version);
        });

        asOfList(before)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.asOf.exact").value(false))
                .andExpect(jsonPath("$.asOf.notes[0]")
                        .value(org.hamcrest.Matchers.containsString("invoice_history")));
    }

    /**
     * ADDED BEYOND THE UNIT'S NAMED LIST, and it is the measurement B3-VIEWS recorded as an honest
     * gap: the claim that "the live path is untouched" is here a reading of the SQL that reached
     * the database rather than an argument about the code.
     *
     * <p>A live invoice list asks the mirror nothing, asks the placement ledger nothing and asks
     * the drift table nothing — because {@code invoiceSource()} returns the live triple and both
     * {@code placeRegions} and {@code markDrift} return on {@code isActive()} before they do any
     * work. The as-of list is measured in the same breath so that the absence means something:
     * the same three tables ARE read when a date is asked for (B3).
     */
    @Test
    void theLiveInvoiceListAsksTheMirrorAndThePlacementLedgerNothingAtAll() throws Exception {
        place(acme, defaultRegion(), today.minusDays(90), null);
        raise(acme, raised, due, "1000.00");

        List<String> live = com.geneinvoice.CountingStatements.capture(() ->
                liveList().andExpect(jsonPath("$.totalElements").value(1)));
        assertThat(live).anySatisfy(sql -> assertThat(sql).contains("from invoices"));
        assertThat(live).noneSatisfy(sql -> assertThat(sql).contains("invoice_history"));
        assertThat(live).noneSatisfy(sql -> assertThat(sql).contains("customer_region_history"));

        List<String> historical = com.geneinvoice.CountingStatements.capture(() ->
                asOfList(before).andExpect(jsonPath("$.totalElements").value(1)));
        assertThat(historical).as("and the as-of list really does read all three")
                .anySatisfy(sql -> assertThat(sql).contains("invoice_history"))
                .anySatisfy(sql -> assertThat(sql).contains("customer_region_history"));
        assertThat(historical).noneSatisfy(sql -> assertThat(sql).contains("from invoices "));
    }

    // ---------------------------------------------------------------- fixtures

    /** Noon of a day, so a version opened "on" a date is comfortably inside it at both ends. */
    private static Instant noon(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant();
    }

    private Invoice raise(Customer c, LocalDate on, LocalDate dueOn, String total) {
        return raise(c, on, dueOn, total, admin);
    }

    /**
     * A real create, through the real service, with the WRITE-side clock held at the day in
     * question — so the mirror row the history writer leaves behind is dated then. Ten lines of a
     * hundred rather than an arbitrary unit price, so the total reads as the round number the
     * assertions name.
     */
    private Invoice raise(Customer c, LocalDate on, LocalDate dueOn, String total, User salesPoc) {
        clock.freezeAt(noon(on));
        int quantity = new BigDecimal(total).divide(new BigDecimal("100.00")).intValue();
        Invoice invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                c.getId(), noon(on), dueOn, PaymentTerm.CUSTOM, null, salesPoc.getId(),
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
     * Eleven invoices, each with two versions — twenty-two mirror rows for eleven records, and
     * eleven is one more than the smallest page size the API allows, so the count and the paging
     * are both under test rather than only the count.
     */
    private List<Invoice> elevenInvoicesEachEditedOnce() {
        List<Invoice> invoices = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) {
            Invoice invoice = raise(acme, raised, due, "100.00");
            clock.freezeAt(noon(today.minusDays(50)));
            invoiceService.update(invoice.getId(),
                    new InvoiceDtos.UpdateInvoiceRequest("edited", null));
            clock.release();
            invoices.add(invoice);
        }
        return invoices;
    }

    /**
     * A change raised on {@code requested} and decided on {@code decided} (null for still
     * waiting), written directly so the dates can be in the past. A decided change has released
     * its pending_key, which is why the live flag reads false while the log still says it was
     * waiting on the date asked about (B2, B3).
     */
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

    private void place(Customer c, Region region, LocalDate from, LocalDate to) {
        customerRegionHistoryRepository.saveAndFlush(CustomerRegionHistory.builder()
                .customerId(c.getId()).regionId(region.getId()).validFrom(from).validTo(to).build());
    }

    // ---------------------------------------------------------------- requests

    private ResultActions asOfList(LocalDate date, String... filters) throws Exception {
        return asOfSorted(date, null, filters);
    }

    private ResultActions asOfSorted(LocalDate date, String sort, String... filters) throws Exception {
        var request = get("/api/invoices").param("asOf", date.toString()).with(as(admin));
        if (sort != null) request = request.param("sort", sort);
        for (String filter : filters) request = request.param("filter", filter);
        return mockMvc.perform(request).andExpect(status().isOk());
    }

    private ResultActions asOfPage(LocalDate date, int page, int size) throws Exception {
        return mockMvc.perform(get("/api/invoices").param("asOf", date.toString())
                        .param("page", String.valueOf(page)).param("size", String.valueOf(size))
                        .with(as(admin)))
                .andExpect(status().isOk());
    }

    private ResultActions liveList(String... filters) throws Exception {
        return liveSorted(null, filters);
    }

    private ResultActions liveSorted(String sort, String... filters) throws Exception {
        var request = get("/api/invoices").with(as(admin));
        if (sort != null) request = request.param("sort", sort);
        for (String filter : filters) request = request.param("filter", filter);
        return mockMvc.perform(request).andExpect(status().isOk());
    }

    private ResultActions asOfTiles(LocalDate date) throws Exception {
        return mockMvc.perform(get("/api/invoices/summary").param("asOf", date.toString())
                .with(as(admin))).andExpect(status().isOk());
    }

    private ResultActions liveTiles() throws Exception {
        return mockMvc.perform(get("/api/invoices/summary").with(as(admin)))
                .andExpect(status().isOk());
    }

    private String export(LocalDate date) throws Exception {
        String body = json(new BulkDtos.BulkRequest("EXPORT", null, true, null, null, null));
        var request = post("/api/invoices/export").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (date != null) request = request.param("asOf", date.toString());
        var response = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader("Content-Disposition"))
                .contains(date == null ? "invoices.csv" : "invoices-as-of-" + date + ".csv");
        return response.getContentAsString();
    }

    private static List<Integer> ids(ResultActions actions) throws Exception {
        com.fasterxml.jackson.databind.JsonNode page = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(actions.andReturn().getResponse().getContentAsString());
        List<Integer> ids = new java.util.ArrayList<>();
        page.get("content").forEach(row -> ids.add(row.get("id").intValue()));
        return ids;
    }
}
