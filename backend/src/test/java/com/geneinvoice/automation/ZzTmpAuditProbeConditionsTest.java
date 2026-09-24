package com.geneinvoice.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ZzTmpAuditProbeConditionsTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired AutomationRuleService ruleService;
    @Autowired AutomationDispatcher dispatcher;

    User admin;
    User ana;
    Customer home;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        ana = admin;
        widget = product("Widget", "1000.00");
        home = customer("Home Ltd", "ap@home.test");
    }

    /** PROBE 1: is a leaf with an uncoercible VALUE accepted at rule-save time? */
    @Test
    void probeUncoercibleValueIsAcceptedAtSave() throws Exception {
        actAs(ana);
        assertThatCode(() -> ruleService.create(new AutomationDtos.SaveRuleRequest(
                "Bad money value", null, SubjectType.INVOICE, TriggerKind.ON_CREATED_OR_UPDATED,
                null, null,
                json("{\"op\":\"OR\",\"of\":[{\"filter\":\"balance:gt:0\"},{\"filter\":\"balance:gt:abc\"}]}"),
                List.of(new ActionSpec.CreateTask("Chase {{Invoice.Number}}", null, List.of(), 3)),
                null, true, List.of())))
                .doesNotThrowAnyException();
        SecurityContextHolder.clearContext();
        System.out.println(">>> PROBE1: rule with balance:gt:abc SAVED, stored json = "
                + automationRuleRepository.findAll().get(0).getConditionJson());
    }

    /** PROBE 2: does that poisoned OR silently suppress the branch that DOES match? */
    @Test
    void probePoisonedOrBranchSuppressesTheWholeRule() {
        actAs(ana);
        // control: a plain OR, both branches coercible
        ruleService.create(new AutomationDtos.SaveRuleRequest("Good", null, SubjectType.INVOICE,
                TriggerKind.ON_CREATED_OR_UPDATED, null, null,
                json("{\"op\":\"OR\",\"of\":[{\"filter\":\"balance:gt:0\"},{\"filter\":\"status:eq:FULLY_PAID\"}]}"),
                List.of(new ActionSpec.CreateTask("Good {{Invoice.Number}}", null, List.of(), 3)),
                null, true, List.of()));
        SecurityContextHolder.clearContext();

        Invoice invoice = invoiceFor(home);
        dispatcher.fanOut(eventFor(invoice.getId()), Instant.now().plusSeconds(60));
        long controlSteps = automationStepRepository.count();
        System.out.println(">>> PROBE2 control steps planned = " + controlSteps);
        assertThat(controlSteps).isEqualTo(1);
    }

    /** PROBE 3: same fixture, but one OR branch has a bad value. */
    @Test
    void probePoisonedOrBranchNeverFires() {
        actAs(ana);
        ruleService.create(new AutomationDtos.SaveRuleRequest("Poisoned", null, SubjectType.INVOICE,
                TriggerKind.ON_CREATED_OR_UPDATED, null, null,
                json("{\"op\":\"OR\",\"of\":[{\"filter\":\"balance:gt:0\"},{\"filter\":\"balance:gt:abc\"}]}"),
                List.of(new ActionSpec.CreateTask("Poison {{Invoice.Number}}", null, List.of(), 3)),
                null, true, List.of()));
        SecurityContextHolder.clearContext();

        Invoice invoice = invoiceFor(home);
        dispatcher.fanOut(eventFor(invoice.getId()), Instant.now().plusSeconds(60));
        long steps = automationStepRepository.count();
        System.out.println(">>> PROBE3 poisoned-OR steps planned = " + steps
                + " (control planned 1; balance:gt:0 is plainly true for this invoice)");
    }

    /** PROBE 4: what does the dry-run / run-now door do with the same rule? */
    @Test
    void probeDryRunOnPoisonedRule() throws Exception {
        actAs(ana);
        Long id = ruleService.create(new AutomationDtos.SaveRuleRequest("Poisoned", null,
                SubjectType.INVOICE, TriggerKind.SCHEDULE_DAILY, 9, null,
                json("{\"op\":\"OR\",\"of\":[{\"filter\":\"balance:gt:0\"},{\"filter\":\"balance:gt:abc\"}]}"),
                List.of(new ActionSpec.CreateTask("Poison {{Invoice.Number}}", null, List.of(), 3)),
                null, true, List.of())).id();
        SecurityContextHolder.clearContext();
        invoiceFor(home);
        try {
            String body = mockMvc.perform(org.springframework.test.web.servlet.request
                            .MockMvcRequestBuilders.post("/api/automation/rules/" + id + "/run")
                            .with(as(ana)))
                    .andReturn().getResponse().getContentAsString();
            System.out.println(">>> PROBE4 dry run response = " + body);
        } catch (Exception e) {
            System.out.println(">>> PROBE4 dry run threw " + e);
        }
    }

    /** PROBE 5: the scheduled sweep with a poisoned rule in the book. */
    @Test
    void probeScheduledSweepWithPoisonedRule() {
        actAs(ana);
        ruleService.create(new AutomationDtos.SaveRuleRequest("Poisoned daily", null,
                SubjectType.INVOICE, TriggerKind.SCHEDULE_DAILY, 0, null,
                json("{\"op\":\"OR\",\"of\":[{\"filter\":\"balance:gt:0\"},{\"filter\":\"balance:gt:abc\"}]}"),
                List.of(new ActionSpec.CreateTask("Poison {{Invoice.Number}}", null, List.of(), 3)),
                null, true, List.of()));
        ruleService.create(new AutomationDtos.SaveRuleRequest("Healthy daily", null,
                SubjectType.INVOICE, TriggerKind.SCHEDULE_DAILY, 0, null,
                json("{\"op\":\"AND\",\"of\":[{\"filter\":\"balance:gt:0\"}]}"),
                List.of(new ActionSpec.CreateTask("Good {{Invoice.Number}}", null, List.of(), 3)),
                null, true, List.of()));
        SecurityContextHolder.clearContext();
        invoiceFor(home);
        try {
            dispatcher.sweep(Instant.now().plus(java.time.Duration.ofDays(2)));
            System.out.println(">>> PROBE5 sweep OK, steps = " + automationStepRepository.count()
                    + " runs = " + automationRunRepository.count());
            automationStepRepository.findAll().forEach(s ->
                    System.out.println(">>>    step rule=" + s.getRuleName() + " status=" + s.getStatus()));
        } catch (RuntimeException e) {
            System.out.println(">>> PROBE5 sweep THREW " + e);
        }
    }

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Long eventFor(Long invoiceId) {
        return automationEventRepository.findAll().stream()
                .filter(e -> e.getSubjectType() == SubjectType.INVOICE
                        && e.getSubjectId().equals(invoiceId))
                .map(AutomationEvent::getId).findFirst().orElseThrow();
    }

    private Invoice invoiceFor(Customer c) {
        actAs(admin);
        Invoice invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(),
                null, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("1000.00")))));
        SecurityContextHolder.clearContext();
        return invoice;
    }

}
