package com.geneinvoice.common.query;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.region.RegionAxes;
import com.geneinvoice.region.RegionAxis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TableSchema(String entity, Class<?> entityType, List<ColumnDef> columns,
                          Map<String, ColumnDef> byName, String defaultSort) {

    // entityType is mandatory and there is deliberately NO overload without it: a table that cannot
    // name the entity it reads cannot have its region axis resolved, so the omission has to be a
    // compile error rather than a silently unscoped list (B1).
    public static TableSchema of(String entity, Class<?> entityType, String defaultSort,
                                 ColumnDef... defs) {
        // Throws for an unclassified entity, at class-init, naming the class: a new schema for a
        // new table cannot reach a request without someone having decided which region its rows
        // are in (B1).
        RegionAxes.of(entityType);
        Map<String, ColumnDef> index = new LinkedHashMap<>();
        for (ColumnDef d : defs) index.put(d.name(), d);
        return new TableSchema(entity, entityType, List.of(defs), Map.copyOf(index), defaultSort);
    }

    /** Which region this table's rows are in. Read by the executor, never by a caller (B1). */
    public RegionAxis axis() {
        return RegionAxes.of(entityType);
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

    public TableSchema visibleTo(boolean customerScoped) {
        if (!customerScoped) return this;
        return of(entity, entityType, defaultSort,
                columns.stream().filter(c -> !c.pocRestricted()).toArray(ColumnDef[]::new));
    }

    public List<ColumnDef> ordered() {
        return columns;
    }
}
