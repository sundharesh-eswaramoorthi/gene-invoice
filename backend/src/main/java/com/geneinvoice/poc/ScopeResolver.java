package com.geneinvoice.poc;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.history.Temporal;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.region.RegionRights;
import com.geneinvoice.task.Task;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.user.User;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which records the caller may see, region by region (B1). Every answer is computed in memory from the
 * principal's {@link RegionRights}: no SQL is spent deciding scope.
 *
 * <p>In each region the caller holds a grant in, a kind of record is seen in full ({@link Tier#FULL}), only
 * as the caller's own book ({@link Tier#BOOK}), or not at all. Sets (lists, counts, exports, bulk) are also
 * narrowed to the regions the request asks about; by-id reads reach every granted region (I3).
 */
@Component
@RequiredArgsConstructor
public class ScopeResolver {

    private final CurrentUser currentUser;
    private final TableQueryExecutor queryExecutor;

    public enum Access { VIEW, MANAGE }

    /** SELECTED: a set, narrowed by the request's regions. GRANTED: a by-id read, reaching every granted region. */
    public enum Reach { SELECTED, GRANTED }

    public enum Tier { FULL, BOOK, NONE }

    /**
     * Whose records make up a POC's book, for each kind of record. PARENT: no book of its own, but only what is
     * on a record the caller can see (a task).
     */
    public enum Book {
        ANY_POC(null), SALES(PocType.SALES), COLLECTION(PocType.COLLECTION), NONE(null), PARENT(null);

        private final PocType type;

        Book(PocType type) {
            this.type = type;
        }
    }

    public enum Kind {
        CUSTOMER(Customer.class, TableSchemas.CUSTOMERS, Privileges.CUSTOMER_MANAGE, Book.ANY_POC, "myBook"),
        INVOICE(Invoice.class, TableSchemas.INVOICES, Privileges.INVOICE_MANAGE, Book.SALES, "salesPocUserId"),
        PAYMENT(Payment.class, TableSchemas.PAYMENTS, Privileges.PAYMENT_MANAGE, Book.COLLECTION, "collectionPocUserId"),
        PROMISE(PaymentPromise.class, TableSchemas.PROMISES, Privileges.PROMISE_MANAGE, Book.COLLECTION, "collectionPocUserId"),
        DISPUTE(Dispute.class, TableSchemas.DISPUTES, Privileges.DISPUTE_MANAGE, Book.NONE, null),
        TASK(Task.class, TableSchemas.TASKS, Privileges.TASK_MANAGE, Book.PARENT, null);

        private final Class<?> type;
        private final TableSchema schema;
        private final String manage;
        private final Book book;
        private final String lockedColumn;

        Kind(Class<?> type, TableSchema schema, String manage, Book book, String lockedColumn) {
            this.type = type;
            this.schema = schema;
            this.manage = manage;
            this.book = book;
            this.lockedColumn = lockedColumn;
        }

        public Class<?> type() {
            return type;
        }

        public TableSchema schema() {
            return schema;
        }

        public String manage() {
            return manage;
        }
    }

    /** The regions an answer covers, by tier; {@code own} for a customer login, which has no choice of region. */
    public record RegionCoverage(Set<Long> full, Set<Long> book, boolean own) {
        public static final RegionCoverage OWN = new RegionCoverage(Set.of(), Set.of(), true);

        public RegionCoverage {
            full = Set.copyOf(full);
            book = Set.copyOf(book);
        }

        public Set<Long> answered() {
            Set<Long> all = new TreeSet<>(full);
            all.addAll(book);
            return all;
        }

        public boolean mixed() {
            return !full.isEmpty() && !book.isEmpty();
        }
    }

    /**
     * {@code lockedFilters} still means "your book only" (R14): it is set only for a pure book view, and
     * never carries the regions, which travel separately as {@link #regionIds()} and {@link #bookRegionIds()}.
     */
    public record Scope(List<PredicateFactory> predicates, List<String> lockedFilters, RegionCoverage coverage) {
        public List<Long> regionIds() {
            return List.copyOf(coverage.answered());
        }

        public List<Long> bookRegionIds() {
            return List.copyOf(new TreeSet<>(coverage.book()));
        }
    }

    public Scope forCustomers() {
        return scope(Kind.CUSTOMER, Access.VIEW, Reach.SELECTED);
    }

    public Scope forInvoices() {
        return scope(Kind.INVOICE, Access.VIEW, Reach.SELECTED);
    }

    public Scope forPayments() {
        return scope(Kind.PAYMENT, Access.VIEW, Reach.SELECTED);
    }

    public Scope forPromises() {
        return scope(Kind.PROMISE, Access.VIEW, Reach.SELECTED);
    }

    public Scope forDisputes() {
        return scope(Kind.DISPUTE, Access.VIEW, Reach.SELECTED);
    }

    public Scope forTasks() {
        return scope(Kind.TASK, Access.VIEW, Reach.SELECTED);
    }

    public Scope scope(Kind kind, Access access, Reach reach, String... alsoRequired) {
        User me = currentUser.require();
        if (me.getCustomerId() != null) {
            return new Scope(List.of(ownPredicate(kind, me.getCustomerId())), List.of(), RegionCoverage.OWN);
        }
        RegionRights rights = currentUser.rights();
        Long meId = me.getId();
        Set<Long> granted = new TreeSet<>();
        boolean anyVisible = false;
        for (Long region : rights.grantedRegions()) {
            if (!gate(rights, kind, access, region, alsoRequired)) continue;
            granted.add(region);
            anyVisible |= tierIn(rights, kind, access, region, alsoRequired) != Tier.NONE;
        }
        Set<Long> asked = reach == Reach.SELECTED ? ViewScopeHolder.current().narrow(granted) : granted;
        Set<Long> full = new TreeSet<>();
        Set<Long> book = new TreeSet<>();
        for (Long region : asked) {
            switch (tierIn(rights, kind, access, region, alsoRequired)) {
                case FULL -> full.add(region);
                case BOOK -> book.add(region);
                case NONE -> { }
            }
        }
        RegionCoverage coverage = new RegionCoverage(full, book, false);
        if (full.isEmpty() && book.isEmpty()) {
            // Today's "nothing" chip only when the book rule itself hides everything; an empty set that
            // comes from narrowing to other regions carries no chip.
            List<String> locked = !anyVisible && kind.lockedColumn != null
                    ? List.of(kind.lockedColumn + ":eq:" + meId) : List.of();
            return new Scope(List.of((root, q, cb) -> cb.disjunction()), locked, coverage);
        }
        List<Long> fullIds = List.copyOf(full);
        List<Long> bookIds = List.copyOf(book);
        PredicateFactory predicate = (root, q, cb) -> {
            Path<Long> region = root.get("region").get("id");
            Predicate f = fullIds.isEmpty() ? null : region.in(fullIds);
            Predicate b = bookIds.isEmpty() ? null
                    : cb.and(region.in(bookIds), bookPredicate(kind, root, q, cb, meId));
            return f == null ? b : b == null ? f : cb.or(f, b);
        };
        List<String> locked = full.isEmpty() && kind.lockedColumn != null
                ? List.of(kind.lockedColumn + ":eq:" + meId) : List.of();
        List<PredicateFactory> predicates = kind.book == Book.PARENT
                ? List.of(predicate, parentVisible(reach)) : List.of(predicate);
        return new Scope(predicates, locked, coverage);
    }

    /**
     * A task is seen only on a record the caller sees (§5.6.4 step 5): an OR, over the kinds of record the caller
     * may view somewhere, of "on such a record, one in its scope". The record scopes are subqueries of the outer
     * query, as the dashboard's are. A task whose record has since been deleted has no record to be seen through; it
     * stays visible where the caller sees that kind of record in full.
     */
    private PredicateFactory parentVisible(Reach reach) {
        RegionRights rights = currentUser.rights();
        List<TaskEntityType> kinds = new ArrayList<>();
        List<Scope> scopes = new ArrayList<>();
        for (TaskEntityType type : TaskEntityType.values()) {
            if (!rights.anywhere(type.viewPrivilege())) continue;
            kinds.add(type);
            scopes.add(scope(type.kind(), Access.VIEW, reach));
        }
        return (root, q, cb) -> {
            List<Predicate> any = new ArrayList<>();
            for (int i = 0; i < kinds.size(); i++) {
                TaskEntityType type = kinds.get(i);
                Scope parent = scopes.get(i);
                Subquery<Long> seen = Temporal.subquery(q, Long.class, type.kind().type(), cb, (record, sq, where) -> {
                    for (PredicateFactory f : parent.predicates()) {
                        Predicate p = f.build(record, q, cb);
                        if (p != null) where.add(p);
                    }
                    return record.get("id");
                });
                Predicate onIt = root.get("entityId").in(seen);
                List<Long> whole = List.copyOf(parent.coverage().full());
                if (!whole.isEmpty()) {
                    onIt = cb.or(onIt, cb.and(cb.isTrue(root.get("entityDeleted")),
                            root.get("region").get("id").in(whole)));
                }
                any.add(cb.and(cb.equal(root.get("entityType"), type), onIt));
            }
            return any.isEmpty() ? cb.disjunction() : cb.or(any.toArray(new Predicate[0]));
        };
    }

    /** The tier of one region for the caller, in memory. */
    public Tier tier(Kind kind, Access access, Long regionId, String... alsoRequired) {
        User me = currentUser.require();
        RegionRights rights = currentUser.rights();
        if (me.getCustomerId() != null) {
            return regionId != null && rights.holdsAnyIn(regionId) ? Tier.FULL : Tier.NONE;
        }
        if (regionId == null || !gate(rights, kind, access, regionId, alsoRequired)) return Tier.NONE;
        return tierIn(rights, kind, access, regionId, alsoRequired);
    }

    /**
     * A by-id read: a record outside the caller's regions or book is answered as missing (F4). A customer
     * login sees only its own customer's records, as it did through the book predicate before regions:
     * several write paths have no own-customer check of their own. Costs SQL only for a book tier or for
     * a customer login's child record.
     */
    public void requireVisible(Kind kind, Long id, Long regionId, String notFoundMessage) {
        Long own = currentUser.customerIdOrNull();
        if (own != null) {
            boolean mine = kind == Kind.CUSTOMER ? own.equals(id)
                    : queryExecutor.inScope(kind.type, kind.schema, id, List.of(ownPredicate(kind, own)));
            if (!mine) throw new NotFoundException(notFoundMessage);
            return;
        }
        switch (tier(kind, Access.VIEW, regionId)) {
            case FULL -> { }
            case BOOK -> {
                if (!queryExecutor.inScope(kind.type, kind.schema, id,
                        scope(kind, Access.VIEW, Reach.GRANTED).predicates())) {
                    throw new NotFoundException(notFoundMessage);
                }
            }
            case NONE -> throw new NotFoundException(notFoundMessage);
        }
    }

    public boolean canSeePoc() {
        return !currentUser.isCustomer() && currentUser.rights().anywhere(Privileges.POC_VIEW);
    }

    public boolean isAssignableAs(PocType type) {
        return !currentUser.isCustomer() && currentUser.rights().anywhere(type.assignabilityPrivilege());
    }

    public boolean isAssignableIn(PocType type, Long regionId) {
        return !currentUser.isCustomer() && currentUser.hasIn(type.assignabilityPrivilege(), regionId);
    }

    /** Whether the invoice list the caller is looking at is whole, with no book limit to clear. */
    public boolean canSeeEverything() {
        if (currentUser.isCustomer()) return false;
        RegionCoverage coverage = forInvoices().coverage();
        return !coverage.full().isEmpty() && coverage.book().isEmpty();
    }

    /** VIEW needs any grant in the region (the X_VIEW privilege itself is checked by @PreAuthorize, F2). */
    private static boolean gate(RegionRights rights, Kind kind, Access access, Long region, String... alsoRequired) {
        boolean open = access == Access.VIEW ? rights.holdsAnyIn(region) : rights.has(kind.manage, region);
        if (!open) return false;
        for (String extra : alsoRequired) {
            if (!rights.has(extra, region)) return false;
        }
        return true;
    }

    private static Tier tierIn(RegionRights rights, Kind kind, Access access, Long region, String... alsoRequired) {
        if (!gate(rights, kind, access, region, alsoRequired)) return Tier.NONE;
        if (kind.book == Book.NONE || kind.book == Book.PARENT) return Tier.FULL;
        if (rights.has(Privileges.SCOPE_OVERRIDE, region)) return Tier.FULL;
        boolean poc = kind.book == Book.ANY_POC
                ? anyAssignable(rights, region)
                : rights.has(kind.book.type.assignabilityPrivilege(), region);
        return poc ? Tier.BOOK : Tier.NONE;
    }

    private static boolean anyAssignable(RegionRights rights, Long region) {
        for (PocType t : PocType.values()) {
            if (rights.has(t.assignabilityPrivilege(), region)) return true;
        }
        return false;
    }

    private static PredicateFactory ownPredicate(Kind kind, Long own) {
        return switch (kind) {
            case CUSTOMER -> (root, q, cb) -> cb.equal(root.get("id"), own);
            // Their own disputes, never one a rule raised for staff to review (D17, SC-1); as of a date, a version
            // from before disputes could be internal has none recorded, and was a customer's own.
            case DISPUTE -> (root, q, cb) -> cb.and(cb.equal(root.get("customerId"), own),
                    cb.or(cb.isNull(root.get("internal")), cb.isFalse(root.get("internal"))));
            case INVOICE, PAYMENT, PROMISE -> (root, q, cb) -> cb.equal(root.get("customer").get("id"), own);
            // Tasks are internal work: a customer login never sees one (D17).
            case TASK -> (root, q, cb) -> cb.disjunction();
        };
    }

    private Predicate bookPredicate(Kind kind, Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb, Long meId) {
        return switch (kind) {
            case CUSTOMER -> cb.or(customerHasPocSeat(root, q, cb, meId), customerHasInvoiceOwnedBy(root, q, cb, meId));
            case INVOICE -> cb.equal(root.get("salesPoc").get("id"), meId);
            case PAYMENT, PROMISE -> cb.equal(root.get("collectionPoc").get("id"), meId);
            case DISPUTE, TASK -> cb.disjunction();
        };
    }

    /** As of a date, the book is the book then: the seats and invoices as they were (§5.7.14). */
    private Predicate customerHasPocSeat(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb, Long meId) {
        return cb.exists(Temporal.subquery(q, Long.class, CustomerPoc.class, cb, (seat, sq, where) -> {
            where.add(cb.equal(seat.get("customer").get("id"), root.get("id")));
            where.add(cb.equal(seat.get("user").get("id"), meId));
            return cb.literal(1L);
        }));
    }

    private Predicate customerHasInvoiceOwnedBy(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb, Long meId) {
        return cb.exists(Temporal.subquery(q, Long.class, Invoice.class, cb, (inv, sq, where) -> {
            where.add(cb.equal(inv.get("customer").get("id"), root.get("id")));
            where.add(cb.equal(inv.get("salesPoc").get("id"), meId));
            return cb.literal(1L);
        }));
    }
}
