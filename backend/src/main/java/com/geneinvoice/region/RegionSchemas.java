package com.geneinvoice.region;

import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.common.query.ColumnType;
import com.geneinvoice.common.query.TableSchema;

/**
 * The region map as a table, plus the one shared pair of as-of region columns.
 *
 * <p>It lives here rather than in TableSchemas because a feature that publishes a new table owns
 * its own schema: registering it is one line in SchemaRegistry and not another edit to the file
 * every other feature is also queueing behind (B1).
 */
public final class RegionSchemas {

    private RegionSchemas() {
    }

    /**
     * Region is classified NONE ("the region map itself"), so this list is deliberately NOT
     * region-scoped: REGION_VIEW is a company-wide privilege and somebody who may see the map may
     * see all of it. What a person may DO in each region is a different question, answered by
     * GET /api/regions/my (B1).
     */
    public static final TableSchema REGIONS = TableSchema.of("regions", Region.class, "code,asc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("code", "Code", ColumnType.TEXT).build(),
            ColumnDef.of("name", "Name", ColumnType.TEXT).build(),
            // Retired rather than deleted: every historical placement stays readable (B1).
            ColumnDef.of("active", "Active", ColumnType.BOOLEAN).build(),
            ColumnDef.of("createdAt", "Created", ColumnType.DATE).build());

    /**
     * The as-of twins of the regionId / regionName columns, declared here so that all six of B3's
     * mirror schemas share ONE pair rather than writing twelve: a mirror root maps its FKs as
     * plain Longs and has no customer association to walk, so B1's live paths cannot resolve on
     * it (B1, B3).
     *
     * <p>R5 shipped them reading the customer's CURRENT placement, as a shape with the right
     * signature and the wrong ledger. B3-SCHEMAS repoints the FILTER at
     * RegionPredicates.asOfRegionFilter — the same five arms asked of customer_region_history at
     * AsOfContext.date() — which is the only change either of them needed.
     *
     * <p>The PATH stays {@code root.get("customerId")}: this column is notSortable, a path is only
     * ever used for ordering, and the id the row actually carries is what resolves on all six
     * mirrors. AsOfSchemaCheck resolves it at boot, so it has to be something a mirror has (B3).
     */
    public static final ColumnDef AS_OF_REGION_ID =
            ColumnDef.of("regionId", "Region", ColumnType.REFERENCE)
                    .reference("region").pocRestricted().notSortable()
                    .path((root, q, cb) -> root.get("customerId"))
                    .filter(RegionPredicates::asOfRegionFilter)
                    .build();

    /**
     * THE NAME COLUMN COMES IN TWO SPELLINGS AND NOT ONE, AND THAT IS MEASURED RATHER THAN
     * PREFERRED. AsOfSchemaCheck refuses to boot when a twin's ColumnDef disagrees with its live
     * one on sortable, and the live regionName column is NOT the same in all six places: on
     * invoices, payments, promises and customers it is a joined column and sortable, while on
     * disputes and tasks it is a correlated scalar subquery and notSortable ("ordering the whole
     * list by it would run the subquery per row"). One constant would therefore halt the
     * application on four tables or on two, whichever way it was written (B1, B3).
     *
     * <p>Both read the same ledger through the same {@link RegionPredicates#asOfRegionName}; the
     * only difference between them is the flag the boot gate compares.
     */
    public static final ColumnDef AS_OF_REGION_NAME =
            ColumnDef.of("regionName", "Region name", ColumnType.TEXT).pocRestricted()
                    .path(RegionPredicates::asOfRegionName).build();

    /** The same column for the two live lists whose regionName is notSortable (B1, B3). */
    public static final ColumnDef AS_OF_REGION_NAME_UNSORTED =
            ColumnDef.of("regionName", "Region name", ColumnType.TEXT).pocRestricted().notSortable()
                    .path(RegionPredicates::asOfRegionName).build();
}
