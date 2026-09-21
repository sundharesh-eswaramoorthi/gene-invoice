package com.geneinvoice.dispute;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.Csv;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
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

@RestController
@RequestMapping("/api/disputes")
@RequiredArgsConstructor
public class DisputeController {

    private final DisputeService service;
    private final DisputeRepository repository;
    private final ScopeResolver scopeResolver;
    private final TableQueryExecutor queryExecutor;

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
        TableQuery query = TableQuery.parse(TableSchemas.DISPUTES, page, size, sort,
                withContext(FilterParams.from(request), customerId, targetType, targetId));
        ScopeResolver.Scope scope = scopeResolver.forDisputes();
        var result = queryExecutor.run(Dispute.class, TableSchemas.DISPUTES, query,
                scope.predicates(), List.of());
        return PageResponse.of(service.toDtos(result.content()),
                query, result.total(), scope.lockedFilters());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.DISPUTE_VIEW + "')")
    public DisputeDtos.DisputeDto get(@PathVariable Long id) {
        return service.toDto(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.DISPUTE_CREATE + "')")
    public DisputeDtos.DisputeDto create(@Valid @RequestBody DisputeDtos.CreateDisputeRequest req) {
        return service.toDto(service.open(req));
    }

    /**
     * Names who is answerable for a dispute. It is a staff-side setter rather than a field on the
     * form that opens one, because disputes are opened from the customer's side and a customer
     * never sees staff to pick them (AC-A8, A1). PATCH because it changes one thing about a dispute
     * and leaves the rest of it alone.
     */
    @PatchMapping("/{id}/assignees")
    @PreAuthorize("hasAuthority('" + Privileges.DISPUTE_MANAGE + "')")
    public DisputeDtos.DisputeDto setAssignees(@PathVariable Long id,
                                               @Valid @RequestBody DisputeDtos.SetAssigneesRequest req) {
        return service.toDto(service.setAssignees(id, req.assignees()));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('" + Privileges.DISPUTE_MANAGE + "')")
    public DisputeDtos.DisputeDto approve(@PathVariable Long id,
                                          @Valid @RequestBody(required = false) DisputeDtos.ResolveDisputeRequest req) {
        return service.toDto(service.approve(id, req));
    }

    @PostMapping("/{id}/deny")
    @PreAuthorize("hasAuthority('" + Privileges.DISPUTE_MANAGE + "')")
    public DisputeDtos.DisputeDto deny(@PathVariable Long id,
                                       @Valid @RequestBody(required = false) DisputeDtos.ResolveDisputeRequest req) {
        return service.toDto(service.deny(id, req));
    }

    @PostMapping("/export")
    @PreAuthorize("hasAuthority('" + Privileges.EXPORT_DATA + "') and hasAuthority('" + Privileges.DISPUTE_VIEW + "')")
    public ResponseEntity<String> export(@RequestBody BulkDtos.BulkRequest req) {
        TableQuery query = TableQuery.parseUnpaged(TableSchemas.DISPUTES, req.sort(), req.filters());
        List<Long> permitted = queryExecutor.ids(Dispute.class, TableSchemas.DISPUTES, query,
                scopeResolver.forDisputes().predicates(), TableQueryExecutor.BULK_ID_LIMIT);
        List<Long> ids = req.allMatching() ? permitted
                : (req.ids() == null ? List.<Long>of()
                        : req.ids().stream().filter(permitted::contains).toList());
        if (ids.isEmpty() && !req.allMatching()) {
            throw new BadRequestException("Provide ids or set selectAllMatchingFilter");
        }
        String csv = Csv.of(
                List.of("Id", "Customer", "Target", "Target id", "Status", "Opened", "Resolved", "Reason"),
                service.toDtos(repository.findAllById(ids)).stream().map(d -> List.<Object>of(
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
