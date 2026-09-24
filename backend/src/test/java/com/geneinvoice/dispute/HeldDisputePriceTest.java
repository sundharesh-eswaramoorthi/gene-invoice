package com.geneinvoice.dispute;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.approval.ApprovalThreshold;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE AMOUNT APPROVED IS THE AMOUNT APPLIED — the dispute half of it (B2).
 *
 * <p>HeldInvoicePriceTest closes this for INVOICE_CREATE and INVOICE_REPLACE_ITEMS by freezing the
 * resolved lines into the parked payload. A DISPUTE_APPROVE is not reached by that freeze: its
 * payload is the dispute's OWN proposedChangeJson, which the customer wrote and which names no
 * unit price, and the replay hands that same text back to applyChange and on into
 * InvoiceService.buildLines — which prices a line with no unitPrice from the catalogue as it
 * stands THEN. So the exposure a checker was shown and the amount that landed could differ, on the
 * one action where the checker is the only control there is. DisputeExposure.score now makes the
 * same copy, into the text it measured, before the gate ever sees it (B2).
 */
class HeldDisputePriceTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired DisputeService disputeService;
    @Autowired DisputeRepository disputeRepository;
    @Autowired PrivilegeRepository privilegeRepository;

    static final String LAKH = "100000.00";

    User admin;
    User resolver;
    User checker;
    Customer acme;
    User acmeLogin;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        resolver = user("rita.resolver", resolverRole().getName());
        checker = user("carl.checker", checkerRole().getName());
        acme = customer("Acme Ltd");
        acmeLogin = customerUser("amy.acme", acme.getId());
        widget = product("Widget", "100.00");
        actAs(admin);
    }

    @Test
    void aHeldDisputeAppliesItsLinesAtThePriceTheyWereApprovedAtAndNotAtTodaysCatalogue()
            throws Exception {
        Invoice inv = invoice(50);                                   // 5,000.00
        threshold(defaultRegion().getId(), LAKH, true);
        // What a customer's proposed change actually looks like: a product and a quantity, and no
        // price at all — the price is the catalogue's business, and nobody types one into a
        // dispute. 2,000 x 100.00 = 2,00,000.00, which is above the branch's limit.
        Dispute d = dispute(inv, "{\"action\":\"replace_items\",\"items\":"
                + "[{\"productId\":" + widget.getId() + ",\"quantity\":2000}],\"notes\":\"agreed\"}");

        MvcResult held = mockMvc.perform(post("/api/disputes/" + d.getId() + "/approve")
                        .with(as(resolver))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.action").value("DISPUTE_APPROVE"))
                .andExpect(jsonPath("$.exposure").value(200000.00))
                .andReturn();

        Long changeId = jsonLong(held, "pendingChangeId");
        PendingChange waiting = pendingChangeRepository.findById(changeId).orElseThrow();
        // The invariant, stated where it is cheapest to read: the text the applier will replay
        // carries the price the exposure was measured from.
        assertThat(payloadChangeJson(waiting)).contains("\"unitPrice\":100.00");
        assertThat(reload(d).getStatus()).isEqualTo(DisputeStatus.PENDING);

        // The quarterly price update lands while the change sits in the queue. PRODUCT_MANAGE is
        // company-wide, unregioned and ungated — the named hole, allowed to be one precisely
        // because a catalogue price is COPIED onto the line rather than referenced.
        repriceWidget("1000.00");

        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.change.status").value("APPROVED"))
                .andExpect(jsonPath("$.result.status").value("APPROVED"));

        Invoice applied = invoiceRepository.findById(inv.getId()).orElseThrow();
        // Ten times the amount anybody agreed to, with a clean four-eyes trail behind it, is what
        // this number being 20,00,000.00 would mean.
        assertThat(applied.getTotal()).isEqualByComparingTo(new BigDecimal("200000.00"))
                .isEqualByComparingTo(waiting.getExposure());
        mockMvc.perform(get("/api/invoices/" + inv.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unitPrice").value(100.00));
        assertThat(reload(d).getStatus()).isEqualTo(DisputeStatus.APPROVED);
    }

    @Test
    void aDisputeApprovalThatWasNeverHeldStillPricesFromTheCatalogueAsItIsNow() throws Exception {
        // The control beside the test above: the freeze copies the price the approval is being
        // measured at, and under the limit that measurement and the application are the same
        // instant, so nothing about an ordinary dispute resolution changes.
        Invoice inv = invoice(50);
        repriceWidget("1000.00");
        Dispute d = dispute(inv, "{\"action\":\"replace_items\",\"items\":"
                + "[{\"productId\":" + widget.getId() + ",\"quantity\":2}]}");

        mockMvc.perform(post("/api/disputes/" + d.getId() + "/approve").with(as(resolver))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getTotal())
                .isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(pendingChangeRepository.count()).isZero();
    }

    @Test
    void aPriceTheCustomerNamedIsTheirOwnAndIsParkedUntouched() throws Exception {
        Invoice inv = invoice(50);
        threshold(defaultRegion().getId(), LAKH, true);
        // Freezing is a fallback made explicit, never an override: a proposed line that names its
        // own price parks that price, and the catalogue is not consulted for it at all.
        Dispute d = dispute(inv, "{\"action\":\"replace_items\",\"items\":[{\"productId\":"
                + widget.getId() + ",\"quantity\":2000,\"unitPrice\":150.00}]}");

        MvcResult held = mockMvc.perform(post("/api/disputes/" + d.getId() + "/approve")
                        .with(as(resolver))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.exposure").value(300000.00))
                .andReturn();

        PendingChange waiting = pendingChangeRepository
                .findById(jsonLong(held, "pendingChangeId")).orElseThrow();
        assertThat(payloadChangeJson(waiting)).contains("\"unitPrice\":150.00")
                .doesNotContain("100.00");
        repriceWidget("1000.00");

        mockMvc.perform(post("/api/approvals/" + waiting.getId() + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getTotal())
                .isEqualByComparingTo(new BigDecimal("300000.00"));
    }

    @Test
    void aChangeWithNoPriceToFreezeIsParkedAsTheMakerWroteItByteForByte() throws Exception {
        // A cancel has no lines, so there is nothing to copy and the text must come back out of
        // the queue exactly as it went in: the freeze is a copy made where one is missing, and
        // never a rewrite of somebody else's words.
        Invoice big = invoice(20_000);                               // 20,00,000.00
        threshold(defaultRegion().getId(), LAKH, true);
        String proposed = "{\"action\":\"cancel\"}";
        Dispute d = dispute(big, proposed);

        MvcResult held = mockMvc.perform(post("/api/disputes/" + d.getId() + "/approve")
                        .with(as(resolver))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.exposure").value(2000000.00))
                .andReturn();

        PendingChange waiting = pendingChangeRepository
                .findById(jsonLong(held, "pendingChangeId")).orElseThrow();
        assertThat(payloadChangeJson(waiting)).isEqualTo(proposed);
        assertThat(invoiceRepository.findById(big.getId()).orElseThrow().getTotal())
                .isEqualByComparingTo(new BigDecimal("2000000.00"));
    }

    // ------------------------------------------------------------------------------- fixtures

    /** The changeJson the applier will replay, read out of the parked payload. */
    private String payloadChangeJson(PendingChange waiting) throws Exception {
        return objectMapper.readTree(waiting.getPayloadJson()).path("changeJson").asText();
    }

    /** Raised through the shared body every dispute goes through, so the fixture is a real one. */
    private Dispute dispute(Invoice inv, String changeJson) {
        actAs(admin);
        return disputeService.openAs(acmeLogin.getId(), acme.getId(), DisputeTargetType.INVOICE,
                inv.getId(), "The lines are wrong", changeJson);
    }

    private Dispute reload(Dispute d) {
        return disputeRepository.findById(d.getId()).orElseThrow();
    }

    private Invoice invoice(int quantity) {
        actAs(admin);
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                acme.getId(), null, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), quantity,
                        new BigDecimal("100.00")))));
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

    /** Staff who decide disputes: everything the approval path touches, and no approval right. */
    private Role resolverRole() {
        return roleWith("DISPUTE_PRICE_RESOLVER",
                Privileges.CUSTOMER_VIEW, Privileges.CUSTOMER_MANAGE,
                Privileges.INVOICE_VIEW, Privileges.INVOICE_MANAGE,
                Privileges.PAYMENT_VIEW, Privileges.PAYMENT_MANAGE,
                Privileges.PRODUCT_VIEW, Privileges.SCOPE_OVERRIDE,
                Privileges.DISPUTE_VIEW, Privileges.DISPUTE_MANAGE,
                Privileges.APPROVAL_VIEW);
    }

    /** An approver and nothing else, so the replay has to rebuild the maker to get anywhere. */
    private Role checkerRole() {
        return roleWith("DISPUTE_PRICE_CHECKER",
                Privileges.APPROVAL_VIEW, Privileges.APPROVAL_APPROVE);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by HeldDisputePriceTest")
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
