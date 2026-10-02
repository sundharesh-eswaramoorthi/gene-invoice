package com.geneinvoice.history.versions;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.VersionRow;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A {@code payment_promises} row over a period. Its invoice and payment links are versioned row by row in
 * {@link EntityLinkVersion}. {@code updatedAt} and {@code brokenNotifiedAt} are kept but never make a version.
 */
@Entity
@Table(name = "promise_versions", indexes = {
        @Index(name = "idx_promise_versions_entity", columnList = "entity_id,valid_to"),
        @Index(name = "idx_promise_versions_window", columnList = "valid_to,valid_from"),
        @Index(name = "idx_promise_versions_customer", columnList = "customer_id,valid_to"),
        @Index(name = "idx_promise_versions_region", columnList = "region_id,valid_to"),
        @Index(name = "idx_promise_versions_collection_poc", columnList = "collection_poc_user_id,valid_to")
})
@Getter
@Setter
@NoArgsConstructor
public class PromiseVersion extends VersionRow {

    @Column(name = "customer_id")
    private Long customerId;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Customer customer;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "promised_date")
    private LocalDate promisedDate;

    @Column(name = "collection_poc_user_id")
    private Long collectionPocId;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collection_poc_user_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User collectionPoc;

    @Column(length = 1000)
    private String notes;

    @Convert(converter = EnumNames.PromiseStatusName.class)
    @Column(length = 20)
    private PromiseStatus status;

    @Column(name = "fulfilled_amount", precision = 14, scale = 2)
    private BigDecimal fulfilledAmount;

    @Column(name = "status_overridden")
    private Boolean statusOverridden;

    @Column(name = "override_reason", length = 500)
    private String overrideReason;

    @Column(name = "overridden_by_user_id")
    private Long overriddenByUserId;

    @Column(name = "overridden_at")
    private Instant overriddenAt;

    @Column(name = "broken_notified_at")
    private Instant brokenNotifiedAt;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "region_id")
    private Long regionId;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Region region;

    private Instant createdAt;

    private Instant updatedAt;
}
