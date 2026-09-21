package com.geneinvoice.document;

import com.geneinvoice.auth.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentCascade {

    private final DocumentRepository repository;
    private final DocumentParentLock parentLock;
    private final CurrentUser currentUser;

    public int onCustomerDeleted(Long customerId) {
        parentLock.lockCustomer(customerId);
        int removed = repository.softDeleteForCustomer(customerId, currentUser.idOrNull(), Instant.now());
        if (removed > 0) {
            log.info("Soft-deleted {} document(s) of deleted customer {}", removed, customerId);
        }
        return removed;
    }
}
