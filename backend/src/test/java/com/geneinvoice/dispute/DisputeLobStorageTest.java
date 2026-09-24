package com.geneinvoice.dispute;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.HistoryReconciler;
import com.geneinvoice.history.HistoryRegistry;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HOW A DISPUTE'S PROPOSED CHANGE IS ACTUALLY STORED (B2, B3).
 *
 * <p>disputes.proposed_change_json was {@code @Lob}, which on Postgres means pgjdbc writes a LARGE
 * OBJECT and stores its OID in the text column. Here that cost more than readability: B3 mirrors
 * this table into dispute_history and its reconciler compares the two copies in RAW SQL, so the
 * OID in the live row never equalled the JSON the mirror holds. Every dispute carrying a proposed
 * change therefore drifted on every sweep, for ever — and the repair pass copied the OID into the
 * mirror, so the as-of answer for that dispute then read back as a run of digits. None of it is
 * visible on H2, which stores the string either way (B2, B3).
 */
class DisputeLobStorageTest extends IntegrationTestBase {

    @Autowired DisputeService disputeService;
    @Autowired InvoiceService invoiceService;
    @Autowired HistoryReconciler reconciler;
    @Autowired HistoryRegistry registry;
    @Autowired JdbcTemplate jdbc;

    static final String PROPOSAL = "{\"items\":[{\"productId\":1,\"quantity\":2}]}";

    User admin;
    Customer acme;
    User acmeLogin;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        acme = customer("Largely Objectionable Ltd");
        acmeLogin = customerUser("amy.objection", acme.getId());
        widget = product("Widget", "100.00");
        actAs(admin);
    }

    /**
     * A derived finder opens no transaction of its own and a test method holds none, so this read
     * runs in auto-commit — where a Clob bind dies on Postgres with "Unable to access lob stream".
     * It passes on H2 either way and on Postgres only with the mapping fixed (B2).
     */
    @Test
    void aProposedChangeIsReadableWithNoTransactionOfItsOwnAndIsTextInTheRawColumn() {
        Dispute opened = dispute();

        List<Dispute> found = disputeRepository.findByCustomerIdOrderByCreatedAtDesc(acme.getId());

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getProposedChangeJson()).isEqualTo(PROPOSAL);

        // An OID column reads back as a short run of digits whose length has nothing to do with
        // the JSON's, so length equality and a leading brace together are what "the text is really
        // in there" means. Cannot fail on H2; it is the whole of the claim on Postgres (B2).
        String raw = jdbc.queryForObject(
                "select proposed_change_json from disputes where id = ?", String.class, opened.getId());
        Integer length = jdbc.queryForObject(
                "select length(proposed_change_json) from disputes where id = ?",
                Integer.class, opened.getId());
        assertThat(raw).isEqualTo(PROPOSAL).startsWith("{").doesNotMatch("\\d+");
        assertThat(length).isEqualTo(PROPOSAL.length());
    }

    /**
     * The mirror and the live row must agree in SQL, not merely through Hibernate: the reconciler
     * only ever compares them in raw SQL. A sweep that finds nothing to do is the assertion —
     * counted per dispute rather than by the sweep's return, so an unrelated row drifting in the
     * same context cannot make this pass or fail (B3).
     */
    @Test
    void theReconcilerFindsNoDriftBetweenADisputeAndItsMirror() {
        Dispute opened = dispute();
        long versionsBefore = versions(opened);

        reconciler.reconcile(registry.forType(Dispute.class));

        assertThat(versions(opened)).as("the reconciler wrote a new version, so it saw drift")
                .isEqualTo(versionsBefore);
        assertThat(mirrored(opened)).isEqualTo(PROPOSAL);
    }

    // ------------------------------------------------------------------------------- fixtures

    private Dispute dispute() {
        Invoice inv = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                acme.getId(), null, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 5, new BigDecimal("100.00")))));
        return disputeService.openAs(acmeLogin.getId(), acme.getId(), DisputeTargetType.INVOICE,
                inv.getId(), "The lines are wrong", PROPOSAL);
    }

    private long versions(Dispute d) {
        Long count = jdbc.queryForObject(
                "select count(*) from dispute_history where dispute_id = ?", Long.class, d.getId());
        return count == null ? 0 : count;
    }

    private String mirrored(Dispute d) {
        return jdbc.queryForObject("select proposed_change_json from dispute_history"
                        + " where dispute_id = ? order by history_id desc limit 1",
                String.class, d.getId());
    }
}
