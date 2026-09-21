package com.geneinvoice.task;

import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The task endpoints. Every one carries a task privilege; which records a caller may raise work on
 * is the record's own rule, checked in the service, so a task is exactly as raisable as the record
 * it hangs off (T1).
 */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService service;
    private final EmailTargets targets;
    private final TableQueryExecutor queryExecutor;

    private TableSchema schema() {
        return TableSchemas.TASKS;
    }

    /**
     * The task list: every task the caller may see, the record's own tab when {@code entityType}
     * and {@code entityId} are given, and the PRD's "my tasks" when {@code mine} is set.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.TASK_VIEW + "')")
    public PageResponse<TaskDtos.TaskDto> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) Boolean mine) {
        TableQuery query = TableQuery.parse(schema(), page, size, sort,
                withContext(FilterParams.from(request), entityType, entityId));
        ScopeResolver.Scope scope = service.scope(mine);
        var result = queryExecutor.run(Task.class, schema(), query, scope.predicates(), List.of());
        return PageResponse.of(service.toDtos(result.content()),
                query, result.total(), scope.lockedFilters());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.TASK_VIEW + "')")
    public TaskDtos.TaskDto get(@PathVariable Long id) {
        return service.toDto(service.get(id));
    }

    /** How many are still live on a record, for the tab's badge — the same shape documents use. */
    @GetMapping("/count")
    @PreAuthorize("hasAuthority('" + Privileges.TASK_VIEW + "')")
    public Map<String, Long> count(@RequestParam String entityType, @RequestParam Long entityId) {
        TaskEntityType type = TaskEntityType.parse(entityType);
        // The count is about somebody else's record until this says otherwise, so it is checked
        // here exactly as the list of that record's tasks would be.
        targets.requireVisible(type.toEmailEntityType(), entityId);
        return Map.of("open", service.openCountFor(type, entityId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.TASK_MANAGE + "')")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDtos.TaskDto create(@Valid @RequestBody TaskDtos.CreateTaskRequest req) {
        return service.toDto(service.create(req));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.TASK_MANAGE + "')")
    public TaskDtos.TaskDto update(@PathVariable Long id,
                                   @Valid @RequestBody TaskDtos.UpdateTaskRequest req) {
        return service.toDto(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.TASK_MANAGE + "')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /** Turns the convenience query params into ordinary filter chips, so scoping still applies. */
    private List<String> withContext(List<String> chips, String entityType, Long entityId) {
        if (entityType == null && entityId == null) return chips;
        List<String> merged = new ArrayList<>(chips == null ? List.of() : chips);
        if (entityType != null) merged.add("entityType:eq:" + TaskEntityType.parse(entityType).name());
        if (entityId != null) merged.add("entityId:eq:" + entityId);
        return merged;
    }
}
