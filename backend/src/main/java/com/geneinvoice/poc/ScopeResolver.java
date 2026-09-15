package com.geneinvoice.poc;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.privilege.Privileges;
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

/**
 * Works out the predicates a list query must always carry for the calling user.
 *
 * <p>Two different things live here. A customer-scoped account is <em>restricted</em>: it can only
 * ever see its own rows and no filter can widen that (AC-D10). A POC without
 * {@link Privileges#SCOPE_OVERRIDE} is <em>defaulted</em> to their own book: the same predicate is
 * applied, and reported back as a locked filter chip so the UI can say so out loud (AC-A6).
 */
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

    /**
     * True when POC identity may be shown to the caller. A customer-scoped account never sees it,
     * whatever privileges its role happens to carry (AC-A8).
     */
    public boolean canSeePoc() {
        User u = currentUser.require();
        return u.getCustomerId() == null
                && userRepository.hasPrivilege(u.getId(), Privileges.POC_VIEW);
    }

    /** True when the caller may look beyond their own book. */
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

    public Scope forInvoices() {
        return build("customer", PocType.SALES, "salesPocUserId",
                (root, q, cb, me) -> cb.equal(root.get("salesPoc").get("id"), me));
    }

    public Scope forPayments() {
        return build("customer", PocType.COLLECTION, "collectionPocUserId",
                (root, q, cb, me) -> cb.equal(root.get("collectionPoc").get("id"), me));
    }

    public Scope forPromises() {
        return build("customer", PocType.COLLECTION, "collectionPocUserId",
                (root, q, cb, me) -> cb.equal(root.get("collectionPoc").get("id"), me));
    }

    /**
     * A customer belongs to a POC's book when they hold a POC seat on it, or — for a sales
     * person, who has no seat on the customer itself — when they own one of its invoices.
     */
    public Scope forCustomers() {
        List<PredicateFactory> predicates = new ArrayList<>();
        List<String> locked = new ArrayList<>();
        User me = currentUser.require();

        if (me.getCustomerId() != null) {
            Long own = me.getCustomerId();
            predicates.add((root, q, cb) -> cb.equal(root.get("id"), own));
            return new Scope(predicates, List.of());
        }
        if (canSeeEverything() || !isAnyPoc()) {
            return Scope.empty();
        }
        Long meId = me.getId();
        predicates.add((root, q, cb) -> cb.or(customerHasPocSeat(root, q, cb, meId),
                customerHasInvoiceOwnedBy(root, q, cb, meId)));
        locked.add("myBook:eq:" + meId);
        return new Scope(predicates, locked);
    }

    /** Disputes carry no POC of their own; only the customer restriction applies. */
    public Scope forDisputes() {
        User me = currentUser.require();
        if (me.getCustomerId() != null) {
            Long own = me.getCustomerId();
            return new Scope(List.of((root, q, cb) -> cb.equal(root.get("customerId"), own)),
                    List.of());
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

    private Scope build(String customerAssociation, PocType type, String lockedColumn, BookPredicate book) {
        User me = currentUser.require();
        if (me.getCustomerId() != null) {
            Long own = me.getCustomerId();
            return new Scope(
                    List.of((root, q, cb) -> cb.equal(root.get(customerAssociation).get("id"), own)),
                    List.of());
        }
        if (canSeeEverything() || !isAssignableAs(type)) {
            return Scope.empty();
        }
        Long meId = me.getId();
        return new Scope(
                List.of((root, q, cb) -> book.build(root, q, cb, meId)),
                List.of(lockedColumn + ":eq:" + meId));
    }

    private Predicate customerHasPocSeat(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb, Long meId) {
        Subquery<Long> sq = q.subquery(Long.class);
        Root<CustomerPoc> seat = sq.from(CustomerPoc.class);
        sq.select(cb.literal(1L)).where(
                cb.equal(seat.get("customer").get("id"), root.get("id")),
                cb.equal(seat.get("user").get("id"), meId));
        return cb.exists(sq);
    }

    private Predicate customerHasInvoiceOwnedBy(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb, Long meId) {
        Subquery<Long> sq = q.subquery(Long.class);
        Root<Invoice> inv = sq.from(Invoice.class);
        sq.select(cb.literal(1L)).where(
                cb.equal(inv.get("customer").get("id"), root.get("id")),
                cb.equal(inv.get("salesPoc").get("id"), meId));
        return cb.exists(sq);
    }
}
