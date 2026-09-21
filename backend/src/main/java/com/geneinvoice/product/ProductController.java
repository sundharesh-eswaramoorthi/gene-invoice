package com.geneinvoice.product;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.Money;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.Strings;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.common.bulk.Csv;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductRepository repository;
    private final TableQueryExecutor queryExecutor;
    private final BulkExecutor bulkExecutor;
    private final AuditService auditService;
    private final CurrentUser currentUser;

    public record ProductDto(Long id, String name, String description, BigDecimal price, boolean active,
                             Instant createdAt) {
        public static ProductDto from(Product p) {
            return new ProductDto(p.getId(), p.getName(), p.getDescription(), p.getPrice(), p.isActive(),
                    p.getCreatedAt());
        }
    }

    public record ProductUpsert(
            @NotBlank @Size(max = FieldLimits.PRODUCT_NAME) String name,
            @Size(max = FieldLimits.PRODUCT_DESCRIPTION) String description,
            @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 2, message = Money.CENTS_MESSAGE)
            BigDecimal price,
            Boolean active
    ) {}

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.PRODUCT_VIEW + "')")
    public PageResponse<ProductDto> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request) {
        TableQuery query = TableQuery.parse(TableSchemas.PRODUCTS, page, size, sort, FilterParams.from(request));
        var result = queryExecutor.run(Product.class, TableSchemas.PRODUCTS, query, List.of(), List.of());
        return PageResponse.of(result.content().stream().map(ProductDto::from).toList(),
                query, result.total(), List.of());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.PRODUCT_VIEW + "')")
    public ProductDto get(@PathVariable Long id) {
        return repository.findById(id).map(ProductDto::from)
                .orElseThrow(() -> new NotFoundException("Product not found"));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.PRODUCT_MANAGE + "')")
    public ProductDto create(@Valid @RequestBody ProductUpsert in) {
        Product p = Product.builder()
                // Trimmed, and a description box left empty is stored as nothing rather than as
                // an empty string, so the list shows its "—" placeholder (CP-15).
                .name(Strings.trim(in.name())).description(Strings.blankToNull(in.description()))
                .price(in.price())
                .active(in.active() == null || in.active())
                .build();
        Product saved = repository.save(p);
        auditService.record("PRODUCT", saved.getId(), "PRODUCT_CREATED", null,
                ProductDto.from(saved), currentUser.require().getId(), null, null);
        return ProductDto.from(saved);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.PRODUCT_MANAGE + "')")
    public ProductDto update(@PathVariable Long id, @Valid @RequestBody ProductUpsert in) {
        Product p = repository.findById(id).orElseThrow(() -> new NotFoundException("Product not found"));
        Object before = ProductDto.from(p);
        p.setName(Strings.trim(in.name()));
        p.setDescription(Strings.blankToNull(in.description()));
        p.setPrice(in.price());
        if (in.active() != null) p.setActive(in.active());
        Product saved = repository.save(p);
        auditService.record("PRODUCT", id, "PRODUCT_UPDATED", before, ProductDto.from(saved),
                currentUser.require().getId(), null, null);
        return ProductDto.from(saved);
    }

    /** Removing a product leaves a trail like every other write on it (CP-04). */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.PRODUCT_MANAGE + "')")
    public void delete(@PathVariable Long id) {
        Product p = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found"));
        Object before = ProductDto.from(p);
        repository.deleteById(id);
        auditService.record("PRODUCT", id, "PRODUCT_DELETED", before, null,
                currentUser.require().getId(), null, "Product deleted");
    }

    public static final List<String> BULK_ACTIONS = List.of("ACTIVATE", "DEACTIVATE");

    @PostMapping("/bulk")
    @PreAuthorize("hasAuthority('" + Privileges.PRODUCT_MANAGE + "')")
    public BulkDtos.BulkResult bulk(@Valid @RequestBody BulkDtos.BulkRequest req) {
        boolean activate = switch (req.action()) {
            case "ACTIVATE" -> true;
            case "DEACTIVATE" -> false;
            default -> throw new BadRequestException(
                    "Unknown bulk action: " + req.action() + " (expected one of " + BULK_ACTIONS + ")");
        };
        List<Long> ids = resolveIds(req);
        boolean truncated = req.allMatching() && ids.size() >= TableQueryExecutor.BULK_ID_LIMIT;
        Long actor = currentUser.require().getId();
        return bulkExecutor.run(req, ids, truncated, id -> {
            Product p = repository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Product not found: " + id));
            if (p.isActive() == activate) {
                throw new BulkExecutor.IneligibleException(
                        "Already " + (activate ? "active" : "inactive"));
            }
            Object before = ProductDto.from(p);
            p.setActive(activate);
            Product saved = repository.save(p);
            auditService.record("PRODUCT", id, activate ? "PRODUCT_ACTIVATED" : "PRODUCT_DEACTIVATED",
                    before, ProductDto.from(saved), actor, null, "Bulk action");
        });
    }

    @PostMapping("/export")
    @PreAuthorize("hasAuthority('" + Privileges.EXPORT_DATA + "') and hasAuthority('" + Privileges.PRODUCT_VIEW + "')")
    public ResponseEntity<String> export(@RequestBody BulkDtos.BulkRequest req) {
        List<Long> ids = resolveIds(req);
        // findAllById ignores order, so put the rows back the way the filter and sort asked (D-41).
        List<Product> products = new ArrayList<>(repository.findAllById(ids));
        products.sort(Comparator.comparingInt(p -> ids.indexOf(p.getId())));
        String csv = Csv.of(List.of("Id", "Name", "Description", "Price", "Active"),
                products.stream().map(p -> List.<Object>of(p.getId(), p.getName(),
                        p.getDescription() == null ? "" : p.getDescription(),
                        p.getPrice(), p.isActive())).toList());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"products.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    private List<Long> resolveIds(BulkDtos.BulkRequest req) {
        TableQuery query = TableQuery.parseUnpaged(TableSchemas.PRODUCTS, req.sort(), req.filters());
        List<Long> permitted = queryExecutor.ids(Product.class, TableSchemas.PRODUCTS, query,
                List.of(), TableQueryExecutor.BULK_ID_LIMIT);
        if (req.allMatching()) return permitted;
        if (req.ids() == null || req.ids().isEmpty()) {
            throw new BadRequestException("Provide ids or set selectAllMatchingFilter");
        }
        return req.ids().stream().filter(permitted::contains).toList();
    }
}
