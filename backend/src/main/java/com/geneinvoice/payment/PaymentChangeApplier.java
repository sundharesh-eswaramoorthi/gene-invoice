package com.geneinvoice.payment;

import com.geneinvoice.approval.ApprovalOperation;
import com.geneinvoice.approval.ChangeApplier;
import com.geneinvoice.approval.Outcome;
import com.geneinvoice.approval.PayloadCodec;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.region.Region;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Applies an approved payment by recording it again as the maker (§5.11.6): every rule runs again, and the
 * amount may not have grown. Its payment time is the time it was asked for.
 */
@Component
@RequiredArgsConstructor
public class PaymentChangeApplier implements ChangeApplier {

    private final PaymentService paymentService;
    private final CustomerRepository customerRepository;
    private final PayloadCodec codec;

    @Override
    public Set<ApprovalOperation> operations() {
        return EnumSet.of(ApprovalOperation.PAYMENT_RECORD);
    }

    /** The customer (§5.11.5); recording takes its invoices after it. */
    @Override
    public void lockDomainRows(PendingChange pc) {
        customerRepository.findByIdForUpdate(pc.getCustomerId());
    }

    @Override
    public Long currentRegionId(PendingChange pc) {
        return customerRepository.findById(pc.getCustomerId())
                .map(c -> Region.idOf(c.getRegion()))
                .orElseThrow(() -> new NotFoundException("Customer not found"));
    }

    @Override
    public Outcome<?> apply(PendingChange pc) {
        PaymentDtos.CreatePaymentRequest req = codec.read(pc.getPayloadJson(), PaymentDtos.CreatePaymentRequest.class);
        codec.validate(req);
        return paymentService.recordOrHold(req);
    }

    /** A payment not yet made has nothing to compare with. */
    @Override
    public Object currentView(PendingChange pc) {
        return null;
    }

    @Override
    public Long resultEntityId(Object applied) {
        return ((Payment) applied).getId();
    }

    @Override
    public Object resultView(Object applied) {
        return PaymentDtos.PaymentDto.from((Payment) applied);
    }
}
