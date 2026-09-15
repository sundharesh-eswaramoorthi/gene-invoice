package com.geneinvoice.promise;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.common.bulk.Csv;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
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
@RequestMapping("/api/promises")
@RequiredArgsConstructor
public class PaymentPromiseController {

    private final PaymentPromiseService service;
    private final BulkExecutor bulkExecutor;
    private final CurrentUser currentUser;

    /** Customer logins cannot filter or sort on the Collection POC columns (AC-A8). */
    private TableSchema schema() {
        return TableSchemas.PROMISES.visibleTo(currentUser.isCustomer());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.PROMISE_VIEW + "')")
    public PageResponse<PromiseDtos.PromiseDto> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long invoiceId) {
        return service.page(TableQuery.parse(schema(), page, size, sort,
                withContext(FilterParams.from(request), customerId, invoiceId)));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('" + Privileges.PROMISE_VIEW + "')")
    public PromiseDtos.PromiseSummaryDto summary(
            HttpServletRequest request,
            @RequestParam(required = false) Long customerId) {
        return service.tiles(TableQuery.parseUnpaged(schema(), null,
                withContext(FilterParams.from(request), customerId, null)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.PROMISE_VIEW + "')")
    public PromiseDtos.PromiseDto get(@PathVariable Long id) {
        return service.dto(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.PROMISE_MANAGE + "')")
    public PromiseDtos.PromiseDto create(@Valid @RequestBody PromiseDtos.CreatePromiseRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.PROMISE_MANAGE + "')")
    public PromiseDtos.PromiseDto update(@PathVariable Long id,
                                         @Valid @RequestBody PromiseDtos.UpdatePromiseRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('" + Privileges.PROMISE_MANAGE + "')")
    public PromiseDtos.PromiseDto cancel(@PathVariable Long id,
                                         @Valid @RequestBody(required = false) PromiseDtos.CancelPromiseRequest req) {
        return service.cancel(id, req == null ? null : req.reason());
    }

    @PostMapping("/{id}/override")
    @PreAuthorize("hasAuthority('" + Privileges.PROMISE_OVERRIDE + "')")
    public PromiseDtos.PromiseDto override(@PathVariable Long id,
                                           @Valid @RequestBody PromiseDtos.OverrideStatusRequest req) {
        return service.override(id, req.status(), req.reason());
    }

    @DeleteMapping("/{id}/override")
    @PreAuthorize("hasAuthority('" + Privileges.PROMISE_OVERRIDE + "')")
    public PromiseDtos.PromiseDto clearOverride(@PathVariable Long id) {
        return service.clearOverride(id);
    }

    // ---- bulk & export ---------------------------------------------------------

    public static final List<String> BULK_ACTIONS = List.of("CANCEL", "REASSIGN_COLLECTION_POC");

    @PostMapping("/bulk")
    @PreAuthorize("hasAuthority('" + Privileges.PROMISE_MANAGE + "')")
    public BulkDtos.BulkResult bulk(@Valid @RequestBody BulkDtos.BulkRequest req) {
        List<Long> ids = resolveIds(req);
        boolean truncated = req.allMatching() && ids.size() >= TableQueryExecutor.BULK_ID_LIMIT;
        return switch (req.action()) {
            case "CANCEL" -> bulkExecutor.run(req, ids, truncated,
                    id -> service.cancel(id, req.stringParam("reason")));
            case "REASSIGN_COLLECTION_POC" -> {
                Long userId = req.longParam("userId");
                if (userId == null) {
                    throw new BadRequestException("REASSIGN_COLLECTION_POC requires params.userId");
                }
                yield bulkExecutor.run(req, ids, truncated,
                        id -> service.reassignCollectionPoc(id, userId));
            }
            default -> throw new BadRequestException(
                    "Unknown bulk action: " + req.action() + " (expected one of " + BULK_ACTIONS + ")");
        };
    }

    @PostMapping("/export")
    @PreAuthorize("hasAuthority('" + Privileges.EXPORT_DATA + "') and hasAuthority('" + Privileges.PROMISE_VIEW + "')")
    public ResponseEntity<String> export(@RequestBody BulkDtos.BulkRequest req) {
        List<Long> ids = resolveIds(req);
        List<PromiseDtos.PromiseDto> rows = service.allMatching(
                        TableQuery.parseUnpaged(schema(), req.sort(), req.filters())).stream()
                .filter(p -> ids.contains(p.getId()))
                .map(service::toDto)
                .toList();

        List<String> headers = new ArrayList<>(List.of(
                "Id", "Customer", "Promised amount", "Promised by", "Status", "Fulfilled",
                "Remaining", "Invoices", "Collection POC", "Notes"));
        List<List<Object>> body = rows.stream().map(p -> List.<Object>of(
                p.id(), p.customerName(), p.amount(), p.promisedDate(), p.status(),
                p.fulfilledAmount(), p.remainingAmount(),
                p.invoices().stream().map(PromiseDtos.PromiseInvoiceDto::invoiceNumber)
                        .reduce((a, b) -> a + "; " + b).orElse(""),
                p.collectionPoc() == null ? "" : p.collectionPoc().username(),
                p.notes() == null ? "" : p.notes())).toList();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payment-promises.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(Csv.of(headers, body));
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

    /** Turns the convenience query params into ordinary FilterParams.from(request) chips, so scoping still applies. */
    private List<String> withContext(List<String> chips, Long customerId, Long invoiceId) {
        if (customerId == null && invoiceId == null) return chips;
        List<String> merged = new ArrayList<>(chips == null ? List.of() : chips);
        if (customerId != null) merged.add("customerId:eq:" + customerId);
        if (invoiceId != null) merged.add("invoiceId:eq:" + invoiceId);
        return merged;
    }
}
