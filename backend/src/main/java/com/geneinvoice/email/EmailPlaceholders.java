package com.geneinvoice.email;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.Emails;
import com.geneinvoice.common.query.Aggregates;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.poc.ScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.geneinvoice.email.EmailRole.COLLECTION_POC;
import static com.geneinvoice.email.EmailRole.CUSTOMER_SUCCESS_POC;
import static com.geneinvoice.email.EmailRole.SALES_POC;

/**
 * Placeholders a sender can write into a subject or a body — {@code {{Customer.Name}}},
 * {@code {{Invoice.Balance}}} — and what they come to on one record (M1). They are offered at both
 * levels: on an invoice the customer's fields <em>and</em> the invoice's, on a payment the
 * customer's and the payment's, on a customer the customer's alone. Every other kind of record
 * that belongs to a customer is offered the customer's fields, which is the same rule read the
 * other way round: a level is offered when the record has one.
 *
 * <p>Two rules make this safe to type into a live email:
 *
 * <ul>
 *   <li>A placeholder nobody recognises is left exactly as it was typed (M5). It is never blanked
 *       and never an error, so a misspelling shows up in the preview as itself — which is where the
 *       writer will see it — instead of quietly deleting a line of their email.
 *   <li>Nothing that is filled in is read again. A customer literally called "{{Invoice.Total}}"
 *       fills in as that name and stays that name, because the pass runs once over the original
 *       text (M5).
 * </ul>
 *
 * <p>Money and dates are written by {@link EmailText}, which is where the app's rupee grouping and
 * {@code yyyy-MM-dd} live, so a placeholder reads exactly as the same figure reads in a suggested
 * email or a notification. Days past due are counted against {@link InvoiceDates#today()}, the day
 * the app itself decides overdue by (AC-A9), so an email never calls an invoice late that the
 * invoice list still calls current.
 *
 * <p>The POC placeholders name a member of staff, which is POC identity and is gated like POC
 * identity everywhere else: a caller without {@code POC_VIEW}, and every customer login whatever
 * privileges its role carries, is not offered them at all (AC-A8, AC-A6). Not offered and not
 * filled in are the same decision, because the offer and the send are one code path (M4): a key
 * this writer's record does not offer is left exactly as they typed it, as any other unknown key
 * is (M5), so a customer who types {@code {{Customer.CollectionPoc.Name}}} reads the braces back in
 * their own preview and sends the braces if they insist — never a name, and never a line of their
 * email silently blanked. Masking them with an empty sample instead was the other way to go and is
 * deliberately not taken: an empty sample already means "this record has nobody in that seat" (L5),
 * so an empty mask would tell the reader something false about the record.
 */
@Component
@RequiredArgsConstructor
public class EmailPlaceholders {

    /**
     * {@code {{ anything but braces }}}. The inside is matched loosely on purpose: a key with
     * spaces, a wrong case or a word that means nothing is still matched, so {@link #fill} can put
     * it back as it was rather than leave half of it behind.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]*)}}");
    private static final String OPEN = "{{";
    private static final String CLOSE = "}}";

    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final ScopeResolver scopeResolver;
    private final CurrentUser currentUser;

    // ---- what is offered -------------------------------------------------------------

    /**
     * Every placeholder this record offers, grouped by level, each with the value it has on
     * <em>this</em> record. The sample and the send are one code path on purpose (M4): what the
     * compose form shows a writer is the same string {@link #fill} will put in the email, so a
     * blank sample is a warning that the record has nothing there, not a rendering quirk.
     */
    @Transactional(readOnly = true)
    public List<EmailDtos.PlaceholderGroup> offered(EmailTargets.Target target, ZoneId zone) {
        boolean namePoc = mayNamePoc();
        List<EmailDtos.PlaceholderGroup> groups = new ArrayList<>();
        customer(target).ifPresent(c ->
                groups.add(new EmailDtos.PlaceholderGroup("Customer", customerFields(c, target, namePoc))));
        switch (target.type()) {
            case INVOICE -> invoiceRepository.findById(target.id()).ifPresent(i ->
                    groups.add(new EmailDtos.PlaceholderGroup("Invoice", invoiceFields(i, target, zone, namePoc))));
            case PAYMENT -> paymentRepository.findById(target.id()).ifPresent(p ->
                    groups.add(new EmailDtos.PlaceholderGroup("Payment", paymentFields(p, target, zone, namePoc))));
            default -> {
                // Promises, disputes, products, users and roles have no fields of their own to offer.
            }
        }
        return groups;
    }

    /**
     * Whether this caller may be told who sits in a POC seat. The same question the invoice list,
     * the payment list and a promise's assignees ask, asked here for the same reason: a placeholder
     * that filled in a name would hand it to anybody who could type the key (AC-A8, AC-A6).
     *
     * <p>Nobody logged in is a background thread — an automation rule's send (R1) — which is
     * internal by definition and has no reader to keep a name from; asking {@link
     * ScopeResolver#canSeePoc()} there would throw rather than answer.
     */
    private boolean mayNamePoc() {
        return currentUser.idOrNull() == null || scopeResolver.canSeePoc();
    }

    /**
     * The same values, keyed as {@link #fill} looks them up: the name inside the braces, lower
     * cased, because a key is read without regard to case (M1).
     *
     * <p>Read straight off {@link #offered}, so what a writer is not offered is also what their
     * send does not fill in — which is how a POC's name stays out of an email written by somebody
     * who may not see one, whatever they type into the body.
     */
    @Transactional(readOnly = true)
    public Map<String, String> values(EmailTargets.Target target, ZoneId zone) {
        Map<String, String> values = new LinkedHashMap<>();
        for (EmailDtos.PlaceholderGroup group : offered(target, zone)) {
            for (EmailDtos.PlaceholderDto placeholder : group.placeholders()) {
                values.put(nameOf(placeholder.key()), placeholder.sample());
            }
        }
        return values;
    }

    // ---- filling in ------------------------------------------------------------------

    /**
     * The text with every placeholder this record knows filled in, and every one it does not left
     * exactly as typed (M5). One pass over the original, so a value that looks like a placeholder
     * is a value and not another round of substitution.
     */
    public String fill(String text, Map<String, String> values) {
        if (text == null || text.isEmpty() || values.isEmpty() || !text.contains(OPEN)) return text;
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            String value = values.get(matcher.group(1).trim().toLowerCase(Locale.ROOT));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    // ---- the fields themselves -------------------------------------------------------

    private List<EmailDtos.PlaceholderDto> customerFields(Customer c, EmailTargets.Target target,
                                                          boolean namePoc) {
        List<EmailDtos.PlaceholderDto> fields = new ArrayList<>(List.of(
                placeholder("Customer.Name", "Customer name", c.getName()),
                placeholder("Customer.Email", "Customer email", Emails.normalize(c.getEmail())),
                placeholder("Customer.Phone", "Customer phone", c.getPhone()),
                placeholder("Customer.Outstanding", "Outstanding balance",
                        EmailText.money(outstanding(c.getId()))),
                // Null terms mean "whatever the system default is", which is a deployment's setting
                // and not a fact about this customer, so nothing is put in its place (D1).
                placeholder("Customer.PaymentTerm", "Payment terms",
                        c.getPaymentTerm() == null ? "" : c.getPaymentTerm().label())));
        if (namePoc) {
            fields.add(placeholder("Customer.SalesPoc.Name", "Sales POC (customer)",
                    primary(target, RoleRef.customer(SALES_POC))));
            fields.add(placeholder("Customer.CollectionPoc.Name", "Collection POC (customer)",
                    primary(target, RoleRef.customer(COLLECTION_POC))));
            fields.add(placeholder("Customer.CustomerSuccessPoc.Name", "Customer Success POC (customer)",
                    primary(target, RoleRef.customer(CUSTOMER_SUCCESS_POC))));
        }
        return List.copyOf(fields);
    }

    private List<EmailDtos.PlaceholderDto> invoiceFields(Invoice i, EmailTargets.Target target, ZoneId zone,
                                                         boolean namePoc) {
        LocalDate today = InvoiceDates.today();
        List<EmailDtos.PlaceholderDto> fields = new ArrayList<>(List.of(
                placeholder("Invoice.Number", "Invoice number", i.getInvoiceNumber()),
                placeholder("Invoice.Total", "Invoice total", EmailText.money(i.getTotal())),
                placeholder("Invoice.Balance", "Balance due", EmailText.money(i.getBalance())),
                placeholder("Invoice.DueDate", "Due date", EmailText.date(i.getDueDate())),
                placeholder("Invoice.IssueDate", "Issue date", EmailText.date(i.getInvoiceDate(), zone)),
                placeholder("Invoice.Status", "Invoice status", EmailText.humanize(i.getStatus())),
                // Zero on an invoice that is not overdue, which is what the invoice list shows too;
                // a paid or cancelled invoice is never past due whatever its date says (D3).
                placeholder("Invoice.DaysPastDue", "Days past due", String.valueOf(i.daysOverdue(today)))));
        if (namePoc) {
            fields.add(placeholder("Invoice.SalesPoc.Name", "Sales POC (this invoice)",
                    primary(target, RoleRef.record(SALES_POC))));
        }
        return List.copyOf(fields);
    }

    private List<EmailDtos.PlaceholderDto> paymentFields(Payment p, EmailTargets.Target target, ZoneId zone,
                                                         boolean namePoc) {
        List<EmailDtos.PlaceholderDto> fields = new ArrayList<>(List.of(
                // A payment carries no reference of its own, so its reference is what the app calls
                // it everywhere else — the same words as the record's label and its Email tab.
                placeholder("Payment.Reference", "Payment reference", "Payment #" + p.getId()),
                placeholder("Payment.Id", "Payment id", String.valueOf(p.getId())),
                placeholder("Payment.Amount", "Amount received", EmailText.money(p.getAmount())),
                placeholder("Payment.PaidAt", "Payment date", EmailText.date(p.getPaidAt(), zone)),
                placeholder("Payment.Method", "Payment method", p.getMethod()),
                placeholder("Payment.Status", "Payment status", EmailText.humanize(p.getStatus()))));
        if (namePoc) {
            fields.add(placeholder("Payment.CollectionPoc.Name", "Collection POC (this payment)",
                    primary(target, RoleRef.record(COLLECTION_POC))));
        }
        return List.copyOf(fields);
    }

    /**
     * Who a POC placeholder names. A role at customer level can be held by several people, and an
     * email is written to one of them, so it fills in the primary: {@link EmailTargets.Target#sender}
     * is the first holder, and the first holder is the primary seat (L2, L5). At record level there
     * is only ever the one person the record itself names (L3).
     *
     * <p>Empty when nobody holds it — including {@code Customer.SalesPoc.Name} on every record, for
     * now: the Sales POC is assigned per invoice and the app seats none on a customer (CP-01). It is
     * offered because a writer looking for it should find it rather than invent a spelling, and the
     * sample shows them at once that it has nobody to name. Only ever reached for a writer who may
     * see POC identity, so an empty sample always means an empty seat and never a mask.
     */
    private static String primary(EmailTargets.Target target, RoleRef role) {
        return target.sender(role).map(EmailTargets.Person::name).orElse("");
    }

    /** What the customer owes across every invoice that is not cancelled — the customer list's own figure. */
    private BigDecimal outstanding(Long customerId) {
        return invoiceRepository.sumOutstandingByCustomer(List.of(customerId)).stream()
                .findFirst().map(row -> Aggregates.asMoney(row[1])).orElse(BigDecimal.ZERO);
    }

    /**
     * The customer the record belongs to. Read by id rather than through the record's own
     * association, so this works on a background thread with no open session behind it
     * ({@code open-in-view} is off).
     */
    private Optional<Customer> customer(EmailTargets.Target target) {
        return target.customerId() == null ? Optional.empty() : customerRepository.findById(target.customerId());
    }

    private static EmailDtos.PlaceholderDto placeholder(String name, String label, String value) {
        return new EmailDtos.PlaceholderDto(OPEN + name + CLOSE, label, value == null ? "" : value);
    }

    /** "{{Customer.Name}}" as {@link #fill} keys it: {@code customer.name}. */
    private static String nameOf(String key) {
        return key.substring(OPEN.length(), key.length() - CLOSE.length()).trim().toLowerCase(Locale.ROOT);
    }
}
