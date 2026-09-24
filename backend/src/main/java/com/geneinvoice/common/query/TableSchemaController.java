package com.geneinvoice.common.query;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.asof.AsOfSupport;
import com.geneinvoice.privilege.Privileges;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
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

    // Map.ofEntries, not Map.of: the live set already sits on the JDK's 10-pair ceiling, and the
    // five keys below are seeded now so no later feature has to reshape this expression again
    // (B1, B2, A1, A6 INTEGRATION). A key whose schema does not exist yet is harmless — entities()
    // iterates TableSchemas.entities() and mayRead is only asked about names that are there.
    //
    // mayRead keeps its exact meaning under regions: currentUser.has(X) already reads as "somewhere",
    // because a privilege exercisable in no region is dropped from the authority set (B1).
    private static final Map<String, String> VIEW_PRIVILEGE = Map.ofEntries(
            Map.entry("invoices", Privileges.INVOICE_VIEW),
            Map.entry("payments", Privileges.PAYMENT_VIEW),
            Map.entry("customers", Privileges.CUSTOMER_VIEW),
            Map.entry("promises", Privileges.PROMISE_VIEW),
            Map.entry("products", Privileges.PRODUCT_VIEW),
            Map.entry("users", Privileges.USER_VIEW),
            Map.entry("roles", Privileges.ROLE_VIEW),
            Map.entry("disputes", Privileges.DISPUTE_VIEW),
            Map.entry("notifications", Privileges.NOTIFICATION_VIEW),
            Map.entry("inbox", Privileges.EMAIL_VIEW),
            Map.entry("regions", Privileges.REGION_VIEW),
            Map.entry("approvals", Privileges.APPROVAL_VIEW),
            Map.entry("tasks", Privileges.TASK_VIEW),
            Map.entry("automationRules", Privileges.AUTOMATION_VIEW),
            Map.entry("automationSteps", Privileges.AUTOMATION_VIEW));

    private final CurrentUser currentUser;

    // ObjectProvider and not a plain dependency, exactly as AsOfController takes it: the answer is
    // the mirror registry's, the registry lives in com.geneinvoice.history, and common.query must
    // never import it. Absent means "no table has a mirror", which is the honest answer for an
    // installation built without the history feature (B3).
    private final ObjectProvider<AsOfSupport> asOfSupport;

    public record ColumnDto(String name, String label, String type, boolean sortable,
                            boolean filterable, List<String> operators, List<String> enumValues,
                            String referenceKind, String asOfMode) {}

    public record SchemaDto(String entity, String defaultSort, List<Integer> pageSizes,
                            int defaultPageSize, List<String> datePresets, List<ColumnDto> columns,
                            boolean asOfSupported) {}

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
                        c.enumValues(), c.referenceKind(), c.asOfMode()))
                .toList();
        // The ONE literal S3 left for B3, and the last edit this file takes. A client decides
        // whether to offer a date picker at all from this flag, so it has to mean "there is a
        // mirror schema behind this table" and not "somebody remembered to say true" (B3).
        AsOfSupport available = asOfSupport.getIfAvailable();
        boolean asOfSupported = available != null && available.supports(schema.entity());
        return new SchemaDto(schema.entity(), schema.defaultSort(), TableQuery.ALLOWED_SIZES,
                TableQuery.DEFAULT_SIZE, DateRange.PRESETS, columns, asOfSupported);
    }
}
