package com.geneinvoice.asof;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.customer.CustomerView;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.dispute.DisputeView;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.invoice.InvoiceView;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.payment.PaymentStatus;
import com.geneinvoice.payment.PaymentView;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.promise.PromiseView;
import com.geneinvoice.task.Task;
import com.geneinvoice.task.TaskDtos;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.task.TaskService;
import com.geneinvoice.task.TaskStatus;
import com.geneinvoice.task.TaskView;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The six *View interfaces: one DTO factory per entity, serving a live row and — from B3-MIRRORS
 * onwards — an interval-versioned mirror row, so the live list and the as-of list cannot come to
 * disagree about what "overdue", "balance" or "still owed" means (B3).
 *
 * <p>WHAT THIS UNIT DOES AND DOES NOT CHANGE, SAID PLAINLY. It is a compiler-driven refactor: the
 * derived bodies moved verbatim out of Invoice and PaymentPromise onto the interfaces, the DTO
 * factories widened their first parameter, and every wire field keeps its exact value. So the five
 * tests the plan named ({@code theBalanceOnTheInterface...} through
 * {@code everyRowDtoStillCarriesItsRegionIdAndRegionName}) pin answers that must NOT move and pass
 * both before and after — they are regression pins, not proofs. The three that FOLLOW them are the
 * load-bearing ones and are mutation-checked: {@code everyRowDtoCanBeBuiltFromSomethingThatIsNot
 * TheEntity} fails to build the moment a factory narrows back to the entity, and
 * {@code theDerivedMoneyAndDateLogicHasExactlyOneDefinition} goes red the moment somebody puts a
 * second copy of getBalance/isOverdue/daysOverdue/getRemainingAmount back on an entity, which is
 * exactly the drift the interfaces exist to prevent (B3).
 */
class ViewInterfaceTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired CustomerService customerService;
    @Autowired DisputeService disputeService;
    @Autowired TaskService taskService;

    User admin;
    User sam;
    User colin;
    Customer acme;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        colin = user("colin.collect", DataSeeder.ROLE_COLLECTION_POC);
        actAs(admin);
        widget = product("Widget", "100.00");
        acme = customer("Acme Ltd");
    }

    // ---- the answers that must not move ------------------------------------------------------

    /**
     * The body moved out of Invoice onto InvoiceView unchanged, so the arithmetic must be
     * unchanged too — total minus paid, signed, with no flooring at zero, because an over-paid
     * invoice reporting a zero balance would hide the credit the customer is owed (B3).
     */
    @Test
    void theBalanceOnTheInterfaceIsTheBalanceTheEntityUsedToCompute() {
        Invoice inv = invoice();
        assertThat(inv.getBalance()).isEqualByComparingTo("100.00");

        inv.setPaidAmount(new BigDecimal("30.00"));
        assertThat(inv.getBalance())
                .as("total minus paid, exactly as the entity's own body computed it")
                .isEqualByComparingTo("70.00");
        assertThat(inv.getBalance()).isEqualByComparingTo(inv.getTotal().subtract(inv.getPaidAmount()));

        // Over-paid: the old body did not floor, and neither does the default.
        inv.setPaidAmount(new BigDecimal("130.00"));
        assertThat(inv.getBalance()).isEqualByComparingTo("-30.00");

        // And the value the wire carries is the same one.
        inv.setPaidAmount(new BigDecimal("30.00"));
        assertThat(InvoiceDtos.InvoiceSummary.from(inv, true).balance())
                .isEqualByComparingTo("70.00");
    }

    /**
     * The one derived field a reader acts on. isBefore, not isEqual-or-before: an invoice due
     * TODAY is not late yet, and a day of slippage here is a dunning email a customer did not
     * deserve (D3, B3).
     */
    @Test
    void overdueAndDaysOverdueAreUnchangedForEveryDueDateBoundary() {
        LocalDate today = InvoiceDates.today();

        Invoice yesterday = dueOn(today.minusDays(1));
        Invoice due = dueOn(today);
        Invoice tomorrow = dueOn(today.plusDays(1));
        // invoices.due_date is NOT NULL from InvoiceSchemaUpgrade onwards, so this row cannot be
        // persisted — but the column is mapped nullable, isOverdue's first arm exists for exactly
        // it, and a half-migrated row or a mirror row can still present one (D-01, B3).
        Invoice never = Invoice.builder().total(new BigDecimal("100.00"))
                .paidAmount(BigDecimal.ZERO).status(InvoiceStatus.UNPAID).build();

        assertThat(yesterday.isOverdue(today)).isTrue();
        assertThat(yesterday.daysOverdue(today)).isEqualTo(1);

        assertThat(due.isOverdue(today)).as("due today is not late today").isFalse();
        assertThat(due.daysOverdue(today)).isZero();

        assertThat(tomorrow.isOverdue(today)).isFalse();
        assertThat(tomorrow.daysOverdue(today)).isZero();

        assertThat(never.isOverdue(today)).as("no due date is never late").isFalse();
        assertThat(never.daysOverdue(today)).isZero();

        // A paid-off invoice is not late however old it is: the balance arm of the same expression.
        yesterday.setPaidAmount(yesterday.getTotal());
        assertThat(yesterday.isOverdue(today)).isFalse();
        assertThat(yesterday.daysOverdue(today)).isZero();

        // Thirty days late reads as thirty, not twenty-nine or thirty-one.
        assertThat(dueOn(today.minusDays(30)).daysOverdue(today)).isEqualTo(30);
    }

    /** Cancelled work is not chased. The status arm of the same expression, kept whole (D3, B3). */
    @Test
    void aCancelledInvoiceIsNeverOverdueHoweverOldItIs() {
        LocalDate today = InvoiceDates.today();
        Invoice ancient = dueOn(today.minusDays(400));
        assertThat(ancient.isOverdue(today)).isTrue();

        Invoice cancelled = invoiceService.cancel(ancient.getId());
        assertThat(cancelled.getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(cancelled.isOverdue(today)).isFalse();
        assertThat(cancelled.daysOverdue(today)).isZero();
    }

    /**
     * The body moved out of PaymentPromise onto PromiseView unchanged. Three edges, all of which a
     * naive amount-minus-fulfilled would get wrong: a settled promise owes nothing whatever its
     * numbers say, a cancelled one owes nothing at all, and an over-fulfilled one owes zero rather
     * than a negative amount the customer would read as credit (B3).
     */
    @Test
    void thePromiseRemainingAmountIsUnchangedForKeptCancelledAndOverfulfilled() {
        assertThat(promise(PromiseStatus.OPEN, "100.00", "40.00").getRemainingAmount())
                .isEqualByComparingTo("60.00");
        assertThat(promise(PromiseStatus.PARTIALLY_KEPT, "100.00", "40.00").getRemainingAmount())
                .isEqualByComparingTo("60.00");
        assertThat(promise(PromiseStatus.BROKEN, "100.00", "0.00").getRemainingAmount())
                .isEqualByComparingTo("100.00");

        assertThat(promise(PromiseStatus.KEPT, "100.00", "40.00").getRemainingAmount())
                .as("a promise marked kept owes nothing, whatever the fulfilled column says")
                .isEqualByComparingTo("0.00");
        assertThat(promise(PromiseStatus.CANCELLED, "100.00", "0.00").getRemainingAmount())
                .isEqualByComparingTo("0.00");
        assertThat(promise(PromiseStatus.OPEN, "100.00", "130.00").getRemainingAmount())
                .as("over-fulfilled floors at zero rather than reading as credit")
                .isEqualByComparingTo("0.00");
        assertThat(promise(PromiseStatus.OPEN, "100.00", null).getRemainingAmount())
                .as("a null fulfilled column counts as nothing paid, not as a null pointer")
                .isEqualByComparingTo("100.00");
    }

    /**
     * B1's two trailing slots are now filled from the row's own view rather than from a static
     * helper that took a Customer. Same value, on every list that has the pair (B1, B3).
     */
    @Test
    void everyRowDtoStillCarriesItsRegionIdAndRegionName() throws Exception {
        Long region = defaultRegion().getId();
        String name = defaultRegion().getName();
        Invoice inv = invoice();
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("10.00"), "CASH", null, List.of(), colin.getId(), null));
        promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(),
                new BigDecimal("50.00"), InvoiceDates.today().plusDays(7), colin.getId(), null,
                null));

        for (String path : List.of("/api/invoices", "/api/customers", "/api/payments",
                "/api/promises")) {
            mockMvc.perform(get(path).with(as(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].regionId").value(region))
                    .andExpect(jsonPath("$.content[0].regionName").value(name));
        }

        // The single-record read builds the fat DTO through the other factory, so it is asked too.
        mockMvc.perform(get("/api/invoices/" + inv.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionId").value(region))
                .andExpect(jsonPath("$.regionName").value(name));
    }

    // ---- the guards ---------------------------------------------------------------------------

    /**
     * THE LOAD-BEARING ONE, AND THE WHOLE POINT OF THE UNIT. Every row DTO is built here from an
     * object that is NOT the entity, has no association to walk and was never near the database —
     * which is precisely the shape an interval-versioned mirror row has. It carries values no live
     * row in this test holds ("Acme As It Was", region 4242), so the assertions prove the factory
     * READ THE VIEW rather than walking {@code getCustomer()} behind the caller's back.
     *
     * <p>This is what goes red — as a build failure, loudly, at the point of the change — if any
     * of these six factories is narrowed back to its entity. Mutation-checked by reverting each
     * widening in turn (B3).
     */
    @Test
    void everyRowDtoCanBeBuiltFromSomethingThatIsNotTheEntity() {
        actAs(admin);

        InvoiceDtos.InvoiceSummary invoiceRow = InvoiceDtos.InvoiceSummary.from(FROZEN_INVOICE, true);
        assertThat(invoiceRow.customerId()).isEqualTo(77L);
        assertThat(invoiceRow.customerName()).isEqualTo("Acme As It Was");
        assertThat(invoiceRow.regionId()).isEqualTo(4242L);
        assertThat(invoiceRow.regionName()).isEqualTo("North As It Was");
        assertThat(invoiceRow.balance()).isEqualByComparingTo("70.00");
        assertThat(invoiceRow.pocMissing()).isTrue();

        // The fat detail DTO takes the same view, with the two things a mirror cannot answer for
        // itself — the line items and the live row version — handed in rather than invented.
        InvoiceDtos.InvoiceDto detail = InvoiceDtos.InvoiceDto.from(FROZEN_INVOICE,
                List.of(new InvoiceDtos.InvoiceLineDto(1L, 2L, "Widget as it was", 1,
                        new BigDecimal("100.00"), new BigDecimal("100.00"))),
                null, true, Boolean.TRUE);
        assertThat(detail.customerName()).isEqualTo("Acme As It Was");
        assertThat(detail.items()).singleElement()
                .extracting(InvoiceDtos.InvoiceLineDto::productName).isEqualTo("Widget as it was");
        assertThat(detail.version()).as("a past snapshot has no live row version to lock").isNull();
        assertThat(detail.approvalPending()).isTrue();

        PaymentDtos.PaymentDto paymentRow = PaymentDtos.PaymentDto.from(FROZEN_PAYMENT,
                List.of(), new BigDecimal("5.00"), true, null);
        assertThat(paymentRow.customerName()).isEqualTo("Acme As It Was");
        assertThat(paymentRow.regionId()).isEqualTo(4242L);
        assertThat(paymentRow.customerCreditBalance()).isEqualByComparingTo("5.00");

        PromiseDtos.PromiseDto promiseRow =
                promiseService.toDto(FROZEN_PROMISE, List.of(), List.of(), null);
        assertThat(promiseRow.customerName()).isEqualTo("Acme As It Was");
        assertThat(promiseRow.regionId()).isEqualTo(4242L);
        assertThat(promiseRow.remainingAmount()).isEqualByComparingTo("60.00");

        CustomerDtos.CustomerDto customerRow = customerService.toDto(FROZEN_CUSTOMER);
        assertThat(customerRow.name()).isEqualTo("Acme As It Was");
        assertThat(customerRow.regionId()).isEqualTo(4242L);
        assertThat(customerRow.regionName()).isEqualTo("North As It Was");

        DisputeDtos.DisputeDto disputeRow = disputeService.toDto(FROZEN_DISPUTE);
        assertThat(disputeRow.customerId()).isEqualTo(77L);
        assertThat(disputeRow.reason()).isEqualTo("Charged twice, as it was");

        TaskDtos.TaskDto taskRow = taskService.toDto(FROZEN_TASK);
        assertThat(taskRow.customerId()).isEqualTo(77L);
        assertThat(taskRow.title()).isEqualTo("Chase Acme, as it was");
        assertThat(taskRow.entityLabel()).isEqualTo("Invoice INV-THEN-1");
    }

    /**
     * THE SECOND LOAD-BEARING ONE. The derived logic has to have exactly ONE definition, or a live
     * list and an as-of list can drift: an entity that keeps its own {@code getBalance} overrides
     * the interface default for live rows only, and the mirror silently goes on using the other
     * one. Asked of the class files rather than of a value, because two copies that AGREE today
     * pass every value test there is and still drift the day one of them is edited (B3).
     */
    @Test
    void theDerivedMoneyAndDateLogicHasExactlyOneDefinition() {
        assertThat(InvoiceView.class.isAssignableFrom(Invoice.class)).isTrue();
        assertThat(CustomerView.class.isAssignableFrom(Customer.class)).isTrue();
        assertThat(PaymentView.class.isAssignableFrom(Payment.class)).isTrue();
        assertThat(PromiseView.class.isAssignableFrom(PaymentPromise.class)).isTrue();
        assertThat(DisputeView.class.isAssignableFrom(Dispute.class)).isTrue();
        assertThat(TaskView.class.isAssignableFrom(Task.class)).isTrue();

        declaredAsDefault(InvoiceView.class, "getBalance");
        declaredAsDefault(InvoiceView.class, "isOverdue", LocalDate.class);
        declaredAsDefault(InvoiceView.class, "daysOverdue", LocalDate.class);
        declaredAsDefault(PromiseView.class, "getRemainingAmount");

        notDeclaredOn(Invoice.class, "getBalance");
        notDeclaredOn(Invoice.class, "isOverdue", LocalDate.class);
        notDeclaredOn(Invoice.class, "daysOverdue", LocalDate.class);
        notDeclaredOn(PaymentPromise.class, "getRemainingAmount");

        // And the entity really does answer through the inherited default.
        Invoice inv = invoice();
        assertThat(((InvoiceView) inv).getBalance()).isEqualByComparingTo(inv.getBalance());
    }

    /**
     * THE THIRD, AND THE ONE THAT CAUGHT A REAL DEFECT. A read-only duplicate column mapping is
     * populated when Hibernate LOADS the row and never after an insert, so on the entity instance
     * a create path just saved — which is the object every POST answers with — the flat Long is
     * null while the association is right. A row DTO that read the field would therefore have
     * answered {@code "customerId": null} to every create, update and cancel in the application.
     * The entity answers these from the association for exactly that reason; this test is the
     * proof, and it goes red the moment somebody "simplifies" the delegates away and lets Lombok's
     * generated getter over the mapped field take over again (B3).
     */
    @Test
    void theRecordAPostAnswersWithNamesItsAccountRatherThanNull() throws Exception {
        mockMvc.perform(post("/api/invoices").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", acme.getId(),
                                "salesPocUserId", sam.getId(),
                                "items", List.of(Map.of("productId", widget.getId(),
                                        "quantity", 1, "unitPrice", "100.00"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(acme.getId()))
                .andExpect(jsonPath("$.customerName").value("Acme Ltd"))
                .andExpect(jsonPath("$.regionId").value(defaultRegion().getId()));

        mockMvc.perform(post("/api/payments").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", acme.getId(), "amount", "10.00",
                                "method", "CASH", "collectionPocUserId", colin.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(acme.getId()))
                .andExpect(jsonPath("$.customerName").value("Acme Ltd"));

        mockMvc.perform(post("/api/promises").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", acme.getId(), "amount", "50.00",
                                "promisedDate", InvoiceDates.today().plusDays(7).toString(),
                                "collectionPocUserId", colin.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(acme.getId()))
                .andExpect(jsonPath("$.customerName").value("Acme Ltd"));

        // The same instance, asked directly: this is the value the DTO above is built from.
        // MockMvc's request post-processor clears the context afterwards, so the actor is set
        // again before a service is called in-process.
        actAs(admin);
        Invoice fresh = invoice();
        assertThat(fresh.getCustomerId()).isEqualTo(acme.getId());
        assertThat(fresh.getSalesPocUserId()).isEqualTo(sam.getId());
    }

    /**
     * THE FOURTH. The three accessors a live entity has to hand-write — the account's name and
     * B1's two region slots — must agree with the associations they stand for, and must tolerate a
     * row that has neither. The null arm is the one that matters: the static helpers these
     * replaced were null-tolerant because a DTO built from a half-migrated row must not throw, and
     * that tolerance had to move with the code (B1, B3).
     */
    @Test
    void everyEntityDelegateAgreesWithTheAssociationItStandsFor() {
        Invoice inv = invoice();
        assertThat(inv.getCustomerName()).isEqualTo(inv.getCustomer().getName()).isEqualTo("Acme Ltd");
        assertThat(inv.getCustomerId()).isEqualTo(inv.getCustomer().getId());
        assertThat(inv.getRegionId()).isEqualTo(inv.getCustomer().getRegion().getId());
        assertThat(inv.getRegionName()).isEqualTo(inv.getCustomer().getRegion().getName());

        Payment pay = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("10.00"), "CASH", null, List.of(), colin.getId(), null));
        assertThat(pay.getCustomerName()).isEqualTo("Acme Ltd");
        assertThat(pay.getRegionId()).isEqualTo(defaultRegion().getId());
        assertThat(pay.getRegionName()).isEqualTo(defaultRegion().getName());

        assertThat(acme.getRegionId()).isEqualTo(defaultRegion().getId());
        assertThat(acme.getRegionName()).isEqualTo(defaultRegion().getName());

        // Taking the person off is answered from the association, not from the duplicate column,
        // which still holds the id they used to have until the row is read again (B3).
        inv.setSalesPoc(null);
        assertThat(inv.getSalesPocUserId()).isNull();

        // A row with no account and an account with no branch: null, never a null pointer.
        Invoice unplaced = Invoice.builder().total(BigDecimal.ONE).paidAmount(BigDecimal.ZERO).build();
        assertThat(unplaced.getCustomerName()).isNull();
        assertThat(unplaced.getRegionId()).isNull();
        assertThat(unplaced.getRegionName()).isNull();

        Customer homeless = Customer.builder().name("Homeless Ltd").build();
        assertThat(homeless.getRegionId()).isNull();
        assertThat(homeless.getRegionName()).isNull();

        PaymentPromise loose = PaymentPromise.builder().amount(BigDecimal.TEN).build();
        assertThat(loose.getCustomerName()).isNull();
        assertThat(loose.getRegionId()).isNull();
        assertThat(loose.getRegionName()).isNull();
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private static void declaredAsDefault(Class<?> view, String name, Class<?>... args) {
        Method m = method(view, name, args);
        assertThat(m).as(view.getSimpleName() + "." + name + " is declared here").isNotNull();
        assertThat(m.isDefault()).as(view.getSimpleName() + "." + name + " is a default body").isTrue();
    }

    private static void notDeclaredOn(Class<?> entity, String name, Class<?>... args) {
        assertThat(method(entity, name, args))
                .as(entity.getSimpleName() + "." + name
                        + " must not be a second copy of the interface default")
                .isNull();
    }

    private static Method method(Class<?> type, String name, Class<?>... args) {
        try {
            return type.getDeclaredMethod(name, args);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private Invoice invoice() {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null,
                null, null, sam.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("100.00")))));
    }

    /**
     * An invoice due on an exact day. The due date is rewritten rather than requested, so the
     * boundary under test is the one isOverdue draws and not the one the term arithmetic drew
     * on the way in (D3, B3).
     */
    private Invoice dueOn(LocalDate dueDate) {
        Invoice inv = invoice();
        inv.setDueDate(dueDate);
        return invoiceRepository.save(inv);
    }

    /**
     * Built rather than persisted: getRemainingAmount is pure arithmetic over four columns, and
     * the three edges it exists for (KEPT, CANCELLED, over-fulfilled) are states the write path
     * only reaches through the sweeper. The builder is safe here because nothing is saved — a
     * promise that IS saved must set the customer association, never the read-only Long (B3).
     */
    private static PaymentPromise promise(PromiseStatus status, String amount, String fulfilled) {
        return PaymentPromise.builder()
                .amount(new BigDecimal(amount))
                .fulfilledAmount(fulfilled == null ? null : new BigDecimal(fulfilled))
                .status(status)
                .build();
    }

    // ---- rows that are not entities -------------------------------------------------------

    private static final Instant THEN = Instant.parse("2026-01-31T00:00:00Z");
    private static final LocalDate THEN_DUE = LocalDate.parse("2026-03-02");

    /**
     * Hand-written stand-ins for the mirror rows B3-MIRRORS will add: flat foreign keys, no
     * associations, no persistence context, and an account name and region frozen as they were on
     * the date asked rather than as they are now. Nothing here may reach the database, which is
     * the property that makes the test meaningful (B3).
     */
    private static final InvoiceView FROZEN_INVOICE = new InvoiceView() {
        @Override public Long getId() { return 1001L; }
        @Override public String getInvoiceNumber() { return "INV-THEN-1"; }
        @Override public Long getCustomerId() { return 77L; }
        @Override public String getCustomerName() { return "Acme As It Was"; }
        @Override public Instant getInvoiceDate() { return THEN; }
        @Override public LocalDate getDueDate() { return THEN_DUE; }
        @Override public PaymentTerm getPaymentTerm() { return PaymentTerm.NET_30; }
        @Override public BigDecimal getTotal() { return new BigDecimal("100.00"); }
        @Override public BigDecimal getPaidAmount() { return new BigDecimal("30.00"); }
        @Override public InvoiceStatus getStatus() { return InvoiceStatus.PARTIALLY_PAID; }
        @Override public String getNotes() { return "as it was"; }
        @Override public User getSalesPoc() { return null; }
        @Override public Instant getCreatedAt() { return THEN; }
        @Override public Long getRegionId() { return 4242L; }
        @Override public String getRegionName() { return "North As It Was"; }
    };

    private static final PaymentView FROZEN_PAYMENT = new PaymentView() {
        @Override public Long getId() { return 2002L; }
        @Override public Long getCustomerId() { return 77L; }
        @Override public String getCustomerName() { return "Acme As It Was"; }
        @Override public BigDecimal getAmount() { return new BigDecimal("30.00"); }
        @Override public BigDecimal getCreditApplied() { return BigDecimal.ZERO; }
        @Override public String getMethod() { return "CASH"; }
        @Override public String getNotes() { return "as it was"; }
        @Override public Instant getPaidAt() { return THEN; }
        @Override public PaymentStatus getStatus() { return PaymentStatus.ACTIVE; }
        @Override public User getCollectionPoc() { return null; }
        @Override public Long getRegionId() { return 4242L; }
        @Override public String getRegionName() { return "North As It Was"; }
    };

    private static final PromiseView FROZEN_PROMISE = new PromiseView() {
        @Override public Long getId() { return 3003L; }
        @Override public Long getCustomerId() { return 77L; }
        @Override public String getCustomerName() { return "Acme As It Was"; }
        @Override public BigDecimal getAmount() { return new BigDecimal("100.00"); }
        @Override public BigDecimal getFulfilledAmount() { return new BigDecimal("40.00"); }
        @Override public LocalDate getPromisedDate() { return THEN_DUE; }
        @Override public PromiseStatus getStatus() { return PromiseStatus.OPEN; }
        @Override public boolean isStatusOverridden() { return false; }
        @Override public String getOverrideReason() { return null; }
        @Override public Long getOverriddenByUserId() { return null; }
        @Override public Instant getOverriddenAt() { return null; }
        @Override public User getCollectionPoc() { return null; }
        @Override public String getNotes() { return "as it was"; }
        @Override public Long getCreatedByUserId() { return null; }
        @Override public Instant getCreatedAt() { return THEN; }
        @Override public Instant getUpdatedAt() { return THEN; }
        @Override public Long getRegionId() { return 4242L; }
        @Override public String getRegionName() { return "North As It Was"; }
    };

    private static final CustomerView FROZEN_CUSTOMER = new CustomerView() {
        @Override public Long getId() { return 77L; }
        @Override public String getName() { return "Acme As It Was"; }
        @Override public String getPhone() { return "0100"; }
        @Override public String getEmail() { return "then@acme.example"; }
        @Override public String getAddress() { return "1 Then Street"; }
        @Override public BigDecimal getCreditBalance() { return new BigDecimal("5.00"); }
        @Override public PaymentTerm getPaymentTerm() { return PaymentTerm.NET_30; }
        @Override public Instant getCreatedAt() { return THEN; }
        @Override public Long getRegionId() { return 4242L; }
        @Override public String getRegionName() { return "North As It Was"; }
    };

    private static final DisputeView FROZEN_DISPUTE = new DisputeView() {
        @Override public Long getId() { return 4004L; }
        @Override public Long getCustomerId() { return 77L; }
        @Override public Long getOpenedByUserId() { return 5L; }
        @Override public DisputeTargetType getTargetType() { return DisputeTargetType.INVOICE; }
        @Override public Long getTargetId() { return 1001L; }
        @Override public String getReason() { return "Charged twice, as it was"; }
        @Override public String getProposedChangeJson() { return null; }
        @Override public DisputeStatus getStatus() { return DisputeStatus.PENDING; }
        @Override public String getAdminNotes() { return null; }
        @Override public Long getResolvedByUserId() { return null; }
        @Override public Instant getResolvedAt() { return null; }
        @Override public Instant getCreatedAt() { return THEN; }
        @Override public Instant getUpdatedAt() { return THEN; }
    };

    private static final TaskView FROZEN_TASK = new TaskView() {
        @Override public Long getId() { return 5005L; }
        @Override public TaskEntityType getEntityType() { return TaskEntityType.INVOICE; }
        @Override public Long getEntityId() { return 1001L; }
        @Override public String getEntityLabel() { return "Invoice INV-THEN-1"; }
        @Override public Long getCustomerId() { return 77L; }
        @Override public String getTitle() { return "Chase Acme, as it was"; }
        @Override public String getNotes() { return "as it was"; }
        @Override public LocalDate getDueDate() { return THEN_DUE; }
        @Override public TaskStatus getStatus() { return TaskStatus.OPEN; }
        @Override public Long getCreatedByUserId() { return 5L; }
        @Override public Long getCreatedByRuleId() { return null; }
        @Override public Long getCreatedByStepId() { return null; }
        @Override public Long getCompletedByUserId() { return null; }
        @Override public Instant getCompletedAt() { return null; }
        @Override public Instant getCreatedAt() { return THEN; }
        @Override public Instant getUpdatedAt() { return THEN; }
    };
}
