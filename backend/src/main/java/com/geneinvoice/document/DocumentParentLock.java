package com.geneinvoice.document;

import com.geneinvoice.customer.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one lock that makes an upload and the deletion of the record it is landing on agree about
 * which of them happened first (AC-C5, DOC-5).
 *
 * <p>It is always the <em>customer</em> row, whichever kind of record the file is attached to:
 * every document carries the customer it belongs to, and that is what the cascade deletes by, so
 * it is the row both sides have in common. Only a customer can be deleted today — invoices are
 * cancelled and payments reversed — so there is nothing else to take.
 *
 * <p>Both sides take it, which is the point. Without it the two are simply interleaved, and the
 * one order that loses is the cascade reading the documents to delete before the upload's row is
 * written and the customer going a moment later: the row is left live, pointing at a customer that
 * is not there, reachable by nothing and cleaned up by nothing. With it, an upload that has not
 * started its row yet loses — its parent is gone when it looks, so it fails and takes its bytes
 * back out — and one that has started makes the delete wait the few milliseconds it needs, so the
 * cascade sees the new row and takes it too.
 *
 * <p>The customer is the first lock every money path takes as well (see
 * {@code CustomerRepository.findByIdForUpdate}), so nothing here can deadlock against them: this
 * transaction holds that one lock and nothing else.
 */
@Component
@RequiredArgsConstructor
class DocumentParentLock {

    private final CustomerRepository customers;

    /**
     * Locks the customer a document hangs off until the caller's transaction ends, and says
     * whether it is still there. A document with no customer — there is no such kind of record
     * today — has nothing to wait for and nothing to lose.
     *
     * <p>It must be called inside the caller's transaction: a lock of its own would be released
     * the moment it was taken, which is the bug rather than the fix.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean lockCustomer(Long customerId) {
        return customerId == null || customers.findByIdForUpdate(customerId).isPresent();
    }
}
