package com.geneinvoice.task;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.common.bulk.Csv;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.common.scope.AsOfAware;
import com.geneinvoice.history.Records;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Tasks (§6.12). Every GET answers as of a date; a record's tab is the list asked with its context. */
@RestController
@AsOfAware
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private static final String VIEW = "hasAuthority('" + Privileges.TASK_VIEW + "')";
    private static final String MANAGE = "hasAuthority('" + Privileges.TASK_MANAGE + "')";

    public static final Map<String, TaskStatus> BULK_ACTIONS = Map.of(
            "START", TaskStatus.IN_PROGRESS,
            "MARK_DONE", TaskStatus.DONE,
            "REOPEN", TaskStatus.OPEN,
            "CANCEL", TaskStatus.CANCELLED);

    private final TaskService service;
    private final BulkExecutor bulkExecutor;
    private final Records records;

    @GetMapping
    @PreAuthorize(VIEW)
    public PageResponse<TaskDtos.TaskDto> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request,
            @RequestParam(required = false) TaskEntityType entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) Long customerId) {
        return service.page(TableQuery.parse(TableSchemas.TASKS, page, size, sort,
                withContext(FilterParams.from(request), entityType, entityId, customerId)), false);
    }

    @GetMapping("/summary")
    @PreAuthorize(VIEW)
    public TaskDtos.TaskSummaryDto summary(HttpServletRequest request,
                                           @RequestParam(required = false) TaskEntityType entityType,
                                           @RequestParam(required = false) Long entityId,
                                           @RequestParam(required = false) Long customerId) {
        return service.summary(TableQuery.parseUnpaged(TableSchemas.TASKS, null,
                withContext(FilterParams.from(request), entityType, entityId, customerId)), false);
    }

    @GetMapping("/mine")
    @PreAuthorize(VIEW)
    public PageResponse<TaskDtos.TaskDto> mine(@RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size,
                                               @RequestParam(required = false) String sort,
                                               HttpServletRequest request) {
        return service.page(TableQuery.parse(TableSchemas.TASKS, page, size, sort, FilterParams.from(request)), true);
    }

    @GetMapping("/mine/summary")
    @PreAuthorize(VIEW)
    public TaskDtos.TaskSummaryDto mineSummary(HttpServletRequest request) {
        return service.summary(TableQuery.parseUnpaged(TableSchemas.TASKS, null, FilterParams.from(request)), true);
    }

    @GetMapping("/mine/count")
    @PreAuthorize(VIEW)
    public TaskDtos.MineCountDto mineCount() {
        return service.mineCount();
    }

    @GetMapping("/count")
    @PreAuthorize(VIEW)
    public TaskDtos.TaskCountDto count(@RequestParam TaskEntityType entityType, @RequestParam Long entityId) {
        return service.count(entityType, entityId);
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public TaskDtos.TaskDto get(@PathVariable Long id) {
        return service.toDto(service.get(id));
    }

    @PostMapping
    @PreAuthorize(MANAGE)
    public TaskDtos.TaskDto create(@Valid @RequestBody TaskDtos.CreateTaskRequest req) {
        return service.toDto(service.create(req));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(MANAGE)
    public TaskDtos.TaskDto patch(@PathVariable Long id, @Valid @RequestBody TaskDtos.PatchTaskRequest req) {
        return service.toDto(service.patch(id, req));
    }

    /** TASK_MANAGE in the task's region, or an effective assignee: decided per task by the service. */
    @PostMapping("/{id}/status")
    @PreAuthorize(VIEW)
    public TaskDtos.TaskDto status(@PathVariable Long id, @Valid @RequestBody TaskDtos.StatusRequest req) {
        return service.toDto(service.changeStatus(id, req.status(), req.version()));
    }

    @PostMapping("/bulk")
    @PreAuthorize(MANAGE)
    public BulkDtos.BulkResult bulk(@Valid @RequestBody BulkDtos.BulkRequest req) {
        TaskStatus to = BULK_ACTIONS.get(req.action());
        if (to == null) {
            throw new BadRequestException("Unknown bulk action: " + req.action()
                    + " (expected one of " + List.of("START", "MARK_DONE", "REOPEN", "CANCEL") + ")");
        }
        // Rows the caller sees but may not change in their region drop out as not reachable.
        List<Long> ids = resolveIds(req, ScopeResolver.Access.MANAGE);
        boolean truncated = req.allMatching() && ids.size() >= TableQueryExecutor.BULK_ID_LIMIT;
        return bulkExecutor.run(req, ids, truncated,
                BulkExecutor.eligibility(id -> service.changeStatus(id, to, null)));
    }

    @PostMapping("/export")
    @AsOfAware
    @PreAuthorize("hasAuthority('" + Privileges.EXPORT_DATA + "') and " + VIEW)
    public ResponseEntity<String> export(@RequestBody BulkDtos.BulkRequest req) {
        List<Long> ids = resolveIds(req, ScopeResolver.Access.VIEW);
        List<TaskDtos.TaskDto> rows = service.toDtos(records.findAll(Task.class, ids));
        String csv = Csv.of(
                List.of("Id", "Title", "Status", "Due", "On", "Record", "Customer", "Assigned to", "Region",
                        "Created", "Completed"),
                rows.stream().map(t -> List.<Object>of(
                        t.id(), t.title(), t.status(), t.dueDate() == null ? "" : t.dueDate(), t.entityType(),
                        t.entityLabel(), t.customerName() == null ? "" : t.customerName(),
                        String.join("; ", t.assignees().stream().map(a -> a.label()).toList()),
                        t.regionCode() == null ? "" : t.regionCode(), t.createdAt(),
                        t.completedAt() == null ? "" : t.completedAt())).toList());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tasks.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    private List<Long> resolveIds(BulkDtos.BulkRequest req, ScopeResolver.Access access) {
        TableQuery query = TableQuery.parseUnpaged(TableSchemas.TASKS, req.sort(), req.filters());
        List<Long> permitted = service.idsMatching(query, TableQueryExecutor.BULK_ID_LIMIT, access);
        if (req.allMatching()) return permitted;
        if (req.ids() == null || req.ids().isEmpty()) {
            throw new BadRequestException("Provide ids or set selectAllMatchingFilter");
        }
        return req.ids().stream().filter(permitted::contains).toList();
    }

    private List<String> withContext(List<String> chips, TaskEntityType entityType, Long entityId, Long customerId) {
        if (entityType == null && entityId == null && customerId == null) return chips;
        List<String> merged = new ArrayList<>(chips == null ? List.of() : chips);
        if (entityType != null) merged.add("entityType:eq:" + entityType.name());
        if (entityId != null) merged.add("entityId:eq:" + entityId);
        if (customerId != null) merged.add("customerId:eq:" + customerId);
        return merged;
    }
}
