package com.geneinvoice.dispute;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.geneinvoice.approval.ApprovalOperation;
import com.geneinvoice.approval.Gated;
import com.geneinvoice.approval.MakerChecker;
import com.geneinvoice.approval.Outcome;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.approval.PendingChangeCloser;
import com.geneinvoice.assignee.AssignedTo;
import com.geneinvoice.assignee.AssigneeStore;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.automation.event.AutomationEvents;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.Fit;
import com.geneinvoice.common.Money;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.email.RoleRef;
import com.geneinvoice.history.Records;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.payment.PaymentStatus;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.RecordAccess;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DisputeService {

    public static final String ENTITY = "DISPUTE";

    private static final String NOTIF_OPENED = "DISPUTE_OPENED";
    private static final String NOTIF_APPROVED = "DISPUTE_APPROVED";
    private static final String NOTIF_DENIED = "DISPUTE_DENIED";

    /** The one change a dispute on a customer may propose, and any dispute may be approved with: nothing. */
    static final String NONE = "none";
    static final String OWN_RULE = "A change your own rule proposed must be approved by someone else";

    /** An internal dispute is already waiting on the record: automation raises one at a time (§5.10.11). */
    public static class AlreadyOpenException extends BadRequestException {
        public AlreadyOpenException(String message) {
            super(message);
        }
    }

    private final DisputeRepository disputeRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final CurrentUser currentUser;
    private final ScopeResolver scopeResolver;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Records records;
    private final AutomationEvents automationEvents;
    private final MakerChecker makerChecker;
    private final PendingChangeCloser pendingChangeCloser;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final AssigneeStore assigneeStore;
    private final RegionRepository regionRepository;

    @Transactional
    public Dispute open(DisputeDtos.CreateDisputeRequest req) {
        User caller = currentUser.require();
        Long callerCustomer = caller.getCustomerId();
        if (callerCustomer == null) {
            throw new AccessDeniedException("Only customers can open disputes");
        }
        if (req.targetType() == DisputeTargetType.CUSTOMER) {
            throw new BadRequestException("A dispute is raised on an invoice or a payment");
        }
        // The customer first (F1): the dispute takes its region from the locked row.
        Customer customer = customerRepository.findByIdForUpdate(callerCustomer)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        ensureTargetBelongsToCustomer(req.targetType(), req.targetId(), callerCustomer);

        // Only the customer's own disputes count: a pending internal one neither blocks nor shows itself (SC-1).
        if (disputeRepository.existsByCustomerIdAndTargetTypeAndTargetIdAndStatusAndInternalFalse(
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
                .region(customer.getRegion())
                .build();
        d = disputeRepository.save(d);
        auditService.record(ENTITY, d.getId(), "DISPUTE_OPENED", null, toDto(d),
                caller.getId(), d.getId(), req.reason());

        notificationService.notifyAdmins(NOTIF_OPENED,
                "New dispute from " + customer.getName(),
                req.reason(),
                // The app has no /admin/disputes route; link where the dispute actually opens (D-53).
                "/disputes/" + d.getId(),
                Region.idOf(d.getRegion()));

        return d;
    }

    /**
     * A dispute a rule raises for staff to review (§5.10.11), in the action's transaction, as the rule's owner. The
     * record is read as the owner (404 if out of their sight), then the customer is locked and its region read under
     * the lock; the owner needs DISPUTE_MANAGE there. One internal dispute waits on a record at a time. The assignees
     * were checked and probed by the handler, which tells them once this returns.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Dispute openInternal(InternalDisputeRequest req) {
        User caller = currentUser.require();
        if (caller.getCustomerId() != null) throw new AccessDeniedException("Not allowed");
        ViewScopeHolder.requireLive();
        Long customerId = customerOf(req.targetType(), req.targetId());
        customerRepository.findByIdForUpdate(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        // Read again under the lock: a copy loaded with the record may predate a move that committed since.
        Long regionId = customerRepository.regionIdOf(customerId).orElse(null);
        scopeResolver.requireVisible(ScopeResolver.Kind.CUSTOMER, customerId, regionId, "Customer not found");
        currentUser.requireIn(Privileges.DISPUTE_MANAGE, regionId);
        String proposal = req.proposal();
        boolean allowed = NONE.equals(proposal)
                || InternalDisputeRequest.CANCEL.equals(proposal) && req.targetType() == DisputeTargetType.INVOICE
                || InternalDisputeRequest.VOID.equals(proposal) && req.targetType() == DisputeTargetType.PAYMENT;
        if (!allowed) {
            throw new BadRequestException("A " + noun(req.targetType()) + " dispute cannot propose " + proposal);
        }
        if (disputeRepository.existsByCustomerIdAndTargetTypeAndTargetIdAndStatusAndInternalTrue(
                customerId, req.targetType(), req.targetId(), DisputeStatus.PENDING)) {
            throw new AlreadyOpenException("An internal dispute is already open on this " + noun(req.targetType()));
        }
        String reason = req.reason() == null || req.reason().isBlank() ? "Raised by an automation rule"
                : Fit.to(req.reason().trim(), FieldLimits.DISPUTE_TEXT);
        Dispute d = disputeRepository.save(Dispute.builder()
                .customerId(customerId)
                .openedByUserId(caller.getId())
                .targetType(req.targetType())
                .targetId(req.targetId())
                .reason(reason)
                .proposedChangeJson("{\"action\":\"" + proposal + "\"}")
                .status(DisputeStatus.PENDING)
                .internal(true)
                .origin(Dispute.ORIGIN_AUTOMATION)
                .ruleId(req.ruleId())
                .region(regionRepository.getReferenceById(regionId))
                .build());
        assigneeStore.replace(AssignedTo.DISPUTE, d.getId(), req.assignees());
        auditService.record(ENTITY, d.getId(), "DISPUTE_OPENED", null, toDto(d), WriteOrigins.actor(currentUser),
                d.getId(), reason, regionId);
        return d;
    }

    /** The customer of the record a rule's dispute is about, read as the caller: out of their sight is a 404. */
    private Long customerOf(DisputeTargetType type, Long id) {
        return switch (type) {
            case INVOICE -> invoiceService.get(id).getCustomer().getId();
            case PAYMENT -> paymentService.get(id).getCustomer().getId();
            case CUSTOMER -> id;
        };
    }

    private static String noun(DisputeTargetType type) {
        return type.name().toLowerCase();
    }

    /**
     * The roles a dispute may be assigned to (§5.9.1): the customer-level ones everywhere, plus the record's own POC
     * on an invoice (its Sales POC) or a payment (its Collection POC).
     */
    public static List<RoleRef> assigneeRoles(DisputeTargetType type) {
        List<RoleRef> roles = new ArrayList<>(List.of(RoleRef.customer(EmailRole.CUSTOMER_SUCCESS_POC),
                RoleRef.customer(EmailRole.COLLECTION_POC)));
        switch (type) {
            case INVOICE -> roles.add(RoleRef.record(EmailRole.SALES_POC));
            case PAYMENT -> roles.add(RoleRef.record(EmailRole.COLLECTION_POC));
            case CUSTOMER -> { }
        }
        return List.copyOf(roles);
    }

    /**
     * Makes the dispute's assignees exactly {@code tokens} (§5.10.11): DISPUTE_MANAGE in its region, customer then
     * dispute locked. The caller has already made sure each person named can see the dispute; this returns the
     * tokens added, for the caller to tell.
     */
    @Transactional
    public List<EmailToken> setAssignees(Long disputeId, List<EmailToken> tokens) {
        Dispute d = locked(disputeId);
        ViewScopeHolder.requireLive();
        currentUser.requireIn(Privileges.DISPUTE_MANAGE, Region.idOf(d.getRegion()));
        List<EmailToken> wanted = assigneeStore.validate(EmailEntityType.DISPUTE, tokens,
                assigneeRoles(d.getTargetType()));
        List<EmailToken> before = assigneeStore.list(AssignedTo.DISPUTE, disputeId);
        if (keys(before).equals(keys(wanted))) return List.of();
        List<EmailToken> added = assigneeStore.replace(AssignedTo.DISPUTE, disputeId, wanted);
        auditService.record(ENTITY, disputeId, "DISPUTE_ASSIGNEES_CHANGED", assigneeKeys(before),
                assigneeKeys(wanted), WriteOrigins.actor(currentUser), disputeId, null, Region.idOf(d.getRegion()));
        return added;
    }

    private static List<String> keys(List<EmailToken> tokens) {
        return tokens.stream().map(AssigneeStore::key).sorted().toList();
    }

    private static Map<String, Object> assigneeKeys(List<EmailToken> tokens) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assignees", tokens.stream().map(AssigneeStore::key).toList());
        return out;
    }

    @Transactional
    public Dispute approve(Long disputeId, DisputeDtos.ResolveDisputeRequest req) {
        return approveOrHold(disputeId, req).appliedOrThrow();
    }

    /**
     * Resolves the dispute with the change it proposes (or the one the approver rewrote it to). A change that
     * moves more money than the region's DISPUTE_APPROVE limit is held for someone else to approve, with the
     * approver as its maker, and the dispute stays pending (B2). Approving a customer's own proposal as it
     * stands is already the second pair of eyes when the approver holds the approval right there (§5.11.8),
     * unless they last set that customer login's password (SC-10).
     */
    @Transactional
    public Outcome<Dispute> approveOrHold(Long disputeId, DisputeDtos.ResolveDisputeRequest req) {
        Dispute d = mustBePending(disputeId);
        User caller = currentUser.require();
        String changeJson = req != null && req.appliedChangeJson() != null && !req.appliedChangeJson().isBlank()
                ? req.appliedChangeJson()
                : d.getProposedChangeJson();
        ParsedChange change = parse(d, changeJson);
        // The owner of the rule that raised an internal dispute is never the one to make the change it proposes.
        if (d.isInternal() && !NONE.equals(change.action()) && caller.getId().equals(d.getOpenedByUserId())) {
            throw new BadRequestException(OWN_RULE);
        }
        BigDecimal amount = measure(d, change);

        boolean asProposed = req == null || req.appliedChangeJson() == null || req.appliedChangeJson().isBlank()
                || change.node().equals(treeOrNull(d.getProposedChangeJson()));
        boolean checkerStep = asProposed && !caller.getId().equals(d.getOpenedByUserId())
                && !caller.getId().equals(credentialsSetBy(d))
                && currentUser.hasIn(Privileges.CHANGE_APPROVE, Region.idOf(d.getRegion()));
        Optional<PendingChange> held = makerChecker.gate(approveGate(d, change, amount, req, checkerStep));
        if (held.isPresent()) {
            // Held from an internal dispute: internal work, never shown to a customer login (SC-1).
            if (d.isInternal()) held.get().setInternalSource(true);
            return Outcome.held(held.get());
        }

        Object before = snapshotTarget(d);
        apply(d, change);
        Object after = snapshotTarget(d);

        d.setStatus(DisputeStatus.APPROVED);
        d.setResolvedAt(clock.instant());
        d.setResolvedByUserId(caller.getId());
        if (req != null && req.adminNotes() != null) d.setAdminNotes(req.adminNotes());
        d = disputeRepository.save(d);

        auditService.record(
                d.getTargetType().name(), d.getTargetId(),
                "DISPUTE_APPROVED",
                before, after,
                d.getResolvedByUserId(), d.getId(),
                d.getAdminNotes() == null ? d.getReason() : d.getAdminNotes());

        notifyCustomerOfResolution(d, NOTIF_APPROVED, "Dispute approved");
        pendingChangeCloser.closeForRecord(ENTITY, d.getId(), "Dispute resolved");
        return Outcome.applied(d);
    }

    /**
     * What the approver of a held resolution is shown: the target's money before and after. The request is kept
     * with the change that was chosen written into it, every new line's price pinned, so the approval makes
     * exactly that change whatever the catalogue says by then.
     */
    private Gated approveGate(Dispute d, ParsedChange change, BigDecimal amount,
                              DisputeDtos.ResolveDisputeRequest req, boolean checkerStep) {
        DisputeChangeApplier.DisputeTargetView base = targetView(d);
        String customerName = customerRepository.findById(d.getCustomerId()).map(Customer::getName).orElse(null);
        String target = describeTarget(d);
        return new Gated(ApprovalOperation.DISPUTE_APPROVE, d.getId(), "Dispute #" + d.getId(),
                d.getTargetType().name(), d.getTargetId(), d.getId(), d.getCustomerId(), customerName,
                Region.idOf(d.getRegion()), targetPocId(d), amount,
                new DisputeDtos.ResolveDisputeRequest(req == null ? null : req.adminNotes(), pinned(change)),
                base, proposedView(base, change),
                "Approve dispute #" + d.getId() + " on " + target + ": " + change.action()
                        + " (" + Money.format(amount) + ")",
                checkerStep);
    }

    /**
     * Whoever last set the password of the customer login that opened the dispute (SC-10). A login from before
     * {@code credentials_set_by_user_id} was kept has none recorded, so the staff member who created the
     * customer, and chose the password it started with, stands in. Null when the opener is not a customer login.
     */
    private Long credentialsSetBy(Dispute d) {
        User opener = userRepository.findById(d.getOpenedByUserId()).orElse(null);
        if (opener == null || opener.getCustomerId() == null) return null;
        if (opener.getCredentialsSetByUserId() != null) return opener.getCredentialsSetByUserId();
        return auditLogRepository.findFirstActor(CustomerService.ENTITY, opener.getCustomerId(), "CUSTOMER_CREATED")
                .orElse(null);
    }

    /** The change as JSON, a replace_items line without a price given the one {@link #measure} used (§5.11.1). */
    private String pinned(ParsedChange change) {
        if (!"replace_items".equals(change.action())) return change.json();
        List<InvoiceDtos.LineInput> priced = invoiceService.priced(change.items());
        ObjectNode node = change.node().deepCopy();
        ArrayNode items = (ArrayNode) node.get("items");
        for (int i = 0; i < items.size(); i++) {
            ((ObjectNode) items.get(i)).put("unitPrice", priced.get(i).unitPrice());
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the change", e);
        }
    }

    @Transactional
    public Dispute deny(Long disputeId, DisputeDtos.ResolveDisputeRequest req) {
        Dispute d = mustBePending(disputeId);
        d.setStatus(DisputeStatus.DENIED);
        d.setResolvedAt(clock.instant());
        d.setResolvedByUserId(currentUser.require().getId());
        if (req != null && req.adminNotes() != null) d.setAdminNotes(req.adminNotes());
        d = disputeRepository.save(d);
        auditService.record(ENTITY, d.getId(), "DISPUTE_DENIED", null, toDto(d),
                d.getResolvedByUserId(), d.getId(),
                d.getAdminNotes() == null ? d.getReason() : d.getAdminNotes());

        notifyCustomerOfResolution(d, NOTIF_DENIED, "Dispute denied");
        // A resolution someone asked to approve is moot once the dispute is denied.
        pendingChangeCloser.closeForRecord(ENTITY, d.getId(), "Dispute denied");
        return d;
    }

    /** The dispute, or as of a date the dispute as it was then (not found if it did not exist yet). */
    @Transactional(readOnly = true)
    public Dispute get(Long id) {
        Dispute d = records.find(Dispute.class, id)
                .orElseThrow(() -> new NotFoundException("Dispute not found"));
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer != null && !callerCustomer.equals(d.getCustomerId())) {
            throw new AccessDeniedException("Not allowed");
        }
        scopeResolver.requireVisible(ScopeResolver.Kind.DISPUTE, id, Region.idOf(d.getRegion()),
                "Dispute not found");
        return d;
    }

    /** An audit snapshot: no capability flags, so a caller's rights never land in audit JSON. */
    public DisputeDtos.DisputeDto toDto(Dispute d) {
        return toDto(d, null);
    }

    public DisputeDtos.DisputeDto toDto(Dispute d, RecordAccess access) {
        return toDtos(List.of(d), access).get(0);
    }

    /**
     * As of a date, the customer and the target are named as they were then. The customers, invoices and payments
     * behind a page are read once per kind, not once per row (§5.7.15).
     */
    public List<DisputeDtos.DisputeDto> toDtos(List<Dispute> disputes, RecordAccess access) {
        if (disputes.isEmpty()) return List.of();
        Map<Long, String> customerNames = new HashMap<>();
        for (Customer c : records.findAll(Customer.class, disputes.stream().map(Dispute::getCustomerId).toList())) {
            customerNames.put(c.getId(), c.getName());
        }
        Map<Long, Target> invoices = new HashMap<>();
        for (Invoice i : records.findAll(Invoice.class, targetIds(disputes, DisputeTargetType.INVOICE))) {
            invoices.put(i.getId(), new Target(i.getInvoiceNumber(), i.getTotal(),
                    i.getInvoiceNumber() + " — " + i.getTotal()));
        }
        Map<Long, Target> payments = new HashMap<>();
        for (Payment p : records.findAll(Payment.class, targetIds(disputes, DisputeTargetType.PAYMENT))) {
            payments.put(p.getId(), new Target("#" + p.getId(), p.getAmount(),
                    "Payment #" + p.getId() + " — " + p.getAmount()));
        }
        for (Customer c : records.findAll(Customer.class, targetIds(disputes, DisputeTargetType.CUSTOMER))) {
            customerNames.put(c.getId(), c.getName());
        }
        boolean staff = !currentUser.isCustomer();
        return disputes.stream().map(d -> {
            Target target = switch (d.getTargetType()) {
                case INVOICE -> invoices.getOrDefault(d.getTargetId(),
                        new Target(null, null, "Invoice #" + d.getTargetId()));
                case PAYMENT -> payments.getOrDefault(d.getTargetId(),
                        new Target(null, null, "Payment #" + d.getTargetId()));
                case CUSTOMER -> new Target(null, null, customerNames.containsKey(d.getTargetId())
                        ? "Customer " + customerNames.get(d.getTargetId()) : "Customer #" + d.getTargetId());
            };
            return new DisputeDtos.DisputeDto(
                    d.getId(), d.getCustomerId(), customerNames.get(d.getCustomerId()), d.getOpenedByUserId(),
                    d.getTargetType(), d.getTargetId(), target.summary(), target.number(), target.amount(),
                    d.getReason(), d.getProposedChangeJson(),
                    d.getStatus(), d.getAdminNotes(),
                    currentUser.isCustomer() ? null : d.getResolvedByUserId(), d.getResolvedAt(),
                    d.getCreatedAt(), d.getUpdatedAt(),
                    Region.idOf(d.getRegion()), Region.codeOf(d.getRegion()), Region.nameOf(d.getRegion()),
                    access == null ? null : access.canResolve(d), null,
                    staff ? d.isInternal() : null, staff ? d.getOrigin() : null, staff ? d.getRuleId() : null, null);
        }).toList();
    }

    private static List<Long> targetIds(List<Dispute> disputes, DisputeTargetType type) {
        return disputes.stream().filter(d -> d.getTargetType() == type).map(Dispute::getTargetId).toList();
    }

    /**
     * A dispute to decide, locked customer first (§5.11.5; CF-8, SC-19): its customer id is read without a lock,
     * then the customer row is locked, then the dispute, so an approval serialises with a region move and every
     * other writer on the customer. One the caller cannot see is missing (404), a decided one a 400, and lacking
     * DISPUTE_MANAGE in its region a 403.
     */
    private Dispute mustBePending(Long disputeId) {
        Dispute d = locked(disputeId);
        if (d.getStatus() != DisputeStatus.PENDING) {
            throw new BadRequestException("Dispute already resolved");
        }
        ViewScopeHolder.requireLive();
        currentUser.requireIn(Privileges.DISPUTE_MANAGE, Region.idOf(d.getRegion()));
        return d;
    }

    /** The dispute locked customer first, then itself; one the caller cannot see is missing (404). */
    private Dispute locked(Long disputeId) {
        Long customerId = disputeRepository.customerIdOf(disputeId)
                .orElseThrow(() -> new NotFoundException("Dispute not found"));
        customerRepository.findByIdForUpdate(customerId);
        Dispute d = disputeRepository.findByIdForUpdate(disputeId)
                .orElseThrow(() -> new NotFoundException("Dispute not found"));
        scopeResolver.requireVisible(ScopeResolver.Kind.DISPUTE, disputeId, Region.idOf(d.getRegion()),
                "Dispute not found");
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
            case CUSTOMER -> {
                if (!id.equals(customerId)) throw new AccessDeniedException("Not allowed");
            }
        }
    }

    private Object snapshotTarget(Dispute d) {
        return switch (d.getTargetType()) {
            case INVOICE -> invoiceRepository.findById(d.getTargetId())
                    .map(InvoiceDtos.InvoiceDto::from).orElse(null);
            case PAYMENT -> paymentRepository.findById(d.getTargetId())
                    .map(PaymentDtos.PaymentDto::from).orElse(null);
            case CUSTOMER -> customerRepository.findById(d.getTargetId())
                    .map(CustomerService::snapshotOf).orElse(null);
        };
    }

    private record Target(String number, BigDecimal amount, String summary) {}

    /**
     * A resolution read and checked, before anything is changed: the effective JSON, its action, and the values
     * the action takes. Every refusal the change can meet as written is raised here.
     */
    record ParsedChange(String json, JsonNode node, String action, List<InvoiceDtos.LineInput> items,
                        String notes, BigDecimal amount, String method) {}

    ParsedChange parse(Dispute d, String changeJson) {
        if (changeJson == null || changeJson.isBlank()) {
            // A dispute about the customer as a whole asks for nothing to be changed: approving acknowledges it.
            if (d.getTargetType() == DisputeTargetType.CUSTOMER) {
                String none = "{\"action\":\"" + NONE + "\"}";
                return new ParsedChange(none, treeOrNull(none), NONE, null, null, null, null);
            }
            throw new BadRequestException("No change specified for approval");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(changeJson);
        } catch (Exception e) {
            throw new BadRequestException("The change is not valid JSON");
        }
        if (node == null || !node.isObject()) {
            throw new BadRequestException("The change must be a JSON object");
        }
        String action = node.path("action").asText("");
        // Approving with no change acknowledges the dispute, whatever it is about (§5.10.11).
        if (NONE.equals(action)) return new ParsedChange(changeJson, node, action, null, null, null, null);
        return switch (d.getTargetType()) {
            case INVOICE -> switch (action) {
                case "cancel" -> new ParsedChange(changeJson, node, action, null, null, null, null);
                case "replace_items" -> {
                    JsonNode itemsNode = node.path("items");
                    if (!itemsNode.isArray() || itemsNode.isEmpty()) {
                        throw new BadRequestException("replace_items requires non-empty items array");
                    }
                    List<InvoiceDtos.LineInput> items = new ArrayList<>();
                    for (JsonNode it : itemsNode) {
                        items.add(new InvoiceDtos.LineInput(wholeNumber(it, "productId"),
                                wholeInt(it, "quantity"), decimal(it, "unitPrice")));
                    }
                    yield new ParsedChange(changeJson, node, action, items,
                            text(node, "notes", FieldLimits.INVOICE_NOTES), null, null);
                }
                case "update_notes" -> new ParsedChange(changeJson, node, action, null,
                        text(node, "notes", FieldLimits.INVOICE_NOTES), null, null);
                default -> throw new BadRequestException("Unknown invoice action: " + action);
            };
            case PAYMENT -> switch (action) {
                case "void" -> new ParsedChange(changeJson, node, action, null, null, null, null);
                case "update_amount" -> {
                    BigDecimal amount = decimal(node, "amount");
                    if (amount == null) {
                        throw new BadRequestException("update_amount requires amount");
                    }
                    yield new ParsedChange(changeJson, node, action, null,
                            text(node, "notes", FieldLimits.PAYMENT_NOTES), amount,
                            text(node, "method", FieldLimits.PAYMENT_METHOD));
                }
                case "update_meta" -> new ParsedChange(changeJson, node, action, null,
                        text(node, "notes", FieldLimits.PAYMENT_NOTES), null,
                        text(node, "method", FieldLimits.PAYMENT_METHOD));
                default -> throw new BadRequestException("Unknown payment action: " + action);
            };
            case CUSTOMER -> throw new BadRequestException("Only 'none' is allowed on a customer dispute");
        };
    }

    /**
     * How much money the resolution moves (§5.11.1): a cancelled invoice's total, a new set of lines' change in
     * total, a voided payment's amount, a payment's change of amount; notes and details move nothing.
     */
    BigDecimal measure(Dispute d, ParsedChange change) {
        return switch (change.action()) {
            case "cancel" -> invoiceService.getInternal(d.getTargetId()).getTotal();
            case "replace_items" -> invoiceService.totalFor(change.items())
                    .subtract(invoiceService.getInternal(d.getTargetId()).getTotal()).abs();
            case "void" -> payment(d.getTargetId()).getAmount();
            case "update_amount" -> change.amount().subtract(payment(d.getTargetId()).getAmount()).abs();
            default -> BigDecimal.ZERO;
        };
    }

    /** Makes the change. The internals it calls are never gated: an approved resolution is never held twice. */
    void apply(Dispute d, ParsedChange change) {
        Long targetId = d.getTargetId();
        switch (change.action()) {
            case NONE -> { }
            case "cancel" -> invoiceService.cancelWithRefund(targetId);
            case "replace_items" -> invoiceService.replaceItems(targetId, change.items(), change.notes());
            case "update_notes" -> {
                Invoice inv = invoiceService.getInternal(targetId);
                inv.setNotes(change.notes());
                invoiceRepository.save(inv);
                automationEvents.updated(TriggerEntity.INVOICE, targetId, "DETAILS");
            }
            case "void" -> paymentService.voidPayment(targetId);
            case "update_amount" -> paymentService.updateAmount(targetId, change.amount(), change.method(),
                    change.notes());
            case "update_meta" -> {
                Payment p = payment(targetId);
                if (change.method() != null) p.setMethod(change.method());
                if (change.notes() != null) p.setNotes(change.notes());
                paymentRepository.save(p);
                automationEvents.updated(TriggerEntity.PAYMENT, targetId, "DETAILS");
            }
            default -> throw new BadRequestException("Unknown action: " + change.action());
        }
    }

    private Payment payment(Long id) {
        return paymentRepository.findById(id).orElseThrow(() -> new NotFoundException("Payment not found"));
    }

    /** The target's money now, as the approver of a held resolution compares it. */
    DisputeChangeApplier.DisputeTargetView targetView(Dispute d) {
        return switch (d.getTargetType()) {
            case INVOICE -> invoiceRepository.findById(d.getTargetId())
                    .map(inv -> new DisputeChangeApplier.DisputeTargetView(d.getTargetType(), inv.getTotal(),
                            inv.getPaidAmount(), inv.getStatus().name(), null))
                    .orElse(null);
            case PAYMENT -> paymentRepository.findById(d.getTargetId())
                    .map(p -> new DisputeChangeApplier.DisputeTargetView(d.getTargetType(), null, null,
                            p.getStatus().name(), p.getAmount()))
                    .orElse(null);
            // A customer has no money of its own that a dispute on it could move.
            case CUSTOMER -> null;
        };
    }

    /** What the resolution would leave the target's money at, for the approver. */
    private DisputeChangeApplier.DisputeTargetView proposedView(DisputeChangeApplier.DisputeTargetView base,
                                                                ParsedChange change) {
        if (base == null) return null;
        return switch (change.action()) {
            case "cancel" -> new DisputeChangeApplier.DisputeTargetView(base.targetType(), base.total(),
                    BigDecimal.ZERO, InvoiceStatus.CANCELLED.name(), null);
            case "replace_items" -> {
                BigDecimal total = invoiceService.totalFor(change.items());
                BigDecimal paid = base.paidAmount().min(total);
                InvoiceStatus status = total.subtract(paid).signum() <= 0 ? InvoiceStatus.FULLY_PAID
                        : paid.signum() > 0 ? InvoiceStatus.PARTIALLY_PAID : InvoiceStatus.UNPAID;
                yield new DisputeChangeApplier.DisputeTargetView(base.targetType(), total, paid, status.name(), null);
            }
            case "void" -> new DisputeChangeApplier.DisputeTargetView(base.targetType(), null, null,
                    PaymentStatus.VOIDED.name(), base.amount());
            case "update_amount" -> new DisputeChangeApplier.DisputeTargetView(base.targetType(), null, null,
                    base.status(), change.amount());
            default -> base;
        };
    }

    /** The target's POC: whoever an approval about it concerns in the book. */
    private Long targetPocId(Dispute d) {
        return switch (d.getTargetType()) {
            case INVOICE -> invoiceRepository.findById(d.getTargetId())
                    .map(i -> i.getSalesPoc() == null ? null : i.getSalesPoc().getId()).orElse(null);
            case PAYMENT -> paymentRepository.findById(d.getTargetId())
                    .map(p -> p.getCollectionPoc() == null ? null : p.getCollectionPoc().getId()).orElse(null);
            case CUSTOMER -> null;
        };
    }

    private String describeTarget(Dispute d) {
        return switch (d.getTargetType()) {
            case INVOICE -> invoiceRepository.findById(d.getTargetId()).map(Invoice::getInvoiceNumber)
                    .orElse("invoice #" + d.getTargetId());
            case PAYMENT -> "payment #" + d.getTargetId();
            case CUSTOMER -> "Customer " + customerRepository.findById(d.getTargetId()).map(Customer::getName)
                    .orElse("#" + d.getTargetId());
        };
    }

    private JsonNode treeOrNull(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static long wholeNumber(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v != null && v.isIntegralNumber() && v.canConvertToLong()) return v.asLong();
        if (v != null && v.isTextual()) {
            try {
                return Long.parseLong(v.asText().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        throw new BadRequestException(field + " must be a whole number");
    }

    private static int wholeInt(JsonNode node, String field) {
        long value = wholeNumber(node, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new BadRequestException(field + " is out of range");
        }
        return (int) value;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.decimalValue();
        if (v.isTextual()) {
            try {
                return new BigDecimal(v.asText().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        throw new BadRequestException(field + " must be a number");
    }

    private static String text(JsonNode node, String field, int maxLength) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String value = v.asText();
        if (value.length() > maxLength) {
            throw new BadRequestException(field + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    private void notifyCustomerOfResolution(Dispute d, String type, String title) {
        notificationService.notify(
                d.getOpenedByUserId(),
                type,
                title,
                d.getAdminNotes() == null ? d.getReason() : d.getAdminNotes(),
                "/disputes/" + d.getId(),
                Region.idOf(d.getRegion()));
    }
}
