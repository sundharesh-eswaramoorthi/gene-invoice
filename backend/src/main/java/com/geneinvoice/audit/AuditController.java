package com.geneinvoice.audit;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.automation.rule.AutomationRuleRepository;
import com.geneinvoice.automation.rule.RuleService;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfAware;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.history.Records;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.product.ProductRepository;
import com.geneinvoice.promise.PaymentPromiseRepository;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.region.RegionService;
import com.geneinvoice.task.TaskRepository;
import com.geneinvoice.task.TaskService;
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
import java.util.stream.Stream;

@RestController
@AsOfAware
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    public static final Set<String> SUPPORTED = Set.of(
            "INVOICE", "PAYMENT", "CUSTOMER", "PROMISE", "USER", "PRODUCT", RegionService.ENTITY, TaskService.ENTITY,
            RuleService.ENTITY);

    private static final Set<String> CUSTOMER_READABLE = Set.of("INVOICE", "PAYMENT", "CUSTOMER", "PROMISE");

    private static final Map<String, String> VIEW_PRIVILEGE = Map.ofEntries(
            Map.entry("INVOICE", Privileges.INVOICE_VIEW),
            Map.entry("PAYMENT", Privileges.PAYMENT_VIEW),
            Map.entry("CUSTOMER", Privileges.CUSTOMER_VIEW),
            Map.entry("PROMISE", Privileges.PROMISE_VIEW),
            Map.entry("USER", Privileges.USER_VIEW),
            Map.entry("PRODUCT", Privileges.PRODUCT_VIEW),
            Map.entry(RegionService.ENTITY, Privileges.REGION_VIEW),
            Map.entry(TaskService.ENTITY, Privileges.TASK_VIEW),
            Map.entry(RuleService.ENTITY, Privileges.AUTOMATION_VIEW));

    private final AuditTimelineService timelineService;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final CustomerService customerService;
    private final PaymentPromiseService promiseService;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final CustomerRepository customerRepository;
    private final PaymentPromiseRepository promiseRepository;
    private final ProductRepository productRepository;
    private final Records records;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final RegionService regionService;
    private final RegionRepository regionRepository;
    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final RuleService ruleService;
    private final AutomationRuleRepository ruleRepository;
    private final CurrentUser currentUser;

    public record AuditEntryDto(Long id, String entityType, Long entityId, String entityLabel,
                                String action, String beforeJson, String afterJson,
                                Long changedByUserId, String changedByUsername,
                                Long disputeId, String reason,
                                Instant createdAt, boolean derived, boolean actorHidden,
                                String origin, Long ruleId, Long pendingChangeId,
                                Long approvedByUserId, String approvedByUsername, String pendingStatus) {}

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

        List<AuditTimelineService.Entry> entries = timelineService.timeline(type, entityId, includeRelated,
                currentUser.isCustomer());
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer != null) {
            Set<Long> ownLogins = new HashSet<>();
            ownLogins.add(currentUser.require().getId());
            userRepository.findByCustomerId(callerCustomer).ifPresent(u -> ownLogins.add(u.getId()));
            entries = timelineService.withoutPocIdentity(entries, ownLogins);
        }

        Set<Long> userIds = entries.stream()
                .flatMap(e -> Stream.of(e.changedByUserId(), e.approvedByUserId()))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> usernames = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
        return entries.stream().map(e -> new AuditEntryDto(e.id(), e.entityType(), e.entityId(),
                e.entityLabel(), e.action(), e.beforeJson(), e.afterJson(),
                e.changedByUserId(), usernames.get(e.changedByUserId()),
                e.disputeId(), e.reason(), e.createdAt(), e.derived(), e.actorHidden(),
                e.origin(), e.ruleId(), e.pendingChangeId(),
                e.approvedByUserId(), usernames.get(e.approvedByUserId()), e.pendingStatus())).toList();
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
                    customerRepository::existsById, this::deletedCustomerReachable);
            case "PROMISE" -> readOrGone(entityId, () -> promiseService.get(entityId),
                    promiseRepository::existsById);
            case "PRODUCT" -> readOrGone(entityId, () -> records.find(Product.class, entityId)
                    .orElseThrow(() -> new NotFoundException("Product not found")), productRepository::existsById);
            case RegionService.ENTITY -> readOrGone(entityId, () -> regionService.get(entityId),
                    regionRepository::existsById);
            case TaskService.ENTITY -> readOrGone(entityId, () -> taskService.get(entityId),
                    taskRepository::existsById);
            // A deleted rule stays (soft delete), so its trail is read through the same visibility.
            case RuleService.ENTITY -> readOrGone(entityId, () -> ruleService.get(entityId),
                    ruleRepository::existsById);
            default -> { }
        }
    }

    private void readOrGone(Long entityId, Runnable readRecord, Predicate<Long> stillExists) {
        readOrGone(entityId, readRecord, stillExists, id -> true);
    }

    /**
     * A record the caller cannot read is missing, unless it is gone altogether: a deleted record's history stays
     * readable, to those who could reach the region it was in. As of a date there is no such fallback: a record
     * with no version then (not yet created, or already deleted) has no history to show then.
     */
    private void readOrGone(Long entityId, Runnable readRecord, Predicate<Long> stillExists,
                            Predicate<Long> reachableOnceGone) {
        try {
            readRecord.run();
        } catch (NotFoundException gone) {
            if (AsOf.active() || currentUser.isCustomer() || stillExists.test(entityId)
                    || !reachableOnceGone.test(entityId)) {
                throw gone;
            }
        }
    }

    /**
     * The region a deleted customer was in, from its history; the default region for one deleted before regions,
     * or from a region since deleted itself, which nobody can hold any more.
     */
    private boolean deletedCustomerReachable(Long customerId) {
        Long regionId = auditLogRepository.findLatestRegionId(CustomerService.ENTITY, customerId)
                .filter(regionRepository::existsById)
                .orElseGet(() -> regionService.defaultRegion().getId());
        return currentUser.rights().holdsAnyIn(regionId);
    }
}
