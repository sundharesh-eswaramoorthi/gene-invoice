package com.geneinvoice.assignee;

import com.geneinvoice.auth.AppUserDetails;
import com.geneinvoice.auth.AppUserDetailsService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.auth.RunAs;
import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.scope.ViewScope;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Whether someone else could open a record (§5.9.1): nobody is assigned, or emailed by a rule, about a record they
 * would get a 403 or a 404 on. Asked as that person, today, in every region they hold.
 *
 * <p>Non-throwing and transaction-neutral (CF-2): it answers false rather than failing, and calls nothing that
 * throws across a {@code @Transactional} boundary, so it can be asked inside a write transaction that carries on and
 * commits after a "no".
 */
@Component
@RequiredArgsConstructor
public class VisibilityProbe {

    private static final Map<EmailEntityType, ScopeResolver.Kind> KINDS = Map.of(
            EmailEntityType.CUSTOMER, ScopeResolver.Kind.CUSTOMER,
            EmailEntityType.INVOICE, ScopeResolver.Kind.INVOICE,
            EmailEntityType.PAYMENT, ScopeResolver.Kind.PAYMENT,
            EmailEntityType.PROMISE, ScopeResolver.Kind.PROMISE,
            EmailEntityType.DISPUTE, ScopeResolver.Kind.DISPUTE);

    private static final Map<EmailEntityType, String> VIEW_PRIVILEGE = Map.of(
            EmailEntityType.CUSTOMER, Privileges.CUSTOMER_VIEW,
            EmailEntityType.INVOICE, Privileges.INVOICE_VIEW,
            EmailEntityType.PAYMENT, Privileges.PAYMENT_VIEW,
            EmailEntityType.PROMISE, Privileges.PROMISE_VIEW,
            EmailEntityType.DISPUTE, Privileges.DISPUTE_VIEW);

    private final AppUserDetailsService principals;
    private final RunAs runAs;
    private final ScopeResolver scopeResolver;
    private final TableQueryExecutor queryExecutor;
    private final CurrentUser currentUser;

    /**
     * Whether {@code userId} holds the record's VIEW privilege and sees it: in full in its region, or in their
     * book there. An inactive person, a customer login, a missing one or a kind of record no staff scope covers
     * answers false.
     */
    public boolean canSee(Long userId, EmailEntityType type, Long id, Long regionId) {
        ScopeResolver.Kind kind = KINDS.get(type);
        if (id == null || kind == null) return false;
        return asThem(userId, type, regionId, () -> queryExecutor.inScope(kind.type(), kind.schema(), id,
                scopeResolver.scope(kind, ScopeResolver.Access.VIEW, ScopeResolver.Reach.GRANTED).predicates()));
    }

    /**
     * As {@link #canSee}, for a dispute or a promise not made yet that will be in {@code regionId}, so a rule's action
     * can ask before its lock (§5.10.6). A dispute has no book: seeing its region is seeing it. In a book, a promise
     * is seen only by its Collection POC, {@code collectionPocUserId}.
     */
    public boolean wouldSee(Long userId, EmailEntityType type, Long regionId, Long collectionPocUserId) {
        if (type != EmailEntityType.DISPUTE && type != EmailEntityType.PROMISE) return false;
        return asThem(userId, type, regionId,
                () -> type == EmailEntityType.PROMISE && Objects.equals(userId, collectionPocUserId));
    }

    private boolean asThem(Long userId, EmailEntityType type, Long regionId, BooleanSupplier inBook) {
        ScopeResolver.Kind kind = KINDS.get(type);
        if (userId == null) return false;
        AppUserDetails principal = principals.principalFor(userId).orElse(null);
        if (principal == null || !principal.getUser().isActive() || principal.getUser().getCustomerId() != null) {
            return false;
        }
        return runAs.call(principal, ViewScope.LIVE_ALL, WriteOrigin.system(), () -> {
            if (!currentUser.has(VIEW_PRIVILEGE.get(type))) return false;
            return switch (scopeResolver.tier(kind, ScopeResolver.Access.VIEW, regionId)) {
                case FULL -> true;
                case NONE -> false;
                case BOOK -> inBook.getAsBoolean();
            };
        });
    }
}
