package com.geneinvoice.approval;

import com.geneinvoice.CountingStatements;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Everywhere a held change has to SHOW, and everywhere it must not (B2).
 *
 * <p>The single claim these tests exist to defend is that nothing pending has taken effect, so no
 * money figure anywhere changes while a change waits: the invoice still reports its full total,
 * the tiles still report the same outstanding, and every dashboard figure is byte-identical
 * before and after. What is waiting is reported as a separate flag and a separate COUNT, never
 * netted into an amount. A read model that quietly subtracted a held cancellation would be
 * telling a collections user that money they are still owed is gone.
 *
 * <p>The second claim is about cost: the flag on a page of rows is ONE query however many of them
 * are held, and the four tiles ride inside the aggregate that was already running.
 */
class ApprovalReadModelTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PrivilegeRepository privilegeRepository;

    static final String LAKH = "100000.00";

    User admin;          // the maker: wildcard MANAGE and APPROVE, so they can hold anything
    User checker;        // APPROVAL_APPROVE in the home branch, and did not raise anything
    User reader;         // APPROVAL_VIEW in the home branch and no right to decide
    User collections;
    Customer acme;
    Product widget;
    Long home;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        checker = user("kerry.checker", checkerRole().getName());
        reader = user("rhea.reader", readerRole().getName());
        collections = user("cora.collections3", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        home = defaultRegion().getId();
        actAs(admin);
    }

    // ------------------------------------------------------------------ the flag on the record

    @Test
    void aPendingChangeIsVisibleOnTheRecordToAnyoneWhoCanReadTheRecordWithCanDecideFalse()
            throws Exception {
        Invoice big = invoice(20_000);
        threshold(home, LAKH, true);
        Long changeId = holdCancelOf(big);

        // The reader holds APPROVAL_VIEW in this branch and nothing more. They see the change —
        // "who can read the record can see what is waiting on it" — and are told in a sentence
        // why there is no Approve button rather than being left to guess (B2).
        mockMvc.perform(get("/api/approvals/" + changeId).with(as(reader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(changeId))
                .andExpect(jsonPath("$.action").value("INVOICE_CANCEL"))
                .andExpect(jsonPath("$.targetId").value(big.getId()))
                .andExpect(jsonPath("$.mine").value(false))
                .andExpect(jsonPath("$.canDecide").value(false))
                .andExpect(jsonPath("$.cannotDecideReason").isNotEmpty())
                // The four names the data layer cannot fill, filled: a queue showing four bare id
                // numbers would be the one screen in the application nobody could read (B2).
                .andExpect(jsonPath("$.customerName").value("Acme Ltd"))
                .andExpect(jsonPath("$.regionName").value(defaultRegion().getName()))
                .andExpect(jsonPath("$.requestedByName").value(admin.getFullName()))
                .andExpect(jsonPath("$.decidedByName").doesNotExist());

        // And the record itself says so, to the same reader.
        mockMvc.perform(get("/api/invoices/" + big.getId()).with(as(reader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalPending").value(true));

        // The checker may decide it, and is told so by the same field rather than by the client
        // re-implementing the rule (B2).
        mockMvc.perform(get("/api/approvals/" + changeId).with(as(checker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canDecide").value(true))
                .andExpect(jsonPath("$.cannotDecideReason").doesNotExist());
    }

    @Test
    void theInvoiceDetailStillReportsTheLiveTotalAndBalanceWhileAChangeIsPending() throws Exception {
        Invoice big = invoice(20_000);                                // 20,00,000.00, unpaid
        threshold(home, LAKH, true);
        holdCancelOf(big);

        // Not one figure moves. A cancellation that is only WAITING has cancelled nothing, and a
        // detail screen that showed the balance as zero would be reporting a refund nobody has
        // agreed to (B2).
        mockMvc.perform(get("/api/invoices/" + big.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2000000.00))
                .andExpect(jsonPath("$.paidAmount").value(0.00))
                .andExpect(jsonPath("$.balance").value(2000000.00))
                .andExpect(jsonPath("$.status").value(InvoiceStatus.UNPAID.name()))
                .andExpect(jsonPath("$.approvalPending").value(true));

        assertThat(invoiceRepository.findById(big.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.UNPAID);
    }

    @Test
    void theInvoiceListMarksRowsThatHaveAChangeAwaitingApproval() throws Exception {
        Invoice big = invoice(20_000);
        Invoice small = invoice(50);
        threshold(home, LAKH, true);
        holdCancelOf(big);

        MvcResult result = mockMvc.perform(get("/api/invoices?sort=id,asc").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn();

        var tree = objectMapper.readTree(body(result)).get("content");
        assertThat(flagOf(tree, big.getId())).isTrue();
        assertThat(flagOf(tree, small.getId())).isFalse();

        // "Not asked" is the third answer, and it is what a WRITE path returns: from(inv, poc)
        // passes null, so an audit before/after blob never varies with an unrelated pending row.
        // On the wire that reads as the field being absent (B2).
        mockMvc.perform(post("/api/invoices").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null,
                                null, admin.getId(),
                                List.of(new InvoiceDtos.LineInput(widget.getId(), 1,
                                        new BigDecimal("100.00")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalPending").doesNotExist());
    }

    @Test
    void theInvoiceSummaryCountsRecordsAwaitingApprovalAndLeavesOutstandingUnchanged()
            throws Exception {
        Invoice big = invoice(20_000);
        invoice(50);
        threshold(home, LAKH, true);

        String before = body(mockMvc.perform(get("/api/invoices/summary").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaitingApprovalCount").value(0))
                .andReturn());

        holdCancelOf(big);

        mockMvc.perform(get("/api/invoices/summary").with(as(admin)))
                .andExpect(status().isOk())
                // The count is the ONLY figure that moves. outstanding, totalBilled, unpaidCount
                // and the overdue pair all read exactly what they read before, because a held
                // cancellation has cancelled nothing (B2).
                .andExpect(jsonPath("$.awaitingApprovalCount").value(1))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.totalBilled").value(2005000.00))
                .andExpect(jsonPath("$.outstanding").value(2005000.00))
                .andExpect(jsonPath("$.unpaidCount").value(2))
                .andExpect(jsonPath("$.cancelledCount").value(0));

        // Byte for byte apart from the one new count: the tile rides inside the aggregate that
        // was already running, so nothing else in that select can have been disturbed (B2).
        assertThat(before.replace("\"awaitingApprovalCount\":0", "\"awaitingApprovalCount\":1"))
                .isEqualTo(body(mockMvc.perform(get("/api/invoices/summary").with(as(admin)))
                        .andReturn()));
    }

    @Test
    void filteringOnApprovalPendingReturnsOnlyTheHeldRows() throws Exception {
        Invoice big = invoice(20_000);
        Invoice small = invoice(50);
        threshold(home, LAKH, true);
        holdCancelOf(big);

        mockMvc.perform(get("/api/invoices?filter=approvalPending:eq:true").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(big.getId()))
                .andExpect(jsonPath("$.content[0].approvalPending").value(true));

        mockMvc.perform(get("/api/invoices?filter=approvalPending:eq:false").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(small.getId()));

        // The column is published to the client, or the chip exists on the server and nowhere a
        // person could press it (B2).
        mockMvc.perform(get("/api/table-schemas/invoices").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[?(@.name == 'approvalPending')].type")
                        .value("BOOLEAN"))
                .andExpect(jsonPath("$.columns[?(@.name == 'approvalPending')].sortable")
                        .value(false));

        // And a nonsense value is refused by the same sentence every other boolean column uses.
        mockMvc.perform(get("/api/invoices?filter=approvalPending:eq:maybe").with(as(admin)))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------- the queue itself

    @Test
    void theApprovalsListShowsOnlyChangesInRegionsTheCallerHoldsSomethingInAndSaysSoInLockedFilters()
            throws Exception {
        Region west = region("WEST");
        Customer far = customerRepository.save(
                Customer.builder().name("Far Ltd").region(west).build());
        // The invoices are raised BEFORE the limit exists, or INVOICE_CREATE is itself held and
        // there is no invoice to cancel (B2).
        Invoice here = invoice(20_000);
        Invoice there = invoice(far, 20_000);
        threshold(home, LAKH, true);
        threshold(west.getId(), LAKH, true);

        Long mine = holdCancelOf(here);
        Long theirs = holdCancelOf(there);

        // The administrator's wildcard grant sees both and carries no chip at all.
        mockMvc.perform(get("/api/approvals").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.lockedFilters").isEmpty());

        // The reader works in the home branch only. The change raised in WEST is ABSENT rather
        // than refused, which is AUTH-08, and the page says which branches it covers so nobody
        // reads a short queue as an empty one (B2, B1).
        MvcResult result = mockMvc.perform(get("/api/approvals").with(as(reader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(mine))
                .andExpect(jsonPath("$.lockedFilters[0]").value("regionId:in:" + home))
                .andReturn();
        assertThat(body(result)).doesNotContain("\"id\":" + theirs + ",");

        // The same rule on the single-record read, and on the tiles.
        mockMvc.perform(get("/api/approvals/" + theirs).with(as(reader)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/approvals/summary").with(as(reader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.pendingCount").value(1))
                .andExpect(jsonPath("$.pendingExposure").value(2000000.00))
                // The reader may decide nothing anywhere, so nothing is waiting for THEM even
                // though something is waiting in their branch (B2).
                .andExpect(jsonPath("$.awaitingMyDecisionCount").value(0))
                .andExpect(jsonPath("$.mineCount").value(0));

        // The checker holds APPROVE here and did not raise it, so this one IS theirs to decide.
        mockMvc.perform(get("/api/approvals/summary").with(as(checker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaitingMyDecisionCount").value(1));

        // And the maker's own tile: they raised it, so it is counted as theirs and never as
        // theirs to decide — the whole point of maker-checker (B2).
        mockMvc.perform(get("/api/approvals/summary").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.mineCount").value(2))
                .andExpect(jsonPath("$.awaitingMyDecisionCount").value(0));
    }

    @Test
    void aCustomerLoginSeesNoApprovalsAtAll() throws Exception {
        Invoice big = invoice(20_000);
        threshold(home, LAKH, true);
        holdCancelOf(big);

        // The shipped CUSTOMER role does not carry APPROVAL_VIEW, so the annotation refuses it
        // before anything else is asked.
        User plain = customerUser("acme.login", acme.getId());
        mockMvc.perform(get("/api/approvals").with(as(plain)))
                .andExpect(status().isForbidden());

        // And if an operator ever grants APPROVAL_VIEW to a customer-facing role by accident, the
        // book scope still answers an EMPTY list rather than the staff queue — a denied READ is
        // always an empty result and never an exception (AUTH-08, B2).
        plain.setRole(customerApproverRole());
        userRepository.save(plain);

        mockMvc.perform(get("/api/approvals").with(as(plain)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.lockedFilters[0]").value("approvals:none"));

        mockMvc.perform(get("/api/approvals/summary").with(as(plain)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.pendingCount").value(0))
                .andExpect(jsonPath("$.pendingExposure").value(0.00));
    }

    @Test
    void theApprovalsListSaysPerRowWhetherThisCallerMayDecideItAndWhyNot() throws Exception {
        Invoice raisedByTheChecker = invoice(20_000);
        Invoice raisedByTheAdmin = invoice(20_000);
        Invoice alreadyDecided = invoice(20_000);
        threshold(home, LAKH, true);

        // The checker needs to be able to MANAGE to raise one of their own, and ALREADY CAN:
        // checkerRole() holds INVOICE_MANAGE, RegionRights.LEVEL maps that to MANAGE, and
        // IntegrationTestBase.user(...) staffs whoever it creates at the levels their own role
        // implies. Saving the grant again wrote a SECOND identical row, which uk_grant_region —
        // the Postgres partial unique index on (user_id, region_id, right_level) where the region
        // is named — refuses outright; H2 has no partial index and took it, so this fixture could
        // only ever run on H2. Asserted rather than deleted, so the test still SAYS what it
        // depends on and would fail loudly if the fixture ever stopped staffing them (B2, B1).
        assertThat(userRegionGrantRepository.findByUserId(checker.getId()))
                .as("the checker already holds MANAGE in the home branch, so they can raise their own")
                .anySatisfy(g -> {
                    assertThat(g.getRegionId()).isEqualTo(home);
                    assertThat(g.getRight()).isEqualTo(RegionRight.MANAGE);
                });

        Long theirOwn = holdCancelAs(checker, raisedByTheChecker);
        Long somebodyElses = holdCancelAs(admin, raisedByTheAdmin);
        Long closed = holdCancelAs(admin, alreadyDecided);
        mockMvc.perform(post("/api/approvals/" + closed + "/reject").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionNotes\":\"not this quarter\"}"))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/approvals?sort=id,asc").with(as(checker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andReturn();
        var rows = objectMapper.readTree(body(result)).get("content");

        // Three different reasons, all computed server-side, so the UI never re-implements the
        // rule and never disagrees with the endpoint that would refuse the button (B2).
        var own = rowById(rows, theirOwn);
        assertThat(own.get("mine").asBoolean()).isTrue();
        assertThat(own.get("canDecide").asBoolean()).isFalse();
        assertThat(own.get("cannotDecideReason").asText()).contains("raised");

        var other = rowById(rows, somebodyElses);
        assertThat(other.get("mine").asBoolean()).isFalse();
        assertThat(other.get("canDecide").asBoolean()).isTrue();
        assertThat(other.get("cannotDecideReason").isNull()).isTrue();

        var done = rowById(rows, closed);
        assertThat(done.get("status").asText()).isEqualTo(PendingChangeStatus.REJECTED.name());
        assertThat(done.get("canDecide").asBoolean()).isFalse();
        assertThat(done.get("cannotDecideReason").asText()).contains("already been decided");
        assertThat(done.get("decidedByName").asText()).isEqualTo(checker.getFullName());

        // The reader may decide in no branch, so every row answers the third reason.
        MvcResult asReader = mockMvc.perform(get("/api/approvals?sort=id,asc").with(as(reader)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(rowById(objectMapper.readTree(body(asReader)).get("content"), somebodyElses)
                .get("cannotDecideReason").asText()).contains("branch");
    }

    // ------------------------------------------------------------------------ export and credit

    @Test
    void theInvoiceExportNamesTheRowsAwaitingApproval() throws Exception {
        Invoice big = invoice(20_000);
        Invoice small = invoice(50);
        threshold(home, LAKH, true);
        holdCancelOf(big);

        String csv = body(mockMvc.perform(post("/api/invoices/export").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BulkDtos.BulkRequest("EXPORT", null, true, "id,asc",
                                List.of(), null))))
                .andExpect(status().isOk())
                .andReturn());

        List<String> lines = List.of(csv.split("\r\n"));
        assertThat(lines).hasSize(3);
        // Appended, never inserted: every column an operator's spreadsheet already reads keeps
        // its position (B2).
        assertThat(lines.get(0)).endsWith(",Overdue,Awaiting approval,Sales POC");
        assertThat(lines.get(1)).contains(big.getInvoiceNumber()).contains(",true,");
        assertThat(lines.get(2)).contains(small.getInvoiceNumber()).contains(",false,");

        // The queue has an export of its own, and it needs BOTH privileges.
        String queue = body(mockMvc.perform(post("/api/approvals/export").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BulkDtos.BulkRequest("EXPORT", null, true, null,
                                List.of(), null))))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(queue).contains("Id,Action,Record type,Record id,Customer,Region")
                .contains("INVOICE_CANCEL")
                .contains("Acme Ltd");

        mockMvc.perform(post("/api/approvals/export").with(as(reader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BulkDtos.BulkRequest("EXPORT", null, true, null,
                                List.of(), null))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/approvals/export").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BulkDtos.BulkRequest("EXPORT", null, true, null,
                                List.of(), null))))
                .andExpect(status().isForbidden());   // APPROVAL_VIEW without EXPORT_DATA
    }

    @Test
    void theCustomerCreditReadSaysWhenAChangeIsWaitingOnThatCustomer() throws Exception {
        threshold(home, LAKH, true);

        mockMvc.perform(get("/api/payments/credits/" + acme.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditBalance").value(0.00))
                .andExpect(jsonPath("$.approvalPendingOnCustomer").value(false));

        // A held PAYMENT_RECORD: target_id is NULL because there is no payment yet, so a flag
        // that only looked for a CUSTOMER-targeted change would say "nothing waiting" about the
        // very change that is about to move this account's money (B2).
        mockMvc.perform(post("/api/payments").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                                new BigDecimal("1250000.00"), "NEFT", null, List.of(),
                                collections.getId(), null))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.targetId").doesNotExist());

        mockMvc.perform(get("/api/payments/credits/" + acme.getId()).with(as(admin)))
                .andExpect(status().isOk())
                // The balance itself is UNCHANGED — the payment did not happen — and the flag is
                // what tells the cashier the number may be about to move (B2).
                .andExpect(jsonPath("$.creditBalance").value(0.00))
                .andExpect(jsonPath("$.approvalPendingOnCustomer").value(true));

        // A different account is unaffected.
        Customer other = customer("Other Ltd");
        mockMvc.perform(get("/api/payments/credits/" + other.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalPendingOnCustomer").value(false));
    }

    // ------------------------------------------------------------------- the dashboard, and cost

    @Test
    void noDashboardFigureNetsAPendingChange() throws Exception {
        Invoice billed = invoice(20_000);
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("500000.00"), "NEFT", null, List.of(billed.getId()),
                collections.getId(), null));
        Invoice openOne = invoice(20_000);
        threshold(home, LAKH, true);

        List<String> before = dashboard();

        // Two held changes that would each move a figure on this page if they had taken effect:
        // cancelling openOne takes 20,00,000.00 out of outstanding and out of the ageing buckets,
        // and recording 12,50,000.00 puts it into collected-by-month and into top-paying. Neither
        // has happened, so neither shows (B2).
        holdCancelOf(openOne);
        mockMvc.perform(post("/api/payments").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                                new BigDecimal("1250000.00"), "NEFT", null, List.of(),
                                collections.getId(), null))))
                .andExpect(status().isAccepted());
        assertThat(pendingChangeRepository.count()).isEqualTo(2);

        assertThat(dashboard())
                .describedAs("a dashboard figure moved because a change is WAITING")
                .isEqualTo(before);

        // Not vacuous: the same two acts, actually applied, DO move the page. Without this the
        // assertion above would pass against a dashboard that read nothing at all (B2).
        threshold(home, LAKH, false);
        actAs(admin);
        invoiceService.cancel(openOne.getId());
        assertThat(dashboard()).isNotEqualTo(before);
    }

    @Test
    void aPageOfInvoicesCostsOneExtraQueryHoweverManyPendingChangesItHas() throws Exception {
        List<Invoice> held = List.of(invoice(20_000), invoice(20_000), invoice(20_000));
        invoice(50);
        threshold(home, LAKH, true);
        for (Invoice i : held) holdCancelOf(i);
        assertThat(pendingChangeRepository.count()).isEqualTo(3);

        // ONE statement against pending_changes for the whole page, not one per row and not one
        // per held row: openTargetIds asks about every id on the page at once (B2).
        assertThat(CountingStatements.reads("pending_changes",
                () -> mockMvc.perform(get("/api/invoices").with(as(admin)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalElements").value(4))))
                .isEqualTo(1);

        // An EMPTY page costs NONE: `in ()` is a syntax error on Postgres and a full scan waiting
        // to happen, so the repository answers Set.of() without going to the database (B2).
        assertThat(CountingStatements.reads("pending_changes",
                () -> mockMvc.perform(get("/api/invoices?filter=id:eq:-1").with(as(admin)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalElements").value(0))))
                .isZero();

        // And the tile costs none either: the count rides inside the aggregate that was already
        // running, as a correlated EXISTS in its case expression (B2).
        assertThat(CountingStatements.reads("invoices",
                () -> mockMvc.perform(get("/api/invoices/summary").with(as(admin)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.awaitingApprovalCount").value(3))))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------------------- fixtures

    private List<String> dashboard() throws Exception {
        List<String> out = new java.util.ArrayList<>();
        for (String path : List.of("/api/dashboard/billed-by-month",
                "/api/dashboard/outstanding-by-age",
                "/api/dashboard/top-outstanding-customers",
                "/api/dashboard/collected-by-month",
                "/api/dashboard/top-paying-customers")) {
            out.add(body(mockMvc.perform(get(path).with(as(admin)))
                    .andExpect(status().isOk()).andReturn()));
        }
        return out;
    }

    private void threshold(Long regionId, String amount, boolean enabled) {
        ApprovalThreshold row = approvalThresholdRepository.findByRegionId(regionId)
                .orElseGet(() -> ApprovalThreshold.builder().regionId(regionId).build());
        row.setAmount(new BigDecimal(amount));
        row.setEnabled(enabled);
        approvalThresholdRepository.saveAndFlush(row);
    }

    /** Cancel an invoice big enough to be held, as the administrator, and return the change id. */
    private Long holdCancelOf(Invoice inv) throws Exception {
        return holdCancelAs(admin, inv);
    }

    private Long holdCancelAs(User who, Invoice inv) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/invoices/" + inv.getId() + "/cancel").with(as(who)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value(ApprovalDtos.Accepted.PENDING_APPROVAL))
                .andReturn();
        return objectMapper.readTree(body(result)).get("pendingChangeId").asLong();
    }

    private Invoice invoice(int quantity) {
        return invoice(acme, quantity);
    }

    private Invoice invoice(Customer c, int quantity) {
        actAs(admin);
        // The administrator is the Sales POC: they hold POC_ASSIGNABLE_SALES and a wildcard
        // MANAGE grant, so the same fixture works in a branch opened for this test.
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                c.getId(), Instant.parse("2026-03-01T09:00:00Z"), null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), quantity,
                        new BigDecimal("100.00")))));
    }

    private boolean flagOf(com.fasterxml.jackson.databind.JsonNode content, Long id) {
        return rowById(content, id).get("approvalPending").asBoolean();
    }

    private com.fasterxml.jackson.databind.JsonNode rowById(
            com.fasterxml.jackson.databind.JsonNode content, Long id) {
        for (com.fasterxml.jackson.databind.JsonNode row : content) {
            if (row.get("id").asLong() == id) return row;
        }
        throw new AssertionError("no row with id " + id + " in " + content);
    }

    private Role checkerRole() {
        return roleWith("READMODEL_CHECKER",
                Privileges.APPROVAL_VIEW, Privileges.APPROVAL_APPROVE,
                Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW, Privileges.INVOICE_MANAGE,
                Privileges.PAYMENT_VIEW, Privileges.PROMISE_VIEW, Privileges.SCOPE_OVERRIDE);
    }

    /** APPROVAL_VIEW and no right to decide anywhere: the "can read it, cannot press it" case. */
    private Role readerRole() {
        return roleWith("READMODEL_READER",
                Privileges.APPROVAL_VIEW, Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW,
                Privileges.PAYMENT_VIEW, Privileges.PROMISE_VIEW, Privileges.EXPORT_DATA,
                Privileges.SCOPE_OVERRIDE);
    }

    /** A customer-facing role that an operator gave APPROVAL_VIEW to by mistake. */
    private Role customerApproverRole() {
        return roleWith("READMODEL_CUSTOMER_APPROVER",
                Privileges.APPROVAL_VIEW, Privileges.INVOICE_VIEW, Privileges.PAYMENT_VIEW);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by ApprovalReadModelTest")
                .privileges(Arrays.stream(privileges)
                        .map(p -> privilegeRepository.findByName(p).orElseThrow())
                        .collect(Collectors.toCollection(HashSet<Privilege>::new)))
                .build()));
    }

    /** UTF-8 and not the response's own encoding, or every rupee sign comes back as mojibake. */
    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
