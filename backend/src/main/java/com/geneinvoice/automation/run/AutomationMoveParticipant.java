package com.geneinvoice.automation.run;

import com.geneinvoice.automation.event.AutomationEvents;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.region.RegionMoveParticipant;
import org.springframework.stereotype.Component;

/**
 * Publishes a customer's region move (M1): the customer was updated, and so were all of its invoices and payments.
 * The children are one CHILDREN event, expanded by the consumer, so the move writes two outbox rows however many
 * records the customer has.
 */
@Component
public class AutomationMoveParticipant implements RegionMoveParticipant {

    static final String REGION = "REGION";

    private final AutomationEvents events;

    public AutomationMoveParticipant(AutomationEvents events) {
        this.events = events;
    }

    /** Moves no rows of its own, so it reports none. */
    @Override
    public int afterCustomerMoved(Long customerId, Long fromRegionId, Long toRegionId) {
        events.updated(TriggerEntity.CUSTOMER, customerId, REGION);
        events.children(customerId, REGION);
        return 0;
    }
}
