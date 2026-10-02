package com.geneinvoice.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.MovableClock;
import com.geneinvoice.approval.ApprovalOperation;
import com.geneinvoice.approval.ApprovalService;
import com.geneinvoice.approval.ApprovalThreshold;
import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.CustomerRegionMoveService;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionMoveDtos;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One customer's story over four days, told through the application, so that every as-of read can be checked
 * against what the screens said on each of those days (§8.4). Today is {@link #TODAY}, the day after D4.
 * <ul>
 *   <li><b>D1</b>: customer Acme Ltd in NORTH, with invoices X (1000, due D2) and Y (500, due D1 + 8), both sold by
 *   Sam, who also holds Acme's Customer Success seat; Beta Co in NORTH with invoice B (100); product Widget.</li>
 *   <li><b>D2</b>: a payment of 400 on X and a promise of 600 linked to X, both worked by Colin; Y's one line
 *   becomes two of 250.</li>
 *   <li><b>D3</b>: Acme is renamed Zeta Holdings and moved to SOUTH; Sam's seat is removed; Widget is renamed
 *   Widget Pro; customer Gamma Co and product Gadget are created.</li>
 *   <li><b>D4</b>: a dispute voids the payment; Y is cancelled.</li>
 * </ul>
 * {@link #xExtendedWithApproval()} tells D5 to D7 on top of it, for approvals as of a date.
 */
public abstract class AsOfScenario extends IntegrationTestBase {

    public static final LocalDate D0 = LocalDate.of(2026, 3, 1);
    public static final LocalDate D1 = D0.plusDays(1);
    public static final LocalDate D2 = D0.plusDays(2);
    public static final LocalDate D3 = D0.plusDays(3);
    public static final LocalDate D4 = D0.plusDays(4);
    public static final LocalDate TODAY = D0.plusDays(5);
    /** The approval story's days (after the four above; D5 is {@link #TODAY}'s date, which it moves on from). */
    public static final LocalDate D5 = D0.plusDays(5);
    public static final LocalDate D6 = D0.plusDays(6);
    public static final LocalDate D7 = D0.plusDays(7);
    public static final LocalDate AFTER_D7 = D0.plusDays(8);

    @Autowired protected InvoiceService invoiceService;
    @Autowired protected PaymentService paymentService;
    @Autowired protected PaymentPromiseService promiseService;
    @Autowired protected DisputeService disputeService;
    @Autowired protected CustomerService customerService;
    @Autowired protected PocService pocService;
    @Autowired protected CustomerRegionMoveService moveService;
    @Autowired protected PrivilegeRepository privilegeRepository;
    @Autowired protected ApprovalService approvalService;

    protected User admin;
    protected User sam;
    protected User colin;
    protected Region north;
    protected Region south;
    protected Product widget;
    protected Product gadget;
    protected Customer acme;
    protected Customer beta;
    protected Customer gamma;
    protected Long x;
    protected Long y;
    protected Long b;
    protected Long paymentId;
    protected Long promiseId;
    protected Long seatId;
    protected Long disputeId;

    @BeforeEach
    void playTheScenario() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        north = region("NORTH");
        south = region("SOUTH");
        grant(admin, north, AccessLevel.APPROVE, RecordScope.ALL);
        grant(admin, south, AccessLevel.APPROVE, RecordScope.ALL);
        sam = user("sam.sales", salesAndSuccessRole().getName());
        grant(sam, north, AccessLevel.READ, RecordScope.BOOK);
        grant(sam, south, AccessLevel.READ, RecordScope.BOOK);
        colin = user("colin.collections", DataSeeder.ROLE_COLLECTION_POC);
        grant(colin, north, AccessLevel.READ, RecordScope.BOOK);
        grant(colin, south, AccessLevel.READ, RecordScope.BOOK);

        on(D1);
        widget = product("Widget", "1000.00");
        acme = customer("Acme Ltd", north);
        beta = customer("Beta Co", north);
        actAs(admin);
        seatId = pocService.add(acme.getId(), PocType.SUCCESS, sam.getId(), true).getId();
        x = invoice(acme, D2, null);
        y = invoice(acme, D1.plusDays(8), new BigDecimal("500.00"));
        b = invoice(beta, D1.plusDays(30), new BigDecimal("100.00"));

        on(D2);
        paymentId = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("400.00"), "Cash", null, List.of(x), colin.getId(), null)).getId();
        promiseId = promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(),
                new BigDecimal("600.00"), D2.plusDays(7), colin.getId(), null, List.of(x))).id();
        invoiceService.replaceItems(y, List.of(new InvoiceDtos.LineInput(widget.getId(), 2,
                new BigDecimal("250.00"))), null);

        on(D3);
        customerService.update(acme.getId(),
                new CustomerDtos.CustomerUpdateRequest("Zeta Holdings", null, null, null, null, null));
        moveService.move(acme.getId(), new RegionMoveDtos.MoveRequest(south.getId(), null, "Reorganised"));
        pocService.remove(acme.getId(), seatId);
        Product renamed = productRepository.findById(widget.getId()).orElseThrow();
        renamed.setName("Widget Pro");
        productRepository.save(renamed);
        gamma = customer("Gamma Co", north);
        gadget = product("Gadget", "20.00");

        on(D4);
        actAs(customerUser("acme.login", acme.getId()));
        disputeId = disputeService.open(new DisputeDtos.CreateDisputeRequest(DisputeTargetType.PAYMENT, paymentId,
                "Paid twice", "{\"action\":\"void\"}")).getId();
        actAs(admin);
        disputeService.approve(disputeId, new DisputeDtos.ResolveDisputeRequest("Refunded", null));
        invoiceService.cancel(y);

        SecurityContextHolder.clearContext();
        MovableClock.SHARED.set(TODAY.atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC));
    }

    /**
     * A second dispute, told on top of the story: on D2 Beta's login disputes invoice B (100); on D3 Beta Co is
     * renamed Beta Group, B's line becomes 150 and the dispute is denied. The clock is back at {@link #TODAY} after.
     */
    protected Long betaDisputesB() {
        on(D2);
        actAs(customerUser("beta.login", beta.getId()));
        Long id = disputeService.open(new DisputeDtos.CreateDisputeRequest(DisputeTargetType.INVOICE, b,
                "Not ordered", "{\"action\":\"cancel\"}")).getId();
        on(D3);
        actAs(admin);
        customerService.update(beta.getId(),
                new CustomerDtos.CustomerUpdateRequest("Beta Group", null, null, null, null, null));
        invoiceService.replaceItems(b, List.of(new InvoiceDtos.LineInput(widget.getId(), 1,
                new BigDecimal("150.00"))), null);
        disputeService.deny(id, new DisputeDtos.ResolveDisputeRequest("It was ordered", null));
        SecurityContextHolder.clearContext();
        on(TODAY);
        return id;
    }

    /**
     * X's due date moved later, held for approval and approved, told on top of the story. SOUTH (where Acme is from
     * D3) holds any extension of a balance over 100. <b>D5</b>: Carl, a SOUTH cashier, asks to move X's due date
     * (its whole 1000 is unpaid since the void); <b>D6</b>: he asks again for another date, which replaces the first
     * request; <b>D7</b>: admin approves the second. Today is then {@link #AFTER_D7}. Returns both changes' ids,
     * first and second.
     */
    protected Long[] xExtendedWithApproval() {
        approvalThresholdRepository.save(ApprovalThreshold.builder().region(south)
                .operation(ApprovalOperation.INVOICE_TERMS).amount(new BigDecimal("100.00")).windowHours(0)
                .updatedByUserId(admin.getId()).updatedAt(AppClock.now()).build());
        User carl = user("carl.cashier", "CASHIER");
        grant(carl, south, AccessLevel.MANAGE, RecordScope.ALL);
        on(D5);
        actAs(carl);
        Long first = invoiceService.updateOrHold(x, new InvoiceDtos.UpdateInvoiceRequest(null, null,
                D5.plusDays(30), null)).heldChange().orElseThrow().getId();
        on(D6);
        Long second = invoiceService.updateOrHold(x, new InvoiceDtos.UpdateInvoiceRequest(null, null,
                D6.plusDays(60), null)).heldChange().orElseThrow().getId();
        on(D7);
        actAs(admin);
        approvalService.approve(second, "Agreed with the customer");
        SecurityContextHolder.clearContext();
        on(AFTER_D7);
        return new Long[] {first, second};
    }

    /** Ten in the morning of {@code day}, UTC. */
    protected static void on(LocalDate day) {
        MovableClock.SHARED.set(day.atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC));
    }

    /** SALES_POC's privileges, and Customer Success seats too: Sam sells and looks after the account. */
    private Role salesAndSuccessRole() {
        Role role = roleRepository.findByName("SALES_AND_SUCCESS")
                .orElseGet(() -> Role.builder().name("SALES_AND_SUCCESS").description("SALES_AND_SUCCESS").build());
        role.setPrivileges(new HashSet<>(role(DataSeeder.ROLE_SALES_POC).getPrivileges()));
        role.getPrivileges().add(privilegeRepository.findByName(Privileges.POC_ASSIGNABLE_SUCCESS).orElseThrow());
        return roleRepository.save(role);
    }

    /** One line of Widget, at its price or at {@code price}, sold by Sam. */
    private Long invoice(Customer c, LocalDate due, BigDecimal price) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(), null, due, null, null,
                sam.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, price)))).getId();
    }

    /** {@code request} as {@code caller}, looking at {@code day} (null: the live view). */
    protected ResultActions call(MockHttpServletRequestBuilder request, User caller, LocalDate day) throws Exception {
        if (day != null) request.param("asOf", day.toString());
        return mockMvc.perform(request.with(as(caller)));
    }

    /** A GET as admin on {@code day}, which must answer 200; its JSON. */
    protected JsonNode read(String url, LocalDate day, String... filters) throws Exception {
        MockHttpServletRequestBuilder request = get(url);
        for (String f : filters) request.param("filter", f);
        return body(call(request, admin, day).andExpect(status().isOk()));
    }

    /** An export as admin on {@code day}: every row matching {@code filters}; the CSV. */
    protected String export(String url, LocalDate day, String... filters) throws Exception {
        String body = json(java.util.Map.of("selectAllMatchingFilter", true, "filters", List.of(filters)));
        return call(post(url).contentType(MediaType.APPLICATION_JSON).content(body), admin, day)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
