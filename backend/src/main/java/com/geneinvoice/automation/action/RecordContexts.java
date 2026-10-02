package com.geneinvoice.automation.action;

import com.geneinvoice.automation.AutomationProperties;
import com.geneinvoice.automation.placeholder.PlaceholderRenderer;
import com.geneinvoice.automation.placeholder.PlaceholderValues;
import com.geneinvoice.automation.placeholder.Rendered;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.common.Money;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.email.EmailText;
import com.geneinvoice.email.RoleRef;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.invoice.InvoiceProperties;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentAllocation;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.region.Region;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Reads the record an action is about through the scoped services, as whoever is signed in (a record they cannot
 * see is not found), and gathers what its placeholders stand for (§5.10.4). Read-only: it is the work done before
 * any lock is taken (§5.10.6).
 */
@Component
@RequiredArgsConstructor
public class RecordContexts {

    static final int INVOICES_MAX = 500;

    private final CustomerService customerService;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final EmailTargets emailTargets;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceProperties invoiceProperties;
    private final ScopeResolver scopeResolver;
    private final CustomerRepository customerRepository;
    private final PaymentRepository paymentRepository;
    private final AutomationProperties automationProperties;
    private final Clock clock;

    /** An email's subject and body with their placeholders filled in, and what is worth saying about them. */
    public record EmailTexts(String subject, String body, List<String> warnings) {}

    @Transactional(readOnly = true)
    public RecordContext load(TriggerEntity type, Long id, Long ruleId, String ruleName, ZoneId zone) {
        return load(type, id, ruleId, ruleName, zone, true);
    }

    /**
     * As {@link #load(TriggerEntity, Long, Long, String, ZoneId)}, with POC placeholders shown only when
     * {@code pocVisible} holds as well: a preview in the owner's view shows them only if the caller may see them too
     * (PC-7).
     */
    @Transactional(readOnly = true)
    public RecordContext load(TriggerEntity type, Long id, Long ruleId, String ruleName, ZoneId zone,
                              boolean pocVisible) {
        return load(type, id, ruleId, ruleName, zone, pocVisible, true);
    }

    /**
     * As {@link #load(TriggerEntity, Long, Long, String, ZoneId, boolean)}, with an invoice's or a payment's customer
     * fields (other than its name) filled in only when whoever is signed in could open the customer (SC-1, RR-1) and
     * {@code customerVisible} holds as well: a preview in the owner's view shows them only if the caller could open
     * the customer too, as with POC details (PC-7). Otherwise they come out empty with a warning.
     */
    @Transactional(readOnly = true)
    public RecordContext load(TriggerEntity type, Long id, Long ruleId, String ruleName, ZoneId zone,
                              boolean pocVisible, boolean customerVisible) {
        Object entity = switch (type) {
            case CUSTOMER -> customerService.get(id);
            case INVOICE -> invoiceService.get(id);
            case PAYMENT -> paymentService.get(id);
        };
        EmailTargets.Target target = emailTargets.load(type.emailType(), id);
        Customer customer = customerOf(entity);
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        Map<String, Supplier<String>> values = new LinkedHashMap<>();
        boolean customerHidden = type != TriggerEntity.CUSTOMER && !(customerVisible && canSeeCustomer(customer));
        if (customerHidden) {
            values.put("Customer.Name", customer::getName);
        } else {
            customerValues(values, customer, target);
        }
        switch (type) {
            case CUSTOMER -> { }
            case INVOICE -> invoiceValues(values, (Invoice) entity, target, zone);
            case PAYMENT -> paymentValues(values, (Payment) entity, target, zone);
        }
        values.put("Today", today::toString);
        values.put("Rule.Name", () -> ruleName);
        PlaceholderValues placeholders = new PlaceholderValues(values, pocVisible && scopeResolver.canSeePoc(),
                customerHidden);
        return new RecordContext(type, id, entity, customer.getId(), target.regionId(), target.label(),
                target.link(), target, placeholders, ruleId, zone, today);
    }

    /**
     * An email written by hand about a record, its placeholders filled in as the caller sees the record (A4, PC-10):
     * a customer's, an invoice's or a payment's own fields and its customer's; a promise's or a dispute's customer's
     * only; {@code {{Today}}} in the default zone. Rendered here, inside the transaction the record is read in, and
     * never cut: the email's own limits are checked on the result. A text without a placeholder is returned as is.
     *
     * <p>The customer's fields are filled in only when the caller could open the customer itself (SC-1): a record
     * seen outside the customer's book (a dispute has none, a promise and a payment are the Collection POC's, an
     * invoice the Sales POC's) gives only the customer's name, which the record already shows, and the other
     * Customer placeholders come out empty with a warning.
     */
    @Transactional(readOnly = true)
    public EmailTexts renderForEmail(EmailTargets.Target target, String subject, String body) {
        ZoneId zone = ZoneId.of(automationProperties.getDefaultZone());
        Map<String, Supplier<String>> values = new LinkedHashMap<>();
        boolean customerHidden = false;
        switch (target.type()) {
            case CUSTOMER, INVOICE, PAYMENT -> {
                TriggerEntity type = TriggerEntity.valueOf(target.type().name());
                Object entity = switch (type) {
                    case CUSTOMER -> customerService.get(target.id());
                    case INVOICE -> invoiceService.get(target.id());
                    case PAYMENT -> paymentService.get(target.id());
                };
                Customer customer = customerOf(entity);
                if (type == TriggerEntity.CUSTOMER || canSeeCustomer(customer)) {
                    customerValues(values, customer, target);
                } else {
                    values.put("Customer.Name", customer::getName);
                    customerHidden = true;
                }
                if (entity instanceof Invoice i) invoiceValues(values, i, target, zone);
                if (entity instanceof Payment p) paymentValues(values, p, target, zone);
            }
            case PROMISE, DISPUTE -> {
                // The record was found as the caller already; its customer's fields are what it offers, when the
                // caller could open the customer too.
                Customer customer = target.customerId() == null ? null
                        : customerRepository.findById(target.customerId()).orElse(null);
                if (customer != null && canSeeCustomer(customer)) {
                    customerValues(values, customer, target);
                } else if (customer != null) {
                    values.put("Customer.Name", customer::getName);
                    customerHidden = true;
                }
            }
            default -> { }
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        values.put("Today", today::toString);
        PlaceholderValues placeholders = new PlaceholderValues(values, scopeResolver.canSeePoc(), customerHidden);
        List<String> warnings = new ArrayList<>();
        String renderedSubject = subject;
        if (PlaceholderRenderer.mayHavePlaceholders(subject)) {
            Rendered r = PlaceholderRenderer.render(subject, placeholders, Integer.MAX_VALUE, true, "Subject");
            renderedSubject = r.text();
            ActionSupport.collect(r, warnings);
        }
        String renderedBody = body;
        if (PlaceholderRenderer.mayHavePlaceholders(body)) {
            Rendered r = PlaceholderRenderer.render(body, placeholders, Integer.MAX_VALUE, false, "Message");
            renderedBody = r.text();
            ActionSupport.collect(r, warnings);
        }
        return new EmailTexts(renderedSubject, renderedBody, warnings);
    }

    /**
     * Whether the caller could open the customer, as {@link CustomerService#get} decides it (a customer login its
     * own, staff through the customer's book and regions). Asked of the non-transactional {@link ScopeResolver} so
     * that a refusal caught here cannot mark the email's transaction rollback-only.
     */
    private boolean canSeeCustomer(Customer c) {
        try {
            scopeResolver.requireVisible(ScopeResolver.Kind.CUSTOMER, c.getId(), Region.idOf(c.getRegion()),
                    "Customer not found");
            return true;
        } catch (NotFoundException hidden) {
            return false;
        }
    }

    /**
     * Whether the caller could open the customer of this record (a customer is its own): asked before a preview in
     * the owner's view, so that the owner's view shows the customer's fields only if the caller could open it too
     * (RR-1). False when the record is not found.
     */
    @Transactional(readOnly = true)
    public boolean callerSeesCustomerOf(TriggerEntity type, Long id) {
        if (id == null) return false;
        Customer customer = switch (type) {
            case CUSTOMER -> customerRepository.findById(id).orElse(null);
            case INVOICE -> invoiceRepository.findById(id).map(Invoice::getCustomer).orElse(null);
            case PAYMENT -> paymentRepository.findById(id).map(Payment::getCustomer).orElse(null);
        };
        return customer != null && canSeeCustomer(customer);
    }

    /** What a record is called in lists of matches: its customer's name, or its number. */
    public static String label(TriggerEntity type, Object entity) {
        return switch (type) {
            case CUSTOMER -> "Customer " + ((Customer) entity).getName();
            case INVOICE -> "Invoice " + ((Invoice) entity).getInvoiceNumber();
            case PAYMENT -> "Payment #" + ((Payment) entity).getId();
        };
    }

    public static Long idOf(TriggerEntity type, Object entity) {
        return switch (type) {
            case CUSTOMER -> ((Customer) entity).getId();
            case INVOICE -> ((Invoice) entity).getId();
            case PAYMENT -> ((Payment) entity).getId();
        };
    }

    private static Customer customerOf(Object entity) {
        if (entity instanceof Invoice i) return i.getCustomer();
        if (entity instanceof Payment p) return p.getCustomer();
        return (Customer) entity;
    }

    private void customerValues(Map<String, Supplier<String>> values, Customer c, EmailTargets.Target target) {
        values.put("Customer.Name", c::getName);
        values.put("Customer.Email", c::getEmail);
        values.put("Customer.Phone", c::getPhone);
        values.put("Customer.Address", c::getAddress);
        values.put("Customer.PaymentTerm", () -> (c.getPaymentTerm() == null
                ? invoiceProperties.defaultTerm() : c.getPaymentTerm()).label());
        values.put("Customer.CreditBalance", () -> Money.format(c.getCreditBalance()));
        values.put("Customer.Outstanding", () -> Money.format(first(
                invoiceRepository.sumOutstandingByCustomer(List.of(c.getId())))));
        values.put("Customer.OverdueAmount", () -> Money.format(first(
                invoiceRepository.sumOverdueByCustomer(List.of(c.getId()), InvoiceDates.today()))));
        values.put("Customer.CollectionPoc", () -> nameOf(target, RoleRef.customer(EmailRole.COLLECTION_POC)));
        values.put("Customer.CollectionPocEmail", () -> addressOf(target, RoleRef.customer(EmailRole.COLLECTION_POC)));
        values.put("Customer.SuccessPoc", () -> nameOf(target, RoleRef.customer(EmailRole.CUSTOMER_SUCCESS_POC)));
        values.put("Customer.SuccessPocEmail",
                () -> addressOf(target, RoleRef.customer(EmailRole.CUSTOMER_SUCCESS_POC)));
        values.put("Customer.Region", () -> Region.nameOf(c.getRegion()));
    }

    private void invoiceValues(Map<String, Supplier<String>> values, Invoice i, EmailTargets.Target target,
                               ZoneId zone) {
        LocalDate today = InvoiceDates.today();
        values.put("Invoice.Number", i::getInvoiceNumber);
        values.put("Invoice.Date", () -> date(i.getInvoiceDate(), zone));
        values.put("Invoice.DueDate", () -> i.getDueDate() == null ? null : i.getDueDate().toString());
        values.put("Invoice.PaymentTerm", () -> i.getPaymentTerm() == null ? null : i.getPaymentTerm().label());
        values.put("Invoice.Total", () -> Money.format(i.getTotal()));
        values.put("Invoice.Paid", () -> Money.format(i.getPaidAmount()));
        values.put("Invoice.Balance", () -> Money.format(i.getBalance()));
        values.put("Invoice.Status", () -> EmailText.humanize(i.getStatus()));
        values.put("Invoice.Overdue", () -> i.isOverdue(today) ? "Yes" : "No");
        values.put("Invoice.DaysOverdue", () -> String.valueOf(i.daysOverdue(today)));
        values.put("Invoice.Notes", i::getNotes);
        values.put("Invoice.SalesPoc", () -> nameOf(target, RoleRef.record(EmailRole.SALES_POC)));
        values.put("Invoice.SalesPocEmail", () -> addressOf(target, RoleRef.record(EmailRole.SALES_POC)));
    }

    private void paymentValues(Map<String, Supplier<String>> values, Payment p, EmailTargets.Target target,
                               ZoneId zone) {
        values.put("Payment.Number", () -> "#" + p.getId());
        values.put("Payment.Date", () -> date(p.getPaidAt(), zone));
        values.put("Payment.Amount", () -> Money.format(p.getAmount()));
        values.put("Payment.Unallocated", () -> Money.format(p.getCreditApplied()));
        values.put("Payment.Method", p::getMethod);
        values.put("Payment.Notes", p::getNotes);
        values.put("Payment.Status", () -> EmailText.humanize(p.getStatus()));
        values.put("Payment.Invoices", () -> EmailText.fit(p.getAllocations().stream()
                .map(PaymentAllocation::getInvoice).filter(Objects::nonNull).map(Invoice::getInvoiceNumber)
                .distinct().collect(Collectors.joining(", ")), INVOICES_MAX));
        values.put("Payment.CollectionPoc", () -> nameOf(target, RoleRef.record(EmailRole.COLLECTION_POC)));
        values.put("Payment.CollectionPocEmail", () -> addressOf(target, RoleRef.record(EmailRole.COLLECTION_POC)));
    }

    /** The person a role's work falls to: the primary holder while active, else the longest-seated (X7). */
    private static String nameOf(EmailTargets.Target target, RoleRef role) {
        return target.sender(role).map(EmailTargets.Person::name).orElse(null);
    }

    private static String addressOf(EmailTargets.Target target, RoleRef role) {
        return target.sender(role).map(EmailTargets.Person::address).orElse(null);
    }

    private static String date(Instant instant, ZoneId zone) {
        return instant == null ? null : LocalDate.ofInstant(instant, zone).toString();
    }

    private static BigDecimal first(List<Object[]> rows) {
        return rows.isEmpty() || rows.get(0)[1] == null ? BigDecimal.ZERO : (BigDecimal) rows.get(0)[1];
    }
}
