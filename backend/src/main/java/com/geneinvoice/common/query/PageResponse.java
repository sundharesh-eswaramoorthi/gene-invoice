package com.geneinvoice.common.query;

import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.asof.AsOfInfo;

import java.util.ArrayList;
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
        // Null on a live read, and a component rather than a sixth parameter of of(...): a list
        // served as of a date CANNOT forget to say so, and the twelve call sites B1 fixed are not
        // touched again. Flutter's hand-written PagedResult.fromJson ignores unknown keys, so the
        // shipped client is wire-compatible with it (B3).
        AsOfInfo asOf
) {
    /**
     * The region chips are a SEPARATE argument rather than something a caller folds into
     * lockedFilters, so that a list which forgets to say which regions it covers is a compile
     * error at all twelve call sites instead of a page that is quietly narrower than it claims
     * (B1).
     *
     * @param lockedFilters the book's own chips, from ScopeResolver.Scope
     * @param regionFilters regionScope.lockedFilters(entityType); empty for an unregioned table,
     *                      for a wildcard holder and for a customer login
     */
    public static <T> PageResponse<T> of(List<T> content, TableQuery query, long total,
                                         List<String> lockedFilters, List<String> regionFilters) {
        int totalPages = query.size() <= 0 ? 1 : (int) Math.ceil((double) total / query.size());
        return new PageResponse<>(content, query.page(), query.size(), total,
                Math.max(totalPages, 1), query.sortWire(),
                query.filters().stream().map(FilterSpec::wire).toList(),
                // Book chips first, then region chips: the order the UI renders them in, and the
                // order the settled wire example shows (B1).
                concat(lockedFilters, regionFilters),
                // Read here, from the thread, for the same reason the region predicate is added by
                // the executor and not by a caller: it is a property of the query and not of
                // anyone remembering to pass it (B3).
                AsOfContext.info());
    }

    private static List<String> concat(List<String> book, List<String> region) {
        if (region == null || region.isEmpty()) return book == null ? List.of() : List.copyOf(book);
        List<String> all = new ArrayList<>(book == null ? List.of() : book);
        all.addAll(region);
        return List.copyOf(all);
    }

    public <R> PageResponse<R> map(Function<T, R> mapper) {
        // asOf is carried, not rebuilt: map() runs inside the same request but a service that
        // maps a page would otherwise drop the one field that says the page is historical (B3).
        return new PageResponse<>(content.stream().map(mapper).toList(), page, size,
                totalElements, totalPages, sort, appliedFilters, lockedFilters, asOf);
    }
}
