package com.geneinvoice.customer;

import com.geneinvoice.invoice.PaymentTerm;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Everything {@link CustomerService#toDtos} reads off an account, in ONE place that both a live
 * {@link Customer} and an interval-versioned mirror row can implement (B3).
 *
 * <p>WHY AN INTERFACE AND NOT A SHARED SUPERCLASS: the mirror deliberately maps its foreign keys
 * as plain read-only Longs and is not substitutable at the ORM level, so the sharing is done at
 * the DTO level instead (B3).
 *
 * <p>The figures a customer row carries that are NOT on the customer row — outstanding, overdue,
 * the login's username, the POC seats and the held-change flag — are already batched by ids inside
 * the service and never read off the entity, so they need no accessor here (B3).
 */
public interface CustomerView {

    Long getId();

    String getName();

    String getPhone();

    String getEmail();

    String getAddress();

    BigDecimal getCreditBalance();

    PaymentTerm getPaymentTerm();

    Instant getCreatedAt();

    /**
     * The row says where it lives, so the client can hide an edit button on a record in a region
     * the caller only reads rather than offering it and collecting a 403. A customer's region is
     * its own — this is the only region column there is (B1).
     *
     * <p>A mirror row answers it as of the date asked, from customer_region_history rather than
     * from its own mirrored column, because that ledger is the authoritative record of which
     * region an account was in then (B3, B1).
     */
    Long getRegionId();

    String getRegionName();
}
