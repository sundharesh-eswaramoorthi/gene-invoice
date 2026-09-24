package com.geneinvoice.history;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerHistory;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeHistory;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceHistory;
import com.geneinvoice.invoice.InvoiceItem;
import com.geneinvoice.invoice.InvoiceItemHistory;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentAllocation;
import com.geneinvoice.payment.PaymentAllocationHistory;
import com.geneinvoice.payment.PaymentHistory;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.CustomerPocHistory;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PromiseHistory;
import com.geneinvoice.promise.PromiseInvoiceHistory;
import com.geneinvoice.promise.PromisePaymentHistory;
import com.geneinvoice.task.Task;
import com.geneinvoice.task.TaskHistory;
import com.geneinvoice.user.User;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The eleven mirrors, as DATA. Every later bean in B3 is driven off this list rather than off a
 * hand-kept copy of it: the writer, the reconciler, the enum widener and the startup metamodel
 * check all iterate {@link #all()}, so a twelfth mirror is one entry here and not five edits in
 * five files that can each be forgotten separately (B3).
 *
 * <p>NINE PRIMARIES AND TWO LINKS. {@link #forType} answers for a live @Entity and is what the
 * writer looks a buffered key up in. The two promise link mirrors have no live entity of their own
 * — they mirror {@code @ManyToMany} join tables, which Hibernate reports through collection events
 * on the owning promise — so they are reached through {@link #linksOf} instead, and their
 * binding's {@code entityClass} names that owner (B3).
 */
@Component
public class HistoryRegistry {

    /**
     * A live persisted attribute that deliberately has no mirror column, and the ONLY one in the
     * programme: {@code version}, on all FOUR versioned entities — customers and invoices had one
     * already and S5-ROW-VERSION added it to payments and payment_promises. Miss one and the W18
     * startup check refuses to boot, which is the point (B3, B2).
     */
    private static final Set<String> VERSION = HistoryBinding.VERSION;

    private static final List<HistoryBinding> BINDINGS = List.of(
            customer(), invoice(), invoiceItem(), payment(), paymentAllocation(),
            promise(), promiseInvoiceLink(), promisePaymentLink(), dispute(), customerPoc(), task());

    private static final Map<Class<?>, HistoryBinding> BY_ENTITY = primaries();
    private static final Map<Class<?>, HistoryBinding> BY_MIRROR = byMirror();

    private static Map<Class<?>, HistoryBinding> primaries() {
        Map<Class<?>, HistoryBinding> out = new LinkedHashMap<>();
        for (HistoryBinding b : BINDINGS) {
            if (isLink(b)) continue;
            out.put(b.entityClass(), b);
        }
        return Map.copyOf(out);
    }

    private static Map<Class<?>, HistoryBinding> byMirror() {
        Map<Class<?>, HistoryBinding> out = new LinkedHashMap<>();
        for (HistoryBinding b : BINDINGS) out.put(b.mirrorClass(), b);
        return Map.copyOf(out);
    }

    private static boolean isLink(HistoryBinding b) {
        return b.mirrorClass() == PromiseInvoiceHistory.class
                || b.mirrorClass() == PromisePaymentHistory.class;
    }

    public List<HistoryBinding> all() {
        return BINDINGS;
    }

    /** The primary binding for a live entity, or null. Never answers with a link binding (B3). */
    public HistoryBinding forType(Class<?> entityClass) {
        return BY_ENTITY.get(entityClass);
    }

    public HistoryBinding forMirror(Class<? extends HistoryRow> mirrorClass) {
        return BY_MIRROR.get(mirrorClass);
    }

    /**
     * The join-table mirrors a live entity owns — two for PaymentPromise, none for everything
     * else. A promise's collection events rewrite these in the same drain as the promise row (B3).
     */
    public List<HistoryBinding> linksOf(Class<?> ownerClass) {
        return BINDINGS.stream().filter(HistoryRegistry::isLink)
                .filter(b -> b.entityClass() == ownerClass).toList();
    }

    /**
     * Is this entity mirrored at all? The listener filter asks before it buffers anything, so an
     * AutomationEvent or an AuditLog insert can never enter the buffer (B3, A5).
     */
    public boolean isMirrored(Class<?> entityClass) {
        return BY_ENTITY.containsKey(entityClass);
    }

    public Set<Class<?>> mirroredTypes() {
        return BY_ENTITY.keySet();
    }

    // ---- the eleven bindings ---------------------------------------------------------------

    private static HistoryBinding customer() {
        List<String> live = List.of("name", "phone", "email", "address", "credit_balance",
                "payment_term", "region_id", "created_at");
        return new HistoryBinding(Customer.class, CustomerHistory.class, "customers",
                "customer_history", "customer_id", live,
                (o, row) -> {
                    Customer c = (Customer) o;
                    row.set("name", c.getName());
                    row.set("phone", c.getPhone());
                    row.set("email", c.getEmail());
                    row.set("address", c.getAddress());
                    row.set("credit_balance", c.getCreditBalance());
                    row.set("payment_term", name(c.getPaymentTerm()));
                    row.set("region_id", c.getRegionId());
                    row.set("created_at", c.getCreatedAt());
                },
                drift("customers", "customer_history", "customer_id", live), VERSION);
    }

    private static HistoryBinding invoice() {
        List<String> live = List.of("customer_id", "invoice_number", "invoice_date", "due_date",
                "payment_term", "total", "paid_amount", "status", "notes", "sales_poc_user_id",
                "created_at");
        List<String> columns = concat(live, "customer_name", "sales_poc_name");
        return new HistoryBinding(Invoice.class, InvoiceHistory.class, "invoices",
                "invoice_history", "invoice_id", columns,
                (o, row) -> {
                    Invoice i = (Invoice) o;
                    row.set("customer_id", i.getCustomerId());
                    row.set("invoice_number", i.getInvoiceNumber());
                    row.set("invoice_date", i.getInvoiceDate());
                    row.set("due_date", i.getDueDate());
                    row.set("payment_term", name(i.getPaymentTerm()));
                    row.set("total", i.getTotal());
                    row.set("paid_amount", i.getPaidAmount());
                    row.set("status", name(i.getStatus()));
                    row.set("notes", i.getNotes());
                    row.set("sales_poc_user_id", i.getSalesPocUserId());
                    row.set("created_at", i.getCreatedAt());
                    // The two denormalised as-of labels. Read through the still-open session, off
                    // the associations, because the flat duplicate columns are stale on a freshly
                    // inserted instance (B3).
                    row.set("customer_name", i.getCustomerName());
                    row.set("sales_poc_name", fullName(i.getSalesPoc()));
                },
                drift("invoices", "invoice_history", "invoice_id", live), VERSION);
    }

    private static HistoryBinding invoiceItem() {
        List<String> live = List.of("invoice_id", "product_id", "quantity", "unit_price",
                "line_total");
        return new HistoryBinding(InvoiceItem.class, InvoiceItemHistory.class, "invoice_items",
                "invoice_item_history", "invoice_item_id", live,
                (o, row) -> {
                    InvoiceItem it = (InvoiceItem) o;
                    row.set("invoice_id", it.getInvoice() == null ? null : it.getInvoice().getId());
                    row.set("product_id", it.getProduct() == null ? null : it.getProduct().getId());
                    row.set("quantity", it.getQuantity());
                    row.set("unit_price", it.getUnitPrice());
                    row.set("line_total", it.getLineTotal());
                },
                drift("invoice_items", "invoice_item_history", "invoice_item_id", live), Set.of());
    }

    private static HistoryBinding payment() {
        List<String> live = List.of("customer_id", "amount", "credit_applied", "method", "notes",
                "paid_at", "collection_poc_user_id", "status");
        List<String> columns = concat(live, "customer_name", "collection_poc_name");
        return new HistoryBinding(Payment.class, PaymentHistory.class, "payments",
                "payment_history", "payment_id", columns,
                (o, row) -> {
                    Payment p = (Payment) o;
                    row.set("customer_id", p.getCustomerId());
                    row.set("amount", p.getAmount());
                    row.set("credit_applied", p.getCreditApplied());
                    row.set("method", p.getMethod());
                    row.set("notes", p.getNotes());
                    row.set("paid_at", p.getPaidAt());
                    row.set("collection_poc_user_id", p.getCollectionPocUserId());
                    row.set("status", name(p.getStatus()));
                    row.set("customer_name", p.getCustomerName());
                    row.set("collection_poc_name", fullName(p.getCollectionPoc()));
                },
                drift("payments", "payment_history", "payment_id", live), VERSION);
    }

    private static HistoryBinding paymentAllocation() {
        List<String> live = List.of("payment_id", "invoice_id", "amount");
        List<String> columns = concat(live, "payment_status", "paid_at", "customer_id",
                "customer_name");
        return new HistoryBinding(PaymentAllocation.class, PaymentAllocationHistory.class,
                "payment_allocations", "payment_allocation_history", "allocation_id", columns,
                (o, row) -> {
                    PaymentAllocation a = (PaymentAllocation) o;
                    Payment p = a.getPayment();
                    row.set("payment_id", p == null ? null : p.getId());
                    row.set("invoice_id", a.getInvoice() == null ? null : a.getInvoice().getId());
                    row.set("amount", a.getAmount());
                    // Denormalised so B3-DASHBOARD's collected-as-of figure reads them flat, with
                    // no walk from the allocation to the payment to the invoice to the customer,
                    // and with no chance of joining a past allocation to today's payment (B3).
                    row.set("payment_status", p == null ? null : name(p.getStatus()));
                    row.set("paid_at", p == null ? null : p.getPaidAt());
                    row.set("customer_id", p == null ? null : p.getCustomerId());
                    row.set("customer_name", p == null ? null : p.getCustomerName());
                },
                drift("payment_allocations", "payment_allocation_history", "allocation_id", live),
                Set.of());
    }

    private static HistoryBinding promise() {
        List<String> live = List.of("customer_id", "amount", "promised_date",
                "collection_poc_user_id", "notes", "status", "fulfilled_amount",
                "status_overridden", "override_reason", "overridden_by_user_id", "overridden_at",
                "broken_notified_at", "created_by_user_id", "created_at", "updated_at");
        List<String> columns = concat(live, "customer_name", "collection_poc_name");
        return new HistoryBinding(PaymentPromise.class, PromiseHistory.class, "payment_promises",
                "promise_history", "promise_id", columns,
                (o, row) -> {
                    PaymentPromise p = (PaymentPromise) o;
                    row.set("customer_id", p.getCustomerId());
                    row.set("amount", p.getAmount());
                    row.set("promised_date", p.getPromisedDate());
                    row.set("collection_poc_user_id", p.getCollectionPocUserId());
                    row.set("notes", p.getNotes());
                    row.set("status", name(p.getStatus()));
                    row.set("fulfilled_amount", p.getFulfilledAmount());
                    row.set("status_overridden", p.isStatusOverridden());
                    row.set("override_reason", p.getOverrideReason());
                    row.set("overridden_by_user_id", p.getOverriddenByUserId());
                    row.set("overridden_at", p.getOverriddenAt());
                    row.set("broken_notified_at", p.getBrokenNotifiedAt());
                    row.set("created_by_user_id", p.getCreatedByUserId());
                    row.set("created_at", p.getCreatedAt());
                    row.set("updated_at", p.getUpdatedAt());
                    row.set("customer_name", p.getCustomerName());
                    row.set("collection_poc_name", fullName(p.getCollectionPoc()));
                },
                drift("payment_promises", "promise_history", "promise_id", live), VERSION);
    }

    private static HistoryBinding promiseInvoiceLink() {
        return new HistoryBinding(PaymentPromise.class, PromiseInvoiceHistory.class,
                "payment_promise_invoices", "promise_invoice_history", "promise_id",
                List.of("invoice_id"),
                (o, row) -> row.set("invoice_id", ((HistoryBinding.Link) o).otherId()),
                linkDrift("payment_promise_invoices", "promise_invoice_history", "invoice_id"),
                Set.of());
    }

    private static HistoryBinding promisePaymentLink() {
        return new HistoryBinding(PaymentPromise.class, PromisePaymentHistory.class,
                "payment_promise_payments", "promise_payment_history", "promise_id",
                List.of("payment_id"),
                (o, row) -> row.set("payment_id", ((HistoryBinding.Link) o).otherId()),
                linkDrift("payment_promise_payments", "promise_payment_history", "payment_id"),
                Set.of());
    }

    private static HistoryBinding dispute() {
        List<String> columns = List.of("customer_id", "opened_by_user_id", "target_type",
                "target_id", "reason", "proposed_change_json", "status", "admin_notes",
                "resolved_by_user_id", "resolved_at", "created_at", "updated_at");
        // proposed_change_json IS compared, and that took a measurement rather than a reading.
        // Dispute.proposedChangeJson carries @Lob, which on Postgres normally makes the row hold
        // a large-object OID — and an oid-against-text comparison would make this whole query
        // fail on the real database while passing on H2. It does not here, because the live
        // column also carries columnDefinition = "TEXT" and that wins: verified on a real
        // Postgres 16, where information_schema reports disputes.proposed_change_json as text,
        // and the query below was run there. The mirror is plain TEXT with no @Lob at all (B3).
        return new HistoryBinding(Dispute.class, DisputeHistory.class, "disputes",
                "dispute_history", "dispute_id", columns,
                (o, row) -> {
                    Dispute d = (Dispute) o;
                    row.set("customer_id", d.getCustomerId());
                    row.set("opened_by_user_id", d.getOpenedByUserId());
                    row.set("target_type", name(d.getTargetType()));
                    row.set("target_id", d.getTargetId());
                    row.set("reason", d.getReason());
                    row.set("proposed_change_json", d.getProposedChangeJson());
                    row.set("status", name(d.getStatus()));
                    row.set("admin_notes", d.getAdminNotes());
                    row.set("resolved_by_user_id", d.getResolvedByUserId());
                    row.set("resolved_at", d.getResolvedAt());
                    row.set("created_at", d.getCreatedAt());
                    row.set("updated_at", d.getUpdatedAt());
                },
                drift("disputes", "dispute_history", "dispute_id", columns), Set.of());
    }

    private static HistoryBinding customerPoc() {
        List<String> live = List.of("customer_id", "user_id", "poc_type", "is_primary",
                "created_by_user_id", "created_at");
        return new HistoryBinding(CustomerPoc.class, CustomerPocHistory.class, "customer_pocs",
                "customer_poc_history", "customer_poc_id", live,
                (o, row) -> {
                    CustomerPoc s = (CustomerPoc) o;
                    row.set("customer_id", s.getCustomer() == null ? null : s.getCustomer().getId());
                    row.set("user_id", s.getUser() == null ? null : s.getUser().getId());
                    row.set("poc_type", name(s.getPocType()));
                    row.set("is_primary", s.isPrimary());
                    row.set("created_by_user_id", s.getCreatedByUserId());
                    row.set("created_at", s.getCreatedAt());
                },
                drift("customer_pocs", "customer_poc_history", "customer_poc_id", live), Set.of());
    }

    private static HistoryBinding task() {
        List<String> live = List.of("entity_type", "entity_id", "entity_label", "customer_id",
                "title", "notes", "due_date", "status", "created_by_user_id", "created_by_rule_id",
                "created_by_step_id", "completed_by_user_id", "completed_at", "created_at",
                "updated_at");
        return new HistoryBinding(Task.class, TaskHistory.class, "tasks", "task_history", "task_id",
                live,
                (o, row) -> {
                    Task t = (Task) o;
                    row.set("entity_type", name(t.getEntityType()));
                    row.set("entity_id", t.getEntityId());
                    row.set("entity_label", t.getEntityLabel());
                    row.set("customer_id", t.getCustomerId());
                    row.set("title", t.getTitle());
                    row.set("notes", t.getNotes());
                    row.set("due_date", t.getDueDate());
                    row.set("status", name(t.getStatus()));
                    row.set("created_by_user_id", t.getCreatedByUserId());
                    row.set("created_by_rule_id", t.getCreatedByRuleId());
                    row.set("created_by_step_id", t.getCreatedByStepId());
                    row.set("completed_by_user_id", t.getCompletedByUserId());
                    row.set("completed_at", t.getCompletedAt());
                    row.set("created_at", t.getCreatedAt());
                    row.set("updated_at", t.getUpdatedAt());
                },
                drift("tasks", "task_history", "task_id", live), Set.of());
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static String name(Enum<?> value) {
        // The mirror stores the enum's NAME, exactly as @Enumerated(STRING) does on the live row,
        // which is what keeps ValueCoercion's target.isEnum() branch correct for both roots (B3).
        return value == null ? null : value.name();
    }

    private static String fullName(User user) {
        return user == null ? null : user.getFullName();
    }

    private static List<String> concat(List<String> live, String... denormalised) {
        return java.util.stream.Stream.concat(live.stream(), java.util.Arrays.stream(denormalised))
                .toList();
    }

    /**
     * The reconciler's keyset-chunked diff, built once per binding instead of written out eleven
     * times. Three parameters, in this order and no other: the OPEN sentinel, the id to read
     * after, and the chunk size. {@code is distinct from} is understood by both Postgres and
     * H2 2.x and is the only comparison that treats two nulls as equal (B3).
     */
    private static String drift(String liveTable, String mirrorTable, String idColumn,
                                List<String> compared) {
        StringBuilder sql = new StringBuilder("select l.id from ").append(liveTable).append(" l")
                .append(" left join ").append(mirrorTable).append(" h on h.").append(idColumn)
                .append(" = l.id and h.valid_to = ? and h.deleted = false")
                .append(" where l.id > ? and (h.history_id is null");
        for (String column : compared) {
            sql.append(" or h.").append(column).append(" is distinct from l.").append(column);
        }
        return sql.append(") order by l.id limit ?").toString();
    }

    /**
     * A join table has no id and no columns to disagree about, so the only drift a link can have
     * is a missing mirror row. Chunked by the OWNER id, so the three parameters mean the same
     * thing here as everywhere else (B3).
     */
    private static String linkDrift(String liveTable, String mirrorTable, String otherColumn) {
        return "select l.promise_id, l." + otherColumn + " from " + liveTable + " l"
                + " left join " + mirrorTable + " h on h.promise_id = l.promise_id"
                + " and h." + otherColumn + " = l." + otherColumn
                + " and h.valid_to = ? and h.deleted = false"
                + " where l.promise_id > ? and h.history_id is null"
                + " order by l.promise_id, l." + otherColumn + " limit ?";
    }
}
