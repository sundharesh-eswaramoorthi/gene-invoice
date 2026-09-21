package com.geneinvoice.automation;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.email.EmailDtos;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.task.Task;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The people, the customer and the rule-writing shorthand the automation tests share.
 *
 * <p>Two things about driving this engine from a test are worth knowing before reading any of
 * them. {@code app.automation.async} is false, so a hand-off made from a thread with no
 * transaction open runs the work right there and the outcome is on the table by the time the call
 * returns — that is how the sweep, a scheduled run and Run now are driven here. The one hand-off
 * that still goes to the background thread is the nudge after a user's save, because it arrives
 * inside an after-commit callback where running the work would join a transaction that has already
 * committed; those tests, and only those, wait with {@link #waitUntil}.
 */
abstract class AutomationTestBase extends IntegrationTestBase {

    @Autowired protected AutomationService automationService;
    @Autowired protected AutomationEvents outbox;
    @Autowired protected AutomationWorker worker;
    @Autowired protected AutomationScheduler scheduler;
    @Autowired protected InvoiceService invoiceService;
    @Autowired protected PocService pocService;

    protected User admin;
    protected User collections;
    protected User sales;
    protected Customer acme;
    protected Product widget;

    @BeforeEach
    void automationFixtures() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        acme = customer("Acme Ltd", "ap@acme.test");
        widget = product("Widget", "100.00");
        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, collections.getId(), true);
    }

    // ---- records ---------------------------------------------------------------

    /** An invoice for Acme of {@code unitPrice} × {@code qty}, so a test can put a total either side of a rule's figure. */
    protected Invoice invoice(String unitPrice, int qty) {
        actAs(admin);
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                acme.getId(), null, null, sales.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), qty, new BigDecimal(unitPrice)))));
    }

    /** The everyday edit a user makes from the detail screen, which is what an UPDATED rule watches. */
    protected Invoice edit(Invoice inv, String notes) {
        actAs(admin);
        return invoiceService.update(inv.getId(), new InvoiceDtos.UpdateInvoiceRequest(notes, null));
    }

    // ---- writing rules ---------------------------------------------------------

    /** The rule as the engine holds it, written through the very service the rules screen posts to. */
    protected AutomationRule rule(String name, AutomationEntityType type, TriggerKind trigger,
                                  List<String> filters, ActionType action,
                                  AutomationDtos.ActionSpec spec) {
        actAs(admin);
        AutomationDtos.RuleDto dto = automationService.create(new AutomationDtos.CreateRuleRequest(
                name, null, true, type.name(), trigger.name(), filters, action.name(), spec));
        return automationRuleRepository.findById(dto.id()).orElseThrow();
    }

    /** "Raise a task called this, for these people, due a week out." */
    protected static AutomationDtos.ActionSpec taskSpec(String title, EmailDtos.EmailToken... people) {
        return new AutomationDtos.ActionSpec(title, null, null, null, null, List.of(people), null);
    }

    protected static EmailDtos.EmailToken userToken(User u) {
        return new EmailDtos.EmailToken("USER", u.getId(), null, null);
    }

    /** A role at a level, exactly as the recipient picker produces it. */
    protected static EmailDtos.EmailToken roleToken(String role, String level) {
        return new EmailDtos.EmailToken("ROLE", null, role, level);
    }

    protected static EmailDtos.EmailToken customerToken() {
        return new EmailDtos.EmailToken("CUSTOMER", null, null, null);
    }

    // ---- reading the outbox back -----------------------------------------------

    /** Every row this rule has been asked about, oldest first. */
    protected List<AutomationEvent> eventsOf(AutomationRule rule) {
        return automationEventRepository.findAll().stream()
                .filter(e -> rule.getId().equals(e.getRuleId()))
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }

    /** The fan-out rows a save wrote: the ones that name what happened and no rule. */
    protected List<AutomationEvent> fanOutEvents() {
        return automationEventRepository.findAll().stream()
                .filter(e -> e.getRuleId() == null)
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }

    protected AutomationEvent reload(AutomationEvent event) {
        return automationEventRepository.findById(event.getId()).orElseThrow();
    }

    /**
     * One rule against one record, written straight to the table with the key the engine would
     * have given it: a piece of work waiting for a consumer, which a test can then hand to the
     * worker at a time of its choosing.
     */
    protected AutomationEvent ruleRow(AutomationRule rule, Long entityId, Instant now) {
        return automationEventRepository.save(AutomationEvent.builder()
                .ruleId(rule.getId())
                .entityType(rule.getEntityType())
                .entityId(entityId)
                .trigger(rule.getTrigger())
                .idempotencyKey(AutomationEvent.ruleKey(rule.getId(), rule.getEntityType(), entityId,
                        rule.getTrigger(), now))
                .status(AutomationEventStatus.QUEUED)
                .enqueuedAt(now)
                .build());
    }

    /**
     * An outbox row as the transport would find it, written straight to the table: the consumer was
     * down when the save happened, so nobody has published it and nobody has worked it.
     */
    protected AutomationEvent unpublishedFanOut(AutomationEntityType type, Long entityId, TriggerKind trigger) {
        return automationEventRepository.save(AutomationEvent.builder()
                .entityType(type)
                .entityId(entityId)
                .trigger(trigger)
                .idempotencyKey(AutomationEvent.fanOutKey(type, entityId, trigger))
                .status(AutomationEventStatus.QUEUED)
                .enqueuedAt(null)
                .build());
    }

    // ---- what the rules made ---------------------------------------------------

    protected List<Task> tasksOn(Invoice inv) {
        return taskRepository.findByEntityTypeAndEntityIdOrderByIdAsc(TaskEntityType.INVOICE, inv.getId());
    }

    /**
     * Waits for work the background thread is carrying — the nudge after a user's save is the only
     * hand-off in these tests that does not run inline.
     */
    protected static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting");
            Thread.sleep(20);
        }
    }

    /** True once every outbox row has been settled, so "nothing was created" means nothing will be. */
    protected boolean outboxSettled() {
        List<AutomationEvent> all = automationEventRepository.findAll();
        return !all.isEmpty() && all.stream().allMatch(e ->
                e.getStatus() != AutomationEventStatus.QUEUED
                        && e.getStatus() != AutomationEventStatus.RUNNING);
    }

    /** A fixed instant, so the day and week buckets a key is built from never move under a test. */
    protected static final Instant MORNING = Instant.parse("2026-09-21T09:00:00Z");
}
