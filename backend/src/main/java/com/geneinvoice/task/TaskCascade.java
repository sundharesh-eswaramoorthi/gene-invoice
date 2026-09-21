package com.geneinvoice.task;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * What happens to tasks when the record they hang off goes (T6). Only a customer can be deleted
 * today — invoices are cancelled and payments are reversed, neither of which removes a record — so
 * that is the one cascade there is.
 *
 * <p>It is its own component rather than a call into {@link TaskService} so that the customer
 * package can depend on it without the two services depending on each other, exactly as
 * {@code DocumentCascade} does.
 */
@Component
@RequiredArgsConstructor
public class TaskCascade {

    /**
     * Resolved when a customer is actually deleted, not when this bean is built (T6). A task's
     * record is loaded through {@code EmailTargets}, which loads a customer through
     * {@code CustomerService}, which is the very bean that calls this cascade — so injecting the
     * service outright closes a cycle that Spring refuses to start with, and the context fails at
     * boot rather than at the delete. Deferring the lookup leaves both as plain singletons.
     */
    private final ObjectProvider<TaskService> service;

    /** Runs in the customer delete's own transaction, so the customer and its tasks go together. */
    public void onCustomerDeleted(Long customerId) {
        service.getObject().onCustomerDeleted(customerId);
    }
}
