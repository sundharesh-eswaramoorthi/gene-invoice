package com.geneinvoice.common.query;

import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;

import java.util.List;

public record ColumnDef(
        String name,
        String label,
        ColumnType type,
        boolean sortable,
        boolean filterable,
        List<String> enumValues,
        String referenceKind,
        boolean pocRestricted,
        PathResolver path,
        PredicateResolver customFilter,
        String asOfMode
) {

    public static Builder of(String name, String label, ColumnType type) {
        return new Builder(name, label, type);
    }

    public static PathResolver attr(String attribute) {
        return (root, q, cb) -> root.get(attribute);
    }

    public static PathResolver nested(String association, String attribute) {
        return (root, q, cb) -> leftJoin(root, association).get(attribute);
    }

    public static PathResolver referenceId(String association) {
        return (root, q, cb) -> root.get(association).get("id");
    }

    // LEFT at both hops on purpose: referenceId over a 2-level path INNER-joins and silently drops
    // every row whose intermediate association is null, which is exactly the rows a region column
    // has to keep showing (B1).
    public static PathResolver nested2(String a, String b, String attribute) {
        return (root, q, cb) -> leftJoin(leftJoin(root, a), b).get(attribute);
    }

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
        // EXACT means "this column reads as of the requested date"; it is the default because a
        // column with no historical source is the exception, not the rule (B3).
        private String asOfMode = "EXACT";

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
        // CURRENT marks a denormalised label that is always read live, so the client can say
        // "(current value)" instead of pretending the label is the one that was in force (B3).
        public Builder current() { this.asOfMode = "CURRENT"; return this; }

        public ColumnDef build() {
            return new ColumnDef(name, label, type, sortable, filterable, enumValues,
                    referenceKind, pocRestricted, path, customFilter, asOfMode);
        }
    }
}
