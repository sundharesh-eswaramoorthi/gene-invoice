package com.geneinvoice.common.query;

import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.poc.ScopeResolver;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String sort,
        List<String> appliedFilters,
        List<String> lockedFilters,
        List<Long> regionIds,
        List<Long> bookRegionIds,
        String asOf
) {
    /** An unscoped list (users, roles, products...): it answers no regions. */
    public static <T> PageResponse<T> of(List<T> content, TableQuery query, long total,
                                         List<String> lockedFilters) {
        return of(content, query, total, lockedFilters, List.of(), List.of());
    }

    /** A region-scoped list: the regions answered, and those answered as the caller's book only, from its scope. */
    public static <T> PageResponse<T> of(List<T> content, TableQuery query, long total, ScopeResolver.Scope scope) {
        return of(content, query, total, scope.lockedFilters(), scope.regionIds(), scope.bookRegionIds());
    }

    private static <T> PageResponse<T> of(List<T> content, TableQuery query, long total, List<String> lockedFilters,
                                          List<Long> regionIds, List<Long> bookRegionIds) {
        int totalPages = query.size() <= 0 ? 1 : (int) Math.ceil((double) total / query.size());
        AsOfPoint point = AsOf.current();
        return new PageResponse<>(content, query.page(), query.size(), total,
                Math.max(totalPages, 1), query.sortWire(),
                query.filters().stream().map(FilterSpec::wire).toList(),
                lockedFilters == null ? List.of() : lockedFilters,
                regionIds, bookRegionIds, point == null ? null : point.date().toString());
    }

    public <R> PageResponse<R> map(Function<T, R> mapper) {
        return new PageResponse<>(content.stream().map(mapper).toList(), page, size,
                totalElements, totalPages, sort, appliedFilters, lockedFilters, regionIds, bookRegionIds, asOf);
    }
}
