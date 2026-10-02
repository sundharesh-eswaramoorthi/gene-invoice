package com.geneinvoice.automation.placeholder;

import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.email.EmailEntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The placeholders offered on each kind of record (A4, §5.10.4): the customer's fields everywhere, the record's own
 * on an invoice or a payment, and a few that belong to no record. A key is offered at a level only where that level
 * exists, so an invoice's number cannot be asked for on a customer rule.
 */
public final class PlaceholderCatalogue {

    private PlaceholderCatalogue() {}

    public enum Level { CUSTOMER, RECORD, COMMON }

    public enum Kind { TEXT, MONEY, DATE, NUMBER }

    /** One placeholder; {@code poc} ones show who looks after the record, and need POC_VIEW to be filled in. */
    public record Item(String key, String label, Kind kind, Level level, boolean poc) {
        public String token() {
            return "{{" + key + "}}";
        }
    }

    public record ItemDto(String token, String label, String kind) {}

    public record GroupDto(String level, String label, List<ItemDto> items) {}

    public record CatalogueDto(String entityType, List<GroupDto> groups) {}

    static final List<Item> CUSTOMER = List.of(
            item("Customer.Name", "Name", Kind.TEXT, Level.CUSTOMER),
            item("Customer.Email", "Email", Kind.TEXT, Level.CUSTOMER),
            item("Customer.Phone", "Phone", Kind.TEXT, Level.CUSTOMER),
            item("Customer.Address", "Address", Kind.TEXT, Level.CUSTOMER),
            item("Customer.PaymentTerm", "Payment term", Kind.TEXT, Level.CUSTOMER),
            item("Customer.CreditBalance", "Credit balance", Kind.MONEY, Level.CUSTOMER),
            item("Customer.Outstanding", "Outstanding", Kind.MONEY, Level.CUSTOMER),
            item("Customer.OverdueAmount", "Overdue amount", Kind.MONEY, Level.CUSTOMER),
            poc("Customer.CollectionPoc", "Collection POC", Level.CUSTOMER),
            poc("Customer.CollectionPocEmail", "Collection POC email", Level.CUSTOMER),
            poc("Customer.SuccessPoc", "Customer Success POC", Level.CUSTOMER),
            poc("Customer.SuccessPocEmail", "Customer Success POC email", Level.CUSTOMER),
            item("Customer.Region", "Region", Kind.TEXT, Level.CUSTOMER));

    static final List<Item> INVOICE = List.of(
            item("Invoice.Number", "Number", Kind.TEXT, Level.RECORD),
            item("Invoice.Date", "Date", Kind.DATE, Level.RECORD),
            item("Invoice.DueDate", "Due date", Kind.DATE, Level.RECORD),
            item("Invoice.PaymentTerm", "Payment term", Kind.TEXT, Level.RECORD),
            item("Invoice.Total", "Total", Kind.MONEY, Level.RECORD),
            item("Invoice.Paid", "Paid", Kind.MONEY, Level.RECORD),
            item("Invoice.Balance", "Balance", Kind.MONEY, Level.RECORD),
            item("Invoice.Status", "Status", Kind.TEXT, Level.RECORD),
            item("Invoice.Overdue", "Overdue (Yes/No)", Kind.TEXT, Level.RECORD),
            item("Invoice.DaysOverdue", "Days overdue", Kind.NUMBER, Level.RECORD),
            item("Invoice.Notes", "Notes", Kind.TEXT, Level.RECORD),
            poc("Invoice.SalesPoc", "Sales POC", Level.RECORD),
            poc("Invoice.SalesPocEmail", "Sales POC email", Level.RECORD));

    static final List<Item> PAYMENT = List.of(
            item("Payment.Number", "Number", Kind.TEXT, Level.RECORD),
            item("Payment.Date", "Date", Kind.DATE, Level.RECORD),
            item("Payment.Amount", "Amount", Kind.MONEY, Level.RECORD),
            item("Payment.Unallocated", "Unallocated", Kind.MONEY, Level.RECORD),
            item("Payment.Method", "Method", Kind.TEXT, Level.RECORD),
            item("Payment.Notes", "Notes", Kind.TEXT, Level.RECORD),
            item("Payment.Status", "Status", Kind.TEXT, Level.RECORD),
            item("Payment.Invoices", "Invoices paid", Kind.TEXT, Level.RECORD),
            poc("Payment.CollectionPoc", "Collection POC", Level.RECORD),
            poc("Payment.CollectionPocEmail", "Collection POC email", Level.RECORD));

    static final Item TODAY = item("Today", "Today", Kind.DATE, Level.COMMON);

    static final List<Item> COMMON = List.of(TODAY, item("Rule.Name", "Rule name", Kind.TEXT, Level.COMMON));

    private static Item item(String key, String label, Kind kind, Level level) {
        return new Item(key, label, kind, level, false);
    }

    private static Item poc(String key, String label, Level level) {
        return new Item(key, label, Kind.TEXT, level, true);
    }

    /** What a rule on this kind of record may use. */
    public static List<Item> offered(TriggerEntity entity) {
        return Stream.of(CUSTOMER, recordItems(entity), COMMON).flatMap(List::stream).toList();
    }

    /**
     * What an email written by hand about a record of this kind may use (PC-10): a customer's, an invoice's or a
     * payment's fields as on rules about it, a promise's or a dispute's customer, and today; no rule's name, as no
     * rule sends it; nothing on a record with no customer (a product, a user, a role).
     */
    public static List<Item> offered(EmailEntityType type) {
        return switch (type) {
            case CUSTOMER, INVOICE, PAYMENT -> Stream.of(CUSTOMER, recordItems(TriggerEntity.valueOf(type.name())),
                    List.of(TODAY)).flatMap(List::stream).toList();
            case PROMISE, DISPUTE -> Stream.concat(CUSTOMER.stream(), Stream.of(TODAY)).toList();
            default -> List.of();
        };
    }

    private static List<Item> recordItems(TriggerEntity entity) {
        return switch (entity) {
            case CUSTOMER -> List.of();
            case INVOICE -> INVOICE;
            case PAYMENT -> PAYMENT;
        };
    }

    /** Any placeholder at all, whatever the record; empty for a key that is none. */
    public static Optional<Item> find(String key) {
        return Stream.of(CUSTOMER, INVOICE, PAYMENT, COMMON).flatMap(List::stream)
                .filter(i -> i.key().equalsIgnoreCase(key)).findFirst();
    }

    /**
     * The picker's catalogue for {@code entityType}: a rule's entity, or a record an email is written about, where
     * a promise or a dispute offers its customer's fields only (§5.10.4).
     */
    public static CatalogueDto catalogue(String entityType) {
        String wanted = entityType == null ? "" : entityType.trim().toUpperCase(Locale.ROOT);
        List<GroupDto> groups = new ArrayList<>();
        switch (wanted) {
            case "CUSTOMER", "INVOICE", "PAYMENT" -> {
                TriggerEntity entity = TriggerEntity.valueOf(wanted);
                groups.add(group(Level.CUSTOMER, "Customer", CUSTOMER));
                if (entity != TriggerEntity.CUSTOMER) {
                    groups.add(group(Level.RECORD, entity == TriggerEntity.INVOICE ? "Invoice" : "Payment",
                            recordItems(entity)));
                }
                groups.add(group(Level.COMMON, "General", COMMON));
            }
            case "PROMISE", "DISPUTE" -> {
                groups.add(group(Level.CUSTOMER, "Customer", CUSTOMER));
                groups.add(group(Level.COMMON, "General", List.of(TODAY)));
            }
            default -> throw new BadRequestException("entityType must be one of CUSTOMER, INVOICE, PAYMENT, "
                    + EmailEntityType.PROMISE + ", " + EmailEntityType.DISPUTE);
        }
        return new CatalogueDto(wanted, groups);
    }

    private static GroupDto group(Level level, String label, List<Item> items) {
        return new GroupDto(level.name(), label,
                items.stream().map(i -> new ItemDto(i.token(), i.label(), i.kind().name())).toList());
    }
}
