package com.geneinvoice.common.query;

import java.util.List;
import java.util.function.Function;

/** The paged envelope every list endpoint returns. */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String sort,
        List<String> appliedFilters,
        /** Filters the server pinned on regardless of what the caller asked for (locked scope). */
        List<String> lockedFilters
) {
    public static <T> PageResponse<T> of(List<T> content, TableQuery query, long total,
                                         List<String> lockedFilters) {
        int totalPages = query.size() <= 0 ? 1 : (int) Math.ceil((double) total / query.size());
        return new PageResponse<>(content, query.page(), query.size(), total,
                Math.max(totalPages, 1), query.sortWire(),
                query.filters().stream().map(FilterSpec::wire).toList(),
                lockedFilters == null ? List.of() : lockedFilters);
    }

    public <R> PageResponse<R> map(Function<T, R> mapper) {
        return new PageResponse<>(content.stream().map(mapper).toList(), page, size,
                totalElements, totalPages, sort, appliedFilters, lockedFilters);
    }
}
