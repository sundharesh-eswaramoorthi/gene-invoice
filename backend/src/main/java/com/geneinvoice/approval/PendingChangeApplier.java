package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs the change the maker asked for, by calling the same service method their save would have
 * called (B2).
 *
 * <p>It replays the CALL, not the setters: every guard the mutator has — a cancelled invoice, a
 * voided payment, an inactive product (InvoiceService.buildLines), Money.requireCents, the PPD-01
 * lock order — is re-run against the record as it is NOW. A change that can no longer be made
 * fails here with the mutator's own message rather than half-applying: replaceItems computes
 * paidAmount &gt; total and silently refunds the excess to credit, and that paid amount can have
 * appeared while this waited.
 *
 * <p>No default branch: a PendingAction constant added without a branch here will not compile.
 * That is this design's completeness-by-construction, at zero refactor cost (B2).
 */
@Component
@RequiredArgsConstructor
public class PendingChangeApplier {

    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final PaymentPromiseService promiseService;
    private final CustomerService customerService;
    private final DisputeService disputeService;
    private final ApprovalThresholdService thresholdService;
    private final ObjectMapper objectMapper;
    // spring-boot-starter-validation is already a dependency, so the container's own validator is
    // the one a controller's @Valid uses — not a second one with different constraints (B2).
    private final Validator validator;
    private final ApprovalContext context;

    public Object apply(PendingChange pc) {
        if (pc.getPayloadVersion() != PendingChange.PAYLOAD_VERSION) {
            // Jackson does not complain about a field that no longer exists, so a payload written
            // under an older shape would be replayed with components silently defaulted. Refused
            // rather than guessed (B2).
            throw new StaleChangeException("This change was raised by an older version of the "
                    + "application and cannot be applied; withdraw it and raise it again");
        }
        // Inside this, every gate the replay walks through is a no-op: the second pair of eyes is
        // already here, and without it the applier would hold its own work for approval for
        // ever (B2).
        return context.<Object>applying(pc.getId(), () -> switch (pc.getAction()) {
            case PAYMENT_RECORD -> PaymentDtos.PaymentDto.from(
                    paymentService.record(valid(pc, PaymentDtos.CreatePaymentRequest.class)));
            case PAYMENT_UPDATE_AMOUNT -> {
                ApprovalDtos.AmountChange in = valid(pc, ApprovalDtos.AmountChange.class);
                yield PaymentDtos.PaymentDto.from(paymentService.updateAmount(
                        pc.getTargetId(), in.amount(), in.method(), in.notes()));
            }
            case PAYMENT_VOID -> PaymentDtos.PaymentDto.from(
                    paymentService.voidPayment(pc.getTargetId()));
            case INVOICE_CREATE -> InvoiceDtos.InvoiceDto.from(
                    invoiceService.create(valid(pc, InvoiceDtos.CreateInvoiceRequest.class)));
            case INVOICE_CANCEL -> InvoiceDtos.InvoiceDto.from(
                    invoiceService.cancel(pc.getTargetId()));
            // R9 renamed cancelWithRefund and replaceItems: they are the two deliberate escape
            // hatches that read past the POC book and past the region predicate, and the check
            // that stands for both of them is the approval itself (B2, B1).
            case INVOICE_CANCEL_WITH_REFUND -> InvoiceDtos.InvoiceDto.from(
                    invoiceService.cancelWithRefundForDisputeApplication(pc.getTargetId()));
            case INVOICE_REPLACE_ITEMS -> {
                ApprovalDtos.ItemsChange in = valid(pc, ApprovalDtos.ItemsChange.class);
                yield InvoiceDtos.InvoiceDto.from(invoiceService.replaceItemsForDisputeApplication(
                        pc.getTargetId(), in.items(), in.notes()));
            }
            // createAs and never create: S4-ACTOR-SEAMS moved the whole body and every guard into
            // createAs, and the person accountable for the promise is the maker, not whoever is
            // signed in — which for an engine-raised change is nobody at all (B2, A5).
            case PROMISE_CREATE -> promiseService.createAs(pc.getRequestedByUserId(),
                    valid(pc, PromiseDtos.CreatePromiseRequest.class));
            case PROMISE_UPDATE -> promiseService.update(pc.getTargetId(),
                    valid(pc, PromiseDtos.UpdatePromiseRequest.class));
            case PROMISE_CANCEL -> promiseService.cancel(pc.getTargetId(),
                    valid(pc, ApprovalDtos.ReasonOnly.class).reason());
            case PROMISE_OVERRIDE -> {
                PromiseDtos.OverrideStatusRequest in =
                        valid(pc, PromiseDtos.OverrideStatusRequest.class);
                yield promiseService.override(pc.getTargetId(), in.status(), in.reason());
            }
            case CUSTOMER_DELETE -> {
                customerService.delete(pc.getTargetId());
                yield null;
            }
            // ResolveDisputeRequest is (adminNotes, appliedChangeJson) in that order
            // (DisputeDtos.java), which is the opposite way round from the way a reader of the
            // design's snippet would build it (B2).
            case DISPUTE_APPROVE -> {
                ApprovalDtos.DisputeApproval in = valid(pc, ApprovalDtos.DisputeApproval.class);
                yield disputeService.toDto(disputeService.approve(pc.getTargetId(),
                        new DisputeDtos.ResolveDisputeRequest(
                                in.resolve() == null ? null : in.resolve().adminNotes(),
                                in.changeJson())));
            }
            // The maker is who ASKED for the limit to move, so the THRESHOLD_CHANGED row names
            // them and the CHANGE_APPROVED row beside it names the approver (B2).
            case APPROVAL_THRESHOLD_SET -> thresholdService.applySet(pc.getTargetId(),
                    valid(pc, ApprovalDtos.ThresholdRequest.class), pc.getRequestedByUserId());
        });
    }

    /**
     * The bean validation a controller would have run, run again on JSON nobody re-validated: the
     * payload went to the database as text and comes back as text, and JSON nobody re-validated is
     * JSON nobody validated (B2).
     */
    private <T> T valid(PendingChange pc, Class<T> type) {
        T in;
        try {
            in = objectMapper.readValue(pc.getPayloadJson(), type);
        } catch (Exception e) {
            // The 409 family rather than a 500: the stored row is what is wrong, so the answer is
            // "look again, do not replay" and the change stays where it is (B2).
            throw new StaleChangeException("The stored change is not a valid " + type.getSimpleName());
        }
        Set<ConstraintViolation<T>> bad = validator.validate(in);
        if (!bad.isEmpty()) {
            throw new GlobalExceptionHandler.InvalidFieldsException(bad.stream().collect(
                    Collectors.toMap(v -> v.getPropertyPath().toString(),
                            ConstraintViolation::getMessage, (a, b) -> a)));
        }
        return in;
    }
}
