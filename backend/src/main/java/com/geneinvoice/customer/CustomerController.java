package com.geneinvoice.customer;

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
import com.geneinvoice.poc.PocDtos;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
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

import java.util.List;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService service;
    private final PocService pocService;
    private final BulkExecutor bulkExecutor;
    private final CurrentUser currentUser;
    private final UserRepository userRepository;

    /** Customer logins cannot filter on the POC seat columns (AC-A8). */
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
        return service.toDto(service.get(id));
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

    // ---- POC roster ------------------------------------------------------------

    @GetMapping("/{id}/pocs")
    @PreAuthorize("hasAuthority('" + Privileges.POC_VIEW + "')")
    public List<PocDtos.CustomerPocDto> pocs(@PathVariable Long id) {
        requireNonCustomerCaller();
        service.get(id);
        return pocService.listFor(id).stream().map(PocDtos.CustomerPocDto::from).toList();
    }

    @PostMapping("/{id}/pocs")
    @PreAuthorize("hasAuthority('" + Privileges.POC_ASSIGN + "')")
    public PocDtos.CustomerPocDto addPoc(@PathVariable Long id,
                                         @Valid @RequestBody PocDtos.AddCustomerPocRequest req) {
        requireNonCustomerCaller();
        return PocDtos.CustomerPocDto.from(
                pocService.add(id, req.pocType(), req.userId(), Boolean.TRUE.equals(req.primary())));
    }

    @DeleteMapping("/{id}/pocs/{pocId}")
    @PreAuthorize("hasAuthority('" + Privileges.POC_ASSIGN + "')")
    public void removePoc(@PathVariable Long id, @PathVariable Long pocId) {
        requireNonCustomerCaller();
        pocService.remove(id, pocId);
    }

    @PostMapping("/{id}/pocs/{pocId}/primary")
    @PreAuthorize("hasAuthority('" + Privileges.POC_ASSIGN + "')")
    public PocDtos.CustomerPocDto setPrimary(@PathVariable Long id, @PathVariable Long pocId) {
        requireNonCustomerCaller();
        return PocDtos.CustomerPocDto.from(pocService.setPrimary(id, pocId));
    }

    // ---- bulk & export ---------------------------------------------------------

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
            throw new BadRequestException("You may not change POC assignments");
        }
        Long userId = req.longParam("userId");
        String type = req.stringParam("pocType");
        if (userId == null || type == null) {
            throw new BadRequestException("ADD_POC requires params.userId and params.pocType");
        }
        PocType pocType = PocType.valueOf(type.toUpperCase());
        boolean makePrimary = Boolean.parseBoolean(String.valueOf(req.stringParam("primary")));
        return bulkExecutor.run(req.action(), ids, truncated, id -> {
            try {
                pocService.add(id, pocType, userId, makePrimary);
            } catch (BadRequestException e) {
                // Already holding that seat is not a failure — report it as skipped.
                throw new BulkExecutor.IneligibleException(e.getMessage());
            }
        });
    }

    @PostMapping("/export")
    @PreAuthorize("hasAuthority('" + Privileges.EXPORT_DATA + "') and hasAuthority('" + Privileges.CUSTOMER_VIEW + "')")
    public ResponseEntity<String> export(@RequestBody BulkDtos.BulkRequest req) {
        List<Long> ids = resolveIds(req);
        List<CustomerDtos.CustomerDto> rows = service.toDtos(
                service.allMatching(TableQuery.parseUnpaged(schema(), req.sort(), req.filters()))
                        .stream().filter(c -> ids.contains(c.getId())).toList());

        String csv = Csv.of(
                List.of("Id", "Name", "Phone", "Email", "Credit balance", "Outstanding",
                        "Customer Success POCs", "Collection POCs"),
                rows.stream().map(c -> List.<Object>of(
                        c.id(), c.name(), c.phone() == null ? "" : c.phone(),
                        c.email() == null ? "" : c.email(), c.creditBalance(), c.outstanding(),
                        joinPocs(c.successPocs()), joinPocs(c.collectionPocs()))).toList());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"customers.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    private String joinPocs(List<PocDtos.CustomerPocDto> pocs) {
        if (pocs == null || pocs.isEmpty()) return "";
        return pocs.stream()
                .map(p -> p.user().username() + (p.primary() ? " (primary)" : ""))
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

    /** POC data is never exposed to a customer-scoped account, whatever its role grants (AC-A8). */
    private void requireNonCustomerCaller() {
        if (currentUser.isCustomer()) {
            throw new org.springframework.security.access.AccessDeniedException("Not allowed");
        }
    }
}
