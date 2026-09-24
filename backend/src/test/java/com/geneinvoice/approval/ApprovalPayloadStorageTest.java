package com.geneinvoice.approval;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Lob;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.BasicValuedModelPart;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Field;
import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * How a held change's two JSON blobs are actually STORED, as opposed to what the application can
 * read back out of them. The distinction is the whole point: with {@code @Lob} on a String field
 * Hibernate binds the value as a Clob, so the Postgres driver writes a LARGE OBJECT and puts its
 * OID in the text column — {@code select length(after_json), after_json from audit_logs} came back
 * {@code 5 | 60798} on the verification pass — and yet every getter, every DTO and every applier
 * round-trips perfectly, so no functional test anywhere can see it (B2).
 *
 * <p>H2 stores the string either way, which is exactly why the assertions below are about the
 * MAPPING and the RAW COLUMN rather than about the getter. audit_logs and disputes carried the
 * same defect and have since been fixed the same way, with common/LobTextUpgrade migrating the
 * rows already written; AuditLobStorageTest makes these same assertions about those three columns.
 * The comparison below still reads against audit_logs deliberately: what it pins is that the DDL
 * is TEXT on both tables and that only the bind ever differed (B2).
 */
class ApprovalPayloadStorageTest extends IntegrationTestBase {

    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;

    User admin;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        actAs(admin);
    }

    @Test
    void theTwoJsonColumnsOfAHeldChangeAreNotBoundAsLargeObjects() throws Exception {
        for (String attribute : new String[]{"payloadJson", "beforeJson"}) {
            Field field = PendingChange.class.getDeclaredField(attribute);

            // The annotation, because that is what somebody would re-add by hand (B2).
            assertThat(field.isAnnotationPresent(Lob.class))
                    .as("PendingChange.%s must not be @Lob", attribute).isFalse();

            // And the bind Hibernate actually chose, because @Lob is not the only way to get one.
            assertThat(jdbcTypeCodeOf(attribute))
                    .as("PendingChange.%s must bind as character data, not as a locator", attribute)
                    .isNotIn(Types.CLOB, Types.NCLOB, Types.BLOB);
        }
    }

    @Test
    void droppingLobLeftTheColumnItselfExactlyAsItWas() throws Exception {
        // columnDefinition is what emits the DDL, and it is untouched, so no deployment that
        // already ran the schema needs anything done to it (B2).
        for (String attribute : new String[]{"payloadJson", "beforeJson"}) {
            assertThat(PendingChange.class.getDeclaredField(attribute)
                    .getAnnotation(Column.class).columnDefinition()).isEqualTo("TEXT");
        }

        // Stated against the database and not only against the annotation: pending_changes'
        // payload column is the SAME declared type as the audit blob beside it, which IS still
        // @Lob. Only the bind differs — that is the entire fix (B2).
        assertThat(declaredTypeOf("PENDING_CHANGES", "PAYLOAD_JSON"))
                .isEqualTo(declaredTypeOf("AUDIT_LOGS", "AFTER_JSON"));
        assertThat(declaredTypeOf("PENDING_CHANGES", "BEFORE_JSON"))
                .isEqualTo(declaredTypeOf("AUDIT_LOGS", "BEFORE_JSON"));
    }

    @Test
    void aHeldChangeLeavesReadableJsonInTheRawColumnAndNotAnObjectId() throws Exception {
        Customer doomed = customer("Largely Objectionable Ltd");

        // CUSTOMER_DELETE is always checked, so this needs no threshold — and it is the one held
        // action that fills before_json as well as payload_json (CP-04, B2).
        MvcResult result = mockMvc.perform(delete("/api/customers/" + doomed.getId()).with(as(admin)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("PENDING_APPROVAL"))
                .andReturn();
        Long changeId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("pendingChangeId").asLong();

        PendingChange held = pendingChangeRepository.findById(changeId).orElseThrow();
        assertThat(held.getPayloadJson()).isNotNull();
        assertThat(held.getBeforeJson()).contains("Largely Objectionable Ltd");

        // The assertion the application itself cannot make. An OID column reads back as a short
        // run of digits whose length has nothing to do with the JSON's — `5` for a blob of 60
        // characters — so length equality and a leading brace together are what "the text is
        // really in there" means (B2).
        String rawPayload = raw(changeId, "payload_json");
        String rawBefore = raw(changeId, "before_json");

        assertThat(rawPayload).isEqualTo(held.getPayloadJson()).startsWith("{");
        assertThat(rawBefore).isEqualTo(held.getBeforeJson()).startsWith("{");
        assertThat(rawBefore).contains("Largely Objectionable Ltd");
        assertThat(lengthIn(changeId, "before_json")).isEqualTo(held.getBeforeJson().length());
        assertThat(rawBefore).doesNotMatch("\\d+");
    }

    // ------------------------------------------------------------------------------- fixtures

    /** The JDBC type Hibernate binds the attribute with — {@link Types#CLOB} is the defect (B2). */
    private int jdbcTypeCodeOf(String attribute) {
        EntityMappingType type = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
                .getMappingMetamodel().getEntityDescriptor(PendingChange.class);
        return ((BasicValuedModelPart) type.findAttributeMapping(attribute))
                .getJdbcMapping().getJdbcType().getJdbcTypeCode();
    }

    /** Upper-cased on both sides because H2 folds identifiers up and Postgres folds them down. */
    private String declaredTypeOf(String table, String column) {
        return jdbc.queryForObject("select data_type from information_schema.columns"
                + " where upper(table_name) = ? and upper(column_name) = ?",
                String.class, table, column);
    }

    private String raw(Long changeId, String column) {
        return jdbc.queryForObject(
                "select " + column + " from pending_changes where id = ?", String.class, changeId);
    }

    private int lengthIn(Long changeId, String column) {
        return jdbc.queryForObject(
                "select length(" + column + ") from pending_changes where id = ?",
                Integer.class, changeId);
    }
}
