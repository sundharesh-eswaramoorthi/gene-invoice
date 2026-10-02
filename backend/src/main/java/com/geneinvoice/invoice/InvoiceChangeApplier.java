package com.geneinvoice.invoice;

import com.geneinvoice.approval.ApprovalOperation;
import com.geneinvoice.approval.ChangeApplier;
import com.geneinvoice.approval.ChangeConflictException;
import com.geneinvoice.approval.Outcome;
import com.geneinvoice.approval.PayloadCodec;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.region.Region;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.geneinvoice.approval.ChangeConflictException.requireNotChangedElsewhere;
import static com.geneinvoice.approval.ChangeConflictException.requireUnchanged;
import static com.geneinvoice.approval.ChangeConflictException.same;

/** Applies approved invoice changes by re-running {@link InvoiceService}'s gated methods as the maker. */
@Component
@RequiredArgsConstructor
public class InvoiceChangeApplier implements ChangeApplier {

    /** What a held cancel stores: the invoice, nothing else; the rules are all run again. */
    public record CancelPayload(@NotNull Long invoiceId) {}

    /** What the approver compares: a cancel is only approved for the invoice as it was asked about. */
    public record CancelView(BigDecimal total, BigDecimal paidAmount, InvoiceStatus status) {
        static CancelView of(Invoice inv) {
            return new CancelView(inv.getTotal(), inv.getPaidAmount(), inv.getStatus());
        }
    }

    /** The terms of an invoice, before and after a held change of them. */
    public record TermsView(LocalDate dueDate, PaymentTerm paymentTerm, String notes, Long salesPocUserId) {
        static TermsView of(Invoice inv) {
            return new TermsView(inv.getDueDate(), inv.getPaymentTerm(), inv.getNotes(),
                    inv.getSalesPoc() == null ? null : inv.getSalesPoc().getId());
        }
    }

    /** What a held create would make; there is nothing before it. */
    public record CreateView(Instant invoiceDate, LocalDate dueDate, PaymentTerm paymentTerm, BigDecimal total,
                             Long salesPocUserId, String notes, List<InvoiceDtos.LineInput> items) {}

    private final InvoiceService invoiceService;
    private final InvoiceRepository invoiceRepository;
    private final CustomerRepository customerRepository;
    private final PayloadCodec codec;

    @Override
    public Set<ApprovalOperation> operations() {
        return EnumSet.of(ApprovalOperation.INVOICE_CREATE, ApprovalOperation.INVOICE_CANCEL,
                ApprovalOperation.INVOICE_TERMS);
    }

    /** Customer, then invoice (§5.11.5), as the maker's own change takes them; a create has only the customer. */
    @Override
    public void lockDomainRows(PendingChange pc) {
        customerRepository.findByIdForUpdate(pc.getCustomerId());
        if (pc.getEntityId() != null) invoiceRepository.findByIdForUpdate(pc.getEntityId());
    }

    @Override
    public Long currentRegionId(PendingChange pc) {
        if (pc.getOperation() == ApprovalOperation.INVOICE_CREATE) {
            return customerRepository.findById(pc.getCustomerId())
                    .map(c -> Region.idOf(c.getRegion()))
                    .orElseThrow(() -> new NotFoundException("Customer not found"));
        }
        return invoiceRepository.findById(pc.getEntityId())
                .map(inv -> Region.idOf(inv.getRegion()))
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
    }

    @Override
    public Outcome<?> apply(PendingChange pc) {
        return switch (pc.getOperation()) {
            case INVOICE_CREATE -> {
                // Every rule runs again; the prices were pinned when it was asked for, so the total cannot move.
                InvoiceDtos.CreateInvoiceRequest req =
                        codec.read(pc.getPayloadJson(), InvoiceDtos.CreateInvoiceRequest.class);
                codec.validate(req);
                yield invoiceService.createOrHold(req);
            }
            case INVOICE_CANCEL -> applyCancel(pc);
            case INVOICE_TERMS -> applyTerms(pc);
            default -> throw new IllegalArgumentException(pc.getOperation() + " is not an invoice change");
        };
    }

    private Outcome<?> applyCancel(PendingChange pc) {
        CancelPayload payload = codec.read(pc.getPayloadJson(), CancelPayload.class);
        codec.validate(payload);
        Invoice inv = invoiceRepository.findById(payload.invoiceId())
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        // An invoice the cancel's own rules now refuse (cancelled meanwhile, or paid) fails with the rule's
        // message; otherwise it must still be the invoice the maker asked about, field for field.
        if (inv.getStatus() != InvoiceStatus.CANCELLED && inv.getPaidAmount().signum() == 0) {
            CancelView base = codec.read(pc.getBaseJson(), CancelView.class);
            requireUnchanged("Total", base.total(), inv.getTotal());
            requireUnchanged("Paid amount", base.paidAmount(), inv.getPaidAmount());
            requireUnchanged("Status", base.status(), inv.getStatus());
        }
        return invoiceService.cancelOrHold(payload.invoiceId());
    }

    /**
     * Each field the change sets may have been changed since only to what it sets it to (three-way); the rest
     * of the invoice is whatever it is now. Only the fields the change actually sets are applied: notes or a
     * Sales POC the request carried unchanged are left as they are now, so an edit someone else made to them
     * after the request is kept (APP-R1-1). Blank notes are no notes (APP-R1-2). A cancelled invoice fails with
     * the edit's own rule.
     */
    private Outcome<?> applyTerms(PendingChange pc) {
        InvoiceDtos.UpdateInvoiceRequest req = codec.read(pc.getPayloadJson(), InvoiceDtos.UpdateInvoiceRequest.class);
        codec.validate(req);
        Invoice inv = invoiceRepository.findById(pc.getEntityId())
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        TermsView base = codec.read(pc.getBaseJson(), TermsView.class);
        TermsView proposed = codec.read(pc.getProposedJson(), TermsView.class);
        if (inv.getStatus() != InvoiceStatus.CANCELLED) {
            TermsView now = TermsView.of(inv);
            requireNotChangedElsewhere("Due date", base.dueDate(), proposed.dueDate(), now.dueDate());
            requireNotChangedElsewhere("Payment term", base.paymentTerm(), proposed.paymentTerm(), now.paymentTerm());
            if (req.notes() != null) {
                requireNotChangedElsewhere("Notes", norm(base.notes()), norm(proposed.notes()), norm(now.notes()));
            }
            if (req.salesPocUserId() != null) {
                requireNotChangedElsewhere("Sales POC", base.salesPocUserId(), proposed.salesPocUserId(),
                        now.salesPocUserId());
            }
        }
        String notes = same(norm(base.notes()), norm(proposed.notes())) ? null : req.notes();
        Long poc = same(base.salesPocUserId(), proposed.salesPocUserId()) ? null : req.salesPocUserId();
        return invoiceService.updateOrHold(pc.getEntityId(),
                new InvoiceDtos.UpdateInvoiceRequest(notes, poc, req.dueDate(), req.paymentTerm(), null));
    }

    /** Notes that are blank are no notes: {@code null} and {@code ""} are the same. */
    static String norm(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @Override
    public Object currentView(PendingChange pc) {
        return switch (pc.getOperation()) {
            case INVOICE_CANCEL -> invoiceRepository.findById(pc.getAffectedId()).map(CancelView::of).orElse(null);
            case INVOICE_TERMS -> invoiceRepository.findById(pc.getAffectedId()).map(TermsView::of).orElse(null);
            default -> null;
        };
    }

    @Override
    public Long resultEntityId(Object applied) {
        return ((Invoice) applied).getId();
    }

    @Override
    public Object resultView(Object applied) {
        return InvoiceDtos.InvoiceDto.from((Invoice) applied);
    }
}
