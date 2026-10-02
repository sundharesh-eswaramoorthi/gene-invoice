package com.geneinvoice.common.query;

import com.geneinvoice.common.BadRequestException;
import jakarta.persistence.criteria.Predicate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A rule's condition (A2): an AND/OR tree whose leaves are the list pages' own filters over the entity's columns
 * (§5.10.2). A leaf means exactly what the same filter means on the list, so an AND-only tree matches the same rows
 * as the list asked with those filters. Lives beside {@link FilterPredicates}, which it builds its leaves with.
 */
public sealed interface Condition permits Condition.Leaf, Condition.Group {

    int MAX_DEPTH = 4;
    int MAX_LEAVES = 30;
    int MAX_CHILDREN = 20;

    enum Junction { AND, OR }

    record Leaf(FilterSpec spec) implements Condition {}

    record Group(Junction op, List<Condition> children) implements Condition {
        public Group {
            children = List.copyOf(children);
        }
    }

    /** A leaf that cannot be built against the schema (a bad value, found only when it is coerced), by its path. */
    final class InvalidLeafException extends BadRequestException {
        private final String path;

        public InvalidLeafException(String path, String message) {
            super(message);
            this.path = path;
        }

        public String path() {
            return path;
        }
    }

    /**
     * Checks the limits and every leaf against the schema: a column that exists and may be filtered, and an operator
     * its type takes. Problems go into {@code errors} under the leaf's path, e.g.
     * {@code condition.children[1].children[0].filter}.
     */
    default void validate(TableSchema schema, String path, Map<String, String> errors) {
        if (leaves().size() > MAX_LEAVES) {
            errors.put(path, "A condition has at most " + MAX_LEAVES + " filters");
        }
        check(this, schema, path, 1, errors);
    }

    private static void check(Condition c, TableSchema schema, String path, int depth, Map<String, String> errors) {
        if (c instanceof Leaf leaf) {
            try {
                schema.requireFilterable(leaf.spec().field(), leaf.spec().operator());
            } catch (BadRequestException e) {
                errors.put(path + ".filter", e.getMessage());
            }
            return;
        }
        Group group = (Group) c;
        if (depth > MAX_DEPTH) {
            errors.put(path, "Groups go at most " + MAX_DEPTH + " deep");
            return;
        }
        if (group.children().isEmpty()) {
            errors.put(path, "A group needs at least one condition");
        } else if (group.children().size() > MAX_CHILDREN) {
            errors.put(path, "A group has at most " + MAX_CHILDREN + " conditions");
        }
        for (int i = 0; i < group.children().size(); i++) {
            check(group.children().get(i), schema, path + ".children[" + i + "]", depth + 1, errors);
        }
    }

    /** The whole tree as one predicate: leaves as the list builds them, groups as {@code and} / {@code or}. */
    default PredicateFactory toPredicate(TableSchema schema) {
        return toPredicate(schema, "condition");
    }

    private PredicateFactory toPredicate(TableSchema schema, String path) {
        if (this instanceof Leaf leaf) {
            return (root, q, cb) -> {
                try {
                    ColumnDef def = schema.requireFilterable(leaf.spec().field(), leaf.spec().operator());
                    return FilterPredicates.build(leaf.spec(), def, root, q, cb);
                } catch (InvalidLeafException e) {
                    throw e;
                } catch (BadRequestException e) {
                    throw new InvalidLeafException(path + ".filter", e.getMessage());
                }
            };
        }
        Group group = (Group) this;
        List<PredicateFactory> parts = new ArrayList<>();
        for (int i = 0; i < group.children().size(); i++) {
            parts.add(group.children().get(i).toPredicate(schema, path + ".children[" + i + "]"));
        }
        return (root, q, cb) -> {
            Predicate[] built = parts.stream().map(p -> p.build(root, q, cb)).toArray(Predicate[]::new);
            return group.op() == Junction.AND ? cb.and(built) : cb.or(built);
        };
    }

    /** Every leaf, left to right. */
    default List<Leaf> leaves() {
        List<Leaf> out = new ArrayList<>();
        collect(this, out);
        return out;
    }

    private static void collect(Condition c, List<Leaf> out) {
        if (c instanceof Leaf leaf) {
            out.add(leaf);
        } else {
            ((Group) c).children().forEach(child -> collect(child, out));
        }
    }

    /** Whether no OR appears anywhere: then the tree is exactly a list page's filters, in any order. */
    default boolean andOnly() {
        if (this instanceof Group group) {
            return group.op() == Junction.AND && group.children().stream().allMatch(Condition::andOnly);
        }
        return true;
    }
}
