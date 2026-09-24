package com.geneinvoice.dispute;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.asof.AsOfCsv;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.Csv;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
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
        // The query is parsed against the LIVE schema and answered by the service's source switch,
        // which is the shape every other list in this application has: the twin's column names,
        // filterability and operator sets are equal to the live schema's by construction, so the
        // requireFilterable messages are byte-identical and the executor re-checks anyway (B3).
        return service.page(TableQuery.parse(TableSchemas.DISPUTES, page, size, sort,
                withContext(FilterParams.from(request), customerId, targetType, targetId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.DISPUTE_VIEW + "')")
    public DisputeDtos.DisputeDto get(@PathVariable Long id) {
        // Every figure below is the LIVE one and the flag is the only thing a waiting change adds
        // (B2) — unless the reader asked as of a date, and then the dispute, the record it is
        // about and the flag are all that date's, and one that did not exist then is 404 rather
        // than 403 (B3, AUTH-08).
        return service.detail(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.DISPUTE_CREATE + "')")
    public DisputeDtos.DisputeDto create(@Valid @RequestBody DisputeDtos.CreateDisputeRequest req) {
        return service.toDto(service.open(req));
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
        List<Long> permitted = service.idsMatching(query, TableQueryExecutor.BULK_ID_LIMIT);
        List<Long> ids = req.allMatching() ? permitted
                : (req.ids() == null ? List.<Long>of()
                        : req.ids().stream().filter(permitted::contains).toList());
        if (ids.isEmpty() && !req.allMatching()) {
            throw new BadRequestException("Provide ids or set selectAllMatchingFilter");
        }
        // The ROWS the query matched, filtered to the ids this caller named — and no longer
        // repository.findAllById, which read the live table whatever date was asked for and would
        // have put today's disputes in a file the banner called January's (B3, B1).
        String csv = Csv.of(
                List.of("Id", "Customer", "Target", "Target id", "Status", "Opened", "Resolved", "Reason"),
                service.allMatching(query).stream()
                        .filter(d -> ids.contains(d.getId()))
                        .map(service::toDto).map(d -> List.<Object>of(
                        d.id(), d.customerName() == null ? "" : d.customerName(),
                        d.targetType(), d.targetId(), d.status(), d.createdAt(),
                        d.resolvedAt() == null ? "" : d.resolvedAt(), d.reason())).toList());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + AsOfCsv.filename("disputes") + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                // A downloaded file outlives the banner that framed it, so the caveat travels
                // inside the file: one leading cell, empty on a live export (B3).
                .body(AsOfCsv.caveat() + csv);
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
