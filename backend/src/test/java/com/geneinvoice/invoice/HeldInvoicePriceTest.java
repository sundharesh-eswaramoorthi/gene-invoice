package com.geneinvoice.invoice;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.approval.ApprovalThreshold;
import com.geneinvoice.approval.PendingApprovalException;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.privilege.Privilege;
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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE AMOUNT APPROVED IS THE AMOUNT APPLIED.
 *
 * <p>A line with no unitPrice is priced from the catalogue inside buildLines, and the shipped
 * Flutter form never sends one — so if the PARKED payload stores the request as it arrived, the
 * applier re-prices it from products.price as it stands at approval time. The exposure, the
 * summary the approver read and the CHANGE_APPROVED audit row would all still say what it cost
 * when it was raised, while the invoice that lands says something else. Product.price is
 * deliberately ungated (B2-maker-checker.md:126) on the premise that a catalogue price is COPIED
 * onto the line at create; for a HELD create there is no line yet to have copied it, so the copy
 * is made into the payload instead (B2).
 */
class HeldInvoicePriceTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PrivilegeRepository privilegeRepository;

    static final String LAKH = "100000.00";

    User admin;
    User maker;
    User checker;
    Customer acme;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        maker = user("mary.maker", makerRole().getName());
        checker = user("carl.checker", checkerRole().getName());
        acme = customer("Acme Ltd");
        widget = product("Widget", "1200.00");
        actAs(admin);
    }

    @Test
    void aHeldInvoiceIsRaisedAtThePriceItWasApprovedAtAndNotAtTodaysCatalogue() throws Exception {
        threshold(defaultRegion().getId(), LAKH, true);

        // Byte for byte what the shipped form sends: no unitPrice, so buildLines prices it from
        // the catalogue — 100 x 1,200.00 = 1,20,000.00, which is above the branch's limit.
        MvcResult held = mockMvc.perform(post("/api/invoices").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(unpricedRequest(100))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.exposure").value(120000.00))
                .andReturn();

        Long changeId = jsonLong(held, "pendingChangeId");
        PendingChange waiting = pendingChangeRepository.findById(changeId).orElseThrow();
        // The invariant, stated where it is cheapest to read: the payload the applier will replay
        // carries the price the exposure was measured from.
        assertThat(waiting.getPayloadJson()).contains("\"unitPrice\":1200.00");
        assertThat(invoiceRepository.count()).isZero();

        // The quarterly price update lands while the change sits in the queue. PRODUCT_MANAGE is
        // company-wide, unregioned and ungated — that is the named hole, and it is allowed to be
        // one precisely because a catalogue price is copied onto the line rather than referenced.
        repriceWidget("12000.00");

        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.change.status").value("APPROVED"));

        Invoice raised = invoiceRepository.findAll().get(0);
        // Ten times the amount anybody agreed to, with a clean four-eyes trail, is what this
        // number being 1200000.00 would mean.
        assertThat(raised.getTotal()).isEqualByComparingTo(new BigDecimal("120000.00"))
                .isEqualByComparingTo(waiting.getExposure());
        mockMvc.perform(get("/api/invoices/" + raised.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unitPrice").value(1200.00));
    }

    @Test
    void anInvoiceThatWasNeverHeldIsStillPricedFromTheCatalogueAsItIsNow() throws Exception {
        // The control beside the test above: freezing happens at the GATE, so nothing about an
        // ordinary save changes. A price rise applies to the next invoice raised, as it always has.
        repriceWidget("12000.00");
        actAs(maker);

        Invoice straightThrough = invoiceService.create(unpricedRequest(1));

        assertThat(straightThrough.getTotal()).isEqualByComparingTo(new BigDecimal("12000.00"));
    }

    @Test
    void aHeldLineReplacementFreezesItsPricesTheSameWay() {
        // INVOICE_REPLACE_ITEMS has the identical defect and is NOT saved by having a targetId:
        // requireUnchanged compares the INVOICE's row version, and moving a product's price does
        // not bump it, so the precondition passes and the replay would re-price anyway.
        actAs(admin);
        Invoice inv = invoiceService.create(unpricedRequest(1));
        threshold(defaultRegion().getId(), LAKH, true);

        PendingApprovalException held = catchThrowableOfType(
                () -> invoiceService.replaceItemsForDisputeApplication(inv.getId(),
                        List.of(new InvoiceDtos.LineInput(widget.getId(), 100, null)), "agreed"),
                PendingApprovalException.class);

        assertThat(held).isNotNull();
        assertThat(held.change().getExposure()).isEqualByComparingTo(new BigDecimal("120000.00"));
        assertThat(held.change().getPayloadJson()).contains("\"unitPrice\":1200.00");
    }

    @Test
    void aPriceTheCallerNamedIsStillTheirOwnAndIsCarriedThrough() throws Exception {
        threshold(defaultRegion().getId(), LAKH, true);

        // Freezing is a fallback made explicit, never an override: a request that names its own
        // price parks that price and not the catalogue's.
        MvcResult held = mockMvc.perform(post("/api/invoices").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null,
                                admin.getId(),
                                List.of(new InvoiceDtos.LineInput(widget.getId(), 100,
                                        new BigDecimal("2000.00")))))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.exposure").value(200000.00))
                .andReturn();

        assertThat(pendingChangeRepository.findById(jsonLong(held, "pendingChangeId")).orElseThrow()
                .getPayloadJson()).contains("\"unitPrice\":2000.00");
    }

    // ------------------------------------------------------------------------------ fixtures

    /** What the shipped Flutter form posts: a product and a quantity, and no price at all. */
    private InvoiceDtos.CreateInvoiceRequest unpricedRequest(int quantity) {
        return new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), quantity, null)));
    }

    private void repriceWidget(String price) {
        Product p = productRepository.findById(widget.getId()).orElseThrow();
        p.setPrice(new BigDecimal(price));
        productRepository.saveAndFlush(p);
    }

    private void threshold(Long regionId, String amount, boolean enabled) {
        ApprovalThreshold row = approvalThresholdRepository.findByRegionId(regionId)
                .orElseGet(() -> ApprovalThreshold.builder().regionId(regionId).build());
        row.setAmount(new BigDecimal(amount));
        row.setEnabled(enabled);
        approvalThresholdRepository.saveAndFlush(row);
    }

    private Role makerRole() {
        return roleWith("PRICE_MAKER",
                Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW, Privileges.INVOICE_MANAGE,
                Privileges.PRODUCT_VIEW, Privileges.POC_VIEW, Privileges.POC_ASSIGN,
                Privileges.SCOPE_OVERRIDE, Privileges.APPROVAL_VIEW);
    }

    private Role checkerRole() {
        return roleWith("PRICE_CHECKER", Privileges.APPROVAL_VIEW, Privileges.APPROVAL_APPROVE);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by HeldInvoicePriceTest")
                .privileges(Arrays.stream(privileges)
                        .map(p -> privilegeRepository.findByName(p).orElseThrow())
                        .collect(Collectors.toCollection(HashSet<Privilege>::new)))
                .build()));
    }

    private Long jsonLong(MvcResult result, String field) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get(field).asLong();
    }
}
