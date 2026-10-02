package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.MovableClock;
import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who sees a pending change (§5.11.9): its maker while they hold its region, the region's approvers, and whoever can
 * see the record it is about; nobody else, not even by id. A customer login sees what waits on its own records,
 * without who asked, never a create and never a change an internal dispute raised (SC-1), and has no list. The list
 * answers "which regions am I asking about", and each list row of a record says whether a change waits on it.
 */
class ApprovalVisibilityTest extends ApprovalTestBase {

    @Autowired PrivilegeRepository privilegeRepository;

    Region north;
    Region south;
    User maker;
    User approver;
    Customer acme;
    User acmeLogin;
    Invoice held;
    Invoice untouched;
    PendingChange cancel;

    @BeforeEach
    void setUp() throws Exception {
        north = workRegion("NORTH");
        south = workRegion("SOUTH");
        maker = makerIn(north);
        approver = approverIn(north);
        acme = customer("Acme Ltd", north);
        acmeLogin = customerUser("acme.login", acme.getId());
        // The seller works NORTH as a book: the invoices they sell, nothing else.
        grantRepository.deleteAllOfUser(seller.getId());
        grant(seller, north, AccessLevel.READ, RecordScope.BOOK);
        threshold(north, ApprovalOperation.INVOICE_CANCEL, "100.00");
        held = invoice(acme, "500.00");
        untouched = invoice(acme, "600.00");
        cancel(held.getId(), maker).andExpect(status().isAccepted());
        cancel = pendingOf("INVOICE", held.getId());
    }

    @Test
    void aBookColleagueSeesItAndSomeoneOutsideTheBookDoesNot() throws Exception {
        assertThat(ids(list(seller, null))).containsExactly(cancel.getId());
        assertThat(ids(body(forRecord(seller, "INVOICE", held.getId()).andExpect(status().isOk()))))
                .containsExactly(cancel.getId());
        detail(cancel.getId(), seller).andExpect(status().isOk());

        User otto = staffIn("otto", DataSeeder.ROLE_SALES_POC, north, AccessLevel.READ);
        grantRepository.deleteAllOfUser(otto.getId());
        grant(otto, north, AccessLevel.READ, RecordScope.BOOK);
        assertThat(list(otto, null).get("content")).isEmpty();
        assertThat(summary(otto).get("pendingCount").asLong()).isZero();
        detail(cancel.getId(), otto).andExpect(status().isNotFound());
        forRecord(otto, "INVOICE", held.getId()).andExpect(status().isNotFound());
    }

    /** SC-15: approving is a right in the region, seeing the record a right to the record; one without the other. */
    @Test
    void anApproverWithoutTheBookSeesTheChangeButNotTheRecordItIsAbout() throws Exception {
        User bookApprover = staffIn("bea", "ADMIN", north, AccessLevel.APPROVE);
        grantRepository.deleteAllOfUser(bookApprover.getId());
        grant(bookApprover, north, AccessLevel.APPROVE, RecordScope.BOOK);
        mockMvc.perform(get("/api/invoices/" + held.getId()).with(as(bookApprover)))
                .andExpect(status().isNotFound());

        assertThat(ids(list(bookApprover, null))).containsExactly(cancel.getId());
        JsonNode detail = body(detail(cancel.getId(), bookApprover).andExpect(status().isOk()));
        assertThat(detail.get("current").isNull()).as("the record as it is now, which they cannot see").isTrue();
        assertThat(detail.get("changes")).allSatisfy(c -> assertThat(c.get("current").isNull()).isTrue());
        assertThat(detail.get("canApprove").asBoolean()).isTrue();
        // RGN-R3-1: the blank "now" values are explained; approving stays a right in the region (SC-15).
        assertThat(detail.get("blockedReason").asText()).isEqualTo("You no longer have access to this record");
        assertThat(detail.get("canReject").asBoolean()).isTrue();

        JsonNode seen = body(detail(cancel.getId(), approver).andExpect(status().isOk()));
        assertThat(seen.get("current").get("status").asText()).isEqualTo("UNPAID");
        assertThat(seen.get("blockedReason").isNull()).as("an approver who sees the record").isTrue();
        JsonNode own = body(detail(cancel.getId(), maker).andExpect(status().isOk()));
        assertThat(own.get("current").isNull()).as("the maker sees the record").isFalse();
        assertThat(own.get("blockedReason").asText()).isEqualTo("You requested this change");

        // Tomorrow, asked about today: the as-of view is read-only before anything else.
        MovableClock.SHARED.set(AppClock.now().plus(Duration.ofDays(1)));
        JsonNode then = body(mockMvc.perform(get("/api/approvals/" + cancel.getId())
                        .param("asOf", Today.date().minusDays(1).toString()).with(as(bookApprover)))
                .andExpect(status().isOk()));
        assertThat(then.get("statusAt").asText()).isEqualTo("PENDING");
        assertThat(then.get("blockedReason").asText()).isEqualTo("Read-only in the as-of view");
        assertThat(then.get("canApprove").asBoolean()).isFalse();
    }

    /**
     * RGN-R4-1: whether the caller sees the record is asked of the region the record is in, not the one the change
     * was asked in. Once its customer moves out, a South-only approver still sees the (closed) change as a region
     * right (SC-15), but none of the record's present values, and is told why; a South-only reader loses the change
     * with the record. As of a date before the move, the record was in South, and both see the change then.
     */
    @Test
    void aChangeLeftBehindByARegionMoveShowsNoLiveValuesToTheOldRegion() throws Exception {
        threshold(south, ApprovalOperation.INVOICE_TERMS, "1000.00");
        User sally = approverIn(south);
        User vic = staffIn("vic", "VIEWER", south, AccessLevel.READ);
        User nico = staffIn("nico", "VIEWER", north, AccessLevel.READ);
        grant(seller, south, AccessLevel.READ, RecordScope.BOOK);
        User sam = makerIn(south);
        Customer southCo = customer("Southern Traders", south);
        Invoice inv = invoice(southCo, "2500.00");
        mockMvc.perform(patch("/api/invoices/" + inv.getId()).with(as(sam)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("dueDate", Today.date().plusDays(60).toString(),
                                "paymentTerm", "CUSTOM"))))
                .andExpect(status().isAccepted());
        PendingChange terms = pendingOf("INVOICE", inv.getId());
        assertThat(terms.getOperation()).isEqualTo(ApprovalOperation.INVOICE_TERMS);

        JsonNode pending = body(detail(terms.getId(), sally).andExpect(status().isOk()));
        assertThat(pending.get("current").isNull()).as("sally sees the record while it is in South").isFalse();
        assertThat(pending.get("changes")).isNotEmpty()
                .allSatisfy(c -> assertThat(c.get("current").isNull()).as(c.get("field").asText()).isFalse());
        assertThat(pending.get("blockedReason").isNull()).isTrue();
        assertThat(pending.get("canApprove").asBoolean()).isTrue();
        assertThat(body(detail(terms.getId(), vic).andExpect(status().isOk())).get("current").isNull()).isFalse();
        // A North-only reader has no part in a South record's change.
        detail(terms.getId(), nico).andExpect(status().isNotFound());
        assertThat(ids(list(nico, null))).doesNotContain(terms.getId());

        // The next day the customer moves to North, where neither sally nor vic holds anything.
        MovableClock.SHARED.set(AppClock.now().plus(Duration.ofDays(1)));
        mockMvc.perform(post("/api/customers/" + southCo.getId() + "/move-region").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("regionId", north.getId()))))
                .andExpect(status().isOk());
        assertThat(reload(terms).getStatus()).isEqualTo(PendingStatus.CLOSED);

        mockMvc.perform(get("/api/invoices/" + inv.getId()).with(as(sally))).andExpect(status().isNotFound());
        JsonNode left = body(detail(terms.getId(), sally).andExpect(status().isOk()));
        assertThat(left.get("status").asText()).isEqualTo("CLOSED");
        assertThat(left.get("current").isNull()).as("a North record's values, to a South-only approver").isTrue();
        assertThat(left.get("changes")).isNotEmpty().allSatisfy(c -> {
            assertThat(c.get("current").isNull()).as(c.get("field").asText()).isTrue();
            assertThat(c.get("currentText").isNull())
                    .as(c.get("field").asText()).isTrue();
        });
        assertThat(left.get("blockedReason").asText()).isEqualTo("You no longer have access to this record");
        detail(terms.getId(), vic).andExpect(status().isNotFound());
        JsonNode northern = body(detail(terms.getId(), admin).andExpect(status().isOk()));
        assertThat(northern.get("current").isNull()).as("the admin holds North").isFalse();

        // RGN-R5-1: the record now lives in North, so North's reader sees the change its History links to.
        mockMvc.perform(get("/api/invoices/" + inv.getId()).with(as(nico))).andExpect(status().isOk());
        JsonNode moved = body(detail(terms.getId(), nico).andExpect(status().isOk()));
        assertThat(moved.get("status").asText()).isEqualTo("CLOSED");
        assertThat(moved.get("current").isNull()).as("a North reader sees the North record now").isFalse();
        assertThat(moved.get("changes")).isNotEmpty()
                .allSatisfy(c -> assertThat(c.get("current").isNull()).as(c.get("field").asText()).isFalse());
        assertThat(moved.get("blockedReason").isNull()).isTrue();
        assertThat(moved.get("canApprove").asBoolean()).isFalse();
        assertThat(ids(list(nico, null))).contains(terms.getId());
        assertThat(ids(listWith(nico, "filter", "id:eq:" + terms.getId()))).containsExactly(terms.getId());
        assertThat(ids(listWith(nico, "regionIds", north.getId().toString()))).contains(terms.getId());
        // RGN-R4-1 still holds: South's approver keeps it, South's plain reader loses it.
        assertThat(ids(list(sally, null))).contains(terms.getId());
        assertThat(ids(list(vic, null))).doesNotContain(terms.getId());
        detail(terms.getId(), vic).andExpect(status().isNotFound());
        // The admin reaches it by the record (North) and as an approver where it was asked (South).
        assertThat(ids(listWith(admin, "regionIds", north.getId().toString()))).contains(terms.getId());
        assertThat(ids(listWith(admin, "regionIds", south.getId().toString()))).contains(terms.getId());

        // As of the day before the move, the record was in South: both saw the change then, read-only.
        MovableClock.SHARED.set(AppClock.now().plus(Duration.ofDays(1)));
        String before = Today.date().minusDays(2).toString();
        JsonNode sallyThen = body(mockMvc.perform(get("/api/approvals/" + terms.getId()).param("asOf", before)
                .with(as(sally))).andExpect(status().isOk()));
        assertThat(sallyThen.get("blockedReason").asText()).isEqualTo("Read-only in the as-of view");
        assertThat(sallyThen.get("current").isNull()).isTrue();
        mockMvc.perform(get("/api/approvals/" + terms.getId()).param("asOf", before).with(as(vic)))
                .andExpect(status().isOk());
        // Then the record was in South, out of North's sight.
        mockMvc.perform(get("/api/approvals/" + terms.getId()).param("asOf", before).with(as(nico)))
                .andExpect(status().isNotFound());
        assertThat(ids(listWith(nico, "asOf", before))).doesNotContain(terms.getId());
    }

    /** RGN-R5-1: a held create is seen through its customer, in the region the customer is in now. */
    @Test
    void aHeldCreateLeftBehindByARegionMoveFollowsTheCustomer() throws Exception {
        threshold(south, ApprovalOperation.INVOICE_CREATE, "1000.00");
        User sally = approverIn(south);
        User vic = staffIn("vic", "VIEWER", south, AccessLevel.READ);
        User nico = staffIn("nico", "VIEWER", north, AccessLevel.READ);
        grant(seller, south, AccessLevel.READ, RecordScope.BOOK);
        User sam = makerIn(south);
        Customer southCo = customer("Southern Traders", south);
        createInvoice(southCo, widget, 2500, sam).andExpect(status().isAccepted());
        PendingChange create = pendingChangeRepository.findAll().stream()
                .filter(pc -> pc.getOperation() == ApprovalOperation.INVOICE_CREATE).findFirst().orElseThrow();
        assertThat(create.getEntityId()).isNull();
        detail(create.getId(), nico).andExpect(status().isNotFound());
        detail(create.getId(), vic).andExpect(status().isOk());

        MovableClock.SHARED.set(AppClock.now().plus(Duration.ofDays(1)));
        mockMvc.perform(post("/api/customers/" + southCo.getId() + "/move-region").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("regionId", north.getId()))))
                .andExpect(status().isOk());
        assertThat(reload(create).getStatus()).isEqualTo(PendingStatus.CLOSED);

        detail(create.getId(), nico).andExpect(status().isOk());
        assertThat(ids(list(nico, null))).contains(create.getId());
        detail(create.getId(), vic).andExpect(status().isNotFound());
        assertThat(ids(list(vic, null))).doesNotContain(create.getId());
        JsonNode left = body(detail(create.getId(), sally).andExpect(status().isOk()));
        assertThat(left.get("blockedReason").asText()).isEqualTo("You no longer have access to this record");
        detail(create.getId(), sam).andExpect(status().isOk());
    }

    /** SC-15: a maker who loses the region loses their own request with it. */
    @Test
    void aMakerWhoLostTheRegionNoLongerSeesTheirChange() throws Exception {
        assertThat(ids(list(maker, "MINE"))).containsExactly(cancel.getId());

        grantRepository.deleteAllOfUser(maker.getId());
        grant(maker, south, AccessLevel.MANAGE, RecordScope.ALL);

        detail(cancel.getId(), maker).andExpect(status().isNotFound());
        assertThat(list(maker, "MINE").get("content")).isEmpty();
        withdraw(cancel.getId(), maker).andExpect(status().isNotFound());
        assertThat(reload(cancel).getStatus()).isEqualTo(PendingStatus.PENDING);
    }

    @Test
    void aCustomerLoginSeesWhatWaitsOnItsOwnRecordsWithoutWhoAskedAndHasNoList() throws Exception {
        Customer beta = customer("Beta Co", north);
        Invoice betas = invoice(beta, "300.00");
        threshold(north, ApprovalOperation.INVOICE_CREATE, "100.00");
        createInvoice(acme, widget, 500, maker).andExpect(status().isAccepted());
        PendingChange create = pendingChangeRepository.findAll().stream()
                .filter(pc -> pc.getOperation() == ApprovalOperation.INVOICE_CREATE).findFirst().orElseThrow();

        JsonNode rows = body(forRecord(acmeLogin, "INVOICE", held.getId()).andExpect(status().isOk()));
        assertThat(ids(rows)).containsExactly(cancel.getId());
        assertThat(rows.get(0).get("requestedBy").isNull()).isTrue();
        assertThat(rows.get(0).get("origin").isNull()).isTrue();
        JsonNode detail = body(detail(cancel.getId(), acmeLogin).andExpect(status().isOk()));
        assertThat(detail.get("requestedBy").isNull()).isTrue();
        assertThat(detail.get("decisionNote").isNull()).isTrue();

        detail(create.getId(), acmeLogin).andExpect(status().isNotFound());
        forRecord(acmeLogin, "CUSTOMER", acme.getId()).andExpect(status().isForbidden());
        assertThat(ids(body(forRecord(admin, "CUSTOMER", acme.getId()).andExpect(status().isOk()))))
                .containsExactly(create.getId());
        mockMvc.perform(get("/api/approvals").with(as(acmeLogin))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/approvals/summary").with(as(acmeLogin))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/approvals/awaiting-me/count").with(as(acmeLogin)))
                .andExpect(status().isForbidden());

        forRecord(acmeLogin, "INVOICE", betas.getId()).andExpect(status().isNotFound());
    }

    /** SC-1: a change an internal (automation) dispute raised is internal work, on every path a customer has. */
    @Test
    void aCustomerLoginNeverSeesAChangeAnInternalDisputeRaised() throws Exception {
        cancel.setInternalSource(true);
        pendingChangeRepository.save(cancel);

        assertThat(body(forRecord(acmeLogin, "INVOICE", held.getId()).andExpect(status().isOk()))).isEmpty();
        detail(cancel.getId(), acmeLogin).andExpect(status().isNotFound());
        assertThat(pendingFlag(acmeLogin, held.getId())).isFalse();
        assertThat(filtered(acmeLogin, "true")).isEmpty();
        assertThat(filtered(acmeLogin, "false")).contains(held.getId());
        assertThat(auditRows(acmeLogin, held.getId())).noneMatch(r -> r.get("action").asText().startsWith("CHANGE_"))
                .noneMatch(r -> r.hasNonNull("pendingStatus"));

        // Staff still see it everywhere.
        assertThat(ids(body(forRecord(admin, "INVOICE", held.getId()).andExpect(status().isOk()))))
                .containsExactly(cancel.getId());
        assertThat(pendingFlag(admin, held.getId())).isTrue();
        assertThat(filtered(admin, "true")).containsExactly(held.getId());
        assertThat(auditRows(admin, held.getId())).anySatisfy(r -> {
            assertThat(r.get("action").asText()).isEqualTo("CHANGE_REQUESTED");
            assertThat(r.get("pendingStatus").asText()).isEqualTo("PENDING");
        });
    }

    /**
     * SC-1 hides the request and its decision, not what the approved change did: the customer's own invoice was
     * cancelled, and its trail says so, without the link to the internal change.
     */
    @Test
    void aCustomerLoginSeesWhatAnInternalChangeDidOnceApproved() throws Exception {
        cancel.setInternalSource(true);
        pendingChangeRepository.save(cancel);
        approve(cancel.getId(), approver, "Automation was right").andExpect(status().isOk());

        List<JsonNode> rows = auditRows(acmeLogin, held.getId());
        assertThat(rows).noneMatch(r -> r.get("action").asText().startsWith("CHANGE_"));
        assertThat(rows).filteredOn(r -> r.get("action").asText().equals("INVOICE_CANCELLED")).singleElement()
                .satisfies(r -> {
                    assertThat(r.get("pendingChangeId").isNull()).isTrue();
                    assertThat(r.get("pendingStatus").isNull()).isTrue();
                });
        assertThat(auditRows(admin, held.getId()))
                .filteredOn(r -> r.get("action").asText().equals("INVOICE_CANCELLED")).singleElement()
                .satisfies(r -> assertThat(r.get("pendingChangeId").asLong()).isEqualTo(cancel.getId()));
    }

    @Test
    void theRegionsAskedAboutNarrowTheList() throws Exception {
        Customer southCo = customer("South Co", south);
        grant(seller, south, AccessLevel.READ, RecordScope.BOOK);
        threshold(south, ApprovalOperation.INVOICE_CANCEL, "100.00");
        User southMaker = makerIn(south);
        Invoice inSouth = invoice(southCo, "400.00");
        cancel(inSouth.getId(), southMaker).andExpect(status().isAccepted());
        PendingChange southCancel = pendingOf("INVOICE", inSouth.getId());

        assertThat(ids(list(admin, null))).containsExactlyInAnyOrder(cancel.getId(), southCancel.getId());
        JsonNode narrowed = body(mockMvc.perform(get("/api/approvals").with(as(admin))
                .param("regionIds", south.getId().toString())).andExpect(status().isOk()));
        assertThat(ids(narrowed)).containsExactly(southCancel.getId());
        assertThat(narrowed.get("regionIds")).extracting(JsonNode::asLong).containsExactly(south.getId());
        assertThat(narrowed.get("lockedFilters")).isEmpty();
        JsonNode count = body(mockMvc.perform(get("/api/approvals/awaiting-me/count").with(as(admin))
                .param("regionIds", north.getId().toString())).andExpect(status().isOk()));
        assertThat(count.get("count").asLong()).isEqualTo(1);
        // By id, whatever is asked: a deep link from a notice always opens (I3).
        mockMvc.perform(get("/api/approvals/" + southCancel.getId()).with(as(admin))
                .param("regionIds", north.getId().toString())).andExpect(status().isOk());
    }

    @Test
    void theViewSaysWhoseChangesTheListIsAbout() throws Exception {
        // One asked for by an approver: never theirs to decide.
        Invoice mine = invoice(acme, "700.00");
        cancel(mine.getId(), admin).andExpect(status().isAccepted());
        PendingChange admins = pendingOf("INVOICE", mine.getId());

        assertThat(ids(list(approver, "AWAITING_ME"))).containsExactlyInAnyOrder(cancel.getId(), admins.getId());
        assertThat(ids(list(admin, "AWAITING_ME"))).containsExactly(cancel.getId());
        assertThat(ids(list(admin, "MINE"))).containsExactly(admins.getId());
        assertThat(ids(list(maker, "MINE"))).containsExactly(cancel.getId());
        assertThat(list(maker, "AWAITING_ME").get("content")).as("the maker approves nothing").isEmpty();
        assertThat(ids(list(approver, "ALL"))).containsExactlyInAnyOrder(cancel.getId(), admins.getId());
        mockMvc.perform(get("/api/approvals").with(as(admin)).param("view", "SOMEONE"))
                .andExpect(status().isBadRequest());

        JsonNode tiles = summary(admin);
        assertThat(tiles.get("pendingCount").asLong()).isEqualTo(2);
        assertThat(tiles.get("pendingAmount").decimalValue()).isEqualByComparingTo("1200.00");
        assertThat(tiles.get("awaitingMeCount").asLong()).isEqualTo(1);
        assertThat(tiles.get("mineCount").asLong()).isEqualTo(1);

        // A decided change stays in ALL, and no longer waits.
        reject(cancel.getId(), approver, "Not this month").andExpect(status().isOk());
        assertThat(ids(list(admin, "ALL"))).contains(cancel.getId());
        assertThat(list(admin, "AWAITING_ME").get("content")).isEmpty();
        assertThat(summary(admin).get("pendingCount").asLong()).isEqualTo(1);
        JsonNode filtered = body(mockMvc.perform(get("/api/approvals").with(as(admin))
                .param("filter", "status:eq:REJECTED").param("sort", "amount,desc")).andExpect(status().isOk()));
        assertThat(ids(filtered)).containsExactly(cancel.getId());
        assertThat(filtered.at("/content/0/statusAt").isNull()).as("live").isTrue();
    }

    /** Every record list says whether a change waits on each row, and can be filtered by it (§5.11.9). */
    @Test
    void recordListsFlagAndFilterWhatWaitsForApproval() throws Exception {
        assertThat(pendingFlag(admin, held.getId())).isTrue();
        assertThat(pendingFlag(admin, untouched.getId())).isFalse();
        assertThat(filtered(admin, "true")).containsExactly(held.getId());
        assertThat(filtered(admin, "false")).containsExactly(untouched.getId());
        assertThat(filtered(seller, "true")).as("the seller sees the invoices they sell").containsExactly(held.getId());

        // A dispute's approval waits on the dispute, and on the payment whose money it moves.
        User collector = collectorIn(north);
        actAs(admin);
        Payment payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("100.00"), "Bank", null, List.of(untouched.getId()), collector.getId(), null));
        SecurityContextHolder.clearContext();
        threshold(north, ApprovalOperation.DISPUTE_APPROVE, "10.00");
        Long disputeId = openDispute(payment.getId());
        User resolver = staffIn("resolver", "ADMIN", north, AccessLevel.MANAGE);
        mockMvc.perform(post("/api/disputes/" + disputeId + "/approve").with(as(resolver))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isAccepted());

        JsonNode payments = body(mockMvc.perform(get("/api/payments").with(as(admin))).andExpect(status().isOk()));
        assertThat(payments.at("/content/0/pendingApproval").asBoolean()).isTrue();
        JsonNode disputes = body(mockMvc.perform(get("/api/disputes").with(as(admin))
                .param("filter", "pendingApproval:eq:true")).andExpect(status().isOk()));
        assertThat(ids(disputes)).containsExactly(disputeId);

        // And a promise whose change waits.
        threshold(north, ApprovalOperation.PROMISE_UPDATE, "10.00");
        Long promiseId = promise(acme, "300.00", collector).getId();
        putPromise(promiseId, "100.00", Today.date().plusDays(10), null,
                collector).andExpect(status().isAccepted());
        JsonNode promises = body(mockMvc.perform(get("/api/promises").with(as(admin))
                .param("filter", "pendingApproval:eq:true")).andExpect(status().isOk()));
        assertThat(ids(promises)).containsExactly(promiseId);
        assertThat(promises.at("/content/0/pendingApproval").asBoolean()).isTrue();

        mockMvc.perform(get("/api/invoices").with(as(admin)).param("filter", "pendingApproval:eq:maybe"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/invoices").with(as(admin)).param("sort", "pendingApproval,asc"))
                .andExpect(status().isBadRequest());
        // The detail read carries no such flag: it is the list's.
        mockMvc.perform(get("/api/invoices/" + held.getId()).with(as(admin)))
                .andExpect(jsonPath("$.pendingApproval").doesNotExist());
    }

    /** A change of a region's limits belongs to those who approve or set limits there; nobody else sees it. */
    @Test
    void aChangeOfLimitsIsSeenOnlyByTheRegionsApproversAndLimitSetters() throws Exception {
        mockMvc.perform(put("/api/regions/" + north.getId() + "/approval-thresholds").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("thresholds", List.of(Map.of("operation", "INVOICE_CANCEL",
                        "amount", new BigDecimal("1000.00")))))))
                .andExpect(status().isAccepted());
        PendingChange loosening = pendingChangeRepository.findByOutstandingKey("REGION_THRESHOLDS:" + north.getId())
                .orElseThrow();

        User setter = staffIn("lima", limitSetterRole().getName(), north, AccessLevel.APPROVE);
        assertThat(ids(list(approver, null))).contains(loosening.getId());
        assertThat(ids(list(setter, null))).containsExactly(loosening.getId());
        detail(loosening.getId(), setter).andExpect(status().isOk());

        User reader = staffIn("vera", "VIEWER", north, AccessLevel.READ);
        assertThat(ids(list(reader, null))).doesNotContain(loosening.getId()).contains(cancel.getId());
        detail(loosening.getId(), reader).andExpect(status().isNotFound());
        assertThat(ids(list(maker, null))).doesNotContain(loosening.getId());
        detail(loosening.getId(), acmeLogin).andExpect(status().isNotFound());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Sets limits in a region, and sees invoices; cannot approve changes. */
    private Role limitSetterRole() {
        Role role = roleRepository.findByName("LIMIT_SETTER")
                .orElseGet(() -> Role.builder().name("LIMIT_SETTER").description("LIMIT_SETTER").build());
        role.setPrivileges(new HashSet<>(List.of(
                privilegeRepository.findByName(Privileges.APPROVAL_THRESHOLD_MANAGE).orElseThrow(),
                privilegeRepository.findByName(Privileges.INVOICE_VIEW).orElseThrow())));
        return roleRepository.save(role);
    }

    private Long openDispute(Long paymentId) throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("targetType", "PAYMENT");
        req.put("targetId", paymentId);
        req.put("reason", "Paid twice");
        req.put("proposedChangeJson", "{\"action\":\"void\"}");
        return body(mockMvc.perform(post("/api/disputes").with(as(acmeLogin))
                .contentType(MediaType.APPLICATION_JSON).content(json(req))).andExpect(status().isOk()))
                .get("id").asLong();
    }

    private JsonNode list(User as, String view) throws Exception {
        var request = get("/api/approvals").with(as(as));
        if (view != null) request.param("view", view);
        return body(mockMvc.perform(request).andExpect(status().isOk()));
    }

    private JsonNode listWith(User as, String param, String value) throws Exception {
        return body(mockMvc.perform(get("/api/approvals").with(as(as)).param(param, value))
                .andExpect(status().isOk()));
    }

    private JsonNode summary(User as) throws Exception {
        return body(mockMvc.perform(get("/api/approvals/summary").with(as(as))).andExpect(status().isOk()));
    }

    private ResultActions detail(Long id, User as) throws Exception {
        return mockMvc.perform(get("/api/approvals/" + id).with(as(as)));
    }

    private ResultActions forRecord(User as, String type, Long id) throws Exception {
        return mockMvc.perform(get("/api/approvals/for-record").with(as(as))
                .param("entityType", type).param("entityId", id.toString()));
    }

    private boolean pendingFlag(User as, Long invoiceId) throws Exception {
        JsonNode page = body(mockMvc.perform(get("/api/invoices").with(as(as))).andExpect(status().isOk()));
        return StreamSupport.stream(page.get("content").spliterator(), false)
                .filter(r -> r.get("id").asLong() == invoiceId).findFirst().orElseThrow()
                .get("pendingApproval").asBoolean();
    }

    private List<Long> filtered(User as, String value) throws Exception {
        return ids(body(mockMvc.perform(get("/api/invoices").with(as(as))
                .param("filter", "pendingApproval:eq:" + value)).andExpect(status().isOk())));
    }

    private List<JsonNode> auditRows(User as, Long invoiceId) throws Exception {
        JsonNode rows = body(mockMvc.perform(get("/api/audit").with(as(as))
                .param("entityType", "INVOICE").param("entityId", invoiceId.toString())).andExpect(status().isOk()));
        return StreamSupport.stream(rows.spliterator(), false).toList();
    }

    /** The ids of a page's rows, or of a plain array. */
    private static List<Long> ids(JsonNode node) {
        JsonNode rows = node.isArray() ? node : node.get("content");
        return StreamSupport.stream(rows.spliterator(), false).map(r -> r.get("id").asLong()).toList();
    }
}
