package com.geneinvoice.history;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One version of a record: its state over {@code [validFrom, validTo)}. The table's key is {@code versionId};
 * the business id lives in {@code entity_id} under the attribute name {@code id}, so a query over versions
 * filters and sorts by the record's own id exactly as one over the live table does. Never look a version up
 * with {@code em.find}: that would take a record id for a version id.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class VersionRow {

    /** {@code validTo} of the current version. */
    public static final Instant OPEN = Instant.parse("9999-12-31T00:00:00Z");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "version_id")
    private Long versionId;

    @Column(name = "entity_id", nullable = false)
    private Long id;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to", nullable = false)
    private Instant validTo;

    /** The same for every version one transaction wrote. */
    @Column(name = "tx_id", nullable = false, length = 36)
    private String txId;

    /** A {@code WriteOrigin.Kind} name. */
    @Column(nullable = false, length = 12)
    private String origin;

    /** The rule of an AUTOMATION write, the pending change of an APPROVAL one. */
    @Column(name = "origin_ref_id")
    private Long originRefId;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    public boolean isOpen() {
        return OPEN.equals(validTo);
    }
}
