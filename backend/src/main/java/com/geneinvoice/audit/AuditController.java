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
import com.geneinvoice.user.UserController;
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
    // The staff-directory gate, so a person's history is readable exactly where the person is (B1).
    private final UserController users;
    private final CurrentUser currentUser;

    // pendingChangeId is TRAILING, the blueprint's fixed append order. The panel renders it
    // "via approval #N" beside the "via dispute #N" it already renders, so the four-eyes story of
    // a record is readable from its own history rather than only from the queue (B2).
    public record AuditEntryDto(Long id, String entityType, Long entityId, String entityLabel,
                                String action, String beforeJson, String afterJson,
                                Long changedByUserId, String changedByUsername,
                                Long disputeId, String reason,
                                Instant createdAt, boolean derived, boolean actorHidden,
                                Long pendingChangeId) {}

    /**
     * THE TRAIL, LIVE OR AS IT STOOD ON A DATE (B3).
     *
     * <p>{@code ?asOf} is honoured here and the endpoint is in AsOfEndpoints.AS_OF_CAPABLE, but
     * nothing below changes for it and that is deliberate. The truncation and the suppression of
     * the fabricated entries both live in {@link AuditTimelineService#timeline}, where the entries
     * are, so there is no second opinion about what "as of" means in this package.
     *
     * <p>THREE THINGS STAY TODAY'S, AND EACH IS A DECISION RATHER THAN AN OVERSIGHT.
     *
     * <ul>
     *   <li>{@link #ensureCallerCanSee} and {@code withoutPocIdentity} are untouched: WHO MAY READ
     *       a trail is judged by the rights the caller holds now, which is contract clause a.1 and
     *       is the one thing as-of must never widen. A privilege somebody held in January does not
     *       come back because they asked about January.</li>
     *   <li>The usernames resolved below, and every {@code entityLabel} the timeline carries, are
     *       today's — an invoice renumbered since renders under its new number, a person renamed
     *       since under their new name. That is contract clause a.3, the same rule that renders a
     *       branch and a product under today's name on every other as-of answer.</li>
     *   <li>{@code includeRelated} walks TODAY'S relationships to decide which records' trails to
     *       gather. It needs no as-of arm because every row it can reach is stamped with the
     *       instant it was written, and the truncation drops everything after T regardless of
     *       which record led us to it.</li>
     * </ul>
     *
     * <p>A RECORD THAT DID NOT EXIST THEN IS 404 FOR AN INVOICE AND AN EMPTY TRAIL FOR THE OTHER
     * THREE, because {@code InvoiceService.requireInBook} roots on the mirror under an open context
     * and the customer, payment and promise checks root live. Both answers are honest — neither
     * shows anything that had not happened by T — and the difference is recorded rather than
     * papered over here (B3).
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
                e.disputeId(), e.reason(), e.createdAt(), e.derived(), e.actorHidden(),
                e.pendingChangeId())).toList();
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
            // A person is visible where they work, so their history is read through the same gate
            // the staff directory uses: somebody the caller shares no branch with answers exactly
            // as a missing person does (B1, AUTH-08).
            case "USER" -> readOrGone(entityId, () -> users.requireInScope(entityId),
                    userRepository::existsById);
            // The catalogue is ONE company-wide list with no branch of its own — unregioned by
            // declaration, and PRODUCT_VIEW above is the whole of its gate. Said out loud rather
            // than left to fall through (B1).
            case "PRODUCT" -> { }
            // Not "do nothing": a type added to SUPPORTED without a visibility verdict is a
            // history readable by anyone holding its view privilege, anywhere. Unreachable today,
            // because SUPPORTED is checked above, and that is the point (B1).
            default -> throw new IllegalStateException(
                    "No audit visibility rule for entity type " + entityType);
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
