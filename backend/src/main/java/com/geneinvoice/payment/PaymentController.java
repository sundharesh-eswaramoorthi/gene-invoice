package com.geneinvoice.payment;

import com.geneinvoice.approval.PendingChangeViews;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Outcomes;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.common.bulk.Csv;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.common.scope.AsOfAware;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.region.RecordAccess;
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@RestController
@AsOfAware
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final CustomerService customerService;
    private final CurrentUser currentUser;
    private final ScopeResolver scopeResolver;
    private final RecordAccess recordAccess;
    private final BulkExecutor bulkExecutor;
    private final PendingChangeViews pendingChangeViews;

    private TableSchema schema() {
        return TableSchemas.PAYMENTS.visibleTo(currentUser.isCustomer());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_VIEW + "')")
    public PageResponse<PaymentDtos.PaymentDto> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request,
            @RequestParam(required = false) Long customerId) {
        return paymentService.page(TableQuery.parse(schema(), page, size, sort,
                withCustomer(FilterParams.from(request), customerId)));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_VIEW + "')")
    public PaymentDtos.PaymentSummaryTiles summary(
            HttpServletRequest request,
            @RequestParam(required = false) Long customerId) {
        return paymentService.tiles(TableQuery.parseUnpaged(schema(), null,
                withCustomer(FilterParams.from(request), customerId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_VIEW + "')")
    public PaymentDtos.PaymentDto get(@PathVariable Long id) {
        return PaymentDtos.PaymentDto.from(paymentService.get(id), scopeResolver.canSeePoc(), recordAccess);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_MANAGE + "')")
    public ResponseEntity<Object> create(@Valid @RequestBody PaymentDtos.CreatePaymentRequest req) {
        // Above the region's limit: 202 and the pending change; no money moves until it is approved (§6.6).
        return Outcomes.respond(paymentService.recordOrHold(req),
                p -> PaymentDtos.PaymentDto.from(p, scopeResolver.canSeePoc(), recordAccess), pendingChangeViews);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_MANAGE + "')")
    public PaymentDtos.PaymentDto update(@PathVariable Long id,
                                         @Valid @RequestBody PaymentDtos.UpdatePaymentRequest req) {
        return PaymentDtos.PaymentDto.from(paymentService.update(id, req), scopeResolver.canSeePoc(), recordAccess);
    }

    public record CustomerCreditDto(Long customerId, String customerName, BigDecimal creditBalance) {}

    @GetMapping("/credits/{customerId}")
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_VIEW + "')")
    public CustomerCreditDto credit(@PathVariable Long customerId) {
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer != null && !callerCustomer.equals(customerId)) {
            throw new AccessDeniedException("Not allowed");
        }
        // A customer's name and credit balance are the customer's own, so this is gated exactly
        // where GET /api/customers/{id} is, by the same call: outside the caller's regions or book it
        // is a 404, not a lookup anyone holding PAYMENT_VIEW can walk the id space with (AUTH-02).
        Customer c = customerService.get(customerId);
        return new CustomerCreditDto(c.getId(), c.getName(), c.getCreditBalance());
    }

    public static final List<String> BULK_ACTIONS = List.of("REASSIGN_COLLECTION_POC");

    @PostMapping("/bulk")
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_MANAGE + "')")
    public BulkDtos.BulkResult bulk(@Valid @RequestBody BulkDtos.BulkRequest req) {
        // Rows the caller sees but may not change in their region drop out as not reachable.
        List<Long> ids = resolveIds(req, ScopeResolver.Access.MANAGE, Privileges.POC_ASSIGN);
        boolean truncated = req.allMatching() && ids.size() >= TableQueryExecutor.BULK_ID_LIMIT;
        if (!"REASSIGN_COLLECTION_POC".equals(req.action())) {
            throw new BadRequestException(
                    "Unknown bulk action: " + req.action() + " (expected one of " + BULK_ACTIONS + ")");
        }
        Long userId = req.longParam("userId");
        if (userId == null) throw new BadRequestException("REASSIGN_COLLECTION_POC requires params.userId");
        if (!currentUser.canAssignPocAnywhere()) {
            throw new BadRequestException("You may not change the Collection POC");
        }
        return bulkExecutor.run(req, ids, truncated,
                id -> paymentService.reassignCollectionPoc(id, userId));
    }

    @PostMapping("/export")
    @AsOfAware
    @PreAuthorize("hasAuthority('" + Privileges.EXPORT_DATA + "') and hasAuthority('" + Privileges.PAYMENT_VIEW + "')")
    public ResponseEntity<String> export(@RequestBody BulkDtos.BulkRequest req) {
        boolean poc = scopeResolver.canSeePoc();
        List<Long> ids = resolveIds(req, ScopeResolver.Access.VIEW);
        List<Payment> payments = paymentService.allMatching(
                        TableQuery.parseUnpaged(schema(), req.sort(), req.filters())).stream()
                .filter(p -> ids.contains(p.getId()))
                .toList();

        List<String> headers = new ArrayList<>(List.of(
                "Payment #", "Customer", "Paid at", "Amount", "Credit applied", "Method", "Status"));
        if (poc) headers.add("Collection POC");
        List<List<Object>> rows = payments.stream().map(p -> {
            List<Object> row = new ArrayList<>(List.of(
                    p.getId(), p.getCustomer().getName(), p.getPaidAt(), p.getAmount(),
                    p.getCreditApplied(), p.getMethod() == null ? "" : p.getMethod(), p.getStatus()));
            if (poc) row.add(p.getCollectionPoc() == null ? "" : p.getCollectionPoc().getUsername());
            return row;
        }).toList();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payments.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(Csv.of(headers, rows));
    }

    private List<Long> resolveIds(BulkDtos.BulkRequest req, ScopeResolver.Access access, String... alsoRequired) {
        TableQuery query = TableQuery.parseUnpaged(schema(), req.sort(), req.filters());
        List<Long> permitted = paymentService.idsMatching(query, TableQueryExecutor.BULK_ID_LIMIT, access,
                alsoRequired);
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
}
