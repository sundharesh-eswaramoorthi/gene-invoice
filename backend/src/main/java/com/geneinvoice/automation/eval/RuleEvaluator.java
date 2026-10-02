package com.geneinvoice.automation.eval;

import com.geneinvoice.automation.rule.RuleDefinition;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.query.FilterSpec;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.poc.ScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Which records a rule's condition matches (§5.10.3), as whoever is signed in and for the regions the bound view
 * asks about: the owner in the consumer, the caller (or the owner) in a preview. It is the list's own query, so the
 * rows are the list's rows; while a point is bound the query reads versions, which is how a rule is evaluated as of a
 * moment (D10). Sets are paged by id, never read whole (R18).
 */
@Component
@RequiredArgsConstructor
public class RuleEvaluator {

    private final TableQueryExecutor queryExecutor;
    private final ScopeResolver scopeResolver;

    /** Narrows a set run: to the records asked for by hand, or to one customer's (a CHILDREN event). */
    public record Restriction(List<Long> ids, Long customerId) {
        public static final Restriction NONE = new Restriction(null, null);

        public static Restriction ids(List<Long> ids) {
            return new Restriction(List.copyOf(ids), null);
        }

        public static Restriction customer(Long customerId) {
            return new Restriction(null, customerId);
        }
    }

    @Transactional(readOnly = true)
    public boolean matches(RuleDefinition r, Long id) {
        return built(() -> queryExecutor.count(r.entity().type(), r.entity().schema(),
                new TableQuery(0, 1, "id", true, List.of(FilterSpec.parse("id:eq:" + id))), predicates(r))) > 0;
    }

    /** The next page of matches after {@code afterId}, in id order. */
    @Transactional(readOnly = true)
    public List<Long> matchingPage(RuleDefinition r, long afterId, int pageSize, Restriction restriction) {
        List<PredicateFactory> scope = predicates(r);
        if (restriction.ids() != null) {
            List<Long> ids = restriction.ids();
            scope.add((root, q, cb) -> ids.isEmpty() ? cb.disjunction() : root.get("id").in(ids));
        }
        if (restriction.customerId() != null) {
            Long customerId = restriction.customerId();
            scope.add((root, q, cb) -> cb.equal(switch (r.entity()) {
                case CUSTOMER -> root.get("id");
                case INVOICE, PAYMENT -> root.get("customer").get("id");
            }, customerId));
        }
        return built(() -> queryExecutor.ids(r.entity().type(), r.entity().schema(),
                new TableQuery(0, pageSize, "id", true, List.of(FilterSpec.parse("id:gt:" + afterId))), scope,
                pageSize));
    }

    @Transactional(readOnly = true)
    public long count(RuleDefinition r) {
        return built(() -> queryExecutor.count(r.entity().type(), r.entity().schema(),
                new TableQuery(0, 1, "id", true, List.of()), predicates(r)));
    }

    /** The newest {@code n} matches. */
    @Transactional(readOnly = true)
    public List<Long> sample(RuleDefinition r, int n) {
        return page(r, 0, n);
    }

    /** One page of matches, newest first, read at its offset. */
    @Transactional(readOnly = true)
    public List<Long> page(RuleDefinition r, int page, int size) {
        return built(() -> queryExecutor.idsPage(r.entity().type(), r.entity().schema(),
                new TableQuery(page, size, "id", false, List.of()), predicates(r)));
    }

    /** What the signed-in person may see of the rule's entity, in the regions asked about, and the condition. */
    private List<PredicateFactory> predicates(RuleDefinition r) {
        List<PredicateFactory> scope = new ArrayList<>(scopeResolver.scope(r.entity().kind(),
                ScopeResolver.Access.VIEW, ScopeResolver.Reach.SELECTED).predicates());
        if (r.condition() != null) scope.add(r.condition().toPredicate(r.entity().schema()));
        return scope;
    }

    private static <T> T built(Supplier<T> query) {
        try {
            return query.get();
        } catch (RuleDefinitionException e) {
            throw e;
        } catch (BadRequestException e) {
            throw new RuleDefinitionException("The rule's condition cannot be used: " + e.getMessage());
        }
    }
}
