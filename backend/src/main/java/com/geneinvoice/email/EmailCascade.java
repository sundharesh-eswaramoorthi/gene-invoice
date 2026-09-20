package com.geneinvoice.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * What happens to emails when the record they were recorded against goes (CP-13). Only a customer
 * can be deleted, so that is the one cascade there is.
 *
 * <p>The emails themselves are kept: they are a record of something that was actually said, and
 * an email sent to a customer that no longer exists still happened. What goes is the pretence
 * that the record behind it is still there — the row is marked, and every view then drops the
 * link that used to lead to a 404 and says the record is gone.
 *
 * <p>It is its own component rather than a call into {@link EmailService} so that the customer
 * package can depend on it without the two services depending on each other, exactly as
 * {@link com.geneinvoice.document.DocumentCascade} is.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailCascade {

    private final EmailRepository repository;

    /**
     * Marks the emails recorded against a customer being deleted. Runs in the caller's
     * transaction, so the customer and the marking go together or not at all. A customer with
     * invoices cannot be deleted, so emails on its invoices and payments cannot be stranded this
     * way and are left alone.
     */
    public int onCustomerDeleted(Long customerId) {
        int marked = repository.markEntityDeleted(EmailEntityType.CUSTOMER, customerId);
        if (marked > 0) {
            log.info("Marked {} email(s) of deleted customer {} as having no record", marked, customerId);
        }
        return marked;
    }
}
