package com.geneinvoice.promise;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a promise says it is still owed (PPD-04), who may move its Collection POC (PPD-05), how its
 * broken-promise notification reads (PPD-06), and how a bulk run reports a row that does not
 * qualify (TBL-05).
 */
class PromiseFiguresAndPocTest extends IntegrationTestBase {

    @Autowired PaymentPromiseService promiseService;
    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PocService pocService;
    @Autowired PrivilegeRepository privilegeRepository;

    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    static final LocalDate TOMORROW = TODAY.plusDays(1);
    static final LocalDate YESTERDAY = TODAY.minusDays(1);

    User admin;
    User collections;
    User otherCollections;
    Customer acme;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        otherCollections = user("colin.collections", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, collections.getId(), true);
    }

    private Invoice invoice(String unitPrice, int qty) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                acme.getId(), null, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), qty, new BigDecimal(unitPrice)))));
    }

    private PromiseDtos.PromiseDto promise(String amount, LocalDate date, List<Long> invoiceIds) {
        return promiseService.create(new PromiseDtos.CreatePromiseRequest(
                acme.getId(), new BigDecimal(amount), date, null, "note", invoiceIds, null));
    }

    // ---- PPD-04: a promise's "remaining" agrees with its status --------------------

    /**
     * A promise against an invoice that has since been settled some other way is KEPT with nothing
     * fulfilled against it, so amount − fulfilled still read as the whole amount: the row said
     * both "kept" and "still owes 100" (PPD-04).
     */
    @Test
    void aKeptPromiseOwesNothing() {
        Invoice settled = invoice("100.00", 1);
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("100.00"), "Cash", null, List.of(settled.getId()),
                collections.getId(), null));

        PromiseDtos.PromiseDto kept = promise("100", TOMORROW, List.of(settled.getId()));

        assertThat(kept.status()).isEqualTo(PromiseStatus.KEPT);
        assertThat(kept.remainingAmount()).isEqualByComparingTo("0");
    }

    /** A promise withdrawn in error is not a debt either, whatever it was raised for. */
    @Test
    void aCancelledPromiseOwesNothing() {
        PromiseDtos.PromiseDto live = promise("600", TOMORROW, List.of());

        PromiseDtos.PromiseDto withdrawn = promiseService.cancel(live.id(), "raised in error");

        assertThat(withdrawn.status()).isEqualTo(PromiseStatus.CANCELLED);
        assertThat(withdrawn.remainingAmount()).isEqualByComparingTo("0");
    }

    /** A promise that really is still owed keeps the arithmetic it always had. */
    @Test
    void anOpenPromiseStillShowsWhatIsLeftToPay() {
        Invoice big = invoice("1000.00", 1);
        PromiseDtos.PromiseDto part = promise("400", TOMORROW, List.of(big.getId()));
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("150.00"), "Cash", null, List.of(big.getId()),
                collections.getId(), null));

        PromiseDtos.PromiseDto open = promiseService.dto(part.id());

        assertThat(open.status()).isEqualTo(PromiseStatus.PARTIALLY_KEPT);
        assertThat(open.remainingAmount()).isEqualByComparingTo("250");
    }

    /**
     * The column is sortable and filterable, so the criteria expression has to say the same thing
     * the row does — otherwise "Remaining is 0" would not find a kept promise.
     */
    @Test
    void filteringOnRemainingAgreesWithWhatTheRowShows() throws Exception {
        Invoice settled = invoice("100.00", 1);
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("100.00"), "Cash", null, List.of(settled.getId()),
                collections.getId(), null));
        PromiseDtos.PromiseDto kept = promise("100", TOMORROW, List.of(settled.getId()));
        assertThat(kept.status()).isEqualTo(PromiseStatus.KEPT);

        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get("/api/promises")
                        .param("remainingAmount", "eq:0")
                        .with(as(admin)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(page.get("content")).hasSize(1);
        assertThat(page.get("content").get(0).get("id").asLong()).isEqualTo(kept.id());
    }

    // ---- PPD-06: the broken-promise notification reads as money --------------------

    /**
     * The Collection POC reads the same figure the promise page shows them; it used to arrive as a
     * bare "777.00" while every screen said ₹777.00 (PPD-06).
     */
    @Test
    void theBrokenPromiseNotificationFormatsTheAmountAsMoney() {
        Invoice unpaid = invoice("777.00", 1);

        promise("777", YESTERDAY, List.of(unpaid.getId()));

        List<Notification> broken = notificationRepository.findAll().stream()
                .filter(n -> "PROMISE_BROKEN".equals(n.getType()))
                .toList();
        assertThat(broken).hasSize(1);
        assertThat(broken.get(0).getMessage()).contains("₹777.00").doesNotContain("promised 777.00");
    }

    // ---- PPD-05: moving a promise's Collection POC needs POC_ASSIGN ----------------

    /**
     * A user who may manage promises but not assign POCs is refused on a payment and was allowed
     * on a promise, single-record and in bulk alike (PPD-05).
     */
    private User promiseManagerWithoutPocAssign() {
        Role role = roleRepository.findByName("PROMISE_CLERK").orElseGet(() -> roleRepository.save(
                Role.builder()
                        .name("PROMISE_CLERK")
                        .description("Manages promises but may not assign POCs")
                        .privileges(Stream.of(Privileges.PROMISE_VIEW, Privileges.PROMISE_MANAGE,
                                        Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW,
                                        Privileges.POC_VIEW, Privileges.SCOPE_OVERRIDE)
                                .map(n -> privilegeRepository.findByName(n).orElseThrow())
                                .collect(Collectors.toCollection(HashSet::new)))
                        .build()));
        return user("pat.promises", role.getName());
    }

    @Test
    void aPromisesCollectionPocCannotBeMovedWithoutPocAssign() throws Exception {
        PromiseDtos.PromiseDto p = promise("100", TOMORROW, List.of());
        User clerk = promiseManagerWithoutPocAssign();

        mockMvc.perform(put("/api/promises/" + p.id()).with(as(clerk))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("amount", "100", "promisedDate", TOMORROW.toString(),
                                "collectionPocUserId", otherCollections.getId()))))
                .andExpect(status().isBadRequest());

        assertThat(promiseRepository.findById(p.id()).orElseThrow().getCollectionPoc().getId())
                .isEqualTo(collections.getId());
    }

    @Test
    void theBulkReassignRefusesTheSameCallerTheSingleRecordEndpointDoes() throws Exception {
        PromiseDtos.PromiseDto p = promise("100", TOMORROW, List.of());
        User clerk = promiseManagerWithoutPocAssign();

        mockMvc.perform(post("/api/promises/bulk").with(as(clerk))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("REASSIGN_COLLECTION_POC",
                                "ids", List.of(p.id()),
                                "params", Map.of("userId", otherCollections.getId())))))
                .andExpect(status().isBadRequest());

        assertThat(promiseRepository.findById(p.id()).orElseThrow().getCollectionPoc().getId())
                .isEqualTo(collections.getId());
    }

    /** A caller who does hold POC_ASSIGN is unaffected. */
    @Test
    void anAdministratorStillMovesAPromisesCollectionPoc() {
        PromiseDtos.PromiseDto p = promise("100", TOMORROW, List.of());

        promiseService.reassignCollectionPoc(p.id(), otherCollections.getId());

        assertThat(promiseRepository.findById(p.id()).orElseThrow().getCollectionPoc().getId())
                .isEqualTo(otherCollections.getId());
    }

    // ---- TBL-05: a promise that does not qualify is skipped, not failed ------------

    @Test
    void bulkCancellingAnAlreadyCancelledPromiseSkipsItRatherThanFailingIt() throws Exception {
        PromiseDtos.PromiseDto p = promise("100", TOMORROW, List.of());
        promiseService.cancel(p.id(), "raised in error");

        MvcResult result = mockMvc.perform(post("/api/promises/bulk").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("CANCEL", "ids", List.of(p.id())))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(body.get("failed")).isEmpty();
        assertThat(body.get("skipped").size()).isEqualTo(1);
        assertThat(body.get("skipped").get(0).get("reason").asText())
                .isEqualTo("Promise is already cancelled");
    }

    // ---- AUTH-05: recompute is gated on a privilege, not on the ADMIN role name ----

    /**
     * Every other endpoint is keyed on a privilege, so a role composed of privileges can reach
     * them all. This one named the ADMIN role, which no tailored role can hold (AUTH-05).
     */
    @Test
    void recomputeIsReachableByARoleHoldingThePromiseOverridePrivilege() throws Exception {
        Role role = roleRepository.save(Role.builder()
                .name("PROMISE_OVERSEER")
                .description("May take charge of promise status by hand")
                .privileges(Stream.of(Privileges.PROMISE_VIEW, Privileges.PROMISE_OVERRIDE,
                                Privileges.SCOPE_OVERRIDE)
                        .map(n -> privilegeRepository.findByName(n).orElseThrow())
                        .collect(Collectors.toCollection(HashSet::new)))
                .build());
        User overseer = user("olly.overseer", role.getName());

        mockMvc.perform(post("/api/promises/recompute").with(as(overseer)))
                .andExpect(status().isOk());
    }

    /** It is still not open to a promise reader who was given no override privilege. */
    @Test
    void recomputeStaysClosedToARoleWithoutTheOverridePrivilege() throws Exception {
        User clerk = promiseManagerWithoutPocAssign();

        mockMvc.perform(post("/api/promises/recompute").with(as(clerk)))
                .andExpect(status().isForbidden());
    }

    private Map<String, Object> request(String action, Object... kv) {
        Map<String, Object> m = new HashMap<>();
        m.put("action", action);
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
