package com.geneinvoice.common.query;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The drift tests over the whole table catalogue, landing at the first moment ALL FIFTEEN schemas
 * exist (S3, B1, B2, A6, A1 INTEGRATION).
 *
 * <p>Three properties, each of which has already been broken once in this programme or is one
 * line away from being broken:
 *
 * <ol>
 *   <li>A schema with no entry in TableSchemaController.VIEW_PRIVILEGE is invisible to everybody,
 *       for ever, and silently: {@code mayRead} answers false for a name it does not hold, so the
 *       table simply never appears in {@code GET /api/table-schemas} and no error is raised
 *       anywhere. An entry with no schema is the same mistake pointing the other way.</li>
 *   <li>{@code id} must be filterable on every schema, because {@code inScope} and every single
 *       entity condition match are literally {@code TableQuery.parseUnpaged(schema, null,
 *       List.of("id:eq:" + id))} — a non-filterable id turns a 404 gate into a 400.</li>
 *   <li>A schema must resolve by ITS OWN NAME on the wire. That one was actually broken:
 *       {@code byEntity} lower-cased its argument while the map was keyed by
 *       {@code schema.entity()}, which was invisible for as long as every registered name was
 *       lower case and became a 400 the moment {@code automationRules} was registered (A1).</li>
 * </ol>
 */
class SchemaCatalogueTest extends IntegrationTestBase {

    @Test
    void everyTableSchemaHasAViewPrivilegeAndEveryViewPrivilegeHasATableSchema() {
        // Read rather than re-typed: a list in this test that somebody kept in step by hand would
        // confirm whatever it was last edited to say (S3, B1).
        Set<String> gated = new TreeSet<>(viewPrivilege().keySet());
        Set<String> registered = new TreeSet<>(TableSchemas.entities());

        assertThat(registered)
                .describedAs("a schema with no VIEW_PRIVILEGE entry is invisible to everybody,"
                        + " silently and for ever")
                .isEqualTo(gated);

        // Fifteen, said out loud, so that adding a sixteenth is a deliberate edit here too.
        assertThat(registered).hasSize(15);
        assertThat(registered).contains("automationRules", "automationSteps", "tasks", "approvals",
                "regions");
        // Nothing may be gated on a blank or null privilege, which would read as "nobody".
        assertThat(viewPrivilege().values()).allMatch(p -> p != null && !p.isBlank());
    }

    @Test
    void everyTableSchemaHasAFilterableIdColumn() {
        for (String entity : TableSchemas.entities()) {
            TableSchema schema = TableSchemas.byEntity(entity);
            ColumnDef id = schema.byName().get("id");
            assertThat(id)
                    .describedAs("%s has no id column, so inScope() cannot gate a single record"
                            + " and a rule cannot be matched against one entity", entity)
                    .isNotNull();
            assertThat(id.filterable())
                    .describedAs("%s.id is not filterable, so the id:eq: query inScope() and the"
                            + " single-entity condition match both build would be a 400", entity)
                    .isTrue();
            assertThat(id.type().supports(FilterOperator.EQ))
                    .describedAs("%s.id does not support eq", entity).isTrue();
            // And it really does answer: the gate is exercised rather than assumed.
            assertThat(TableQuery.parseUnpaged(schema, null, List.of("id:eq:1")).filters())
                    .hasSize(1);
        }
    }

    @Test
    void everyRegisteredSchemaResolvesByItsOwnNameOnTheWire() throws Exception {
        User admin = userRepository.findByUsername("admin").orElseThrow();

        for (String entity : TableSchemas.entities()) {
            // The canonical name, exactly as entities() publishes it and exactly as the client
            // will ask for it, because entities() is where the client gets the list from.
            assertThat(TableSchemas.byEntity(entity).entity()).isEqualTo(entity);
            mockMvc.perform(get("/api/table-schemas/" + entity).with(as(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entity").value(entity));
            // And case-insensitively, because that is what byEntity has always promised and what
            // half this application's callers rely on.
            assertThat(TableSchemas.byEntity(entity.toLowerCase(Locale.ROOT)).entity())
                    .isEqualTo(entity);
            assertThat(TableSchemas.byEntity(entity.toUpperCase(Locale.ROOT)).entity())
                    .isEqualTo(entity);
        }

        // The admin holds every privilege, so the published list is the whole catalogue. If a
        // schema were missing its VIEW_PRIVILEGE entry this is the other place it would show.
        mockMvc.perform(get("/api/table-schemas").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(TableSchemas.entities().size()));
        mockMvc.perform(get("/api/table-schemas/automationRules").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity").value("automationRules"));

        // Two canonical names differing only in case would collide in the lower-cased index and
        // one of them would 400 for ever, so the index refuses to build at all. Asserted here
        // rather than trusted, because the refusal happens at class-init and nowhere else.
        Set<String> lowered = new LinkedHashSet<>();
        for (String entity : TableSchemas.entities()) {
            assertThat(lowered.add(entity.toLowerCase(Locale.ROOT)))
                    .describedAs("%s collides with another schema name in lower case", entity)
                    .isTrue();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> viewPrivilege() {
        try {
            // TableSchemaController is S3's file and nobody else's, so this test reads the map
            // rather than asking for it to be widened: an architecture test must not change the
            // shape of the thing it is checking (S3, INTEGRATION).
            Field field = TableSchemaController.class.getDeclaredField("VIEW_PRIVILEGE");
            field.setAccessible(true);
            return (Map<String, String>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "TableSchemaController.VIEW_PRIVILEGE has moved; this test is the only reader"
                            + " and has to move with it", e);
        }
    }
}
