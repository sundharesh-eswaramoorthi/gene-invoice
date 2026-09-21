package com.geneinvoice.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailCascade {

    private final EmailRepository repository;

    public int onCustomerDeleted(Long customerId) {
        int marked = repository.markEntityDeleted(EmailEntityType.CUSTOMER, customerId);
        if (marked > 0) {
            log.info("Marked {} email(s) of deleted customer {} as having no record", marked, customerId);
        }
        return marked;
    }
}
