package com.geneinvoice.common.query;

import java.util.List;
import java.util.Objects;

/**
 * The AND/OR condition tree a rule is written in (A2).
 *
 * <p>A leaf is an existing {@link FilterSpec} — the very thing {@code ?filter=} carries, the very
 * thing the Flutter filter bar produces and the very thing {@code TableQuery.parse} builds — so a
 * rule's conditions and a user's filter chips are ONE grammar: one operator vocabulary, one value
 * coercion, one set of 400s. There is deliberately no second codec (A2).
 *
 * <p>This interface lives in {@code com.geneinvoice.common.query} rather than in
 * {@code com.geneinvoice.automation} because {@link Conditions}, which compiles it, has to call
 * the package-private {@code FilterPredicates}; putting either class outside the package would
 * mean widening that class to the whole application just to serve one caller (A2).
 *
 * <p>There is deliberately NO Jackson import here: this package is Jackson-free today and stays
 * so. The wire edge is {@code com.geneinvoice.automation.ConditionJson} (A2).
 */
public sealed interface ConditionNode permits ConditionNode.Leaf, ConditionNode.Group {

    /** How a group joins its children. There is no NOT: a negating operator already exists on
     *  every column type that can be negated (neq, notIn, isEmpty), so a third connector would
     *  add a second way to say the same thing (A2). */
    enum Connector { AND, OR }

    record Leaf(FilterSpec spec) implements ConditionNode {
        public Leaf {
            // A leaf with no spec would only surface as an NPE deep inside the criteria build, on
            // a rule that had already been saved and scheduled (A2).
            Objects.requireNonNull(spec, "A condition leaf needs a filter");
        }
    }

    record Group(Connector op, List<ConditionNode> of) implements ConditionNode {
        public Group {
            // A null connector would silently read as AND at the one place the connector is
            // examined, which is the quietest way to build the wrong rule (A2).
            Objects.requireNonNull(op, "A condition group needs a connector");
            // Copied, so a tree cannot be edited underneath a rule after it was validated;
            // List.copyOf also refuses a null child, which the compiler could not (A2).
            of = List.copyOf(of);
        }
    }
}
