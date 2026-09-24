package com.geneinvoice.promise;

import com.geneinvoice.auth.CurrentUser;
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
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.user.UserRepository;
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
    private final UserRepository userRepository;

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
            @RequestParam(required = false) Long invoiceId,
            @RequestParam(required = false) Long paymentId) {
        return service.page(TableQuery.parse(schema(), page, size, sort,
                withContext(FilterParams.from(request), customerId, invoiceId, paymentId)));
    }

    @PostMapping("/recompute")
    // Keyed on the privilege that already means "may take charge of promise status by hand",
    // rather than on a role name: this was the one endpoint in the app that a tailored
    // administrator role carrying every privilege still could not reach (AUTH-05).
    @PreAuthorize("hasAuthority('" + Privileges.PROMISE_OVERRIDE + "')")
    public List<PaymentPromiseService.RecomputeChange> recompute(
            @RequestParam(defaultValue = "false") boolean apply) {
        return service.recomputeAll(apply);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('" + Privileges.PROMISE_VIEW + "')")
    public PromiseDtos.PromiseSummaryDto summary(
            HttpServletRequest request,
            @RequestParam(required = false) Long customerId) {
        return service.tiles(TableQuery.parseUnpaged(schema(), null,
                withContext(FilterParams.from(request), customerId, null, null)));
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

    public static final List<String> BULK_ACTIONS = List.of("CANCEL", "REASSIGN_COLLECTION_POC");

    @PostMapping("/bulk")
    @PreAuthorize("hasAuthority('" + Privileges.PROMISE_MANAGE + "')")
    public BulkDtos.BulkResult bulk(@Valid @RequestBody BulkDtos.BulkRequest req) {
        List<Long> ids = resolveIds(req);
        boolean truncated = req.allMatching() && ids.size() >= TableQueryExecutor.BULK_ID_LIMIT;
        // "Already cancelled" is a row that did not qualify, not one that went wrong, and is
        // reported as skipped like every other list's is (TBL-05).
        return switch (req.action()) {
            case "CANCEL" -> bulkExecutor.run(req, ids, truncated,
                    BulkExecutor.eligibility(id -> service.cancel(id, req.stringParam("reason"))));
            case "REASSIGN_COLLECTION_POC" -> {
                Long userId = req.longParam("userId");
                if (userId == null) {
                    throw new BadRequestException("REASSIGN_COLLECTION_POC requires params.userId");
                }
                // The privilege is checked once for the whole request, as the payments list does:
                // it is the caller that is or is not allowed to move a Collection POC, not the
                // individual row (PPD-05).
                if (!currentUser.canAssignPoc(userRepository)) {
                    throw new BadRequestException("You may not change the Collection POC");
                }
                yield bulkExecutor.run(req, ids, truncated,
                        BulkExecutor.eligibility(id -> service.reassignCollectionPoc(id, userId)));
            }
            default -> throw new BadRequestException(
                    "Unknown bulk action: " + req.action() + " (expected one of " + BULK_ACTIONS + ")");
        };
    }

    @PostMapping("/export")
    @PreAuthorize("hasAuthority('" + Privileges.EXPORT_DATA + "') and hasAuthority('" + Privileges.PROMISE_VIEW + "')")
    public ResponseEntity<String> export(@RequestBody BulkDtos.BulkRequest req) {
        List<Long> ids = resolveIds(req);
        List<? extends PromiseView> found = service.allMatching(
                        TableQuery.parseUnpaged(schema(), req.sort(), req.filters())).stream()
                .filter(p -> ids.contains(p.getId()))
                .toList();
        // Through the page's own mapper, which batches the approval flags and — under ?asOf — the
        // two link collections the mirror rows cannot walk to for themselves (B2, B3).
        List<PromiseDtos.PromiseDto> rows = service.toDtos(found);

        List<String> headers = new ArrayList<>(List.of(
                "Id", "Customer", "Promised amount", "Promised by", "Status", "Fulfilled",
                "Remaining", "Invoices", "Collection POC", "Notes", "Awaiting approval"));
        List<List<Object>> body = rows.stream().map(p -> List.<Object>of(
                p.id(), p.customerName(), p.amount(), p.promisedDate(), p.status(),
                p.fulfilledAmount(), p.remainingAmount(),
                p.invoices().stream().map(PromiseDtos.PromiseInvoiceDto::invoiceNumber)
                        .reduce((a, b) -> a + "; " + b).orElse(""),
                p.collectionPoc() == null ? "" : p.collectionPoc().username(),
                p.notes() == null ? "" : p.notes(),
                // Read straight off the DTO the batch above already filled (B2).
                Boolean.TRUE.equals(p.approvalPending()))).toList();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + AsOfCsv.filename("payment-promises") + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                // A downloaded file outlives the banner that framed it, so the caveat travels
                // inside the file: one leading cell, empty on a live export (B3).
                .body(AsOfCsv.caveat() + Csv.of(headers, body));
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

    private List<String> withContext(List<String> chips, Long customerId, Long invoiceId, Long paymentId) {
        if (customerId == null && invoiceId == null && paymentId == null) return chips;
        List<String> merged = new ArrayList<>(chips == null ? List.of() : chips);
        if (customerId != null) merged.add("customerId:eq:" + customerId);
        if (invoiceId != null) merged.add("invoiceId:eq:" + invoiceId);
        if (paymentId != null) merged.add("paymentId:eq:" + paymentId);
        return merged;
    }
}
