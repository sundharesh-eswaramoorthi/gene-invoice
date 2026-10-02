package com.geneinvoice.region;

import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.customer.CustomerDtos;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public final class RegionMoveDtos {
    private RegionMoveDtos() {}

    /** {@code version} is the customer's as the caller last read it; when given, a changed customer is a 409. */
    public record MoveRequest(
            @NotNull Long regionId,
            Long version,
            @Size(max = FieldLimits.REASON) String reason
    ) {}

    /** How many of the customer's records moved; {@code other} is each participant's own count, by name. */
    public record Moved(int invoices, int payments, int promises, int disputes, Map<String, Integer> other) {}

    /**
     * {@code warnings} name the people who hold a seat or a POC role on the customer's records but have no access
     * to the new region: nothing is taken from them, they just cannot reach it until they are granted it.
     */
    public record MoveResult(CustomerDtos.CustomerDto customer, Moved moved, List<String> warnings) {}
}
