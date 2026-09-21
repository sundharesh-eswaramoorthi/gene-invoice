package com.geneinvoice.audit;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.promise.PaymentPromiseRepository;
import com.geneinvoice.promise.PaymentPromiseService;
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
import java.util.function.Predicate;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    public static final Set<String> SUPPORTED = Set.of(
            "INVOICE", "PAYMENT", "CUSTOMER", "PROMISE", "USER", "PRODUCT");

    private static final Set<String> CUSTOMER_READABLE = Set.of("INVOICE", "PAYMENT", "CUSTOMER", "PROMISE");

    private static final Map<String, String> VIEW_PRIVILEGE = Map.of(
            "INVOICE", Privileges.INVOICE_VIEW,
            "PAYMENT", Privileges.PAYMENT_VIEW,
            "CUSTOMER", Privileges.CUSTOMER_VIEW,
            "PROMISE", Privileges.PROMISE_VIEW,
            "USER", Privileges.USER_VIEW,
            "PRODUCT", Privileges.PRODUCT_VIEW);

    private final AuditTimelineService timelineService;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final CustomerService customerService;
    private final PaymentPromiseService promiseService;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final CustomerRepository customerRepository;
    private final PaymentPromiseRepository promiseRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public record AuditEntryDto(Long id, String entityType, Long entityId, String entityLabel,
                                String action, String beforeJson, String afterJson,
                                Long changedByUserId, String changedByUsername,
                                Long disputeId, String reason,
                                Instant createdAt, boolean derived, boolean actorHidden) {}

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
        if (!currentUser.has(VIEW_PRIVILEGE.get(entityType))) {
            throw new AccessDeniedException("Not allowed");
        }
        if (currentUser.isCustomer() && !CUSTOMER_READABLE.contains(entityType)) {
            throw new AccessDeniedException("Not allowed");
        }
        switch (entityType) {
            case "INVOICE" -> readOrGone(entityId, () -> invoiceService.get(entityId),
                    invoiceRepository::existsById);
            case "PAYMENT" -> readOrGone(entityId, () -> paymentService.get(entityId),
                    paymentRepository::existsById);
            case "CUSTOMER" -> readOrGone(entityId, () -> customerService.get(entityId),
                    customerRepository::existsById);
            case "PROMISE" -> readOrGone(entityId, () -> promiseService.get(entityId),
                    promiseRepository::existsById);
            default -> { }
        }
    }

    private void readOrGone(Long entityId, Runnable readRecord, Predicate<Long> stillExists) {
        try {
            readRecord.run();
        } catch (NotFoundException gone) {
            if (currentUser.isCustomer() || stillExists.test(entityId)) throw gone;
        }
    }
}
