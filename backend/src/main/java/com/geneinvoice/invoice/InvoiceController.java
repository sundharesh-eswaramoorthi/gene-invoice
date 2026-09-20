package com.geneinvoice.invoice;

import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.common.bulk.Csv;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import com.geneinvoice.user.UserRepository;
import com.geneinvoice.auth.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService service;
    private final ScopeResolver scopeResolver;
    private final BulkExecutor bulkExecutor;
    private final CurrentUser currentUser;
    private final UserRepository userRepository;

    /** Customer logins cannot filter or sort on the Sales POC columns (AC-A8). */
    private TableSchema schema() {
        return TableSchemas.INVOICES.visibleTo(currentUser.isCustomer());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_VIEW + "')")
    public PageResponse<InvoiceDtos.InvoiceSummary> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request,
            @RequestParam(required = false) Long customerId) {
        return service.page(TableQuery.parse(schema(), page, size, sort,
                withCustomer(FilterParams.from(request), customerId)));
    }

    /** Tiles over the whole filtered set — never the current page (AC-E1). */
    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_VIEW + "')")
    public InvoiceDtos.InvoiceSummaryTiles summary(
            HttpServletRequest request,
            @RequestParam(required = false) Long customerId) {
        return service.tiles(TableQuery.parseUnpaged(schema(), null,
                withCustomer(FilterParams.from(request), customerId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_VIEW + "')")
    public InvoiceDtos.InvoiceDto get(@PathVariable Long id) {
        return InvoiceDtos.InvoiceDto.from(service.get(id), scopeResolver.canSeePoc());
    }

    /**
     * The due date the customer's terms give, so the form can fill it in the moment a customer is
     * picked (US-A2). It is only of use to someone raising an invoice, so it needs INVOICE_MANAGE.
     */
    @GetMapping("/due-date-preview")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_MANAGE + "')")
    public InvoiceDtos.DueDatePreview dueDatePreview(
            @RequestParam Long customerId,
            @RequestParam(required = false) String invoiceDate) {
        return service.previewDueDate(customerId, InvoiceDates.parse(invoiceDate));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_MANAGE + "')")
    public InvoiceDtos.InvoiceDto create(@Valid @RequestBody InvoiceDtos.CreateInvoiceRequest req) {
        return InvoiceDtos.InvoiceDto.from(service.create(req), scopeResolver.canSeePoc());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_MANAGE + "')")
    public InvoiceDtos.InvoiceDto update(@PathVariable Long id,
                                         @Valid @RequestBody InvoiceDtos.UpdateInvoiceRequest req) {
        // Whether the POC may change is decided by the service, which knows the current one.
        return InvoiceDtos.InvoiceDto.from(service.update(id, req), scopeResolver.canSeePoc());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_MANAGE + "')")
    public InvoiceDtos.InvoiceDto cancel(@PathVariable Long id) {
        return InvoiceDtos.InvoiceDto.from(service.cancel(id), scopeResolver.canSeePoc());
    }

    // ---- bulk & export ---------------------------------------------------------

    public static final List<String> BULK_ACTIONS = List.of("CANCEL", "REASSIGN_SALES_POC");

    @PostMapping("/bulk")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_MANAGE + "')")
    public BulkDtos.BulkResult bulk(@Valid @RequestBody BulkDtos.BulkRequest req) {
        List<Long> ids = resolveIds(req);
        boolean truncated = req.allMatching() && ids.size() >= TableQueryExecutor.BULK_ID_LIMIT;

        // A row the action does not apply to — already cancelled, or holding a payment — is one
        // that did not qualify, not one that went wrong, and is reported as skipped like every
        // other list's does (TBL-05).
        return switch (req.action()) {
            case "CANCEL" -> bulkExecutor.run(req, ids, truncated,
                    BulkExecutor.eligibility(service::cancel));
            case "REASSIGN_SALES_POC" -> {
                Long userId = req.longParam("userId");
                if (userId == null) throw new BadRequestException("REASSIGN_SALES_POC requires params.userId");
                if (!currentUser.canAssignPoc(userRepository)) {
                    throw new BadRequestException("You may not change the Sales POC");
                }
                yield bulkExecutor.run(req, ids, truncated,
                        BulkExecutor.eligibility(id -> service.reassignSalesPoc(id, userId)));
            }
            default -> throw new BadRequestException(
                    "Unknown bulk action: " + req.action() + " (expected one of " + BULK_ACTIONS + ")");
        };
    }

    @PostMapping("/export")
    @PreAuthorize("hasAuthority('" + Privileges.EXPORT_DATA + "') and hasAuthority('" + Privileges.INVOICE_VIEW + "')")
    public ResponseEntity<String> export(@RequestBody BulkDtos.BulkRequest req) {
        boolean poc = scopeResolver.canSeePoc();
        List<Long> ids = resolveIds(req);
        List<Invoice> invoices = service.allMatching(
                        TableQuery.parseUnpaged(schema(), req.sort(), req.filters())).stream()
                .filter(i -> ids.contains(i.getId()))
                .toList();

        // Overdue is read from the clock at export time, exactly as the list shows it (D3).
        LocalDate today = InvoiceDates.today();
        List<String> headers = new ArrayList<>(List.of(
                "Invoice #", "Customer", "Date", "Due date", "Total", "Paid", "Balance",
                "Status", "Overdue"));
        if (poc) headers.add("Sales POC");
        List<List<Object>> rows = invoices.stream().map(i -> {
            List<Object> row = new ArrayList<>(List.of(
                    i.getInvoiceNumber(), i.getCustomer().getName(), i.getInvoiceDate(),
                    i.getDueDate(), i.getTotal(), i.getPaidAmount(), i.getBalance(),
                    i.getStatus(), i.isOverdue(today)));
            if (poc) row.add(i.getSalesPoc() == null ? "" : i.getSalesPoc().getUsername());
            return row;
        }).toList();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"invoices.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(Csv.of(headers, rows));
    }

    /**
     * Explicit ids, or every id matching the FilterParams.from(request). Either way the ids are re-resolved through
     * the caller's scope, so a bulk parameter can never widen what they may touch (AC-D6, AC-D10).
     */
    private List<Long> resolveIds(BulkDtos.BulkRequest req) {
        TableQuery query = TableQuery.parseUnpaged(schema(), req.sort(), req.filters());
        List<Long> permitted = service.idsMatching(query, TableQueryExecutor.BULK_ID_LIMIT);
        if (req.allMatching()) return permitted;
        if (req.ids() == null || req.ids().isEmpty()) {
            throw new BadRequestException("Provide ids or set selectAllMatchingFilter");
        }
        return req.ids().stream().filter(permitted::contains).toList();
    }

    private List<String> withCustomer(List<String> chips, Long customerId) {
        if (customerId == null) return chips;
        List<String> merged = new ArrayList<>(chips == null ? List.of() : chips);
        merged.add("customerId:eq:" + customerId);
        return merged;
    }

    /** Which of the three POC kinds this caller may be offered as, for pre-selecting a form. */
    @GetMapping("/assignable-check")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_VIEW + "')")
    public java.util.Map<String, Boolean> assignableCheck() {
        return java.util.Map.of("sales", scopeResolver.isAssignableAs(PocType.SALES));
    }
}
