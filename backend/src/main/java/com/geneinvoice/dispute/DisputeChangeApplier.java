package com.geneinvoice.dispute;

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

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

import static com.geneinvoice.approval.ChangeConflictException.requireUnchanged;

/**
 * Applies an approved dispute resolution by approving the dispute again as the maker, with the change they chose
 * (§5.11.6, §5.11.8). The target's money must be exactly what the maker saw.
 */
@Component
@RequiredArgsConstructor
public class DisputeChangeApplier implements ChangeApplier {

    /** A dispute target's money: an invoice's total, paid amount and status, or a payment's amount and status. */
    public record DisputeTargetView(DisputeTargetType targetType, BigDecimal total, BigDecimal paidAmount,
                                    String status, BigDecimal amount) {}

    private final DisputeService disputeService;
    private final DisputeRepository disputeRepository;
    private final CustomerRepository customerRepository;
    private final PayloadCodec codec;

    @Override
    public Set<ApprovalOperation> operations() {
        return EnumSet.of(ApprovalOperation.DISPUTE_APPROVE);
    }

    /** The customer, then the dispute (§5.11.5), as the maker's own approval takes them. */
    @Override
    public void lockDomainRows(PendingChange pc) {
        customerRepository.findByIdForUpdate(pc.getCustomerId());
        disputeRepository.findByIdForUpdate(pc.getEntityId());
    }

    @Override
    public Long currentRegionId(PendingChange pc) {
        return disputeRepository.findById(pc.getEntityId())
                .map(d -> Region.idOf(d.getRegion()))
                .orElseThrow(() -> new NotFoundException("Dispute not found"));
    }

    @Override
    public Outcome<?> apply(PendingChange pc) {
        DisputeDtos.ResolveDisputeRequest req =
                codec.read(pc.getPayloadJson(), DisputeDtos.ResolveDisputeRequest.class);
        codec.validate(req);
        Dispute d = disputeRepository.findById(pc.getEntityId())
                .orElseThrow(() -> new NotFoundException("Dispute not found"));
        // A dispute decided meanwhile fails with the approval's own rule; otherwise its target must be as it was.
        if (d.getStatus() == DisputeStatus.PENDING) {
            DisputeTargetView base = codec.read(pc.getBaseJson(), DisputeTargetView.class);
            DisputeTargetView now = disputeService.targetView(d);
            if (base != null && now != null) {
                requireUnchanged("Total", base.total(), now.total());
                requireUnchanged("Paid amount", base.paidAmount(), now.paidAmount());
                requireUnchanged("Amount", base.amount(), now.amount());
                requireUnchanged("Status", base.status(), now.status());
            }
        }
        return disputeService.approveOrHold(d.getId(), req);
    }

    @Override
    public Object currentView(PendingChange pc) {
        return disputeRepository.findById(pc.getEntityId()).map(disputeService::targetView).orElse(null);
    }

    @Override
    public Long resultEntityId(Object applied) {
        return ((Dispute) applied).getId();
    }

    @Override
    public Object resultView(Object applied) {
        return disputeService.toDto((Dispute) applied);
    }
}
