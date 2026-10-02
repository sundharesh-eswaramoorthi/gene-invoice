package com.geneinvoice.common.query;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.scope.AsOfIgnored;
import com.geneinvoice.privilege.Privileges;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@AsOfIgnored
@RequestMapping("/api/table-schemas")
@RequiredArgsConstructor
public class TableSchemaController {

    /** Who may read each table's schema: any one of the listed privileges. */
    private static final Map<String, List<String>> VIEW_PRIVILEGE = Map.ofEntries(
            Map.entry("invoices", List.of(Privileges.INVOICE_VIEW)),
            Map.entry("payments", List.of(Privileges.PAYMENT_VIEW)),
            Map.entry("customers", List.of(Privileges.CUSTOMER_VIEW)),
            Map.entry("promises", List.of(Privileges.PROMISE_VIEW)),
            Map.entry("products", List.of(Privileges.PRODUCT_VIEW)),
            Map.entry("users", List.of(Privileges.USER_VIEW)),
            Map.entry("roles", List.of(Privileges.ROLE_VIEW)),
            Map.entry("regions", List.of(Privileges.REGION_VIEW, Privileges.USER_MANAGE)),
            Map.entry("disputes", List.of(Privileges.DISPUTE_VIEW)),
            Map.entry("tasks", List.of(Privileges.TASK_VIEW)),
            // Whoever can see a record may see a change waiting on it (§5.11.9).
            Map.entry("approvals", List.of(Privileges.CHANGE_APPROVE, Privileges.INVOICE_VIEW, Privileges.PAYMENT_VIEW,
                    Privileges.PROMISE_VIEW, Privileges.DISPUTE_VIEW)),
            Map.entry("automation-rules", List.of(Privileges.AUTOMATION_VIEW)),
            Map.entry("automation-runs", List.of(Privileges.AUTOMATION_VIEW)),
            Map.entry("notifications", List.of(Privileges.NOTIFICATION_VIEW)),
            Map.entry("inbox", List.of(Privileges.EMAIL_VIEW)));

    private final CurrentUser currentUser;

    public record ColumnDto(String name, String label, String type, boolean sortable,
                            boolean filterable, List<String> operators, List<String> enumValues,
                            String referenceKind) {}

    public record SchemaDto(String entity, String defaultSort, List<Integer> pageSizes,
                            int defaultPageSize, List<String> datePresets, List<ColumnDto> columns) {}

    @GetMapping
    public List<String> entities() {
        return TableSchemas.entities().stream().filter(this::mayRead).toList();
    }

    @GetMapping("/{entity}")
    public SchemaDto schema(@PathVariable String entity) {
        TableSchema schema = TableSchemas.byEntity(entity).visibleTo(currentUser.isCustomer());
        if (!mayRead(schema.entity())) {
            throw new AccessDeniedException("Not allowed");
        }
        return describe(schema);
    }

    @GetMapping("/all")
    public Map<String, SchemaDto> all() {
        Map<String, SchemaDto> out = new LinkedHashMap<>();
        for (String entity : TableSchemas.entities()) {
            if (mayRead(entity)) {
                out.put(entity, describe(TableSchemas.byEntity(entity)
                        .visibleTo(currentUser.isCustomer())));
            }
        }
        return out;
    }

    private boolean mayRead(String entity) {
        return VIEW_PRIVILEGE.getOrDefault(entity, List.of()).stream().anyMatch(currentUser::has);
    }

    private SchemaDto describe(TableSchema schema) {
        List<ColumnDto> columns = schema.ordered().stream()
                .map(c -> new ColumnDto(c.name(), c.label(), c.type().name(), c.sortable(),
                        c.filterable(),
                        c.type().operators().stream().map(FilterOperator::wire).toList(),
                        c.enumValues(), c.referenceKind()))
                .toList();
        return new SchemaDto(schema.entity(), schema.defaultSort(), TableQuery.ALLOWED_SIZES,
                TableQuery.DEFAULT_SIZE, DateRange.PRESETS, columns);
    }
}
