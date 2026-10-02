package com.geneinvoice.automation.run;

import com.geneinvoice.MovableClock;
import com.geneinvoice.automation.AutomationTestBase;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.common.LobLoadProbe;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.task.Task;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What sets a rule off, end to end through the consumer (A1, PC-15): each kind of record, each trigger, a create and
 * an update, drained with no one signed in. Also: a record made with credit applied in the same save is one CREATED
 * event, a MANUAL rule never fires by itself, a region move's CHILDREN event runs an invoice rule once per matching
 * invoice, and a customer counts as updated when an invoice takes its outstanding past a figure.
 */
class TriggerMatrixTest extends AutomationTestBase {

    @Autowired CustomerService customerService;
    @Autowired EntityManagerFactory emf;

    User admin;
    User owen;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        owen = user("owen.owner", "CASHIER");
        widget = product("Widget", "100.00");
    }

    static Stream<Arguments> matrix() {
        return Stream.of("CUSTOMER", "INVOICE", "PAYMENT").flatMap(entity -> Stream.of(
                Arguments.of(entity, "ENTITY_CREATED", true, false),
                Arguments.of(entity, "ENTITY_UPDATED", false, true),
                Arguments.of(entity, "ENTITY_CREATED_OR_UPDATED", true, true)));
    }

    @ParameterizedTest(name = "{0} {1}: fires on create {2}, on update {3}")
    @MethodSource("matrix")
    void eachTriggerFiresOnWhatItNames(String entity, String kind, boolean onCreate, boolean onUpdate)
            throws Exception {
        Long ruleId = saveRule(owen, rule(entity + " " + kind, entity, kind, null,
                everyTime(task("Look at it"))));
        Long recordId = create(entity);
        drain();
        List<Task> afterCreate = on(ruleId, entity, recordId);
        assertThat(afterCreate).as("fired on create").hasSize(onCreate ? 1 : 0);

        update(entity, recordId);
        drain();
        assertThat(on(ruleId, entity, recordId)).as("fired on update")
                .hasSize(afterCreate.size() + (onUpdate ? 1 : 0));
    }

    private List<Task> on(Long ruleId, String entity, Long recordId) {
        return tasksOf(ruleId).stream().filter(t -> t.getEntityType() == TaskEntityType.valueOf(entity)
                && t.getEntityId().equals(recordId)).toList();
    }

    /** A new record of the kind, as a person makes it; its id. */
    private Long create(String entity) {
        actAs(admin);
        return switch (entity) {
            case "CUSTOMER" -> customerService.create(new CustomerDtos.CustomerCreateRequest("Acme " + UUID.randomUUID(),
                    null, null, null, null, "acme-" + UUID.randomUUID(), "Password1!", null)).getId();
            case "INVOICE" -> invoice(customer("Acme Ltd"), widget, 1, null);
            default -> {
                Customer c = customer("Acme Ltd");
                yield pay(c, "100.00", invoice(c, widget, 1, null));
            }
        };
    }

    private void update(String entity, Long id) {
        actAs(admin);
        switch (entity) {
            case "CUSTOMER" -> {
                Customer c = customerRepository.findById(id).orElseThrow();
                customerService.update(id, new CustomerDtos.CustomerUpdateRequest(c.getName(), "555-0100", null, null,
                        null, null));
            }
            case "INVOICE" -> invoiceService.update(id, new InvoiceDtos.UpdateInvoiceRequest("chased", null, null,
                    null, null));
            default -> paymentService.update(id, new PaymentDtos.UpdatePaymentRequest("checked", null));
        }
    }

    @Test
    void anInvoiceMadeWithCreditAppliedInTheSameSaveIsCreatedNotUpdated() throws Exception {
        Customer acme = customer("Acme Ltd");
        actAs(admin);
        pay(acme, "300.00", invoice(acme, widget, 1, null));
        assertThat(customerRepository.findById(acme.getId()).orElseThrow().getCreditBalance()).isPositive();
        drain();
        Long created = saveRule(owen, rule("Created", "INVOICE", "ENTITY_CREATED", null, task("Created")));
        Long updated = saveRule(owen, rule("Updated", "INVOICE", "ENTITY_UPDATED", null, task("Updated")));
        Long either = saveRule(owen, rule("Either", "INVOICE", "ENTITY_CREATED_OR_UPDATED", null, task("Either")));

        actAs(admin);
        Long fromCredit = invoice(acme, widget, 1, null);
        assertThat(invoiceRepository.findById(fromCredit).orElseThrow().getPaidAmount()).isPositive();
        drain();

        assertThat(tasksOf(created)).extracting(Task::getEntityId).containsExactly(fromCredit);
        assertThat(tasksOf(either)).extracting(Task::getEntityId).containsExactly(fromCredit);
        assertThat(tasksOf(updated)).isEmpty();
    }

    @Test
    void aManualRuleNeverFiresByItself() throws Exception {
        Long manual = saveRule(owen, rule("By hand", "INVOICE", "MANUAL", null, task("By hand")));
        actAs(admin);
        Customer acme = customer("Acme Ltd");
        Long id = invoice(acme, widget, 1, null);
        invoiceService.update(id, new InvoiceDtos.UpdateInvoiceRequest("chased", null, null, null, null));
        drain();
        assertThat(runsOf(manual)).isEmpty();
        assertThat(tasksOf(manual)).isEmpty();
    }

    @Test
    void aRegionMoveRunsAnInvoiceRuleOncePerMatchingInvoice() throws Exception {
        Region north = region("NORTH");
        Region south = region("SOUTH");
        for (User u : List.of(owen, admin, seller)) {
            grant(u, north, AccessLevel.APPROVE, RecordScope.ALL);
            grant(u, south, AccessLevel.APPROVE, RecordScope.ALL);
        }
        Customer acme = customer("Acme Ltd", north);
        actAs(admin);
        Long big = invoice(acme, widget, 5, null);
        Long bigger = invoice(acme, widget, 7, null);
        invoice(acme, widget, 1, null);
        drain();
        Long ruleId = saveRule(owen, rule("Big invoices", "INVOICE", "ENTITY_UPDATED",
                and(leaf("total:gte:500")), everyTime(task("Big one moved"))));
        Long paymentRule = saveRule(owen, rule("Payments", "PAYMENT", "ENTITY_UPDATED", null,
                everyTime(task("Payment moved"))));

        send(post("/api/customers/" + acme.getId() + "/move-region"), admin,
                Map.of("regionId", south.getId())).andExpect(status().isOk());
        assertThat(events(AutomationEvent.CHILDREN)).as("one for the move, whatever the customer has").hasSize(1);
        drain();

        assertThat(tasksOf(ruleId)).extracting(Task::getEntityId).containsExactlyInAnyOrder(big, bigger);
        assertThat(tasksOf(ruleId)).allSatisfy(t -> assertThat(t.getRegion().getId()).isEqualTo(south.getId()));
        assertThat(runsOf(ruleId)).singleElement().satisfies(r -> {
            assertThat(r.getTriggerType()).isEqualTo(AutomationEvent.CHILDREN);
            assertThat(r.getEntityType()).isEqualTo("CUSTOMER");
            assertThat(r.getEntityId()).isEqualTo(acme.getId());
            assertThat(r.getMatched()).isEqualTo(2);
            assertThat(r.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        });
        assertThat(runsOf(paymentRule)).singleElement()
                .satisfies(r -> assertThat(r.getMatched()).as("the customer has no payment").isZero());
        assertThat(events(AutomationEvent.CHILDREN)).as("the move's event, and one per rule concerned").hasSize(3);

        drain();
        assertThat(tasksOf(ruleId)).as("nothing is done twice").hasSize(2);
    }

    @Test
    void aCustomersChangeStillWaitingWhenTheRuleWasSwitchedOffAndOnIsNotActedOn() throws Exception {
        Region north = region("NORTH");
        Region south = region("SOUTH");
        for (User u : List.of(owen, admin, seller)) {
            grant(u, north, AccessLevel.APPROVE, RecordScope.ALL);
            grant(u, south, AccessLevel.APPROVE, RecordScope.ALL);
        }
        Customer acme = customer("Acme Ltd", north);
        actAs(admin);
        invoice(acme, widget, 5, null);
        drain();
        Long ruleId = saveRule(owen, rule("Big invoices", "INVOICE", "ENTITY_UPDATED",
                and(leaf("total:gte:500")), everyTime(task("Big one moved"))));

        send(post("/api/customers/" + acme.getId() + "/move-region"), admin,
                Map.of("regionId", south.getId())).andExpect(status().isOk());
        automationFaults.failBeforeAction(() -> new CannotAcquireLockException("customer is busy"));
        drain();
        AutomationEvent waiting = events(AutomationEvent.CHILDREN).stream()
                .filter(e -> ruleId.equals(e.getRuleId())).findFirst().orElseThrow();
        assertThat(waiting.getStatus()).as("to be tried again").isEqualTo(AutomationEvent.PENDING);
        assertThat(tasksOf(ruleId)).isEmpty();

        later(Duration.ofSeconds(1));
        send(post(RULES + "/" + ruleId + "/disable"), owen, Map.of()).andExpect(status().isOk());
        send(post(RULES + "/" + ruleId + "/enable"), owen, Map.of()).andExpect(status().isOk());
        later(Duration.ofMinutes(1));
        drain();

        assertThat(event(waiting.getId()).getStatus()).isEqualTo(AutomationEvent.DONE);
        assertThat(tasksOf(ruleId)).as("the move happened while the rule was off: backlog").isEmpty();
        assertThat(runsOf(ruleId)).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(RunStatus.SKIPPED);
            assertThat(r.getMessage()).isEqualTo(RuleRunner.NOT_ACTIVE);
        });
    }

    private void later(Duration by) {
        MovableClock.SHARED.set(MovableClock.SHARED.instant().plus(by));
    }

    @Test
    void aCustomerIsUpdatedWhenAnInvoiceTakesItsOutstandingPastAFigure() throws Exception {
        Customer acme = customer("Acme Ltd");
        Long ruleId = saveRule(owen, rule("Owes a lot", "CUSTOMER", "ENTITY_UPDATED",
                and(leaf("outstanding:gt:1000")), task("Call {{Customer.Name}}")));
        actAs(admin);
        invoice(acme, widget, 5, null);
        drain();
        assertThat(tasksOf(ruleId)).as("500 outstanding").isEmpty();

        actAs(admin);
        invoice(acme, widget, 6, null);
        drain();
        assertThat(tasksOf(ruleId)).extracting(Task::getTitle).containsExactly("Call Acme Ltd");
        assertThat(tasksOf(ruleId).get(0).getEntityId()).isEqualTo(acme.getId());
    }

    /**
     * A customer saved over the API, and the worker (no transaction open, as on its own thread) acting on the change.
     * On PostgreSQL loading the candidate rules there failed on their large objects ("Unable to access lob stream",
     * E2E AUT-R1-1) and no rule on a record's change ever ran; the probe stands in for Postgres on H2.
     */
    @Test
    void aCustomerPutIsActedOnByTheWorkerWhichReadsTheRulesInATransaction() throws Exception {
        Customer acme = customer("Acme Ltd");
        Long ruleId = saveRule(owen, rule("Renamed", "CUSTOMER", "ENTITY_UPDATED", null,
                task("Check {{Customer.Name}}")));
        drain();

        send(put("/api/customers/" + acme.getId()), admin, Map.of("name", "Acme Holdings"))
                .andExpect(status().isOk());
        AutomationEvent update = events(AutomationEvent.UPDATED).stream()
                .filter(e -> "CUSTOMER".equals(e.getEntityType()) && acme.getId().equals(e.getEntityId()))
                .reduce((a, b) -> b).orElseThrow();

        List<String> outside = LobLoadProbe.during(emf, this::drain);

        assertThat(outside).as("rules and events (with @Lob fields) loaded outside a transaction").isEmpty();
        assertThat(event(update.getId()).getStatus()).isEqualTo(AutomationEvent.DONE);
        assertThat(tasksOf(ruleId)).extracting(Task::getTitle).containsExactly("Check Acme Holdings");
        assertThat(runsOf(ruleId)).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(r.getTriggerType()).isEqualTo(AutomationEvent.UPDATED);
        });
    }
}
