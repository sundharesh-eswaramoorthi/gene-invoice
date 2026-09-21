package com.geneinvoice.dashboard;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.query.Aggregates;
import com.geneinvoice.customer.Customer;
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
import jakarta.persistence.criteria.Join;
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
import java.util.List;

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
        Root<Invoice> inv = cq.from(Invoice.class);
        Expression<Instant> date = inv.get("invoiceDate");

        List<Predicate> where = scoped(inv, cq, cb, scope);
        where.add(cb.notEqual(inv.get("status"), InvoiceStatus.CANCELLED));
        where.add(within(cb, date, window));
        cq.multiselect(monthSelections(cb, date, inv.get("total"), inv.get("id"), window))
                .where(where.toArray(new Predicate[0]));
        return new DashboardDtos.MonthlySeries(coverage(scope),
                points(window, em.createQuery(cq).getSingleResult()));
    }

    @Transactional(readOnly = true)
    public DashboardDtos.OutstandingByAge outstandingByAge(LocalDate today) {
        ScopeResolver.Scope scope = scopeResolver.forInvoices();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<Invoice> inv = cq.from(Invoice.class);
        Expression<LocalDate> dueDate = inv.get("dueDate");
        Expression<BigDecimal> balance = balance(cb, inv);

        List<Selection<?>> select = new ArrayList<>();
        for (Age age : AGES) {
            select.add(Aggregates.sumWhen(cb, overdueBy(cb, dueDate, age, today), balance));
            select.add(Aggregates.countWhen(cb, overdueBy(cb, dueDate, age, today)));
        }
        cq.multiselect(select).where(open(cb, inv, scoped(inv, cq, cb, scope)).toArray(new Predicate[0]));
        Object[] row = em.createQuery(cq).getSingleResult();

        List<DashboardDtos.AgeBucket> buckets = new ArrayList<>();
        for (int i = 0; i < AGES.size(); i++) {
            Age age = AGES.get(i);
            buckets.add(new DashboardDtos.AgeBucket(age.label(), age.fromDays(), age.toDays(),
                    Aggregates.asMoney(row[2 * i]), Aggregates.asLong(row[2 * i + 1]),
                    age.toDays() == null ? null : today.minusDays(age.toDays()),
                    age.fromDays() == null ? null : today.minusDays(age.fromDays())));
        }
        return new DashboardDtos.OutstandingByAge(coverage(scope), buckets);
    }

    @Transactional(readOnly = true)
    public DashboardDtos.TopOutstanding topOutstanding(int limit) {
        requireStaff();
        requireLimit(limit);
        ScopeResolver.Scope scope = scopeResolver.forInvoices();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<Invoice> inv = cq.from(Invoice.class);
        Join<Invoice, Customer> customer = inv.join("customer");
        Expression<BigDecimal> outstanding = cb.sum(balance(cb, inv));

        cq.multiselect(customer.get("id"), customer.get("name"), outstanding,
                        cb.count(inv.get("id")), cb.least(inv.<Instant>get("invoiceDate")))
                .where(open(cb, inv, scoped(inv, cq, cb, scope)).toArray(new Predicate[0]))
                .groupBy(customer.get("id"), customer.get("name"))
                .orderBy(cb.desc(outstanding), cb.asc(customer.get("name")));

        List<DashboardDtos.OutstandingCustomer> rows = em.createQuery(cq).setMaxResults(limit)
                .getResultList().stream()
                .map(r -> new DashboardDtos.OutstandingCustomer((Long) r[0], (String) r[1],
                        Aggregates.asMoney(r[2]), Aggregates.asLong(r[3]), asInstant(r[4])))
                .toList();
        return new DashboardDtos.TopOutstanding(coverage(scope), rows);
    }

    @Transactional(readOnly = true)
    public DashboardDtos.MonthlySeries collectedByMonth(int months, LocalDate today) {
        List<YearMonth> window = window(months, today);
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Collected c = collected(cq, cb);

        List<Predicate> where = c.where();
        where.add(within(cb, c.paidAt(), window));
        cq.multiselect(monthSelections(cb, c.paidAt(), c.amount(), c.paymentId(), window))
                .where(where.toArray(new Predicate[0]));
        return new DashboardDtos.MonthlySeries(paymentCoverage(),
                points(window, em.createQuery(cq).getSingleResult()));
    }

    @Transactional(readOnly = true)
    public DashboardDtos.TopPaying topPaying(int months, int limit, LocalDate today) {
        requireStaff();
        requireLimit(limit);
        List<YearMonth> window = window(months, today);
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Collected c = collected(cq, cb);
        Expression<BigDecimal> total = cb.sum(c.amount());

        List<Predicate> where = c.where();
        where.add(within(cb, c.paidAt(), window));
        cq.multiselect(c.customer().get("id"), c.customer().get("name"), total,
                        cb.countDistinct(c.paymentId()), cb.greatest(c.paidAt()))
                .where(where.toArray(new Predicate[0]))
                .groupBy(c.customer().get("id"), c.customer().get("name"))
                .orderBy(cb.desc(total), cb.asc(c.customer().get("name")));

        List<DashboardDtos.PayingCustomer> rows = em.createQuery(cq).setMaxResults(limit)
                .getResultList().stream()
                .map(r -> new DashboardDtos.PayingCustomer((Long) r[0], (String) r[1],
                        Aggregates.asMoney(r[2]), Aggregates.asLong(r[3]), asInstant(r[4])))
                .toList();
        return new DashboardDtos.TopPaying(paymentCoverage(), rows);
    }

    private record Collected(Expression<BigDecimal> amount, Expression<Instant> paidAt,
                             Expression<Long> paymentId, From<?, Customer> customer,
                             List<Predicate> where) {}

    private Collected collected(CriteriaQuery<Object[]> cq, CriteriaBuilder cb) {
        if (paidAgainstBookOnly()) {
            Root<PaymentAllocation> alloc = cq.from(PaymentAllocation.class);
            Join<PaymentAllocation, Payment> payment = alloc.join("payment");
            Join<PaymentAllocation, Invoice> invoice = alloc.join("invoice");
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(payment.get("status"), PaymentStatus.ACTIVE));
            Predicate onMyInvoices =
                    invoice.get("id").in(idsInScope(Invoice.class, scopeResolver.forInvoices(), cq, cb));
            ScopeResolver.Scope payments = scopeResolver.forPayments();
            where.add(payments.predicates().isEmpty() ? onMyInvoices
                    : cb.or(onMyInvoices, payment.get("id").in(idsInScope(Payment.class, payments, cq, cb))));
            return new Collected(alloc.get("amount"), payment.get("paidAt"), payment.get("id"),
                    invoice.join("customer"), where);
        }
        Root<Payment> payment = cq.from(Payment.class);
        List<Predicate> where = scoped(payment, cq, cb, scopeResolver.forPayments());
        where.add(cb.equal(payment.get("status"), PaymentStatus.ACTIVE));
        return new Collected(payment.get("amount"), payment.get("paidAt"), payment.get("id"),
                payment.join("customer"), where);
    }

    private boolean paidAgainstBookOnly() {
        return !scopeResolver.forInvoices().lockedFilters().isEmpty();
    }

    private DashboardDtos.Coverage paymentCoverage() {
        return paidAgainstBookOnly()
                ? DashboardDtos.Coverage.BOOK
                : coverage(scopeResolver.forPayments());
    }

    private DashboardDtos.Coverage coverage(ScopeResolver.Scope scope) {
        if (currentUser.isCustomer()) return DashboardDtos.Coverage.OWN;
        return scope.lockedFilters().isEmpty() ? DashboardDtos.Coverage.ALL : DashboardDtos.Coverage.BOOK;
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

    private static Expression<BigDecimal> balance(CriteriaBuilder cb, Root<Invoice> inv) {
        return cb.diff(inv.<BigDecimal>get("total"), inv.<BigDecimal>get("paidAmount"));
    }

    private static List<Predicate> open(CriteriaBuilder cb, Root<Invoice> inv, List<Predicate> where) {
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

    private static <T> Subquery<Long> idsInScope(Class<T> type, ScopeResolver.Scope scope,
                                                 CriteriaQuery<?> cq, CriteriaBuilder cb) {
        Subquery<Long> sq = cq.subquery(Long.class);
        Root<T> root = sq.from(type);
        sq.select(root.get("id")).where(scoped(root, cq, cb, scope).toArray(new Predicate[0]));
        return sq;
    }

    private static Instant asInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant i) return i;
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof OffsetDateTime odt) return odt.toInstant();
        throw new IllegalStateException("Unexpected timestamp type: " + o.getClass());
    }
}
