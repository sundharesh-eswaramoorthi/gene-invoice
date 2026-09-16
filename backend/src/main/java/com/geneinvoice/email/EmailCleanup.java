package com.geneinvoice.email;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes a customer's emails when the customer itself is deleted. Kept apart from
 * {@link EmailService}, which reads customers, so the customer service can call it without a cycle.
 */
@Component
@RequiredArgsConstructor
public class EmailCleanup {

    private final EmailRepository emailRepository;
    private final EmailDeliveryRepository deliveryRepository;

    /** The emails go from every Inbox too; they were about a customer that no longer exists. */
    @Transactional
    public void deleteForCustomer(Long customerId) {
        deliveryRepository.deleteForCustomer(customerId);
        emailRepository.deleteAll(emailRepository.findByCustomerId(customerId));
    }
}
