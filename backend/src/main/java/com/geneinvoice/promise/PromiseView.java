package com.geneinvoice.promise;

import com.geneinvoice.user.User;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Everything {@link PaymentPromiseService#toDto} reads off a promise, and the one piece of derived
 * money logic, in ONE place that both a live {@link PaymentPromise} and an interval-versioned
 * mirror row can implement (B3).
 *
 * <p>WHY AN INTERFACE AND NOT A SHARED SUPERCLASS: the mirror deliberately maps its foreign keys
 * as plain read-only Longs and carries neither of the two link collections, so it is not
 * substitutable at the ORM level and the sharing is done at the DTO level instead (B3).
 *
 * <p>The invoices a promise covers and the payments that fulfilled it are passed to the DTO
 * factory as arguments rather than declared here: a mirror answers them from the two link mirrors
 * in force on the date asked, and an accessor it could only answer with an empty set would be a
 * silent wrong answer waiting to be shipped (B3).
 *
 * <p>{@code getRemainingAmount} lives here and NOWHERE ELSE, so a live list and an as-of list
 * cannot come to disagree about how much of the same promise is still owed (B3).
 */
public interface PromiseView {

    Long getId();

    /** The account's id off the row's own foreign key, never walked through an association (B3). */
    Long getCustomerId();

    /** The account's name — today's on a live row, the name it had THEN on a mirror row (B3). */
    String getCustomerName();

    BigDecimal getAmount();

    BigDecimal getFulfilledAmount();

    LocalDate getPromisedDate();

    PromiseStatus getStatus();

    boolean isStatusOverridden();

    String getOverrideReason();

    Long getOverriddenByUserId();

    Instant getOverriddenAt();

    /**
     * The POC's IDENTITY is as-of; the User row behind it is not mirrored, so the name and username
     * a DTO renders are always today's (B3).
     */
    User getCollectionPoc();

    String getNotes();

    Long getCreatedByUserId();

    Instant getCreatedAt();

    Instant getUpdatedAt();

    /**
     * The row says where it lives, so the client can hide an edit button on a record in a region
     * the caller only reads rather than offering it and collecting a 403 (B1). A live promise
     * answers it through its customer; a mirror is filled from customer_region_history, the
     * authoritative ledger of which region an account was in on a date (B3, B1).
     */
    Long getRegionId();

    String getRegionName();

    default BigDecimal getRemainingAmount() {
        if (getStatus() == PromiseStatus.KEPT || getStatus() == PromiseStatus.CANCELLED) {
            return BigDecimal.ZERO;
        }
        BigDecimal fulfilled = getFulfilledAmount() == null ? BigDecimal.ZERO : getFulfilledAmount();
        BigDecimal remaining = getAmount().subtract(fulfilled);
        return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
    }
}
