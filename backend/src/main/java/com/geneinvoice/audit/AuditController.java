package com.geneinvoice.audit;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    /** Entity types the history panel can be anchored to. */
    public static final Set<String> SUPPORTED = Set.of(
            "INVOICE", "PAYMENT", "CUSTOMER", "PROMISE", "USER", "PRODUCT");

    /** Of those, the ones a customer-scoped account may ever read — and only their own rows. */
    private static final Set<String> CUSTOMER_READABLE = Set.of("INVOICE", "PAYMENT", "CUSTOMER", "PROMISE");

    private final AuditTimelineService timelineService;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentPromiseRepository promiseRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    /**
     * One timeline row. {@code entityLabel} names the record the row is about (an invoice number,
     * "Payment #12"), which matters once related records are mixed in; {@code derived} marks an
     * event reconstructed from a record older than its audit trail, which has no audit id;
     * {@code actorHidden} marks a row whose actor is withheld from this viewer.
     */
    public record AuditEntryDto(Long id, String entityType, Long entityId, String entityLabel,
                                String action, String beforeJson, String afterJson,
                                Long changedByUserId, String changedByUsername,
                                Long disputeId, String reason,
                                Instant createdAt, boolean derived, boolean actorHidden) {}

    /**
     * A record's history, newest first. With {@code includeRelated} it also covers the records
     * hanging off it — see {@link AuditTimelineService}.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.AUDIT_VIEW + "')")
    public List<AuditEntryDto> history(@RequestParam String entityType,
                                       @RequestParam Long entityId,
                                       @RequestParam(defaultValue = "false") boolean includeRelated) {
        String type = entityType == null ? "" : entityType.toUpperCase();
        if (!SUPPORTED.contains(type)) {
            throw new BadRequestException("Unknown entity type: " + entityType
                    + " (expected one of " + SUPPORTED + ")");
        }
        ensureCallerCanSee(type, entityId);

        List<AuditTimelineService.Entry> entries = timelineService.timeline(type, entityId, includeRelated);
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer != null) {
            // A customer login sees its own account's actions named, and nobody else's.
            Set<Long> ownLogins = new HashSet<>();
            ownLogins.add(currentUser.require().getId());
            userRepository.findByCustomerId(callerCustomer).ifPresent(u -> ownLogins.add(u.getId()));
            entries = timelineService.withoutPocIdentity(entries, ownLogins);
        }

        Set<Long> userIds = entries.stream().map(AuditTimelineService.Entry::changedByUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> usernames = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
        return entries.stream().map(e -> new AuditEntryDto(e.id(), e.entityType(), e.entityId(),
                e.entityLabel(), e.action(), e.beforeJson(), e.afterJson(),
                e.changedByUserId(), usernames.get(e.changedByUserId()),
                e.disputeId(), e.reason(), e.createdAt(), e.derived(), e.actorHidden())).toList();
    }

    private void ensureCallerCanSee(String entityType, Long entityId) {
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer == null) return;
        if (!CUSTOMER_READABLE.contains(entityType)) {
            throw new AccessDeniedException("Not allowed");
        }
        Long owningCustomer = switch (entityType) {
            case "INVOICE" -> {
                Invoice inv = invoiceRepository.findById(entityId)
                        .orElseThrow(() -> new NotFoundException("Invoice not found"));
                yield inv.getCustomer().getId();
            }
            case "PAYMENT" -> {
                Payment p = paymentRepository.findById(entityId)
                        .orElseThrow(() -> new NotFoundException("Payment not found"));
                yield p.getCustomer().getId();
            }
            case "PROMISE" -> {
                PaymentPromise promise = promiseRepository.findById(entityId)
                        .orElseThrow(() -> new NotFoundException("Payment promise not found"));
                yield promise.getCustomer().getId();
            }
            case "CUSTOMER" -> entityId;
            default -> throw new BadRequestException("Unknown entity type: " + entityType);
        };
        if (!callerCustomer.equals(owningCustomer)) {
            throw new AccessDeniedException("Not allowed");
        }
    }
}
