package com.geneinvoice.region;

import org.springframework.util.ClassUtils;

/**
 * Told about a customer's region move inside the move's transaction, so what belongs to the customer and is not
 * one of its records (tasks, pending approvals, automation) moves, closes or is published with it (§5.6.8).
 */
public interface RegionMoveParticipant {

    /** Before anything moves; throw a {@code BadRequestException} to refuse the move, which then changes nothing. */
    default void beforeCustomerMoved(Long customerId, Long fromRegionId, Long toRegionId) {}

    /** After the customer's records moved; returns how many rows of its own it moved or closed. */
    default int afterCustomerMoved(Long customerId, Long fromRegionId, Long toRegionId) {
        return 0;
    }

    /** The key its count is reported under, in the move's {@code moved.other}. */
    default String name() {
        return ClassUtils.getUserClass(getClass()).getSimpleName();
    }
}
