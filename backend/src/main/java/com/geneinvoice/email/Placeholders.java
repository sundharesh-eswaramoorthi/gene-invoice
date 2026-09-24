package com.geneinvoice.email;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.common.query.ColumnType;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The placeholder catalogue: what a rule author may write in a subject, a body or a task title,
 * and what it reads on one record (A4).
 *
 * <p>It lives inside com.geneinvoice.email rather than in com.geneinvoice.automation because
 * every value it formats goes through EmailText, which is package-private and stays that way.
 *
 * <p>THE FIRST SEGMENT IS THE LEVEL, which is what "offered for both levels" means:
 * {@code {{Customer.*}}} on every rule, {@code {{Invoice.*}}} / {@code {{Payment.*}}} on a rule of
 * that subject, and role slots generated from EmailTargets.rolesOffered so the offer list can
 * never disagree with the To picker. On a PAYMENT, COLLECTION_POC is offered at BOTH levels and
 * the namespace is exactly what tells them apart.
 *
 * <p>Two different answers to "nothing is behind this", on purpose: a key that is NOT IN THE
 * CATALOGUE is refused when the rule is SAVED and can never reach the renderer; a key that is in
 * the catalogue but EMPTY ON THIS RECORD renders as the empty string and is reported in
 * {@link Rendered#unresolved()}. That is the contract the email layer already has with an unheld
 * role — record it and carry on — and a literal {{...}} in a customer-facing subject would be
 * worse than a gap (A4).
 */
@Component
@Slf4j
public class Placeholders {

    /** One offered placeholder, as the picker shows it. {@code key} is the token to insert. */
    public record Slot(String key, String label, String group, ColumnType type, String example) {}

    /** A rendered template plus the tokens that had nothing behind them on this record (A4). */
    public record Rendered(String text, List<String> unresolved) {}

    /**
     * Two dotted segments, and up to four so a role slot fits: a role name carries an underscore
     * ({@code COLLECTION_POC}) and a role slot names a level, a role and a part. A token with one
     * segment is not a placeholder and is left alone (A4).
     */
    private static final Pattern PATTERN = Pattern.compile(
            "\\{\\{\\s*([A-Za-z]+)\\.([A-Za-z0-9_]+)(?:\\.([A-Za-z0-9_]+))?(?:\\.([A-Za-z0-9_]+))?\\s*}}");

    /**
     * The three kinds of record a rule can have as its subject. A catalogue for anything else
     * would need readers and a region resolution of its own, so it is refused rather than
     * answered with an empty list (A4, A3).
     */
    private static final Set<EmailEntityType> SUBJECTS =
            EnumSet.of(EmailEntityType.CUSTOMER, EmailEntityType.INVOICE, EmailEntityType.PAYMENT);

    /**
     * One reader. {@code column} names the ColumnDef its label and ColumnType come from, and is
     * null for the handful of slots no column can answer — see DERIVED below.
     */
    private record Reader(String key, String column, ColumnType type, String label, Object example,
                          Function<RenderContext, Object> read) {}

    /** A built slot and the function behind it, so offered() and render() cannot drift apart. */
    private record Entry(Slot slot, Function<RenderContext, Object> read) {}

    // VALUES are hand-written readers and not ColumnDef.path, because a path is a Criteria
    // PathResolver and cannot read a loaded entity at all — and because the two fields the PRD
    // names by name are exactly the two that would break reflection anyway: `balance` is
    // cb.diff(total, paidAmount) and `overdue`'s path is a dummy root.get("id") (A4).
    //
    // A ColumnDef with no reader is simply NOT OFFERED (overdue, outstanding, customerId,
    // salesPocUserId, approvalPending and the region pair never appear) rather than rendered
    // wrong. A reader naming a column the schema does not have FAILS THE BOOT: see requireColumns.
    //
    // DERIVED slots name no column because no ColumnDef answers them: Link is EmailTargets.link,
    // DaysOverdue is the Java method the overdue filter has no value for, and PaymentTerm is a
    // real column on the entity that the customers table schema does not publish. Each is a
    // hand-written getter with a test of its own, and the boot check cannot cover them.

    private static final List<Reader> CUSTOMER_FIELDS = List.of(
            column("Name", "name", "Acme Ltd", onCustomer(Customer::getName)),
            column("Email", "email", "ap@acme.test", onCustomer(Customer::getEmail)),
            column("Phone", "phone", "+91 80 4000 1234", onCustomer(Customer::getPhone)),
            column("Address", "address", "12 Residency Road, Bengaluru", onCustomer(Customer::getAddress)),
            column("CreditBalance", "creditBalance", new BigDecimal("1200.00"),
                    onCustomer(Customer::getCreditBalance)),
            derived("PaymentTerm", "Payment term", ColumnType.ENUM, PaymentTerm.NET_30,
                    onCustomer(Customer::getPaymentTerm)),
            derived("Link", "Link", ColumnType.TEXT, "/customers/42",
                    onCustomer(c -> EmailTargets.link(EmailEntityType.CUSTOMER, c.getId()))));

    private static final List<Reader> INVOICE_FIELDS = List.of(
            column("Number", "invoiceNumber", "INV-0042", onInvoice(Invoice::getInvoiceNumber)),
            column("Date", "invoiceDate", LocalDate.of(2026, 9, 1), onInvoice(Invoice::getInvoiceDate)),
            column("DueDate", "dueDate", LocalDate.of(2026, 10, 1), onInvoice(Invoice::getDueDate)),
            column("Total", "total", new BigDecimal("123456.78"), onInvoice(Invoice::getTotal)),
            column("Paid", "paidAmount", new BigDecimal("23456.78"), onInvoice(Invoice::getPaidAmount)),
            // The Java getter, because the ColumnDef for balance is cb.diff(total, paidAmount).
            column("Balance", "balance", new BigDecimal("100000.00"), onInvoice(Invoice::getBalance)),
            column("Status", "status", InvoiceStatus.PARTIALLY_PAID, onInvoice(Invoice::getStatus)),
            // Counted from the RUN's own as-of date, never from the wall clock, so a replayed run
            // renders the same number it rendered the first time (A4, A5).
            derived("DaysOverdue", "Days overdue", ColumnType.NUMBER, 12,
                    ctx -> ctx.entity() instanceof Invoice i && ctx.asOf() != null
                            ? i.daysOverdue(ctx.asOf()) : null),
            column("Notes", "notes", "Please quote the invoice number", onInvoice(Invoice::getNotes)),
            derived("Link", "Link", ColumnType.TEXT, "/invoices/42",
                    onInvoice(i -> EmailTargets.link(EmailEntityType.INVOICE, i.getId()))));

    private static final List<Reader> PAYMENT_FIELDS = List.of(
            column("Id", "id", 42L, onPayment(Payment::getId)),
            column("Amount", "amount", new BigDecimal("23456.78"), onPayment(Payment::getAmount)),
            column("CreditApplied", "creditApplied", new BigDecimal("0.00"),
                    onPayment(Payment::getCreditApplied)),
            column("Method", "method", "NEFT", onPayment(Payment::getMethod)),
            column("PaidAt", "paidAt", LocalDate.of(2026, 9, 30), onPayment(Payment::getPaidAt)),
            column("Status", "status", PaymentStatus.ACTIVE, onPayment(Payment::getStatus)),
            column("Notes", "notes", "Part payment", onPayment(Payment::getNotes)),
            derived("Link", "Link", ColumnType.TEXT, "/payments/42",
                    onPayment(p -> EmailTargets.link(EmailEntityType.PAYMENT, p.getId()))));

    private final EmailTargets targets;
    private final Map<EmailEntityType, Map<String, Entry>> catalogues =
            new EnumMap<>(EmailEntityType.class);

    /**
     * BOOT CHECK (A4). The catalogue is built once, here, and building it asserts that every
     * reader naming a ColumnDef names one the schema still has — so renaming a column breaks the
     * boot loudly instead of producing a blank in a customer-facing email at 3 a.m.
     */
    public Placeholders(EmailTargets targets) {
        this.targets = targets;
        for (EmailEntityType type : SUBJECTS) {
            catalogues.put(type, build(type));
        }
    }

    /** What the picker offers, at both levels, in the To picker's own group order (A4). */
    public List<Slot> offered(EmailEntityType type) {
        return catalogue(type).values().stream().map(Entry::slot).toList();
    }

    /**
     * Rule-save time: a key that is not in the catalogue is a 400 that names itself, so a typo is
     * caught by the author who made it and can never reach a customer (A4).
     */
    public void validate(String template, EmailEntityType type) {
        Map<String, Entry> catalogue = catalogue(type);
        if (template == null || template.isEmpty()) return;
        Matcher matcher = PATTERN.matcher(template);
        while (matcher.find()) {
            String key = key(matcher);
            if (!catalogue.containsKey(key)) {
                throw new BadRequestException(
                        "Unknown placeholder {{" + key + "}} for " + type.noun() + "s");
            }
        }
    }

    /**
     * Run time, and it NEVER throws: a rule that cannot render a slot leaves it empty and says so,
     * rather than failing a send that is otherwise perfectly good (A4).
     *
     * <p>ONE left-to-right pass. Every value is inserted with Matcher.quoteReplacement and is
     * never re-scanned, so a customer whose name is {@code $1}, {@code \} or literally
     * {@code {{Customer.Name}}} cannot reach back into the template: no recursion, no fixpoint.
     *
     * <p>EmailText.fit is deliberately NOT applied here. A forty-character template can render to
     * forty thousand characters, so the trim belongs at the destination column — see
     * AutomationEmailWriter — and never to the template, whose own length is checked at save (A4).
     */
    public Rendered render(String template, RenderContext ctx) {
        if (template == null || template.isEmpty()) {
            return new Rendered(template == null ? "" : template, List.of());
        }
        Map<String, Entry> catalogue = catalogues.getOrDefault(ctx.type(), Map.of());
        Matcher matcher = PATTERN.matcher(template);
        StringBuilder out = new StringBuilder();
        Set<String> unresolved = new LinkedHashSet<>();
        while (matcher.find()) {
            String key = key(matcher);
            String value = resolve(key, catalogue, ctx);
            if (value.isEmpty()) unresolved.add("{{" + key + "}}");
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return new Rendered(out.toString(), List.copyOf(unresolved));
    }

    /**
     * The boot assertion, reachable on its own so a test can show what a renamed column does
     * without renaming one for everybody else (A4).
     */
    static void requireColumns(String entity, TableSchema schema) {
        Set<String> named = fieldsOf(entity).stream().map(Reader::column).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (schema.byName().keySet().containsAll(named)) return;
        Set<String> missing = new LinkedHashSet<>(named);
        missing.removeAll(schema.byName().keySet());
        throw new IllegalStateException("Placeholder readers name columns the " + entity
                + " table schema does not have: " + missing
                + ". Renaming a ColumnDef renames the placeholder that reads it (A4).");
    }

    // ---- building -------------------------------------------------------------------------

    private Map<String, Entry> build(EmailEntityType type) {
        Map<String, Entry> out = new LinkedHashMap<>();
        // Customer level first and whole, exactly as the To picker orders its two groups (L4).
        addFields(out, type, "Customer", RoleLevel.CUSTOMER, "customers", CUSTOMER_FIELDS);
        addRoles(out, type, RoleLevel.CUSTOMER);
        // On a CUSTOMER rule the record level IS the customer level, so the same slots are not
        // offered twice under two names (A4).
        if (type != EmailEntityType.CUSTOMER) {
            addFields(out, type, type.title(), RoleLevel.RECORD, entityOf(type), fieldsOf(entityOf(type)));
        }
        addRoles(out, type, RoleLevel.RECORD);
        return Collections.unmodifiableMap(out);
    }

    private static void addFields(Map<String, Entry> out, EmailEntityType type, String namespace,
                                  RoleLevel level, String entity, List<Reader> readers) {
        TableSchema schema = TableSchemas.byEntity(entity);
        requireColumns(entity, schema);
        for (Reader reader : readers) {
            ColumnDef def = reader.column() == null ? null : schema.byName().get(reader.column());
            ColumnType columnType = def == null ? reader.type() : def.type();
            String label = def == null ? reader.label() : def.label();
            String key = namespace + "." + reader.key();
            out.put(key, new Entry(new Slot("{{" + key + "}}", label, group(type, level), columnType,
                    format(columnType, reader.example())), reader.read()));
        }
    }

    private void addRoles(Map<String, Entry> out, EmailEntityType type, RoleLevel level) {
        for (RoleRef ref : targets.rolesOffered(type)) {
            if (ref.level() != level) continue;
            addRole(out, type, ref, "Name", "Sam Sales", EmailTargets.Person::name);
            addRole(out, type, ref, "Email", "sam.sales@example.com", EmailTargets.Person::address);
        }
    }

    /**
     * A role slot resolves to the PRIMARY, through Target.sender — the first of a list the POC
     * book returns primary-first and filtered to active users. No second definition of primary is
     * invented here (A4, L5).
     */
    private static void addRole(Map<String, Entry> out, EmailEntityType type, RoleRef ref,
                                String part, String example,
                                Function<EmailTargets.Person, Object> read) {
        String key = "Role." + ref.level().name() + "." + ref.role().name() + "." + part;
        out.put(key, new Entry(
                new Slot("{{" + key + "}}", ref.label(type) + " - " + part, ref.groupLabel(type),
                        ColumnType.TEXT, example),
                ctx -> ctx.target() == null ? null : ctx.target().sender(ref).map(read).orElse(null)));
    }

    /**
     * The same two group names the To picker uses, from the same method, so a rename there renames
     * both. groupLabel reads only the level, which is why the role is null here (A4, L4).
     */
    private static String group(EmailEntityType type, RoleLevel level) {
        return new RoleRef(null, level).groupLabel(type);
    }

    private Map<String, Entry> catalogue(EmailEntityType type) {
        Map<String, Entry> catalogue = catalogues.get(type);
        if (catalogue == null) {
            throw new BadRequestException(
                    "Placeholders are offered for customers, invoices and payments");
        }
        return catalogue;
    }

    private static String entityOf(EmailEntityType type) {
        return switch (type) {
            case CUSTOMER -> "customers";
            case INVOICE -> "invoices";
            case PAYMENT -> "payments";
            default -> throw new IllegalStateException("No placeholder catalogue for " + type);
        };
    }

    private static List<Reader> fieldsOf(String entity) {
        return switch (entity) {
            case "customers" -> CUSTOMER_FIELDS;
            case "invoices" -> INVOICE_FIELDS;
            case "payments" -> PAYMENT_FIELDS;
            default -> throw new IllegalStateException("No placeholder readers for " + entity);
        };
    }

    // ---- rendering ------------------------------------------------------------------------

    private static String key(Matcher matcher) {
        StringBuilder key = new StringBuilder(matcher.group(1));
        for (int group = 2; group <= matcher.groupCount(); group++) {
            if (matcher.group(group) != null) key.append('.').append(matcher.group(group));
        }
        return key.toString();
    }

    /** The cache is why a body naming a role five times resolves it once (A4). */
    private static String resolve(String key, Map<String, Entry> catalogue, RenderContext ctx) {
        String cached = ctx.cache().get(key);
        if (cached != null) return cached;
        String value = read(catalogue.get(key), ctx);
        ctx.cache().put(key, value);
        return value;
    }

    private static String read(Entry entry, RenderContext ctx) {
        // A key the catalogue does not hold cannot get here from a SAVED rule, because validate
        // refused it. If one ever does, it reads empty and is reported — never left literal in a
        // customer-facing subject, and never an exception out of a send (A4).
        if (entry == null) return "";
        Object value;
        try {
            value = entry.read().apply(ctx);
        } catch (RuntimeException e) {
            log.debug("Placeholder {} could not be read: {}", entry.slot().key(), e.toString());
            return "";
        }
        return EmailText.storable(format(entry.slot().type(), value));
    }

    /**
     * By the slot's ColumnType, through EmailText, and never toString(): money is formatted the
     * way every other email formats money, and a status reads the way the app reads it (A4).
     */
    private static String format(ColumnType type, Object value) {
        if (value == null) return "";
        return switch (type) {
            case MONEY -> value instanceof BigDecimal amount
                    ? EmailText.money(amount) : String.valueOf(value);
            // No viewer and no browser offset on a consumer thread, so UTC — the zone InvoiceDates
            // itself uses (A4).
            case DATE -> value instanceof Instant instant ? EmailText.date(instant, ZoneOffset.UTC)
                    : value instanceof LocalDate date ? EmailText.date(date) : String.valueOf(value);
            case ENUM -> value instanceof Enum<?> constant
                    ? EmailText.humanize(constant) : String.valueOf(value);
            case BOOLEAN -> value instanceof Boolean flag
                    ? EmailText.yesNo(flag) : String.valueOf(value);
            case NUMBER, TEXT, REFERENCE -> String.valueOf(value);
        };
    }

    // ---- reader factories -----------------------------------------------------------------

    private static Reader column(String key, String column, Object example,
                                 Function<RenderContext, Object> read) {
        return new Reader(key, column, null, null, example, read);
    }

    private static Reader derived(String key, String label, ColumnType type, Object example,
                                  Function<RenderContext, Object> read) {
        return new Reader(key, null, type, label, example, read);
    }

    private static Function<RenderContext, Object> onCustomer(Function<Customer, Object> read) {
        return ctx -> ctx.customer() == null ? null : read.apply(ctx.customer());
    }

    private static Function<RenderContext, Object> onInvoice(Function<Invoice, Object> read) {
        return ctx -> ctx.entity() instanceof Invoice invoice ? read.apply(invoice) : null;
    }

    private static Function<RenderContext, Object> onPayment(Function<Payment, Object> read) {
        return ctx -> ctx.entity() instanceof Payment payment ? read.apply(payment) : null;
    }
}
