package com.geneinvoice.task;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.common.query.ColumnType;
import com.geneinvoice.common.query.FilterSpec;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.region.RegionPredicates;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * The task list as a table.
 *
 * <p>It lives here rather than in TableSchemas because a feature that publishes a new table owns
 * its own schema: registering it is one line in SchemaRegistry and not another edit to the file
 * every other feature is also queueing behind (A6, B1, B2 INTEGRATION).
 *
 * <p>Task is classified VIA_CUSTOMER_ID, so TableQueryExecutor ANDs in the caller's own regions at
 * VIEW level before this list has said anything: the list is narrowed to the branches the caller
 * works in by the axis and not by a scope argument anybody could forget (A6, B1).
 */
public final class TaskSchemas {

    private TaskSchemas() {}

    private static List<String> names(Class<? extends Enum<?>> e) {
        return Arrays.stream(e.getEnumConstants()).map(Enum::name).toList();
    }

    public static final TableSchema TASKS = TableSchema.of("tasks", Task.class, "dueDate,asc",
            // Mandatory: inScope() and every orderBy tie-break resolve through it (A6).
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("title", "Title", ColumnType.TEXT).build(),
            ColumnDef.of("entityType", "About", ColumnType.ENUM)
                    .enumValues(names(TaskEntityType.class)).build(),
            ColumnDef.of("entityId", "Record id", ColumnType.NUMBER).notSortable().build(),
            ColumnDef.of("entityLabel", "Record", ColumnType.TEXT).build(),
            ColumnDef.of("customerId", "Customer", ColumnType.REFERENCE)
                    .reference("customer").notSortable().build(),
            // A task keeps a bare customer_id with no association to walk, so these two are the
            // dispute pair verbatim rather than a third copy of the same two shapes. The path is
            // only ever used for sorting and both columns are notSortable, so regionId resolves to
            // the id it has and regionName is a correlated scalar subquery (A6, B1).
            ColumnDef.of("regionId", "Region", ColumnType.REFERENCE)
                    .reference("region").pocRestricted().notSortable()
                    .path((root, q, cb) -> root.get("customerId"))
                    .filter(RegionPredicates::disputeRegionFilter)
                    .build(),
            ColumnDef.of("regionName", "Region name", ColumnType.TEXT).pocRestricted().notSortable()
                    .path(RegionPredicates::customerRegionName).build(),
            ColumnDef.of("dueDate", "Due", ColumnType.DATE).build(),
            ColumnDef.of("status", "Status", ColumnType.ENUM)
                    .enumValues(names(TaskStatus.class)).build(),
            // Several people can own a task, so the filter is an EXISTS over the join table — the
            // pocSeatPredicate shape, INCLUDING its default -> BadRequestException arm. A custom
            // resolver and not a `nested` path on purpose: task assignees are the first to-many
            // association any registered schema has, and a shared LEFT join is right for a to-one
            // and wrong for a to-many, which is the constraint Conditions' javadoc names (A6, A2).
            ColumnDef.of("assigneeUserId", "Assigned to", ColumnType.REFERENCE)
                    .reference("pocUser").notSortable()
                    .path((root, q, cb) -> root.get("id"))
                    .filter(TaskSchemas::taskAssigneePredicate).build(),
            // Overdue is derived from the clock rather than stored, exactly as on an invoice (D3),
            // which is also what makes an as-of task list right for free once B3 makes
            // InvoiceDates.today() as-of aware (A6, B3).
            ColumnDef.of("overdue", "Overdue", ColumnType.BOOLEAN).notSortable()
                    .path((root, q, cb) -> root.get("id"))
                    .filter((spec, root, q, cb) -> taskOverduePredicate(spec, root, cb)).build(),
            ColumnDef.of("createdByRuleId", "Created by rule", ColumnType.NUMBER).build(),
            ColumnDef.of("createdAt", "Created", ColumnType.DATE).build());

    /**
     * Late and not finished. Shared by the filter chip and by the summary tile so the two can
     * never disagree — the same reason ApprovalSchemas.existsOpenPending is one shape (A6, B2).
     */
    public static Predicate taskOverdue(From<?, ?> root, CriteriaBuilder cb, LocalDate today) {
        return cb.and(
                cb.lessThan(root.<LocalDate>get("dueDate"), today),
                root.get("status").in(TaskStatus.OPEN, TaskStatus.IN_PROGRESS));
    }

    /**
     * Due in the next seven days and not finished, which is what the "due this week" tile counts.
     * A task already overdue is not also due this week: the two tiles are read side by side and
     * counting a row in both would make them add up to more than the list (A6).
     */
    public static Predicate taskDueWithin(From<?, ?> root, CriteriaBuilder cb,
                                          LocalDate today, int days) {
        return cb.and(
                cb.between(root.<LocalDate>get("dueDate"), today, today.plusDays(days)),
                root.get("status").in(TaskStatus.OPEN, TaskStatus.IN_PROGRESS));
    }

    private static Predicate taskOverduePredicate(FilterSpec spec, Root<?> root, CriteriaBuilder cb) {
        Predicate overdue = taskOverdue(root, cb, InvoiceDates.today());
        // A task with no due date is not overdue, and "overdue:eq:false" must still return it —
        // cb.not() over a null comparison would drop it silently, the overduePredicate rule (D3).
        return asBoolean(spec.first())
                ? overdue
                : cb.or(cb.isNull(root.get("dueDate")), cb.not(overdue));
    }

    /**
     * pocSeatPredicate with CustomerPoc -> TaskAssignee and seat.customer.id -> seat.task.id: the
     * same five operator arms and the same default throw, so an operator a REFERENCE column does
     * not offer is refused in the filter bar's own words rather than silently matching (A6).
     */
    private static Predicate taskAssigneePredicate(FilterSpec spec, Root<?> root,
                                                   CriteriaQuery<?> q, CriteriaBuilder cb) {
        Subquery<Long> sq = q.subquery(Long.class);
        Root<TaskAssignee> seat = sq.from(TaskAssignee.class);
        sq.select(cb.literal(1L));
        Predicate base = cb.equal(seat.get("task").get("id"), root.get("id"));

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
                sq.where(cb.and(base, cb.equal(seat.get("userId"), asLong(spec.first()))));
                yield cb.exists(sq);
            }
            case NEQ -> {
                sq.where(cb.and(base, cb.equal(seat.get("userId"), asLong(spec.first()))));
                yield cb.not(cb.exists(sq));
            }
            case IN -> {
                sq.where(cb.and(base, seat.get("userId")
                        .in(spec.values().stream().map(TaskSchemas::asLong).toList())));
                yield cb.exists(sq);
            }
            default -> throw new BadRequestException(
                    "Operator " + spec.operator().wire() + " is not valid for a task assignee column");
        };
    }

    private static boolean asBoolean(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.equalsIgnoreCase("true")) return true;
        if (v.equalsIgnoreCase("false")) return false;
        throw new BadRequestException("Expected true or false but got: " + raw);
    }

    private static Long asLong(String raw) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Expected an id but got: " + raw);
        }
    }
}
