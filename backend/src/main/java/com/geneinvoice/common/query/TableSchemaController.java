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

/**
 * Publishes what each table can be sorted and filtered by, so the frontend builds its filter UI
 * from the columns themselves rather than a hand-maintained copy (D.4, AC-D3).
 *
 * <p>A schema is the shape of a table, so it is read under the same privilege the table's own list
 * endpoint is: a caller who may not list users has no business enumerating the users table's
 * columns either. Without this, any authenticated caller — including one holding no privileges at
 * all — could read every schema in the app in one call (AUTH-07).
 */
@RestController
@RequestMapping("/api/table-schemas")
@RequiredArgsConstructor
public class TableSchemaController {

    /** The privilege that lets a caller read each table's rows, and so its shape. */
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

    /** The tables this caller may read the shape of; naming the rest would say they exist. */
    @GetMapping
    public List<String> entities() {
        return TableSchemas.entities().stream().filter(this::mayRead).toList();
    }

    @GetMapping("/{entity}")
    public SchemaDto schema(@PathVariable String entity) {
        // The same view of the columns the list endpoints validate against, so the published
        // schema and what a filter may name can never drift apart.
        TableSchema schema = TableSchemas.byEntity(entity).visibleTo(currentUser.isCustomer());
        if (!mayRead(schema.entity())) {
            throw new AccessDeniedException("Not allowed");
        }
        return describe(schema);
    }

    /**
     * Convenience for a client that wants every schema in one round trip. It returns the ones the
     * caller may read rather than refusing the whole call, so a caller with a narrower role still
     * gets its own tables' filter UI.
     */
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

    /**
     * Whether the caller holds the table's view privilege. A table with no entry here is one
     * nobody has been granted a privilege for, and is withheld rather than opened by default.
     */
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
