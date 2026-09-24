package com.geneinvoice.history;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.asof.AsOf;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.common.query.ColumnType;
import com.geneinvoice.common.query.FilterSpec;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.customer.CustomerHistory;
import com.geneinvoice.dispute.DisputeHistory;
import com.geneinvoice.invoice.InvoiceHistory;
import com.geneinvoice.payment.PaymentHistory;
import com.geneinvoice.poc.CustomerPocHistory;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.promise.PromiseHistory;
import com.geneinvoice.promise.PromiseInvoiceHistory;
import com.geneinvoice.promise.PromisePaymentHistory;
import com.geneinvoice.region.RegionSchemas;
import com.geneinvoice.task.TaskHistory;
import com.geneinvoice.task.TaskSchemas;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * THE SIX AS-OF TWINS: the same six lists, over the mirror tables, asked as of a date.
 *
 * <p>Each one is its live schema with a handful of ColumnDefs replaced and EVERY OTHER COLUMN THE
 * SAME OBJECT — money, status, dates, notes, ids, overdue, balance, remainingAmount,
 * statusOverridden, approvalPending. That is not an economy, it is the invariant the feature is
 * bounded by: as-of queryability is live queryability, because both are driven by one
 * {@link TableSchema} object graph. A column is filterable and sortable as of a date exactly when
 * it is filterable and sortable today, and AsOfSchemaCheck refuses to boot if that ever stops
 * being true (B3).
 *
 * <p>WHY THE CONSTANTS ARE HERE AND THE HELPER IS NOT. {@link TableSchemas#asOf} takes the mirror
 * as a {@code Class<?>} and lives in common.query, which must never import history; these
 * constants name InvoiceHistory and CustomerHistory, so they cannot. The split is the binding
 * package rule and not a matter of taste.
 *
 * <p>THEY ARE DELIBERATELY NOT REGISTERED in SchemaRegistry and are not published as separate
 * table entities. Each twin keeps the LIVE entity() string — "invoices", "customers" — so that
 * appliedFilters, the locked chips and every requireFilterable message are byte-identical between
 * a live list and the same list as of a date; registering one would replace the live schema under
 * that same key. They reach AsOfSchemaCheck directly instead, and the wire through
 * {@link HistoryAsOfSupport} (B3).
 */
public final class HistorySchemas {

    private HistorySchemas() {
    }

    /**
     * The interval clause for a correlated read of a mirror: the one version of each row that was
     * current at the date being answered. {@code instantOrNow} and not {@code instant}, because
     * these lambdas are also resolved with nothing open — AsOfSchemaCheck does it at boot — and at
     * the real now AsOf.at picks exactly the open rows, which is the same answer the live column
     * gives (B3).
     */
    private static PredicateFactory inForce() {
        return AsOf.at(AsOfContext.instantOrNow());
    }

    /**
     * The same column, saying only that its VALUE is not reconstructed at the as-of date. Built
     * from the live ColumnDef rather than re-declared, so type, sortability, filterability,
     * enumValues, referenceKind and pocRestricted cannot drift from it — which is precisely what
     * AsOfSchemaCheck compares, and asOfMode is the one field it allows to differ (B3).
     */
    private static ColumnDef current(ColumnDef live) {
        return new ColumnDef(live.name(), live.label(), live.type(), live.sortable(),
                live.filterable(), live.enumValues(), live.referenceKind(), live.pocRestricted(),
                live.path(), live.customFilter(), "CURRENT");
    }

    /**
     * A flat REFERENCE column: the foreign key the mirror row already carries, with no join at
     * all. The live spelling walks an association to read its id, which a mirror has not got —
     * joining a past invoice to the LIVE account would render today's name, terms and region on a
     * snapshot, which is the exact leak as-of exists to close (B3).
     */
    private static ColumnDef flat(ColumnDef live, String attribute) {
        return ColumnDef.of(live.name(), live.label(), live.type())
                .reference(live.referenceKind()).notSortable()
                .path(ColumnDef.attr(attribute)).build();
    }

    /**
     * A denormalised as-of LABEL, read off the mirror's own column instead of through a live
     * association.
     *
     * <p>MARKED CURRENT ON INSTRUCTION AND NOT ON THE EVIDENCE, and that is written down rather
     * than glossed. UNITS-B3 requires {@code .current()} on these four columns and pins a test
     * name that says so; B3-as-of.md:69 says the opposite — that customerName, salesPocName and
     * collectionPocName are the three labels that ARE as-of. The measured fact is neither: the
     * column holds the label as it stood at THIS ROW'S last change, so an account renamed after
     * its last invoice moved shows the older name. CURRENT is the wire's way of telling a client
     * not to read the label as an answer about the as-of date, which is the honest instruction
     * even though "current" is not literally what the value is (B3).
     */
    private static ColumnDef label(ColumnDef live, String attribute) {
        ColumnDef.Builder b = ColumnDef.of(live.name(), live.label(), live.type())
                .path(ColumnDef.attr(attribute)).current();
        if (live.pocRestricted()) b.pocRestricted();
        if (!live.sortable()) b.notSortable();
        return b.build();
    }

    // ---- invoices ------------------------------------------------------------------------------

    public static final TableSchema INVOICES = TableSchemas.asOf(
            TableSchemas.INVOICES, InvoiceHistory.class, Map.of(
                    "customerId", flat(TableSchemas.INVOICES.require("customerId"), "customerId"),
                    "customerName", label(TableSchemas.INVOICES.require("customerName"), "customerName"),
                    "salesPocName", label(TableSchemas.INVOICES.require("salesPocName"), "salesPocName"),
                    "regionId", RegionSchemas.AS_OF_REGION_ID,
                    "regionName", RegionSchemas.AS_OF_REGION_NAME));

    // ---- customers -----------------------------------------------------------------------------

    public static final TableSchema CUSTOMERS = TableSchemas.asOf(
            TableSchemas.CUSTOMERS, CustomerHistory.class, Map.of(
                    // What the account owed THEN: the same correlated sum, taken over the invoice
                    // mirror with the interval clause inside it, so a payment made in February
                    // does not reduce a January balance (B3).
                    "outstanding", ColumnDef.of("outstanding", "Outstanding", ColumnType.MONEY)
                            .path((root, q, cb) -> TableSchemas.customerOutstanding(
                                    InvoiceHistory.class, inForce(), root, q, cb))
                            .build(),
                    // Who sat on the account THEN. This is the half of "my book as of January"
                    // that a person can filter by, and it reads the seat mirror for the same
                    // reason ScopeResolver's two seat subqueries do (B3).
                    "successPocUserId", seat(TableSchemas.CUSTOMERS.require("successPocUserId"),
                            PocType.SUCCESS),
                    "collectionPocUserId", seat(TableSchemas.CUSTOMERS.require("collectionPocUserId"),
                            PocType.COLLECTION),
                    "regionId", RegionSchemas.AS_OF_REGION_ID,
                    "regionName", RegionSchemas.AS_OF_REGION_NAME));

    private static ColumnDef seat(ColumnDef live, PocType type) {
        return ColumnDef.of(live.name(), live.label(), live.type())
                .reference(live.referenceKind()).pocRestricted().notSortable()
                .path((root, q, cb) -> root.get("id"))
                .filter((spec, root, q, cb) -> TableSchemas.pocSeatPredicate(
                        CustomerPocHistory.class, inForce(), spec, root, q, cb, type))
                .build();
    }

    // ---- payments ------------------------------------------------------------------------------

    public static final TableSchema PAYMENTS = TableSchemas.asOf(
            TableSchemas.PAYMENTS, PaymentHistory.class, Map.of(
                    "customerId", flat(TableSchemas.PAYMENTS.require("customerId"), "customerId"),
                    "customerName", label(TableSchemas.PAYMENTS.require("customerName"), "customerName"),
                    "collectionPocName", label(TableSchemas.PAYMENTS.require("collectionPocName"),
                            "collectionPocName"),
                    "regionId", RegionSchemas.AS_OF_REGION_ID,
                    "regionName", RegionSchemas.AS_OF_REGION_NAME));

    // ---- promises ------------------------------------------------------------------------------

    public static final TableSchema PROMISES = TableSchemas.asOf(
            TableSchemas.PROMISES, PromiseHistory.class, Map.of(
                    "customerId", flat(TableSchemas.PROMISES.require("customerId"), "customerId"),
                    "customerName", label(TableSchemas.PROMISES.require("customerName"), "customerName"),
                    "collectionPocName", label(TableSchemas.PROMISES.require("collectionPocName"),
                            "collectionPocName"),
                    // Which invoices and payments this promise covered THEN. The live column walks
                    // a @ManyToMany; the mirrors of those two join tables are FLAT, so this is
                    // HistorySchemas' own predicate and not a join parameterised out of existence,
                    // which is why TableSchemas.promiseLinkPredicate stayed private (B3).
                    "invoiceId", link(TableSchemas.PROMISES.require("invoiceId"),
                            PromiseInvoiceHistory.class, "invoiceId", "invoices"),
                    "paymentId", link(TableSchemas.PROMISES.require("paymentId"),
                            PromisePaymentHistory.class, "paymentId", "payments"),
                    "regionId", RegionSchemas.AS_OF_REGION_ID,
                    "regionName", RegionSchemas.AS_OF_REGION_NAME));

    private static ColumnDef link(ColumnDef live, Class<? extends HistoryRow> mirror,
                                  String otherId, String column) {
        return ColumnDef.of(live.name(), live.label(), live.type())
                .reference(live.referenceKind()).notSortable()
                .path((root, q, cb) -> root.get("id"))
                .filter((spec, root, q, cb) -> linkPredicate(mirror, otherId, column, spec, root, q, cb))
                .build();
    }

    /**
     * The live promiseLinkPredicate's five arms and its default throw, over a link mirror. A link
     * mirror's {@code id} IS the promise id and the other end is a plain Long beside it, so the
     * whole shape is one correlated EXISTS with no join — and it carries AsOf.at(T), because a
     * promise that stopped covering an invoice in February still covered it in January (B3).
     */
    private static Predicate linkPredicate(Class<? extends HistoryRow> mirror, String otherId,
                                           String column, FilterSpec spec, Root<?> root,
                                           CriteriaQuery<?> q, CriteriaBuilder cb) {
        Subquery<Long> sq = q.subquery(Long.class);
        Root<?> link = sq.from(mirror);
        sq.select(cb.literal(1L));
        Predicate base = cb.and(
                cb.equal(link.get("id"), root.get("id")),
                inForce().build(link, q, cb));

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
                sq.where(cb.and(base, cb.equal(link.get(otherId), asLong(spec.first()))));
                yield cb.exists(sq);
            }
            case NEQ -> {
                sq.where(cb.and(base, cb.equal(link.get(otherId), asLong(spec.first()))));
                yield cb.not(cb.exists(sq));
            }
            case IN -> {
                sq.where(cb.and(base, link.get(otherId)
                        .in(spec.values().stream().map(HistorySchemas::asLong).toList())));
                yield cb.exists(sq);
            }
            default -> throw new BadRequestException(
                    "Operator " + spec.operator().wire() + " is not valid for the " + column + " column");
        };
    }

    private static Long asLong(String raw) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Expected an id but got: " + raw);
        }
    }

    // ---- disputes ------------------------------------------------------------------------------

    // Not one hand-written override: a dispute already keeps every reference it has as a bare
    // customer_id / target_id Long with no association to walk, so its live ColumnDefs resolve on
    // DisputeHistory exactly as they stand. Only the two region columns move, and those move
    // everywhere (B3).
    public static final TableSchema DISPUTES = TableSchemas.asOf(
            TableSchemas.DISPUTES, DisputeHistory.class, Map.of(
                    "regionId", RegionSchemas.AS_OF_REGION_ID,
                    "regionName", RegionSchemas.AS_OF_REGION_NAME_UNSORTED));

    // ---- tasks ---------------------------------------------------------------------------------

    public static final TableSchema TASKS = TableSchemas.asOf(
            TaskSchemas.TASKS, TaskHistory.class, Map.of(
                    // WHO IS ON THE TASK IS TODAY'S ANSWER, AND IT SAYS SO. task_assignees is not
                    // a mirrored table — the blueprint's mirrored set does not include it — so the
                    // EXISTS behind this filter reads the live seats whatever date is asked. The
                    // path and the predicate are the live ones unchanged; only asOfMode moves,
                    // which is what makes the claim visible on the wire instead of lost in a
                    // design document (B3, A6).
                    "assigneeUserId", current(TaskSchemas.TASKS.require("assigneeUserId")),
                    "regionId", RegionSchemas.AS_OF_REGION_ID,
                    "regionName", RegionSchemas.AS_OF_REGION_NAME_UNSORTED));

    // ---- the register ---------------------------------------------------------------------------

    private static final List<TableSchema> ALL_SCHEMAS =
            List.of(INVOICES, CUSTOMERS, PAYMENTS, PROMISES, DISPUTES, TASKS);

    private static final Map<String, TableSchema> BY_LOWER_ENTITY = index();

    private static Map<String, TableSchema> index() {
        Map<String, TableSchema> m = new LinkedHashMap<>();
        for (TableSchema s : ALL_SCHEMAS) {
            TableSchema clash = m.put(s.entity().toLowerCase(Locale.ROOT), s);
            if (clash != null) {
                throw new IllegalStateException("Two as-of table schemas claim the entity name "
                        + s.entity());
            }
        }
        return Map.copyOf(m);
    }

    /** The six twins, in the order the UI should offer them (B3). */
    public static List<TableSchema> all() {
        return ALL_SCHEMAS;
    }

    /** The live entity names that have a mirror to read, which is what GET /api/as-of publishes. */
    public static List<String> entities() {
        return ALL_SCHEMAS.stream().map(TableSchema::entity).toList();
    }

    /**
     * Case-insensitive, exactly as TableSchemas.byEntity is on the wire, and null rather than a
     * throw: the caller asking is deciding WHETHER a table can be asked as of a date (B3).
     */
    public static TableSchema byEntity(String entity) {
        return BY_LOWER_ENTITY.get(entity == null ? "" : entity.toLowerCase(Locale.ROOT));
    }
}
