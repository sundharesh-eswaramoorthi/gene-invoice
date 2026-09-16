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

/**
 * The dashboard's charts and rankings. Each runs under the scope of the list it summarises, so a
 * card never shows money the caller could not find by opening that list (AC-E6). Months and ages
 * are counted in UTC, like every date filter in the app.
 *
 * <p>One case needs more than that. A POC whose invoices are limited to their book usually still
 * sees every payment, so "collected" would set everyone's payments against their own billing. For
 * them the payment figures count only what was paid against invoices in their book.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    public static final int MAX_MONTHS = 24;
    public static final int MAX_LIMIT = 20;

    private record Age(String label, int fromDays, Integer toDays) {}

    private static final List<Age> AGES = List.of(
            new Age("0–30 days", 0, 30),
            new Age("31–60 days", 31, 60),
            new Age("61–90 days", 61, 90),
            new Age("Over 90 days", 91, null));

    @PersistenceContext
    private EntityManager em;

    private final ScopeResolver scopeResolver;
    private final CurrentUser currentUser;

    // ---- invoices --------------------------------------------------------------

    /** Invoice totals by invoice month, cancelled invoices left out. */
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

    /** What is still owed, by days since the invoice date. */
    @Transactional(readOnly = true)
    public DashboardDtos.OutstandingByAge outstandingByAge(LocalDate today) {
        ScopeResolver.Scope scope = scopeResolver.forInvoices();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<Invoice> inv = cq.from(Invoice.class);
        Expression<Instant> date = inv.get("invoiceDate");
        Expression<BigDecimal> balance = balance(cb, inv);

        List<Selection<?>> select = new ArrayList<>();
        for (Age age : AGES) {
            select.add(Aggregates.sumWhen(cb, aged(cb, date, age, today), balance));
            select.add(Aggregates.countWhen(cb, aged(cb, date, age, today)));
        }
        cq.multiselect(select).where(open(cb, inv, scoped(inv, cq, cb, scope)).toArray(new Predicate[0]));
        Object[] row = em.createQuery(cq).getSingleResult();

        List<DashboardDtos.AgeBucket> buckets = new ArrayList<>();
        for (int i = 0; i < AGES.size(); i++) {
            Age age = AGES.get(i);
            buckets.add(new DashboardDtos.AgeBucket(age.label(), age.fromDays(), age.toDays(),
                    Aggregates.asMoney(row[2 * i]), Aggregates.asLong(row[2 * i + 1])));
        }
        return new DashboardDtos.OutstandingByAge(coverage(scope), buckets);
    }

    /** The customers owing the most, with how many invoices are open and the oldest of them. */
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

    // ---- payments --------------------------------------------------------------

    /** Money collected by payment month, voided payments left out. */
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

    /** The customers who paid the most over the window. */
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

    /** The parts of a "money collected" query: what is summed, when, for whom, and the rows allowed. */
    private record Collected(Expression<BigDecimal> amount, Expression<Instant> paidAt,
                             Expression<Long> paymentId, From<?, Customer> customer,
                             List<Predicate> where) {}

    /**
     * Whole active payments in the caller's payment scope; or, for a caller whose invoices are
     * limited to their book, the parts of active payments that landed on those invoices.
     */
    private Collected collected(CriteriaQuery<Object[]> cq, CriteriaBuilder cb) {
        if (paidAgainstBookOnly()) {
            Root<PaymentAllocation> alloc = cq.from(PaymentAllocation.class);
            Join<PaymentAllocation, Payment> payment = alloc.join("payment");
            Join<PaymentAllocation, Invoice> invoice = alloc.join("invoice");
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(payment.get("status"), PaymentStatus.ACTIVE));
            where.add(invoice.get("id").in(idsInScope(Invoice.class, scopeResolver.forInvoices(), cq, cb)));
            ScopeResolver.Scope payments = scopeResolver.forPayments();
            if (!payments.predicates().isEmpty()) {
                where.add(payment.get("id").in(idsInScope(Payment.class, payments, cq, cb)));
            }
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

    // ---- helpers ---------------------------------------------------------------

    private DashboardDtos.Coverage coverage(ScopeResolver.Scope scope) {
        if (currentUser.isCustomer()) return DashboardDtos.Coverage.OWN;
        return scope.lockedFilters().isEmpty() ? DashboardDtos.Coverage.ALL : DashboardDtos.Coverage.BOOK;
    }

    /** Rankings compare customers with each other, which a customer login has no business seeing. */
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

    /** The current month and the {@code months - 1} before it, oldest first. */
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

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Predicate within(CriteriaBuilder cb, Expression<Instant> date, List<YearMonth> window) {
        return cb.and(cb.greaterThanOrEqualTo(date, startOf(window.get(0))),
                cb.lessThan(date, startOf(window.get(window.size() - 1).plusMonths(1))));
    }

    private static Predicate inMonth(CriteriaBuilder cb, Expression<Instant> date, YearMonth month) {
        return cb.and(cb.greaterThanOrEqualTo(date, startOf(month)),
                cb.lessThan(date, startOf(month.plusMonths(1))));
    }

    /**
     * An amount and a count per month, in one pass. The count is of distinct ids, because a payment
     * split across several of a book's invoices is still one payment.
     */
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

    /**
     * An invoice dated {@code today - n} days is {@code n} days old. The first bucket has no newer
     * bound, so an invoice dated ahead of today still counts somewhere.
     */
    private static Predicate aged(CriteriaBuilder cb, Expression<Instant> date, Age age, LocalDate today) {
        List<Predicate> bounds = new ArrayList<>();
        if (age.fromDays() > 0) {
            bounds.add(cb.lessThan(date, startOf(today.minusDays(age.fromDays() - 1L))));
        }
        if (age.toDays() != null) {
            bounds.add(cb.greaterThanOrEqualTo(date, startOf(today.minusDays(age.toDays()))));
        }
        return cb.and(bounds.toArray(new Predicate[0]));
    }

    private static Expression<BigDecimal> balance(CriteriaBuilder cb, Root<Invoice> inv) {
        return cb.diff(inv.<BigDecimal>get("total"), inv.<BigDecimal>get("paidAmount"));
    }

    /** Invoices that still have something owing: not cancelled, balance above zero. */
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

    /** Aggregated timestamps come back as Instant from Hibernate, but not from every driver. */
    private static Instant asInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant i) return i;
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof OffsetDateTime odt) return odt.toInstant();
        throw new IllegalStateException("Unexpected timestamp type: " + o.getClass());
    }
}
