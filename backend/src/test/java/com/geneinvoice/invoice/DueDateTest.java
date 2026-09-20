package com.geneinvoice.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature A: where an invoice's due date comes from (§2.2). The customer's terms decide it at
 * creation, the date is stored rather than the terms (D1), and nobody has to type it in the
 * common case.
 */
class DueDateTest extends IntegrationTestBase {

    /** A Sunday, far enough back that every derived date is unambiguous. */
    static final Instant RAISED = Instant.parse("2026-03-01T09:00:00Z");
    static final LocalDate RAISED_DAY = LocalDate.of(2026, 3, 1);

    @Autowired InvoiceService invoiceService;
    @Autowired CustomerService customerService;
    @Autowired AuditService auditService;

    User admin;
    User sales;
    Customer acme;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(admin);
    }

    private Invoice invoice(Customer c, Instant date, LocalDate dueDate, PaymentTerm term) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(), date, dueDate,
                term, null, sales.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("100.00")))));
    }

    private Customer onTerms(Customer c, PaymentTerm term) {
        // A MockMvc request clears the thread's security context on its way out, so anything
        // calling a service after one has to say who it is again.
        actAs(admin);
        customerService.update(c.getId(), new CustomerDtos.CustomerUpdateRequest(
                c.getName(), c.getPhone(), c.getEmail(), c.getAddress(), term, null));
        return customerRepository.findById(c.getId()).orElseThrow();
    }

    /** A create request as JSON, so an absent field really is absent rather than null. */
    private Map<String, Object> createBody(Customer c) {
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", c.getId());
        body.put("invoiceDate", RAISED.toString());
        body.put("salesPocUserId", sales.getId());
        body.put("items", List.of(Map.of("productId", widget.getId(), "quantity", 1)));
        return body;
    }

    // ---- where the date comes from ----------------------------------------------

    @Test
    void aCustomerWithNoTermsOfTheirOwnGetsTheSystemDefault() {
        Invoice inv = invoice(acme, RAISED, null, null);

        assertThat(acme.getPaymentTerm()).isNull();
        assertThat(inv.getPaymentTerm()).isEqualTo(PaymentTerm.NET_30);
        assertThat(inv.getDueDate()).isEqualTo(RAISED_DAY.plusDays(30));
    }

    @Test
    void theCustomersOwnTermsDecideTheDate() {
        Invoice inv = invoice(onTerms(acme, PaymentTerm.NET_60), RAISED, null, null);

        assertThat(inv.getPaymentTerm()).isEqualTo(PaymentTerm.NET_60);
        assertThat(inv.getDueDate()).isEqualTo(RAISED_DAY.plusDays(60));
    }

    @Test
    void dueOnReceiptMeansTheInvoiceDateItself() {
        Invoice inv = invoice(onTerms(acme, PaymentTerm.DUE_ON_RECEIPT), RAISED, null, null);

        assertThat(inv.getDueDate()).isEqualTo(RAISED_DAY);
        assertThat(inv.isOverdue(RAISED_DAY)).isFalse();
    }

    @Test
    void namedTermsOnTheRequestBeatTheCustomersOwn() {
        Invoice inv = invoice(onTerms(acme, PaymentTerm.NET_60), RAISED, null, PaymentTerm.NET_15);

        assertThat(inv.getPaymentTerm()).isEqualTo(PaymentTerm.NET_15);
        assertThat(inv.getDueDate()).isEqualTo(RAISED_DAY.plusDays(15));
    }

    /** US-A3: a negotiated date, recorded as the custom date it is. */
    @Test
    void aDateOnItsOwnIsAnOverrideAndIsRecordedAsCustom() {
        LocalDate negotiated = RAISED_DAY.plusDays(7);
        Invoice inv = invoice(acme, RAISED, negotiated, null);

        assertThat(inv.getDueDate()).isEqualTo(negotiated);
        assertThat(inv.getPaymentTerm()).isEqualTo(PaymentTerm.CUSTOM);
    }

    /** AC-A1: there is no way to make an invoice without one, whatever the request left out. */
    @Test
    void everyInvoiceLeavesTheServiceWithADueDate() {
        Invoice noDate = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(),
                null, null, sales.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("100.00")))));

        assertThat(noDate.getDueDate()).isNotNull();
        assertThat(noDate.getPaymentTerm()).isNotNull();
        assertThat(invoiceRepository.findAll()).allSatisfy(i -> {
            assertThat(i.getDueDate()).isNotNull();
            assertThat(i.getPaymentTerm()).isNotNull();
        });
    }

    /**
     * D1 and AC-A2: the invoice stores the resulting date, not a pointer to the terms, so moving
     * a customer onto longer terms leaves money already billed where it was.
     */
    @Test
    void changingACustomersTermsNeverMovesAnInvoiceAlreadyRaised() {
        Invoice before = invoice(onTerms(acme, PaymentTerm.NET_15), RAISED, null, null);
        assertThat(before.getDueDate()).isEqualTo(RAISED_DAY.plusDays(15));

        Invoice after = invoice(onTerms(acme, PaymentTerm.NET_90), RAISED, null, null);

        assertThat(invoiceRepository.findById(before.getId()).orElseThrow())
                .satisfies(unmoved -> {
                    assertThat(unmoved.getDueDate()).isEqualTo(RAISED_DAY.plusDays(15));
                    assertThat(unmoved.getPaymentTerm()).isEqualTo(PaymentTerm.NET_15);
                });
        // Only the invoice raised after the change takes the new terms.
        assertThat(after.getDueDate()).isEqualTo(RAISED_DAY.plusDays(90));
    }

    // ---- editing an existing invoice ---------------------------------------------

    @Test
    void anExplicitDateOnAnUpdateIsAnOverrideAndAnExplicitTermRecomputes() {
        Invoice inv = invoice(acme, RAISED, null, null);
        LocalDate negotiated = RAISED_DAY.plusDays(45);

        Invoice overridden = invoiceService.update(inv.getId(),
                new InvoiceDtos.UpdateInvoiceRequest(null, null, negotiated, null));
        assertThat(overridden.getDueDate()).isEqualTo(negotiated);
        assertThat(overridden.getPaymentTerm()).isEqualTo(PaymentTerm.CUSTOM);

        Invoice recomputed = invoiceService.update(inv.getId(),
                new InvoiceDtos.UpdateInvoiceRequest(null, null, null, PaymentTerm.NET_15));
        assertThat(recomputed.getDueDate()).isEqualTo(RAISED_DAY.plusDays(15));
        assertThat(recomputed.getPaymentTerm()).isEqualTo(PaymentTerm.NET_15);
    }

    @Test
    void anEditThatSaysNothingAboutTheDueDateLeavesItAlone() {
        Invoice inv = invoice(acme, RAISED, null, null);

        Invoice saved = invoiceService.update(inv.getId(),
                new InvoiceDtos.UpdateInvoiceRequest("just a note", null));

        assertThat(saved.getNotes()).isEqualTo("just a note");
        assertThat(saved.getDueDate()).isEqualTo(RAISED_DAY.plusDays(30));
        assertThat(saved.getPaymentTerm()).isEqualTo(PaymentTerm.NET_30);
    }

    /** AC-A8: moving a collections deadline gets an entry of its own, old → new. */
    @Test
    void anOverrideIsAuditedOldToNew() {
        Invoice inv = invoice(acme, RAISED, null, null);
        LocalDate negotiated = RAISED_DAY.plusDays(45);

        invoiceService.update(inv.getId(),
                new InvoiceDtos.UpdateInvoiceRequest(null, null, negotiated, null));

        List<AuditLog> moved = auditService.historyFor(InvoiceService.ENTITY, inv.getId()).stream()
                .filter(e -> e.getAction().equals("INVOICE_DUE_DATE_CHANGED")).toList();
        assertThat(moved).singleElement().satisfies(entry -> {
            assertThat(entry.getBeforeJson()).contains(RAISED_DAY.plusDays(30).toString())
                    .contains("NET_30");
            assertThat(entry.getAfterJson()).contains(negotiated.toString()).contains("CUSTOM");
            assertThat(entry.getChangedByUserId()).isEqualTo(admin.getId());
        });

        // An edit that does not move the date files no such entry.
        invoiceService.update(inv.getId(), new InvoiceDtos.UpdateInvoiceRequest("note", null));
        assertThat(auditService.historyFor(InvoiceService.ENTITY, inv.getId()))
                .filteredOn(e -> e.getAction().equals("INVOICE_DUE_DATE_CHANGED")).hasSize(1);
    }

    /** AC-A8 on the other side of the arrangement: the customer's terms. */
    @Test
    void aCustomersTermsChangeIsAuditedOldToNew() {
        onTerms(acme, PaymentTerm.NET_60);

        assertThat(auditService.historyFor(CustomerService.ENTITY, acme.getId()))
                .filteredOn(e -> e.getAction().equals("CUSTOMER_PAYMENT_TERM_CHANGED"))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.getBeforeJson()).isNull();
                    assertThat(entry.getAfterJson()).contains("NET_60");
                });

        // Saving the same terms again is not a change.
        onTerms(acme, PaymentTerm.NET_60);
        assertThat(auditService.historyFor(CustomerService.ENTITY, acme.getId()))
                .filteredOn(e -> e.getAction().equals("CUSTOMER_PAYMENT_TERM_CHANGED")).hasSize(1);
    }

    // ---- AC-A5: validation --------------------------------------------------------

    @Test
    void aDueDateBeforeTheInvoiceDateIsRefused() throws Exception {
        Map<String, Object> body = createBody(acme);
        body.put("dueDate", RAISED_DAY.minusDays(1).toString());

        mockMvc.perform(post("/api/invoices").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.dueDate")
                        .value("The due date cannot be before the invoice date"));
        assertThat(invoiceRepository.findAll()).isEmpty();
    }

    @Test
    void customTermsWithoutADateAreRefused() throws Exception {
        Map<String, Object> body = createBody(acme);
        body.put("paymentTerm", "CUSTOM");

        mockMvc.perform(post("/api/invoices").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.dueDate").value("Pick a due date for custom terms"));
    }

    /**
     * The mirror of the rule above (§2.2). Named terms say how the date is worked out and a date
     * says what it is, so a request carrying both is refused rather than one of them being
     * thrown away without a word.
     */
    @Test
    void namedTermsWithADateBesideThemAreRefused() throws Exception {
        Map<String, Object> body = createBody(acme);
        body.put("paymentTerm", "NET_15");
        body.put("dueDate", RAISED_DAY.plusDays(30).toString());

        mockMvc.perform(post("/api/invoices").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.dueDate")
                        .value("Pick Custom terms to set the due date yourself"));
        assertThat(invoiceRepository.findAll()).isEmpty();
    }

    /** And an edit is the same request in miniature: the date it holds is the one it keeps. */
    @Test
    void anEditNamingBothIsRefusedAndMovesNothing() throws Exception {
        Invoice inv = invoice(acme, RAISED, null, null);

        mockMvc.perform(patch("/api/invoices/" + inv.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("paymentTerm", "NET_15",
                                "dueDate", RAISED_DAY.plusDays(30).toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.dueDate")
                        .value("Pick Custom terms to set the due date yourself"));

        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow()).satisfies(unmoved -> {
            assertThat(unmoved.getDueDate()).isEqualTo(RAISED_DAY.plusDays(30));
            assertThat(unmoved.getPaymentTerm()).isEqualTo(PaymentTerm.NET_30);
        });
    }

    /** Some contracts really are Net 365: the API takes it, and only the form warns. */
    @Test
    void aDateFarBeyondTheHorizonIsAccepted() {
        LocalDate farOff = RAISED_DAY.plusYears(3);

        assertThat(invoice(acme, RAISED, farOff, null).getDueDate()).isEqualTo(farOff);
    }

    @Test
    void theDueDateMayEqualTheInvoiceDate() {
        assertThat(invoice(acme, RAISED, RAISED_DAY, null).getDueDate()).isEqualTo(RAISED_DAY);
    }

    /** A custom date is one invoice's arrangement, never a standing one (§2.1). */
    @Test
    void acustomerCannotBePutOnCustomTerms() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/customers/" + acme.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Acme Ltd", "paymentTerm", "CUSTOM"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.paymentTerm",
                        org.hamcrest.Matchers.containsString("Custom terms belong on one invoice")));
    }

    // ---- US-A2: the form's preview -------------------------------------------------

    @Test
    void thePreviewNamesTheTermsAndWhereTheyCameFrom() throws Exception {
        JsonNode fromDefault = objectMapper.readTree(mockMvc.perform(
                        get("/api/invoices/due-date-preview").with(as(admin))
                                .param("customerId", String.valueOf(acme.getId()))
                                .param("invoiceDate", RAISED_DAY.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(fromDefault.get("dueDate").asText()).isEqualTo(RAISED_DAY.plusDays(30).toString());
        assertThat(fromDefault.get("paymentTerm").asText()).isEqualTo("NET_30");
        assertThat(fromDefault.get("paymentTermLabel").asText()).isEqualTo("Net 30");
        assertThat(fromDefault.get("source").asText()).isEqualTo("DEFAULT");

        onTerms(acme, PaymentTerm.NET_45);
        JsonNode own = objectMapper.readTree(mockMvc.perform(
                        get("/api/invoices/due-date-preview").with(as(admin))
                                .param("customerId", String.valueOf(acme.getId()))
                                .param("invoiceDate", RAISED.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(own.get("dueDate").asText()).isEqualTo(RAISED_DAY.plusDays(45).toString());
        assertThat(own.get("source").asText()).isEqualTo("CUSTOMER");
        assertThat(own.get("paymentTermLabel").asText()).isEqualTo("Net 45");
    }

    @Test
    void thePreviewWithoutAnInvoiceDateCountsFromToday() throws Exception {
        JsonNode preview = objectMapper.readTree(mockMvc.perform(
                        get("/api/invoices/due-date-preview").with(as(admin))
                                .param("customerId", String.valueOf(acme.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(LocalDate.parse(preview.get("dueDate").asText()))
                .isEqualTo(LocalDate.now(ZoneOffset.UTC).plusDays(30));
    }

    @Test
    void thePreviewNeedsTheRightToRaiseAnInvoiceAndARealCustomer() throws Exception {
        User viewer = user("vera.viewer", "VIEWER");

        mockMvc.perform(get("/api/invoices/due-date-preview").with(as(viewer))
                        .param("customerId", String.valueOf(acme.getId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/invoices/due-date-preview").with(as(admin))
                        .param("customerId", "404404"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/invoices/due-date-preview").with(as(admin))
                        .param("customerId", String.valueOf(acme.getId()))
                        .param("invoiceDate", "the ides of March"))
                .andExpect(status().isBadRequest());
    }

    // ---- AC-A3: the date is on every read path ------------------------------------

    /**
     * The preview hands over a customer's commercial terms, so it may not reach a customer the
     * caller could not read: an id outside a POC's book answers exactly as {@code GET
     * /api/customers/{id}} does, rather than confirming the customer exists (AC-A6).
     */
    @Test
    void thePreviewDoesNotReachOutsideTheCallersBook() throws Exception {
        Customer globex = customer("Globex Corp");
        onTerms(globex, PaymentTerm.NET_60);
        // Acme is Sam's: he owns an invoice on it. Globex is nobody's.
        invoice(acme, RAISED, null, null);

        mockMvc.perform(get("/api/customers/" + globex.getId()).with(as(sales)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/invoices/due-date-preview").with(as(sales))
                        .param("customerId", String.valueOf(globex.getId())))
                .andExpect(status().isNotFound());

        // The customer he does have answers as usual.
        mockMvc.perform(get("/api/invoices/due-date-preview").with(as(sales))
                        .param("customerId", String.valueOf(acme.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentTerm").value("NET_30"));
        // As does everything, for an admin who may see the whole book.
        mockMvc.perform(get("/api/invoices/due-date-preview").with(as(admin))
                        .param("customerId", String.valueOf(globex.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentTerm").value("NET_60"));
    }

    @Test
    void theDetailAndTheListRowBothCarryTheDateAndItsTerms() throws Exception {
        // Raised today, so the terms put the date safely ahead of the clock.
        Invoice inv = invoice(onTerms(acme, PaymentTerm.NET_45), Instant.now(), null, null);
        String due = InvoiceDates.today().plusDays(45).toString();

        mockMvc.perform(get("/api/invoices/" + inv.getId()).with(as(admin)))
                .andExpect(jsonPath("$.dueDate").value(due))
                .andExpect(jsonPath("$.paymentTerm").value("NET_45"))
                .andExpect(jsonPath("$.paymentTermLabel").value("Net 45"))
                .andExpect(jsonPath("$.overdue").value(false))
                .andExpect(jsonPath("$.daysOverdue").value(0));

        mockMvc.perform(get("/api/invoices").with(as(admin)))
                .andExpect(jsonPath("$.content[0].dueDate").value(due))
                .andExpect(jsonPath("$.content[0].paymentTermLabel").value("Net 45"));
    }

    @Test
    void theCustomerRecordShowsTheTermsItsInvoicesWillTake() throws Exception {
        mockMvc.perform(get("/api/customers/" + acme.getId()).with(as(admin)))
                .andExpect(jsonPath("$.paymentTerm").doesNotExist())
                .andExpect(jsonPath("$.paymentTermLabel").value("Net 30"));

        onTerms(acme, PaymentTerm.NET_60);

        mockMvc.perform(get("/api/customers/" + acme.getId()).with(as(admin)))
                .andExpect(jsonPath("$.paymentTerm").value("NET_60"))
                .andExpect(jsonPath("$.paymentTermLabel").value("Net 60"));
    }

    /** AC-A10: promises are a negotiated exception to the due date, not a contradiction of it. */
    @Test
    void aPromiseDatedAfterTheDueDateIsStillAValidPromise() throws Exception {
        User collector = user("cora.collect", DataSeeder.ROLE_COLLECTION_POC);
        Invoice inv = invoice(acme, RAISED, null, null);

        mockMvc.perform(post("/api/promises").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", acme.getId(),
                                "promisedDate", inv.getDueDate().plusDays(20).toString(),
                                "amount", "100.00",
                                "collectionPocUserId", collector.getId(),
                                "invoiceIds", List.of(inv.getId())))))
                .andExpect(status().isOk());

        assertThat(promiseRepository.findAll()).singleElement()
                .satisfies(p -> assertThat(p.getPromisedDate()).isAfter(inv.getDueDate()));
    }

    /** The terms move with the date when a POC edits through the API, not only through a service. */
    @Test
    void theApiEditAcceptsBothAndTheDetailComesBackWithThem() throws Exception {
        Invoice inv = invoice(acme, RAISED, null, null);

        mockMvc.perform(patch("/api/invoices/" + inv.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("paymentTerm", "NET_90"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate").value(RAISED_DAY.plusDays(90).toString()))
                .andExpect(jsonPath("$.paymentTerm").value("NET_90"));

        mockMvc.perform(patch("/api/invoices/" + inv.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("dueDate", RAISED_DAY.minusDays(2).toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.dueDate")
                        .value("The due date cannot be before the invoice date"));
    }
}
