package com.geneinvoice.automation.action;

import com.geneinvoice.automation.AutomationTestBase;
import com.geneinvoice.automation.run.AutomationEffect;
import com.geneinvoice.automation.run.AutomationRun;
import com.geneinvoice.automation.run.EffectStatus;
import com.geneinvoice.automation.run.RunStatus;
import com.geneinvoice.common.Money;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.role.Role;
import com.geneinvoice.task.Task;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.task.TaskStatus;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CREATE_TASK as the consumer carries it out (§5.10.5, §5.10.6): once per record or on every event; its guard asked
 * again under the customer's lock; people who cannot see the record left out with a warning, and those who can
 * told; the task's region taken from the locked customer; the effect's unique key as the backstop against acting
 * twice; and nothing but the write itself inside the lock window (PC-4).
 */
class TaskActionTest extends AutomationTestBase {

    @Autowired PocService pocService;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManagerFactory entityManagerFactory;

    User admin;
    /** Owns the rules: a cashier, who sees every invoice. */
    User owen;
    Customer acme;
    Product widget;
    Long invoiceId;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        owen = user("owen.owner", "CASHIER");
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, collector.getId(), true);
        invoiceId = invoice(acme, widget, 2, null);
    }

    private Long invoiceRule(Object... actions) throws Exception {
        return saveRule(owen, rule("Chase", "INVOICE", "ENTITY_UPDATED", null, actions));
    }

    private void touch(Long id, String notes) {
        actAs(admin);
        invoiceService.update(id, new InvoiceDtos.UpdateInvoiceRequest(notes, null, null, null, null));
    }

    private List<AutomationEffect> effectsOf(Long ruleId) {
        return automationEffectRepository.findAll().stream().filter(e -> e.getRuleId().equals(ruleId))
                .sorted((a, b) -> a.getId().compareTo(b.getId())).toList();
    }

    @Test
    void anActionDoneOncePerRecordIsDoneOnceWhileOneDoneEveryTimeIsDoneForEachEvent() throws Exception {
        Map<String, Object> once = task("Chase {{Invoice.Number}}");
        once.put("onlyIfNoOpenTask", false);
        Map<String, Object> each = everyTime(task("Look at {{Invoice.Number}} again"));
        Long ruleId = invoiceRule(once, each);

        touch(invoiceId, "first");
        touch(invoiceId, "second");
        drain();

        List<Task> tasks = tasksOf(ruleId);
        String number = invoiceRepository.findById(invoiceId).orElseThrow().getInvoiceNumber();
        assertThat(tasks).extracting(Task::getTitle)
                .containsExactly("Chase " + number, "Look at " + number + " again", "Look at " + number + " again");
        assertThat(tasks).allSatisfy(t -> {
            assertThat(t.getOrigin()).isEqualTo(Task.ORIGIN_AUTOMATION);
            assertThat(t.getEntityType()).isEqualTo(TaskEntityType.INVOICE);
            assertThat(t.getEntityId()).isEqualTo(invoiceId);
            assertThat(t.getCreatedByUserId()).isEqualTo(owen.getId());
            assertThat(t.getDueDate()).isNotNull();
        });
        List<AutomationRun> runs = runsOf(ruleId);
        assertThat(runs).hasSize(2).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(RunStatus.SUCCEEDED));
        assertThat(tasks.get(0).getRunId()).isEqualTo(runs.get(0).getId());
        assertThat(runs.get(0).getDone()).isEqualTo(2);
        assertThat(runs.get(1).getDone()).isEqualTo(1);
        assertThat(runs.get(1).getSkipped()).as("the once-per-record task, already made").isEqualTo(1);
        assertThat(effectsOf(ruleId)).filteredOn(e -> e.getStatus() == EffectStatus.SKIPPED)
                .extracting(AutomationEffect::getMessage).containsExactly("Already done for this record");
        assertThat(effectsOf(ruleId)).filteredOn(e -> e.getStatus() == EffectStatus.DONE)
                .extracting(AutomationEffect::getResultType).containsOnly("TASK");
    }

    @Test
    void theOpenTaskGuardIsAskedAgainUnderTheLock() throws Exception {
        Long ruleId = invoiceRule(with(everyTime(task("Chase")), "onlyIfNoOpenTask", true));
        touch(invoiceId, "first");
        // Between the work before the lock and the write, another run's task appears on the invoice.
        automationFaults.onNextBeforeAction(() -> runElsewhere(() -> tx.executeWithoutResult(s ->
                taskRepository.save(Task.builder().title("Made meanwhile").status(TaskStatus.OPEN)
                        .entityType(TaskEntityType.INVOICE).entityId(invoiceId).entityLabel("Invoice")
                        .customerId(acme.getId()).region(defaultRegion()).origin(Task.ORIGIN_AUTOMATION)
                        .ruleId(ruleId).build()))));
        drain();

        assertThat(tasksOf(ruleId)).extracting(Task::getTitle).containsExactly("Made meanwhile");
        assertThat(effectsOf(ruleId)).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(EffectStatus.SKIPPED);
            assertThat(e.getMessage()).isEqualTo("An open task from this rule is already on this record");
            assertThat(e.getDedupeKey()).isNull();
        });

        // Closed, the guard lets the next event make one.
        tx.executeWithoutResult(s -> taskRepository.findAll().forEach(t -> {
            t.setStatus(TaskStatus.DONE);
            taskRepository.save(t);
        }));
        touch(invoiceId, "second");
        drain();
        assertThat(tasksOf(ruleId)).extracting(Task::getTitle).containsExactly("Made meanwhile", "Chase");
    }

    /** Someone who sees the record but not tasks in its region is left out too, saying why (CF-9). */
    @Test
    void aPersonWhoCannotSeeTasksInTheRegionIsLeftOutWithAWarning() throws Exception {
        Role noTasks = roleWith("COLLECTIONS_NO_TASK_VIEW", role(DataSeeder.ROLE_COLLECTION_POC).getPrivileges()
                .stream().map(Privilege::getName).filter(n -> !n.startsWith("TASK_")).toArray(String[]::new));
        User nina = user("nina.notasks", noTasks.getName());
        User helper = user("hana.helper", "CASHIER");
        Long ruleId = invoiceRule(with(task("Chase", userToken(nina), userToken(helper)), "onlyIfNoOpenTask", false));
        touch(invoiceId, "first");
        drain();

        Task made = tasksOf(ruleId).get(0);
        assertThat(recordAssigneeRepository.findAll()).filteredOn(a -> a.getOwnerId().equals(made.getId()))
                .extracting(a -> a.getUserId()).containsExactly(helper.getId());
        AutomationEffect effect = effectsOf(ruleId).get(0);
        assertThat(effect.getStatus()).isEqualTo(EffectStatus.DONE);
        assertThat(effect.warningList()).containsExactly("NINA.NOTASKS cannot see tasks here, so was not assigned");
    }

    @Test
    void aPersonWhoCannotSeeTheRecordIsLeftOutWithAWarningAndTheVisibleAreTold() throws Exception {
        Region north = region("NORTH");
        User outsider = staff("olly.outsider", DataSeeder.ROLE_COLLECTION_POC, north, AccessLevel.MANAGE,
                RecordScope.ALL);
        User helper = user("hana.helper", "CASHIER");
        Long ruleId = invoiceRule(with(task("Chase {{Invoice.Number}}", userToken(outsider), userToken(helper),
                roleToken("COLLECTION_POC", "CUSTOMER")), "onlyIfNoOpenTask", false));
        touch(invoiceId, "first");
        drain();

        Task made = tasksOf(ruleId).get(0);
        assertThat(recordAssigneeRepository.findAll()).filteredOn(a -> a.getOwnerId().equals(made.getId()))
                .extracting(a -> a.getUserId() == null ? a.getRole() : String.valueOf(a.getUserId()))
                .containsExactly(String.valueOf(helper.getId()), "COLLECTION_POC");
        AutomationEffect effect = effectsOf(ruleId).get(0);
        assertThat(effect.getStatus()).isEqualTo(EffectStatus.DONE);
        assertThat(effect.warningList()).containsExactly("OLLY.OUTSIDER cannot see this record, so was not assigned");
        assertThat(effect.getTargetLabel()).startsWith("Invoice ");

        List<Notification> told = notificationRepository.findAll().stream()
                .filter(n -> n.getType().equals("TASK_ASSIGNED")).toList();
        assertThat(told).extracting(Notification::getUserId)
                .containsExactlyInAnyOrder(helper.getId(), collector.getId());
        assertThat(told).allSatisfy(n -> assertThat(n.getLink()).isEqualTo("/tasks/" + made.getId()));
    }

    @Test
    void theTaskTakesItsRegionFromTheLockedCustomer() throws Exception {
        Region south = region("SOUTH");
        grant(owen, south, AccessLevel.MANAGE, RecordScope.ALL);
        grant(admin, south, AccessLevel.APPROVE, RecordScope.ALL);
        grant(seller, south, AccessLevel.MANAGE, RecordScope.ALL);
        Customer beta = customer("Beta Group", south);
        actAs(admin);
        Long betaInvoice = invoice(beta, widget, 1, null);
        Long ruleId = invoiceRule(with(task("Chase"), "onlyIfNoOpenTask", false));
        touch(betaInvoice, "first");
        drain();

        Task made = tasksOf(ruleId).get(0);
        assertThat(made.getRegion().getId()).isEqualTo(south.getId());
        assertThat(made.getCustomerId()).isEqualTo(beta.getId());
    }

    @Test
    void theEffectsUniqueKeyIsTheBackstopWhenTwoWorkersRace() throws Exception {
        Map<String, Object> once = with(task("Chase"), "onlyIfNoOpenTask", false);
        Long ruleId = invoiceRule(once);
        touch(invoiceId, "first");
        String key = AutomationEffect.oncePerRecordKey(ruleId, (String) once.get("key"), "INVOICE", invoiceId);
        // Another worker, which never waited for this one's lock, acts first and commits its effect.
        AtomicReference<Long> racer = new AtomicReference<>();
        automationFaults.onNextBeforeAction(() -> runElsewhere(() -> racer.set(tx.execute(s ->
                automationEffectRepository.save(AutomationEffect.of(-1L, ruleId, -1L, (String) once.get("key"),
                        "CREATE_TASK", "INVOICE", invoiceId, "Invoice", EffectStatus.DONE, "TASK", -1L, null,
                        List.of(), key, Instant.now())).getId()))));
        drain();

        assertThat(tasksOf(ruleId)).as("the task this worker made was rolled back with its effect").isEmpty();
        assertThat(automationEffectRepository.findAll()).filteredOn(e -> key.equals(e.getDedupeKey()))
                .extracting(AutomationEffect::getId).containsExactly(racer.get());
        AutomationRun run = runsOf(ruleId).get(0);
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getSkipped()).isEqualTo(1);
        assertThat(run.getDone()).isZero();
    }

    @Test
    void insideTheLockWindowThereIsOnlyTheWriteItself() throws Exception {
        User helper = user("hana.helper", "CASHIER");
        Long ruleId = invoiceRule(with(task("Chase {{Invoice.Number}} for {{Customer.Name}} ({{Invoice.Balance}})",
                userToken(helper), roleToken("COLLECTION_POC", "CUSTOMER")), "onlyIfNoOpenTask", true));
        touch(invoiceId, "first");
        automationFaults.countLockWindows(entityManagerFactory.unwrap(SessionFactory.class));
        drain();

        Task made = tasksOf(ruleId).get(0);
        assertThat(made.getTitle()).isEqualTo("Chase " + invoiceRepository.findById(invoiceId).orElseThrow()
                .getInvoiceNumber() + " for Acme Ltd (" + Money.format(new java.math.BigDecimal("200.00")) + ")");
        assertThat(recordAssigneeRepository.findAll()).hasSize(2);
        assertThat(automationFaults.lockWindows()).singleElement()
                .satisfies(n -> assertThat(n).as("statements from the lock to the commit").isBetween(1L, 30L));
    }

    /** Runs {@code work} on another thread, as another worker would, and waits for it. */
    private static void runElsewhere(Runnable work) {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                work.run();
            } catch (Throwable e) {
                failed.set(e);
            }
        });
        t.start();
        try {
            t.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (failed.get() != null) throw new IllegalStateException(failed.get());
    }
}
