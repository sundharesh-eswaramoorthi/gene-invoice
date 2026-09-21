package com.geneinvoice.common.query;

import com.geneinvoice.common.BadRequestException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TableSchema(String entity, List<ColumnDef> columns, Map<String, ColumnDef> byName,
                          String defaultSort) {

    public static TableSchema of(String entity, String defaultSort, ColumnDef... defs) {
        Map<String, ColumnDef> index = new LinkedHashMap<>();
        for (ColumnDef d : defs) index.put(d.name(), d);
        return new TableSchema(entity, List.of(defs), Map.copyOf(index), defaultSort);
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
        return of(entity, defaultSort,
                columns.stream().filter(c -> !c.pocRestricted()).toArray(ColumnDef[]::new));
    }

    public List<ColumnDef> ordered() {
        return columns;
    }
}
