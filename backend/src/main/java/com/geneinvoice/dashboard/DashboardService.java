package com.geneinvoice.dashboard;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.query.Aggregates;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.history.Temporal;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentAllocation;
import com.geneinvoice.payment.PaymentStatus;
import com.geneinvoice.poc.ScopeResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The dashboard figures. Every query roots and joins through {@link Temporal}, so as of a date each figure is the
 * one the dashboard showed that day (§5.12): the invoices, payments and allocations that existed then, as they
 * were, under the customers' names then, with "today" (ageing, the month window) being the viewed day.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    public static final int MAX_MONTHS = 24;
    public static final int MAX_LIMIT = 20;

    /**
     * One ageing bucket, in whole days past the due date (D4). A null bound is an open end: "Not
     * yet due" has no lower one, the last bucket no upper one.
     */
    private record Age(String label, Integer fromDays, Integer toDays) {}

    private static final List<Age> AGES = List.of(
            new Age("Not yet due", null, 0),
            new Age("1–30 days", 1, 30),
            new Age("31–60 days", 31, 60),
            new Age("61–90 days", 61, 90),
            new Age("Over 90 days", 91, null));

    @PersistenceContext
    private EntityManager em;

    private final ScopeResolver scopeResolver;
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public DashboardDtos.MonthlySeries billedByMonth(int months, LocalDate today) {
        List<YearMonth> window = window(months, today);
        ScopeResolver.Scope scope = scopeResolver.forInvoices();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        List<Predicate> where = new ArrayList<>();
        Root<?> inv = Temporal.fromValid(cq, Invoice.class, cb, where);
        Expression<Instant> date = inv.get("invoiceDate");

        where.addAll(scoped(inv, cq, cb, scope));
        where.add(cb.notEqual(inv.get("status"), InvoiceStatus.CANCELLED));
        where.add(within(cb, date, window));
        cq.multiselect(monthSelections(cb, date, inv.get("total"), inv.get("id"), window))
                .where(where.toArray(new Predicate[0]));
        return new DashboardDtos.MonthlySeries(coverage(scope),
                points(window, em.createQuery(cq).getSingleResult()), scopeInfo(scope));
    }

    @Transactional(readOnly = true)
    public DashboardDtos.OutstandingByAge outstandingByAge(LocalDate today) {
        ScopeResolver.Scope scope = scopeResolver.forInvoices();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        List<Predicate> where = new ArrayList<>();
        Root<?> inv = Temporal.fromValid(cq, Invoice.class, cb, where);
        Expression<LocalDate> dueDate = inv.get("dueDate");
        Expression<BigDecimal> balance = balance(cb, inv);

        List<Selection<?>> select = new ArrayList<>();
        for (Age age : AGES) {
            select.add(Aggregates.sumWhen(cb, overdueBy(cb, dueDate, age, today), balance));
            select.add(Aggregates.countWhen(cb, overdueBy(cb, dueDate, age, today)));
        }
        where.addAll(scoped(inv, cq, cb, scope));
        cq.multiselect(select).where(open(cb, inv, where).toArray(new Predicate[0]));
        Object[] row = em.createQuery(cq).getSingleResult();

        List<DashboardDtos.AgeBucket> buckets = new ArrayList<>();
        for (int i = 0; i < AGES.size(); i++) {
            Age age = AGES.get(i);
            buckets.add(new DashboardDtos.AgeBucket(age.label(), age.fromDays(), age.toDays(),
                    Aggregates.asMoney(row[2 * i]), Aggregates.asLong(row[2 * i + 1]),
                    age.toDays() == null ? null : today.minusDays(age.toDays()),
                    age.fromDays() == null ? null : today.minusDays(age.fromDays())));
        }
        return new DashboardDtos.OutstandingByAge(coverage(scope), buckets, scopeInfo(scope));
    }

    @Transactional(readOnly = true)
    public DashboardDtos.TopOutstanding topOutstanding(int limit) {
        requireStaff();
        requireLimit(limit);
        ScopeResolver.Scope scope = scopeResolver.forInvoices();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        List<Predicate> where = new ArrayList<>();
        Root<?> inv = Temporal.fromValid(cq, Invoice.class, cb, where);
        From<?, ?> customer = Temporal.join(inv, "customer", JoinType.INNER, cb);
        Expression<BigDecimal> outstanding = cb.sum(balance(cb, inv));
        where.addAll(scoped(inv, cq, cb, scope));

        cq.multiselect(customer.get("id"), customer.get("name"), outstanding,
                        cb.count(inv.get("id")), cb.least(inv.<Instant>get("invoiceDate")))
                .where(open(cb, inv, where).toArray(new Predicate[0]))
                .groupBy(customer.get("id"), customer.get("name"))
                .orderBy(cb.desc(outstanding), cb.asc(customer.get("name")));

        List<DashboardDtos.OutstandingCustomer> rows = em.createQuery(cq).setMaxResults(limit)
                .getResultList().stream()
                .map(r -> new DashboardDtos.OutstandingCustomer((Long) r[0], (String) r[1],
                        Aggregates.asMoney(r[2]), Aggregates.asLong(r[3]), asInstant(r[4])))
                .toList();
        return new DashboardDtos.TopOutstanding(coverage(scope), rows, scopeInfo(scope));
    }

    @Transactional(readOnly = true)
    public DashboardDtos.MonthlySeries collectedByMonth(int months, LocalDate today) {
        List<YearMonth> window = window(months, today);
        Split split = split();
        BigDecimal[] amounts = new BigDecimal[window.size()];
        long[] counts = new long[window.size()];
        Arrays.fill(amounts, BigDecimal.ZERO);
        for (Via via : split.parts()) {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
            Collected c = collected(cq, cb, split, via);

            List<Predicate> where = c.where();
            where.add(within(cb, c.paidAt(), window));
            cq.multiselect(monthSelections(cb, c.paidAt(), c.amount(), c.paymentId(), window))
                    .where(where.toArray(new Predicate[0]));
            // A payment is in exactly one region (I1), so the parts never count the same one twice.
            List<DashboardDtos.MonthPoint> part = points(window, em.createQuery(cq).getSingleResult());
            for (int i = 0; i < window.size(); i++) {
                amounts[i] = amounts[i].add(part.get(i).amount());
                counts[i] += part.get(i).count();
            }
        }
        List<DashboardDtos.MonthPoint> points = new ArrayList<>();
        for (int i = 0; i < window.size(); i++) {
            points.add(new DashboardDtos.MonthPoint(window.get(i).toString(), Aggregates.asMoney(amounts[i]),
                    counts[i]));
        }
        return new DashboardDtos.MonthlySeries(split.coverage(), points, split.scopeInfo());
    }

    @Transactional(readOnly = true)
    public DashboardDtos.TopPaying topPaying(int months, int limit, LocalDate today) {
        requireStaff();
        requireLimit(limit);
        List<YearMonth> window = window(months, today);
        Split split = split();
        Map<Long, DashboardDtos.PayingCustomer> merged = new LinkedHashMap<>();
        for (Via via : split.parts()) {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
            Collected c = collected(cq, cb, split, via);
            Expression<BigDecimal> total = cb.sum(c.amount());

            List<Predicate> where = c.where();
            where.add(within(cb, c.paidAt(), window));
            cq.multiselect(c.customer().get("id"), c.customer().get("name"), total,
                            cb.countDistinct(c.paymentId()), cb.greatest(c.paidAt()))
                    .where(where.toArray(new Predicate[0]))
                    .groupBy(c.customer().get("id"), c.customer().get("name"))
                    .orderBy(cb.desc(total), cb.asc(c.customer().get("name")));

            // A customer is in one region, so in one part: each part's own top rows are enough.
            em.createQuery(cq).setMaxResults(limit).getResultList().forEach(r -> merged.merge((Long) r[0],
                    new DashboardDtos.PayingCustomer((Long) r[0], (String) r[1],
                            Aggregates.asMoney(r[2]), Aggregates.asLong(r[3]), asInstant(r[4])),
                    DashboardService::sameCustomer));
        }
        // One part keeps the database's order. Several are merged by amount, then by name ignoring case (then
        // exactly, then by id, so the order is total): a tie across parts reads by name, not by part (RR-3).
        List<DashboardDtos.PayingCustomer> rows = new ArrayList<>(merged.values());
        if (split.parts().size() > 1) {
            rows.sort(Comparator.comparing(DashboardDtos.PayingCustomer::collected).reversed()
                    .thenComparing(DashboardDtos.PayingCustomer::customerName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(DashboardDtos.PayingCustomer::customerName)
                    .thenComparing(DashboardDtos.PayingCustomer::customerId));
        }
        rows = rows.stream().limit(limit).toList();
        return new DashboardDtos.TopPaying(split.coverage(), rows, split.scopeInfo());
    }

    private static DashboardDtos.PayingCustomer sameCustomer(DashboardDtos.PayingCustomer a,
                                                             DashboardDtos.PayingCustomer b) {
        Instant last = a.lastPaidAt() == null ? b.lastPaidAt()
                : b.lastPaidAt() == null || a.lastPaidAt().isAfter(b.lastPaidAt()) ? a.lastPaidAt() : b.lastPaidAt();
        return new DashboardDtos.PayingCustomer(a.customerId(), a.customerName(),
                a.collected().add(b.collected()), a.payments() + b.payments(), last);
    }

    /** How a part of "collected" is counted: whole payments, or the allocations to the caller's book. */
    private enum Via { PAYMENTS, ALLOCATIONS }

    /**
     * "Collected" split by region (B1). Where the caller sees every payment ({@code sums}: the payment
     * scope's full regions) it is the payments themselves; where they see only a book ({@code allocations}:
     * the book regions of either scope) it is what was allocated to their invoices or paid on their
     * payments, as it was for a whole book before regions. A customer login sees its own payments.
     */
    private record Split(ScopeResolver.Scope invoices, ScopeResolver.Scope payments,
                         Set<Long> sums, Set<Long> allocations, boolean own) {

        List<Via> parts() {
            if (own) return List.of(Via.PAYMENTS);
            List<Via> out = new ArrayList<>();
            if (!sums.isEmpty()) out.add(Via.PAYMENTS);
            if (!allocations.isEmpty()) out.add(Via.ALLOCATIONS);
            return out;
        }

        DashboardDtos.Coverage coverage() {
            if (own) return DashboardDtos.Coverage.OWN;
            if (!sums.isEmpty() && !allocations.isEmpty()) return DashboardDtos.Coverage.MIXED;
            if (!sums.isEmpty()) return DashboardDtos.Coverage.ALL;
            if (!allocations.isEmpty()) return DashboardDtos.Coverage.BOOK;
            return invoices.lockedFilters().isEmpty() && payments.lockedFilters().isEmpty()
                    ? DashboardDtos.Coverage.ALL : DashboardDtos.Coverage.BOOK;
        }

        DashboardDtos.ScopeInfo scopeInfo() {
            if (own) return DashboardService.scopeInfo(payments);
            Set<Long> answered = new TreeSet<>(sums);
            answered.addAll(allocations);
            return new DashboardDtos.ScopeInfo(List.copyOf(answered), List.copyOf(allocations), asOf());
        }
    }

    private Split split() {
        ScopeResolver.Scope invoices = scopeResolver.forInvoices();
        ScopeResolver.Scope payments = scopeResolver.forPayments();
        if (currentUser.isCustomer()) {
            return new Split(invoices, payments, Set.of(), Set.of(), true);
        }
        Set<Long> book = new TreeSet<>(payments.coverage().book());
        book.addAll(invoices.coverage().book());
        return new Split(invoices, payments, new TreeSet<>(payments.coverage().full()), book, false);
    }

    private record Collected(Expression<BigDecimal> amount, Expression<Instant> paidAt,
                             Expression<Long> paymentId, From<?, ?> customer,
                             List<Predicate> where) {}

    private Collected collected(CriteriaQuery<Object[]> cq, CriteriaBuilder cb, Split split, Via via) {
        List<Predicate> where = new ArrayList<>();
        if (via == Via.ALLOCATIONS) {
            Root<?> alloc = Temporal.fromValid(cq, PaymentAllocation.class, cb, where);
            From<?, ?> payment = Temporal.join(alloc, "payment", JoinType.INNER, cb);
            From<?, ?> invoice = Temporal.join(alloc, "invoice", JoinType.INNER, cb);
            where.add(cb.equal(payment.get("status"), PaymentStatus.ACTIVE));
            where.add(invoice.get("region").get("id").in(split.allocations()));
            where.add(cb.or(
                    invoice.get("id").in(idsInScope(Invoice.class, split.invoices(), cq, cb)),
                    payment.get("id").in(idsInScope(Payment.class, split.payments(), cq, cb))));
            return new Collected(alloc.get("amount"), payment.get("paidAt"), payment.get("id"),
                    Temporal.join(invoice, "customer", JoinType.INNER, cb), where);
        }
        Root<?> payment = Temporal.fromValid(cq, Payment.class, cb, where);
        where.addAll(scoped(payment, cq, cb, split.payments()));
        if (!split.own()) {
            where.add(payment.get("region").get("id").in(split.sums()));
        }
        where.add(cb.equal(payment.get("status"), PaymentStatus.ACTIVE));
        return new Collected(payment.get("amount"), payment.get("paidAt"), payment.get("id"),
                Temporal.join(payment, "customer", JoinType.INNER, cb), where);
    }

    /** OWN for a customer login; BOOK for a pure book view (its chip); MIXED when some regions are a book. */
    private DashboardDtos.Coverage coverage(ScopeResolver.Scope scope) {
        if (currentUser.isCustomer()) return DashboardDtos.Coverage.OWN;
        if (!scope.lockedFilters().isEmpty()) return DashboardDtos.Coverage.BOOK;
        return scope.coverage().mixed() ? DashboardDtos.Coverage.MIXED : DashboardDtos.Coverage.ALL;
    }

    private static DashboardDtos.ScopeInfo scopeInfo(ScopeResolver.Scope scope) {
        return new DashboardDtos.ScopeInfo(scope.regionIds(), scope.bookRegionIds(), asOf());
    }

    private static String asOf() {
        AsOfPoint point = AsOf.current();
        return point == null ? null : point.date().toString();
    }

    private void requireStaff() {
        if (currentUser.isCustomer()) {
            throw new AccessDeniedException("Not allowed");
        }
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BadRequestException("limit must be between 1 and " + MAX_LIMIT);
        }
    }

    private static List<YearMonth> window(int months, LocalDate today) {
        if (months < 1 || months > MAX_MONTHS) {
            throw new BadRequestException("months must be between 1 and " + MAX_MONTHS);
        }
        YearMonth current = YearMonth.from(today);
        List<YearMonth> out = new ArrayList<>();
        for (int i = months - 1; i >= 0; i--) {
            out.add(current.minusMonths(i));
        }
        return out;
    }

    private static Instant startOf(YearMonth month) {
        return month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Predicate within(CriteriaBuilder cb, Expression<Instant> date, List<YearMonth> window) {
        return cb.and(cb.greaterThanOrEqualTo(date, startOf(window.get(0))),
                cb.lessThan(date, startOf(window.get(window.size() - 1).plusMonths(1))));
    }

    private static Predicate inMonth(CriteriaBuilder cb, Expression<Instant> date, YearMonth month) {
        return cb.and(cb.greaterThanOrEqualTo(date, startOf(month)),
                cb.lessThan(date, startOf(month.plusMonths(1))));
    }

    private static List<Selection<?>> monthSelections(CriteriaBuilder cb, Expression<Instant> date,
                                                      Expression<BigDecimal> amount, Expression<Long> id,
                                                      List<YearMonth> window) {
        List<Selection<?>> out = new ArrayList<>();
        for (YearMonth month : window) {
            out.add(Aggregates.sumWhen(cb, inMonth(cb, date, month), amount));
            out.add(cb.countDistinct(cb.<Long>selectCase()
                    .when(inMonth(cb, date, month), id)
                    .otherwise(cb.nullLiteral(Long.class))));
        }
        return out;
    }

    private static List<DashboardDtos.MonthPoint> points(List<YearMonth> window, Object[] row) {
        List<DashboardDtos.MonthPoint> out = new ArrayList<>();
        for (int i = 0; i < window.size(); i++) {
            out.add(new DashboardDtos.MonthPoint(window.get(i).toString(),
                    Aggregates.asMoney(row[2 * i]), Aggregates.asLong(row[2 * i + 1])));
        }
        return out;
    }

    private static Predicate overdueBy(CriteriaBuilder cb, Expression<LocalDate> dueDate, Age age,
                                       LocalDate today) {
        List<Predicate> bounds = new ArrayList<>();
        if (age.fromDays() != null) {
            bounds.add(cb.lessThanOrEqualTo(dueDate, today.minusDays(age.fromDays())));
        }
        if (age.toDays() != null) {
            bounds.add(cb.greaterThanOrEqualTo(dueDate, today.minusDays(age.toDays())));
        }
        Predicate within = cb.and(bounds.toArray(new Predicate[0]));
        return age.fromDays() == null ? cb.or(cb.isNull(dueDate), within) : within;
    }

    private static Expression<BigDecimal> balance(CriteriaBuilder cb, Root<?> inv) {
        return cb.diff(inv.<BigDecimal>get("total"), inv.<BigDecimal>get("paidAmount"));
    }

    private static List<Predicate> open(CriteriaBuilder cb, Root<?> inv, List<Predicate> where) {
        where.add(cb.notEqual(inv.get("status"), InvoiceStatus.CANCELLED));
        where.add(cb.greaterThan(balance(cb, inv), BigDecimal.ZERO));
        return where;
    }

    private static List<Predicate> scoped(Root<?> root, CriteriaQuery<?> cq, CriteriaBuilder cb,
                                          ScopeResolver.Scope scope) {
        List<Predicate> out = new ArrayList<>();
        scope.predicates().forEach(f -> {
            Predicate p = f.build(root, cq, cb);
            if (p != null) out.add(p);
        });
        return out;
    }

    /** The ids of the records of {@code type} in scope (as of a date, those valid then, in scope then). */
    private static Subquery<Long> idsInScope(Class<?> type, ScopeResolver.Scope scope,
                                             CriteriaQuery<?> cq, CriteriaBuilder cb) {
        return Temporal.subquery(cq, Long.class, type, cb, (root, sq, where) -> {
            where.addAll(scoped(root, cq, cb, scope));
            return root.get("id");
        });
    }

    private static Instant asInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant i) return i;
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof OffsetDateTime odt) return odt.toInstant();
        throw new IllegalStateException("Unexpected timestamp type: " + o.getClass());
    }
}
