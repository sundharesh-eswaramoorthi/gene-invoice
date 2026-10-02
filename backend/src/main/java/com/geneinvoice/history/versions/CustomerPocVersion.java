package com.geneinvoice.history.versions;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.VersionRow;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A {@code customer_pocs} seat over a period; {@code id} is the seat's id. The table is named so that no count
 * of {@code customer_pocs} statements ever takes it in.
 */
@Entity
@Table(name = "customer_poc_versions", indexes = {
        @Index(name = "idx_customer_poc_versions_entity", columnList = "entity_id,valid_to"),
        @Index(name = "idx_customer_poc_versions_window", columnList = "valid_to,valid_from"),
        @Index(name = "idx_customer_poc_versions_customer", columnList = "customer_id,valid_to"),
        @Index(name = "idx_customer_poc_versions_user", columnList = "user_id,valid_to")
})
@Getter
@Setter
@NoArgsConstructor
public class CustomerPocVersion extends VersionRow {

    @Column(name = "customer_id")
    private Long customerId;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Customer customer;

    @Column(name = "user_id")
    private Long userId;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User user;

    @Convert(converter = EnumNames.PocTypeName.class)
    @Column(name = "poc_type", length = 20)
    private PocType pocType;

    @Column(name = "is_primary")
    private Boolean primary;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    private Instant createdAt;
}
