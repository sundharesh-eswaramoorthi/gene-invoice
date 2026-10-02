package com.geneinvoice.history.versions;

import com.geneinvoice.history.VersionRow;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** A {@code products} row over a period: the name an invoice line showed at the time comes from here. */
@Entity
@Table(name = "product_versions", indexes = {
        @Index(name = "idx_product_versions_entity", columnList = "entity_id,valid_to"),
        @Index(name = "idx_product_versions_window", columnList = "valid_to,valid_from")
})
@Getter
@Setter
@NoArgsConstructor
public class ProductVersion extends VersionRow {

    @Column(length = 150)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(precision = 14, scale = 2)
    private BigDecimal price;

    private Boolean active;

    private Instant createdAt;
}
