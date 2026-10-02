package com.geneinvoice.region;

/** One grant as plain data: what a principal carries, and the target of the grants query. */
public record GrantRow(Long regionId, AccessLevel level, RecordScope scope) {}
