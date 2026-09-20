package com.geneinvoice.invoice;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What an invoice will and will not accept as an edit: which invoices can be cancelled (DASH-05),
 * which can be edited at all (INV-3), what a due date may be set to (INV-4), and what happens to a
 * save composed against a version somebody else has already moved past (UI-09).
 */
class InvoiceEditGuardsTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired PlatformTransactionManager transactionManager;

    User admin;
    User sales;
    Customer acme;
    Product widget;
    Product freebie;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        freebie = product("Free sample", "0.00");
        actAs(admin);
    }

    private Invoice invoice(Product p, String unitPrice) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                acme.getId(), null, null, sales.getId(),
                List.of(new InvoiceDtos.LineInput(p.getId(), 1, new BigDecimal(unitPrice)))));
    }

    // ---- DASH-05: an invoice that took no money can be cancelled ------------------

    /**
     * An invoice with nothing on it is born FULLY_PAID, and refusing on the status alone left it
     * stuck in the dashboard's "Fully paid" slice for ever — under a message telling the user to
     * refund payments it never had (DASH-05).
     */
    @Test
    void aZeroTotalInvoiceCanBeCancelled() throws Exception {
        Invoice free = invoice(freebie, "0.00");
        assertThat(free.getStatus()).isEqualTo(InvoiceStatus.FULLY_PAID);
        assertThat(free.getPaidAmount()).isEqualByComparingTo("0.00");

        mockMvc.perform(post("/api/invoices/" + free.getId() + "/cancel").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    /** An invoice that really did take money is still refused, with the message that fits. */
    @Test
    void anInvoiceHoldingAPaymentStillCannotBeCancelled() throws Exception {
        Invoice paid = invoice(widget, "100.00");
        paid.setPaidAmount(new BigDecimal("40.00"));
        invoiceRepository.save(paid);

        mockMvc.perform(post("/api/invoices/" + paid.getId() + "/cancel").with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Cannot cancel an invoice with payments; refund first"));
    }

    // ---- INV-3: a cancelled invoice is a dead record ------------------------------

    /**
     * Its lines are already refused ("Cannot edit a cancelled invoice"); its terms and its
     * collections deadline were not, so a dead record's terms could be rewritten and an
     * INVOICE_DUE_DATE_CHANGED entry left behind to say so (INV-3).
     */
    @Test
    void aCancelledInvoicesDueDateCannotBeChanged() throws Exception {
        Invoice inv = invoice(widget, "100.00");
        invoiceService.cancel(inv.getId());
        long entriesBefore = auditLogRepository.count();

        mockMvc.perform(patch("/api/invoices/" + inv.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("dueDate", "2027-03-03"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cannot edit a cancelled invoice"));

        Invoice untouched = invoiceRepository.findById(inv.getId()).orElseThrow();
        assertThat(untouched.getDueDate()).isEqualTo(inv.getDueDate());
        assertThat(untouched.getPaymentTerm()).isEqualTo(inv.getPaymentTerm());
        assertThat(auditLogRepository.count())
                .as("a refused edit leaves no trail")
                .isEqualTo(entriesBefore);
    }

    // ---- INV-4: a due date the database cannot hold -------------------------------

    /**
     * {@code +999999999-12-31} is a date Jackson parses happily and Postgres will not store. Left
     * to the database it came back as 409 "This change conflicts with existing data", which names
     * neither the field nor the rule (INV-4).
     */
    @Test
    void aDueDateBeyondTheSupportedRangeIsAFieldError() throws Exception {
        mockMvc.perform(post("/api/invoices").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", acme.getId(),
                                "salesPocUserId", sales.getId(),
                                "dueDate", "+999999999-12-31",
                                "items", List.of(Map.of("productId", widget.getId(), "quantity", 1))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.dueDate").exists());

        assertThat(invoiceRepository.count()).as("nothing was created").isZero();
    }

    /** The stated boundary itself is accepted, so the rule the message quotes is the real one. */
    @Test
    void theLastSupportedDueDateIsStillAccepted() {
        Invoice inv = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                acme.getId(), null, InvoiceService.LATEST_DUE_DATE, PaymentTerm.CUSTOM, null,
                sales.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("100.00")))));

        assertThat(inv.getDueDate()).isEqualTo(InvoiceService.LATEST_DUE_DATE);
    }

    // ---- UI-09: a save composed against a version somebody has moved past ---------

    /**
     * Two people on the same invoice: the one who saves second used to win silently, discarding
     * what the first had saved without either of them being told. Sending back the version the
     * editor had in front of them turns that into a 409 they can act on (UI-09).
     */
    @Test
    void aSaveAgainstAStaleVersionIsRefusedWithAConflict() throws Exception {
        Invoice inv = invoice(widget, "100.00");
        long stale = readVersion(inv.getId());

        // The first save goes through and moves the version on.
        mockMvc.perform(patch("/api/invoices/" + inv.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("notes", "saved by the first editor", "version", stale))))
                .andExpect(status().isOk());

        // The second was composed before that and is refused rather than applied over the top.
        mockMvc.perform(patch("/api/invoices/" + inv.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("notes", "saved by the second editor", "version", stale))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "This record changed while you were working on it; reload and try again"));

        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getNotes())
                .as("the save that got there first is the one that stands")
                .isEqualTo("saved by the first editor");
    }

    /** The version the reader is handed is the one a save must quote, and it moves on every edit. */
    @Test
    void theDetailResponseCarriesAVersionThatMovesWithEachSave() throws Exception {
        Invoice inv = invoice(widget, "100.00");
        long first = readVersion(inv.getId());

        mockMvc.perform(patch("/api/invoices/" + inv.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("notes", "edited", "version", first))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value((int) (first + 1)));

        assertThat(readVersion(inv.getId())).isEqualTo(first + 1);
    }

    /** A caller that quotes no version keeps the old behaviour: there is nothing to check. */
    @Test
    void aSaveThatQuotesNoVersionIsNotRefused() throws Exception {
        Invoice inv = invoice(widget, "100.00");

        mockMvc.perform(patch("/api/invoices/" + inv.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("notes", "no precondition"))))
                .andExpect(status().isOk());
    }

    // ---- INV-1: the due-date trail is a chain, not four moves from one date -------

    /**
     * Each due-date edit records the value the previous one left, so the History tab reads as a
     * chain. Both edits used to take their before-snapshot from a read outside any lock, so two at
     * once recorded the same starting date and the newest entry could name a date the invoice did
     * not hold (INV-1).
     *
     * <p>The race is made deterministic: the first edit's transaction is held open while the
     * second starts inside that window, which is exactly where the second used to read the date
     * the first was about to change.
     */
    @Test
    void simultaneousDueDateChangesEachRecordWhatTheLastOneLeft() throws Exception {
        Invoice inv = invoice(widget, "100.00");
        LocalDate original = inv.getDueDate();
        LocalDate first = original.plusDays(10);
        LocalDate second = original.plusDays(20);

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch firstIsInFlight = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread one = new Thread(() -> {
            actAs(admin);
            try {
                transactions.executeWithoutResult(status -> {
                    invoiceService.update(inv.getId(), new InvoiceDtos.UpdateInvoiceRequest(
                            null, null, first, PaymentTerm.CUSTOM));
                    // Written, not yet committed: where the second edit used to read the date.
                    firstIsInFlight.countDown();
                    sleep(400);
                });
            } catch (Throwable t) {
                firstIsInFlight.countDown();
                failure.set(t);
            }
        }, "due-date-one");

        Thread two = new Thread(() -> {
            actAs(admin);
            try {
                firstIsInFlight.await(5, TimeUnit.SECONDS);
                invoiceService.update(inv.getId(), new InvoiceDtos.UpdateInvoiceRequest(
                        null, null, second, PaymentTerm.CUSTOM));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "due-date-two");

        one.start();
        two.start();
        one.join(30_000);
        two.join(30_000);
        assertThat(failure.get()).isNull();

        List<InvoiceService.DueDateSnapshot> befores = auditLogRepository.findAll().stream()
                .filter(a -> "INVOICE_DUE_DATE_CHANGED".equals(a.getAction()))
                .filter(a -> inv.getId().equals(a.getEntityId()))
                .map(a -> read(a.getBeforeJson()))
                .toList();

        assertThat(befores).hasSize(2);
        assertThat(befores.stream().map(InvoiceService.DueDateSnapshot::dueDate))
                .as("two moves of the same date cannot both start from the same place")
                .containsExactlyInAnyOrder(original, first);
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getDueDate())
                .as("and the invoice holds what the newest entry says it moved to")
                .isEqualTo(second);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private InvoiceService.DueDateSnapshot read(String json) {
        try {
            return objectMapper.readValue(json, InvoiceService.DueDateSnapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The version as a reader of the detail endpoint is given it. */
    private long readVersion(Long id) throws Exception {
        String body = mockMvc.perform(get("/api/invoices/" + id).with(as(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("version").asLong();
    }
}
