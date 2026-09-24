package com.geneinvoice.task;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.asof.AsOfCsv;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.common.bulk.Csv;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.auth.CurrentUser;
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

/**
 * EXACTLY FOUR GET MAPPINGS, and a fifth is a change that has to be agreed with B3-CLOSE, whose
 * as-of allowlist already names these by path (A6, B3 INTEGRATION).
 */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService service;
    private final BulkExecutor bulkExecutor;
    private final CurrentUser currentUser;

    public static final List<String> BULK_ACTIONS = List.of("COMPLETE", "CANCEL", "REASSIGN");

    private TableSchema schema() {
        return TaskSchemas.TASKS.visibleTo(currentUser.isCustomer());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.TASK_VIEW + "')")
    public PageResponse<TaskDtos.TaskDto> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) TaskEntityType entityType,
            @RequestParam(required = false) Long entityId) {
        return service.page(TableQuery.parse(schema(), page, size, sort,
                withContext(FilterParams.from(request), customerId, entityType, entityId)));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('" + Privileges.TASK_VIEW + "')")
    public TaskDtos.TaskSummaryTiles summary(
            HttpServletRequest request,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) TaskEntityType entityType,
            @RequestParam(required = false) Long entityId) {
        // The SAME filters the list ran, parsed the same way, so the tiles and the rows cannot
        // disagree about what is on the screen (A6).
        return service.tiles(TableQuery.parseUnpaged(schema(), null,
                withContext(FilterParams.from(request), customerId, entityType, entityId)));
    }

    /**
     * The sidebar badge. Not as-of capable and deliberately so: a count of what is waiting for me
     * is a fact about now, and B3-CLOSE's allowlist names the other three (A6, B3).
     */
    @GetMapping("/count")
    @PreAuthorize("hasAuthority('" + Privileges.TASK_VIEW + "')")
    public Map<String, Long> count(@RequestParam(defaultValue = "false") boolean mine) {
        return Map.of("count", service.count(mine));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.TASK_VIEW + "')")
    public TaskDtos.TaskDto get(@PathVariable Long id) {
        return service.dto(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.TASK_MANAGE + "')")
    public TaskDtos.TaskDto create(@Valid @RequestBody TaskDtos.CreateTaskRequest req) {
        return service.create(req);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.TASK_MANAGE + "')")
    public TaskDtos.TaskDto update(@PathVariable Long id,
                                   @Valid @RequestBody TaskDtos.UpdateTaskRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('" + Privileges.TASK_MANAGE + "')")
    public TaskDtos.TaskDto complete(@PathVariable Long id) {
        return service.complete(id);
    }

    /**
     * Through BulkExecutor.eligibility, so "this one was already done" is reported as a row that
     * did not qualify rather than failing the whole run — the mistake PaymentController makes is
     * not repeated here (A6, TBL-05).
     */
    @PostMapping("/bulk")
    @PreAuthorize("hasAuthority('" + Privileges.TASK_MANAGE + "')")
    public BulkDtos.BulkResult bulk(@Valid @RequestBody BulkDtos.BulkRequest req) {
        List<Long> ids = resolveIds(req);
        boolean truncated = req.allMatching() && ids.size() >= TableQueryExecutor.BULK_ID_LIMIT;
        return switch (req.action()) {
            case "COMPLETE" -> bulkExecutor.run(req, ids, truncated,
                    BulkExecutor.eligibility(service::complete));
            case "CANCEL" -> bulkExecutor.run(req, ids, truncated,
                    BulkExecutor.eligibility(service::cancel));
            case "REASSIGN" -> {
                Long userId = req.longParam("userId");
                if (userId == null) {
                    throw new BadRequestException("REASSIGN requires params.userId");
                }
                yield bulkExecutor.run(req, ids, truncated,
                        BulkExecutor.eligibility(id -> service.reassign(id, List.of(userId))));
            }
            default -> throw new BadRequestException(
                    "Unknown bulk action: " + req.action() + " (expected one of " + BULK_ACTIONS + ")");
        };
    }

    @PostMapping("/export")
    @PreAuthorize("hasAuthority('" + Privileges.EXPORT_DATA + "') and hasAuthority('"
            + Privileges.TASK_VIEW + "')")
    public ResponseEntity<String> export(@RequestBody BulkDtos.BulkRequest req) {
        List<Long> ids = resolveIds(req);
        // Csv.of carries the =+-@ formula-injection guard, which is what makes a user-authored
        // task title safe to put in a spreadsheet (A6, E16).
        List<TaskDtos.TaskDto> rows = service.allMatching(
                        TableQuery.parseUnpaged(schema(), req.sort(), req.filters())).stream()
                .filter(t -> ids.contains(t.id()))
                .toList();
        String csv = Csv.of(
                List.of("Id", "Title", "About", "Record", "Customer", "Due", "Status", "Overdue",
                        "Assigned to", "Created by rule", "Created"),
                rows.stream().map(t -> List.<Object>of(
                        t.id(), t.title(), t.entityType(), t.entityLabel(),
                        t.customerName() == null ? "" : t.customerName(),
                        t.dueDate() == null ? "" : t.dueDate(), t.status(), t.overdue(),
                        t.assignees().stream().map(TaskDtos.AssigneeDto::username)
                                .filter(u -> u != null).reduce((a, b) -> a + "; " + b).orElse(""),
                        t.createdByRuleId() == null ? "" : t.createdByRuleId(),
                        t.createdAt())).toList());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + AsOfCsv.filename("tasks") + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                // A downloaded file outlives the banner that framed it, so the caveat travels
                // inside the file: one leading cell, empty on a live export (B3).
                .body(AsOfCsv.caveat() + csv);
    }

    private List<Long> resolveIds(BulkDtos.BulkRequest req) {
        TableQuery query = TableQuery.parseUnpaged(schema(), req.sort(), req.filters());
        List<Long> permitted = service.idsMatching(query, TableQueryExecutor.BULK_ID_LIMIT);
        if (req.allMatching()) return permitted;
        if (req.ids() == null || req.ids().isEmpty()) {
            throw new BadRequestException("Provide ids or set selectAllMatchingFilter");
        }
        return req.ids().stream().filter(permitted::contains).toList();
    }

    /**
     * The record context a detail tab asks with, merged into the chips so the tab is filterable
     * and scoped exactly like the list rather than being an ad-hoc unregistered view (A6).
     */
    private List<String> withContext(List<String> chips, Long customerId,
                                     TaskEntityType entityType, Long entityId) {
        if (customerId == null && entityType == null && entityId == null) return chips;
        List<String> merged = new ArrayList<>(chips == null ? List.of() : chips);
        if (customerId != null) merged.add("customerId:eq:" + customerId);
        if (entityType != null) merged.add("entityType:eq:" + entityType.name());
        if (entityId != null) merged.add("entityId:eq:" + entityId);
        return merged;
    }
}
