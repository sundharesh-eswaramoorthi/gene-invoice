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

    /** Entity types the history panel can be anchored to. */
    public static final Set<String> SUPPORTED = Set.of(
            "INVOICE", "PAYMENT", "CUSTOMER", "PROMISE", "USER", "PRODUCT", "TASK", "AUTOMATION_RULE");

    /** Of those, the ones a customer-scoped account may ever read — and only their own rows. */
    private static final Set<String> CUSTOMER_READABLE = Set.of("INVOICE", "PAYMENT", "CUSTOMER", "PROMISE");

    /** The privilege that lets a caller see each kind of record, and so its history. */
    private static final Map<String, String> VIEW_PRIVILEGE = Map.of(
            "INVOICE", Privileges.INVOICE_VIEW,
            "PAYMENT", Privileges.PAYMENT_VIEW,
            "CUSTOMER", Privileges.CUSTOMER_VIEW,
            "PROMISE", Privileges.PROMISE_VIEW,
            "USER", Privileges.USER_VIEW,
            "PRODUCT", Privileges.PRODUCT_VIEW,
            "TASK", Privileges.TASK_VIEW,
            // A rule is read under the privilege that lets a caller read rules at all; a customer
            // login never holds it, which is also why AUTOMATION_RULE is not customer-readable.
            "AUTOMATION_RULE", Privileges.AUTOMATION_VIEW);

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

    /**
     * A record's history is readable exactly where the record is: the caller needs the record's
     * view privilege, and the service's own read by id applies the customer restriction and the
     * POC's book. Users and products carry no customer or book restriction.
     */
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

    /**
     * The record's own read decides, exactly as it does everywhere else — but a record that has
     * been deleted cannot answer, and its history is the only place the deletion is now recorded
     * (CP-04). So a "not found" is tested against the table itself: a row that is still there was
     * refused because it is outside the caller's book or not their customer's, and stays refused;
     * one that is really gone leaves the caller's view privilege, already checked above, to decide.
     * A customer login is refused either way — with no record there is nothing to say it is theirs.
     */
    private void readOrGone(Long entityId, Runnable readRecord, Predicate<Long> stillExists) {
        try {
            readRecord.run();
        } catch (NotFoundException gone) {
            if (currentUser.isCustomer() || stillExists.test(entityId)) throw gone;
        }
    }
}
