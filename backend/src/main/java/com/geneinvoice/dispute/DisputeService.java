package com.geneinvoice.dispute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DisputeService {

    private static final String NOTIF_OPENED = "DISPUTE_OPENED";
    private static final String NOTIF_APPROVED = "DISPUTE_APPROVED";
    private static final String NOTIF_DENIED = "DISPUTE_DENIED";

    private final DisputeRepository disputeRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;

    @Transactional
    public Dispute open(DisputeDtos.CreateDisputeRequest req) {
        User caller = currentUser.require();
        Long callerCustomer = caller.getCustomerId();
        if (callerCustomer == null) {
            throw new AccessDeniedException("Only customers can open disputes");
        }
        ensureTargetBelongsToCustomer(req.targetType(), req.targetId(), callerCustomer);

        if (disputeRepository.existsByCustomerIdAndTargetTypeAndTargetIdAndStatus(
                callerCustomer, req.targetType(), req.targetId(), DisputeStatus.PENDING)) {
            throw new BadRequestException("An open dispute already exists for this " +
                    req.targetType().name().toLowerCase());
        }

        Dispute d = Dispute.builder()
                .customerId(callerCustomer)
                .openedByUserId(caller.getId())
                .targetType(req.targetType())
                .targetId(req.targetId())
                .reason(req.reason())
                .proposedChangeJson(req.proposedChangeJson())
                .status(DisputeStatus.PENDING)
                .build();
        d = disputeRepository.save(d);

        Customer cust = customerRepository.findById(callerCustomer).orElse(null);
        String custName = cust == null ? "customer" : cust.getName();
        notificationService.notifyAdmins(NOTIF_OPENED,
                "New dispute from " + custName,
                req.reason(),
                "/admin/disputes/" + d.getId());

        return d;
    }

    @Transactional
    public Dispute approve(Long disputeId, DisputeDtos.ResolveDisputeRequest req) {
        Dispute d = mustBePending(disputeId);
        String changeJson = req != null && req.appliedChangeJson() != null && !req.appliedChangeJson().isBlank()
                ? req.appliedChangeJson()
                : d.getProposedChangeJson();

        Object before = snapshotTarget(d);
        applyChange(d, changeJson);
        Object after = snapshotTarget(d);

        d.setStatus(DisputeStatus.APPROVED);
        d.setResolvedAt(java.time.Instant.now());
        d.setResolvedByUserId(currentUser.require().getId());
        if (req != null && req.adminNotes() != null) d.setAdminNotes(req.adminNotes());
        d = disputeRepository.save(d);

        auditService.record(
                d.getTargetType().name(), d.getTargetId(),
                "DISPUTE_APPROVED",
                before, after,
                d.getResolvedByUserId(), d.getId(),
                d.getAdminNotes() == null ? d.getReason() : d.getAdminNotes());

        notifyCustomerOfResolution(d, NOTIF_APPROVED, "Dispute approved");
        return d;
    }

    @Transactional
    public Dispute deny(Long disputeId, DisputeDtos.ResolveDisputeRequest req) {
        Dispute d = mustBePending(disputeId);
        d.setStatus(DisputeStatus.DENIED);
        d.setResolvedAt(java.time.Instant.now());
        d.setResolvedByUserId(currentUser.require().getId());
        if (req != null && req.adminNotes() != null) d.setAdminNotes(req.adminNotes());
        d = disputeRepository.save(d);

        notifyCustomerOfResolution(d, NOTIF_DENIED, "Dispute denied");
        return d;
    }

    @Transactional(readOnly = true)
    public List<Dispute> list() {
        Long callerCustomer = currentUser.customerIdOrNull();
        return callerCustomer != null
                ? disputeRepository.findByCustomerIdOrderByCreatedAtDesc(callerCustomer)
                : disputeRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Dispute get(Long id) {
        Dispute d = disputeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Dispute not found"));
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer != null && !callerCustomer.equals(d.getCustomerId())) {
            throw new AccessDeniedException("Not allowed");
        }
        return d;
    }

    public DisputeDtos.DisputeDto toDto(Dispute d) {
        String summary = buildTargetSummary(d);
        String customerName = customerRepository.findById(d.getCustomerId())
                .map(Customer::getName).orElse(null);
        return new DisputeDtos.DisputeDto(
                d.getId(), d.getCustomerId(), customerName, d.getOpenedByUserId(),
                d.getTargetType(), d.getTargetId(), summary,
                d.getReason(), d.getProposedChangeJson(),
                d.getStatus(), d.getAdminNotes(),
                d.getResolvedByUserId(), d.getResolvedAt(),
                d.getCreatedAt(), d.getUpdatedAt());
    }

    private Dispute mustBePending(Long disputeId) {
        Dispute d = disputeRepository.findById(disputeId)
                .orElseThrow(() -> new NotFoundException("Dispute not found"));
        if (d.getStatus() != DisputeStatus.PENDING) {
            throw new BadRequestException("Dispute already resolved");
        }
        return d;
    }

    private void ensureTargetBelongsToCustomer(DisputeTargetType type, Long id, Long customerId) {
        switch (type) {
            case INVOICE -> {
                Invoice inv = invoiceRepository.findById(id)
                        .orElseThrow(() -> new NotFoundException("Invoice not found"));
                if (!inv.getCustomer().getId().equals(customerId)) {
                    throw new AccessDeniedException("Not allowed");
                }
            }
            case PAYMENT -> {
                Payment p = paymentRepository.findById(id)
                        .orElseThrow(() -> new NotFoundException("Payment not found"));
                if (!p.getCustomer().getId().equals(customerId)) {
                    throw new AccessDeniedException("Not allowed");
                }
            }
        }
    }

    private Object snapshotTarget(Dispute d) {
        return switch (d.getTargetType()) {
            case INVOICE -> invoiceRepository.findById(d.getTargetId())
                    .map(inv -> InvoiceDtos.InvoiceDto.from(inv, invoiceService.activeCreditedAmount(inv.getId())))
                    .orElse(null);
            case PAYMENT -> paymentRepository.findById(d.getTargetId())
                    .map(PaymentDtos.PaymentDto::from).orElse(null);
        };
    }

    private String buildTargetSummary(Dispute d) {
        return switch (d.getTargetType()) {
            case INVOICE -> invoiceRepository.findById(d.getTargetId())
                    .map(i -> i.getInvoiceNumber() + " — " + i.getTotal())
                    .orElse("Invoice #" + d.getTargetId());
            case PAYMENT -> paymentRepository.findById(d.getTargetId())
                    .map(p -> "Payment #" + p.getId() + " — " + p.getAmount())
                    .orElse("Payment #" + d.getTargetId());
        };
    }

    /** Dispatch the applied JSON change to the right service method. */
    private void applyChange(Dispute d, String changeJson) {
        if (changeJson == null || changeJson.isBlank()) {
            throw new BadRequestException("No change specified for approval");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(changeJson);
        } catch (Exception e) {
            throw new BadRequestException("Invalid change JSON: " + e.getMessage());
        }
        String action = node.path("action").asText("");

        switch (d.getTargetType()) {
            case INVOICE -> applyInvoiceChange(d.getTargetId(), action, node);
            case PAYMENT -> applyPaymentChange(d.getTargetId(), action, node);
        }
    }

    private void applyInvoiceChange(Long invoiceId, String action, JsonNode node) {
        switch (action) {
            case "cancel" -> invoiceService.cancelWithRefund(invoiceId);
            case "replace_items" -> {
                JsonNode itemsNode = node.path("items");
                if (!itemsNode.isArray() || itemsNode.isEmpty()) {
                    throw new BadRequestException("replace_items requires non-empty items array");
                }
                List<InvoiceDtos.LineInput> items = new ArrayList<>();
                for (JsonNode it : itemsNode) {
                    Long productId = it.path("productId").asLong();
                    int qty = it.path("quantity").asInt();
                    JsonNode unit = it.get("unitPrice");
                    BigDecimal unitPrice = unit == null || unit.isNull() ? null : new BigDecimal(unit.asText());
                    items.add(new InvoiceDtos.LineInput(productId, qty, unitPrice));
                }
                String notes = node.hasNonNull("notes") ? node.get("notes").asText() : null;
                invoiceService.replaceItems(invoiceId, items, notes);
            }
            case "update_notes" -> {
                Invoice inv = invoiceService.getInternal(invoiceId);
                inv.setNotes(node.hasNonNull("notes") ? node.get("notes").asText() : null);
                invoiceRepository.save(inv);
            }
            default -> throw new BadRequestException("Unknown invoice action: " + action);
        }
    }

    private void applyPaymentChange(Long paymentId, String action, JsonNode node) {
        switch (action) {
            case "void" -> paymentService.voidPayment(paymentId);
            case "update_amount" -> {
                if (!node.hasNonNull("amount")) {
                    throw new BadRequestException("update_amount requires amount");
                }
                BigDecimal amount = new BigDecimal(node.get("amount").asText());
                String method = node.hasNonNull("method") ? node.get("method").asText() : null;
                String notes = node.hasNonNull("notes") ? node.get("notes").asText() : null;
                paymentService.updateAmount(paymentId, amount, method, notes);
            }
            case "update_meta" -> {
                Payment p = paymentRepository.findById(paymentId)
                        .orElseThrow(() -> new NotFoundException("Payment not found"));
                if (node.hasNonNull("method")) p.setMethod(node.get("method").asText());
                if (node.hasNonNull("notes")) p.setNotes(node.get("notes").asText());
                paymentRepository.save(p);
            }
            default -> throw new BadRequestException("Unknown payment action: " + action);
        }
    }

    private void notifyCustomerOfResolution(Dispute d, String type, String title) {
        // notify the user who opened the dispute (typically the customer's user account)
        notificationService.notify(
                d.getOpenedByUserId(),
                type,
                title,
                d.getAdminNotes() == null ? d.getReason() : d.getAdminNotes(),
                "/disputes/" + d.getId());
    }
}
