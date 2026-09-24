package com.geneinvoice.region;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.common.SchemaSupport;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The region migration, driven against real rows.
 *
 * <p>The test profile is ddl-auto:create-drop, so at boot the upgrade finds an empty database:
 * the backfill has nothing to place and the NOT NULL tighten lands on an empty table. That proves
 * the upgrade is harmless, not that it WORKS. So most of these tests drop the not-null again,
 * seed customers and staff through raw JDBC exactly as a populated production database would hold
 * them, drive the package-private steps one at a time, and put the constraint back afterwards —
 * which is why those steps are package-private statics taking a Connection in the first place
 * (the InvoiceSchemaUpgrade.backfill idiom) (B1).
 */
class RegionSchemaUpgradeTest extends IntegrationTestBase {

    @Autowired DataSource dataSource;
    @Autowired AuditService auditService;
    @Autowired RegionSchemaUpgrade upgrade;

    @AfterEach
    void putTheConstraintBack() throws SQLException {
        // Whatever a test did to the column, the next test class in this cached context must find
        // customers.region_id NOT NULL, or it would be testing a schema nobody ever runs.
        try (Connection c = dataSource.getConnection()) {
            RegionSchemaUpgrade.enforceRegionNotNull(c);
        }
    }

    @Test
    void regionIdIsNotNullAfterTheUpgrade() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThat(SchemaSupport.isNullable(c, "customers", "region_id"))
                    .as("the upgrade ran at boot and tightened the column")
                    .isFalse();
        }
        assertThatThrownBy(() -> customerRepository.saveAndFlush(Customer.builder().name("Nowhere Ltd").build()))
                .as("a customer that belongs to no region is refused by the database itself")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theUpgradeGivesEveryCustomerWithoutOneTheDefaultRegion() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            allowNullRegion(c);
            insertUnplacedCustomer(c, "Acme Ltd", Instant.parse("2023-05-17T09:00:00Z"));
            insertUnplacedCustomer(c, "Beta Ltd", Instant.parse("2024-01-02T09:00:00Z"));
            long region = RegionSchemaUpgrade.seedDefaultRegion(
                    c, regionProperties.defaultCode(), regionProperties.defaultName());

            assertThat(RegionSchemaUpgrade.unplaced(c, 0, 1000)).hasSize(2);
            assertThat(RegionSchemaUpgrade.backfillCustomerRegions(c, region)).isEqualTo(2);
            assertThat(RegionSchemaUpgrade.unplaced(c, 0, 1000)).isEmpty();
            assertThat(placedIn(c, region)).as("both customers are in the default region").isEqualTo(2);
            assertThat(region).isEqualTo(defaultRegion().getId());
        }
    }

    /**
     * The chunking itself, which nothing else in this suite has ever reached: every other test
     * here seeds one or two customers, and RegionSchemaUpgrade pages a thousand at a time, so the
     * loop, the keyset cursor and the rows either side of a page boundary were all covered only by
     * a hand-run against a seeded Postgres — a run nobody repeats (B1).
     */
    @Test
    void theBackfillPagesPastItsOwnChunkAndPlacesTheRowsOnEitherSideOfTheBoundary() throws Exception {
        int seeded = 1500;          // deliberately more than RegionSchemaUpgrade's own CHUNK
        Instant born = Instant.parse("2023-03-01T10:00:00Z");
        try (Connection c = dataSource.getConnection()) {
            allowNullRegion(c);
            try {
                seedUnplacedCustomers(c, seeded, born);
                // Holes all the way through the range, including across the page boundary. The
                // pager is keyset-based — "id > ? order by id limit ?" — and a rewrite to OFFSET,
                // or to a dense id range, would still pass against a table whose ids run 1..N
                // with nothing missing from them.
                //
                // Two coprime strides and not one, because a REGULAR hole pattern can conspire
                // with the page size and hide the very bug this is for: with a hole at every
                // sixth id, a thousand surviving rows land exactly on a group boundary, so a
                // cursor advanced to lastId + 1 lands on a hole and skips nothing. 7 and 13 leave
                // no such alignment at any page size this migration would plausibly use (B1).
                int total = seeded - punchHoles(c);
                assertThat(total).as("still more than one page after the holes").isGreaterThan(1000);

                // The pager's contract, stated without reference to the migration's page size: a
                // full page, then the REST resumed from that page's last id, nothing twice and
                // nothing missed.
                List<Long> page = RegionSchemaUpgrade.unplaced(c, 0, 1000);
                assertThat(page).hasSize(1000).isSorted().doesNotHaveDuplicates();
                long boundary = page.get(999);
                List<Long> rest = RegionSchemaUpgrade.unplaced(c, boundary, 1000);
                assertThat(rest).hasSize(total - 1000).isSorted().doesNotHaveDuplicates();
                assertThat(rest.get(0))
                        .as("the cursor resumes AFTER the last id of the page, never at it")
                        .isGreaterThan(boundary);

                long region = RegionSchemaUpgrade.seedDefaultRegion(
                        c, regionProperties.defaultCode(), regionProperties.defaultName());
                assertThat(RegionSchemaUpgrade.backfillCustomerRegions(c, region)).isEqualTo(total);
                assertThat(RegionSchemaUpgrade.unplaced(c, 0, seeded))
                        .as("a pager that stopped after one chunk would leave these behind")
                        .isEmpty();
                assertThat(placedIn(c, region)).isEqualTo(total);
                // The two rows a broken pager drops first: the last of a full page and the first
                // of the next one.
                assertThat(regionOf(c, boundary)).isEqualTo(region);
                assertThat(regionOf(c, rest.get(0))).isEqualTo(region);

                assertThat(RegionSchemaUpgrade.openHistoryRows(c)).isEqualTo(total);
                assertThat(openPlacements(c))
                        .as("exactly one open placement each, at a scale the page boundary is inside")
                        .isEqualTo(total);
                // Dated from the account's own creation at scale too, and in UTC: a pager that
                // re-read the wrong row would date a placement from somebody else's created_at.
                assertThat(customerRegionHistoryRepository.findOpen(boundary).orElseThrow().getValidFrom())
                        .isEqualTo(InvoiceDates.dayOf(born));
                assertThat(RegionSchemaUpgrade.openHistoryRows(c))
                        .as("and the second pass over a thousand rows opens nothing")
                        .isZero();
            } finally {
                purgeSeededCustomers(c);
            }
        }
    }

    @Test
    void theUpgradeOpensAHistoryRowDatedFromTheCustomersCreation() throws Exception {
        long acme;
        long ancient;
        try (Connection c = dataSource.getConnection()) {
            allowNullRegion(c);
            acme = insertUnplacedCustomer(c, "Acme Ltd", Instant.parse("2023-05-17T09:00:00Z"));
            ancient = insertUnplacedCustomer(c, "Before Time Ltd", null);
            long region = RegionSchemaUpgrade.seedDefaultRegion(
                    c, regionProperties.defaultCode(), regionProperties.defaultName());
            RegionSchemaUpgrade.backfillCustomerRegions(c, region);

            assertThat(RegionSchemaUpgrade.openHistoryRows(c)).isEqualTo(2);
        }

        CustomerRegionHistory placed = customerRegionHistoryRepository.findOpen(acme).orElseThrow();
        // Dated from the account's own creation, not from deploy day: an as-of read of May 2023
        // must find Acme where it has always been, not report that it had no region then (B1, B3).
        assertThat(placed.getValidFrom()).isEqualTo(LocalDate.of(2023, 5, 17));
        assertThat(placed.getValidTo()).isNull();
        assertThat(placed.getMovedByUserId()).isNull();
        assertThat(placed.getReason()).contains("before branches existed");
        assertThat(placed.getRegionId()).isEqualTo(defaultRegion().getId());

        // A row with no created_at predates the column; 1970 is before anything this company did.
        assertThat(customerRegionHistoryRepository.findOpen(ancient).orElseThrow().getValidFrom())
                .isEqualTo(LocalDate.of(1970, 1, 1));
    }

    @Test
    void theOpeningDateIsTheAccountsUtcDayWhateverTimeZoneTheDatabaseSessionIsIn() throws Exception {
        // 22:00 UTC on the 17th is already the 18th anywhere east of UTC+2, and the session time
        // zone is where a database gets its calendar from: both drivers take it from the JVM's
        // default zone, so this is whatever the machine running the migration happens to be set
        // to. Forced here so the test asks the question on every machine (B1).
        Instant lateEvening = Instant.parse("2023-05-17T22:00:00Z");
        long evening;
        try (Connection c = dataSource.getConnection()) {
            String restore = restoreTimeZone(c);
            try {
                try (Statement s = c.createStatement()) {
                    // INTERVAL and not the string '+14:00'. Postgres reads a bare '+14:00' the
                    // POSIX way — fourteen hours WEST — which put the session on the WRONG side of
                    // the day boundary and left this test proving nothing at all on the database
                    // production runs on, while proving the real thing on H2. The INTERVAL form is
                    // east of Greenwich on both engines (B1).
                    s.execute("set time zone interval '+14:00' hour to minute");
                }
                assertThat(sessionDayOf(c, lateEvening))
                        .as("the session really is far enough east that the database's own"
                                + " calendar has already turned over; without this the test is"
                                + " asserting nothing")
                        .isEqualTo(LocalDate.of(2023, 5, 18));

                allowNullRegion(c);
                evening = insertUnplacedCustomerAt(c, "Late Evening Ltd", lateEvening);
                long region = RegionSchemaUpgrade.seedDefaultRegion(
                        c, regionProperties.defaultCode(), regionProperties.defaultName());
                RegionSchemaUpgrade.backfillCustomerRegions(c, region);

                assertThat(RegionSchemaUpgrade.openHistoryRows(c)).isEqualTo(1);
            } finally {
                // The connection goes back to the pool, so it goes back in the zone it came in.
                try (Statement s = c.createStatement()) {
                    s.execute(restore);
                }
            }
        }

        // The UTC day, which is the only calendar this application has: InvoiceDates.today(),
        // todayForWrite() and dayOf() are all pinned to ZoneOffset.UTC, RegionCustodyService.move
        // refuses a move dated before validFrom, and B3 reads ?asOf as a UTC day. A ledger dated
        // in the JVM's zone would file this account in its branch from TOMORROW (B1, B3).
        assertThat(customerRegionHistoryRepository.findOpen(evening).orElseThrow().getValidFrom())
                .isEqualTo(InvoiceDates.dayOf(lateEvening))
                .isEqualTo(LocalDate.of(2023, 5, 17));
    }

    @Test
    void theUpgradeGrantsEveryExistingStaffUserTheDefaultRegionAndAdministratorsTheWildcard() throws Exception {
        User vera = user("vera.viewer", "VIEWER");
        User sally = user("sally.sales", DataSeeder.ROLE_SALES_POC);
        User admin = userRepository.findByUsername("admin").orElseThrow();
        userRegionGrantRepository.deleteAll();
        Long home = defaultRegion().getId();

        try (Connection c = dataSource.getConnection()) {
            assertThat(RegionSchemaUpgrade.grantExistingStaff(c, home)).isGreaterThanOrEqualTo(4);
        }

        // The administrator keeps working everywhere, so opening a branch next year cannot lock
        // the company out of its own data (B1).
        // Both levels: APPROVE does not cover MANAGE, so one wildcard row would leave the
        // administrator able to sign a change off everywhere and raise an invoice nowhere (B1).
        assertThat(userRegionGrantRepository.findByUserId(admin.getId()))
                .extracting(UserRegionGrant::getRegionId, UserRegionGrant::getRight)
                .containsExactlyInAnyOrder(tuple(null, RegionRight.MANAGE), tuple(null, RegionRight.APPROVE));
        // A salesperson may change invoices, so they keep being able to — in the default region
        // only, which is the whole behaviour-preserving-but-not-a-no-op trade (B1).
        assertThat(userRegionGrantRepository.findByUserId(sally.getId()))
                .extracting(UserRegionGrant::getRegionId, UserRegionGrant::getRight)
                .containsExactly(tuple(home, RegionRight.MANAGE));
        // A reader's role holds no %_MANAGE, so a reader stays a reader.
        assertThat(userRegionGrantRepository.findByUserId(vera.getId()))
                .extracting(UserRegionGrant::getRegionId, UserRegionGrant::getRight)
                .containsExactly(tuple(home, RegionRight.VIEW));
    }

    @Test
    void theUpgradeDoesNotGrantACustomerLoginAnyRegionRight() throws Exception {
        Customer acme = customer("Acme Ltd");
        User login = customerUser("acme.login", acme.getId());
        userRegionGrantRepository.deleteAll();

        try (Connection c = dataSource.getConnection()) {
            RegionSchemaUpgrade.grantExistingStaff(c, defaultRegion().getId());
        }

        // A customer login's reach is its own account, which customer_id and the POC book already
        // decide; a region grant would widen it to every account in the branch (B1).
        assertThat(userRegionGrantRepository.findByUserId(login.getId())).isEmpty();
    }

    @Test
    void runningTheUpgradeTwiceChangesNothing() throws Exception {
        userRegionGrantRepository.deleteAll();
        try (Connection c = dataSource.getConnection()) {
            allowNullRegion(c);
            insertUnplacedCustomer(c, "Acme Ltd", Instant.parse("2023-05-17T09:00:00Z"));

            long first = RegionSchemaUpgrade.seedDefaultRegion(
                    c, regionProperties.defaultCode(), regionProperties.defaultName());
            assertThat(RegionSchemaUpgrade.backfillCustomerRegions(c, first)).isEqualTo(1);
            assertThat(RegionSchemaUpgrade.openHistoryRows(c)).isEqualTo(1);
            int granted = RegionSchemaUpgrade.grantExistingStaff(c, first);
            assertThat(granted).isPositive();
            RegionSchemaUpgrade.enforceRegionNotNull(c);
            RegionSchemaUpgrade.addRegionIndexes(c);
            assertThat(RegionSchemaUpgrade.closeDuplicateOpenIntervals(c)).isZero();

            long second = RegionSchemaUpgrade.seedDefaultRegion(
                    c, regionProperties.defaultCode(), regionProperties.defaultName());
            assertThat(second).as("the default region is seeded once, ever").isEqualTo(first);
            assertThat(RegionSchemaUpgrade.backfillCustomerRegions(c, second)).isZero();
            assertThat(RegionSchemaUpgrade.openHistoryRows(c)).isZero();
            assertThat(RegionSchemaUpgrade.grantExistingStaff(c, second)).isZero();
            assertThatCode(() -> {
                RegionSchemaUpgrade.enforceRegionNotNull(c);
                RegionSchemaUpgrade.addRegionIndexes(c);
                RegionSchemaUpgrade.addPartialIndexes(c);
            }).doesNotThrowAnyException();
        }

        assertThat(regionRepository.findAll()).hasSize(1);
        assertThat(customerRegionHistoryRepository.findAll()).hasSize(1);
        // admin's two wildcard rows and cashier's one in the default region.
        assertThat(userRegionGrantRepository.findAll()).hasSize(3);
    }

    @Test
    void aSecondOpenPlacementIsClosedSoOnlyTheNewestOneSurvives() throws Exception {
        Customer acme = customer("Acme Ltd");
        Long home = defaultRegion().getId();

        try (Connection c = dataSource.getConnection()) {
            // The repair exists for a database that PREDATES uk_crh_open — which is why
            // RegionSchemaUpgrade.upgrade runs closeDuplicateOpenIntervals BEFORE
            // addPartialIndexes. On Postgres the key is already there and refuses the second open
            // row outright, so the test has to stand where the migration stands: key off,
            // duplicates made, repair run, key back on. On H2 there is no such key to drop and
            // this is a no-op, which is why the test used to pass there while erroring on the
            // database production runs on (B1).
            dropOpenPlacementKey(c);
            try {
                customerRegionHistoryRepository.saveAndFlush(CustomerRegionHistory.builder()
                        .customerId(acme.getId()).regionId(home)
                        .validFrom(LocalDate.of(2024, 1, 1)).build());
                customerRegionHistoryRepository.saveAndFlush(CustomerRegionHistory.builder()
                        .customerId(acme.getId()).regionId(home)
                        .validFrom(LocalDate.of(2025, 6, 1)).build());

                assertThat(RegionSchemaUpgrade.closeDuplicateOpenIntervals(c)).isEqualTo(1);
            } finally {
                // Put back however this test ends, or every later test in this cached context
                // would run against a database that has quietly lost the invariant.
                RegionSchemaUpgrade.addPartialIndexes(c);
            }
            // And the repair left the ledger in a state where the key can be created AGAIN, which
            // is the whole reason the migration runs it first: partialIndex swallows a failed
            // create as a WARN nobody reads, so "it came back" is asserted rather than assumed.
            // Postgres only — addPartialIndexes returns immediately on H2, where this invariant is
            // held by RegionCustodyService's row lock instead (B1).
            if (SchemaSupport.product(c).contains("postgresql")) {
                assertThat(hasOpenPlacementKey(c))
                        .as("uk_crh_open is creatable again, so exactly one row per customer is open")
                        .isTrue();
            }
        }

        // The newest placement is what anyone last said about the account; the older duplicate is
        // collapsed to a zero-length interval so no as-of read can ever match it (B1).
        assertThat(customerRegionHistoryRepository.findOpen(acme.getId()).orElseThrow().getValidFrom())
                .isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(customerRegionHistoryRepository.findByCustomerIdOrderByValidFromAsc(acme.getId()))
                .filteredOn(h -> h.getValidFrom().equals(LocalDate.of(2024, 1, 1)))
                .singleElement()
                .satisfies(h -> assertThat(h.getValidTo()).isEqualTo(h.getValidFrom()));
    }

    @Test
    void theBackfillLeavesAnAuditRowNamingWhatItDidSoTheGrantsCanBeReviewed() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            allowNullRegion(c);
            insertUnplacedCustomer(c, "Acme Ltd", Instant.parse("2023-05-17T09:00:00Z"));
        }

        upgrade.afterPropertiesSet();

        // The only trace an operator has that every existing staff user was staffed in the
        // default region and now needs tightening by hand: there is no report and no nag (B1).
        assertThat(auditService.historyFor(RegionSchemaUpgrade.ENTITY, RegionSchemaUpgrade.BACKFILL_ENTITY_ID))
                .anySatisfy(row -> {
                    assertThat(row.getAction()).isEqualTo("REGIONS_BACKFILLED");
                    assertThat(row.getAfterJson()).contains("\"customers\":1")
                            .contains("\"placements\":1")
                            .contains("\"regionCode\":\"" + regionProperties.defaultCode() + "\"");
                    assertThat(row.getReason()).contains("Placed 1 customers");
                    assertThat(row.getChangedByUserId()).as("no person ran the migration").isNull();
                });
    }

    @Test
    void theUpgradeNeverThrowsWhenATableIsMissing() {
        // A database where ddl-auto has not created the region tables — the shape an operator sees
        // when a failed schema export is started with SCHEMA_HALT_ON_ERROR=false. The upgrade must
        // log and carry on, because refusing to start leaves them with no application to fix it
        // from; RegionCoverageCheck is the bean whose job is to refuse (B1).
        DriverManagerDataSource bare = new DriverManagerDataSource(
                "jdbc:h2:mem:region-upgrade-no-tables;DB_CLOSE_DELAY=-1", "sa", "");
        bare.setDriverClassName("org.h2.Driver");

        assertThatCode(() -> new RegionSchemaUpgrade(bare, regionProperties, auditService, null)
                .afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void theUpgradeThatRanAtBootLeftTheApplicationAbleToStart() {
        // The bean is real, was constructed, and can be asked to run again without complaint: the
        // second boot of a production database has to be a no-op (B1).
        assertThat(upgrade).isNotNull();
        assertThatCode(upgrade::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void everyValueOfTheRightLadderIsStorableAndAnInventedOneIsRefused() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            // Pre-emptive and, on a table this new, a no-op: there is no stale constraint to
            // replace today. The call is wired now so that whoever adds a fourth RegionRight in
            // 2027 inherits a column that widens itself instead of a deploy where every grant
            // insert is rejected by a constraint generated when the column was created (B1).
            assertThatCode(() -> SchemaSupport.widen(c, "user_region_grants", "right_level", RegionRight.class))
                    .doesNotThrowAnyException();
        }

        User vera = user("vera.viewer", "VIEWER");
        // The fixture staffs whoever it creates; this test counts rows, so it starts from none.
        revokeRegionGrants(vera);
        for (RegionRight r : RegionRight.values()) {
            userRegionGrantRepository.save(UserRegionGrant.builder()
                    .userId(vera.getId()).regionId(defaultRegion().getId()).right(r).build());
        }
        assertThat(userRegionGrantRepository.findByUserId(vera.getId()))
                .extracting(UserRegionGrant::getRight)
                .containsExactlyInAnyOrder(RegionRight.values());

        // And a value the ladder does not know is refused by the database, not merely by Java.
        // H2 holds right_level as a native ENUM and Postgres as a varchar under a generated check
        // constraint, which is the constraint SchemaSupport.widen above exists to replace (B1).
        try (Connection c = dataSource.getConnection();
             PreparedStatement statement = c.prepareStatement(
                     "insert into user_region_grants (user_id, region_id, right_level, created_at)"
                             + " values (?, ?, 'OWNER', ?)")) {
            statement.setLong(1, vera.getId());
            statement.setLong(2, defaultRegion().getId());
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            assertThatThrownBy(statement::executeUpdate).isInstanceOf(SQLException.class);
        }
    }

    /**
     * The Postgres partial unique keys, asserted as definitions because addPartialIndexes returns
     * immediately on H2 and this suite has no other way to reach them.
     *
     * <p>Both grant keys have to carry right_level. The ladder is not a total order, so a user
     * holds one grant row per right they have in a place — and {@link #theUpgradeGrantsEveryExistingStaffUserTheDefaultRegionAndAdministratorsTheWildcard}
     * asserts the upgrade writes every administrator TWO wildcard rows. Keyed on user_id alone,
     * uk_grant_all could not be created at all on any database that has an administrator: against
     * a real Postgres with a real seeded admin the migration logged "Could not create uk_grant_all
     * ... Key (user_id)=(1) is duplicated" and carried on, leaving the invariant unenforced on
     * every database B1 had ever touched, on every restart, with nothing but a WARN to say so (B1).
     */
    @Test
    void bothGrantKeysCarryTheRightSoTheRowsTheUpgradeItselfWritesFitThem() {
        assertThat(RegionSchemaUpgrade.UK_GRANT_ALL)
                .as("an administrator holds a MANAGE wildcard and an APPROVE wildcard")
                .contains("right_level");
        assertThat(RegionSchemaUpgrade.UK_GRANT_REGION)
                .as("anyone who must both work and approve in a branch holds two rows there")
                .contains("right_level");
        // uk_crh_open is the one key that is genuinely one row per customer, and must stay that way.
        assertThat(RegionSchemaUpgrade.UK_CRH_OPEN)
                .isEqualTo("customer_region_history (customer_id) where valid_to is null");
    }

    // ---- fixtures -------------------------------------------------------------------------

    /**
     * The statement that puts this session's time zone back where it came from. H2's LOCAL is the
     * JVM's own zone, which is where the pooled connection came in; Postgres's LOCAL is the
     * SERVER's default, which is not — the driver sets the session zone from the JVM at connect —
     * so there the zone is read back by name and restored by name, or the connection would rejoin
     * the pool in a zone nobody chose and the next test would inherit it (B1).
     */
    private static String restoreTimeZone(Connection connection) throws SQLException {
        if (!SchemaSupport.product(connection).contains("postgresql")) return "set time zone local";
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select current_setting('TimeZone')")) {
            rows.next();
            return "set time zone '" + rows.getString(1) + "'";
        }
    }

    /** The calendar day the DATABASE puts an instant on, in whatever zone this session is in —
     *  the answer the migration deliberately refuses to use (B1). */
    private static LocalDate sessionDayOf(Connection connection, Instant at) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select cast(cast(? as timestamp with time zone) as date)")) {
            statement.setObject(1, at.atOffset(ZoneOffset.UTC));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getObject(1, LocalDate.class);
            }
        }
    }

    /**
     * Drops the one open-placement key, where the dialect has one. Postgres holds it as a partial
     * unique index and H2 holds it not at all (RegionSchemaUpgrade.addPartialIndexes), so this is
     * a no-op there — the same asymmetry addPartialIndexes itself carries (B1).
     */
    private static void dropOpenPlacementKey(Connection connection) throws SQLException {
        if (!SchemaSupport.product(connection).contains("postgresql")) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute("drop index if exists uk_crh_open");
        }
    }

    /** Whether the database is actually carrying uk_crh_open right now. Postgres only. */
    private static boolean hasOpenPlacementKey(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from pg_indexes"
                        + " where schemaname = current_schema() and indexname = 'uk_crh_open'")) {
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1) == 1;
            }
        }
    }

    private static final String SEEDED = "Chunk Test Ltd %";

    /** A thousand-odd accounts as they exist the moment before the migration, in one batch. */
    private static void seedUnplacedCustomers(Connection connection, int count, Instant createdAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into customers (name, credit_balance, version, created_at, region_id)"
                        + " values (?, 0, 0, ?, null)")) {
            for (int i = 1; i <= count; i++) {
                statement.setString(1, "Chunk Test Ltd " + i);
                statement.setObject(2, createdAt.atOffset(ZoneOffset.UTC));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Punches irregular holes through the whole id range, so the ids the pager walks are
     *  neither dense nor evenly spaced. */
    private static int punchHoles(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "delete from customers where (mod(id, 7) = 0 or mod(id, 13) = 0) and name like ?")) {
            statement.setString(1, SEEDED);
            return statement.executeUpdate();
        }
    }

    private static void purgeSeededCustomers(Connection connection) throws SQLException {
        // Raw JDBC and not the repository: deleting a thousand entities one at a time through
        // Hibernate would cost more than the test does, and these rows were never in a session.
        try (PreparedStatement statement = connection.prepareStatement(
                "delete from customer_region_history where customer_id in"
                        + " (select id from customers where name like ?)")) {
            statement.setString(1, SEEDED);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "delete from customers where name like ?")) {
            statement.setString(1, SEEDED);
            statement.executeUpdate();
        }
    }

    private static long regionOf(Connection connection, long customerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select region_id from customers where id = ?")) {
            statement.setLong(1, customerId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static int openPlacements(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select count(*) from customer_region_history where valid_to is null")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static int placedIn(Connection connection, long regionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from customers where region_id = ?")) {
            statement.setLong(1, regionId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /**
     * Puts customers.region_id back the way a production database holds it before the upgrade.
     *
     * <p>Dialect-branched for the same reason {@link SchemaSupport#enforceNotNull} is: H2 spells
     * the loosening {@code set null} and Postgres spells it {@code drop not null}, and neither
     * understands the other. Spelled for H2 only, this helper threw
     * {@code syntax error at or near "null"} on the database production runs on, which took the
     * five tests below — every test of the chunked backfill there is — out of service on Postgres
     * while they stayed green on H2 (B1).
     */
    private static void allowNullRegion(Connection connection) throws SQLException {
        String product = SchemaSupport.product(connection);
        String alter;
        if (product.contains("postgresql")) {
            alter = "alter table customers alter column region_id drop not null";
        } else if (product.contains("h2")) {
            alter = "alter table customers alter column region_id set null";
        } else {
            // Not skipped: a third dialect would otherwise run the whole backfill against a NOT
            // NULL column, place nothing, and report that as a pass.
            throw new IllegalStateException("No loosening is spelled for " + product);
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(alter);
        }
        // Asserted, not assumed. The point of the branch is that the column really is loose
        // again; a dialect whose alter is accepted and does nothing would otherwise leave every
        // insertUnplacedCustomer below failing for a reason nobody could read (B1).
        assertThat(SchemaSupport.isNullable(connection, "customers", "region_id"))
                .as("customers.region_id is loose again, whatever this dialect spells it")
                .isTrue();
    }

    /**
     * The same row, with the instant bound EXACTLY rather than through the JVM's default zone, so
     * a test can put an account's creation on the far side of a day boundary and know where it
     * put it (B1).
     */
    private static long insertUnplacedCustomerAt(Connection connection, String name, Instant createdAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into customers (name, credit_balance, version, created_at, region_id)"
                        + " values (?, 0, 0, ?, null)", ID)) {
            statement.setString(1, name);
            statement.setObject(2, createdAt.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    /** A customer as it exists the moment before the migration: real row, no region. */
    private static long insertUnplacedCustomer(Connection connection, String name, Instant createdAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into customers (name, credit_balance, version, created_at, region_id)"
                        + " values (?, 0, 0, ?, null)", ID)) {
            statement.setString(1, name);
            statement.setTimestamp(2, createdAt == null
                    ? null
                    : Timestamp.valueOf(createdAt.atOffset(ZoneOffset.UTC).toLocalDateTime()));
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    /**
     * The key column, NAMED. {@code Statement.RETURN_GENERATED_KEYS} means different things to the
     * two drivers: H2 hands back the identity column alone, so column 1 is the id, while the
     * Postgres driver turns it into {@code returning *} and hands back the whole row in the
     * table's PHYSICAL column order — where Hibernate puts credit_balance first and id third. Read
     * positionally, every customer these fixtures made came back with id 0 on Postgres, the
     * placements were opened against real ids the test then never looked for, and the assertions
     * below failed with "No value present" for a ledger that was in fact perfectly correct.
     * Naming the column makes the driver return that column and nothing else on both (B1).
     */
    private static final String[] ID = {"id"};

    private static long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            keys.next();
            return keys.getLong(1);
        }
    }

}
