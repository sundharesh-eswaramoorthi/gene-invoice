package com.geneinvoice.document;

import com.geneinvoice.customer.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class DocumentParentLock {

    private final CustomerRepository customers;

    @Transactional(propagation = Propagation.MANDATORY)
    boolean lockCustomer(Long customerId) {
        return customerId == null || customers.findByIdForUpdate(customerId).isPresent();
    }
}
