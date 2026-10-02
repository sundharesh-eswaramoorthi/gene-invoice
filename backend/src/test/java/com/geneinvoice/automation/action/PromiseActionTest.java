package com.geneinvoice.automation.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.assignee.AssignedTo;
import com.geneinvoice.assignee.RecordAssignee;
import com.geneinvoice.automation.AutomationTestBase;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.run.AutomationEffect;
import com.geneinvoice.automation.run.EffectStatus;
import com.geneinvoice.automation.run.RunStatus;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CREATE_PROMISE as the consumer carries it out (§5.10.5, §5.10.12): the customer's primary Collection POC works it
 * unless the action names one, and without one the action fails for good; its co-assignees are stored and mean
 * whoever holds a role now; nothing owed leaves it undone, and so does an open promise when the action says so; and
 * done once per record, it is done once across every event.
 */
class PromiseActionTest extends AutomationTestBase {

    /**
     * The statements one promise may run from the lock to its commit: 31 when whom to tell was worked out before it,
     * 47 when it is worked out inside, as it once was.
     */
    static final long LOCK_MAX = 38;

    @Autowired PocService pocService;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManagerFactory entityManagerFactory;

    User admin;
    /** Owns the rules: may make promises everywhere, but approves nothing. */
    User owen;
    /** A second Collection POC on the customer, seated first, but not its primary. */
    User colin;
    Customer acme;
    Product widget;
    Long invoiceId;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        roleWith("PROMISE_RULER", Privileges.AUTOMATION_VIEW, Privileges.AUTOMATION_MANAGE, Privileges.CUSTOMER_VIEW,
                Privileges.INVOICE_VIEW, Privileges.PAYMENT_VIEW, Privileges.PROMISE_VIEW, Privileges.PROMISE_MANAGE,
                Privileges.SCOPE_OVERRIDE, Privileges.POC_VIEW, Privileges.NOTIFICATION_VIEW);
        owen = user("owen.owner", "PROMISE_RULER");
        colin = user("colin.collector", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, colin.getId(), false);
        pocService.add(acme.getId(), PocType.COLLECTION, collector.getId(), true);
        invoiceId = invoice(acme, widget, 2, null);
    }

    private static Map<String, Object> promise(String repeat, boolean onlyIfNoOpenPromise, Object... assignees) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("key", UUID.randomUUID().toString());
        a.put("type", "CREATE_PROMISE");
        a.put("repeat", repeat);
        a.put("amount", Map.of("source", "FIELD", "field", "Invoice.Balance"));
        a.put("promisedDate", Map.of("base", "TODAY", "offsetDays", 7));
        a.put("notes", "For {{Invoice.Number}}");
        a.put("linkRecord", true);
        a.put("onlyIfNoOpenPromise", onlyIfNoOpenPromise);
        a.put("assignees", List.of(assignees));
        return a;
    }

    private Long invoiceRule(Map<String, Object> action) throws Exception {
        return saveRule(owen, rule("Promise", "INVOICE", "ENTITY_UPDATED", null, action));
    }

    private void touch(Long id, String notes) {
        actAs(admin);
        invoiceService.update(id, new InvoiceDtos.UpdateInvoiceRequest(notes, null, null, null, null));
    }

    private List<PaymentPromise> promises() {
        return tx.execute(s -> promiseRepository.findAll().stream()
                .sorted(Comparator.comparing(PaymentPromise::getId))
                .peek(p -> p.getInvoices().size())
                .toList());
    }

    private List<AutomationEffect> effectsOf(Long ruleId) {
        return automationEffectRepository.findAll().stream().filter(e -> e.getRuleId().equals(ruleId))
                .sorted(Comparator.comparing(AutomationEffect::getId)).toList();
    }

    private List<Notification> noticesTo(User u, String type) {
        return notificationRepository.findAll().stream()
                .filter(n -> n.getUserId().equals(u.getId()) && n.getType().equals(type)).toList();
    }

    @Test
    void thePrimaryCollectionPocWorksThePromiseAndItsCoAssigneesMeanWhoeverHoldsTheRoleNow() throws Exception {
        Long ruleId = invoiceRule(promise("ONCE_PER_RECORD", false,
                userToken(admin), roleToken("CUSTOMER_SUCCESS_POC", "CUSTOMER")));
        touch(invoiceId, "first");
        drain();

        List<PaymentPromise> made = promises();
        assertThat(made).hasSize(1);
        PaymentPromise p = made.get(0);
        String number = invoiceRepository.findById(invoiceId).map(Invoice::getInvoiceNumber).orElseThrow();
        assertThat(p.getAmount()).isEqualByComparingTo("200.00");
        assertThat(p.getPromisedDate()).isEqualTo(Today.date().plusDays(7));
        assertThat(p.getCollectionPoc().getId()).as("the primary, not the older seat").isEqualTo(collector.getId());
        assertThat(p.getNotes()).isEqualTo("For " + number);
        assertThat(p.getInvoices()).extracting(Invoice::getId).containsExactly(invoiceId);
        assertThat(p.getCreatedByUserId()).isEqualTo(owen.getId());
        assertThat(p.getStatus()).isEqualTo(PromiseStatus.OPEN);
        AutomationEffect effect = effectsOf(ruleId).get(0);
        assertThat(effect.getStatus()).isEqualTo(EffectStatus.DONE);
        assertThat(effect.getResultType()).isEqualTo("PROMISE");
        assertThat(effect.getResultId()).isEqualTo(p.getId());

        assertThat(recordAssigneeRepository.findAll()).filteredOn(r -> r.getOwnerType().equals(AssignedTo.PROMISE)
                        && r.getOwnerId().equals(p.getId()) && r.getRemovedAt() == null)
                .extracting(r -> RecordAssignee.USER.equals(r.getKind()) ? "USER:" + r.getUserId() : r.getRole())
                .containsExactly("USER:" + admin.getId(), "CUSTOMER_SUCCESS_POC");
        assertThat(noticesTo(admin, "PROMISE_ASSIGNED")).as("a rule's assignee is told, its owner too")
                .hasSize(1);

        // Stored as roles, resolved now: whoever takes the seat later is the assignee.
        JsonNode before = body(mockMvc.perform(get("/api/promises/" + p.getId()).with(as(admin))));
        assertThat(before.get("assignees").get(1).get("unresolved").asBoolean()).isTrue();
        User sue = user("sue.success", DataSeeder.ROLE_SUCCESS_POC);
        actAs(admin);
        pocService.add(acme.getId(), PocType.SUCCESS, sue.getId(), true);
        JsonNode after = body(mockMvc.perform(get("/api/promises/" + p.getId()).with(as(admin))));
        assertThat(after.get("assignees").get(1).get("people")).extracting(n -> n.get("userId").asLong())
                .containsExactly(sue.getId());
        assertThat(after.get("assignees").get(0).get("people").get(0).get("userId").asLong())
                .isEqualTo(admin.getId());
    }

    @Test
    void withNoCollectionPocTheActionFailsForGood() throws Exception {
        Customer bare = customer("Bare Ltd");
        actAs(admin);
        Long bareInvoice = invoice(bare, widget, 1, null);
        Long ruleId = invoiceRule(promise("ONCE_PER_RECORD", false));
        touch(bareInvoice, "first");
        drain();

        assertThat(promises()).isEmpty();
        AutomationEffect effect = effectsOf(ruleId).get(0);
        assertThat(effect.getStatus()).isEqualTo(EffectStatus.FAILED);
        assertThat(effect.getMessage()).isEqualTo("No Collection POC");
        assertThat(runsOf(ruleId).get(0).getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(automationEventRepository.findAll()).allSatisfy(e -> assertThat(e.getStatus()).isEqualTo(AutomationEvent.DONE));
    }

    @Test
    void nothingOwedLeavesItUndone() throws Exception {
        pay(acme, "200.00", invoiceId);
        drain();
        Long ruleId = invoiceRule(promise("EVERY_TIME", false));
        touch(invoiceId, "paid up");
        drain();

        assertThat(promises()).isEmpty();
        AutomationEffect effect = effectsOf(ruleId).get(0);
        assertThat(effect.getStatus()).isEqualTo(EffectStatus.SKIPPED);
        assertThat(effect.getMessage()).isEqualTo("Nothing owed");
        assertThat(effect.getDedupeKey()).isNull();
    }

    @Test
    void nothingOwedIsSkippedEvenWhereNobodyCouldWorkAPromise() throws Exception {
        Customer bare = customer("Bare Ltd");
        actAs(admin);
        Long bareInvoice = invoice(bare, widget, 1, null);
        pay(bare, "100.00", bareInvoice);
        drain();
        Map<String, Object> action = promise("EVERY_TIME", false);
        Long ruleId = invoiceRule(action);
        touch(bareInvoice, "paid up");
        drain();

        assertThat(promises()).isEmpty();
        AutomationEffect effect = effectsOf(ruleId).get(0);
        assertThat(effect.getStatus()).as("no Collection POC is asked for when nothing is owed")
                .isEqualTo(EffectStatus.SKIPPED);
        assertThat(effect.getMessage()).isEqualTo("Nothing owed");
        assertThat(runsOf(ruleId).get(0).getStatus()).isEqualTo(RunStatus.SUCCEEDED);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rule", rule("Promise", "INVOICE", "ENTITY_UPDATED", null, action));
        body.put("recordId", bareInvoice);
        body.put("sampleSize", 10);
        JsonNode preview = body(send(post(RULES + "/preview"), owen, body)).get("record").get("actions").get(0);
        assertThat(preview.get("problems")).as("the preview agrees: it would run, and do nothing").isEmpty();
        assertThat(preview.get("warnings")).extracting(JsonNode::asText)
                .contains("Nothing owed, so none would be made");
    }

    /** Whom to tell is worked out before the lock (§5.10.6): inside it, only the write and its notices. */
    @Test
    void insideTheLockWindowThereIsOnlyTheWriteAndItsNotices() throws Exception {
        User helper = user("hana.helper", "PROMISE_RULER");
        Long ruleId = invoiceRule(promise("ONCE_PER_RECORD", false, userToken(admin), userToken(helper),
                roleToken("COLLECTION_POC", "RECORD"), roleToken("COLLECTION_POC", "CUSTOMER")));
        touch(invoiceId, "first");
        automationFaults.countLockWindows(entityManagerFactory.unwrap(SessionFactory.class));
        drain();

        PaymentPromise p = promises().get(0);
        assertThat(effectsOf(ruleId).get(0).getResultId()).isEqualTo(p.getId());
        assertThat(noticesTo(admin, "PROMISE_ASSIGNED")).hasSize(1);
        assertThat(noticesTo(helper, "PROMISE_ASSIGNED")).hasSize(1);
        assertThat(noticesTo(collector, "PROMISE_ASSIGNED")).as("the promise's own, and the customer's primary")
                .singleElement().satisfies(n -> assertThat(n.getLink()).isEqualTo("/promises/" + p.getId()));
        assertThat(automationFaults.lockWindows()).singleElement()
                .satisfies(n -> assertThat(n).as("statements from the lock to the commit").isBetween(1L, LOCK_MAX));
    }

    @Test
    void anOpenPromiseOnTheInvoiceKeepsAnotherFromBeingMadeWhenTheActionSaysSo() throws Exception {
        Long ruleId = invoiceRule(promise("EVERY_TIME", true));
        touch(invoiceId, "first");
        drain();
        touch(invoiceId, "second");
        drain();

        assertThat(promises()).hasSize(1);
        List<AutomationEffect> effects = effectsOf(ruleId);
        assertThat(effects).extracting(AutomationEffect::getStatus)
                .containsExactly(EffectStatus.DONE, EffectStatus.SKIPPED);
        assertThat(effects.get(1).getMessage()).isEqualTo("An open promise is already linked to this invoice");

        // Once it is no longer open, the next change makes another.
        tx.executeWithoutResult(s -> promiseRepository.findAll().forEach(p -> {
            p.setStatus(PromiseStatus.CANCELLED);
            promiseRepository.save(p);
        }));
        touch(invoiceId, "third");
        drain();
        assertThat(promises()).hasSize(2);
    }

    @Test
    void doneOncePerRecordItIsDoneOnceAcrossEveryEvent() throws Exception {
        Long ruleId = invoiceRule(promise("ONCE_PER_RECORD", false));
        touch(invoiceId, "first");
        drain();
        touch(invoiceId, "second");
        touch(invoiceId, "third");
        drain();

        assertThat(promises()).hasSize(1);
        assertThat(effectsOf(ruleId)).extracting(AutomationEffect::getStatus)
                .containsExactly(EffectStatus.DONE, EffectStatus.SKIPPED, EffectStatus.SKIPPED);
        assertThat(effectsOf(ruleId)).filteredOn(e -> e.getStatus() == EffectStatus.SKIPPED)
                .extracting(AutomationEffect::getMessage).containsOnly("Already done for this record");
    }
}
