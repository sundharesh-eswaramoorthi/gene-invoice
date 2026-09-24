package com.geneinvoice.payment;

import com.geneinvoice.user.User;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Everything {@link PaymentDtos.PaymentDto#from} reads off a payment, in ONE place that both a
 * live {@link Payment} and an interval-versioned mirror row can implement (B3).
 *
 * <p>WHY AN INTERFACE AND NOT A SHARED SUPERCLASS: the mirror deliberately maps its foreign keys
 * as plain read-only Longs and carries no {@code Customer} and no allocation rows, so it is not
 * substitutable at the ORM level and the sharing is done at the DTO level instead (B3).
 *
 * <p>The two things a mirror cannot answer — the invoices this payment was allocated to, and the
 * account's credit balance — are passed to the DTO factory as arguments rather than declared here.
 * A mirror's allocations are the allocation-mirror rows in force on the date asked, and its credit
 * balance is the customer mirror's; an accessor a mirror could only answer with an empty list
 * would be a silent wrong answer waiting to be shipped (B3).
 */
public interface PaymentView {

    Long getId();

    /** The account's id off the row's own foreign key, never walked through an association (B3). */
    Long getCustomerId();

    /** The account's name — today's on a live row, the name it had THEN on a mirror row (B3). */
    String getCustomerName();

    BigDecimal getAmount();

    BigDecimal getCreditApplied();

    String getMethod();

    String getNotes();

    Instant getPaidAt();

    PaymentStatus getStatus();

    /**
     * The POC's IDENTITY is as-of; the User row behind it is not mirrored, so the name and username
     * a DTO renders are always today's (B3).
     */
    User getCollectionPoc();

    /**
     * The row says where it lives, so the client can hide an edit button on a record in a region
     * the caller only reads rather than offering it and collecting a 403 (B1). A live payment
     * answers it through its customer; a mirror is filled from customer_region_history, the
     * authoritative ledger of which region an account was in on a date (B3, B1).
     */
    Long getRegionId();

    String getRegionName();
}
