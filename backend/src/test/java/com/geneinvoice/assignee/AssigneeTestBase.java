package com.geneinvoice.assignee;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.email.RoleRef;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.task.TaskDtos;
import com.geneinvoice.task.TaskService;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * The cast every assignee test needs: a customer with a POC book, the three kinds of internal
 * person who can sit in one, a record of each kind that carries assignees, and a customer login to
 * check what it is not told.
 *
 * <p>Only Cara is seated in the book here. The second Collection POC is added by the tests that are
 * about somebody joining or leaving a seat, because that is the moment they are measuring (A2).
 */
abstract class AssigneeTestBase extends IntegrationTestBase {

    @Autowired protected TaskService taskService;
    @Autowired protected PaymentPromiseService promiseService;
    @Autowired protected InvoiceService invoiceService;
    @Autowired protected PaymentService paymentService;
    @Autowired protected PocService pocService;

    protected User admin;
    /** The customer's primary Collection POC, and the Collection POC on every record below. */
    protected User cara;
    /** Assignable as a Collection POC but seated nowhere until a test seats her. */
    protected User carl;
    protected User suki;
    /** The Sales POC on {@link #acmeInvoice}; a Sales seat exists only on a record (L2). */
    protected User sam;
    /** Holds no seat anywhere and is nobody's assignee — the control for "my tasks". */
    protected User nina;
    protected User acmeLogin;

    protected Customer acme;
    protected Product widget;
    protected Invoice acmeInvoice;
    protected Payment acmePayment;

    @BeforeEach
    void setUpAssigneeFixtures() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        cara = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        carl = user("carl.collections", DataSeeder.ROLE_COLLECTION_POC);
        suki = user("suki.success", DataSeeder.ROLE_SUCCESS_POC);
        sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        nina = user("nina.nobody", "VIEWER");

        acme = customer("Acme Ltd", "billing@acme.test");
        widget = product("Widget", "100.00");
        acmeLogin = customerUser("acme.login", acme.getId());

        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, cara.getId(), true);

        acmeInvoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                acme.getId(), null, null, sam.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("100.00")))));
        acmePayment = paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("10.00"), "Cash", null, null, cara.getId(), null));
    }

    // ---- tokens ----------------------------------------------------------------

    protected static EmailToken byName(User u) {
        return EmailToken.user(u.getId());
    }

    /** The seat as the customer's book holds it — everyone active in it (L2). */
    protected static EmailToken customerSeat(EmailRole role) {
        return EmailToken.role(RoleRef.customer(role));
    }

    /** The seat as the record itself holds it — the one person it names (L3). */
    protected static EmailToken recordSeat(EmailRole role) {
        return EmailToken.role(RoleRef.record(role));
    }

    /** A role with no level at all, which is read at the kind's default level (L7). */
    protected static EmailToken unlevelled(EmailRole role) {
        return new EmailToken("ROLE", null, role.name(), null);
    }

    // ---- records that carry assignees ------------------------------------------

    protected TaskDtos.TaskDto raise(String entityType, Long entityId, String title,
                                     EmailToken... assignees) {
        return taskService.toDto(taskService.create(new TaskDtos.CreateTaskRequest(
                entityType, entityId, title, null, null, null, Arrays.asList(assignees))));
    }

    /** The task as it reads now — which is the only way a role's people are ever answered (A2). */
    protected TaskDtos.TaskDto reread(Long taskId) {
        return taskService.toDto(taskService.get(taskId));
    }

    protected PromiseDtos.PromiseDto promise(EmailToken... assignees) {
        return promiseService.create(new PromiseDtos.CreatePromiseRequest(
                acme.getId(), new BigDecimal("500.00"), LocalDate.now().plusDays(7),
                cara.getId(), "promise notes", null, Arrays.asList(assignees)));
    }

    // ---- reading assignees back ------------------------------------------------

    protected static List<Long> reaches(AssigneeDtos.AssigneeDto a) {
        return a.people().stream().map(AssigneeDtos.PersonDto::userId).toList();
    }

    protected static List<String> labels(List<AssigneeDtos.AssigneeDto> rows) {
        return rows.stream().map(AssigneeDtos.AssigneeDto::label).toList();
    }

    /** The one assignee on a record that has exactly one, so a test reads what it means to read. */
    protected static AssigneeDtos.AssigneeDto only(List<AssigneeDtos.AssigneeDto> rows) {
        if (rows.size() != 1) {
            throw new AssertionError("expected exactly one assignee but found " + rows.size() + ": " + rows);
        }
        return rows.get(0);
    }

    protected JsonNode read(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
