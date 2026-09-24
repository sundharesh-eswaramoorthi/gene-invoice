package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionScope;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The placeholder catalogue, the renderer and the two seams a principal-less engine addresses an
 * email through. The two places this could have become a data leak are the visibility widening on
 * EmailAddressing.offeredRole and the unscoped read behind EmailTargets.describeUnscoped; both are
 * closed by the region check inside RoleResolver.snapshot, which is what the last two tests here
 * are for (A3, A4, B1).
 */
class PlaceholderRenderTest extends EmailTestBase {

    @Autowired Placeholders placeholders;
    @Autowired RoleResolver roleResolver;
    @Autowired AutomationEmailWriter writer;
    @Autowired EmailAddressing addressing;
    @Autowired PaymentService paymentService;
    @Autowired CustomerService customerService;
    @Autowired TransactionTemplate transactions;

    @Test
    void thePickerOffersBothTheCustomerLevelAndTheRecordLevelUnderTheGroupNamesTheToFieldUses()
            throws Exception {
        Invoice inv = invoice(acme, sales);
        List<Placeholders.Slot> offered = placeholders.offered(EmailEntityType.INVOICE);

        // The group names are not a second vocabulary: they come from RoleRef.groupLabel, which is
        // what the To picker already groups its roles by, so the two lists cannot drift apart.
        JsonNode ctx = getOk("/api/emails/context", admin,
                "entityType", "INVOICE", "entityId", inv.getId().toString());
        List<String> pickerGroups = StreamSupport.stream(ctx.get("roles").spliterator(), false)
                .map(r -> r.get("groupLabel").asText()).distinct().toList();
        assertThat(offered.stream().map(Placeholders.Slot::group).distinct().toList())
                .isEqualTo(pickerGroups)
                .containsExactly("Customer level", "Invoice level");

        // Per slot and not only per list: a record-level FIELD is grouped with the record-level
        // ROLES, which is the whole of "offered at both levels" (A4).
        assertThat(groupOf(offered, "{{Customer.Name}}")).isEqualTo("Customer level");
        assertThat(groupOf(offered, "{{Customer.Link}}")).isEqualTo("Customer level");
        assertThat(groupOf(offered, "{{Role.CUSTOMER.COLLECTION_POC.Name}}")).isEqualTo("Customer level");
        assertThat(groupOf(offered, "{{Invoice.Balance}}")).isEqualTo("Invoice level");
        assertThat(groupOf(offered, "{{Invoice.DaysOverdue}}")).isEqualTo("Invoice level");
        assertThat(groupOf(offered, "{{Role.RECORD.SALES_POC.Name}}")).isEqualTo("Invoice level");

        assertThat(keys(offered)).contains(
                "{{Customer.Name}}", "{{Customer.CreditBalance}}", "{{Customer.PaymentTerm}}",
                "{{Customer.Link}}", "{{Invoice.Number}}", "{{Invoice.Balance}}",
                "{{Invoice.DaysOverdue}}", "{{Invoice.Link}}",
                "{{Role.CUSTOMER.CUSTOMER_SUCCESS_POC.Name}}", "{{Role.CUSTOMER.COLLECTION_POC.Email}}",
                "{{Role.RECORD.SALES_POC.Name}}", "{{Role.RECORD.SALES_POC.Email}}");
        // A ColumnDef with no reader is NOT offered rather than rendered wrong.
        assertThat(keys(offered)).doesNotContain("{{Invoice.Overdue}}", "{{Invoice.CustomerId}}",
                "{{Invoice.ApprovalPending}}", "{{Invoice.RegionId}}", "{{Invoice.SalesPocUserId}}");

        // On a PAYMENT, COLLECTION_POC is offered at BOTH levels and the namespace is what tells
        // them apart — exactly as the To picker offers two of it.
        List<Placeholders.Slot> onPayment = placeholders.offered(EmailEntityType.PAYMENT);
        assertThat(keys(onPayment)).contains("{{Role.CUSTOMER.COLLECTION_POC.Name}}",
                "{{Role.RECORD.COLLECTION_POC.Name}}", "{{Payment.Amount}}", "{{Payment.Link}}");
        assertThat(groupOf(onPayment, "{{Payment.Amount}}")).isEqualTo("Payment level");
        assertThat(groupOf(onPayment, "{{Role.RECORD.COLLECTION_POC.Name}}")).isEqualTo("Payment level");
        assertThat(labelOf(onPayment, "{{Role.CUSTOMER.COLLECTION_POC.Name}}"))
                .isEqualTo("Collection POC (customer) - Name");
        assertThat(labelOf(onPayment, "{{Role.RECORD.COLLECTION_POC.Name}}"))
                .isEqualTo("Collection POC (this payment) - Name");

        // A CUSTOMER rule offers the customer level once, not twice under two namespaces.
        assertThat(keys(placeholders.offered(EmailEntityType.CUSTOMER))).doesNotHaveDuplicates()
                .contains("{{Customer.Name}}").doesNotContain("{{Invoice.Number}}");

        // Labels and types come from the table schema, so the picker reads like the table does.
        assertThat(labelOf(offered, "{{Invoice.Balance}}")).isEqualTo("Balance");
        assertThat(exampleOf(offered, "{{Invoice.Balance}}")).isEqualTo("₹1,00,000.00");
        assertThat(exampleOf(offered, "{{Invoice.Status}}")).isEqualTo("Partially paid");
    }

    @Test
    void aMisspeltPlaceholderIsRefusedWhenTheRuleIsSavedAndNamesItself() {
        assertThatThrownBy(() ->
                placeholders.validate("Balance: {{Invoice.Ballance}}", EmailEntityType.INVOICE))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Unknown placeholder {{Invoice.Ballance}} for invoices");

        assertThatCode(() -> placeholders.validate(
                "Hello {{Customer.Name}}, {{Invoice.Balance}} is due", EmailEntityType.INVOICE))
                .doesNotThrowAnyException();

        // The right key on the wrong subject is just as wrong, and says which subject it is.
        assertThatThrownBy(() ->
                placeholders.validate("{{Invoice.Balance}}", EmailEntityType.CUSTOMER))
                .hasMessage("Unknown placeholder {{Invoice.Balance}} for customers");
        assertThatThrownBy(() ->
                placeholders.validate("{{Role.RECORD.SALES_POC.Name}}", EmailEntityType.PAYMENT))
                .hasMessage("Unknown placeholder {{Role.RECORD.SALES_POC.Name}} for payments");
    }

    @Test
    void aPlaceholderWithNobodyBehindItRendersEmptyAndIsReportedAsUnresolved() {
        Invoice inv = invoice(acme, sales);

        Placeholders.Rendered rendered = placeholders.render(
                "Hello {{Role.CUSTOMER.CUSTOMER_SUCCESS_POC.Name}}, about {{Invoice.Number}}."
                        + " Notes: {{Invoice.Notes}}",
                contextFor(EmailEntityType.INVOICE, inv.getId()));

        // Empty, not a literal {{...}} in a customer-facing line, and never an exception.
        assertThat(rendered.text())
                .isEqualTo("Hello , about " + inv.getInvoiceNumber() + ". Notes: ");
        assertThat(rendered.unresolved()).containsExactly(
                "{{Role.CUSTOMER.CUSTOMER_SUCCESS_POC.Name}}", "{{Invoice.Notes}}");

        // The role that IS held on the record resolves in the same pass.
        assertThat(placeholders.render("{{Role.RECORD.SALES_POC.Name}}",
                contextFor(EmailEntityType.INVOICE, inv.getId())).unresolved()).isEmpty();
    }

    @Test
    void aRoleHeldByThreePeopleRendersThePrimaryAndFallsThroughWhenThePrimaryIsDeactivated() {
        List<User> others = threeCollectionSeats(acme);
        User cora = others.get(0);
        Invoice inv = invoice(acme, sales);

        assertThat(placeholders.render("{{Role.CUSTOMER.COLLECTION_POC.Name}}",
                contextFor(EmailEntityType.INVOICE, inv.getId())).text())
                .isEqualTo(collections.getFullName());

        // Not a second definition of "primary": the seat book returns its holders primary-first
        // and filtered to active users, so deactivating the primary falls through to the next
        // active holder rather than leaving the slot empty (A4, L5).
        deactivate(collections);
        actAs(admin);
        assertThat(placeholders.render("{{Role.CUSTOMER.COLLECTION_POC.Name}}",
                contextFor(EmailEntityType.INVOICE, inv.getId())).text())
                .isEqualTo(cora.getFullName());

        EmailTargets.Target target = roleResolver.snapshot(EmailEntityType.INVOICE, inv.getId());
        RoleRef role = RoleRef.customer(EmailRole.COLLECTION_POC);
        assertThat(roleResolver.holders(target, role)).extracting(EmailTargets.Person::name)
                .containsExactly(cora.getFullName());
        assertThat(roleResolver.primary(target, role)).get()
                .extracting(EmailTargets.Person::name).isEqualTo(cora.getFullName());
    }

    @Test
    void moneyDatesAndStatusesAreFormattedTheWayEveryOtherEmailFormatsThem() {
        acme.setPaymentTerm(PaymentTerm.NET_30);
        customerRepository.save(acme);
        Invoice inv = invoice(acme, sales);
        Invoice stored = invoiceRepository.findById(inv.getId()).orElseThrow();
        stored.setPaidAmount(new BigDecimal("200.00"));
        stored.setStatus(InvoiceStatus.PARTIALLY_PAID);
        invoiceRepository.save(stored);
        LocalDate asOf = stored.getDueDate().plusDays(12);

        RenderContext ctx = contextFor(EmailEntityType.INVOICE, inv.getId(), asOf);
        Placeholders.Rendered rendered = placeholders.render(
                "{{Invoice.Total}}|{{Invoice.Paid}}|{{Invoice.Balance}}|{{Invoice.Date}}"
                        + "|{{Invoice.Status}}|{{Invoice.DaysOverdue}}|{{Customer.CreditBalance}}"
                        + "|{{Customer.PaymentTerm}}|{{Invoice.Link}}", ctx);

        assertThat(rendered.text()).isEqualTo(String.join("|",
                "₹1,200.00", "₹200.00", "₹1,000.00",
                LocalDate.ofInstant(stored.getInvoiceDate(), ZoneOffset.UTC).toString(),
                "Partially paid", "12", "₹0.00", "Net 30", "/invoices/" + inv.getId()));
        assertThat(rendered.unresolved()).isEmpty();

        // The run's own as-of date and nothing else: a replayed run renders the same number.
        assertThat(placeholders.render("{{Invoice.DaysOverdue}}",
                contextFor(EmailEntityType.INVOICE, inv.getId(), asOf.plusDays(5))).text())
                .isEqualTo("17");

        // The payment namespace reads its own record, formatted by the same table.
        Payment payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("150.50"), "NEFT", null, null, collections.getId(), null));
        Placeholders.Rendered onPayment = placeholders.render(
                "{{Payment.Amount}}|{{Payment.Method}}|{{Payment.Status}}|{{Payment.PaidAt}}|{{Payment.Link}}",
                contextFor(EmailEntityType.PAYMENT, payment.getId()));
        assertThat(onPayment.text()).isEqualTo(String.join("|", "₹150.50", "NEFT", "Active",
                LocalDate.ofInstant(payment.getPaidAt(), ZoneOffset.UTC).toString(),
                "/payments/" + payment.getId()));
    }

    @Test
    void aValueThatLooksLikeAPlaceholderOrARegexGroupIsInsertedLiterally() {
        Customer tricky = customer("$1 \\ {{Customer.Email}} Ltd", "ap@tricky.test");

        Placeholders.Rendered rendered = placeholders.render(
                "To: {{Customer.Name}} <{{Customer.Email}}>",
                contextFor(EmailEntityType.CUSTOMER, tricky.getId()));

        // One left-to-right pass with quoteReplacement: $1 is not a group, the backslash is not an
        // escape, and the {{...}} a customer typed into their own name is not re-scanned.
        assertThat(rendered.text())
                .isEqualTo("To: $1 \\ {{Customer.Email}} Ltd <ap@tricky.test>");
        assertThat(rendered.unresolved()).isEmpty();
    }

    @Test
    void aLongValueIsTrimmedToTheColumnAfterRenderingRatherThanFailingTheSend() {
        Customer big = customer("L".repeat(150), "ap@big.test");
        EmailTargets.Target target = roleResolver.snapshot(EmailEntityType.CUSTOMER, big.getId());

        String subject = placeholders.render("{{Customer.Name}} ".repeat(5),
                contextFor(EmailEntityType.CUSTOMER, big.getId())).text();
        // A forty-character template renders to well over the column's limit, and the renderer
        // itself does NOT trim: the destination column does.
        assertThat(subject.length()).isGreaterThan(FieldLimits.EMAIL_SUBJECT);

        AutomationEmailWriter.Drafted drafted = inTransaction(() -> writer.draft(target,
                EmailToken.user(admin.getId()), List.of(EmailToken.customer()),
                subject, "Hello", admin.getId()));

        assertThat(drafted.problem()).isNull();
        Email saved = emailRepository.findById(drafted.emailId()).orElseThrow();
        assertThat(saved.getSubject()).hasSize(FieldLimits.EMAIL_SUBJECT).endsWith("…");
        assertThat(saved.getSentByUserId()).isEqualTo(admin.getId());
    }

    @Test
    void aRuleEmailIsAddressedWithoutASignedInUserAndStillRefusesASenderItIsNotOffered() {
        Invoice inv = invoice(acme, sales);
        Long home = defaultRegion().getId();
        // Nobody is signed in from here on: this is the consumer thread's posture exactly.
        SecurityContextHolder.clearContext();

        // The role rule and its 400 text are the To field's own, produced by the one method that
        // owns them — which is why rule validation can speak in already-asserted sentences.
        assertThatThrownBy(() -> addressing.planForRule(EmailEntityType.INVOICE,
                EmailToken.role(RoleRef.customer(EmailRole.SALES_POC)), List.of(EmailToken.customer())))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Sales POC (customer) is not a role on invoices");
        assertThatThrownBy(() -> addressing.planForRule(EmailEntityType.INVOICE, null,
                List.of(EmailToken.customer())))
                .hasMessage("A rule's email needs a sender");
        assertThatThrownBy(() -> addressing.planForRule(EmailEntityType.INVOICE,
                EmailToken.customer(), List.of(EmailToken.customer())))
                .hasMessage("The sender must be a person or a role");

        EmailTargets.Target target = RegionScope.asRegions(Set.of(home),
                RegionScope.SystemReason.AUTOMATION_ACT,
                () -> roleResolver.snapshot(EmailEntityType.INVOICE, inv.getId()));

        AutomationEmailWriter.Drafted drafted = inTransaction(() -> writer.draft(target,
                EmailToken.role(RoleRef.record(EmailRole.SALES_POC)),
                List.of(EmailToken.customer(),
                        EmailToken.role(RoleRef.customer(EmailRole.CUSTOMER_SUCCESS_POC))),
                "Invoice " + inv.getInvoiceNumber(), "Hello", sales.getId()));

        Email saved = emailRepository.findById(drafted.emailId()).orElseThrow();
        assertThat(saved.getFromUserId()).isEqualTo(sales.getId());
        assertThat(saved.getFromRole()).isEqualTo(EmailRole.SALES_POC);
        // The rule's author is who is answerable for the send, and is never null.
        assertThat(saved.getSentByUserId()).isEqualTo(sales.getId());
        // An unheld role is recorded and the send carries on, which is the contract the email
        // layer already has.
        assertThat(saved.getUnresolved()).isEqualTo("ROLE:CUSTOMER:CUSTOMER_SUCCESS_POC");
        assertThat(drafted.unresolved()).containsExactly("ROLE:CUSTOMER:CUSTOMER_SUCCESS_POC");
        // Both of the customer's addresses: the account's own and its active login's.
        assertThat(recipientsOf(saved.getId())).extracting(EmailRecipient::getAddress)
                .containsExactlyInAnyOrder("ap@acme.test", "acme.login@test.local");
        assertThat(recipientsOf(saved.getId())).allSatisfy(r ->
                assertThat(r.getDeliveryStatus()).isEqualTo(RecipientDeliveryStatus.QUEUED));

        // Nothing resolves at all: nothing is written and the caller is told why, rather than a
        // half-addressed email being left behind for somebody to find.
        AutomationEmailWriter.Drafted nobody = inTransaction(() -> writer.draft(target,
                EmailToken.role(RoleRef.customer(EmailRole.CUSTOMER_SUCCESS_POC)),
                List.of(EmailToken.customer()), "Anything", "Hello", sales.getId()));
        assertThat(nobody.emailId()).isNull();
        assertThat(nobody.problem()).isEqualTo("Nobody holds Customer Success POC (customer) on "
                + target.label() + ", so it cannot be the sender");
        assertThat(emailRepository.count()).isEqualTo(1);
    }

    @Test
    void aRoleSnapshotOfARecordOutsideTheRunsBranchesIsRefused() {
        Region west = region("WEST");
        Customer westAccount = opened("West Ltd", "west@west.test", "west.login", west);
        Invoice homeInvoice = invoice(acme, sales);
        Long home = defaultRegion().getId();
        SecurityContextHolder.clearContext();

        // describeUnscoped reads any record by id with no check of its own; the region is the
        // whole access decision off-request, and asRegions is a BOUND and not a bypass.
        assertThatThrownBy(() -> inRegions(Set.of(home),
                () -> roleResolver.snapshot(EmailEntityType.CUSTOMER, westAccount.getId())))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("That region is outside this run's reach");
        // An invoice is asked about its ACCOUNT's branch, which is the only region column there is.
        assertThatThrownBy(() -> inRegions(Set.of(west.getId()),
                () -> roleResolver.snapshot(EmailEntityType.INVOICE, homeInvoice.getId())))
                .isInstanceOf(AccessDeniedException.class);
        // An empty reach denies rather than widens: a rule whose author has no grants acts nowhere.
        assertThatThrownBy(() -> inRegions(Set.of(),
                () -> roleResolver.snapshot(EmailEntityType.CUSTOMER, westAccount.getId())))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(inRegions(Set.of(west.getId()),
                () -> roleResolver.snapshot(EmailEntityType.CUSTOMER, westAccount.getId())).label())
                .isEqualTo("Customer West Ltd");

        // On a request path it is the caller's own grants, with nothing else to remember: the same
        // door, a different bound. Cashier works in the default branch only.
        actAs(userRepository.findByUsername("cashier").orElseThrow());
        assertThatThrownBy(() -> roleResolver.snapshot(EmailEntityType.CUSTOMER, westAccount.getId()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("You have no view access in that region");
        assertThat(roleResolver.snapshot(EmailEntityType.CUSTOMER, acme.getId()).label())
                .isEqualTo("Customer Acme Ltd");

        // A record that is not there is 404 and not a refusal, so the two answers agree with
        // describeUnscoped's own.
        assertThatThrownBy(() -> roleResolver.snapshot(EmailEntityType.INVOICE, 9_999_999L))
                .isInstanceOf(com.geneinvoice.common.NotFoundException.class);
        // The three subjects a rule can have, and nothing else: the one door does not open wider.
        assertThatThrownBy(() -> roleResolver.snapshot(EmailEntityType.PRODUCT, widget.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("A rule's subject must be a customer, an invoice or a payment");
    }

    @Test
    void renamingAColumnAPlaceholderReadsStopsTheApplicationAtStartup() {
        ColumnDef[] withoutBalance = TableSchemas.INVOICES.columns().stream()
                .filter(c -> !"balance".equals(c.name())).toArray(ColumnDef[]::new);
        TableSchema renamed = TableSchema.of("invoices", Invoice.class, "invoiceDate,desc", withoutBalance);

        assertThatThrownBy(() -> Placeholders.requireColumns("invoices", renamed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("balance")
                .hasMessageContaining("invoices");

        // The other direction, and the reason this context started at all: every reader that names
        // a ColumnDef names one the three live schemas still have.
        assertThatCode(() -> {
            Placeholders.requireColumns("customers", TableSchemas.byEntity("customers"));
            Placeholders.requireColumns("invoices", TableSchemas.byEntity("invoices"));
            Placeholders.requireColumns("payments", TableSchemas.byEntity("payments"));
        }).doesNotThrowAnyException();
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private List<String> keys(List<Placeholders.Slot> slots) {
        return slots.stream().map(Placeholders.Slot::key).toList();
    }

    private String labelOf(List<Placeholders.Slot> slots, String key) {
        return slots.stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow().label();
    }

    private String groupOf(List<Placeholders.Slot> slots, String key) {
        return slots.stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow().group();
    }

    private String exampleOf(List<Placeholders.Slot> slots, String key) {
        return slots.stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow().example();
    }

    private RenderContext contextFor(EmailEntityType type, Long id) {
        return contextFor(type, id, LocalDate.now(ZoneOffset.UTC));
    }

    private RenderContext contextFor(EmailEntityType type, Long id, LocalDate asOf) {
        EmailTargets.Target target = roleResolver.snapshot(type, id);
        Object entity = switch (type) {
            case CUSTOMER -> customerRepository.findById(id).orElseThrow();
            case INVOICE -> invoiceRepository.findById(id).orElseThrow();
            case PAYMENT -> paymentRepository.findById(id).orElseThrow();
            default -> throw new IllegalStateException("Not a rule subject: " + type);
        };
        Customer account = customerRepository.findById(target.customerId()).orElseThrow();
        return RenderContext.of(type, entity, account, target, asOf);
    }

    private <T> T inRegions(Set<Long> regionIds, Supplier<T> body) {
        return RegionScope.asRegions(regionIds, RegionScope.SystemReason.AUTOMATION_ACT, body);
    }

    /** draft is MANDATORY on purpose: the email row commits with the step's settle (A5). */
    private <T> T inTransaction(Supplier<T> body) {
        return transactions.execute(status -> body.get());
    }

    private Customer opened(String name, String customerEmail, String username, Region where) {
        actAs(admin);
        return customerService.create(new CustomerDtos.CustomerCreateRequest(
                name, null, customerEmail, null, null, where.getId(), username, "Password1!"));
    }
}
