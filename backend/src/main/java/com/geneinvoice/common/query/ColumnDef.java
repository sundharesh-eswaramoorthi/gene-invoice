package com.geneinvoice.common.query;

import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;

import java.util.List;

/**
 * One filterable/sortable column of a table. Filter values are bound through the criteria API via
 * the column's own resolver, so no user-supplied string ever reaches the persistence layer as
 * query text (AC-D9).
 */
public record ColumnDef(
        String name,
        String label,
        ColumnType type,
        boolean sortable,
        boolean filterable,
        List<String> enumValues,
        /** Non-null for REFERENCE columns: which picker the UI should show. */
        String referenceKind,
        /** True when the column carries POC identity and must be hidden from customer-scoped users. */
        boolean pocRestricted,
        PathResolver path,
        /** When set, replaces the generic path-based filtering for this column. */
        PredicateResolver customFilter
) {

    public static Builder of(String name, String label, ColumnType type) {
        return new Builder(name, label, type);
    }

    /** A plain attribute on the root. */
    public static PathResolver attr(String attribute) {
        return (root, q, cb) -> root.get(attribute);
    }

    /** A nested attribute reached through a left join, e.g. {@code customer.name}. */
    public static PathResolver nested(String association, String attribute) {
        return (root, q, cb) -> leftJoin(root, association).get(attribute);
    }

    /** The id of a to-one association, without forcing a join. */
    public static PathResolver referenceId(String association) {
        return (root, q, cb) -> root.get(association).get("id");
    }

    /** Reuses an existing left join rather than adding a duplicate one. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Join<?, ?> leftJoin(From<?, ?> from, String association) {
        for (Join<?, ?> j : from.getJoins()) {
            if (j.getAttribute().getName().equals(association) && j.getJoinType() == JoinType.LEFT) {
                return j;
            }
        }
        return ((From) from).join(association, JoinType.LEFT);
    }

    public static final class Builder {
        private final String name;
        private final String label;
        private final ColumnType type;
        private boolean sortable = true;
        private boolean filterable = true;
        private List<String> enumValues = List.of();
        private String referenceKind;
        private boolean pocRestricted;
        private PathResolver path;
        private PredicateResolver customFilter;

        private Builder(String name, String label, ColumnType type) {
            this.name = name;
            this.label = label;
            this.type = type;
            this.path = attr(name);
        }

        public Builder notSortable() { this.sortable = false; return this; }
        public Builder notFilterable() { this.filterable = false; return this; }
        public Builder enumValues(List<String> v) { this.enumValues = v; return this; }
        public Builder reference(String kind) { this.referenceKind = kind; return this; }
        public Builder pocRestricted() { this.pocRestricted = true; return this; }
        public Builder path(PathResolver p) { this.path = p; return this; }
        public Builder filter(PredicateResolver r) { this.customFilter = r; return this; }

        public ColumnDef build() {
            return new ColumnDef(name, label, type, sortable, filterable, enumValues,
                    referenceKind, pocRestricted, path, customFilter);
        }
    }
}
