package com.geneinvoice.audit;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Lob;
import org.hamcrest.Matchers;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.BasicValuedModelPart;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HOW THE TRAIL IS ACTUALLY STORED, as opposed to what the application can read back out of it.
 *
 * <p>audit_logs.before_json and audit_logs.after_json were {@code @Lob} for the whole life of this
 * product, and {@code @Lob} on a String makes Hibernate bind a Clob: on Postgres pgjdbc then
 * writes a LARGE OBJECT and puts its OID in the text column. Everything round-tripped, so no test
 * on H2 could see it — until four AutomationTriggerTest cases started reading audit rows from a
 * test method, which holds no transaction, and pgjdbc refuses the large-object API in auto-commit
 * mode: {@code Unable to access lob stream}. That is the failure these assertions stand against,
 * and it is why {@link #anAuditRowIsReadableWithNoTransactionOfItsOwn} looks like an ordinary read
 * and is anything but (B2).
 *
 * <p>The assertions are deliberately of two kinds. The MAPPING ones fail on H2 the moment somebody
 * re-adds the annotation, which is the realistic regression; the RAW COLUMN one cannot fail on H2,
 * because H2 stores the string either way, and earns its keep only when the suite is pointed at a
 * real Postgres (B2).
 */
class AuditLobStorageTest extends IntegrationTestBase {

    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired AuditService auditService;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired JdbcTemplate jdbc;

    User admin;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        actAs(admin);
    }

    @Test
    void theTwoTrailColumnsAndTheDisputesProposalAreNotBoundAsLargeObjects() throws Exception {
        assertNotALob(AuditLog.class, "beforeJson");
        assertNotALob(AuditLog.class, "afterJson");
        assertNotALob(Dispute.class, "proposedChangeJson");
    }

    @Test
    void droppingLobLeftTheColumnsThemselvesExactlyAsTheyWere() throws Exception {
        // columnDefinition is what emits the DDL, and it is untouched on all three, so no
        // deployment needs a schema change to take this fix — only the data migration (B2).
        assertThat(AuditLog.class.getDeclaredField("beforeJson")
                .getAnnotation(Column.class).columnDefinition()).isEqualTo("TEXT");
        assertThat(AuditLog.class.getDeclaredField("afterJson")
                .getAnnotation(Column.class).columnDefinition()).isEqualTo("TEXT");
        assertThat(Dispute.class.getDeclaredField("proposedChangeJson")
                .getAnnotation(Column.class).columnDefinition()).isEqualTo("TEXT");

        // Said against the database and not only against the annotation: the declared type of all
        // three is the type pending_changes has carried since it was born without the annotation.
        // Only the bind ever differed (B2).
        String text = declaredTypeOf("PENDING_CHANGES", "PAYLOAD_JSON");
        assertThat(declaredTypeOf("AUDIT_LOGS", "BEFORE_JSON")).isEqualTo(text);
        assertThat(declaredTypeOf("AUDIT_LOGS", "AFTER_JSON")).isEqualTo(text);
        assertThat(declaredTypeOf("DISPUTES", "PROPOSED_CHANGE_JSON")).isEqualTo(text);
    }

    /**
     * THE REGRESSION TEST FOR THE FOUR POSTGRES FAILURES. A test method holds no transaction and a
     * derived finder opens none of its own, so this read runs in auto-commit — which is exactly
     * where a Clob bind dies on Postgres. It passes on H2 with or without the fix, and on Postgres
     * only with it (B2).
     */
    @Test
    void anAuditRowIsReadableWithNoTransactionOfItsOwn() {
        Customer c = customer("Largely Objectionable Ltd");
        auditService.record("CUSTOMER", c.getId(), "CUSTOMER_UPDATED",
                Map.of("name", "Largely Objectionable Ltd"), Map.of("name", "Renamed Ltd"),
                admin.getId(), null, "storage test");

        List<AuditLog> rows = auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtDesc("CUSTOMER", c.getId());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getBeforeJson()).contains("Largely Objectionable Ltd");
        assertThat(rows.get(0).getAfterJson()).contains("Renamed Ltd");
    }

    @Test
    void anAuditRowLeavesReadableJsonInTheRawColumnsAndNotAnObjectId() {
        Customer c = customer("Largely Objectionable Ltd");
        AuditLog written = auditService.record("CUSTOMER", c.getId(), "CUSTOMER_UPDATED",
                Map.of("name", "Largely Objectionable Ltd"), Map.of("name", "Renamed Ltd"),
                admin.getId(), null, "storage test");

        // The assertion the application itself cannot make. An OID column reads back as a short
        // run of digits whose length has nothing to do with the JSON's — `5` for a blob of 60
        // characters — so length equality and a leading brace together are what "the text is
        // really in there" means (B2).
        for (String column : new String[]{"before_json", "after_json"}) {
            String raw = jdbc.queryForObject("select " + column + " from audit_logs where id = ?",
                    String.class, written.getId());
            Integer length = jdbc.queryForObject(
                    "select length(" + column + ") from audit_logs where id = ?",
                    Integer.class, written.getId());
            assertThat(raw).as(column).startsWith("{").doesNotMatch("\\d+");
            assertThat(length).as(column).isEqualTo(raw.length());
        }
        assertThat(jdbc.queryForObject("select before_json from audit_logs where id = ?",
                String.class, written.getId())).isEqualTo(written.getBeforeJson());
    }

    /**
     * The feature the columns exist for, end to end and through HTTP, because a panel is what a
     * person actually reads the trail in. This one passed on Postgres before the fix as well — the
     * timeline service holds a read-only transaction of its own, which is why the pre-existing
     * defect never took the endpoint down — and it is here so the fix is pinned by the product's
     * own surface and not only by a mapping assertion (B2).
     */
    @Test
    void theTimelineEndpointAnswersWithTheJsonThatWasWrittenAndNotWithDigits() throws Exception {
        Customer c = customer("Largely Objectionable Ltd");
        auditService.record("CUSTOMER", c.getId(), "CUSTOMER_UPDATED",
                Map.of("name", "Largely Objectionable Ltd"), Map.of("name", "Renamed Ltd"),
                admin.getId(), null, "storage test");

        mockMvc.perform(get("/api/audit")
                        .param("entityType", "CUSTOMER")
                        .param("entityId", String.valueOf(c.getId()))
                        .with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='CUSTOMER_UPDATED')].beforeJson")
                        .value(Matchers.hasItem(
                                Matchers.containsString("Largely Objectionable Ltd"))));
    }

    // ------------------------------------------------------------------------------- fixtures

    /** The annotation, because that is what somebody would re-add by hand — and the bind Hibernate
     *  actually chose, because {@code @Lob} is not the only way to get a locator (B2). */
    private void assertNotALob(Class<?> entity, String attribute) throws Exception {
        Field field = entity.getDeclaredField(attribute);
        assertThat(field.isAnnotationPresent(Lob.class))
                .as("%s.%s must not be @Lob", entity.getSimpleName(), attribute).isFalse();
        assertThat(jdbcTypeCodeOf(entity, attribute))
                .as("%s.%s must bind as character data, not as a locator",
                        entity.getSimpleName(), attribute)
                .isNotIn(Types.CLOB, Types.NCLOB, Types.BLOB);
    }

    private int jdbcTypeCodeOf(Class<?> entity, String attribute) {
        EntityMappingType type = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
                .getMappingMetamodel().getEntityDescriptor(entity);
        return ((BasicValuedModelPart) type.findAttributeMapping(attribute))
                .getJdbcMapping().getJdbcType().getJdbcTypeCode();
    }

    /** Upper-cased on both sides because H2 folds identifiers up and Postgres folds them down. */
    private String declaredTypeOf(String table, String column) {
        return jdbc.queryForObject("select data_type from information_schema.columns"
                        + " where upper(table_name) = ? and upper(column_name) = ?",
                String.class, table, column);
    }
}
