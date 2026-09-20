package com.geneinvoice.document;

import com.geneinvoice.auth.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * What happens to documents when the record they hang off goes (AC-C5). Only a customer can be
 * deleted today — invoices are cancelled and payments are reversed, neither of which removes a
 * record — so that is the one cascade there is.
 *
 * <p>It is its own component rather than a call into {@link DocumentService} so that the customer
 * package can depend on it without the two services depending on each other.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentCascade {

    private final DocumentRepository repository;
    private final DocumentParentLock parentLock;
    private final CurrentUser currentUser;

    /**
     * Soft-deletes the documents of a customer being deleted: its own, and those on its invoices
     * and payments, which is what {@code customer_id} is on the row for. Runs in the caller's
     * transaction, so the customer and its documents go together or not at all. Nothing is left
     * downloadable: a deleted document is a 404.
     *
     * <p>The customer is locked before its documents are read, so an upload that is part-way
     * through — its bytes stored, its row not yet committed — is waited for rather than missed.
     * Miss it and the row it is about to write survives a customer that does not (DOC-5).
     */
    public int onCustomerDeleted(Long customerId) {
        parentLock.lockCustomer(customerId);
        int removed = repository.softDeleteForCustomer(customerId, currentUser.idOrNull(), Instant.now());
        if (removed > 0) {
            log.info("Soft-deleted {} document(s) of deleted customer {}", removed, customerId);
        }
        return removed;
    }
}
