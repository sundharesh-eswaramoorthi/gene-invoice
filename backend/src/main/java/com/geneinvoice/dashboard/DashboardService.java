package com.geneinvoice.dashboard;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.asof.AsOf;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.query.Aggregates;
import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.history.HistoryDrift;
import com.geneinvoice.history.HistoryRow;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceHistory;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentAllocation;
import com.geneinvoice.payment.PaymentAllocationHistory;
import com.geneinvoice.payment.PaymentHistory;
import com.geneinvoice.payment.PaymentStatus;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.region.RegionAxes;
import com.geneinvoice.region.RegionAxis;
import com.geneinvoice.region.RegionGrants;
import com.geneinvoice.region.RegionPredicates;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.RegionScope;
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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
    // The dashboard is the one reader in the application that does not go through
    // TableQueryExecutor, so the region axis has to be added by hand here or all five figures
    // count every branch in the company while every list counts only the caller's own (B1).
    private final RegionScope regionScope;
    // Only to name the branches a figure covered: RegionCoverage carries code and name, and a
    // dashboard has no lockedFilters chip to say it with (B1).
    private final RegionRepository regionRepository;
    // A figure read off a mirror the reconciler had to REPAIR is the best answer available and not
    // the one that was watched happen, so it is downgraded to exact:false with a note naming the
    // table rather than reported as an exact reconstruction. A no-op on every live read (B3).
    private final HistoryDrift historyDrift;

    @Transactional(readOnly = true)
    public DashboardDtos.MonthlySeries billedByMonth(int months, LocalDate today, List<Long> regionIds) {
        List<YearMonth> window = window(months, today);
        ScopeResolver.Scope scope = scopeResolver.forInvoices();
        RegionAsk ask = regionAsk(regionIds);
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<?> inv = invoiceRoot(cq);
        Expression<Instant> date = inv.get("invoiceDate");

        List<Predicate> where = scoped(inv, cq, cb, scope, ask.narrowTo());
        where.add(cb.notEqual(inv.get("status"), InvoiceStatus.CANCELLED));
        where.add(within(cb, date, window));
        cq.multiselect(monthSelections(cb, date, inv.get("total"), inv.get("id"), window))
                .where(where.toArray(new Predicate[0]));
        markDrift(InvoiceHistory.class);
        return new DashboardDtos.MonthlySeries(coverage(scope), ask.coverage(), AsOfContext.info(),
                points(window, em.createQuery(cq).getSingleResult()));
    }

    @Transactional(readOnly = true)
    public DashboardDtos.OutstandingByAge outstandingByAge(LocalDate today, List<Long> regionIds) {
        ScopeResolver.Scope scope = scopeResolver.forInvoices();
        RegionAsk ask = regionAsk(regionIds);
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<?> inv = invoiceRoot(cq);
        Expression<LocalDate> dueDate = inv.get("dueDate");
        Expression<BigDecimal> balance = balance(cb, inv);

        List<Selection<?>> select = new ArrayList<>();
        for (Age age : AGES) {
            select.add(Aggregates.sumWhen(cb, overdueBy(cb, dueDate, age, today), balance));
            select.add(Aggregates.countWhen(cb, overdueBy(cb, dueDate, age, today)));
        }
        cq.multiselect(select)
                .where(open(cb, inv, scoped(inv, cq, cb, scope, ask.narrowTo())).toArray(new Predicate[0]));
        markDrift(InvoiceHistory.class);
        Object[] row = em.createQuery(cq).getSingleResult();

        List<DashboardDtos.AgeBucket> buckets = new ArrayList<>();
        for (int i = 0; i < AGES.size(); i++) {
            Age age = AGES.get(i);
            buckets.add(new DashboardDtos.AgeBucket(age.label(), age.fromDays(), age.toDays(),
                    Aggregates.asMoney(row[2 * i]), Aggregates.asLong(row[2 * i + 1]),
                    age.toDays() == null ? null : today.minusDays(age.toDays()),
                    age.fromDays() == null ? null : today.minusDays(age.fromDays())));
        }
        return new DashboardDtos.OutstandingByAge(coverage(scope), ask.coverage(),
                AsOfContext.info(), buckets);
    }

    @Transactional(readOnly = true)
    public DashboardDtos.TopOutstanding topOutstanding(int limit, List<Long> regionIds) {
        requireStaff();
        requireLimit(limit);
        ScopeResolver.Scope scope = scopeResolver.forInvoices();
        RegionAsk ask = regionAsk(regionIds);
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<?> inv = invoiceRoot(cq);
        Account customer = account(inv);
        Expression<BigDecimal> outstanding = cb.sum(balance(cb, inv));

        cq.multiselect(customer.id(), customer.name(), outstanding,
                        cb.count(inv.get("id")), cb.least(inv.<Instant>get("invoiceDate")))
                .where(open(cb, inv, scoped(inv, cq, cb, scope, ask.narrowTo())).toArray(new Predicate[0]))
                .groupBy(customer.id(), customer.name())
                .orderBy(cb.desc(outstanding), cb.asc(customer.name()));

        markDrift(InvoiceHistory.class);
        List<DashboardDtos.OutstandingCustomer> rows = em.createQuery(cq).setMaxResults(limit)
                .getResultList().stream()
                .map(r -> new DashboardDtos.OutstandingCustomer((Long) r[0], (String) r[1],
                        Aggregates.asMoney(r[2]), Aggregates.asLong(r[3]), asInstant(r[4])))
                .toList();
        return new DashboardDtos.TopOutstanding(coverage(scope), ask.coverage(),
                AsOfContext.info(), rows);
    }

    @Transactional(readOnly = true)
    public DashboardDtos.MonthlySeries collectedByMonth(int months, LocalDate today, List<Long> regionIds) {
        List<YearMonth> window = window(months, today);
        RegionAsk ask = regionAsk(regionIds);
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Collected c = collected(cq, cb, ask.narrowTo());

        List<Predicate> where = c.where();
        where.add(within(cb, c.paidAt(), window));
        cq.multiselect(monthSelections(cb, c.paidAt(), c.amount(), c.paymentId(), window))
                .where(where.toArray(new Predicate[0]));
        return new DashboardDtos.MonthlySeries(paymentCoverage(), ask.coverage(), AsOfContext.info(),
                points(window, em.createQuery(cq).getSingleResult()));
    }

    @Transactional(readOnly = true)
    public DashboardDtos.TopPaying topPaying(int months, int limit, LocalDate today, List<Long> regionIds) {
        requireStaff();
        requireLimit(limit);
        List<YearMonth> window = window(months, today);
        RegionAsk ask = regionAsk(regionIds);
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Collected c = collected(cq, cb, ask.narrowTo());
        Expression<BigDecimal> total = cb.sum(c.amount());

        List<Predicate> where = c.where();
        where.add(within(cb, c.paidAt(), window));
        cq.multiselect(c.account().id(), c.account().name(), total,
                        cb.countDistinct(c.paymentId()), cb.greatest(c.paidAt()))
                .where(where.toArray(new Predicate[0]))
                .groupBy(c.account().id(), c.account().name())
                .orderBy(cb.desc(total), cb.asc(c.account().name()));

        List<DashboardDtos.PayingCustomer> rows = em.createQuery(cq).setMaxResults(limit)
                .getResultList().stream()
                .map(r -> new DashboardDtos.PayingCustomer((Long) r[0], (String) r[1],
                        Aggregates.asMoney(r[2]), Aggregates.asLong(r[3]), asInstant(r[4])))
                .toList();
        return new DashboardDtos.TopPaying(paymentCoverage(), ask.coverage(),
                AsOfContext.info(), rows);
    }

    /**
     * THE ACCOUNT A MONEY ROW BELONGS TO, spelled the two ways the two roots spell it. A live row
     * walks its {@code customer} association; a mirror row has none to walk — a foreign key to a
     * MIRRORED thing is a flat Long, by B3-MIRRORS' rule — and carries the id AND the name AS OF
     * THEN as columns of its own. Grouping by the mirror's pair is therefore one fewer join AND a
     * more correct answer: "Acme Ltd" on a January figure is the name it had in January and not
     * the name it was renamed to last week (B3).
     */
    private record Account(Expression<Long> id, Expression<String> name) {}

    private static Account account(From<?, ?> moneyRow) {
        if (AsOfContext.isActive()) {
            return new Account(moneyRow.get("customerId"), moneyRow.get("customerName"));
        }
        // One join, built once and handed on, exactly as the live code built it once and reused
        // it for the projection, the group-by and the order-by (B1).
        From<?, ?> customer = moneyRow.join("customer");
        return new Account(customer.get("id"), customer.get("name"));
    }

    private record Collected(Expression<BigDecimal> amount, Expression<Instant> paidAt,
                             Expression<Long> paymentId, Account account,
                             List<Predicate> where) {}

    private Collected collected(CriteriaQuery<Object[]> cq, CriteriaBuilder cb, Set<Long> narrowTo) {
        if (AsOfContext.isActive()) return collectedAsOf(cq, cb, narrowTo);
        if (paidAgainstBookOnly()) {
            Root<PaymentAllocation> alloc = cq.from(PaymentAllocation.class);
            Join<PaymentAllocation, Payment> payment = alloc.join("payment");
            Join<PaymentAllocation, Invoice> invoice = alloc.join("invoice");
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(payment.get("status"), PaymentStatus.ACTIVE));
            // The outer root is an allocation, which has no region of its own, and membership
            // below is an OR of two region-bounded subqueries while the figure groups by the
            // INVOICE's customer. Today PaymentService.applyTo only ever allocates to the
            // payment's own customer's invoices, so the OR cannot cross a region — but the
            // region bound of a figure must not rest on an invariant three layers away, so both
            // joins say it plainly: an allocation admitted by the payment arm cannot NAME a
            // customer from a region the caller may not see, and one admitted by the invoice arm
            // cannot COUNT money from a payment there (B1).
            region(invoice, cq, cb, narrowTo, where);
            region(payment, cq, cb, narrowTo, where);
            Predicate onMyInvoices = invoice.get("id")
                    .in(idsInScope(Invoice.class, scopeResolver.forInvoices(), cq, cb, narrowTo));
            ScopeResolver.Scope payments = scopeResolver.forPayments();
            where.add(payments.predicates().isEmpty() ? onMyInvoices
                    : cb.or(onMyInvoices,
                    payment.get("id").in(idsInScope(Payment.class, payments, cq, cb, narrowTo))));
            return new Collected(alloc.get("amount"), payment.get("paidAt"), payment.get("id"),
                    account(invoice), where);
        }
        Root<Payment> payment = cq.from(Payment.class);
        List<Predicate> where = scoped(payment, cq, cb, scopeResolver.forPayments(), narrowTo);
        where.add(cb.equal(payment.get("status"), PaymentStatus.ACTIVE));
        return new Collected(payment.get("amount"), payment.get("paidAt"), payment.get("id"),
                account(payment), where);
    }

    /**
     * THE SAME FIGURE, ASKED OF THE MIRRORS — the one part of this unit that is a second body and
     * not a root swap, and therefore the one part that can drift away from the live one. The
     * "as of today equals live" tests in AsOfFiguresTest are the net under it (B3).
     *
     * <p>IT DOES NOT CHANGE MEANING. A sales POC still sees money that landed on invoices THEY
     * sell, the money is still grouped by the INVOICE's account, and the two region clauses the
     * live body spells on its two joins are spelled here on the two mirror roots that stand in for
     * them. Only the date moves.
     *
     * <p>THREE ROOTS AND NOT ONE, WHICH IS A DEVIATION FROM THE UNIT'S "no joins at all", AND THE
     * REASON IS A MEASUREMENT RATHER THAN A PREFERENCE. B3's text assumes
     * {@code payment_allocation_history.customer_id} is the INVOICE's account. It is not: the
     * projector in HistoryRegistry fills it from {@code p.getCustomerId()}, the PAYMENT's account.
     * Reading it flat would therefore (a) group topPaying by the payer instead of the payee,
     * silently disagreeing with the live figure, and (b) drop the invoice-side region bound the
     * live body's comment says must not rest on an invariant three layers away — a cross-allocated
     * payment would then count another branch's money as this one's. {@code invoice_id} and
     * {@code payment_id} ARE on the row, so the two ends are reached with an equality apiece and
     * {@link AsOf#at} keeps each of them to exactly one version (B3).
     *
     * <p>{@code payment.status} and not the denormalised {@code alloc.paymentStatus}: the copy on
     * the allocation row is only refreshed when the ALLOCATION is written, so a payment voided
     * without touching its allocations would still read ACTIVE there. (Today voiding clears the
     * allocations, so both answers agree — but the figure must not depend on that.)
     */
    private Collected collectedAsOf(CriteriaQuery<Object[]> cq, CriteriaBuilder cb,
                                    Set<Long> narrowTo) {
        markDrift(PaymentHistory.class);
        if (paidAgainstBookOnly()) {
            markDrift(PaymentAllocationHistory.class);
            markDrift(InvoiceHistory.class);
            Root<PaymentAllocationHistory> alloc = cq.from(PaymentAllocationHistory.class);
            Root<InvoiceHistory> invoice = cq.from(InvoiceHistory.class);
            Root<PaymentHistory> payment = cq.from(PaymentHistory.class);
            List<Predicate> where = new ArrayList<>();
            Instant at = AsOfContext.instant();
            where.add(AsOf.at(at).build(alloc, cq, cb));
            where.add(AsOf.at(at).build(invoice, cq, cb));
            where.add(AsOf.at(at).build(payment, cq, cb));
            where.add(cb.equal(invoice.get("id"), alloc.get("invoiceId")));
            where.add(cb.equal(payment.get("id"), alloc.get("paymentId")));
            where.add(cb.equal(payment.get("status"), PaymentStatus.ACTIVE));
            // Both ends say it plainly, exactly as the live body does: an allocation admitted by
            // the payment arm cannot NAME an account from a branch the caller may not see, and one
            // admitted by the invoice arm cannot COUNT money from a payment there (B1, B3).
            region(invoice, cq, cb, narrowTo, where);
            region(payment, cq, cb, narrowTo, where);
            Predicate onMyInvoices = invoice.get("id")
                    .in(idsInScope(InvoiceHistory.class, scopeResolver.forInvoices(), cq, cb, narrowTo));
            ScopeResolver.Scope payments = scopeResolver.forPayments();
            where.add(payments.predicates().isEmpty() ? onMyInvoices
                    : cb.or(onMyInvoices,
                    payment.get("id").in(idsInScope(PaymentHistory.class, payments, cq, cb, narrowTo))));
            return new Collected(alloc.get("amount"), payment.get("paidAt"), payment.get("id"),
                    account(invoice), where);
        }
        Root<PaymentHistory> payment = cq.from(PaymentHistory.class);
        List<Predicate> where = scoped(payment, cq, cb, scopeResolver.forPayments(), narrowTo);
        where.add(cb.equal(payment.get("status"), PaymentStatus.ACTIVE));
        return new Collected(payment.get("amount"), payment.get("paidAt"), payment.get("id"),
                account(payment), where);
    }

    // UNTOUCHED BY B1, AND DELIBERATELY SO: a region chip in ScopeResolver.Scope.lockedFilters()
    // would make this true for every caller with a grant, silently switching collectedByMonth's
    // query root from Payment to PaymentAllocation for the whole company. That is the whole
    // reason the region axis never enters ScopeResolver, and DashboardRegionTest pins it (B1).
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

    /**
     * What one dashboard request covers: the ids the caller narrowed to — null when they narrowed
     * nothing, so only their own grants bound the figure — and the coverage the payload reports.
     */
    private record RegionAsk(Set<Long> narrowTo, DashboardDtos.RegionCoverage coverage) {}

    private RegionAsk regionAsk(List<Long> requested) {
        Set<Long> mine = visibleRegions();
        Set<Long> asked = requested == null ? Set.of()
                : requested.stream().filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (asked.isEmpty()) return new RegionAsk(null, regionCoverage(mine));
        // A caller's own narrowing can only ever narrow: it is ANDed on top of the mandatory
        // predicate, so what the figure COVERS is the intersection, and a region they cannot see
        // contributes nothing rather than a 403 — identical to one that does not exist (AUTH-08).
        Set<Long> covered = new LinkedHashSet<>(asked);
        if (mine != null) covered.retainAll(mine);
        return new RegionAsk(asked, regionCoverage(covered));
    }

    /**
     * The regions this caller may read, or null for "every region there is" — the same question
     * RegionScope.predicate asks of the same grants, asked again here because a figure has to
     * report what it covered as well as be bounded by it. A hatched thread (asSystem/asRegions)
     * never reaches the dashboard: all five endpoints are @PreAuthorize'd and have a principal (B1).
     */
    private Set<Long> visibleRegions() {
        // A customer login is pinned to its own account rather than to a branch, so no region
        // narrows it, exactly as RegionScope.lockedFilters gives it no chip (B1).
        if (currentUser.isCustomer()) return null;
        RegionGrants grants = currentUser.grants();
        // allRegions FIRST: with() is empty for a wildcard holder too, and reading the two in the
        // other order reports "covers no region" for the one caller who covers every one (B1).
        if (grants.allRegions(RegionRight.VIEW)) return null;
        return grants.with(RegionRight.VIEW);
    }

    /** Null ids mean "every region", which is the wildcard holder's answer and the customer's. */
    private DashboardDtos.RegionCoverage regionCoverage(Set<Long> ids) {
        if (ids == null) return new DashboardDtos.RegionCoverage(true, List.of());
        List<DashboardDtos.RegionRef> refs = new ArrayList<>();
        // A region deleted since the grant was written is skipped rather than half-named, the
        // RegionDtos.heldBy rule; an id nobody holds is skipped for the same reason (B1).
        regionRepository.findAllById(ids).forEach(r ->
                refs.add(new DashboardDtos.RegionRef(r.getId(), r.getCode(), r.getName())));
        refs.sort(Comparator.comparing(DashboardDtos.RegionRef::code));
        return new DashboardDtos.RegionCoverage(false, List.copyOf(refs));
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

    // From<?, ?> and no longer Root<Invoice>: the same two expressions resolve on the live invoice
    // and on invoice_history, because the mirror spells total and paid_amount under the same
    // attribute names with the same Java types. That is what keeps this unit's hand work to root
    // swaps rather than to a second set of expressions (B3).
    private static Expression<BigDecimal> balance(CriteriaBuilder cb, From<?, ?> inv) {
        return cb.diff(inv.<BigDecimal>get("total"), inv.<BigDecimal>get("paidAmount"));
    }

    private static List<Predicate> open(CriteriaBuilder cb, From<?, ?> inv, List<Predicate> where) {
        where.add(cb.notEqual(inv.get("status"), InvoiceStatus.CANCELLED));
        where.add(cb.greaterThan(balance(cb, inv), BigDecimal.ZERO));
        return where;
    }

    /**
     * WHICH TABLE THE THREE INVOICE FIGURES COUNT: the live one, or the interval mirror when this
     * thread is answering as of a date. One line, because every expression those figures build —
     * {@code total}, {@code paidAmount}, {@code status}, {@code dueDate}, {@code invoiceDate},
     * {@code id} — is spelled and typed the same way on both roots (B3).
     */
    private static Root<?> invoiceRoot(CriteriaQuery<?> cq) {
        return AsOfContext.isActive() ? cq.from(InvoiceHistory.class) : cq.from(Invoice.class);
    }

    /**
     * Downgrade this figure if the mirror it read holds a row the reconciler REPAIRED rather than
     * watched happen. Guarded on {@code isActive()} outside the call and not inside it, because
     * {@code AsOfContext.instant()} throws when nothing is open — evaluating the argument is
     * already too late. A no-op on every live read (B3).
     */
    private void markDrift(Class<? extends HistoryRow> mirror) {
        if (!AsOfContext.isActive()) return;
        historyDrift.markIfDrifted(mirror, AsOfContext.instant());
    }

    // Non-static from B1 onwards, because the region axis is a property of the caller and not of
    // the root: the book predicates come from ScopeResolver and the region clause from RegionScope,
    // and every figure gets both or neither (B1).
    //
    // NO dashboard figure nets a pending change, and that is a decision rather than an omission.
    // Every tile on this screen is money that HAS moved — collected, outstanding, aged, promised —
    // and a held change has moved none. A figure mixing applied and unapplied money would be
    // wrong on every screen it appeared on, and the one that netted a held PAYMENT_VOID out of
    // "collected this month" would be quietly reporting a refund nobody has agreed to yet. What
    // is waiting is counted, never summed into an amount, and it is counted on the approvals
    // queue and on the four lists' own awaitingApprovalCount tiles (B2).
    private List<Predicate> scoped(Root<?> root, CriteriaQuery<?> cq, CriteriaBuilder cb,
                                   ScopeResolver.Scope scope, Set<Long> narrowTo) {
        List<Predicate> out = new ArrayList<>();
        asOf(root, cq, cb, out);
        region(root, cq, cb, narrowTo, out);
        scope.predicates().forEach(f -> {
            Predicate p = f.build(root, cq, cb);
            if (p != null) out.add(p);
        });
        return out;
    }

    /**
     * THE LINE THAT MAKES A FIGURE COUNT RECORDS AND NOT VERSIONS. A mirror root is unfiltered by
     * everything else here — the region clause, the book predicates and the caller's narrowing all
     * resolve per record, and none of them looks at the interval columns — so without this an
     * as-of figure would sum every VERSION of every invoice and a five-times-edited invoice would
     * be counted five times.
     *
     * <p>It lives in {@code scoped()} rather than beside each {@code cq.from(...)} deliberately.
     * The unit's text prepends it to each figure's where list by hand; there are five figures, two
     * {@code idsInScope} subqueries and two branches of {@link #collectedAsOf} that need it, and a
     * root swapped without its interval clause is silent — the figure is simply too big. Asking
     * the ROOT whether it is a mirror is one question that cannot be forgotten (B3).
     */
    private static void asOf(Root<?> root, CriteriaQuery<?> cq, CriteriaBuilder cb,
                             List<Predicate> out) {
        if (!AsOfContext.isActive()) return;
        if (!HistoryRow.class.isAssignableFrom(root.getJavaType())) return;
        out.add(AsOf.at(AsOfContext.instant()).build(root, cq, cb));
    }

    /** Both region clauses for one root or join: the mandatory one, then the caller's own ask. */
    private void region(From<?, ?> root, CriteriaQuery<?> cq, CriteriaBuilder cb,
                        Set<Long> narrowTo, List<Predicate> out) {
        Predicate mandatory = regionScope.predicate(root, cq, cb, RegionRight.VIEW, null);
        if (mandatory != null) out.add(mandatory);
        if (narrowTo != null) out.add(inRegions(root, cq, cb, narrowTo));
    }

    /**
     * The narrowing the caller asked for with ?region=3, ANDed on top of the mandatory predicate
     * and never replacing it, so a region they cannot see returns zero rows rather than a 403 —
     * exactly what filter=regionId:in:3 does on a list (AUTH-08, B1).
     *
     * <p>THE VIA_CUSTOMER_ID ARM IS WHAT STOPS THE FIRST NARROWED AS-OF REQUEST BEING A 500, and
     * R6 flagged it when it wrote the throw. Every mirror root a figure counts is VIA_CUSTOMER_ID
     * — it keeps a flat {@code customer_id} with no association to walk — so without this arm
     * {@code GET /api/dashboard/billed-by-month?asOf=…&region=3} would be the one request in the
     * feature that threw. The throw STAYS for every other axis, because a root narrowed by the
     * wrong rule is worse than a loud failure (B1, B3).
     *
     * <p>LIVE, BOTH ARMS READ TODAY'S PLACEMENT; UNDER ?asOf BOTH READ THE LEDGER AT THAT DATE,
     * and the second half is the answer to the deviation this method used to record. The
     * narrowing has to mean the same thing here as it does on a list, where
     * {@code filter=regionId:eq:} resolves through RegionSchemas.AS_OF_REGION_ID to
     * {@code RegionPredicates.asOfRegionFilter} over {@code customer_region_history} — otherwise
     * one screen bills January's money to the branch the account sits in TODAY while the other
     * bills it to the branch it was in THEN, under the same "as of 31 January" chip. It is the
     * same ledger reading the mandatory clause above already makes, so this adds a call and not a
     * mechanism (B1, B3).
     */
    private static Predicate inRegions(From<?, ?> root, CriteriaQuery<?> cq, CriteriaBuilder cb,
                                       Set<Long> regionIds) {
        RegionAxis axis = RegionAxes.of(root.getJavaType());
        LocalDate at = AsOfContext.date();
        // Every root and join a figure counts reads its region through its customer — by walking
        // the association live, or by the flat foreign key a mirror row keeps instead. Anything
        // else fails loudly rather than being narrowed by the wrong rule or silently not at
        // all (B1).
        return switch (axis) {
            case VIA_CUSTOMER -> {
                From<?, ?> customer = ColumnDef.leftJoin(root, "customer");
                yield at == null
                        ? customer.get("region").get("id").in(regionIds)
                        : RegionPredicates.asOf(customer.<Long>get("id"), cq, cb, regionIds, at);
            }
            case VIA_CUSTOMER_ID -> at == null
                    ? RegionPredicates.customerRegionIn(root.<Long>get("customerId"), cq, cb, regionIds)
                    : RegionPredicates.asOf(root.<Long>get("customerId"), cq, cb, regionIds, at);
            default -> throw new IllegalStateException("No dashboard region narrowing for "
                    + root.getJavaType().getSimpleName() + " (axis " + axis + ")");
        };
    }

    private <T> Subquery<Long> idsInScope(Class<T> type, ScopeResolver.Scope scope,
                                          CriteriaQuery<?> cq, CriteriaBuilder cb, Set<Long> narrowTo) {
        Subquery<Long> sq = cq.subquery(Long.class);
        Root<T> root = sq.from(type);
        sq.select(root.get("id"))
                .where(scoped(root, cq, cb, scope, narrowTo).toArray(new Predicate[0]));
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
