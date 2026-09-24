package com.geneinvoice.dispute;

import java.time.Instant;

/**
 * Everything {@link DisputeService#toDto} reads off a dispute, in ONE place that both a live
 * {@link Dispute} and an interval-versioned mirror row can implement (B3).
 *
 * <p>WHY AN INTERFACE AND NOT A SHARED SUPERCLASS: the mirror deliberately maps its foreign keys
 * as plain read-only Longs and is not substitutable at the ORM level, so the sharing is done at
 * the DTO level instead (B3).
 *
 * <p>A dispute already carries its account as a flat {@code customerId} with no association — the
 * cross-aggregate house convention — so every accessor here resolves on the live entity with no
 * delegate at all. The account's NAME and the dispute's TARGET are looked up by the service and
 * are not read off the row, so they are not declared here. B1's regionId/regionName slots are
 * absent for the same reason they are absent from DisputeDto: R5 gave the disputes table its
 * region as ColumnDefs only (B3, B2, B1).
 */
public interface DisputeView {

    Long getId();

    Long getCustomerId();

    Long getOpenedByUserId();

    DisputeTargetType getTargetType();

    Long getTargetId();

    String getReason();

    String getProposedChangeJson();

    DisputeStatus getStatus();

    String getAdminNotes();

    Long getResolvedByUserId();

    Instant getResolvedAt();

    Instant getCreatedAt();

    Instant getUpdatedAt();
}
