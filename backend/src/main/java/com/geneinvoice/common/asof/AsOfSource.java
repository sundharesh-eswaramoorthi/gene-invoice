package com.geneinvoice.common.asof;

import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableSchema;

import java.util.List;

/**
 * WHERE ONE LIST READS FROM, AND IT IS THE WHOLE OF THE "ROUTING" (B3).
 *
 * <p>A service answers a list, its tiles, its ids, its export and its single-record 404 from ONE
 * of these, and the only thing that changes under {@code ?asOf} is which one it built. Nothing
 * downstream branches: {@code TableQueryExecutor.predicates()} ANDs the scope list it is given,
 * {@code ids()} selects {@code root.get("id")} — which on a mirror is the BUSINESS id — and
 * {@code count()} counts records rather than versions because exactly one version of each record
 * satisfies {@link AsOf#at}. The executor is not touched by this feature at all.
 *
 * <p>THE FIVE PARTS MOVE TOGETHER OR NOT AT ALL, which is why they are one object and not five
 * arguments. The root type, the schema and the scope list are a matched set — the executor refuses
 * a schema whose {@code entityType()} is not the root's — and a service that swapped the root but
 * forgot the scope would serve every VERSION of every record instead of one row per record. That
 * is the mistake this record exists to make impossible to write.
 *
 * @param <V>    the read interface both roots implement (InvoiceView, CustomerView, …), so the
 *               DTO factory downstream needs no cast and no second overload
 * @param type   the entity to root on: the live entity, or its interval-versioned mirror
 * @param schema the matching TableSchema — the live one, or its as-of twin, which keeps the SAME
 *               {@code entity()} string so every appliedFilters chip and every requireFilterable
 *               message is byte-identical between the two
 * @param scope  the predicates ANDed onto the query. Under as-of this LEADS with
 *               {@code AsOf.at(T)}: the mirror's twin carries the interval clause only inside its
 *               correlated subqueries, so without this line the root is unfiltered and a list
 *               returns every version of every record
 * @param locked the chips the page says it is already narrowed by, book chips first and then
 *               {@code "asOf:eq:2026-01-31"}; the region chips are added by the caller from
 *               RegionScope, which is the one place they may come from (B1)
 * @param fetch  the associations to LEFT JOIN FETCH. A mirror has fewer of them, because a
 *               foreign key to a MIRRORED thing is a flat Long with nothing to walk to
 */
public record AsOfSource<V>(Class<? extends V> type, TableSchema schema,
                            List<PredicateFactory> scope, List<String> locked, List<String> fetch) {
}
