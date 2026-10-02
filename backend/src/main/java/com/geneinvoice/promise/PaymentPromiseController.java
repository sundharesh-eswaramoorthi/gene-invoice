package com.geneinvoice.promise;

import com.geneinvoice.approval.Outcome;
import com.geneinvoice.approval.PendingChangeViews;
import com.geneinvoice.assignee.AssignedTo;
import com.geneinvoice.assignee.AssigneeDtos;
import com.geneinvoice.assignee.AssigneeService;
import com.geneinvoice.assignee.AssigneeStore;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.Outcomes;
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
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfAware;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
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

@RestController
@AsOfAware
@RequestMapping("/api/promises")
@RequiredArgsConstructor
public class PaymentPromiseController {

    private final PaymentPromiseService service;
    private final BulkExecutor bulkExecutor;
    private final CurrentUser currentUser;
    private final PendingChangeViews pendingChangeViews;
    private final AssigneeStore assigneeStore;
    private final AssigneeService assigneeService;
    private final EmailTargets emailTargets;

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
        return detail(service.dto(id));
    }

    /**
     * Makes a promise. Co-assignees named with it must be able to see the customer, and are told once it is made;
     * the promise page assigns them afterwards as a rule (§5.10.12). Above the region's limit: 202 and the pending
     * change (§6.6), its co-assignees kept with it.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.PROMISE_MANAGE + "')")
    public ResponseEntity<Object> create(@Valid @RequestBody PromiseDtos.CreatePromiseRequest req) {
        if (req.assignees() != null && !req.assignees().isEmpty()) {
            List<EmailToken> tokens = assigneeStore.validate(EmailEntityType.PROMISE, req.assignees(),
                    EmailTargets.offeredRoles(EmailEntityType.PROMISE));
            Long regionId = emailTargets.load(EmailEntityType.CUSTOMER, req.customerId()).regionId();
            assigneeService.requireVisible(AssignedTo.PROMISE, tokens, EmailEntityType.CUSTOMER, req.customerId(),
                    regionId);
        }
        Outcome<PromiseDtos.PromiseDto> out = service.createOrHold(req);
        if (out instanceof Outcome.Applied<PromiseDtos.PromiseDto> applied) {
            Long id = applied.value().id();
            assigneeService.notifyAssignedTo(AssignedTo.PROMISE, id, assigneeStore.list(AssignedTo.PROMISE, id),
                    currentUser.idOrNull());
        }
        return Outcomes.respond(out, dto -> dto, pendingChangeViews);
    }

    /**
     * Replaces the promise's co-assignees (§6.13): PROMISE_MANAGE in its region. Someone named who could not open
     * the promise is a 400 against {@code assignees} and nothing changes; those newly assigned are told.
     */
    @PutMapping("/{id}/assignees")
    @PreAuthorize("hasAuthority('" + Privileges.PROMISE_MANAGE + "')")
    public PromiseDtos.PromiseDto assignees(@PathVariable Long id, @RequestBody PromiseDtos.AssigneesRequest req) {
        Long regionId = service.dto(id).regionId();
        currentUser.requireIn(Privileges.PROMISE_MANAGE, regionId);
        List<EmailToken> tokens = assigneeStore.validate(EmailEntityType.PROMISE, req == null ? null
                : req.assignees(), EmailTargets.offeredRoles(EmailEntityType.PROMISE));
        assigneeService.requireVisible(AssignedTo.PROMISE, tokens, EmailEntityType.PROMISE, id, regionId);
        List<EmailToken> added = service.setAssignees(id, tokens);
        assigneeService.notifyAssignedTo(AssignedTo.PROMISE, id, added, currentUser.idOrNull());
        return detail(service.dto(id));
    }

    /** The promise's page: for staff, its co-assignees and the people each means now (as of a date, then). */
    private PromiseDtos.PromiseDto detail(PromiseDtos.PromiseDto dto) {
        if (currentUser.isCustomer()) return dto;
        List<EmailToken> tokens = assigneeStore.list(AssignedTo.PROMISE, dto.id());
        AsOfPoint point = AsOf.current();
        List<AssigneeDtos.AssigneeDto> people = point != null
                ? assigneeService.describeAt(tokens, EmailEntityType.PROMISE, dto.id(), dto.customerId(), point)
                : assigneeService.describe(AssignedTo.PROMISE, tokens, EmailEntityType.PROMISE,
                        emailTargets.load(EmailEntityType.PROMISE, dto.id()));
        return dto.withAssignees(people);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.PROMISE_MANAGE + "')")
    public ResponseEntity<Object> update(@PathVariable Long id,
                                         @Valid @RequestBody PromiseDtos.UpdatePromiseRequest req) {
        return Outcomes.respond(service.updateOrHold(id, req), dto -> dto, pendingChangeViews);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('" + Privileges.PROMISE_MANAGE + "')")
    public PromiseDtos.PromiseDto cancel(@PathVariable Long id,
                                         @Valid @RequestBody(required = false) PromiseDtos.CancelPromiseRequest req) {
        return service.cancel(id, req == null ? null : req.reason());
    }

    @PostMapping("/{id}/override")
    @PreAuthorize("hasAuthority('" + Privileges.PROMISE_OVERRIDE + "')")
    public ResponseEntity<Object> override(@PathVariable Long id,
                                           @Valid @RequestBody PromiseDtos.OverrideStatusRequest req) {
        return Outcomes.respond(service.overrideOrHold(id, req.status(), req.reason()), dto -> dto,
                pendingChangeViews);
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
        // Rows the caller sees but may not change in their region drop out as not reachable.
        List<Long> ids = "REASSIGN_COLLECTION_POC".equals(req.action())
                ? resolveIds(req, ScopeResolver.Access.MANAGE, Privileges.POC_ASSIGN)
                : resolveIds(req, ScopeResolver.Access.MANAGE);
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
                if (!currentUser.canAssignPocAnywhere()) {
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
    @AsOfAware
    @PreAuthorize("hasAuthority('" + Privileges.EXPORT_DATA + "') and hasAuthority('" + Privileges.PROMISE_VIEW + "')")
    public ResponseEntity<String> export(@RequestBody BulkDtos.BulkRequest req) {
        List<Long> ids = resolveIds(req, ScopeResolver.Access.VIEW);
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

    private List<Long> resolveIds(BulkDtos.BulkRequest req, ScopeResolver.Access access, String... alsoRequired) {
        TableQuery query = TableQuery.parseUnpaged(schema(), req.sort(), req.filters());
        List<Long> permitted = service.idsMatching(query, TableQueryExecutor.BULK_ID_LIMIT, access, alsoRequired);
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
