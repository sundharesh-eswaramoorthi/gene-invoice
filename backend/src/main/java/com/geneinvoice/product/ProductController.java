package com.geneinvoice.product;

import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.privilege.Privileges;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductRepository repository;

    public record ProductDto(Long id, String name, String description, BigDecimal price, boolean active) {
        public static ProductDto from(Product p) {
            return new ProductDto(p.getId(), p.getName(), p.getDescription(), p.getPrice(), p.isActive());
        }
    }

    public record ProductUpsert(
            @NotBlank String name,
            String description,
            @NotNull @PositiveOrZero BigDecimal price,
            Boolean active
    ) {}

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.PRODUCT_VIEW + "')")
    public List<ProductDto> list() {
        return repository.findAll().stream().map(ProductDto::from).toList();
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
                .name(in.name()).description(in.description()).price(in.price())
                .active(in.active() == null || in.active())
                .build();
        return ProductDto.from(repository.save(p));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.PRODUCT_MANAGE + "')")
    public ProductDto update(@PathVariable Long id, @Valid @RequestBody ProductUpsert in) {
        Product p = repository.findById(id).orElseThrow(() -> new NotFoundException("Product not found"));
        p.setName(in.name());
        p.setDescription(in.description());
        p.setPrice(in.price());
        if (in.active() != null) p.setActive(in.active());
        return ProductDto.from(repository.save(p));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.PRODUCT_MANAGE + "')")
    public void delete(@PathVariable Long id) {
        repository.deleteById(id);
    }
}
