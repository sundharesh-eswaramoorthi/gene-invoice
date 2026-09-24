package com.geneinvoice.common;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * THE MIGRATION THAT MAKES DROPPING &#64;Lob SAFE ON A DATABASE THAT HAS ALREADY RUN (B2).
 *
 * <p>A Postgres deployment written by the old mapping holds a large-object OID in
 * audit_logs.before_json, audit_logs.after_json and disputes.proposed_change_json. Fixing the
 * mapping alone would leave every one of those rows reading back as the literal string "60798" for
 * ever, which is why {@code LobTextUpgrade} exists and why it is not optional.
 *
 * <p>THE TWO TESTS SAY DIFFERENT THINGS AND ONE OF THEM IS POSTGRES-ONLY, DELIBERATELY. The safety
 * property — that a column already holding plain text is not touched, which is the state of every
 * H2 database, every new deployment, and every deployment after the first migrating boot — is
 * asserted on both engines. The migration itself cannot be stated on H2 at all: H2 has no large
 * objects of this kind, so there is nothing to convert and a test pretending otherwise would be
 * asserting nothing. It is skipped there, loudly, and runs for real when the suite is pointed at a
 * Postgres (see the runbook in the verification report) (B2).
 */
class LobTextUpgradeTest extends IntegrationTestBase {

    @Autowired LobTextUpgrade upgrade;
    @Autowired AuditService auditService;
    @Autowired JdbcTemplate jdbc;

    User admin;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        actAs(admin);
    }

    @Test
    void theUpgradeLeavesAColumnThatIsAlreadyPlainTextExactlyAsItIs() {
        Customer c = customer("Largely Objectionable Ltd");
        AuditLog json = auditService.record("CUSTOMER", c.getId(), "CUSTOMER_UPDATED",
                Map.of("name", "Largely Objectionable Ltd"), Map.of("name", "Renamed Ltd"),
                admin.getId(), null, "upgrade test");
        // A trail entry whose before really IS a bare number, written by the product's own writer:
        // the one shape that could be mistaken for an OID by anything that only looks at the text.
        AuditLog digits = auditService.record("CUSTOMER", c.getId(), "CUSTOMER_COUNTED",
                999999999L, null, admin.getId(), null, "upgrade test");

        upgrade.afterPropertiesSet();

        assertThat(raw(json.getId(), "before_json")).isEqualTo(json.getBeforeJson()).startsWith("{");
        assertThat(raw(json.getId(), "after_json")).isEqualTo(json.getAfterJson()).startsWith("{");
        assertThat(raw(digits.getId(), "before_json")).isEqualTo("999999999");
    }

    @Test
    void anOidTheOldMappingLeftBehindBecomesReadableTextAndTheObjectIsUnlinked() {
        assumeTrue(postgres(), "large objects exist only on Postgres; nothing to migrate on H2");

        Customer c = customer("Largely Objectionable Ltd");
        AuditLog untouched = auditService.record("CUSTOMER", c.getId(), "CUSTOMER_UPDATED",
                Map.of("name", "Largely Objectionable Ltd"), null,
                admin.getId(), null, "upgrade test");
        AuditLog stranded = auditService.record("CUSTOMER", c.getId(), "CUSTOMER_RENAMED",
                Map.of("name", "Written As An Object Ltd"), null,
                admin.getId(), null, "upgrade test");

        // Exactly what the old mapping left behind: the text in a large object, the object's id in
        // the column. Written here with server-side SQL rather than the driver's LO API, which is
        // the same row either way (B2).
        String text = stranded.getBeforeJson();
        long oid = jdbc.queryForObject("select lo_from_bytea(0, convert_to(?, 'UTF8'))",
                Long.class, text);
        jdbc.update("update audit_logs set before_json = ? where id = ?",
                String.valueOf(oid), stranded.getId());
        // And a row whose digits name no object at all, which must survive untouched.
        AuditLog digits = auditService.record("CUSTOMER", c.getId(), "CUSTOMER_COUNTED",
                freeOid(oid), null, admin.getId(), null, "upgrade test");

        assertThat(raw(stranded.getId(), "before_json")).isEqualTo(String.valueOf(oid));

        upgrade.afterPropertiesSet();

        assertThat(raw(stranded.getId(), "before_json"))
                .as("the object's text is back in its own column")
                .isEqualTo(text).contains("Written As An Object Ltd");
        assertThat(objectsNamed(oid)).as("the object was unlinked, not left to leak").isZero();
        assertThat(raw(untouched.getId(), "before_json")).isEqualTo(untouched.getBeforeJson());
        assertThat(raw(digits.getId(), "before_json")).isEqualTo(digits.getBeforeJson());

        // Idempotent: a second boot finds no large object at all and changes nothing.
        upgrade.afterPropertiesSet();
        assertThat(raw(stranded.getId(), "before_json")).isEqualTo(text);
    }

    /**
     * The walk is keyset-paged over the rows that LOOK like an OID, and most of those are decided
     * in Java rather than in SQL — a run of digits too big to be an oid cannot be range-checked in
     * the query without a cast that would fail the whole statement on the first row of JSON. So a
     * whole page can be scanned and nothing kept, and a pager that stopped on "kept nothing" would
     * leave every row after it unmigrated for ever, silently. This is that page (B2).
     */
    @Test
    void theWalkPagesPastAWholeChunkOfRowsItCannotConvert() {
        assumeTrue(postgres(), "large objects exist only on Postgres; nothing to migrate on H2");

        Customer c = customer("Largely Objectionable Ltd");
        // A full page and one more of ten-digit numbers, every one of them too big to be an oid.
        jdbc.update("insert into audit_logs (entity_type, entity_id, action, before_json, created_at)"
                        + " select 'CUSTOMER', ?, 'PADDING', (9000000000 + g)::text, now()"
                        + " from generate_series(1, ?) g",
                c.getId(), LobTextUpgrade.CHUNK + 1);
        AuditLog stranded = auditService.record("CUSTOMER", c.getId(), "CUSTOMER_RENAMED",
                Map.of("name", "Beyond The First Page Ltd"), null,
                admin.getId(), null, "upgrade test");
        String text = stranded.getBeforeJson();
        long oid = jdbc.queryForObject("select lo_from_bytea(0, convert_to(?, 'UTF8'))",
                Long.class, text);
        jdbc.update("update audit_logs set before_json = ? where id = ?",
                String.valueOf(oid), stranded.getId());

        upgrade.afterPropertiesSet();

        assertThat(raw(stranded.getId(), "before_json"))
                .as("a row a whole page beyond the unconvertible ones was still migrated")
                .isEqualTo(text);
        assertThat(objectsNamed(oid)).isZero();
    }

    // ------------------------------------------------------------------------------- fixtures

    private boolean postgres() {
        String product = jdbc.execute((ConnectionCallback<String>) c ->
                c.getMetaData().getDatabaseProductName());
        return product != null && product.toLowerCase(Locale.ROOT).contains("postgresql");
    }

    /** An oid no object answers to, so the row built from it is a number and nothing more. */
    private long freeOid(long taken) {
        long candidate = taken + 1;
        while (objectsNamed(candidate) > 0) candidate++;
        return candidate;
    }

    private int objectsNamed(long oid) {
        Integer count = jdbc.queryForObject(
                "select count(*) from pg_largeobject_metadata where oid = cast(? as oid)",
                Integer.class, oid);
        return count == null ? 0 : count;
    }

    private String raw(Long id, String column) {
        return jdbc.queryForObject(
                "select " + column + " from audit_logs where id = ?", String.class, id);
    }
}
