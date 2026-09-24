package com.geneinvoice.payment;

import com.geneinvoice.approval.PendingChangeRepository;
import com.geneinvoice.approval.PendingTargetType;
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
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.customer.CustomerView;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import com.geneinvoice.user.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    // The account behind the credit figure, reached through the service so that the book, the
    // region axis and the as-of switch are the ones GET /api/customers/{id} uses (B3, AUTH-02).
    private final CustomerService customerService;
    private final CurrentUser currentUser;
    private final ScopeResolver scopeResolver;
    private final BulkExecutor bulkExecutor;
    private final UserRepository userRepository;
    // The export's flags, batched exactly as the list page's are (B2).
    private final PendingChangeRepository pendingChangeRepository;

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
        // The amount, the allocations and the credit balance below are the LIVE ones; the flag is
        // the only thing a waiting change adds, because nothing pending has taken effect (B2).
        //
        // ...unless the reader asked as of a date, and then every figure is that date's — the
        // allocations it had then, the account's balance then — and the flag says whether the
        // change was still waiting THEN. The switch is inside the service, in the one place the
        // list makes it too (B3).
        return paymentService.detail(id, scopeResolver.canSeePoc());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_MANAGE + "')")
    public PaymentDtos.PaymentDto create(@Valid @RequestBody PaymentDtos.CreatePaymentRequest req) {
        return PaymentDtos.PaymentDto.from(paymentService.record(req), scopeResolver.canSeePoc());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_MANAGE + "')")
    public PaymentDtos.PaymentDto update(@PathVariable Long id,
                                         @Valid @RequestBody PaymentDtos.UpdatePaymentRequest req) {
        return PaymentDtos.PaymentDto.from(paymentService.update(id, req), scopeResolver.canSeePoc());
    }

    /**
     * {@code approvalPendingOnCustomer} is the one read in this application that would otherwise
     * MISSTATE money rather than merely omit a badge: creditBalance is what a cashier is about to
     * spend, and a held PAYMENT_VOID would take it away again. It is true for ANY change waiting
     * on the account and not only a CUSTOMER-targeted one, for exactly that reason (B2).
     */
    public record CustomerCreditDto(Long customerId, String customerName, BigDecimal creditBalance,
                                    Boolean approvalPendingOnCustomer) {}

    @GetMapping("/credits/{customerId}")
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_VIEW + "')")
    public CustomerCreditDto credit(@PathVariable Long customerId) {
        // A customer's name and credit balance are the customer's own, so this is gated exactly
        // where GET /api/customers/{id} is: outside the caller's book it is a 404, not a lookup
        // anyone holding PAYMENT_VIEW can walk the id space with (AUTH-02).
        //
        // Under ?asOf all three figures move together: the balance the account HELD then, the name
        // it had then, and whether a change was waiting on it then — and an account that did not
        // exist then is 404 rather than a zero balance. A cashier reading a past page must not be
        // shown today's spendable credit beside it (B3, B2).
        CustomerView c = customerService.visibleAccount(customerId);
        return new CustomerCreditDto(c.getId(), c.getName(), c.getCreditBalance(),
                paymentService.approvalPendingOnCustomer(customerId));
    }

    public static final List<String> BULK_ACTIONS = List.of("REASSIGN_COLLECTION_POC");

    // not gated (B2): the one bulk arm in the application that is NOT wrapped in
    // BulkExecutor.eligibility, and the only one whose single action moves no money —
    // REASSIGN_COLLECTION_POC changes who chases a payment, not the payment. RE-CHECK THIS if a
    // money action is ever added here: the service method behind it would need an
    // approvalGate.check of its own, and the rows this run held would then have to be reported
    // through BulkExecutor's pending bucket rather than looking like successes (B2).
    @PostMapping("/bulk")
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_MANAGE + "')")
    public BulkDtos.BulkResult bulk(@Valid @RequestBody BulkDtos.BulkRequest req) {
        List<Long> ids = resolveIds(req);
        boolean truncated = req.allMatching() && ids.size() >= TableQueryExecutor.BULK_ID_LIMIT;
        if (!"REASSIGN_COLLECTION_POC".equals(req.action())) {
            throw new BadRequestException(
                    "Unknown bulk action: " + req.action() + " (expected one of " + BULK_ACTIONS + ")");
        }
        Long userId = req.longParam("userId");
        if (userId == null) throw new BadRequestException("REASSIGN_COLLECTION_POC requires params.userId");
        if (!currentUser.canAssignPoc(userRepository)) {
            throw new BadRequestException("You may not change the Collection POC");
        }
        return bulkExecutor.run(req, ids, truncated,
                id -> paymentService.reassignCollectionPoc(id, userId));
    }

    @PostMapping("/export")
    @PreAuthorize("hasAuthority('" + Privileges.EXPORT_DATA + "') and hasAuthority('" + Privileges.PAYMENT_VIEW + "')")
    public ResponseEntity<String> export(@RequestBody BulkDtos.BulkRequest req) {
        boolean poc = scopeResolver.canSeePoc();
        List<Long> ids = resolveIds(req);
        List<? extends PaymentView> payments = paymentService.allMatching(
                        TableQuery.parseUnpaged(schema(), req.sort(), req.filters())).stream()
                .filter(p -> ids.contains(p.getId()))
                .toList();

        // One query for the whole export, the same batch the list page uses (B2).
        Set<Long> held = pendingChangeRepository.openTargetIds(PendingTargetType.PAYMENT,
                payments.stream().map(PaymentView::getId).toList());
        List<String> headers = new ArrayList<>(List.of(
                "Payment #", "Customer", "Paid at", "Amount", "Credit applied", "Method", "Status",
                "Awaiting approval"));
        if (poc) headers.add("Collection POC");
        // The account's name off the VIEW and not off a customer association: on a mirror row it
        // is the name the account had THEN, and on a live row it is the same value the list shows
        // (B3).
        List<List<Object>> rows = payments.stream().map(p -> {
            List<Object> row = new ArrayList<>(List.of(
                    p.getId(), p.getCustomerName(), p.getPaidAt(), p.getAmount(),
                    p.getCreditApplied(), p.getMethod() == null ? "" : p.getMethod(), p.getStatus(),
                    held.contains(p.getId())));
            if (poc) row.add(p.getCollectionPoc() == null ? "" : p.getCollectionPoc().getUsername());
            return row;
        }).toList();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + AsOfCsv.filename("payments") + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                // A downloaded file outlives the banner that framed it, so the caveat travels
                // inside the file: one leading cell, empty on a live export (B3).
                .body(AsOfCsv.caveat() + Csv.of(headers, rows));
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
