package com.geneinvoice.invoice;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §2.5, D2: a database made before due dates existed has {@code ddl-auto: update} add the column
 * nullable, and the app fills it in and then makes it not null. This drives that sequence the way
 * a real upgrade meets it — a populated table whose column has just appeared — and then runs it a
 * second time, which is what a restart does.
 */
class InvoiceSchemaUpgradeTest extends IntegrationTestBase {

    /** Either side of a UTC midnight, so a machine in any zone would notice a wrong one. */
    static final Instant JUST_AFTER_MIDNIGHT = Instant.parse("2026-03-01T00:30:00Z");
    static final Instant JUST_BEFORE_MIDNIGHT = Instant.parse("2026-03-01T23:30:00Z");
    static final LocalDate THAT_DAY = LocalDate.of(2026, 3, 1);

    @Autowired InvoiceSchemaUpgrade upgrade;
    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired AuditService auditService;
    @Autowired DataSource dataSource;

    User admin;
    User sales;
    Customer acme;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        user("cora.collect", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(admin);
    }

    private Invoice invoice(Instant raised) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), raised,
                null, sales.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("100.00")))));
    }

    /** Winds the table back to what {@code ddl-auto: update} leaves on a populated database. */
    private void asBeforeTheUpgrade(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("alter table invoices alter column due_date set null");
            st.execute("update invoices set due_date = null, payment_term = null");
        }
    }

    private List<AuditLog> backfillEntries() {
        return auditService.historyFor(InvoiceService.ENTITY, InvoiceSchemaUpgrade.BACKFILL_ENTITY_ID)
                .stream().filter(e -> e.getAction().equals("INVOICE_DUE_DATES_BACKFILLED")).toList();
    }

    @Test
    void rowsWithoutADueDateAreFilledInFromTheDefaultTermAndTheColumnBecomesNotNull() throws Exception {
        Invoice early = invoice(JUST_AFTER_MIDNIGHT);
        Invoice late = invoice(JUST_BEFORE_MIDNIGHT);
        // Something on the row worth watching: the backfill must not disturb it.
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("40.00"), "Cash", null, List.of(early.getId()),
                userRepository.findByUsername("cora.collect").orElseThrow().getId(), null));
        int entriesBefore = backfillEntries().size();

        try (Connection c = dataSource.getConnection()) {
            // Startup has already been through it once, so the column is not null now.
            assertThat(InvoiceSchemaUpgrade.isNullable(c, "invoices", "due_date")).isFalse();
            try {
                asBeforeTheUpgrade(c);
                assertThat(InvoiceSchemaUpgrade.isNullable(c, "invoices", "due_date")).isTrue();

                upgrade.afterPropertiesSet();
            } finally {
                // Whatever happened above, the other tests get a table every invoice can be written to.
                upgrade.afterPropertiesSet();
            }
            assertThat(InvoiceSchemaUpgrade.isNullable(c, "invoices", "due_date")).isFalse();
        }

        // Net 30 from the invoice's UTC day, whichever side of midnight it was raised.
        assertThat(invoiceRepository.findById(early.getId()).orElseThrow()).satisfies(filled -> {
            assertThat(filled.getDueDate()).isEqualTo(THAT_DAY.plusDays(30));
            assertThat(filled.getPaymentTerm()).isEqualTo(PaymentTerm.NET_30);
            // Nothing else was touched (§9 of the PRD).
            assertThat(filled.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
            assertThat(filled.getPaidAmount()).isEqualByComparingTo("40.00");
        });
        assertThat(invoiceRepository.findById(late.getId()).orElseThrow().getDueDate())
                .isEqualTo(THAT_DAY.plusDays(30));

        // One entry, naming how many rows it moved and the term it used.
        assertThat(backfillEntries()).hasSize(entriesBefore + 1);
        assertThat(backfillEntries().get(0).getReason())
                .isEqualTo("Backfilled 2 invoice due dates using Net 30");
        assertThat(backfillEntries().get(0).getAfterJson()).contains("NET_30");
    }

    /** A restart re-runs it: no rows to move, no second entry, nothing moved. */
    @Test
    void aSecondRunFindsNothingToDoAndFilesNothing() throws Exception {
        Invoice inv = invoice(JUST_AFTER_MIDNIGHT);
        int entriesBefore = backfillEntries().size();

        try (Connection c = dataSource.getConnection()) {
            assertThat(InvoiceSchemaUpgrade.backfill(c, PaymentTerm.NET_30)).isZero();
            // And the not-null step is skipped rather than run again.
            InvoiceSchemaUpgrade.enforceDueDateNotNull(c);
            assertThat(InvoiceSchemaUpgrade.isNullable(c, "invoices", "due_date")).isFalse();
        }
        upgrade.afterPropertiesSet();

        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getDueDate())
                .isEqualTo(inv.getDueDate());
        assertThat(backfillEntries()).hasSize(entriesBefore);
    }

    /** The invariant the not-null column is there to hold: no invoice without a due date (AC-A1). */
    @Test
    void theDatabaseItselfRefusesAnInvoiceWithoutADueDate() {
        Invoice inv = invoice(JUST_AFTER_MIDNIGHT);

        assertThatThrownBy(() -> {
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                st.execute("update invoices set due_date = null where id = " + inv.getId());
            }
        }).isInstanceOfAny(SQLException.class, DataIntegrityViolationException.class);
    }

    /**
     * §2.5: each page starts after the last id of the one before, so a populated table is read
     * once through rather than scanned from the first row again for every chunk.
     */
    @Test
    void eachPageStartsAfterTheLastIdOfTheOneBefore() throws Exception {
        Invoice first = invoice(JUST_AFTER_MIDNIGHT);
        Invoice second = invoice(JUST_BEFORE_MIDNIGHT);

        try (Connection c = dataSource.getConnection()) {
            try {
                asBeforeTheUpgrade(c);

                assertThat(InvoiceSchemaUpgrade.undated(c, 0L, 10))
                        .containsOnlyKeys(first.getId(), second.getId());
                // A page of one stops at the first row, and the next page carries on after it
                // instead of meeting it again.
                assertThat(InvoiceSchemaUpgrade.undated(c, 0L, 1)).containsOnlyKeys(first.getId());
                assertThat(InvoiceSchemaUpgrade.undated(c, first.getId(), 10))
                        .containsOnlyKeys(second.getId());
                assertThat(InvoiceSchemaUpgrade.undated(c, second.getId(), 10)).isEmpty();
            } finally {
                upgrade.afterPropertiesSet();
            }
        }
    }

    /**
     * A driver that hands the raised timestamp over as a {@link Timestamp} — which is what
     * PostgreSQL's does for this column — has already rendered it in the machine's own zone. Read
     * as an instant it is still the instant it was; read as a wall time it would move by that
     * offset and date a late-evening invoice a day out, which is the very thing §2.5 works the
     * date out in Java to avoid. H2 answers with an {@link java.time.OffsetDateTime}, so the rest
     * of this class never reaches that branch.
     */
    @Test
    void aTimestampFromTheDriverIsTheInstantItStandsFor() {
        TimeZone machine = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));

            assertThat(InvoiceSchemaUpgrade.instantOf(Timestamp.from(JUST_BEFORE_MIDNIGHT)))
                    .isEqualTo(JUST_BEFORE_MIDNIGHT);
            // Which is the whole point: the day the term is counted from is the UTC one.
            assertThat(InvoiceDates.dayOf(
                    InvoiceSchemaUpgrade.instantOf(Timestamp.from(JUST_BEFORE_MIDNIGHT))))
                    .isEqualTo(THAT_DAY);
            assertThat(InvoiceDates.dayOf(
                    InvoiceSchemaUpgrade.instantOf(Timestamp.from(JUST_AFTER_MIDNIGHT))))
                    .isEqualTo(THAT_DAY);
            // A column with no zone of its own is still the UTC the app wrote into it.
            assertThat(InvoiceSchemaUpgrade.instantOf(
                    LocalDateTime.ofInstant(JUST_BEFORE_MIDNIGHT, ZoneOffset.UTC)))
                    .isEqualTo(JUST_BEFORE_MIDNIGHT);
        } finally {
            TimeZone.setDefault(machine);
        }
    }

    /** The backfill needs a term with a number of days; CUSTOM is not one (§2.1). */
    @Test
    void theBackfillRefusesCustomTerms() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThatThrownBy(() -> InvoiceSchemaUpgrade.backfill(c, PaymentTerm.CUSTOM))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
