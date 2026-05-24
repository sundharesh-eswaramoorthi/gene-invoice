package com.geneinvoice.audit;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.privilege.Privileges;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final CurrentUser currentUser;

    public record AuditEntryDto(Long id, String entityType, Long entityId, String action,
                                String beforeJson, String afterJson,
                                Long changedByUserId, Long disputeId, String reason,
                                Instant createdAt) {
        static AuditEntryDto from(AuditLog a) {
            return new AuditEntryDto(a.getId(), a.getEntityType(), a.getEntityId(), a.getAction(),
                    a.getBeforeJson(), a.getAfterJson(),
                    a.getChangedByUserId(), a.getDisputeId(), a.getReason(), a.getCreatedAt());
        }
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.AUDIT_VIEW + "')")
    public List<AuditEntryDto> history(@RequestParam String entityType,
                                       @RequestParam Long entityId) {
        ensureCallerCanSee(entityType, entityId);
        return auditService.historyFor(entityType.toUpperCase(), entityId).stream()
                .map(AuditEntryDto::from).toList();
    }

    private void ensureCallerCanSee(String entityType, Long entityId) {
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer == null) return;
        Long owningCustomer;
        switch (entityType.toUpperCase()) {
            case "INVOICE" -> {
                Invoice inv = invoiceRepository.findById(entityId)
                        .orElseThrow(() -> new NotFoundException("Invoice not found"));
                owningCustomer = inv.getCustomer().getId();
            }
            case "PAYMENT" -> {
                Payment p = paymentRepository.findById(entityId)
                        .orElseThrow(() -> new NotFoundException("Payment not found"));
                owningCustomer = p.getCustomer().getId();
            }
            default -> throw new BadRequestException("Unknown entity type: " + entityType);
        }
        if (!callerCustomer.equals(owningCustomer)) {
            throw new AccessDeniedException("Not allowed");
        }
    }
}
