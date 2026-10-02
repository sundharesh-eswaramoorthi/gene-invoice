package com.geneinvoice.promise;

import com.geneinvoice.approval.ApprovalOperation;
import com.geneinvoice.approval.ChangeApplier;
import com.geneinvoice.approval.Outcome;
import com.geneinvoice.approval.PayloadCodec;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.region.Region;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.geneinvoice.approval.ChangeConflictException.requireNotChangedElsewhere;
import static com.geneinvoice.approval.ChangeConflictException.requireUnchanged;
import static com.geneinvoice.approval.ChangeConflictException.same;

/** Applies approved promise changes by re-running {@link PaymentPromiseService}'s gated methods as the maker. */
@Component
@RequiredArgsConstructor
public class PromiseChangeApplier implements ChangeApplier {

    /** A promise's terms, before and after a held change of them. */
    public record PromiseView(BigDecimal amount, LocalDate promisedDate, Long collectionPocUserId, String notes,
                              List<Long> invoiceIds) {
        static PromiseView of(PaymentPromise p) {
            return new PromiseView(p.getAmount(), p.getPromisedDate(),
                    p.getCollectionPoc() == null ? null : p.getCollectionPoc().getId(), p.getNotes(),
                    p.getInvoices().stream().map(Invoice::getId).sorted().toList());
        }
    }

    /** A promise's status and whether a person set it, before and after a held override. */
    public record OverrideView(PromiseStatus status, boolean statusOverridden, String overrideReason) {
        static OverrideView of(PaymentPromise p) {
            return new OverrideView(p.getStatus(), p.isStatusOverridden(), p.getOverrideReason());
        }
    }

    private final PaymentPromiseService promiseService;
    private final PaymentPromiseRepository promiseRepository;
    private final CustomerRepository customerRepository;
    private final PayloadCodec codec;

    @Override
    public Set<ApprovalOperation> operations() {
        return EnumSet.of(ApprovalOperation.PROMISE_CREATE, ApprovalOperation.PROMISE_UPDATE,
                ApprovalOperation.PROMISE_OVERRIDE);
    }

    /** The customer, then the promise (§5.11.5), as the maker's own change takes them. */
    @Override
    public void lockDomainRows(PendingChange pc) {
        customerRepository.findByIdForUpdate(pc.getCustomerId());
        if (pc.getEntityId() != null) promiseRepository.findByIdForUpdate(pc.getEntityId());
    }

    @Override
    public Long currentRegionId(PendingChange pc) {
        if (pc.getOperation() == ApprovalOperation.PROMISE_CREATE) {
            return customerRepository.findById(pc.getCustomerId())
                    .map(c -> Region.idOf(c.getRegion()))
                    .orElseThrow(() -> new NotFoundException("Customer not found"));
        }
        return promiseRepository.findById(pc.getEntityId())
                .map(p -> Region.idOf(p.getRegion()))
                .orElseThrow(() -> new NotFoundException("Payment promise not found"));
    }

    @Override
    public Outcome<?> apply(PendingChange pc) {
        return switch (pc.getOperation()) {
            case PROMISE_CREATE -> {
                PromiseDtos.CreatePromiseRequest req =
                        codec.read(pc.getPayloadJson(), PromiseDtos.CreatePromiseRequest.class);
                codec.validate(req);
                yield promiseService.createOrHold(req);
            }
            case PROMISE_UPDATE -> applyUpdate(pc);
            case PROMISE_OVERRIDE -> applyOverride(pc);
            default -> throw new IllegalArgumentException(pc.getOperation() + " is not a promise change");
        };
    }

    /**
     * A PUT replaces every term, so it is re-based first: a term the maker left alone takes the promise's value
     * now, and a term they set may since have been changed only to what they set it to (three-way). Without the
     * re-base an approval would quietly undo whatever else was changed while it waited. A cancelled promise fails
     * with the edit's own rule.
     */
    private Outcome<?> applyUpdate(PendingChange pc) {
        PromiseDtos.UpdatePromiseRequest req = codec.read(pc.getPayloadJson(), PromiseDtos.UpdatePromiseRequest.class);
        codec.validate(req);
        PaymentPromise promise = promiseRepository.findById(pc.getEntityId())
                .orElseThrow(() -> new NotFoundException("Payment promise not found"));
        if (promise.getStatus() == PromiseStatus.CANCELLED) return promiseService.updateOrHold(promise.getId(), req);

        PromiseView base = codec.read(pc.getBaseJson(), PromiseView.class);
        PromiseView proposed = codec.read(pc.getProposedJson(), PromiseView.class);
        PromiseView now = PromiseView.of(promise);
        requireNotChangedElsewhere("Amount", base.amount(), proposed.amount(), now.amount());
        requireNotChangedElsewhere("Promised date", base.promisedDate(), proposed.promisedDate(), now.promisedDate());
        requireNotChangedElsewhere("Notes", base.notes(), proposed.notes(), now.notes());
        boolean pocSet = req.collectionPocUserId() != null
                && !same(base.collectionPocUserId(), proposed.collectionPocUserId());
        if (pocSet) {
            requireNotChangedElsewhere("Collection POC", base.collectionPocUserId(), proposed.collectionPocUserId(),
                    now.collectionPocUserId());
        }
        boolean invoicesSet = req.invoiceIds() != null && !same(base.invoiceIds(), proposed.invoiceIds());
        if (invoicesSet) {
            requireNotChangedElsewhere("Invoices", base.invoiceIds(), proposed.invoiceIds(), now.invoiceIds());
        }
        PromiseDtos.UpdatePromiseRequest rebased = new PromiseDtos.UpdatePromiseRequest(
                same(base.amount(), proposed.amount()) ? now.amount() : req.amount(),
                same(base.promisedDate(), proposed.promisedDate()) ? now.promisedDate() : req.promisedDate(),
                pocSet ? req.collectionPocUserId() : null,
                same(base.notes(), proposed.notes()) ? now.notes() : req.notes(),
                invoicesSet ? req.invoiceIds() : null);
        return promiseService.updateOrHold(promise.getId(), rebased);
    }

    /** Strict: the status must still have been set, or not, by a person exactly as the maker saw it. */
    private Outcome<?> applyOverride(PendingChange pc) {
        PromiseDtos.OverrideStatusRequest req =
                codec.read(pc.getPayloadJson(), PromiseDtos.OverrideStatusRequest.class);
        codec.validate(req);
        PaymentPromise promise = promiseRepository.findById(pc.getEntityId())
                .orElseThrow(() -> new NotFoundException("Payment promise not found"));
        if (promise.getStatus() != PromiseStatus.CANCELLED) {
            OverrideView base = codec.read(pc.getBaseJson(), OverrideView.class);
            requireUnchanged("Status override", base.statusOverridden(), promise.isStatusOverridden());
            requireUnchanged("Override reason", base.overrideReason(), promise.getOverrideReason());
        }
        return promiseService.overrideOrHold(promise.getId(), req.status(), req.reason());
    }

    @Override
    public Object currentView(PendingChange pc) {
        if (pc.getEntityId() == null) return null;
        return promiseRepository.findById(pc.getEntityId())
                .map(p -> pc.getOperation() == ApprovalOperation.PROMISE_OVERRIDE
                        ? (Object) OverrideView.of(p) : PromiseView.of(p))
                .orElse(null);
    }

    @Override
    public Long resultEntityId(Object applied) {
        return ((PromiseDtos.PromiseDto) applied).id();
    }

    @Override
    public Object resultView(Object applied) {
        return applied;
    }
}
