package com.geneinvoice.dispute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.assignee.Assignee;
import com.geneinvoice.assignee.AssigneeDtos;
import com.geneinvoice.assignee.AssigneeKind;
import com.geneinvoice.assignee.AssigneeOwnerType;
import com.geneinvoice.assignee.AssigneeService;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.email.EmailDtos;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.email.RoleRef;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DisputeService {

    public static final String ENTITY = "DISPUTE";

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
    private final UserRepository userRepository;
    private final PocService pocService;
    private final ScopeResolver scopeResolver;

    /**
     * Assignees, and the role holders they read through, are taken lazily on purpose (A5).
     * {@code EmailTargets} loads a dispute through this very service, and {@code AssigneeService}
     * reads its role holders from {@code EmailTargets}, so injecting either of them outright would
     * close a bean cycle that Spring refuses to start with. An {@code ObjectProvider} defers the
     * lookup to the moment a dispute is written or rendered, by which time every bean exists.
     */
    private final ObjectProvider<AssigneeService> assignees;
    private final ObjectProvider<EmailTargets> emailTargets;

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
        // The customer opening it cannot see staff, so they cannot name one: the dispute starts
        // assigned to the seat on the record it is about, and staff move it from there (A6).
        writeAssignees(d, defaultAssignees(req.targetType()));
        auditService.record(ENTITY, d.getId(), "DISPUTE_OPENED", null, toDto(d),
                caller.getId(), d.getId(), req.reason());

        notifyAdminsOfNewDispute(d);
        return d;
    }

    /**
     * Opens a dispute with nobody logged in, which is how an automation rule raises one (A3). It is
     * the same act as {@link #open} without the two things that need a caller: there is no customer
     * login to take the customer from, so it is read from the record the dispute is about, and
     * there is no picker on screen, so the rule hands the assignees in. Everything else — one
     * pending dispute per record, the audit row, the notice to admins — is deliberately identical,
     * because a rule's dispute is an ordinary dispute from the moment it exists.
     */
    @Transactional
    public Dispute createInBackground(DisputeDtos.CreateDisputeRequest req,
                                      List<EmailDtos.EmailToken> assigneeTokens) {
        if (req == null || req.targetType() == null || req.targetId() == null) {
            throw new BadRequestException("A dispute must say what it is about");
        }
        // Nothing validates the request here: a rule's text is not a form submission, so the
        // limits the form applies are applied by hand rather than by the column refusing the row.
        if (req.reason() == null || req.reason().isBlank()) {
            throw new BadRequestException("A dispute needs a reason");
        }
        if (req.reason().length() > FieldLimits.DISPUTE_TEXT) {
            throw new BadRequestException("A dispute reason must be at most "
                    + FieldLimits.DISPUTE_TEXT + " characters");
        }
        Long customerId = customerOfTarget(req.targetType(), req.targetId());
        if (disputeRepository.existsByCustomerIdAndTargetTypeAndTargetIdAndStatus(
                customerId, req.targetType(), req.targetId(), DisputeStatus.PENDING)) {
            throw new BadRequestException("An open dispute already exists for this " +
                    req.targetType().name().toLowerCase());
        }

        Dispute d = Dispute.builder()
                .customerId(customerId)
                .openedByUserId(backgroundOpener(customerId))
                .targetType(req.targetType())
                .targetId(req.targetId())
                .reason(req.reason())
                .proposedChangeJson(req.proposedChangeJson())
                .status(DisputeStatus.PENDING)
                .build();
        d = disputeRepository.save(d);
        writeAssignees(d, assigneeTokens == null || assigneeTokens.isEmpty()
                ? defaultAssignees(req.targetType())
                : assigneeTokens);
        auditService.record(ENTITY, d.getId(), "DISPUTE_OPENED", null, toDto(d),
                currentUser.idOrNull(), d.getId(), req.reason());

        notifyAdminsOfNewDispute(d);
        return d;
    }

    /**
     * Names who is answerable for a dispute (A1). Assigning is a staff act of its own rather than a
     * field on the form that raises one: a dispute arrives from the customer's side, where staff
     * cannot be seen at all, let alone picked (AC-A8). Doing it here rather than on approve or deny
     * also means a dispute can be handed on while it is still open, which is the only time anybody
     * needs to pick it up.
     */
    @Transactional
    public Dispute setAssignees(Long id, List<EmailDtos.EmailToken> tokens) {
        Dispute d = get(id);
        if (currentUser.isCustomer()) {
            throw new AccessDeniedException("Only staff may assign a dispute");
        }
        List<String> before = AssigneeService.tokens(
                assignees.getObject().of(AssigneeOwnerType.DISPUTE, id));
        List<Assignee> after = writeAssignees(d, tokens == null ? List.of() : tokens);
        auditService.record(ENTITY, id, "DISPUTE_ASSIGNEES_SET", before,
                AssigneeService.tokens(after), currentUser.idOrNull(), id, null);
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
        // Approval is audited on the invoice or payment it changed; a denial changes nothing
        // there, so it is recorded on the dispute itself.
        auditService.record(ENTITY, d.getId(), "DISPUTE_DENIED", null, toDto(d),
                d.getResolvedByUserId(), d.getId(),
                d.getAdminNotes() == null ? d.getReason() : d.getAdminNotes());

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

    @Transactional(readOnly = true)
    public DisputeDtos.DisputeDto toDto(Dispute d) {
        return toDtos(List.of(d)).get(0);
    }

    /**
     * Every listed dispute at once, so a page costs a fixed number of queries rather than several
     * per row (A9). Three things are read together: every row's assignees in one go, every record
     * the rows are about in one read of each table, and each customer's POC book and addresses once
     * however many rows share that customer.
     *
     * <p>It used to be one dispute at a time, and a role assignee — which every dispute has from
     * the moment it is opened (A6) — sent each row off to re-read the dispute, its invoice or
     * payment, the customer, that customer's logins and that customer's POC book. A page of fifty
     * therefore ran a few hundred queries to say what the rows already knew, and the CSV export,
     * which renders every matching row, ran that many again.
     */
    @Transactional(readOnly = true)
    public List<DisputeDtos.DisputeDto> toDtos(List<Dispute> rows) {
        if (rows.isEmpty()) return List.of();
        Map<Long, List<Assignee>> byOwner = assignees.getObject()
                .byOwner(AssigneeOwnerType.DISPUTE, rows.stream().map(Dispute::getId).toList());
        Page page = page(rows, byOwner);
        return rows.stream().map(d -> toDto(d, byOwner.getOrDefault(d.getId(), List.of()), page)).toList();
    }

    /**
     * Everything a page of disputes needs beyond the rows themselves, read once for the page: the
     * invoices and payments they are about, the customers they belong to, and the record each role
     * assignee is resolved against. Nothing here depends on which row is being rendered, which is
     * precisely why it used to be read again for every one of them.
     */
    private record Page(boolean showStaff, boolean showPoc,
                        Map<Long, Invoice> invoices, Map<Long, Payment> payments,
                        Map<Long, String> customerNames,
                        Map<Long, EmailTargets.Target> roleTargets) {}

    private Page page(List<Dispute> rows, Map<Long, List<Assignee>> byOwner) {
        // Both are the same answer for every row — who is asking does not change between them —
        // and canSeePoc() reads the caller's privileges from the database, so they are asked once.
        boolean showStaff = showStaff();
        boolean showPoc = showPoc();
        Map<Long, Invoice> invoices = new HashMap<>();
        for (Invoice i : invoiceRepository.findAllById(targetIds(rows, DisputeTargetType.INVOICE))) {
            invoices.put(i.getId(), i);
        }
        Map<Long, Payment> payments = new HashMap<>();
        for (Payment p : paymentRepository.findAllById(targetIds(rows, DisputeTargetType.PAYMENT))) {
            payments.put(p.getId(), p);
        }
        Map<Long, String> names = new HashMap<>();
        for (Customer c : customerRepository.findAllById(rows.stream()
                .map(Dispute::getCustomerId).filter(Objects::nonNull).distinct().toList())) {
            names.put(c.getId(), c.getName());
        }
        // Nothing about the assignees is shown to a customer login, so nothing is looked up for
        // one either: the seats and the people in them are staff identity (AC-A8).
        return new Page(showStaff, showPoc, invoices, payments, names,
                showStaff ? roleTargets(rows, byOwner) : Map.of());
    }

    private static List<Long> targetIds(List<Dispute> rows, DisputeTargetType targetType) {
        return rows.stream().filter(d -> d.getTargetType() == targetType)
                .map(Dispute::getTargetId).filter(Objects::nonNull).distinct().toList();
    }

    private DisputeDtos.DisputeDto toDto(Dispute d, List<Assignee> assigneeRows, Page page) {
        boolean showStaff = page.showStaff();
        Target target = describeTarget(d, page);
        String customerName = page.customerNames().get(d.getCustomerId());
        return new DisputeDtos.DisputeDto(
                d.getId(), d.getCustomerId(), customerName, d.getOpenedByUserId(),
                d.getTargetType(), d.getTargetId(), target.summary(), target.number(), target.amount(),
                d.getReason(), d.getProposedChangeJson(),
                d.getStatus(), d.getAdminNotes(),
                // Who internally owns the dispute is staff identity like the resolver below it, so
                // a customer login is told nothing about it — not even that a seat is unheld (AC-A8).
                // A staff caller who may not see POC identity sees the seats themselves but nobody
                // in them, exactly as a promise's assignees read for them (AC-A6).
                showStaff ? assignees.getObject().describe(assigneeRows,
                        page.roleTargets().get(d.getId()), page.showPoc())
                        : List.<AssigneeDtos.AssigneeDto>of(),
                // The staff member who resolved it is not the customer's to see (AC-A8).
                showStaff ? d.getResolvedByUserId() : null, d.getResolvedAt(),
                d.getCreatedAt(), d.getUpdatedAt());
    }

    /**
     * Whether staff identity may be shown. A customer login never sees it (AC-A8); work running
     * with nobody logged in has no customer to hide it from, and asking whether the caller is one
     * would throw rather than answer there, so it reads the record whole (A3).
     */
    private boolean showStaff() {
        return currentUser.idOrNull() == null || !currentUser.isCustomer();
    }

    /**
     * Whether a dispute's assignees may name the people they reach. A role assignee reaches the
     * holders of the customer's POC seats and the seat on the record the dispute is about, which
     * is POC identity and is {@code POC_VIEW}'s to give — the same check the invoice list, the
     * payment list and a promise's assignees make, rather than a second rule that lets a dispute
     * hand out by name what those three withhold (AC-A6, AC-A8). Work with nobody logged in reads
     * the record whole for the reason {@link #showStaff} gives (A3).
     */
    private boolean showPoc() {
        return currentUser.idOrNull() == null || scopeResolver.canSeePoc();
    }

    /**
     * The record each role assignee is read against, for a whole page at once (A2). Only a role
     * needs one — a named person is themselves — so a page of disputes assigned to people by name
     * costs no lookup at all, and one with roles on it costs a fixed number however many rows carry
     * them.
     *
     * <p>Read without the caller's privilege and book, as a page of tasks reads its records (T2):
     * these rows have already been scoped by the query that produced them — a customer login to its
     * own customer, staff to everything — so loading each one again under that same scope would
     * only ask a question already answered, and would turn a dispute the caller may see but whose
     * invoice sits outside their book into a failure that rolled the whole page back. A dispute
     * whose record has gone is absent, which reads as a seat nobody holds — exactly how an unheld
     * seat reads anyway.
     */
    private Map<Long, EmailTargets.Target> roleTargets(List<Dispute> rows,
                                                       Map<Long, List<Assignee>> byOwner) {
        List<Long> needRecord = rows.stream()
                .filter(d -> byOwner.getOrDefault(d.getId(), List.of()).stream()
                        .anyMatch(a -> a.getKind() == AssigneeKind.ROLE))
                .map(Dispute::getId).toList();
        if (needRecord.isEmpty()) return Map.of();
        return emailTargets.getObject().loadAllInBackground(EmailEntityType.DISPUTE, needRecord);
    }

    /**
     * Makes the dispute's assignees exactly these. Unlike a promise, a dispute holds nothing lazy,
     * so the persistence context that replacing them clears costs the caller nothing: the row it
     * is holding reads the same detached as it did attached. The tokens are read before anything
     * is deleted, so a list the picker could not have produced is a 400 that changes nothing.
     */
    private List<Assignee> writeAssignees(Dispute d, List<EmailDtos.EmailToken> tokens) {
        AssigneeService service = assignees.getObject();
        return service.replace(AssigneeOwnerType.DISPUTE, d.getId(),
                service.parse(EmailEntityType.DISPUTE, d.getCustomerId(), tokens));
    }

    /**
     * Who a dispute answers to when nobody said (A6). A dispute is always about one invoice or one
     * payment, and the person who owns that record is the one who has to deal with it, so the seat
     * on the record itself is the default. It is stored as the seat and not as the person sitting
     * in it, so the dispute follows a reassignment of the invoice or payment without being touched
     * (A2), and a record with nobody on it reads as an unresolved assignee — something to fix —
     * rather than as no assignee at all.
     */
    private static List<EmailDtos.EmailToken> defaultAssignees(DisputeTargetType type) {
        EmailRole role = type == DisputeTargetType.INVOICE
                ? EmailRole.SALES_POC
                : EmailRole.COLLECTION_POC;
        return List.of(EmailDtos.EmailToken.role(RoleRef.record(role)));
    }

    /** The customer a dispute's target belongs to, for work with no caller to take it from (A3). */
    private Long customerOfTarget(DisputeTargetType type, Long id) {
        return switch (type) {
            case INVOICE -> invoiceRepository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Invoice not found"))
                    .getCustomer().getId();
            case PAYMENT -> paymentRepository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Payment not found"))
                    .getCustomer().getId();
        };
    }

    /**
     * Who a dispute raised with nobody logged in is recorded as opened by (A3). The column has
     * never been nullable, and the notice that the dispute was approved or denied goes to whoever
     * is named here, so it has to be a real person who will read it: the customer's own login where
     * they have one, else the collections person who answers for them. A customer with neither has
     * nobody to tell, so the rule is refused rather than leaving behind a dispute that answers to
     * no one.
     */
    private Long backgroundOpener(Long customerId) {
        return userRepository.findByCustomerId(customerId).map(User::getId)
                .or(() -> pocService.defaultAssignee(customerId, PocType.COLLECTION).map(User::getId))
                .orElseThrow(() -> new BadRequestException(
                        "This customer has no login and no active Collection POC, so there is "
                                + "nobody to open a dispute on their behalf"));
    }

    private void notifyAdminsOfNewDispute(Dispute d) {
        Customer cust = customerRepository.findById(d.getCustomerId()).orElse(null);
        String custName = cust == null ? "customer" : cust.getName();
        notificationService.notifyAdmins(NOTIF_OPENED,
                "New dispute from " + custName,
                d.getReason(),
                // The app has no /admin/disputes route; link where the dispute actually opens (D-53).
                "/disputes/" + d.getId());
    }

    /**
     * The dispute, locked until the transaction ends, and still pending as of that lock. Both
     * halves matter: a double-clicked Approve used to let two requests past an unlocked status
     * read and then race inside the money they both moved, so one of them came back "Unexpected
     * error" after an action that had in fact succeeded. Now the second one waits, sees the
     * status the first one wrote, and is told the plain truth instead (PPD-03).
     */
    private Dispute mustBePending(Long disputeId) {
        Dispute d = disputeRepository.findByIdForUpdate(disputeId)
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
                    .map(InvoiceDtos.InvoiceDto::from).orElse(null);
            case PAYMENT -> paymentRepository.findById(d.getTargetId())
                    .map(PaymentDtos.PaymentDto::from).orElse(null);
        };
    }

    /**
     * What a dispute is about: the record's number and amount, for the client to format, and the
     * plain summary older clients read. Number and amount are null once the record is gone.
     */
    private record Target(String number, BigDecimal amount, String summary) {}

    private Target describeTarget(Dispute d, Page page) {
        return switch (d.getTargetType()) {
            case INVOICE -> Optional.ofNullable(page.invoices().get(d.getTargetId()))
                    .map(i -> new Target(i.getInvoiceNumber(), i.getTotal(),
                            i.getInvoiceNumber() + " — " + i.getTotal()))
                    .orElse(new Target(null, null, "Invoice #" + d.getTargetId()));
            case PAYMENT -> Optional.ofNullable(page.payments().get(d.getTargetId()))
                    .map(p -> new Target("#" + p.getId(), p.getAmount(),
                            "Payment #" + p.getId() + " — " + p.getAmount()))
                    .orElse(new Target(null, null, "Payment #" + d.getTargetId()));
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
            throw new BadRequestException("The change is not valid JSON");
        }
        if (node == null || !node.isObject()) {
            throw new BadRequestException("The change must be a JSON object");
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
                // Quantity and price rules are the invoice's own; replaceItems enforces them.
                List<InvoiceDtos.LineInput> items = new ArrayList<>();
                for (JsonNode it : itemsNode) {
                    items.add(new InvoiceDtos.LineInput(wholeNumber(it, "productId"),
                            wholeInt(it, "quantity"), decimal(it, "unitPrice")));
                }
                invoiceService.replaceItems(invoiceId, items,
                        text(node, "notes", FieldLimits.INVOICE_NOTES));
            }
            case "update_notes" -> {
                Invoice inv = invoiceService.getInternal(invoiceId);
                inv.setNotes(text(node, "notes", FieldLimits.INVOICE_NOTES));
                invoiceRepository.save(inv);
            }
            default -> throw new BadRequestException("Unknown invoice action: " + action);
        }
    }

    private void applyPaymentChange(Long paymentId, String action, JsonNode node) {
        switch (action) {
            case "void" -> paymentService.voidPayment(paymentId);
            case "update_amount" -> {
                BigDecimal amount = decimal(node, "amount");
                if (amount == null) {
                    throw new BadRequestException("update_amount requires amount");
                }
                paymentService.updateAmount(paymentId, amount,
                        text(node, "method", FieldLimits.PAYMENT_METHOD),
                        text(node, "notes", FieldLimits.PAYMENT_NOTES));
            }
            case "update_meta" -> {
                Payment p = paymentRepository.findById(paymentId)
                        .orElseThrow(() -> new NotFoundException("Payment not found"));
                String method = text(node, "method", FieldLimits.PAYMENT_METHOD);
                String notes = text(node, "notes", FieldLimits.PAYMENT_NOTES);
                if (method != null) p.setMethod(method);
                if (notes != null) p.setNotes(notes);
                paymentRepository.save(p);
            }
            default -> throw new BadRequestException("Unknown payment action: " + action);
        }
    }

    // ---- reading an approved change strictly: a bad value is the approver's mistake, a 400 ----

    /** A required whole number, written as a JSON number or a numeric string. */
    private static long wholeNumber(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v != null && v.isIntegralNumber() && v.canConvertToLong()) return v.asLong();
        if (v != null && v.isTextual()) {
            try {
                return Long.parseLong(v.asText().trim());
            } catch (NumberFormatException ignored) {
                // reported below
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

    /** An optional amount, written as a JSON number or a numeric string; null when absent. */
    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.decimalValue();
        if (v.isTextual()) {
            try {
                return new BigDecimal(v.asText().trim());
            } catch (NumberFormatException ignored) {
                // reported below
            }
        }
        throw new BadRequestException(field + " must be a number");
    }

    /** Optional text; null when absent, refused when longer than the column that stores it. */
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
        // notify the user who opened the dispute (typically the customer's user account)
        notificationService.notify(
                d.getOpenedByUserId(),
                type,
                title,
                d.getAdminNotes() == null ? d.getReason() : d.getAdminNotes(),
                "/disputes/" + d.getId());
    }
}
