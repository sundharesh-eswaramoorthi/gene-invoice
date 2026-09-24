package com.geneinvoice.common.query;

import com.geneinvoice.common.BadRequestException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * Compiles a {@link ConditionNode} tree into ONE {@link PredicateFactory}, so a rule's conditions
 * ride the existing {@code scope} argument and {@code TableQueryExecutor} is not modified at all
 * (A2).
 *
 * <p>Every leaf goes through the same {@code FilterPredicates.build} the filter bar goes through,
 * so every operator, every value coercion, {@code Strings.escapeLike}, the date-only {@code lte}
 * rule, relative dates, enum chips and every custom {@code PredicateResolver} work unchanged
 * inside an OR. Each custom filter builds its own {@code q.subquery(...)} (pocSeatPredicate,
 * promiseLinkPredicate) and {@code ColumnDef.leftJoin} reuses an existing LEFT join, which is
 * correct for a to-one association (A2).
 *
 * <p>THE ONE CONSTRAINT: {@code Conditions} must never be used with a to-many {@code nested} path.
 * Reusing one LEFT join across the branches of an OR is right for a to-one association and wrong
 * for a to-many one, where each branch needs its own join or an EXISTS to mean what it says. No
 * to-many path exists in any registered schema today; a schema that adds one must give that column
 * a custom {@code PredicateResolver} with its own subquery before a rule may name it (A2).
 *
 * <p>Region is NOT applied here. {@code TableQueryExecutor.predicates} adds the region predicate
 * itself, before any scope factory, so a condition tree that matches every record is still bounded
 * by the caller's grants and cannot widen past them (A2, B1).
 */
public final class Conditions {

    private Conditions() {}

    /** Counting the leaf's own level, so five means four groups around a leaf (A2). */
    public static final int MAX_DEPTH = 5;
    public static final int MAX_LEAVES = 40;

    /**
     * Rejects at rule-SAVE time what would otherwise fail at rule-RUN time, in the words the
     * filter bar already uses, so a rule can never name a column the schema does not offer and the
     * author hears about it while they are still looking at the screen (A2).
     */
    public static void validate(ConditionNode node, TableSchema schema) {
        if (node == null) return;
        int leaves = walk(node, schema, 1);
        if (leaves > MAX_LEAVES) {
            throw new BadRequestException(
                    "A rule may not have more than " + MAX_LEAVES + " conditions");
        }
    }

    private static int walk(ConditionNode n, TableSchema schema, int depth) {
        if (depth > MAX_DEPTH) {
            throw new BadRequestException(
                    "Conditions may not be nested more than " + MAX_DEPTH + " deep");
        }
        if (n instanceof ConditionNode.Leaf leaf) {
            // The very call TableQuery.parse makes for a `?filter=` chip, so the 400 text is the
            // EXISTING one ("Unknown column: x" / "Column is not filterable: x" / "Operator
            // contains is not valid for column balance (MONEY)") and there is no second
            // vocabulary for the same mistake (A2).
            schema.requireFilterable(leaf.spec().field(), leaf.spec().operator());
            return 1;
        }
        ConditionNode.Group g = (ConditionNode.Group) n;
        // An empty group has no honest truth value once it is nested: as a child of an OR it would
        // read "or anything", which quietly turns the whole rule into "every record" (A2).
        if (g.of().isEmpty() && depth > 1) {
            throw new BadRequestException("A group needs at least one condition");
        }
        int leaves = 0;
        for (ConditionNode c : g.of()) {
            leaves += walk(c, schema, depth + 1);
        }
        return leaves;
    }

    /**
     * One {@link PredicateFactory} for the whole tree.
     *
     * <p>Returns null for a null tree, meaning "this rule has no conditions". CAUTION, verified
     * against the source: {@code TableQueryExecutor.predicates} skips a factory that RETURNS null
     * ({@code if (p != null)}), but it does NOT skip a null FACTORY in the scope list — it calls
     * {@code f.build(...)} on every element. A caller must therefore add the result only when it
     * is non-null (A2).
     */
    public static PredicateFactory factory(ConditionNode node, TableSchema schema) {
        return node == null ? null : (root, q, cb) -> build(node, schema, root, q, cb);
    }

    static Predicate build(ConditionNode n, TableSchema schema,
                           Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb) {
        if (n instanceof ConditionNode.Leaf leaf) {
            // Re-checked at run time and not only at save time: a column can stop being filterable
            // between the save and the run, and a rule that outlived its schema must fail with the
            // filter bar's own 400 rather than build a predicate over a column that has gone (A2).
            ColumnDef def = schema.requireFilterable(leaf.spec().field(), leaf.spec().operator());
            // Legal only from inside this package, which is the whole reason this class is not in
            // com.geneinvoice.automation (A2).
            return FilterPredicates.build(leaf.spec(), def, root, q, cb);
        }
        ConditionNode.Group g = (ConditionNode.Group) n;
        // An empty top-level group is "every record", exactly what the executor's own no-filter
        // path means; a nested empty group never reaches here because validate refuses it (A2).
        if (g.of().isEmpty()) return cb.conjunction();
        Predicate[] parts = g.of().stream()
                .map(c -> build(c, schema, root, q, cb))
                .toArray(Predicate[]::new);
        return g.op() == ConditionNode.Connector.OR ? cb.or(parts) : cb.and(parts);
    }
}
