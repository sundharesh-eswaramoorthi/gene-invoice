package com.geneinvoice.history.versions;

import com.geneinvoice.history.VersionRow;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.region.Region;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** A {@code customers} row over a period. Columns, names and lengths as the live table's. */
@Entity
@Table(name = "customer_versions", indexes = {
        @Index(name = "idx_customer_versions_entity", columnList = "entity_id,valid_to"),
        @Index(name = "idx_customer_versions_window", columnList = "valid_to,valid_from"),
        @Index(name = "idx_customer_versions_region", columnList = "region_id,valid_to")
})
@Getter
@Setter
@NoArgsConstructor
public class CustomerVersion extends VersionRow {

    @Column(length = 150)
    private String name;

    @Column(length = 30)
    private String phone;

    @Column(length = 120)
    private String email;

    @Column(length = 500)
    private String address;

    @Column(precision = 14, scale = 2)
    private BigDecimal creditBalance;

    @Convert(converter = EnumNames.PaymentTermName.class)
    @Column(name = "payment_term", length = 20)
    private PaymentTerm paymentTerm;

    @Column(name = "region_id")
    private Long regionId;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Region region;

    private Instant createdAt;
}
