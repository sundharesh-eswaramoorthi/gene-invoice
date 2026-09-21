package com.geneinvoice.assignee;

import com.geneinvoice.CountingStatements;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeRepository;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a page of records with assignees costs to render (A9). An assignee picked as a role is
 * answered against the record it is on, at the moment it is read (A2) — and a dispute carries such
 * a role from the moment it is opened (A6), so every row of every dispute page has one.
 *
 * <p>Answering it a row at a time meant re-reading the dispute, the invoice or payment it is about,
 * the customer, that customer's logins and that customer's POC book, once per row: a page of fifty
 * ran several hundred queries to say what the rows already knew, and the CSV export, which renders
 * every matching row, ran them again. These tests pin the shape rather than a number: what a page
 * costs must not grow with the number of rows on it.
 */
class AssigneePageCostTest extends AssigneeTestBase {

    @Autowired DisputeService disputeService;
    @Autowired DisputeRepository disputeRepository;

    /**
     * A dispute names its customer by id rather than by association, so it outlives the customer
     * delete the base class sweeps with and is the one transactional row left standing between
     * tests. These tests count what a page costs, so the page has to be exactly the rows they made.
     */
    @BeforeEach
    void onlyTheDisputesThisTestRaises() {
        disputeRepository.deleteAll();
    }

    /** The tables a row used to be read out of, one by one. */
    private static final List<String> TABLES =
            List.of("disputes", "invoices", "customers", "customer_pocs", "users");

    @Test
    void aPageOfDisputesCostsWhatOneRowCostsHoweverManyRowsThereAre() throws Exception {
        raiseDispute(acmeInvoice);
        List<Long> oneRow = costOf(this::listDisputes);

        for (int i = 0; i < 4; i++) {
            raiseDispute(anotherInvoiceFor(acme));
        }
        assertThat(listDisputes()).isEqualTo(5);
        List<Long> fiveRows = costOf(this::listDisputes);

        // Five times the rows, the same reads — and every one of the five rows still says who its
        // seat reaches, which the test below this one checks is not simply missing.
        assertThat(fiveRows).isEqualTo(oneRow);
    }

    /**
     * The customer's book and addresses are shared by every row about that customer, so the cost
     * follows the customers on the page and not the rows. Two customers with three disputes each
     * cost what two customers with one each do.
     */
    @Test
    void whatAPageCostsFollowsTheCustomersOnItAndNotTheRows() throws Exception {
        Customer globex = customer("Globex Corp", "ap@globex.test");
        User globexLogin = customerUser("globex.login", globex.getId());
        raiseDispute(acmeInvoice);
        raiseDispute(anotherInvoiceFor(globex), globexLogin);
        List<Long> twoRows = costOf(this::listDisputes);

        for (int i = 0; i < 2; i++) {
            raiseDispute(anotherInvoiceFor(acme));
            raiseDispute(anotherInvoiceFor(globex), globexLogin);
        }
        assertThat(listDisputes()).isEqualTo(6);

        assertThat(costOf(this::listDisputes)).isEqualTo(twoRows);
    }

    /** The export renders every matching row at once, so it is where the old cost hurt most. */
    @Test
    void theCsvExportCostsWhatOneRowCostsHoweverManyRowsItWrites() throws Exception {
        raiseDispute(acmeInvoice);
        List<Long> oneRow = costOf(this::exportDisputes);

        for (int i = 0; i < 4; i++) {
            raiseDispute(anotherInvoiceFor(acme));
        }
        assertThat(costOf(this::exportDisputes)).isEqualTo(oneRow);
    }

    /**
     * The point of the three above: cheaper, and not emptier. Every row still names the person its
     * seat reaches, which is the whole of what a role assignee is for (A2).
     */
    @Test
    void everyRowOfTheCheaperPageStillSaysWhoItsSeatReaches() throws Exception {
        raiseDispute(acmeInvoice);
        raiseDispute(anotherInvoiceFor(acme));

        String page = mockMvc.perform(get("/api/disputes").param("size", "50").with(as(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("\"userId\":" + sam.getId());
        assertThat(objectMapper.readTree(page).get("content")).allSatisfy(row -> {
            assertThat(row.at("/assignees/0/resolved").asBoolean()).isTrue();
            assertThat(row.at("/assignees/0/people/0/userId").asLong()).isEqualTo(sam.getId());
        });
    }

    // ---- helpers ---------------------------------------------------------------

    /** How often each table is read while the request runs; the body runs once per table. */
    private List<Long> costOf(CountingStatements.ThrowingRunnable request) throws Exception {
        List<Long> reads = new ArrayList<>();
        for (String table : TABLES) {
            reads.add(CountingStatements.reads(table, request));
        }
        return reads;
    }

    /** The dispute list as staff read it, returning how many rows came back. */
    private int listDisputes() throws Exception {
        return objectMapper.readTree(mockMvc.perform(
                        get("/api/disputes").param("size", "50").with(as(admin)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .get("content").size();
    }

    private int exportDisputes() throws Exception {
        return mockMvc.perform(post("/api/disputes/export").with(as(admin))
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of("selectAllMatchingFilter", true))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString().length();
    }

    private Invoice anotherInvoiceFor(Customer c) {
        actAs(admin);
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                c.getId(), null, null, sam.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("100.00")))));
    }

    private Dispute raiseDispute(Invoice inv) {
        return raiseDispute(inv, acmeLogin);
    }

    /** Opened from the customer's own side, so it starts on the seat its invoice names (A6). */
    private Dispute raiseDispute(Invoice inv, User login) {
        actAs(login);
        Dispute d = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.INVOICE, inv.getId(), "wrong amount", null));
        actAs(admin);
        return d;
    }
}
