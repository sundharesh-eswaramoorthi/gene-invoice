package com.geneinvoice.common.query;

import com.geneinvoice.approval.ApprovalOperation;
import com.geneinvoice.approval.ChangeOrigin;
import com.geneinvoice.approval.PendingChangeScope;
import com.geneinvoice.approval.PendingStatus;
import com.geneinvoice.assignee.AssignedTo;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.rule.AutomationRule;
import com.geneinvoice.automation.rule.SuspendReason;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.automation.rule.TriggerKind;
import com.geneinvoice.automation.run.RunStatus;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.email.EmailDirection;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailStatus;
import com.geneinvoice.history.Temporal;
import com.geneinvoice.history.versions.TaskVersion;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.payment.PaymentStatus;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.task.Task;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.task.TaskStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TableSchemas {

    private TableSchemas() {}

    private static List<String> names(Class<? extends Enum<?>> e) {
        return Arrays.stream(e.getEnumConstants()).map(Enum::name).toList();
    }

    /** The region a record belongs to (B1); the options are the caller's own regions. */
    private static ColumnDef regionId() {
        return ColumnDef.of("regionId", "Region", ColumnType.REFERENCE)
                .reference("region").notSortable()
                .path(ColumnDef.referenceId("region")).build();
    }

    private static ColumnDef regionName() {
        return ColumnDef.of("regionName", "Region name", ColumnType.TEXT)
                .path(ColumnDef.nested("region", "name")).build();
    }

    /**
     * Whether a change waits for approval on the row's record (§5.11.9): a filter, never a sort, and as of a date
     * the changes waiting then. Its value on each row is filled by the list itself.
     */
    private static ColumnDef pendingApproval(String entityType) {
        return ColumnDef.of("pendingApproval", "Awaiting approval", ColumnType.BOOLEAN).notSortable()
                .path((root, q, cb) -> root.get("id"))
                .filter((spec, root, q, cb) -> PendingChangeScope.pendingApprovalFilter(entityType, spec, root, q, cb))
                .build();
    }

    public static final TableSchema INVOICES = TableSchema.of("invoices", "invoiceDate,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("invoiceNumber", "Invoice #", ColumnType.TEXT).build(),
            ColumnDef.of("customerId", "Customer", ColumnType.REFERENCE)
                    .reference("customer").notSortable()
                    .path(ColumnDef.referenceId("customer")).build(),
            ColumnDef.of("customerName", "Customer name", ColumnType.TEXT)
                    .path(ColumnDef.nested("customer", "name")).build(),
            ColumnDef.of("invoiceDate", "Date", ColumnType.DATE).build(),
            ColumnDef.of("dueDate", "Due date", ColumnType.DATE).build(),
            paymentTerm(),
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
            ColumnDef.of("notes", "Notes", ColumnType.TEXT).notSortable().build(),
            ColumnDef.of("salesPocUserId", "Sales POC", ColumnType.REFERENCE)
                    .reference("pocUser").pocRestricted().notSortable()
                    .path(ColumnDef.referenceId("salesPoc")).build(),
            ColumnDef.of("salesPocName", "Sales POC name", ColumnType.TEXT)
                    .pocRestricted().path(ColumnDef.nested("salesPoc", "fullName")).build(),
            regionId(),
            regionName(),
            pendingApproval("INVOICE"),
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
                    .pocRestricted().path(ColumnDef.nested("collectionPoc", "fullName")).build(),
            regionId(),
            regionName(),
            pendingApproval("PAYMENT"));

    public static final TableSchema CUSTOMERS = TableSchema.of("customers", "name,asc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("name", "Name", ColumnType.TEXT).build(),
            ColumnDef.of("phone", "Phone", ColumnType.TEXT).build(),
            ColumnDef.of("email", "Email", ColumnType.TEXT).build(),
            ColumnDef.of("address", "Address", ColumnType.TEXT).notSortable().build(),
            paymentTerm(),
            ColumnDef.of("creditBalance", "Credit balance", ColumnType.MONEY).build(),
            ColumnDef.of("outstanding", "Outstanding", ColumnType.MONEY)
                    .path(TableSchemas::customerOutstanding).build(),
            // What placeholders and promises already call Customer.OverdueAmount (PC-11); as of a date, then.
            ColumnDef.of("overdueAmount", "Overdue amount", ColumnType.MONEY)
                    .path(TableSchemas::customerOverdue).build(),
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
            regionId(),
            regionName(),
            ColumnDef.of("createdAt", "Created", ColumnType.DATE).build());

    /** The customer's balance on invoices not cancelled; as of a date, over the invoices as they were. */
    private static Expression<BigDecimal> customerOutstanding(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb) {
        return Temporal.subquery(q, BigDecimal.class, Invoice.class, cb, (inv, sq, where) -> {
            where.add(cb.equal(inv.get("customer").get("id"), root.get("id")));
            where.add(cb.notEqual(inv.get("status"), InvoiceStatus.CANCELLED));
            return cb.coalesce(
                    cb.sum(cb.diff(inv.<BigDecimal>get("total"), inv.<BigDecimal>get("paidAmount"))),
                    cb.literal(BigDecimal.ZERO));
        });
    }

    /**
     * The customer's balance on invoices overdue today (the list's "today", so a past date's as of a date), over the
     * invoices as they were then.
     */
    private static Expression<BigDecimal> customerOverdue(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb) {
        return Temporal.subquery(q, BigDecimal.class, Invoice.class, cb, (inv, sq, where) -> {
            where.add(cb.equal(inv.get("customer").get("id"), root.get("id")));
            where.add(invoiceOverdue(inv, cb, InvoiceDates.today()));
            return cb.coalesce(
                    cb.sum(cb.diff(inv.<BigDecimal>get("total"), inv.<BigDecimal>get("paidAmount"))),
                    cb.literal(BigDecimal.ZERO));
        });
    }

    /** A customer's or an invoice's payment term (PC-11); a filter over the entity's field as it is (or was). */
    private static ColumnDef paymentTerm() {
        return ColumnDef.of("paymentTerm", "Payment term", ColumnType.ENUM)
                .enumValues(names(PaymentTerm.class)).build();
    }

    /** Whether the customer has (or lacks) a seat of this type, held by the people asked about; as of a date, then. */
    private static Predicate pocSeatPredicate(FilterSpec spec, Root<?> root, CriteriaQuery<?> q,
                                              CriteriaBuilder cb, PocType type) {
        Subquery<Long> sq = Temporal.subquery(q, Long.class, CustomerPoc.class, cb, (seat, s, where) -> {
            Predicate base = cb.and(
                    cb.equal(seat.get("customer").get("id"), root.get("id")),
                    cb.equal(seat.get("pocType"), type));
            where.add(switch (spec.operator()) {
                case IS_EMPTY, IS_NOT_EMPTY -> base;
                case EQ, NEQ -> cb.and(base, cb.equal(seat.get("user").get("id"), asLong(spec.first())));
                case IN -> cb.and(base, seat.get("user").get("id")
                        .in(spec.values().stream().map(TableSchemas::asLong).toList()));
                default -> throw new BadRequestException(
                        "Operator " + spec.operator().wire() + " is not valid for a POC seat column");
            });
            return cb.literal(1L);
        });
        return switch (spec.operator()) {
            case IS_EMPTY, NEQ -> cb.not(cb.exists(sq));
            default -> cb.exists(sq);
        };
    }

    private static Long asLong(String raw) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Expected an id but got: " + raw);
        }
    }

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
            ColumnDef.of("assigneeUserId", "Assigned to", ColumnType.REFERENCE)
                    .reference("staffUser").pocRestricted().notSortable()
                    .path((root, q, cb) -> root.get("id"))
                    .filter((spec, root, q, cb) -> AssignedTo.filter(AssignedTo.PROMISE, spec, root, q, cb))
                    .build(),
            regionId(),
            regionName(),
            pendingApproval("PROMISE"),
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

    /** Whether the promise is linked to (or not to) the invoices or payments asked about; as of a date, then. */
    private static Predicate promiseLinkPredicate(String association, FilterSpec spec, Root<?> root,
                                                  CriteriaQuery<?> q, CriteriaBuilder cb) {
        Subquery<Long> sq = Temporal.subquery(q, Long.class, PaymentPromise.class, cb, (promise, s, where) -> {
            From<?, ?> linked = Temporal.join(promise, association, JoinType.INNER, cb);
            Predicate base = cb.equal(promise.get("id"), root.get("id"));
            where.add(switch (spec.operator()) {
                case IS_EMPTY, IS_NOT_EMPTY -> base;
                case EQ, NEQ -> cb.and(base, cb.equal(linked.get("id"), asLong(spec.first())));
                case IN -> cb.and(base, linked.get("id")
                        .in(spec.values().stream().map(TableSchemas::asLong).toList()));
                default -> throw new BadRequestException(
                        "Operator " + spec.operator().wire() + " is not valid for the " + association + " column");
            });
            return cb.literal(1L);
        });
        return switch (spec.operator()) {
            case IS_EMPTY, NEQ -> cb.not(cb.exists(sq));
            default -> cb.exists(sq);
        };
    }

    public static final TableSchema PRODUCTS = TableSchema.of("products", "name,asc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("name", "Name", ColumnType.TEXT).build(),
            ColumnDef.of("description", "Description", ColumnType.TEXT).notSortable().build(),
            ColumnDef.of("price", "Price", ColumnType.MONEY).build(),
            ColumnDef.of("active", "Active", ColumnType.BOOLEAN).build(),
            ColumnDef.of("createdAt", "Created", ColumnType.DATE).build());

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

    public static final TableSchema ROLES = TableSchema.of("roles", "name,asc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("name", "Name", ColumnType.TEXT).build(),
            ColumnDef.of("description", "Description", ColumnType.TEXT).notSortable().build());

    public static final TableSchema REGIONS = TableSchema.of("regions", "code,asc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("code", "Code", ColumnType.TEXT).build(),
            ColumnDef.of("name", "Name", ColumnType.TEXT).build(),
            ColumnDef.of("active", "Active", ColumnType.BOOLEAN).build(),
            ColumnDef.of("defaultRegion", "Default", ColumnType.BOOLEAN).build(),
            ColumnDef.of("createdAt", "Created", ColumnType.DATE).build());

    public static final TableSchema DISPUTES = TableSchema.of("disputes", "createdAt,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("customerId", "Customer", ColumnType.REFERENCE).reference("customer").build(),
            ColumnDef.of("targetType", "Target", ColumnType.ENUM)
                    .enumValues(names(DisputeTargetType.class)).build(),
            ColumnDef.of("targetId", "Target id", ColumnType.NUMBER).build(),
            ColumnDef.of("status", "Status", ColumnType.ENUM)
                    .enumValues(names(DisputeStatus.class)).build(),
            ColumnDef.of("reason", "Reason", ColumnType.TEXT).notSortable().build(),
            // Raised by a rule for staff to review; a customer login never sees one, so never this column either.
            ColumnDef.of("internal", "Internal", ColumnType.BOOLEAN).pocRestricted().build(),
            ColumnDef.of("assigneeUserId", "Assigned to", ColumnType.REFERENCE)
                    .reference("staffUser").pocRestricted().notSortable()
                    .path((root, q, cb) -> root.get("id"))
                    .filter((spec, root, q, cb) -> AssignedTo.filter(AssignedTo.DISPUTE, spec, root, q, cb))
                    .build(),
            regionId(),
            regionName(),
            pendingApproval("DISPUTE"),
            ColumnDef.of("createdAt", "Opened", ColumnType.DATE).build(),
            ColumnDef.of("resolvedAt", "Resolved", ColumnType.DATE).build());

    public static final TableSchema TASKS = TableSchema.of("tasks", "dueDate,asc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("title", "Title", ColumnType.TEXT).build(),
            ColumnDef.of("status", "Status", ColumnType.ENUM)
                    .enumValues(names(TaskStatus.class)).build(),
            ColumnDef.of("dueDate", "Due", ColumnType.DATE).build(),
            // Derived from the clock, like an invoice's (D3): a filter, not a value.
            ColumnDef.of("overdue", "Overdue", ColumnType.BOOLEAN).notSortable()
                    .path((root, q, cb) -> root.get("id"))
                    .filter((spec, root, q, cb) -> taskOverduePredicate(spec, root, cb))
                    .build(),
            ColumnDef.of("entityType", "On", ColumnType.ENUM)
                    .enumValues(names(TaskEntityType.class)).build(),
            ColumnDef.of("entityId", "Record id", ColumnType.NUMBER).build(),
            ColumnDef.of("entityLabel", "Record", ColumnType.TEXT).build(),
            ColumnDef.of("customerId", "Customer", ColumnType.REFERENCE)
                    .reference("customer").notSortable().build(),
            ColumnDef.of("assigneeUserId", "Assigned to", ColumnType.REFERENCE)
                    .reference("staffUser").notSortable()
                    .path((root, q, cb) -> root.get("id"))
                    .filter((spec, root, q, cb) -> AssignedTo.filter(AssignedTo.TASK, spec, root, q, cb))
                    .build(),
            ColumnDef.of("origin", "Made by", ColumnType.ENUM)
                    .enumValues(List.of(Task.ORIGIN_USER, Task.ORIGIN_AUTOMATION))
                    .path(TableSchemas::taskOrigin).build(),
            ColumnDef.of("ruleId", "Rule", ColumnType.NUMBER).build(),
            regionId(),
            regionName(),
            ColumnDef.of("createdAt", "Created", ColumnType.DATE).build(),
            ColumnDef.of("completedAt", "Completed", ColumnType.DATE).build(),
            ColumnDef.of("updatedAt", "Updated", ColumnType.DATE).build());

    private static Predicate taskOverduePredicate(FilterSpec spec, Root<?> root, CriteriaBuilder cb) {
        Predicate overdue = cb.and(
                root.get("status").in(TaskStatus.OPEN, TaskStatus.IN_PROGRESS),
                cb.lessThan(root.<LocalDate>get("dueDate"), Today.date()));
        return asBoolean(spec.first())
                ? overdue
                : cb.or(cb.isNull(root.get("dueDate")), cb.not(overdue));
    }

    /**
     * Who made the task. A version row's own {@code origin} is the provenance of the version, so a task's origin is
     * kept there under another name (§3.5, see {@link TaskVersion}).
     */
    private static Expression<?> taskOrigin(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb) {
        return root.get(root.getJavaType() == TaskVersion.class ? TaskVersion.TASK_ORIGIN : "origin");
    }

    /**
     * Pending changes (§5.11.9). The column keys are the list row's own keys, so a sort key is always a cell; who
     * asked filters on {@code requestedByUserId}. Which changes a list holds (visibility, the view, a date) is the
     * scope's business, never a filter.
     */
    public static final TableSchema APPROVALS = TableSchema.of("approvals", "requestedAt,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            approvalStatus().filter(TableSchemas::approvalStatusPredicate).build(),
            ColumnDef.of("operation", "Change", ColumnType.ENUM)
                    .enumValues(names(ApprovalOperation.class)).build(),
            ColumnDef.of("entityType", "On", ColumnType.ENUM)
                    .enumValues(List.of("INVOICE", "PAYMENT", "PROMISE", "DISPUTE", "REGION")).build(),
            ColumnDef.of("entityId", "Record id", ColumnType.NUMBER).build(),
            ColumnDef.of("entityLabel", "Record", ColumnType.TEXT).build(),
            ColumnDef.of("customerId", "Customer", ColumnType.REFERENCE)
                    .reference("customer").notSortable().build(),
            ColumnDef.of("customerName", "Customer name", ColumnType.TEXT).build(),
            ColumnDef.of("regionId", "Region", ColumnType.REFERENCE)
                    .reference("region").path(ColumnDef.referenceId("region")).build(),
            ColumnDef.of("amount", "Amount", ColumnType.MONEY).build(),
            ColumnDef.of("threshold", "Limit", ColumnType.MONEY).path(ColumnDef.attr("thresholdAmount")).build(),
            ColumnDef.of("summary", "Summary", ColumnType.TEXT).notSortable().build(),
            ColumnDef.of("requestedByUserId", "Requested by", ColumnType.REFERENCE)
                    .reference("staffUser").notSortable().path(ColumnDef.attr("makerUserId")).build(),
            ColumnDef.of("origin", "Made by", ColumnType.ENUM)
                    .enumValues(names(ChangeOrigin.class)).build(),
            ColumnDef.of("requestedAt", "Requested", ColumnType.DATE).build(),
            ColumnDef.of("decidedAt", "Decided", ColumnType.DATE).build());

    private static ColumnDef.Builder approvalStatus() {
        return ColumnDef.of("status", "Status", ColumnType.ENUM).enumValues(names(PendingStatus.class));
    }

    /**
     * A change's status as the list answers it (B3): live, its status now; as of a date, its status then. Only the
     * changes waiting then are listed as of a date, so each one's status then is PENDING, whatever became of it.
     */
    private static Predicate approvalStatusPredicate(FilterSpec spec, Root<?> root, CriteriaQuery<?> q,
                                                     CriteriaBuilder cb) {
        if (!AsOf.active()) return FilterPredicates.build(spec, approvalStatus().build(), root, q, cb);
        List<Object> values = spec.values().stream()
                .map(v -> (Object) ValueCoercion.coerce(PendingStatus.class, v, "status")).toList();
        boolean pendingMatches = switch (spec.operator()) {
            case EQ, IN -> values.contains(PendingStatus.PENDING);
            case NEQ, NOT_IN -> !values.contains(PendingStatus.PENDING);
            default -> throw new BadRequestException("Operator " + spec.operator().wire()
                    + " is not valid for column status (ENUM)");
        };
        return pendingMatches ? cb.conjunction() : cb.disjunction();
    }

    /**
     * Rules (§6.14). Which rules a list holds (who may see them, the regions asked about) is the scope's business;
     * {@code status} is worked out from whether the rule is on and suspended, so it filters but does not sort.
     */
    public static final TableSchema AUTOMATION_RULES = TableSchema.of("automation-rules", "name,asc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("name", "Rule", ColumnType.TEXT).build(),
            ColumnDef.of("enabled", "On", ColumnType.BOOLEAN).build(),
            ColumnDef.of("status", "Status", ColumnType.ENUM).notSortable()
                    .enumValues(List.of("ACTIVE", "DISABLED", "SUSPENDED"))
                    .path((root, q, cb) -> root.get("id"))
                    .filter((spec, root, q, cb) -> ruleStatusPredicate(spec, root, cb))
                    .build(),
            ColumnDef.of("suspendedReason", "Why suspended", ColumnType.ENUM)
                    .enumValues(names(SuspendReason.class)).build(),
            ColumnDef.of("triggerKind", "Starts", ColumnType.ENUM).enumValues(names(TriggerKind.class)).build(),
            ColumnDef.of("entityType", "On records", ColumnType.ENUM).enumValues(names(TriggerEntity.class)).build(),
            ColumnDef.of("ownerUserId", "Runs as", ColumnType.REFERENCE)
                    .reference("staffUser").notSortable().build(),
            ColumnDef.of("regionIds", "Regions", ColumnType.REFERENCE)
                    .reference("region").notSortable()
                    .path((root, q, cb) -> root.get("id"))
                    .filter((spec, root, q, cb) -> ruleRegionPredicate(spec, root, q, cb))
                    .build(),
            ColumnDef.of("lastRunStatus", "Last run", ColumnType.ENUM).enumValues(names(RunStatus.class)).build(),
            ColumnDef.of("lastRunAt", "Last run at", ColumnType.DATE).build(),
            ColumnDef.of("nextRunAt", "Next run", ColumnType.DATE).build(),
            ColumnDef.of("createdAt", "Created", ColumnType.DATE).build(),
            ColumnDef.of("updatedAt", "Updated", ColumnType.DATE).build());

    /** ACTIVE: on and not suspended; DISABLED: off and not suspended; SUSPENDED: suspended, on or off. */
    private static Predicate ruleStatusPredicate(FilterSpec spec, Root<?> root, CriteriaBuilder cb) {
        List<Predicate> any = new ArrayList<>();
        for (String raw : spec.values()) {
            String status = raw == null ? "" : raw.trim().toUpperCase();
            Predicate notSuspended = cb.isNull(root.get("suspendedReason"));
            any.add(switch (status) {
                case "ACTIVE" -> cb.and(cb.isTrue(root.get("enabled")), notSuspended);
                case "DISABLED" -> cb.and(cb.isFalse(root.get("enabled")), notSuspended);
                case "SUSPENDED" -> cb.isNotNull(root.get("suspendedReason"));
                default -> throw new BadRequestException("Invalid value for column status: '" + raw
                        + "' (expected ACTIVE, DISABLED or SUSPENDED)");
            });
        }
        Predicate matches = cb.or(any.toArray(new Predicate[0]));
        return switch (spec.operator()) {
            case EQ, IN -> matches;
            case NEQ, NOT_IN -> cb.not(matches);
            default -> throw new BadRequestException(
                    "Operator " + spec.operator().wire() + " is not valid for column status (ENUM)");
        };
    }

    /** Whether the rule is restricted to (one of) the regions asked about, or to none at all (§3.6). */
    private static Predicate ruleRegionPredicate(FilterSpec spec, Root<?> root, CriteriaQuery<?> q,
                                                 CriteriaBuilder cb) {
        if (spec.operator() == FilterOperator.IS_EMPTY) return cb.isEmpty(root.get("regionIds"));
        if (spec.operator() == FilterOperator.IS_NOT_EMPTY) return cb.isNotEmpty(root.get("regionIds"));
        List<Long> ids = spec.values().stream().map(TableSchemas::asLong).toList();
        Subquery<Long> listed = q.subquery(Long.class);
        Root<AutomationRule> rule = listed.from(AutomationRule.class);
        Join<AutomationRule, Long> region = rule.join("regionIds");
        listed.select(rule.get("id")).where(cb.equal(rule.get("id"), root.get("id")), region.in(ids));
        return switch (spec.operator()) {
            case EQ, IN -> cb.exists(listed);
            case NEQ -> cb.not(cb.exists(listed));
            default -> throw new BadRequestException(
                    "Operator " + spec.operator().wire() + " is not valid for column regionIds");
        };
    }

    /** A rule's runs (§6.14), newest first; which rule's is the endpoint's business. */
    public static final TableSchema AUTOMATION_RUNS = TableSchema.of("automation-runs", "createdAt,desc",
            ColumnDef.of("id", "Run", ColumnType.NUMBER).build(),
            ColumnDef.of("status", "Status", ColumnType.ENUM).enumValues(names(RunStatus.class)).build(),
            ColumnDef.of("triggerType", "Started by", ColumnType.ENUM)
                    .enumValues(List.of(AutomationEvent.CREATED, AutomationEvent.UPDATED, AutomationEvent.CHILDREN,
                            AutomationEvent.SCHEDULED, AutomationEvent.MANUAL, AutomationEvent.REPLAY)).build(),
            ColumnDef.of("entityType", "Record type", ColumnType.ENUM).enumValues(names(TriggerEntity.class)).build(),
            ColumnDef.of("entityId", "Record id", ColumnType.NUMBER).build(),
            ColumnDef.of("scheduledFor", "Scheduled for", ColumnType.DATE).build(),
            ColumnDef.of("matched", "Matched", ColumnType.NUMBER).build(),
            ColumnDef.of("done", "Done", ColumnType.NUMBER).build(),
            ColumnDef.of("skipped", "Skipped", ColumnType.NUMBER).build(),
            ColumnDef.of("failed", "Failed", ColumnType.NUMBER).build(),
            ColumnDef.of("truncated", "Cut short", ColumnType.BOOLEAN).build(),
            ColumnDef.of("createdAt", "Queued", ColumnType.DATE).build(),
            ColumnDef.of("startedAt", "Started", ColumnType.DATE).build(),
            ColumnDef.of("finishedAt", "Finished", ColumnType.DATE).build());

    public static final TableSchema NOTIFICATIONS = TableSchema.of("notifications", "createdAt,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("type", "Type", ColumnType.TEXT).build(),
            ColumnDef.of("title", "Title", ColumnType.TEXT).build(),
            ColumnDef.of("message", "Message", ColumnType.TEXT).notSortable().build(),
            ColumnDef.of("read", "Read", ColumnType.BOOLEAN).build(),
            ColumnDef.of("createdAt", "Received", ColumnType.DATE).build());

    public static final TableSchema INBOX = TableSchema.of("inbox", "occurredAt,desc",
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

    private static Map<String, TableSchema> buildIndex() {
        Map<String, TableSchema> m = new LinkedHashMap<>();
        for (TableSchema s : List.of(INVOICES, PAYMENTS, CUSTOMERS, PROMISES, PRODUCTS,
                USERS, ROLES, REGIONS, DISPUTES, TASKS, APPROVALS, AUTOMATION_RULES, AUTOMATION_RUNS,
                NOTIFICATIONS, INBOX)) {
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
