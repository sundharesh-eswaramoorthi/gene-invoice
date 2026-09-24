package com.geneinvoice.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.asof.TestHistoryFloor;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.FixedHistoryClock;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.CustomerRegionHistory;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE BACKTEST'S COOLDOWN IS ASKED AS OF THE SAME DAY AS EVERYTHING ELSE IT ASKS (B3).
 *
 * <p>{@code POST /simulate?asOf=} routes every clause it builds through the as-of context — the
 * mirror root, the twin schema, {@code AsOf.at(T)}, relative dates and the region ledger — and the
 * cooldown was the one that was not: it was anchored at the real {@code Instant.now()} and had no
 * upper bound at all, so a backtest of January both excluded records this rule chased in February
 * (which had not happened yet on the day being asked about) and included records it had chased
 * inside January's own window. The answer was wrong in BOTH directions and was stamped
 * {@code exact: true}.
 *
 * <p>BOTH HALVES ARE PINNED SEPARATELY, because either one alone still gives a wrong population:
 * {@code chasedAfterTheAsOfDate} needs the upper bound and {@code chasedInsideTheWindow} needs the
 * anchor. The live arm is here too, because "evaluate as-of, act now" keeps the ACTING paths on
 * the wall clock deliberately and this must not have moved them.
 */
@Import({FixedHistoryClock.Config.class, TestHistoryFloor.Config.class})
class RuleBacktestCooldownTest extends IntegrationTestBase {

    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired InvoiceService invoiceService;
    @Autowired AutomationRuleService ruleService;
    @Autowired FixedHistoryClock clock;
    @Autowired TestHistoryFloor floor;

    User admin;
    User ana;
    Customer acme;
    Product widget;

    LocalDate today;
    /** The day both invoices were raised, and the day the placement opens. */
    LocalDate raised;
    /** The day the backtest asks about. */
    LocalDate asked;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        ana = user("ana.backtester", authorRole().getName());
        widget = product("Widget", "100.00");
        today = LocalDate.now(ZoneOffset.UTC);
        raised = today.minusDays(60);
        asked = today.minusDays(40);
        acme = customer("Acme Ltd");
        customerRegionHistoryRepository.saveAndFlush(CustomerRegionHistory.builder()
                .customerId(acme.getId()).regionId(defaultRegion().getId())
                .validFrom(today.minusDays(90)).build());
    }

    @AfterEach
    void putEverythingBack() {
        clock.release();
        floor.reset();
    }

    /**
     * ONE RULE, ONE COOLDOWN, TWO INVOICES, AND THE TWO ARMS ANSWER THE OPPOSITE WAY ROUND ON THE
     * TWO DATES.
     *
     * <p>The rule has a 30-day cooldown. It finished a step about {@code chasedLater} ten days
     * before today — twenty days AFTER the day being asked about — and a step about
     * {@code chasedEarlier} fifty days ago, which is inside the window that was in force on that
     * day and outside the one in force now.
     *
     * <p>So the backtest of that day must count {@code chasedLater} and suppress
     * {@code chasedEarlier}, and a live dry run must do exactly the opposite. Anchoring at the
     * real now gave the LIVE answer to the historical question; the counts happen to be equal, so
     * only the identity of the records shows it, which is why this asserts the sample and not just
     * {@code matched}.
     */
    @Test
    void theBacktestsCooldownIsTheWindowThatWasInForceOnTheDayAsked() throws Exception {
        AutomationRule rule = ruleWithCooldown(30);
        Invoice chasedLater = raise("1000.00");
        Invoice chasedEarlier = raise("2000.00");
        step(rule, chasedLater, today.minusDays(10));
        step(rule, chasedEarlier, today.minusDays(50));

        // As of that day: the February chase had not happened, so it suppresses nothing; the one
        // inside the window that WAS in force does.
        assertThat(simulatedIds(rule, asked)).containsExactly(chasedLater.getId());
        simulate(rule, asked)
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.asOf.date").value(asked.toString()));

        // And today, with no date open, the answer is the other one. The acting paths read this
        // same clause and must not have moved (A5, B3).
        assertThat(simulatedIds(rule, null)).containsExactly(chasedEarlier.getId());
    }

    /**
     * The upper bound on its own, with nothing else in the picture: the only step the rule has
     * ever finished was finished after the day being asked about, so on that day the rule had
     * suppressed nothing at all and the backtest counts the record. Anchoring the window's LOWER
     * end at T does not fix this — the subquery reads today's steps whatever it is anchored at, so
     * without {@code finishedAt <= T} the February step still suppresses January.
     */
    @Test
    void aStepFinishedAfterTheAsOfDateSuppressesNothingOnIt() throws Exception {
        AutomationRule rule = ruleWithCooldown(30);
        Invoice invoice = raise("1000.00");
        step(rule, invoice, today.minusDays(10));

        simulate(rule, asked).andExpect(jsonPath("$.matched").value(1));
        assertThat(simulatedIds(rule, asked)).containsExactly(invoice.getId());
        // Live, that same step is inside the window and the rule leaves the record alone.
        simulate(rule, null).andExpect(jsonPath("$.matched").value(0));
    }

    /**
     * And the anchor on its own: a step finished fifty days ago is outside today's thirty-day
     * window and INSIDE the one that was in force on the day asked about, so the backtest must
     * suppress the record that the live dry run counts. An upper bound alone would not fix this
     * one, which is why the fix is both.
     */
    @Test
    void aStepFinishedInsideTheWindowInForceThenSuppressesTheRecord() throws Exception {
        AutomationRule rule = ruleWithCooldown(30);
        Invoice invoice = raise("1000.00");
        step(rule, invoice, today.minusDays(50));

        simulate(rule, asked).andExpect(jsonPath("$.matched").value(0));
        assertThat(simulatedIds(rule, asked)).isEmpty();
        simulate(rule, null).andExpect(jsonPath("$.matched").value(1));
    }

    // ---------------------------------------------------------------- fixtures

    private static Instant noon(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant();
    }

    /** A real create with the WRITE-side clock held at the day, so the mirror row is dated then. */
    private Invoice raise(String total) {
        actAs(admin);
        clock.freezeAt(noon(raised));
        int quantity = new BigDecimal(total).divide(new BigDecimal("100.00")).intValue();
        Invoice invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                acme.getId(), noon(raised), raised.plusDays(30), PaymentTerm.CUSTOM, null,
                admin.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), quantity,
                        new BigDecimal("100.00")))));
        clock.release();
        SecurityContextHolder.clearContext();
        return invoice;
    }

    /**
     * A step this rule finished on a past day, written directly: {@code finished_at} is stamped by
     * the engine at the real now, so a past one is what the table looks like after months of the
     * rule running — which is the precondition the whole defect needs (A5, B3).
     */
    private void step(AutomationRule rule, Invoice about, LocalDate finishedOn) {
        automationStepRepository.saveAndFlush(AutomationStep.builder()
                .occasion("M" + rule.getId() + "-" + about.getId())
                .ruleId(rule.getId())
                .ruleName(rule.getName())
                .ruleVersion(rule.getDefinitionVersion())
                .actionIndex(0)
                .actionKind(ActionKind.CREATE_TASK)
                .subjectType(SubjectType.INVOICE)
                .subjectId(about.getId())
                .customerId(acme.getId())
                .source(StepSource.MANUAL)
                .status(StepStatus.DONE)
                .finishedAt(noon(finishedOn))
                .build());
    }

    private AutomationRule ruleWithCooldown(int cooldownDays) {
        actAs(ana);
        Long id = ruleService.create(new AutomationDtos.SaveRuleRequest("Chase them", null,
                SubjectType.INVOICE, TriggerKind.SCHEDULE_DAILY, 9, null,
                condition("balance:gt:0"),
                List.of(new ActionSpec.CreateTask("Chase {{Invoice.Number}}", null, List.of(), 3)),
                cooldownDays, true, List.of())).id();
        SecurityContextHolder.clearContext();
        return automationRuleRepository.findById(id).orElseThrow();
    }

    private JsonNode condition(String wire) {
        try {
            return objectMapper.readTree("{\"op\":\"AND\",\"of\":[{\"filter\":\"" + wire + "\"}]}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResultActions simulate(AutomationRule rule, LocalDate asOf) throws Exception {
        var request = post("/api/automation/rules/" + rule.getId() + "/simulate").with(as(ana));
        if (asOf != null) request = request.param("asOf", asOf.toString());
        return mockMvc.perform(request).andExpect(status().isOk());
    }

    private List<Long> simulatedIds(AutomationRule rule, LocalDate asOf) throws Exception {
        JsonNode body = objectMapper.readTree(
                simulate(rule, asOf).andReturn().getResponse().getContentAsString());
        List<Long> ids = new ArrayList<>();
        body.get("sample").forEach(row -> ids.add(row.get("id").asLong()));
        return ids;
    }

    private Role authorRole() {
        return roleWith("BACKTEST_COOLDOWN_AUTHOR", Privileges.AUTOMATION_VIEW,
                Privileges.AUTOMATION_MANAGE, Privileges.AUTOMATION_RUN, Privileges.CUSTOMER_VIEW,
                Privileges.INVOICE_VIEW, Privileges.TASK_VIEW, Privileges.TASK_MANAGE,
                // SCOPE_OVERRIDE so the POC book does not empty the sample: these tests are about
                // the rule's reach, not about Ana's book (B1).
                Privileges.SCOPE_OVERRIDE);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by RuleBacktestCooldownTest")
                .privileges(Arrays.stream(privileges)
                        .map(p -> privilegeRepository.findByName(p).orElseThrow())
                        .collect(Collectors.toCollection(HashSet<Privilege>::new)))
                .build()));
    }
}
