package com.geneinvoice.history.versions;

import com.geneinvoice.history.VersionRow;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One link of a many-to-many over a period, such as "promise 7 covers invoice 90": {@code kind} names the link
 * ({@code PROMISE_INVOICE}, {@code PROMISE_PAYMENT}), {@code ownerId} the owner and {@code id} the target, so a
 * query over links filters by the target as it would over the live association.
 */
@Entity
@Table(name = "entity_link_versions", indexes = {
        @Index(name = "idx_entity_link_versions_owner", columnList = "kind,owner_id,valid_to"),
        @Index(name = "idx_entity_link_versions_target", columnList = "kind,target_id,valid_to"),
        @Index(name = "idx_entity_link_versions_window", columnList = "valid_to,valid_from")
})
@AttributeOverride(name = "id", column = @Column(name = "target_id", nullable = false))
@Getter
@Setter
@NoArgsConstructor
public class EntityLinkVersion extends VersionRow {

    @Column(nullable = false, length = 30)
    private String kind;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;
}
