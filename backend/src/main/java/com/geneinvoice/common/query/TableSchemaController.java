package com.geneinvoice.common.query;

import com.geneinvoice.auth.CurrentUser;
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
@RequestMapping("/api/table-schemas")
@RequiredArgsConstructor
public class TableSchemaController {

    private static final Map<String, String> VIEW_PRIVILEGE = Map.of(
            "invoices", Privileges.INVOICE_VIEW,
            "payments", Privileges.PAYMENT_VIEW,
            "customers", Privileges.CUSTOMER_VIEW,
            "promises", Privileges.PROMISE_VIEW,
            "products", Privileges.PRODUCT_VIEW,
            "users", Privileges.USER_VIEW,
            "roles", Privileges.ROLE_VIEW,
            "disputes", Privileges.DISPUTE_VIEW,
            "notifications", Privileges.NOTIFICATION_VIEW,
            "inbox", Privileges.EMAIL_VIEW);

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
        String privilege = VIEW_PRIVILEGE.get(entity);
        return privilege != null && currentUser.has(privilege);
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
