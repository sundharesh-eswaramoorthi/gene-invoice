package com.geneinvoice.customer;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.asof.AsOfCsv;
import com.geneinvoice.common.Money;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.common.bulk.Csv;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.poc.PocDtos;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.RegionCustodyService;
import jakarta.servlet.http.HttpServletRequest;
import com.geneinvoice.user.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService service;
    private final RegionCustodyService custodyService;
    private final PocService pocService;
    private final BulkExecutor bulkExecutor;
    private final CurrentUser currentUser;
    private final UserRepository userRepository;

    private TableSchema schema() {
        return TableSchemas.CUSTOMERS.visibleTo(currentUser.isCustomer());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.CUSTOMER_VIEW + "')")
    public PageResponse<CustomerDtos.CustomerDto> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request) {
        return service.page(TableQuery.parse(schema(), page, size, sort, FilterParams.from(request)));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('" + Privileges.CUSTOMER_VIEW + "')")
    public CustomerDtos.CustomerSummaryTiles summary(
            HttpServletRequest request) {
        return service.tiles(TableQuery.parseUnpaged(schema(), null, FilterParams.from(request)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.CUSTOMER_VIEW + "')")
    public CustomerDtos.CustomerDto get(@PathVariable Long id) {
        // Every figure below is the LIVE one — unless the reader asked as of a date, and then
        // every figure is that date's, including what the account owed and who sat on it. The
        // switch is inside the service, in the one place the list makes it too, so the two cannot
        // disagree about which account this is (B3).
        return service.detail(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.CUSTOMER_MANAGE + "')")
    public CustomerDtos.CustomerDto create(@Valid @RequestBody CustomerDtos.CustomerCreateRequest in) {
        return service.toDto(service.create(in));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.CUSTOMER_MANAGE + "')")
    public CustomerDtos.CustomerDto update(@PathVariable Long id,
                                           @Valid @RequestBody CustomerDtos.CustomerUpdateRequest in) {
        return service.toDto(service.update(id, in));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.CUSTOMER_MANAGE + "')")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /**
     * Move an account to another branch. CUSTOMER_MANAGE says WHAT, and the region rights the
     * custody service checks say WHERE: MANAGE is needed in the branch it is leaving AND in the one
     * it is joining, because the caller named both (B1, D-46).
     */
    @PostMapping("/{id}/region")
    @PreAuthorize("hasAuthority('" + Privileges.CUSTOMER_MANAGE + "')")
    public RegionCustodyService.MoveResult moveRegion(
            @PathVariable Long id,
            @Valid @RequestBody RegionCustodyService.MoveRegionRequest in) {
        // Reached before it is moved: an account outside this caller's book or their regions is
        // 404 here exactly as it is on every other read, and only then is the move's own 403 about
        // the two regions they named (B1, AUTH-08).
        service.get(id);
        return custodyService.move(id, in.toRegionId(), in.effectiveFrom(), in.reason());
    }

    @GetMapping("/{id}/pocs")
    @PreAuthorize("hasAuthority('" + Privileges.POC_VIEW + "')")
    public List<PocDtos.CustomerPocDto> pocs(@PathVariable Long id) {
        requireNonCustomerCaller();
        // Both halves move together under ?asOf: an account that did not exist then is 404, and
        // the seats are the seats that were HELD then rather than the ones held today. "Who was
        // the primary collection POC on 31 January" is a question the live table cannot answer at
        // all, because a vacated seat left no row behind it (B3, AUTH-08).
        service.requireVisible(id);
        return pocService.seatsFor(id);
    }

    @PostMapping("/{id}/pocs")
    @PreAuthorize("hasAuthority('" + Privileges.POC_ASSIGN + "')")
    public PocDtos.CustomerPocDto addPoc(@PathVariable Long id,
                                         @Valid @RequestBody PocDtos.AddCustomerPocRequest req) {
        requireNonCustomerCaller();
        service.get(id);
        return PocDtos.CustomerPocDto.from(
                pocService.add(id, req.pocType(), req.userId(), Boolean.TRUE.equals(req.primary())));
    }

    @DeleteMapping("/{id}/pocs/{pocId}")
    @PreAuthorize("hasAuthority('" + Privileges.POC_ASSIGN + "')")
    public void removePoc(@PathVariable Long id, @PathVariable Long pocId) {
        requireNonCustomerCaller();
        service.get(id);
        pocService.remove(id, pocId);
    }

    @PostMapping("/{id}/pocs/{pocId}/primary")
    @PreAuthorize("hasAuthority('" + Privileges.POC_ASSIGN + "')")
    public PocDtos.CustomerPocDto setPrimary(@PathVariable Long id, @PathVariable Long pocId) {
        requireNonCustomerCaller();
        service.get(id);
        return PocDtos.CustomerPocDto.from(pocService.setPrimary(id, pocId));
    }

    public static final List<String> BULK_ACTIONS = List.of("ADD_POC");

    @PostMapping("/bulk")
    @PreAuthorize("hasAuthority('" + Privileges.CUSTOMER_MANAGE + "')")
    public BulkDtos.BulkResult bulk(@Valid @RequestBody BulkDtos.BulkRequest req) {
        List<Long> ids = resolveIds(req);
        boolean truncated = req.allMatching() && ids.size() >= TableQueryExecutor.BULK_ID_LIMIT;
        if (!"ADD_POC".equals(req.action())) {
            throw new BadRequestException(
                    "Unknown bulk action: " + req.action() + " (expected one of " + BULK_ACTIONS + ")");
        }
        if (!currentUser.canAssignPoc(userRepository)) {
            // Not allowed is 403, not "bad request" (D-46).
            throw new org.springframework.security.access.AccessDeniedException(
                    "You may not change POC assignments");
        }
        Long userId = req.longParam("userId");
        String type = req.stringParam("pocType");
        if (userId == null || type == null) {
            throw new BadRequestException("ADD_POC requires params.userId and params.pocType");
        }
        PocType pocType = PocType.parse(type);
        boolean makePrimary = Boolean.parseBoolean(String.valueOf(req.stringParam("primary")));
        // A request that could not work for any row at all is one 400, not every row skipped (D-45).
        if (pocType == PocType.SALES) {
            throw new BadRequestException("Sales POC is assigned per invoice, not per customer");
        }
        // Null region on purpose: one selection can span as many branches as the filter does, so
        // "does this person work HERE" has no single answer to pre-flight. Everything else about
        // the assignee — active, not a customer login, holds the assignability privilege — is
        // still one 400 for the whole request rather than the same message on every row (D-45),
        // and each row's own add() then checks that row's own branch (B1).
        pocService.requireAssignable(userId, pocType, null);
        // A row whose branch this person does not work in did not QUALIFY; it did not go wrong.
        // eligibility() reports it the way an already-assigned row is already reported, which is
        // also how every other list's bulk action reports a row it could not act on (B1, TBL-05).
        return bulkExecutor.run(req, ids, truncated,
                BulkExecutor.eligibility(id -> pocService.add(id, pocType, userId, makePrimary)));
    }

    @PostMapping("/export")
    @PreAuthorize("hasAuthority('" + Privileges.EXPORT_DATA + "') and hasAuthority('" + Privileges.CUSTOMER_VIEW + "')")
    public ResponseEntity<String> export(@RequestBody BulkDtos.BulkRequest req) {
        List<Long> ids = resolveIds(req);
        List<CustomerDtos.CustomerDto> rows = service.toDtos(
                service.allMatching(TableQuery.parseUnpaged(schema(), req.sort(), req.filters()))
                        .stream().filter(c -> ids.contains(c.getId())).toList());

        // The terms and the overdue figure are what the list and the detail screen treat as
        // first-class on a customer, and a collections user cannot work the ageing out of an
        // export that leaves them behind. Money goes through one formatter, so a customer with
        // no invoices reads 0.00 like every other row (CP-14).
        String csv = Csv.of(
                List.of("Id", "Name", "Phone", "Email", "Payment terms",
                        "Credit balance", "Outstanding", "Overdue",
                        "Customer Success POCs", "Collection POCs", "Awaiting approval"),
                rows.stream().map(c -> List.<Object>of(
                        c.id(), c.name(), c.phone() == null ? "" : c.phone(),
                        c.email() == null ? "" : c.email(),
                        c.paymentTermLabel() == null ? "" : c.paymentTermLabel(),
                        Money.scale(c.creditBalance()), Money.scale(c.outstanding()),
                        Money.scale(c.overdueAmount()),
                        joinPocs(c.successPocs()), joinPocs(c.collectionPocs()),
                        // Read straight off the DTO: toDtos already resolved the whole export's
                        // flags in the one batch it runs for the page (B2).
                        Boolean.TRUE.equals(c.approvalPending()))).toList());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + AsOfCsv.filename("customers") + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                // A downloaded file outlives the banner that framed it, so the caveat travels
                // inside the file: a leading one-cell row, built by the same Csv.of every other
                // row goes through, and empty on a live export (B3).
                .body(AsOfCsv.caveat() + csv);
    }

    private String joinPocs(List<PocDtos.CustomerPocDto> pocs) {
        if (pocs == null || pocs.isEmpty()) return "";
        // A deactivated seat holder is marked the way the list and the detail screen mark them:
        // the export used to print the bare name, so it read as though that person were the POC
        // to contact when the app would no longer route anything to them (CP-07).
        return pocs.stream()
                .map(p -> p.user().username()
                        + (p.primary() ? " (primary)" : "")
                        + (p.user().active() ? "" : " (inactive)"))
                .reduce((a, b) -> a + "; " + b).orElse("");
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

    private void requireNonCustomerCaller() {
        if (currentUser.isCustomer()) {
            throw new org.springframework.security.access.AccessDeniedException("Not allowed");
        }
    }
}
