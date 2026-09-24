package com.geneinvoice.common.query;

import com.geneinvoice.approval.ApprovalSchemas;
import com.geneinvoice.approval.PendingTargetType;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.email.EmailDirection;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailRecipient;
import com.geneinvoice.email.EmailStatus;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentStatus;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.region.RegionPredicates;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import jakarta.persistence.metamodel.SingularAttribute;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TableSchemas {

    private TableSchemas() {}

    private static List<String> names(Class<? extends Enum<?>> e) {
        return Arrays.stream(e.getEnumConstants()).map(Enum::name).toList();
    }

    public static final TableSchema INVOICES = TableSchema.of("invoices", Invoice.class, "invoiceDate,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("invoiceNumber", "Invoice #", ColumnType.TEXT).build(),
            ColumnDef.of("customerId", "Customer", ColumnType.REFERENCE)
                    .reference("customer").notSortable()
                    .path(ColumnDef.referenceId("customer")).build(),
            ColumnDef.of("customerName", "Customer name", ColumnType.TEXT)
                    .path(ColumnDef.nested("customer", "name")).build(),
            // Which region this row is in — read through the customer it hangs off, because
            // customers.region_id is the ONLY region column on a business table. LEFT at both hops:
            // referenceId over a 2-level path INNER-joins and would silently drop rows (B1).
            ColumnDef.of("regionId", "Region", ColumnType.REFERENCE)
                    .reference("region").pocRestricted().notSortable()
                    .path(ColumnDef.nested2("customer", "region", "id")).build(),
            ColumnDef.of("regionName", "Region name", ColumnType.TEXT).pocRestricted()
                    .path(ColumnDef.nested2("customer", "region", "name")).build(),
            ColumnDef.of("invoiceDate", "Date", ColumnType.DATE).build(),
            ColumnDef.of("dueDate", "Due date", ColumnType.DATE).build(),
            ColumnDef.of("total", "Total", ColumnType.MONEY).build(),
            ColumnDef.of("paidAmount", "Paid", ColumnType.MONEY).build(),
            ColumnDef.of("balance", "Balance", ColumnType.MONEY)
                    .path((root, q, cb) -> cb.diff(
                            root.<BigDecimal>get("total"), root.<BigDecimal>get("paidAmount")))
                    .build(),
            ColumnDef.of("status", "Status", ColumnType.ENUM)
                    .enumValues(names(InvoiceStatus.class)).build(),
            // Overdue is derived from the clock rather than stored (D3), so it is a filter and not
            // a value; sorting by lateness is sorting by due date.
            ColumnDef.of("overdue", "Overdue", ColumnType.BOOLEAN).notSortable()
                    .path((root, q, cb) -> root.get("id"))
                    .filter((spec, root, q, cb) -> overduePredicate(spec, root, cb))
                    .build(),
            // Is there a change on this row waiting for somebody to approve it? Built exactly
            // like `overdue` above: derived rather than stored, so it is a filter and not a
            // value, and the path resolves to the id it correlates on because the column is
            // notSortable and a path is only ever used for ordering. Without this ColumnDef
            // schema.requireFilterable answers "Unknown column" and TableSchemaController
            // cannot publish the chip to the client at all (B2).
            ColumnDef.of("approvalPending", "Approval pending", ColumnType.BOOLEAN).notSortable()
                    .path((root, q, cb) -> root.get("id"))
                    .filter((spec, root, q, cb) -> ApprovalSchemas.openPending(
                            spec, root, q, cb, PendingTargetType.INVOICE))
                    .build(),
            ColumnDef.of("notes", "Notes", ColumnType.TEXT).notSortable().build(),
            // The flat foreign key the row already carries, not referenceId("salesPoc"), which is
            // root.get(assoc).get("id"). Two reasons, in this order: the filter then means the
            // same thing on a mirror root, whose sales_poc_user_id is a plain Long with no User
            // to walk; and it is a path JPA itself guarantees needs no join, where the walked one
            // only avoids one because Hibernate chooses to fold it. salesPocName keeps its LEFT
            // join through nested(...) — a NAME genuinely lives on the other table (B3).
            ColumnDef.of("salesPocUserId", "Sales POC", ColumnType.REFERENCE)
                    .reference("pocUser").pocRestricted().notSortable()
                    .path(ColumnDef.attr("salesPocUserId")).build(),
            ColumnDef.of("salesPocName", "Sales POC name", ColumnType.TEXT)
                    .pocRestricted().path(ColumnDef.nested("salesPoc", "fullName")).build(),
            ColumnDef.of("createdAt", "Created", ColumnType.DATE).build());

    public static Predicate invoiceOverdue(Root<?> root, CriteriaBuilder cb, LocalDate today) {
        return cb.and(
                cb.lessThan(root.<LocalDate>get("dueDate"), today),
                cb.greaterThan(cb.diff(root.<BigDecimal>get("total"), root.<BigDecimal>get("paidAmount")),
                        BigDecimal.ZERO),
                cb.notEqual(root.get("status"), InvoiceStatus.CANCELLED));
    }

    private static Predicate overduePredicate(FilterSpec spec, Root<?> root, CriteriaBuilder cb) {
        Predicate overdue = invoiceOverdue(root, cb, InvoiceDates.today());
        return asBoolean(spec.first())
                ? overdue
                : cb.or(cb.isNull(root.get("dueDate")), cb.not(overdue));
    }

    private static boolean asBoolean(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.equalsIgnoreCase("true")) return true;
        if (v.equalsIgnoreCase("false")) return false;
        throw new BadRequestException("Expected true or false but got: " + raw);
    }

    public static final TableSchema PAYMENTS = TableSchema.of("payments", Payment.class, "paidAt,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("customerId", "Customer", ColumnType.REFERENCE)
                    .reference("customer").notSortable()
                    .path(ColumnDef.referenceId("customer")).build(),
            ColumnDef.of("customerName", "Customer name", ColumnType.TEXT)
                    .path(ColumnDef.nested("customer", "name")).build(),
            // The same two columns as INVOICES, resolved through the customer for the same
            // reason: a payment has no region of its own (B1).
            ColumnDef.of("regionId", "Region", ColumnType.REFERENCE)
                    .reference("region").pocRestricted().notSortable()
                    .path(ColumnDef.nested2("customer", "region", "id")).build(),
            ColumnDef.of("regionName", "Region name", ColumnType.TEXT).pocRestricted()
                    .path(ColumnDef.nested2("customer", "region", "name")).build(),
            ColumnDef.of("amount", "Amount", ColumnType.MONEY).build(),
            ColumnDef.of("creditApplied", "Credit applied", ColumnType.MONEY).build(),
            ColumnDef.of("method", "Method", ColumnType.TEXT).build(),
            ColumnDef.of("notes", "Notes", ColumnType.TEXT).notSortable().build(),
            ColumnDef.of("paidAt", "Paid at", ColumnType.DATE).build(),
            ColumnDef.of("status", "Status", ColumnType.ENUM)
                    .enumValues(names(PaymentStatus.class)).build(),
            // The same derived column as INVOICES, over a PAYMENT target (B2).
            ColumnDef.of("approvalPending", "Approval pending", ColumnType.BOOLEAN).notSortable()
                    .path((root, q, cb) -> root.get("id"))
                    .filter((spec, root, q, cb) -> ApprovalSchemas.openPending(
                            spec, root, q, cb, PendingTargetType.PAYMENT))
                    .build(),
            // The flat foreign key, for the same two reasons as INVOICES.salesPocUserId (B3).
            ColumnDef.of("collectionPocUserId", "Collection POC", ColumnType.REFERENCE)
                    .reference("pocUser").pocRestricted().notSortable()
                    .path(ColumnDef.attr("collectionPocUserId")).build(),
            ColumnDef.of("collectionPocName", "Collection POC name", ColumnType.TEXT)
                    .pocRestricted().path(ColumnDef.nested("collectionPoc", "fullName")).build());

    public static final TableSchema CUSTOMERS = TableSchema.of("customers", Customer.class, "name,asc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("name", "Name", ColumnType.TEXT).build(),
            // The account's own region: customers.region_id is the one region column there is, and
            // every other table reads its region through here (B1).
            ColumnDef.of("regionId", "Region", ColumnType.REFERENCE)
                    .reference("region").pocRestricted().notSortable()
                    .path(ColumnDef.referenceId("region")).build(),
            ColumnDef.of("regionName", "Region name", ColumnType.TEXT).pocRestricted()
                    .path(ColumnDef.nested("region", "name")).build(),
            ColumnDef.of("phone", "Phone", ColumnType.TEXT).build(),
            ColumnDef.of("email", "Email", ColumnType.TEXT).build(),
            ColumnDef.of("address", "Address", ColumnType.TEXT).notSortable().build(),
            ColumnDef.of("creditBalance", "Credit balance", ColumnType.MONEY).build(),
            ColumnDef.of("outstanding", "Outstanding", ColumnType.MONEY)
                    .path(TableSchemas::customerOutstanding).build(),
            ColumnDef.of("successPocUserId", "Customer Success POC", ColumnType.REFERENCE)
                    .reference("pocUser").pocRestricted().notSortable()
                    .path((root, q, cb) -> root.get("id"))
                    .filter((spec, root, q, cb) -> pocSeatPredicate(spec, root, q, cb, PocType.SUCCESS))
                    .build(),
            ColumnDef.of("collectionPocUserId", "Collection POC", ColumnType.REFERENCE)
                    .reference("pocUser").pocRestricted().notSortable()
                    .path((root, q, cb) -> root.get("id"))
                    .filter((spec, root, q, cb) -> pocSeatPredicate(spec, root, q, cb, PocType.COLLECTION))
                    .build(),
            // The same derived column as INVOICES, over a CUSTOMER target — and true for a held
            // delete on this account, which is the one change that cannot be undone (B2, CP-04).
            ColumnDef.of("approvalPending", "Approval pending", ColumnType.BOOLEAN).notSortable()
                    .path((root, q, cb) -> root.get("id"))
                    .filter((spec, root, q, cb) -> ApprovalSchemas.openPending(
                            spec, root, q, cb, PendingTargetType.CUSTOMER))
                    .build(),
            ColumnDef.of("createdAt", "Created", ColumnType.DATE).build());

    /** The live spelling: today's invoices, with no interval clause. Byte-identical plan (B3). */
    private static Expression<BigDecimal> customerOutstanding(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb) {
        return customerOutstanding(Invoice.class, null, root, q, cb);
    }

    /**
     * What this account still owes, GENERALISED over the table the sum is taken from rather than
     * copied into a second body. {@code from} is Invoice live and InvoiceHistory as of a date, and
     * {@code inForce} is the null-when-live interval clause that picks the one version of each
     * invoice that was current then (B3).
     *
     * <p>It takes a Class and a lambda and nothing else, which is what keeps common.query free of
     * any import from history — the binding rule this whole feature is arranged around.
     */
    public static Expression<BigDecimal> customerOutstanding(Class<?> from, PredicateFactory inForce,
                                                             Root<?> root, CriteriaQuery<?> q,
                                                             CriteriaBuilder cb) {
        Subquery<BigDecimal> sq = q.subquery(BigDecimal.class);
        Root<?> inv = sq.from(from);
        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(fk(inv, "customer", "customerId"), root.get("id")));
        where.add(cb.notEqual(inv.get("status"), InvoiceStatus.CANCELLED));
        if (inForce != null) where.add(inForce.build(inv, q, cb));
        sq.select(cb.coalesce(
                        cb.sum(cb.diff(inv.<BigDecimal>get("total"), inv.<BigDecimal>get("paidAmount"))),
                        cb.literal(BigDecimal.ZERO)))
                .where(where.toArray(new Predicate[0]));
        return sq;
    }

    /** The live spelling: today's seats, with no interval clause (B3). */
    private static Predicate pocSeatPredicate(FilterSpec spec, Root<?> root, CriteriaQuery<?> q,
                                              CriteriaBuilder cb, PocType type) {
        return pocSeatPredicate(CustomerPoc.class, null, spec, root, q, cb, type);
    }

    /**
     * Who sits on this account in this role, GENERALISED over the seat table. {@code from} is
     * CustomerPoc live and CustomerPocHistory as of a date, and {@code inForce} is the interval
     * clause that picks the seats that were held then — which is what makes "my book as of
     * January" mean the accounts that were mine in January (B3).
     *
     * <p>The same switch is expressed once more in ScopeResolver's two seat subqueries, because
     * those correlate on a Customer root rather than on a FilterSpec. One unit owns both, and the
     * two must move together.
     */
    public static Predicate pocSeatPredicate(Class<?> from, PredicateFactory inForce, FilterSpec spec,
                                             Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb,
                                             PocType type) {
        Subquery<Long> sq = q.subquery(Long.class);
        Root<?> seat = sq.from(from);
        sq.select(cb.literal(1L));
        List<Predicate> parts = new ArrayList<>();
        parts.add(cb.equal(fk(seat, "customer", "customerId"), root.get("id")));
        parts.add(cb.equal(seat.get("pocType"), type));
        if (inForce != null) parts.add(inForce.build(seat, q, cb));
        Predicate base = cb.and(parts.toArray(new Predicate[0]));
        Expression<Long> seatUser = fk(seat, "user", "userId");

        return switch (spec.operator()) {
            case IS_EMPTY -> {
                sq.where(base);
                yield cb.not(cb.exists(sq));
            }
            case IS_NOT_EMPTY -> {
                sq.where(base);
                yield cb.exists(sq);
            }
            case EQ -> {
                sq.where(cb.and(base, cb.equal(seatUser, asLong(spec.first()))));
                yield cb.exists(sq);
            }
            case NEQ -> {
                sq.where(cb.and(base, cb.equal(seatUser, asLong(spec.first()))));
                yield cb.not(cb.exists(sq));
            }
            case IN -> {
                sq.where(cb.and(base, seatUser
                        .in(spec.values().stream().map(TableSchemas::asLong).toList())));
                yield cb.exists(sq);
            }
            default -> throw new BadRequestException(
                    "Operator " + spec.operator().wire() + " is not valid for a POC seat column");
        };
    }

    /**
     * The id a row carries for a to-one: the FLAT foreign key when the root maps one, and the
     * association walked when it does not. Asked of the ROOT through the metamodel rather than
     * branched on a class, so one body serves a live seat (which has a Customer to walk and no
     * flat column) and a mirror seat (which maps customer_id as a plain Long and has no
     * association at all), and so nothing here has to know that mirrors exist (B3).
     *
     * <p>On the three book entities B3-BOOKROOT gave BOTH spellings, and this picks the flat one:
     * measured on Hibernate 6.5, the two emit the same SQL because an association-id path is
     * folded onto the owning side's foreign key. The flat one is the path JPA itself guarantees
     * needs no join (B3).
     */
    private static Expression<Long> fk(Root<?> row, String association, String flat) {
        for (SingularAttribute<?, ?> a : row.getModel().getSingularAttributes()) {
            if (a.getName().equals(flat)) return row.get(flat);
        }
        return row.get(association).get("id");
    }

    /**
     * The as-of TWIN of a live table: the same entity name, the same default sort and the same
     * ColumnDef OBJECT for every column that already resolves on the mirror, with the listed few
     * replaced (B3).
     *
     * <p>It takes the mirror as a {@code Class<?>} and imports nothing, which is what lets the
     * helper live here while the twins themselves live in history/HistorySchemas — common.query
     * must never import history, and {@code TableSchema.of} needs the entity class.
     *
     * <p>The twin keeps the LIVE entity() string on purpose: appliedFilters, the locked chips and
     * every requireFilterable message are then byte-identical between a live list and the same
     * list as of a date. It is deliberately NOT registered in SchemaRegistry — registering it
     * would replace the live schema under the same key.
     *
     * <p>An override naming a column the live schema does not have is a class-init failure rather
     * than a silently ignored line: a renamed column would otherwise take its override with it and
     * leave the twin walking an association the mirror has never had (B3).
     */
    public static TableSchema asOf(TableSchema live, Class<?> mirrorType,
                                   Map<String, ColumnDef> overrides) {
        for (Map.Entry<String, ColumnDef> e : overrides.entrySet()) {
            if (!live.byName().containsKey(e.getKey())) {
                throw new IllegalStateException("The as-of twin of " + live.entity() + " overrides "
                        + e.getKey() + ", which that table does not have");
            }
            if (!e.getKey().equals(e.getValue().name())) {
                throw new IllegalStateException("The as-of twin of " + live.entity() + " files "
                        + e.getValue().name() + " under the name " + e.getKey());
            }
        }
        return TableSchema.of(live.entity(), mirrorType, live.defaultSort(),
                live.columns().stream().map(c -> overrides.getOrDefault(c.name(), c))
                        .toArray(ColumnDef[]::new));
    }

    private static Long asLong(String raw) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Expected an id but got: " + raw);
        }
    }

    public static final TableSchema PROMISES = TableSchema.of("promises", PaymentPromise.class, "promisedDate,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("customerId", "Customer", ColumnType.REFERENCE)
                    .reference("customer").notSortable()
                    .path(ColumnDef.referenceId("customer")).build(),
            ColumnDef.of("customerName", "Customer name", ColumnType.TEXT)
                    .path(ColumnDef.nested("customer", "name")).build(),
            // The same two columns as INVOICES, resolved through the customer for the same
            // reason: a promise has no region of its own (B1).
            ColumnDef.of("regionId", "Region", ColumnType.REFERENCE)
                    .reference("region").pocRestricted().notSortable()
                    .path(ColumnDef.nested2("customer", "region", "id")).build(),
            ColumnDef.of("regionName", "Region name", ColumnType.TEXT).pocRestricted()
                    .path(ColumnDef.nested2("customer", "region", "name")).build(),
            ColumnDef.of("amount", "Promised amount", ColumnType.MONEY).build(),
            ColumnDef.of("fulfilledAmount", "Fulfilled", ColumnType.MONEY).build(),
            ColumnDef.of("remainingAmount", "Remaining", ColumnType.MONEY)
                    .path(TableSchemas::promiseRemaining).build(),
            ColumnDef.of("promisedDate", "Promised by", ColumnType.DATE).build(),
            ColumnDef.of("status", "Status", ColumnType.ENUM)
                    .enumValues(names(PromiseStatus.class)).build(),
            ColumnDef.of("statusOverridden", "Manually overridden", ColumnType.BOOLEAN).build(),
            // The flat foreign key, for the same two reasons as INVOICES.salesPocUserId (B3).
            ColumnDef.of("collectionPocUserId", "Collection POC", ColumnType.REFERENCE)
                    .reference("pocUser").pocRestricted().notSortable()
                    .path(ColumnDef.attr("collectionPocUserId")).build(),
            ColumnDef.of("collectionPocName", "Collection POC name", ColumnType.TEXT)
                    .pocRestricted().path(ColumnDef.nested("collectionPoc", "fullName")).build(),
            ColumnDef.of("notes", "Notes", ColumnType.TEXT).notSortable().build(),
            ColumnDef.of("invoiceId", "Invoice", ColumnType.REFERENCE)
                    .reference("invoice").notSortable()
                    .path((root, q, cb) -> root.get("id"))
                    .filter((spec, root, q, cb) -> promiseLinkPredicate("invoices", spec, root, q, cb))
                    .build(),
            ColumnDef.of("paymentId", "Payment", ColumnType.REFERENCE)
                    .reference("payment").notSortable()
                    .path((root, q, cb) -> root.get("id"))
                    .filter((spec, root, q, cb) -> promiseLinkPredicate("payments", spec, root, q, cb))
                    .build(),
            // The same derived column as INVOICES, over a PROMISE target (B2).
            ColumnDef.of("approvalPending", "Approval pending", ColumnType.BOOLEAN).notSortable()
                    .path((root, q, cb) -> root.get("id"))
                    .filter((spec, root, q, cb) -> ApprovalSchemas.openPending(
                            spec, root, q, cb, PendingTargetType.PROMISE))
                    .build(),
            ColumnDef.of("createdAt", "Created", ColumnType.DATE).build());

    static Expression<BigDecimal> promiseRemaining(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb) {
        Expression<BigDecimal> diff = cb.diff(
                root.<BigDecimal>get("amount"), root.<BigDecimal>get("fulfilledAmount"));
        return cb.<BigDecimal>selectCase()
                .when(root.get("status").in(PromiseStatus.KEPT, PromiseStatus.CANCELLED),
                        cb.literal(BigDecimal.ZERO))
                .when(cb.lessThan(diff, BigDecimal.ZERO), cb.literal(BigDecimal.ZERO))
                .otherwise(diff);
    }

    private static Predicate promiseLinkPredicate(String association, FilterSpec spec, Root<?> root,
                                                  CriteriaQuery<?> q, CriteriaBuilder cb) {
        Subquery<Long> sq = q.subquery(Long.class);
        Root<PaymentPromise> promise = sq.from(PaymentPromise.class);
        Join<PaymentPromise, ?> invoice = promise.join(association);
        sq.select(cb.literal(1L));
        Predicate base = cb.equal(promise.get("id"), root.get("id"));

        return switch (spec.operator()) {
            case IS_EMPTY -> {
                sq.where(base);
                yield cb.not(cb.exists(sq));
            }
            case IS_NOT_EMPTY -> {
                sq.where(base);
                yield cb.exists(sq);
            }
            case EQ -> {
                sq.where(cb.and(base, cb.equal(invoice.get("id"), asLong(spec.first()))));
                yield cb.exists(sq);
            }
            case NEQ -> {
                sq.where(cb.and(base, cb.equal(invoice.get("id"), asLong(spec.first()))));
                yield cb.not(cb.exists(sq));
            }
            case IN -> {
                sq.where(cb.and(base, invoice.get("id")
                        .in(spec.values().stream().map(TableSchemas::asLong).toList())));
                yield cb.exists(sq);
            }
            default -> throw new BadRequestException(
                    "Operator " + spec.operator().wire() + " is not valid for the " + association + " column");
        };
    }

    public static final TableSchema PRODUCTS = TableSchema.of("products", Product.class, "name,asc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("name", "Name", ColumnType.TEXT).build(),
            ColumnDef.of("description", "Description", ColumnType.TEXT).notSortable().build(),
            ColumnDef.of("price", "Price", ColumnType.MONEY).build(),
            ColumnDef.of("active", "Active", ColumnType.BOOLEAN).build(),
            ColumnDef.of("createdAt", "Created", ColumnType.DATE).build());

    public static final TableSchema USERS = TableSchema.of("users", User.class, "username,asc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("username", "Username", ColumnType.TEXT).build(),
            ColumnDef.of("email", "Email", ColumnType.TEXT).build(),
            ColumnDef.of("fullName", "Full name", ColumnType.TEXT).build(),
            ColumnDef.of("active", "Active", ColumnType.BOOLEAN).build(),
            ColumnDef.of("roleName", "Role", ColumnType.TEXT)
                    .path(ColumnDef.nested("role", "name")).build(),
            ColumnDef.of("customerId", "Customer account", ColumnType.NUMBER).build(),
            ColumnDef.of("createdAt", "Created", ColumnType.DATE).build());

    public static final TableSchema ROLES = TableSchema.of("roles", Role.class, "name,asc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("name", "Name", ColumnType.TEXT).build(),
            ColumnDef.of("description", "Description", ColumnType.TEXT).notSortable().build());

    public static final TableSchema DISPUTES = TableSchema.of("disputes", Dispute.class, "createdAt,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("customerId", "Customer", ColumnType.REFERENCE).reference("customer").build(),
            // A dispute keeps a bare customer_id with no association to walk, so the filter is a
            // custom EXISTS over customers rather than a path. The path itself is only ever used
            // for sorting and this column is notSortable, so it resolves to the id it has (B1).
            ColumnDef.of("regionId", "Region", ColumnType.REFERENCE)
                    .reference("region").pocRestricted().notSortable()
                    .path((root, q, cb) -> root.get("customerId"))
                    .filter(RegionPredicates::disputeRegionFilter)
                    .build(),
            // notSortable because this one is a correlated scalar subquery rather than a joined
            // column: ordering the whole list by it would run the subquery per row (B1).
            ColumnDef.of("regionName", "Region name", ColumnType.TEXT).pocRestricted().notSortable()
                    .path(RegionPredicates::customerRegionName).build(),
            ColumnDef.of("targetType", "Target", ColumnType.ENUM)
                    .enumValues(names(DisputeTargetType.class)).build(),
            ColumnDef.of("targetId", "Target id", ColumnType.NUMBER).build(),
            ColumnDef.of("status", "Status", ColumnType.ENUM)
                    .enumValues(names(DisputeStatus.class)).build(),
            ColumnDef.of("reason", "Reason", ColumnType.TEXT).notSortable().build(),
            ColumnDef.of("createdAt", "Opened", ColumnType.DATE).build(),
            ColumnDef.of("resolvedAt", "Resolved", ColumnType.DATE).build());

    public static final TableSchema NOTIFICATIONS = TableSchema.of("notifications", Notification.class, "createdAt,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("type", "Type", ColumnType.TEXT).build(),
            ColumnDef.of("title", "Title", ColumnType.TEXT).build(),
            ColumnDef.of("message", "Message", ColumnType.TEXT).notSortable().build(),
            ColumnDef.of("read", "Read", ColumnType.BOOLEAN).build(),
            ColumnDef.of("createdAt", "Received", ColumnType.DATE).build());

    public static final TableSchema INBOX = TableSchema.of("inbox", EmailRecipient.class, "occurredAt,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("subject", "Subject", ColumnType.TEXT)
                    .path(ColumnDef.nested("email", "subject")).build(),
            ColumnDef.of("fromName", "From", ColumnType.TEXT)
                    .pocRestricted().path(ColumnDef.nested("email", "fromName")).build(),
            ColumnDef.of("entityType", "About", ColumnType.ENUM)
                    .enumValues(names(EmailEntityType.class))
                    .path(ColumnDef.nested("email", "entityType")).build(),
            ColumnDef.of("entityLabel", "Record", ColumnType.TEXT)
                    .path(ColumnDef.nested("email", "entityLabel")).build(),
            ColumnDef.of("direction", "Direction", ColumnType.ENUM)
                    .enumValues(names(EmailDirection.class))
                    .path(ColumnDef.nested("email", "direction")).build(),
            ColumnDef.of("status", "Status", ColumnType.ENUM)
                    .enumValues(names(EmailStatus.class))
                    .path(ColumnDef.nested("email", "status")).build(),
            ColumnDef.of("read", "Read", ColumnType.BOOLEAN).build(),
            ColumnDef.of("occurredAt", "Received", ColumnType.DATE)
                    .path(ColumnDef.nested("email", "occurredAt")).build());

    private static final Map<String, TableSchema> BY_ENTITY = buildIndex();

    // The SECOND index, keyed by the lower case of each canonical name, because byEntity has
    // always lower-cased its argument while this map is keyed by schema.entity(). That was
    // invisible while every registered name happened to be lower case; "automationRules" and
    // "automationSteps" are the first that are not, and without this
    // GET /api/table-schemas/automationRules would answer 400 "Unknown table" while
    // TableSchemaController.VIEW_PRIVILEGE — which holds both camelCase keys — resolved perfectly.
    // entities() still returns the CANONICAL names, so mayRead keeps matching (A1, S3).
    private static final Map<String, TableSchema> BY_LOWER_ENTITY = buildLowerIndex();

    private static Map<String, TableSchema> buildIndex() {
        Map<String, TableSchema> m = new LinkedHashMap<>();
        for (TableSchema s : List.of(INVOICES, PAYMENTS, CUSTOMERS, PROMISES, PRODUCTS,
                USERS, ROLES, DISPUTES, NOTIFICATIONS, INBOX)) {
            m.put(s.entity(), s);
        }
        // Tables owned by a feature package register themselves through SchemaRegistry, so
        // publishing one is a line there and not another edit to this file (B1, B2, A6 INTEGRATION).
        for (TableSchema s : SchemaRegistry.extras()) {
            m.put(s.entity(), s);
        }
        return Map.copyOf(m);
    }

    private static Map<String, TableSchema> buildLowerIndex() {
        Map<String, TableSchema> m = new LinkedHashMap<>();
        for (Map.Entry<String, TableSchema> e : BY_ENTITY.entrySet()) {
            // Two canonical names that differ only in case would silently lose one here, and the
            // one that was lost would 400 for ever. Refuse at class-init instead (A1).
            String key = e.getKey().toLowerCase(Locale.ROOT);
            TableSchema clash = m.put(key, e.getValue());
            if (clash != null) {
                throw new IllegalStateException("Two table schemas differ only in case: "
                        + clash.entity() + " and " + e.getKey());
            }
        }
        return Map.copyOf(m);
    }

    /** Case-insensitive on the wire, canonical in the registry. See BY_LOWER_ENTITY (A1, S3). */
    public static TableSchema byEntity(String entity) {
        TableSchema s = BY_LOWER_ENTITY.get(entity == null ? "" : entity.toLowerCase(Locale.ROOT));
        if (s == null) {
            throw new BadRequestException("Unknown table: " + entity);
        }
        return s;
    }

    public static List<String> entities() {
        return List.copyOf(BY_ENTITY.keySet());
    }
}
