package com.geneinvoice.approval;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.Csv;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.common.scope.AsOfAware;
import com.geneinvoice.common.scope.AsOfIgnored;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.promise.PaymentPromiseService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Pending changes and their decisions (§6.11). Every read answers as of a date from the changes' own intervals and
 * honours {@code regionIds}; every decision is a write, refused while a past date is viewed. An approval that could
 * not be applied still answers 200, with the change FAILED and the reason. Customer logins have no list; they see
 * what waits on their own records only.
 */
@RestController
@AsOfAware
@RequestMapping("/api/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private static final List<String> RECORD_TYPES = List.of("INVOICE", "PAYMENT", "PROMISE", "DISPUTE", "CUSTOMER");

    private final ApprovalService service;
    private final CurrentUser currentUser;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final PaymentPromiseService promiseService;
    private final DisputeService disputeService;
    private final CustomerService customerService;

    public record DecisionRequest(String note) {}

    public record OperationDto(ApprovalOperation operation, String label, String entityType, String measure) {}

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public PageResponse<PendingChangeViews.Brief> list(@RequestParam(required = false) Integer page,
                                                       @RequestParam(required = false) Integer size,
                                                       @RequestParam(required = false) String sort,
                                                       @RequestParam(required = false) String view,
                                                       HttpServletRequest request) {
        return service.page(TableQuery.parse(TableSchemas.APPROVALS, page, size, sort, FilterParams.from(request)),
                PendingChangeScope.View.parse(view));
    }

    @GetMapping("/summary")
    @PreAuthorize("isAuthenticated()")
    public ApprovalService.Summary summary(HttpServletRequest request) {
        return service.summary(TableQuery.parseUnpaged(TableSchemas.APPROVALS, null, FilterParams.from(request)));
    }

    @GetMapping("/awaiting-me/count")
    @PreAuthorize("isAuthenticated()")
    public ApprovalService.Count awaitingMeCount() {
        return service.awaitingMeCount();
    }

    /**
     * What waits on one record, read through the record's own scoped read first (404, or the customer-login 403 it
     * already answers). A customer's waiting creates are staff-only.
     */
    @GetMapping("/for-record")
    @PreAuthorize("isAuthenticated()")
    public List<PendingChangeViews.Brief> forRecord(@RequestParam String entityType, @RequestParam Long entityId) {
        String type = entityType.trim().toUpperCase(Locale.ROOT);
        if (!RECORD_TYPES.contains(type)) {
            throw new BadRequestException("Unknown entity type: " + entityType
                    + " (expected one of " + RECORD_TYPES + ")");
        }
        if ("CUSTOMER".equals(type) && currentUser.isCustomer()) throw new AccessDeniedException("Not allowed");
        switch (type) {
            case "INVOICE" -> invoiceService.get(entityId);
            case "PAYMENT" -> paymentService.get(entityId);
            case "PROMISE" -> promiseService.get(entityId);
            case "DISPUTE" -> disputeService.get(entityId);
            default -> customerService.get(entityId);
        }
        return service.forRecord(type, entityId);
    }

    /** The kinds of change that can wait for approval, for the list's filters and the thresholds screen. */
    @GetMapping("/operations")
    @AsOfIgnored
    @PreAuthorize("isAuthenticated()")
    public List<OperationDto> operations() {
        return Arrays.stream(ApprovalOperation.values())
                .map(op -> new OperationDto(op, op.label(), op.entityType(), op.measureLabel()))
                .toList();
    }

    @PostMapping("/export")
    @AsOfAware
    @PreAuthorize("hasAuthority('" + Privileges.EXPORT_DATA + "')")
    public ResponseEntity<String> export(@RequestBody BulkDtos.BulkRequest req,
                                         @RequestParam(required = false) String view) {
        Object asked = view != null ? view : req.params() == null ? null : req.params().get("view");
        TableQuery query = TableQuery.parseUnpaged(TableSchemas.APPROVALS, req.sort(), req.filters());
        List<Long> only = null;
        if (!req.allMatching()) {
            if (req.ids() == null || req.ids().isEmpty()) {
                throw new BadRequestException("Provide ids or set selectAllMatchingFilter");
            }
            only = req.ids();
        }
        List<PendingChangeViews.Brief> rows = service.briefs(query,
                PendingChangeScope.View.parse(asked == null ? null : asked.toString()), only);
        String csv = Csv.of(
                List.of("Id", "Status", "Change", "Record", "Customer", "Region", "Amount", "Limit", "Summary",
                        "Requested by", "Requested", "Decided"),
                rows.stream().map(b -> List.<Object>of(
                        b.id(), b.statusAt() != null ? b.statusAt() : b.status(), b.operationLabel(),
                        b.entityLabel() == null ? "" : b.entityLabel(),
                        b.customerName() == null ? "" : b.customerName(),
                        b.regionName() == null ? "" : b.regionName(), b.amount(), b.threshold(), b.summary(),
                        b.requestedBy() == null ? "" : b.requestedBy().name(), b.requestedAt(),
                        b.decidedAt() == null ? "" : b.decidedAt())).toList());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"approvals.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public PendingChangeViews.Detail get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('" + Privileges.CHANGE_APPROVE + "')")
    public PendingChangeViews.Detail approve(@PathVariable long id,
                                             @RequestBody(required = false) DecisionRequest req) {
        return service.approve(id, req == null ? null : req.note());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('" + Privileges.CHANGE_APPROVE + "')")
    public PendingChangeViews.Detail reject(@PathVariable long id,
                                            @RequestBody(required = false) DecisionRequest req) {
        return service.reject(id, req == null ? null : req.note());
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("isAuthenticated()")
    public PendingChangeViews.Detail withdraw(@PathVariable long id) {
        return service.withdraw(id);
    }
}
