package com.geneinvoice.common.query;

import com.geneinvoice.auth.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Publishes what each table can be sorted and filtered by, so the frontend builds its filter UI
 * from the columns themselves rather than a hand-maintained copy (D.4, AC-D3).
 */
@RestController
@RequestMapping("/api/table-schemas")
@RequiredArgsConstructor
public class TableSchemaController {

    private final CurrentUser currentUser;

    public record ColumnDto(String name, String label, String type, boolean sortable,
                            boolean filterable, List<String> operators, List<String> enumValues,
                            String referenceKind) {}

    public record SchemaDto(String entity, String defaultSort, List<Integer> pageSizes,
                            int defaultPageSize, List<String> datePresets, List<ColumnDto> columns) {}

    @GetMapping
    public List<String> entities() {
        return TableSchemas.entities();
    }

    @GetMapping("/{entity}")
    public SchemaDto schema(@PathVariable String entity) {
        // The same view of the columns the list endpoints validate against, so the published
        // schema and what a filter may name can never drift apart.
        TableSchema schema = TableSchemas.byEntity(entity).visibleTo(currentUser.isCustomer());
        List<ColumnDto> columns = schema.ordered().stream()
                .map(c -> new ColumnDto(c.name(), c.label(), c.type().name(), c.sortable(),
                        c.filterable(),
                        c.type().operators().stream().map(FilterOperator::wire).toList(),
                        c.enumValues(), c.referenceKind()))
                .toList();
        return new SchemaDto(schema.entity(), schema.defaultSort(), schema.pageSizes(),
                schema.defaultPageSize(), DateRange.PRESETS, columns);
    }

    /** Convenience for a client that wants every schema in one round trip. */
    @GetMapping("/all")
    public Map<String, SchemaDto> all() {
        return TableSchemas.entities().stream()
                .collect(java.util.stream.Collectors.toMap(e -> e, this::schema));
    }
}
