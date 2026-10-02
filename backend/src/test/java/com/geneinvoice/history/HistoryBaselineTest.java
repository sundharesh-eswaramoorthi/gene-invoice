package com.geneinvoice.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.MovableClock;
import com.geneinvoice.common.scope.AsOfException;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.history.versions.CustomerVersion;
import com.geneinvoice.history.versions.EntityLinkVersion;
import com.geneinvoice.history.versions.InvoiceVersion;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.task.TaskDtos;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.task.TaskService;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The baseline gives a database that predates history a first version of everything in it, all from the one
 * instant history starts at, and later finds only gaps, whose history starts when they are found (§5.7.9).
 */
class HistoryBaselineTest extends IntegrationTestBase {

    static final Instant STARTED = Instant.parse("2026-05-04T08:15:30.123456Z");

    @Autowired HistoryBaseline baseline;
    @Autowired HistoryRange range;
    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired DisputeService disputeService;
    @Autowired PocService pocService;
    @Autowired TaskService taskService;
    @Autowired TransactionTemplate tx;
    @PersistenceContext EntityManager em;

    User admin;
    Customer acme;
    Invoice invoice;
    Long promiseId;

    /** A family with a record of every versioned type, then history emptied as if it had never been captured. */
    private void seedWithoutHistory() {
        User sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        User cara = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        actAs(admin);
        Product widget = product("Widget", "100");
        acme = customer("Acme Ltd");
        invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, "First order",
                sam.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 2, null))));
        promiseId = promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(), new BigDecimal("60.00"),
                Today.date().plusDays(3), cara.getId(), "Friday", List.of(invoice.getId()))).id();
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(), new BigDecimal("40.00"),
                "Cash", null, List.of(invoice.getId()), cara.getId(), List.of(promiseId)));
        pocService.add(acme.getId(), PocType.COLLECTION, cara.getId(), true);
        actAs(customerUser("acme.login", acme.getId()));
        disputeService.open(new DisputeDtos.CreateDisputeRequest(DisputeTargetType.INVOICE, invoice.getId(),
                "Wrong quantity", null));
        actAs(admin);
        taskService.create(new TaskDtos.CreateTaskRequest(TaskEntityType.INVOICE, invoice.getId(), "Chase it", null,
                null, List.of()));
        SecurityContextHolder.clearContext();
        historyTestSupport.clearVersions();
    }

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
    }

    private HistoryMeta meta() {
        return tx.execute(status -> em.find(HistoryMeta.class, HistoryMeta.ID));
    }

    /** Live row count and the versions of every versioned type, by type code. */
    private Map<String, Long> liveCounts() {
        Map<String, Long> counts = new TreeMap<>();
        tx.executeWithoutResult(status -> HistoryModel.types().forEach(t -> counts.put(t.code(), em.createQuery(
                "select count(e) from " + em.getMetamodel().entity(t.liveType()).getName() + " e", Long.class)
                .getSingleResult())));
        return counts;
    }

    private List<VersionRow> allVersions() {
        List<VersionRow> all = new ArrayList<>();
        HistoryModel.types().forEach(t -> all.addAll(historyTestSupport.allVersions(t.versionType())));
        all.addAll(historyTestSupport.allVersions(EntityLinkVersion.class));
        return all;
    }

    @Test
    void theFirstRunCopiesEveryRecordFromTheOneInstantHistoryStartsAt() {
        seedWithoutHistory();
        assertThat(historyReconciler.check().missing()).as("nothing has history yet").isNotEmpty();
        MovableClock.SHARED.set(STARTED);

        int copied = baseline.run();

        Map<String, Long> live = liveCounts();
        assertThat(live).allSatisfy((code, n) -> assertThat(n).as(code).isPositive());
        assertThat(copied).isEqualTo(live.values().stream().mapToInt(Long::intValue).sum());
        for (TypeHistory<?, ?> type : HistoryModel.types()) {
            assertThat(historyTestSupport.allVersions(type.versionType())).as(type.code())
                    .hasSize(live.get(type.code()).intValue())
                    .allSatisfy(v -> {
                        assertThat(v.getValidFrom()).isEqualTo(STARTED);
                        assertThat(v.isOpen()).isTrue();
                        assertThat(v.getOrigin()).isEqualTo("SCHEMA");
                    });
        }
        assertThat(historyTestSupport.linksOf(LinkHistory.PROMISE_INVOICES.kind(), promiseId)).singleElement()
                .satisfies(l -> {
                    assertThat(l.getId()).isEqualTo(invoice.getId());
                    assertThat(l.getValidFrom()).isEqualTo(STARTED);
                    assertThat(l.getOrigin()).isEqualTo("SCHEMA");
                });
        assertThat(historyReconciler.check().problems()).isEmpty();
        assertThat(meta().getStartedAt()).isEqualTo(STARTED);
        assertThat(meta().getBaselineCompletedAt()).isEqualTo(STARTED);
        assertThat(range.historyStartsOn()).contains(LocalDate.of(2026, 5, 4));
    }

    @Test
    void aSecondRunChangesNothing() {
        seedWithoutHistory();
        MovableClock.SHARED.set(STARTED);
        baseline.run();
        List<Long> before = allVersions().stream().map(VersionRow::getVersionId).sorted().toList();
        HistoryMeta first = meta();

        MovableClock.SHARED.set(STARTED.plusSeconds(3600));
        assertThat(baseline.run()).isZero();

        assertThat(allVersions().stream().map(VersionRow::getVersionId).sorted().toList()).isEqualTo(before);
        assertThat(meta().getStartedAt()).isEqualTo(first.getStartedAt());
        assertThat(meta().getBaselineCompletedAt()).isEqualTo(first.getBaselineCompletedAt());
    }

    /** A record with no current version after the first run is a gap: its history starts when it is found. */
    @Test
    void aLaterGapGetsAVersionFromNow() {
        seedWithoutHistory();
        MovableClock.SHARED.set(STARTED);
        baseline.run();
        tx.executeWithoutResult(status -> em.createQuery("delete from CustomerVersion v where v.id = :id")
                .setParameter("id", acme.getId()).executeUpdate());
        Instant later = STARTED.plusSeconds(86_400);
        MovableClock.SHARED.set(later);

        assertThat(baseline.run()).isEqualTo(1);

        assertThat(historyTestSupport.versionsOf(CustomerVersion.class, acme.getId())).singleElement()
                .satisfies(v -> {
                    assertThat(v.getValidFrom()).isEqualTo(later);
                    assertThat(v.getOrigin()).isEqualTo("SCHEMA");
                    assertThat(v.getName()).isEqualTo("Acme Ltd");
                });
        assertThat(historyTestSupport.versionsOf(InvoiceVersion.class, invoice.getId())).singleElement()
                .satisfies(v -> assertThat(v.getValidFrom()).as("left as it was").isEqualTo(STARTED));
        assertThat(meta().getStartedAt()).as("history still starts where it started").isEqualTo(STARTED);
    }

    /** Nothing to copy: history has no lower bound, and every day can be asked about. */
    @Test
    void anEmptyDatabaseLeavesNoStart() throws Exception {
        MovableClock.SHARED.set(STARTED);

        assertThat(baseline.run()).isZero();

        assertThat(meta().getStartedAt()).isNull();
        assertThat(meta().getBaselineCompletedAt()).isEqualTo(STARTED);
        assertThat(range.historyStartsOn()).isEmpty();
        range.requireAvailable(LocalDate.of(2000, 1, 1));
        JsonNode body = objectMapper.readTree(mockMvc.perform(get("/api/history/range").with(as(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("historyStartsOn").isNull()).isTrue();
        assertThat(body.get("latestAsOf").asText()).isEqualTo("2026-05-03");
        assertThat(body.get("timezone").asText()).isEqualTo("UTC");
    }

    /**
     * A day before history starts cannot be answered, the day it starts can. (Until as-of reads land in BE-09a the
     * interceptor still refuses every past day, so the range's own check is what is asked here.)
     */
    @Test
    void aDayBeforeHistoryStartsIsRefused() throws Exception {
        seedWithoutHistory();
        MovableClock.SHARED.set(STARTED);
        baseline.run();
        MovableClock.SHARED.set(STARTED.plusSeconds(10 * 86_400));
        LocalDate start = LocalDate.ofInstant(STARTED, ZoneOffset.UTC);

        assertThatThrownBy(() -> range.requireAvailable(start.minusDays(1)))
                .isInstanceOfSatisfying(AsOfException.class, e -> {
                    assertThat(e.code).isEqualTo(AsOfException.BEFORE_HISTORY);
                    assertThat(e.getMessage()).isEqualTo("History is available from 4 May 2026");
                });
        range.requireAvailable(start);
        JsonNode body = objectMapper.readTree(mockMvc.perform(get("/api/history/range")
                        .with(as(customerUser("acme.login2", acme.getId()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("historyStartsOn").asText()).isEqualTo("2026-05-04");
        assertThat(body.get("latestAsOf").asText()).isEqualTo("2026-05-13");
    }

    /**
     * A first run whose chunks committed but which failed before it set {@code started_at} leaves versions and no
     * start. The next run is again a first run with nothing to copy: history starts where the earliest version does.
     */
    @Test
    void aFirstRunThatFindsVersionsAlreadyThereStartsHistoryAtTheEarliest() {
        seedWithoutHistory();
        MovableClock.SHARED.set(STARTED);
        baseline.run();
        MovableClock.SHARED.set(STARTED.plusSeconds(3600));
        actAs(admin);
        customer("Later Ltd");
        SecurityContextHolder.clearContext();
        tx.executeWithoutResult(status -> em.createQuery(
                "update HistoryMeta m set m.startedAt = null, m.baselineCompletedAt = null").executeUpdate());
        Instant earliest = allVersions().stream().map(VersionRow::getValidFrom).min(Instant::compareTo).orElseThrow();
        assertThat(earliest).isEqualTo(STARTED);
        MovableClock.SHARED.set(STARTED.plusSeconds(7200));

        assertThat(baseline.run()).isZero();

        assertThat(meta().getStartedAt()).isEqualTo(earliest);
        assertThat(meta().getBaselineCompletedAt()).isEqualTo(STARTED.plusSeconds(7200));
        assertThat(range.historyStartsOn()).contains(LocalDate.of(2026, 5, 4));
    }

    /** A failure is logged and startup carries on; nothing is half-done, and the next run completes. */
    @Test
    void aFailingBaselineLetsStartupCarryOn() {
        seedWithoutHistory();
        MovableClock.SHARED.set(STARTED);
        historyFaults.failSnapshotOf("CUSTOMER", acme.getId());

        baseline.afterSingletonsInstantiated();

        assertThat(historyTestSupport.allVersions(CustomerVersion.class)).isEmpty();
        assertThat(meta().getBaselineCompletedAt()).as("not complete, so the next run is a first run").isNull();
        historyFaults.reset();
        baseline.run();
        assertThat(meta().getStartedAt()).isEqualTo(STARTED);
        assertThat(historyReconciler.check().problems()).isEmpty();
    }
}
