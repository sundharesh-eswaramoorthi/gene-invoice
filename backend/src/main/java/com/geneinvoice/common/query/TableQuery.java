package com.geneinvoice.common.query;

import com.geneinvoice.common.BadRequestException;

import java.util.ArrayList;
import java.util.List;

public record TableQuery(int page, int size, String sortField, boolean sortAscending,
                         List<FilterSpec> filters) {

    public static final int DEFAULT_SIZE = 20;
    public static final List<Integer> ALLOWED_SIZES = List.of(10, 20, 50);
    public static final int MAX_SIZE = 50;

    public static TableQuery parse(TableSchema schema, Integer page, Integer size,
                                   String sort, List<String> filterParams) {
        int p = page == null ? 0 : page;
        if (p < 0) throw new BadRequestException("page must be >= 0");

        int s = size == null ? DEFAULT_SIZE : size;
        if (!ALLOWED_SIZES.contains(s)) {
            throw new BadRequestException("size must be one of " + ALLOWED_SIZES);
        }

        String sortSpec = (sort == null || sort.isBlank()) ? schema.defaultSort() : sort;
        String field;
        boolean asc;
        if (sortSpec == null || sortSpec.isBlank()) {
            field = "id";
            asc = false;
        } else {
            String[] bits = sortSpec.split(",");
            field = bits[0].trim();
            // A direction that is neither asc nor desc is a mistake worth saying out loud, rather
            // than silently sorting the other way (D-40).
            String direction = bits.length < 2 ? "asc" : bits[1].trim();
            if (!"asc".equalsIgnoreCase(direction) && !"desc".equalsIgnoreCase(direction)) {
                throw new BadRequestException("sort direction must be asc or desc, not " + direction);
            }
            asc = !"desc".equalsIgnoreCase(direction);
            schema.requireSortable(field);
        }

        List<FilterSpec> specs = new ArrayList<>();
        if (filterParams != null) {
            for (String raw : filterParams) {
                if (raw == null || raw.isBlank()) continue;
                FilterSpec spec = FilterSpec.parse(raw);
                schema.requireFilterable(spec.field(), spec.operator());
                specs.add(spec);
            }
        }
        return new TableQuery(p, s, field, asc, List.copyOf(specs));
    }

    public static TableQuery parseUnpaged(TableSchema schema, String sort, List<String> filterParams) {
        TableQuery q = parse(schema, 0, DEFAULT_SIZE, sort, filterParams);
        return new TableQuery(0, Integer.MAX_VALUE, q.sortField(), q.sortAscending(), q.filters());
    }

    public String sortWire() {
        return sortField + "," + (sortAscending ? "asc" : "desc");
    }
}
