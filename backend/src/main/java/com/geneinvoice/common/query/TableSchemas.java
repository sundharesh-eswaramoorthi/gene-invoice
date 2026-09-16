package com.geneinvoice.common.query;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.PaymentStatus;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PromiseStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single source of truth for what every table can be sorted and filtered by. The frontend
 * builds its filter UI from these definitions, and every list request is validated against them.
 */
public final class TableSchemas {

    private TableSchemas() {}

    private static List<String> names(Class<? extends Enum<?>> e) {
        return Arrays.stream(e.getEnumConstants()).map(Enum::name).toList();
    }

    // ---- invoices ------------------------------------------------------------------

    public static final TableSchema INVOICES = TableSchema.of("invoices", "invoiceDate,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("invoiceNumber", "Invoice #", ColumnType.TEXT).build(),
            ColumnDef.of("customerId", "Customer", ColumnType.REFERENCE)
                    .reference("customer").notSortable()
                    .path(ColumnDef.referenceId("customer")).build(),
            ColumnDef.of("customerName", "Customer name", ColumnType.TEXT)
                    .path(ColumnDef.nested("customer", "name")).build(),
            ColumnDef.of("invoiceDate", "Date", ColumnType.DATE).build(),
            ColumnDef.of("total", "Total", ColumnType.MONEY).build(),
            ColumnDef.of("paidAmount", "Paid", ColumnType.MONEY).build(),
            ColumnDef.of("balance", "Balance", ColumnType.MONEY)
                    .path((root, q, cb) -> cb.diff(
                            root.<BigDecimal>get("total"), root.<BigDecimal>get("paidAmount")))
                    .build(),
            ColumnDef.of("status", "Status", ColumnType.ENUM)
                    .enumValues(names(InvoiceStatus.class)).build(),
            ColumnDef.of("notes", "Notes", ColumnType.TEXT).notSortable().build(),
            ColumnDef.of("salesPocUserId", "Sales POC", ColumnType.REFERENCE)
                    .reference("pocUser").pocRestricted().notSortable()
                    .path(ColumnDef.referenceId("salesPoc")).build(),
            ColumnDef.of("salesPocName", "Sales POC name", ColumnType.TEXT)
                    .pocRestricted().path(ColumnDef.nested("salesPoc", "fullName")).build(),
            ColumnDef.of("createdAt", "Created", ColumnType.DATE).build());

    // ---- payments ------------------------------------------------------------------

    public static final TableSchema PAYMENTS = TableSchema.of("payments", "paidAt,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("customerId", "Customer", ColumnType.REFERENCE)
                    .reference("customer").notSortable()
                    .path(ColumnDef.referenceId("customer")).build(),
            ColumnDef.of("customerName", "Customer name", ColumnType.TEXT)
                    .path(ColumnDef.nested("customer", "name")).build(),
            ColumnDef.of("amount", "Amount", ColumnType.MONEY).build(),
            ColumnDef.of("creditApplied", "Credit applied", ColumnType.MONEY).build(),
            ColumnDef.of("method", "Method", ColumnType.TEXT).build(),
            ColumnDef.of("notes", "Notes", ColumnType.TEXT).notSortable().build(),
            ColumnDef.of("paidAt", "Paid at", ColumnType.DATE).build(),
            ColumnDef.of("status", "Status", ColumnType.ENUM)
                    .enumValues(names(PaymentStatus.class)).build(),
            ColumnDef.of("collectionPocUserId", "Collection POC", ColumnType.REFERENCE)
                    .reference("pocUser").pocRestricted().notSortable()
                    .path(ColumnDef.referenceId("collectionPoc")).build(),
            ColumnDef.of("collectionPocName", "Collection POC name", ColumnType.TEXT)
                    .pocRestricted().path(ColumnDef.nested("collectionPoc", "fullName")).build());

    // ---- customers -----------------------------------------------------------------

    public static final TableSchema CUSTOMERS = TableSchema.of("customers", "name,asc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("name", "Name", ColumnType.TEXT).build(),
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
            ColumnDef.of("createdAt", "Created", ColumnType.DATE).build());

    /** Sum of live invoice balances for the customer this root points at. */
    private static Expression<BigDecimal> customerOutstanding(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb) {
        Subquery<BigDecimal> sq = q.subquery(BigDecimal.class);
        Root<Invoice> inv = sq.from(Invoice.class);
        sq.select(cb.coalesce(
                        cb.sum(cb.diff(inv.<BigDecimal>get("total"), inv.<BigDecimal>get("paidAmount"))),
                        cb.literal(BigDecimal.ZERO)))
                .where(cb.equal(inv.get("customer").get("id"), root.get("id")),
                        cb.notEqual(inv.get("status"), InvoiceStatus.CANCELLED));
        return sq;
    }

    /**
     * A customer's POC seats are a to-many relationship, so "is" means "has a seat held by",
     * and "is empty" means "holds no seat of this kind" — the POC-missing filter of AC-A9.
     */
    private static Predicate pocSeatPredicate(FilterSpec spec, Root<?> root, CriteriaQuery<?> q,
                                              CriteriaBuilder cb, PocType type) {
        Subquery<Long> sq = q.subquery(Long.class);
        Root<CustomerPoc> seat = sq.from(CustomerPoc.class);
        sq.select(cb.literal(1L));
        Predicate base = cb.and(
                cb.equal(seat.get("customer").get("id"), root.get("id")),
                cb.equal(seat.get("pocType"), type));

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
                sq.where(cb.and(base, cb.equal(seat.get("user").get("id"), asLong(spec.first()))));
                yield cb.exists(sq);
            }
            case NEQ -> {
                sq.where(cb.and(base, cb.equal(seat.get("user").get("id"), asLong(spec.first()))));
                yield cb.not(cb.exists(sq));
            }
            case IN -> {
                sq.where(cb.and(base, seat.get("user").get("id")
                        .in(spec.values().stream().map(TableSchemas::asLong).toList())));
                yield cb.exists(sq);
            }
            default -> throw new BadRequestException(
                    "Operator " + spec.operator().wire() + " is not valid for a POC seat column");
        };
    }

    private static Long asLong(String raw) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Expected an id but got: " + raw);
        }
    }

    // ---- payment promises ----------------------------------------------------------

    public static final TableSchema PROMISES = TableSchema.of("promises", "promisedDate,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("customerId", "Customer", ColumnType.REFERENCE)
                    .reference("customer").notSortable()
                    .path(ColumnDef.referenceId("customer")).build(),
            ColumnDef.of("customerName", "Customer name", ColumnType.TEXT)
                    .path(ColumnDef.nested("customer", "name")).build(),
            ColumnDef.of("amount", "Promised amount", ColumnType.MONEY).build(),
            ColumnDef.of("fulfilledAmount", "Fulfilled", ColumnType.MONEY).build(),
            ColumnDef.of("remainingAmount", "Remaining", ColumnType.MONEY)
                    .path(TableSchemas::promiseRemaining).build(),
            ColumnDef.of("promisedDate", "Promised by", ColumnType.DATE).build(),
            ColumnDef.of("status", "Status", ColumnType.ENUM)
                    .enumValues(names(PromiseStatus.class)).build(),
            ColumnDef.of("statusOverridden", "Manually overridden", ColumnType.BOOLEAN).build(),
            ColumnDef.of("collectionPocUserId", "Collection POC", ColumnType.REFERENCE)
                    .reference("pocUser").pocRestricted().notSortable()
                    .path(ColumnDef.referenceId("collectionPoc")).build(),
            ColumnDef.of("collectionPocName", "Collection POC name", ColumnType.TEXT)
                    .pocRestricted().path(ColumnDef.nested("collectionPoc", "fullName")).build(),
            ColumnDef.of("notes", "Notes", ColumnType.TEXT).notSortable().build(),
            // Backs the Promises tab on Invoice Details, which asks for ?invoiceId=N.
            ColumnDef.of("invoiceId", "Invoice", ColumnType.REFERENCE)
                    .reference("invoice").notSortable()
                    .path((root, q, cb) -> root.get("id"))
                    .filter((spec, root, q, cb) -> promiseLinkPredicate("invoices", spec, root, q, cb))
                    .build(),
            // Backs the Promises tab on Payment Details: the promises that payment counts towards.
            ColumnDef.of("paymentId", "Payment", ColumnType.REFERENCE)
                    .reference("payment").notSortable()
                    .path((root, q, cb) -> root.get("id"))
                    .filter((spec, root, q, cb) -> promiseLinkPredicate("payments", spec, root, q, cb))
                    .build(),
            ColumnDef.of("createdAt", "Created", ColumnType.DATE).build());

    /** amount - fulfilled, floored at zero so an overpayment never reads as a negative debt. */
    static Expression<BigDecimal> promiseRemaining(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb) {
        Expression<BigDecimal> diff = cb.diff(
                root.<BigDecimal>get("amount"), root.<BigDecimal>get("fulfilledAmount"));
        return cb.<BigDecimal>selectCase()
                .when(cb.lessThan(diff, BigDecimal.ZERO), cb.literal(BigDecimal.ZERO))
                .otherwise(diff);
    }

    /**
     * A promise covers any number of invoices, and counts any number of payments, through link
     * tables ({@code association} is "invoices" or "payments"). So "is" means "linked to this one",
     * and "is empty" means none — for invoices, a general promise against the account. Matching
     * through an EXISTS subquery keeps a promise with several links to one row and one count.
     */
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

    // ---- products ------------------------------------------------------------------

    public static final TableSchema PRODUCTS = TableSchema.of("products", "name,asc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("name", "Name", ColumnType.TEXT).build(),
            ColumnDef.of("description", "Description", ColumnType.TEXT).notSortable().build(),
            ColumnDef.of("price", "Price", ColumnType.MONEY).build(),
            ColumnDef.of("active", "Active", ColumnType.BOOLEAN).build(),
            ColumnDef.of("createdAt", "Created", ColumnType.DATE).build());

    // ---- users ---------------------------------------------------------------------

    public static final TableSchema USERS = TableSchema.of("users", "username,asc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("username", "Username", ColumnType.TEXT).build(),
            ColumnDef.of("email", "Email", ColumnType.TEXT).build(),
            ColumnDef.of("fullName", "Full name", ColumnType.TEXT).build(),
            ColumnDef.of("active", "Active", ColumnType.BOOLEAN).build(),
            ColumnDef.of("roleName", "Role", ColumnType.TEXT)
                    .path(ColumnDef.nested("role", "name")).build(),
            ColumnDef.of("customerId", "Customer account", ColumnType.NUMBER).build(),
            ColumnDef.of("createdAt", "Created", ColumnType.DATE).build());

    // ---- roles ---------------------------------------------------------------------

    public static final TableSchema ROLES = TableSchema.of("roles", "name,asc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("name", "Name", ColumnType.TEXT).build(),
            ColumnDef.of("description", "Description", ColumnType.TEXT).notSortable().build());

    // ---- disputes ------------------------------------------------------------------

    public static final TableSchema DISPUTES = TableSchema.of("disputes", "createdAt,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("customerId", "Customer", ColumnType.REFERENCE).reference("customer").build(),
            ColumnDef.of("targetType", "Target", ColumnType.ENUM)
                    .enumValues(names(DisputeTargetType.class)).build(),
            ColumnDef.of("targetId", "Target id", ColumnType.NUMBER).build(),
            ColumnDef.of("status", "Status", ColumnType.ENUM)
                    .enumValues(names(DisputeStatus.class)).build(),
            ColumnDef.of("reason", "Reason", ColumnType.TEXT).notSortable().build(),
            ColumnDef.of("createdAt", "Opened", ColumnType.DATE).build(),
            ColumnDef.of("resolvedAt", "Resolved", ColumnType.DATE).build());

    // ---- notifications -------------------------------------------------------------

    public static final TableSchema NOTIFICATIONS = TableSchema.of("notifications", "createdAt,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("type", "Type", ColumnType.TEXT).build(),
            ColumnDef.of("title", "Title", ColumnType.TEXT).build(),
            ColumnDef.of("message", "Message", ColumnType.TEXT).notSortable().build(),
            ColumnDef.of("read", "Read", ColumnType.BOOLEAN).build(),
            ColumnDef.of("createdAt", "Received", ColumnType.DATE).build());
    // ---- email inbox -------------------------------------------------------------

    // The one table with its own page capacities: 10/25/50/100, defaulting to 25 (FR17, AC24).
    // Ordering is sentAt descending with the executor's id-descending tiebreak (AQ8). Columns are
    // declared notFilterable because this phase has no Inbox search or filters (NFR1).
    public static final TableSchema INBOX = TableSchema.of("inbox", "sentAt,desc",
            List.of(10, 25, 50, 100), 25,
            ColumnDef.of("id", "Id", ColumnType.NUMBER).notFilterable().build(),
            ColumnDef.of("subject", "Subject", ColumnType.TEXT)
                    .notFilterable().path(ColumnDef.nested("email", "subject")).build(),
            ColumnDef.of("senderDisplay", "From", ColumnType.TEXT)
                    .notFilterable().path(ColumnDef.nested("email", "senderDisplay")).build(),
            ColumnDef.of("read", "Read", ColumnType.BOOLEAN).notFilterable().build(),
            ColumnDef.of("sentAt", "Sent", ColumnType.DATE)
                    .notFilterable().path(ColumnDef.nested("email", "sentAt")).build());

    private static final Map<String, TableSchema> BY_ENTITY = buildIndex();

    private static Map<String, TableSchema> buildIndex() {
        Map<String, TableSchema> m = new LinkedHashMap<>();
        for (TableSchema s : List.of(INVOICES, PAYMENTS, CUSTOMERS, PROMISES, PRODUCTS,
                USERS, ROLES, DISPUTES, NOTIFICATIONS, INBOX)) {
            m.put(s.entity(), s);
        }
        return Map.copyOf(m);
    }

    public static TableSchema byEntity(String entity) {
        TableSchema s = BY_ENTITY.get(entity == null ? "" : entity.toLowerCase());
        if (s == null) {
            throw new BadRequestException("Unknown table: " + entity);
        }
        return s;
    }

    public static List<String> entities() {
        return List.copyOf(BY_ENTITY.keySet());
    }
}
