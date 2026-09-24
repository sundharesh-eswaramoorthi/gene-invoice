package com.geneinvoice.region;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WHICH BRANCH EACH OF THESE ACCOUNTS WAS IN ON A DATE — the label half of the as-of region
 * answer, for a page of mirror rows (B3, B1).
 *
 * <p>No mirror table carries {@code region_id}: blueprint conflict 1 struck it, because R7 already
 * built {@link CustomerRegionHistory} as the ledger of record and a second copy of the same fact
 * on eleven tables would be a second mechanism to keep in step. So an as-of row's
 * {@code regionId}/{@code regionName} are looked up HERE, from the ledger, and set on the mirror's
 * two {@code @Transient} fields before the DTO factory reads them — which is why the factory's
 * signature does not grow and why one factory still serves a live row and a mirror row.
 *
 * <p>ONE QUERY PER PAGE, not one per row. The alternative — a correlated subquery per row inside
 * the list query — is what {@code RegionSchemas.AS_OF_REGION_ID} does for FILTERING and SORTING,
 * where it has to; for a rendered LABEL a batched read of the page's account ids is the same
 * answer for a fraction of the work. Chunked at 1000 because a CSV export resolves up to
 * {@code TableQueryExecutor.BULK_ID_LIMIT} rows and an {@code in (…)} list of five thousand
 * parameters is a shape no planner enjoys (B3).
 *
 * <p>THE NAME IS TODAY'S NAME, AND THAT IS CONTRACT CLAUSE a.3 RATHER THAN AN OVERSIGHT. WHICH
 * region is reconstructed — the ledger says where the account was on the date asked — but
 * {@link Region} is not a mirrored entity, so a branch renamed last month renders under its new
 * name on a January list. It is the same rule the POC's name and the product's name already
 * follow, and it is stated on {@link Placement} so a caller cannot read the pair as both being
 * as-of (B3).
 *
 * <p>AN ACCOUNT WITH NO PLACEMENT IN FORCE REPORTS NULL, which is the existing "not asked"
 * convention every nullable DTO slot in this codebase already uses — never a guess at today's
 * region, because that is precisely the leak as-of exists to close.
 */
@Component
public class RegionPlacements {

    /** Where one account was on the date asked. {@code regionName} is TODAY'S name (clause a.3). */
    public record Placement(Long regionId, String regionName) {
    }

    /** The same chunk size the schema upgrades and the reconciler page at (B1, B3). */
    private static final int CHUNK = 1000;

    /**
     * Half-open [validFrom, validTo), and {@code validTo is null} is the one OPEN placement — the
     * identical window {@link RegionPredicates#asOf} builds in Criteria for the region PREDICATE.
     * Two readings of one ledger, deliberately, and never two ledgers (B1, B3).
     *
     * <p>A LEFT join to {@code regions} and not a cross join: a placement naming a branch row that
     * has gone must still answer WHICH branch, with a null label, rather than dropping the account
     * out of the result and reporting it as unplaced.
     */
    private static final String IN_FORCE = """
            select h.customerId, h.regionId, r.name
              from CustomerRegionHistory h
              left join Region r on r.id = h.regionId
             where h.customerId in :ids
               and h.validFrom <= :on
               and (h.validTo is null or h.validTo > :on)
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * @param customerIds the accounts on this page; duplicates and nulls are tolerated
     * @param date        the day being answered as of
     * @return one entry per account that HAD a placement in force; an account with none is simply
     *         absent, so a caller reads null and reports "not asked" rather than today's branch
     */
    @Transactional(readOnly = true)
    public Map<Long, Placement> at(Collection<Long> customerIds, LocalDate date) {
        if (customerIds == null || customerIds.isEmpty() || date == null) return Map.of();
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : customerIds) {
            if (id != null) ids.add(id);
        }
        if (ids.isEmpty()) return Map.of();

        Map<Long, Placement> placements = new LinkedHashMap<>();
        List<Long> all = new ArrayList<>(ids);
        for (int from = 0; from < all.size(); from += CHUNK) {
            List<Long> chunk = all.subList(from, Math.min(from + CHUNK, all.size()));
            List<Object[]> rows = entityManager.createQuery(IN_FORCE, Object[].class)
                    .setParameter("ids", chunk)
                    .setParameter("on", date)
                    .getResultList();
            for (Object[] row : rows) {
                // B1 guarantees at most one row in force per account per date — half-open
                // intervals written under the customer's row lock, exactly one open row — so this
                // put never overwrites in practice. If the ledger ever DID hold two overlapping
                // placements this picks one rather than failing, which is the right failure for a
                // rendered label; the FILTER path (RegionPredicates.asOfRegionId, a scalar
                // subquery) would raise a cardinality error instead, and that is the right
                // failure there (B1, B3).
                placements.put((Long) row[0], new Placement((Long) row[1], (String) row[2]));
            }
        }
        return placements;
    }
}
