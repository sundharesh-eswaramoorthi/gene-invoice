package com.geneinvoice.poc;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.asof.AsOf;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceHistory;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.task.TaskAssignee;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ScopeResolver {

    private final CurrentUser currentUser;
    private final UserRepository userRepository;

    public record Scope(List<PredicateFactory> predicates, List<String> lockedFilters) {
        static Scope empty() {
            return new Scope(List.of(), List.of());
        }
    }

    public boolean canSeePoc() {
        User u = currentUser.require();
        return u.getCustomerId() == null
                && userRepository.hasPrivilege(u.getId(), Privileges.POC_VIEW);
    }

    /**
     * WHICH BRANCH A RECORD IS FILED IN IS THE COMPANY'S OWN BUSINESS (B1, AUTH-08).
     *
     * <p>A customer login reads its own account, its own invoices, its own payments and its own
     * promises, and before regions existed none of those answers said anything about how the
     * company is organised internally. They do now: regionId and regionName are on every one of
     * those rows, so a customer reading an invoice learns which internal branch handles them, and
     * a customer filtering on regionId can walk the branch id space and count them. It is not a
     * fact the customer can act on and it was never theirs to have.
     *
     * <p>This is the DTO half of the answer and {@code TableSchema.visibleTo(isCustomer())} is the
     * schema half: the columns are marked {@code pocRestricted()}, exactly as the POC identity
     * columns are, so a customer login is not offered them and cannot sort or filter by them, and
     * the two slots come back null on the row for the same caller. Both halves are needed — the
     * schema alone would still hand the branch over in the payload, and the null alone would
     * still let the id space be probed one filter at a time (B1, AUTH-08).
     *
     * <p>Not keyed on a privilege: there is no privilege a customer login could be given that
     * should make the internal branch theirs, so the question is only whether this is a customer.
     */
    public boolean canSeeRegion() {
        return currentUser.require().getCustomerId() == null;
    }

    public boolean canSeeEverything() {
        User u = currentUser.require();
        return u.getCustomerId() == null
                && userRepository.hasPrivilege(u.getId(), Privileges.SCOPE_OVERRIDE);
    }

    public boolean isAssignableAs(PocType type) {
        User u = currentUser.require();
        return u.getCustomerId() == null
                && userRepository.hasPrivilege(u.getId(), type.assignabilityPrivilege());
    }

    /**
     * The three book predicates name the foreign key the row already carries rather than walking
     * the association to read its id, so ONE lambda serves a live root and a mirror root: a
     * history row maps sales_poc_user_id as a plain Long and has no User to walk, and
     * {@code root.get("salesPoc")} would not resolve on it at all. The chip each one locks is
     * already spelled {@code salesPocUserId:eq:12}, so the predicate now says what the chip says.
     *
     * <p>On Hibernate 6 the two spellings emit the SAME SQL — the association-id path is folded
     * onto the owning side's foreign key with no join — so nothing a caller can observe moves
     * here. That was measured, not assumed, and BookRootTest keeps measuring it (B3).
     */
    public Scope forInvoices() {
        return build(PocType.SALES, "salesPocUserId",
                (root, q, cb, me) -> cb.equal(root.get("salesPocUserId"), me));
    }

    public Scope forPayments() {
        return build(PocType.COLLECTION, "collectionPocUserId",
                (root, q, cb, me) -> cb.equal(root.get("collectionPocUserId"), me));
    }

    public Scope forPromises() {
        return build(PocType.COLLECTION, "collectionPocUserId",
                (root, q, cb, me) -> cb.equal(root.get("collectionPocUserId"), me));
    }

    public Scope forCustomers() {
        List<PredicateFactory> predicates = new ArrayList<>();
        List<String> locked = new ArrayList<>();
        User me = currentUser.require();

        if (me.getCustomerId() != null) {
            Long own = me.getCustomerId();
            predicates.add((root, q, cb) -> cb.equal(root.get("id"), own));
            return new Scope(predicates, List.of());
        }
        if (canSeeEverything()) {
            return Scope.empty();
        }
        Long meId = me.getId();
        if (!isAnyPoc()) {
            return nothing("myBook:eq:" + meId);
        }
        predicates.add((root, q, cb) -> cb.or(customerHasPocSeat(root, q, cb, meId),
                customerHasInvoiceOwnedBy(root, q, cb, meId)));
        locked.add("myBook:eq:" + meId);
        return new Scope(predicates, locked);
    }

    public Scope forDisputes() {
        User me = currentUser.require();
        if (me.getCustomerId() != null) {
            Long own = me.getCustomerId();
            return new Scope(List.of((root, q, cb) -> cb.equal(root.get("customerId"), own)),
                    List.of());
        }
        return Scope.empty();
    }

    /**
     * The approvals queue's BOOK scope, and deliberately nothing else (B2, B1 INTEGRATION).
     *
     * <p>B2's own design said this method should contribute
     * {@code regionId in approvableRegions(me) union {rows I raised}} and report it as
     * {@code lockedFilters ["regionId:in:3,7"]}. The blueprint narrows that and it must not be
     * implemented as written: NO region predicate may ever enter Scope.predicates() or
     * Scope.lockedFilters(). PendingChange is classified OWN_ID, so TableQueryExecutor already
     * ANDs the caller's own regions in at VIEW level, and the chip comes from
     * regionScope.lockedFilters(PendingChange.class) through PageResponse.of's fifth argument.
     * Putting a second region clause here would give the queue a rule of its own that nothing
     * else in the application shares — and would silently switch DashboardService's
     * paidAgainstBookOnly root the day anybody reused this Scope.
     *
     * <p>The consequence is correct and is written down rather than discovered: a maker who has
     * since LOST the region no longer sees the change they raised, which is the same answer the
     * replay gives them — 403, "the person who raised this no longer has manage access in that
     * region" (AUTH-08, B2, B1).
     *
     * <p>A customer login sees no staff queue at all, as an empty result and never as an
     * exception, because a denied READ is always cb.disjunction() here (AUTH-08).
     */
    public Scope forApprovals() {
        User me = currentUser.require();
        if (me.getCustomerId() != null) {
            return nothing("approvals:none");
        }
        return Scope.empty();
    }

    /**
     * The task list's BOOK scope, and deliberately nothing else (A6, B1 INTEGRATION).
     *
     * <p>A task is mine if I am ON it, or if its customer is in my book. The first half is what
     * makes "my work" mean something to somebody who is nobody's POC — which is why there is no
     * {@code isAnyPoc()} short-circuit here as there is in forCustomers: a person with no seats at
     * all still owns the tasks somebody handed them.
     *
     * <p>THE INVARIANT ALL FOUR forX METHODS PRESERVE: no region predicate ever enters
     * Scope.predicates() or Scope.lockedFilters(). Task is classified VIA_CUSTOMER_ID, so
     * TableQueryExecutor ANDs the caller's own regions in at VIEW level before this list has said
     * anything, and the chip comes from regionScope.lockedFilters(Task.class) through
     * PageResponse.of's fifth argument. A second region clause here would give tasks a rule of
     * their own that nothing else in the application shares (A6, B1).
     *
     * <p>A customer login is pinned to its own account by customer_id and holds no book, exactly
     * as forDisputes does it. It cannot reach this list today — the CUSTOMER role has no
     * TASK_VIEW — and the clause is here so that it still could not if somebody granted it (A6).
     */
    public Scope forTasks() {
        User me = currentUser.require();
        if (me.getCustomerId() != null) {
            Long own = me.getCustomerId();
            return new Scope(List.of((root, q, cb) -> cb.equal(root.get("customerId"), own)),
                    List.of());
        }
        if (canSeeEverything()) {
            return Scope.empty();
        }
        Long meId = me.getId();
        return new Scope(
                List.of((root, q, cb) -> cb.or(taskHasAssignee(root, q, cb, meId),
                        taskCustomerInBook(root, q, cb, meId))),
                List.of("myTasks:eq:" + meId));
    }

    /**
     * The rules list's BOOK scope, and deliberately nothing else (A1, B1 INTEGRATION).
     *
     * <p>Part A's own design said this should contribute the rule-visibility clause — a rule names
     * a region I may read, or names none and its author may manage one I may read, or I wrote it.
     * The blueprint narrows that and it must not be implemented as written: NO region predicate
     * may ever enter Scope.predicates() or Scope.lockedFilters(). AutomationRule is classified
     * NONE, so there is no axis to ride on and the clause has to be built somewhere — it is built
     * in AutomationRuleService as its own PredicateFactory, beside the soft-delete clause it has
     * to sit with, exactly as B2-READ's forApprovals was narrowed (A1, B1).
     *
     * <p>A customer login sees no staff automation at all, as an empty result and never as an
     * exception, because a denied READ is always cb.disjunction() here (AUTH-08).
     */
    public Scope forAutomationRules() {
        User me = currentUser.require();
        if (me.getCustomerId() != null) {
            return nothing("automationRules:none");
        }
        return Scope.empty();
    }

    /**
     * The run history's BOOK scope, and deliberately nothing else (A5, B1 INTEGRATION).
     *
     * <p>AutomationStep is classified VIA_CUSTOMER_ID, so TableQueryExecutor ANDs the caller's own
     * regions in at VIEW level before this list has said anything — which is what makes "the run
     * history only shows steps about records the reader may see" a property of the axis and not of
     * a scope argument anybody could forget. There is deliberately no POC-book clause either: a
     * step is a record of what the SYSTEM did, and narrowing it by whose desk the account is on
     * would hide half of an engine's behaviour from the person asked to explain it (A5, B1).
     */
    public Scope forAutomationSteps() {
        User me = currentUser.require();
        if (me.getCustomerId() != null) {
            return nothing("automationSteps:none");
        }
        return Scope.empty();
    }

    private boolean isAnyPoc() {
        for (PocType t : PocType.values()) {
            if (isAssignableAs(t)) return true;
        }
        return false;
    }

    @FunctionalInterface
    private interface BookPredicate {
        Predicate build(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb, Long meId);
    }

    /**
     * A customer login is pinned to its own account by the FLAT {@code customer_id} the row
     * carries, not by walking a {@code customer} association to read its id. The two are the same
     * SQL on Hibernate 6.5 (measured) and the same rule forDisputes and forTasks already use — but
     * only one of them resolves on a mirror root, which maps customer_id as a plain Long and has
     * no Customer to walk. Three B3 units in a row reported this line as the last thing in
     * ScopeResolver that would throw under ?asOf and none of them owned it; it is one word, it is
     * in a file this unit is already rewriting, and leaving it would have made "the book
     * predicates are root-agnostic" false for the one caller who cannot choose another (B3).
     */
    private Scope build(PocType type, String lockedColumn, BookPredicate book) {
        User me = currentUser.require();
        if (me.getCustomerId() != null) {
            Long own = me.getCustomerId();
            return new Scope(
                    List.of((root, q, cb) -> cb.equal(root.get("customerId"), own)),
                    List.of());
        }
        if (canSeeEverything()) {
            return Scope.empty();
        }
        Long meId = me.getId();
        if (!isAssignableAs(type)) {
            return nothing(lockedColumn + ":eq:" + meId);
        }
        return new Scope(
                List.of((root, q, cb) -> book.build(root, q, cb, meId)),
                List.of(lockedColumn + ":eq:" + meId));
    }

    private static Scope nothing(String lockedFilter) {
        return new Scope(List.of((root, q, cb) -> cb.disjunction()), List.of(lockedFilter));
    }

    /**
     * A seat I hold on this account — AS OF THE DATE BEING ANSWERED, which is what makes "my book
     * then" mean the accounts that were mine then and not the accounts that are mine now (B3).
     *
     * <p>The switch is on the ROOT of the subquery, not on a second copy of the predicate: live it
     * reads customer_pocs and walks its two associations, and under ?asOf it reads the interval
     * mirror, which maps both foreign keys flat because a mirror row has nothing to walk to. The
     * SAME switch is expressed once more as TableSchemas.pocSeatPredicate, for the
     * successPocUserId / collectionPocUserId filter columns; one unit owns both and the two must
     * move together (B3).
     */
    private Predicate customerHasPocSeat(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb, Long meId) {
        Subquery<Long> sq = q.subquery(Long.class);
        boolean asOf = AsOfContext.isActive();
        Class<?> seatTable = asOf ? CustomerPocHistory.class : CustomerPoc.class;
        Root<?> seat = sq.from(seatTable);
        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(asOf ? seat.get("customerId") : seat.get("customer").get("id"),
                root.get("id")));
        where.add(cb.equal(asOf ? seat.get("userId") : seat.get("user").get("id"), meId));
        if (asOf) where.add(AsOf.at(AsOfContext.instant()).build(seat, q, cb));
        sq.select(cb.literal(1L)).where(where.toArray(new Predicate[0]));
        return cb.exists(sq);
    }

    /**
     * An invoice of this account that I sell — as of the date being answered, for the same reason
     * (B3).
     *
     * <p>Both flat paths resolve on BOTH roots, because B3-BOOKROOT mapped customer_id and
     * sales_poc_user_id a second time read-only on Invoice, so only the root and the interval
     * clause change. Measured on Hibernate 6.5: the live SQL is unchanged, because an
     * association-id path is already folded onto the owning side's foreign key (B3).
     */
    private Predicate customerHasInvoiceOwnedBy(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb, Long meId) {
        Subquery<Long> sq = q.subquery(Long.class);
        boolean asOf = AsOfContext.isActive();
        Class<?> invoiceTable = asOf ? InvoiceHistory.class : Invoice.class;
        Root<?> inv = sq.from(invoiceTable);
        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(inv.get("customerId"), root.get("id")));
        where.add(cb.equal(inv.get("salesPocUserId"), meId));
        if (asOf) where.add(AsOf.at(AsOfContext.instant()).build(inv, q, cb));
        sq.select(cb.literal(1L)).where(where.toArray(new Predicate[0]));
        return cb.exists(sq);
    }

    /** I am on this task. The half of "mine" that owes nothing to the POC book (A6). */
    private Predicate taskHasAssignee(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb, Long meId) {
        Subquery<Long> sq = q.subquery(Long.class);
        Root<TaskAssignee> seat = sq.from(TaskAssignee.class);
        sq.select(cb.literal(1L)).where(
                cb.equal(seat.get("task").get("id"), root.get("id")),
                cb.equal(seat.get("userId"), meId));
        return cb.exists(sq);
    }

    /**
     * This task's customer is in my book: the same two facts forCustomers asks about — a POC seat
     * I hold on the account, or an invoice of the account whose Sales POC is me — spelled against
     * the bare {@code customer_id} a task carries instead of against a Customer root.
     *
     * <p>Two flat correlated subqueries and not an EXISTS-over-Customer wrapping the existing
     * helpers, because those correlate on {@code root.get("id")} and a nested Root would need them
     * to be root-agnostic — which is B3-BOOKROOT's change and not this unit's. It is a second
     * SPELLING of one definition, not a second definition, and the two must move together (A6).
     *
     * <p>AND THEY HAVE. Both arms take the same as-of switch the two helpers above take, because
     * the comment directly over them says they must: without it, a task list and a customer list
     * asked as of the same January date would disagree about whose book an account was in, and
     * only one of them would be right (A6, B3).
     */
    private Predicate taskCustomerInBook(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb, Long meId) {
        boolean asOf = AsOfContext.isActive();

        Subquery<Long> seats = q.subquery(Long.class);
        Class<?> seatTable = asOf ? CustomerPocHistory.class : CustomerPoc.class;
        Root<?> seat = seats.from(seatTable);
        List<Predicate> seatWhere = new ArrayList<>();
        seatWhere.add(cb.equal(asOf ? seat.get("customerId") : seat.get("customer").get("id"),
                root.get("customerId")));
        seatWhere.add(cb.equal(asOf ? seat.get("userId") : seat.get("user").get("id"), meId));
        if (asOf) seatWhere.add(AsOf.at(AsOfContext.instant()).build(seat, q, cb));
        seats.select(cb.literal(1L)).where(seatWhere.toArray(new Predicate[0]));

        Subquery<Long> invoices = q.subquery(Long.class);
        Class<?> invoiceTable = asOf ? InvoiceHistory.class : Invoice.class;
        Root<?> inv = invoices.from(invoiceTable);
        List<Predicate> invoiceWhere = new ArrayList<>();
        invoiceWhere.add(cb.equal(inv.get("customerId"), root.get("customerId")));
        invoiceWhere.add(cb.equal(inv.get("salesPocUserId"), meId));
        if (asOf) invoiceWhere.add(AsOf.at(AsOfContext.instant()).build(inv, q, cb));
        invoices.select(cb.literal(1L)).where(invoiceWhere.toArray(new Predicate[0]));

        return cb.or(cb.exists(seats), cb.exists(invoices));
    }
}
