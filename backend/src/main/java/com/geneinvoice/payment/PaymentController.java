package com.geneinvoice.payment;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.common.bulk.Csv;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import com.geneinvoice.user.UserRepository;
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
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final CustomerRepository customerRepository;
    private final CurrentUser currentUser;
    private final ScopeResolver scopeResolver;
    private final BulkExecutor bulkExecutor;
    private final UserRepository userRepository;

    /** Customer logins cannot filter or sort on the Collection POC columns (AC-A8). */
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
        Payment p = paymentService.get(id);
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer != null && !callerCustomer.equals(p.getCustomer().getId())) {
            throw new AccessDeniedException("Not allowed");
        }
        return PaymentDtos.PaymentDto.from(p, scopeResolver.canSeePoc());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_MANAGE + "')")
    public PaymentDtos.PaymentDto create(@Valid @RequestBody PaymentDtos.CreatePaymentRequest req) {
        return PaymentDtos.PaymentDto.from(paymentService.record(req), scopeResolver.canSeePoc());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_MANAGE + "')")
    public PaymentDtos.PaymentDto update(@PathVariable Long id,
                                         @RequestBody PaymentDtos.UpdatePaymentRequest req) {
        // Whether the POC may change is decided by the service, which knows the current one.
        return PaymentDtos.PaymentDto.from(paymentService.update(id, req), scopeResolver.canSeePoc());
    }

    public record CustomerCreditDto(Long customerId, String customerName, BigDecimal creditBalance) {}

    @GetMapping("/credits/{customerId}")
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_VIEW + "')")
    public CustomerCreditDto credit(@PathVariable Long customerId) {
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer != null && !callerCustomer.equals(customerId)) {
            throw new AccessDeniedException("Not allowed");
        }
        Customer c = customerRepository.findById(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        return new CustomerCreditDto(c.getId(), c.getName(), c.getCreditBalance());
    }

    // ---- bulk & export ---------------------------------------------------------

    public static final List<String> BULK_ACTIONS = List.of("REASSIGN_COLLECTION_POC");

    @PostMapping("/bulk")
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_MANAGE + "')")
    public BulkDtos.BulkResult bulk(@Valid @RequestBody BulkDtos.BulkRequest req) {
        List<Long> ids = resolveIds(req);
        boolean truncated = req.allMatching() && ids.size() >= TableQueryExecutor.BULK_ID_LIMIT;
        // Voiding is deliberately not a bulk action: it moves money and must be confirmed one
        // record at a time through the dispute flow.
        if (!"REASSIGN_COLLECTION_POC".equals(req.action())) {
            throw new BadRequestException(
                    "Unknown bulk action: " + req.action() + " (expected one of " + BULK_ACTIONS + ")");
        }
        Long userId = req.longParam("userId");
        if (userId == null) throw new BadRequestException("REASSIGN_COLLECTION_POC requires params.userId");
        if (!currentUser.canAssignPoc(userRepository)) {
            throw new BadRequestException("You may not change the Collection POC");
        }
        return bulkExecutor.run(req.action(), ids, truncated,
                id -> paymentService.reassignCollectionPoc(id, userId));
    }

    @PostMapping("/export")
    @PreAuthorize("hasAuthority('" + Privileges.EXPORT_DATA + "') and hasAuthority('" + Privileges.PAYMENT_VIEW + "')")
    public ResponseEntity<String> export(@RequestBody BulkDtos.BulkRequest req) {
        boolean poc = scopeResolver.canSeePoc();
        List<Long> ids = resolveIds(req);
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

    private List<Long> resolveIds(BulkDtos.BulkRequest req) {
        TableQuery query = TableQuery.parseUnpaged(schema(), req.sort(), req.filters());
        List<Long> permitted = paymentService.idsMatching(query, TableQueryExecutor.BULK_ID_LIMIT);
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
