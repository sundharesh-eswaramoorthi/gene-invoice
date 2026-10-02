package com.geneinvoice.dispute;

import com.geneinvoice.approval.PendingChangeScope;
import com.geneinvoice.approval.PendingChangeViews;
import com.geneinvoice.assignee.AssignedTo;
import com.geneinvoice.assignee.AssigneeDtos;
import com.geneinvoice.assignee.AssigneeService;
import com.geneinvoice.assignee.AssigneeStore;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Outcomes;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.Csv;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfAware;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.history.Records;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.RecordAccess;
import com.geneinvoice.region.Region;
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
import java.util.Set;

@RestController
@AsOfAware
@RequestMapping("/api/disputes")
@RequiredArgsConstructor
public class DisputeController {

    private final DisputeService service;
    private final Records records;
    private final ScopeResolver scopeResolver;
    private final RecordAccess recordAccess;
    private final TableQueryExecutor queryExecutor;
    private final PendingChangeViews pendingChangeViews;
    private final PendingChangeScope pendingChangeScope;
    private final AssigneeStore assigneeStore;
    private final AssigneeService assigneeService;
    private final EmailTargets emailTargets;
    private final CurrentUser currentUser;

    private TableSchema schema() {
        return TableSchemas.DISPUTES.visibleTo(currentUser.isCustomer());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.DISPUTE_VIEW + "')")
    public PageResponse<DisputeDtos.DisputeDto> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) DisputeTargetType targetType,
            @RequestParam(required = false) Long targetId) {
        TableSchema schema = schema();
        TableQuery query = TableQuery.parse(schema, page, size, sort,
                withContext(FilterParams.from(request), customerId, targetType, targetId));
        ScopeResolver.Scope scope = scopeResolver.forDisputes();
        var result = queryExecutor.run(Dispute.class, schema, query,
                scope.predicates(), List.of("region"));
        Set<Long> pending = pendingChangeScope.pendingOn(DisputeService.ENTITY,
                result.content().stream().map(Dispute::getId).toList());
        return PageResponse.of(service.toDtos(result.content(), recordAccess).stream()
                        .map(d -> d.withPendingApproval(pending.contains(d.id())))
                        .toList(),
                query, result.total(), scope);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.DISPUTE_VIEW + "')")
    public DisputeDtos.DisputeDto get(@PathVariable Long id) {
        return detail(service.get(id));
    }

    /**
     * Replaces the dispute's assignees (§6.13): DISPUTE_MANAGE in its region. Someone named who could not open the
     * dispute is a 400 against {@code assignees} and nothing changes; those newly assigned are told.
     */
    @PutMapping("/{id}/assignees")
    @PreAuthorize("hasAuthority('" + Privileges.DISPUTE_MANAGE + "')")
    public DisputeDtos.DisputeDto assignees(@PathVariable Long id, @RequestBody DisputeDtos.AssigneesRequest req) {
        Dispute d = service.get(id);
        Long regionId = Region.idOf(d.getRegion());
        currentUser.requireIn(Privileges.DISPUTE_MANAGE, regionId);
        List<EmailToken> tokens = assigneeStore.validate(EmailEntityType.DISPUTE, req == null ? null
                : req.assignees(), DisputeService.assigneeRoles(d.getTargetType()));
        assigneeService.requireVisible(AssignedTo.DISPUTE, tokens, EmailEntityType.DISPUTE, id, regionId);
        List<EmailToken> added = service.setAssignees(id, tokens);
        assigneeService.notifyAssignedTo(AssignedTo.DISPUTE, id, added, currentUser.idOrNull());
        return detail(service.get(id));
    }

    /** The dispute's page: for staff, its assignees and the people each means now (as of a date, then). */
    private DisputeDtos.DisputeDto detail(Dispute d) {
        DisputeDtos.DisputeDto dto = service.toDto(d, recordAccess);
        if (currentUser.isCustomer()) return dto;
        List<EmailToken> tokens = assigneeStore.list(AssignedTo.DISPUTE, d.getId());
        AsOfPoint point = AsOf.current();
        List<AssigneeDtos.AssigneeDto> people = point != null
                ? assigneeService.describeAt(tokens, EmailEntityType.DISPUTE, d.getId(), d.getCustomerId(), point)
                : assigneeService.describe(AssignedTo.DISPUTE, tokens, EmailEntityType.DISPUTE,
                        emailTargets.load(EmailEntityType.DISPUTE, d.getId()));
        return dto.withAssignees(people);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.DISPUTE_CREATE + "')")
    public DisputeDtos.DisputeDto create(@Valid @RequestBody DisputeDtos.CreateDisputeRequest req) {
        return service.toDto(service.open(req), recordAccess);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('" + Privileges.DISPUTE_MANAGE + "')")
    public ResponseEntity<Object> approve(@PathVariable Long id,
                                          @Valid @RequestBody(required = false) DisputeDtos.ResolveDisputeRequest req) {
        // A resolution above the region's limit: 202, and the dispute stays pending (§6.6, §5.11.8).
        return Outcomes.respond(service.approveOrHold(id, req), d -> service.toDto(d, recordAccess),
                pendingChangeViews);
    }

    @PostMapping("/{id}/deny")
    @PreAuthorize("hasAuthority('" + Privileges.DISPUTE_MANAGE + "')")
    public DisputeDtos.DisputeDto deny(@PathVariable Long id,
                                       @Valid @RequestBody(required = false) DisputeDtos.ResolveDisputeRequest req) {
        return service.toDto(service.deny(id, req), recordAccess);
    }

    @PostMapping("/export")
    @AsOfAware
    @PreAuthorize("hasAuthority('" + Privileges.EXPORT_DATA + "') and hasAuthority('" + Privileges.DISPUTE_VIEW + "')")
    public ResponseEntity<String> export(@RequestBody BulkDtos.BulkRequest req) {
        TableSchema schema = schema();
        TableQuery query = TableQuery.parseUnpaged(schema, req.sort(), req.filters());
        List<Long> permitted = queryExecutor.ids(Dispute.class, schema, query,
                scopeResolver.forDisputes().predicates(), TableQueryExecutor.BULK_ID_LIMIT);
        List<Long> ids = req.allMatching() ? permitted
                : (req.ids() == null ? List.<Long>of()
                        : req.ids().stream().filter(permitted::contains).toList());
        if (ids.isEmpty() && !req.allMatching()) {
            throw new BadRequestException("Provide ids or set selectAllMatchingFilter");
        }
        String csv = Csv.of(
                List.of("Id", "Customer", "Target", "Target id", "Status", "Opened", "Resolved", "Reason"),
                service.toDtos(records.findAll(Dispute.class, ids), null).stream().map(d -> List.<Object>of(
                        d.id(), d.customerName() == null ? "" : d.customerName(),
                        d.targetType(), d.targetId(), d.status(), d.createdAt(),
                        d.resolvedAt() == null ? "" : d.resolvedAt(), d.reason())).toList());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"disputes.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    private List<String> withContext(List<String> chips, Long customerId,
                                     DisputeTargetType targetType, Long targetId) {
        if (customerId == null && targetType == null && targetId == null) return chips;
        List<String> merged = new ArrayList<>(chips == null ? List.of() : chips);
        if (customerId != null) merged.add("customerId:eq:" + customerId);
        if (targetType != null) merged.add("targetType:eq:" + targetType.name());
        if (targetId != null) merged.add("targetId:eq:" + targetId);
        return merged;
    }
}
