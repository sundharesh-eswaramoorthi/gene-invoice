package com.geneinvoice.common.query;

import com.geneinvoice.common.BadRequestException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The set of columns one table exposes for sorting and filtering.
 *
 * <p>Column order is the order they are declared in: the frontend builds its filter picker from
 * this list, so it must not be left to a hash map's whim.
 */
public record TableSchema(String entity, List<ColumnDef> columns, Map<String, ColumnDef> byName,
                          String defaultSort, List<Integer> pageSizes, int defaultPageSize) {

    /** The page capacities existing tables have always offered (DES-EMAIL-12 leaves them be). */
    public static final List<Integer> DEFAULT_PAGE_SIZES = List.of(10, 20, 50);
    public static final int DEFAULT_PAGE_SIZE = 20;

    public static TableSchema of(String entity, String defaultSort, ColumnDef... defs) {
        return of(entity, defaultSort, DEFAULT_PAGE_SIZES, DEFAULT_PAGE_SIZE, defs);
    }

    /** A schema carrying its own permitted page sizes and default — the Inbox uses this. */
    public static TableSchema of(String entity, String defaultSort, List<Integer> pageSizes,
                                 int defaultPageSize, ColumnDef... defs) {
        Map<String, ColumnDef> index = new LinkedHashMap<>();
        for (ColumnDef d : defs) index.put(d.name(), d);
        return new TableSchema(entity, List.of(defs), Map.copyOf(index), defaultSort,
                pageSizes, defaultPageSize);
    }

    public ColumnDef require(String name) {
        ColumnDef def = byName.get(name);
        if (def == null) {
            throw new BadRequestException("Unknown column: " + name);
        }
        return def;
    }

    public ColumnDef requireSortable(String name) {
        ColumnDef def = require(name);
        if (!def.sortable()) {
            throw new BadRequestException("Column is not sortable: " + name);
        }
        return def;
    }

    public ColumnDef requireFilterable(String name, FilterOperator op) {
        ColumnDef def = require(name);
        if (!def.filterable()) {
            throw new BadRequestException("Column is not filterable: " + name);
        }
        if (!def.type().supports(op)) {
            throw new BadRequestException(
                    "Operator " + op.wire() + " is not valid for column " + name + " (" + def.type() + ")");
        }
        return def;
    }

    /**
     * The schema as the caller may use it. A customer-scoped account never sees POC identity
     * (AC-A8), so for them the POC columns do not exist: filtering or sorting on one is an unknown
     * column, not a way to probe who their reps are through the match counts.
     */
    public TableSchema visibleTo(boolean customerScoped) {
        if (!customerScoped) return this;
        return of(entity, defaultSort, pageSizes, defaultPageSize,
                columns.stream().filter(c -> !c.pocRestricted()).toArray(ColumnDef[]::new));
    }

    /** Columns in declaration order. */
    public List<ColumnDef> ordered() {
        return columns;
    }
}
