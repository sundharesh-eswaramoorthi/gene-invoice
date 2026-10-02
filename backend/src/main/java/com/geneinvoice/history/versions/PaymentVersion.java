package com.geneinvoice.history.versions;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.VersionRow;
import com.geneinvoice.payment.PaymentStatus;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** A {@code payments} row over a period. Its allocations have versions of their own. */
@Entity
@Table(name = "payment_versions", indexes = {
        @Index(name = "idx_payment_versions_entity", columnList = "entity_id,valid_to"),
        @Index(name = "idx_payment_versions_window", columnList = "valid_to,valid_from"),
        @Index(name = "idx_payment_versions_customer", columnList = "customer_id,valid_to"),
        @Index(name = "idx_payment_versions_region", columnList = "region_id,valid_to"),
        @Index(name = "idx_payment_versions_collection_poc", columnList = "collection_poc_user_id,valid_to")
})
@Getter
@Setter
@NoArgsConstructor
public class PaymentVersion extends VersionRow {

    @Column(name = "customer_id")
    private Long customerId;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Customer customer;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(precision = 14, scale = 2)
    private BigDecimal creditApplied;

    @Column(length = 40)
    private String method;

    @Column(length = 300)
    private String notes;

    private Instant paidAt;

    @Column(name = "collection_poc_user_id")
    private Long collectionPocId;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collection_poc_user_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User collectionPoc;

    @Column(name = "region_id")
    private Long regionId;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Region region;

    @Convert(converter = EnumNames.PaymentStatusName.class)
    @Column(length = 20)
    private PaymentStatus status;
}
