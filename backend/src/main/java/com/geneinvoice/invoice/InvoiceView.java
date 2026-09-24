package com.geneinvoice.invoice;

import com.geneinvoice.user.User;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Everything a row DTO reads off an invoice, and the derived money and date logic, in ONE place
 * that both a live {@link Invoice} and an interval-versioned mirror row can implement (B3).
 *
 * <p>WHY AN INTERFACE AND NOT A SHARED SUPERCLASS: the mirror deliberately maps its foreign keys
 * as plain read-only Longs and carries no {@code Customer} and no items, so it is not substitutable
 * at the ORM level — a mapped superclass would have to be an entity shape both could inherit, and
 * there is no such shape. The sharing is therefore done at the DTO level instead (B3).
 *
 * <p>It names EXACTLY the accessors {@link InvoiceDtos.InvoiceSummary#from} and
 * {@link InvoiceDtos.InvoiceDto#from} read and nothing else. The two things a mirror cannot answer
 * — the line items and the live row version — are passed to the DTO factory as arguments rather
 * than declared here, because an accessor a mirror can only answer with an empty list is a silent
 * wrong answer waiting to be shipped (B3).
 *
 * <p>{@code getBalance}, {@code isOverdue} and {@code daysOverdue} live here as defaults and
 * NOWHERE ELSE. That is the whole point of the interface: a live list and an as-of list that each
 * worked out "overdue" for themselves would eventually disagree about the same invoice (B3).
 */
public interface InvoiceView {

    Long getId();

    String getInvoiceNumber();

    /** The account's id off the row's own foreign key, never walked through an association (B3). */
    Long getCustomerId();

    /** The account's name — today's on a live row, the name it had THEN on a mirror row (B3). */
    String getCustomerName();

    Instant getInvoiceDate();

    LocalDate getDueDate();

    PaymentTerm getPaymentTerm();

    BigDecimal getTotal();

    BigDecimal getPaidAmount();

    InvoiceStatus getStatus();

    String getNotes();

    /**
     * The POC's IDENTITY is as-of; the User row behind it is not mirrored, so the name and username
     * a DTO renders are always today's. A mirror answers this through a lazy read-only
     * {@code @ManyToOne} on the same foreign key (B3).
     */
    User getSalesPoc();

    Instant getCreatedAt();

    /**
     * The row says where it lives, so the client can hide an edit button on a record in a region
     * the caller only reads rather than offering it and collecting a 403. Null-tolerant on purpose:
     * an invoice is never unplaced once customers.region_id is not null, but a DTO built from a
     * half-migrated row must not throw (B1).
     *
     * <p>A live invoice answers it through its customer; a mirror carries no region column at all
     * and is filled from customer_region_history, which is the authoritative ledger of which
     * region an account was in on a date (B3, B1).
     */
    Long getRegionId();

    String getRegionName();

    default BigDecimal getBalance() {
        return getTotal().subtract(getPaidAmount());
    }

    default boolean isOverdue(LocalDate today) {
        return getDueDate() != null && getDueDate().isBefore(today)
                && getBalance().signum() > 0 && getStatus() != InvoiceStatus.CANCELLED;
    }

    default int daysOverdue(LocalDate today) {
        return isOverdue(today) ? (int) ChronoUnit.DAYS.between(getDueDate(), today) : 0;
    }
}
