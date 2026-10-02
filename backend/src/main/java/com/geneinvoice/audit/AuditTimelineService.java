package com.geneinvoice.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.approval.PendingChangeRepository;
import com.geneinvoice.approval.PendingStatus;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeRepository;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.history.LiveReadGuard;
import com.geneinvoice.history.Records;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentAllocation;
import com.geneinvoice.payment.PaymentAllocationRepository;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseRepository;
import com.geneinvoice.task.Task;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.task.TaskRepository;
import com.geneinvoice.task.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class AuditTimelineService {

    private static final int CHUNK = 1000;

    private static final Comparator<Instant> OLDEST_FIRST =
            Comparator.nullsLast(Comparator.<Instant>naturalOrder());

    private static final Comparator<Entry> NEWEST_FIRST = Comparator
            .comparing(Entry::createdAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
            .thenComparing(Entry::id, Comparator.nullsLast(Comparator.<Long>reverseOrder()));

    /** {@code pendingStatus}: where the pending change a row is about stands (as of a date, stood then). */
    public record Entry(Long id, String entityType, Long entityId, String entityLabel,
                        String action, String beforeJson, String afterJson,
                        Long changedByUserId, Long disputeId, String reason,
                        Instant createdAt, boolean derived, boolean actorHidden,
                        String origin, Long ruleId, Long pendingChangeId, Long approvedByUserId,
                        String pendingStatus) {

        Entry withPendingStatus(String status) {
            return new Entry(id, entityType, entityId, entityLabel, action, beforeJson, afterJson, changedByUserId,
                    disputeId, reason, createdAt, derived, actorHidden, origin, ruleId, pendingChangeId,
                    approvedByUserId, status);
        }

        /** A customer's view of a row an internal dispute caused: the change stays, the dispute and its reason go. */
        Entry withoutDispute() {
            return new Entry(id, entityType, entityId, entityLabel, action, beforeJson, afterJson, changedByUserId,
                    null, null, createdAt, derived, actorHidden, origin, ruleId, pendingChangeId, approvedByUserId,
                    pendingStatus);
        }

        Entry withoutPendingChange() {
            return new Entry(id, entityType, entityId, entityLabel, action, beforeJson, afterJson, changedByUserId,
                    disputeId, reason, createdAt, derived, actorHidden, origin, ruleId, null, null, null);
        }
    }

    private final AuditLogRepository auditRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAllocationRepository allocationRepository;
    private final PaymentPromiseRepository promiseRepository;
    private final DisputeRepository disputeRepository;
    private final TaskRepository taskRepository;
    private final TaskService taskService;
    private final ObjectMapper objectMapper;
    private final Records records;
    private final PendingChangeRepository pendingChangeRepository;
    private final CurrentUser currentUser;
    private final ScopeResolver scopeResolver;
    private final TableQueryExecutor queryExecutor;

    /**
     * A record's trail, newest first, with the entries derived for what was done before auditing began. As of a
     * date, the entries made by the end of that day (§5.7.17): the related records are found through today's
     * relationships, read live on purpose, and each is then read as it was that day, so an entry derived from a
     * record (and every label) shows it as it was then, and one that did not exist then derives nothing.
     *
     * <p>{@code customerView} is a customer login's trail (SC-1): tasks and internal disputes are internal work, so
     * none is taken in as a related record and no row about one is shown; nor is a request or decision about a change
     * an internal dispute raised, though the row the applied change wrote stays, without its link to the change, and
     * a row on the record that an internal dispute's resolution wrote keeps the change without the dispute or its
     * reason. Staff take in only the related records they could open (SC-2): an invoice, payment, promise, dispute
     * or task outside their book or regions brings no row, and an allocation only when both its payment and its
     * invoice are theirs to see.
     *
     * <p>A row about a pending change says where the change stands, from one read of the changes named (§5.11.11).
     */
    @Transactional(readOnly = true)
    public List<Entry> timeline(String entityType, Long entityId, boolean includeRelated, boolean customerView) {
        AsOfPoint point = AsOf.current();
        List<Entry> entries;
        if (point == null) {
            entries = entries(related(entityType, entityId, includeRelated, customerView));
        } else {
            Related found = LiveReadGuard.allow(() -> related(entityType, entityId, includeRelated, customerView));
            entries = entries(asThen(found)).stream()
                    .filter(e -> e.createdAt() != null && !e.createdAt().isAfter(point.instant()))
                    .toList();
        }
        Map<Long, PendingChange> changes = new HashMap<>();
        pendingChangeRepository.findAllById(entries.stream().map(Entry::pendingChangeId)
                .filter(Objects::nonNull).distinct().toList()).forEach(pc -> changes.put(pc.getId(), pc));
        Set<Long> internal = customerView ? internalDisputes(entries) : Set.of();
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            PendingChange pc = e.pendingChangeId() == null ? null : changes.get(e.pendingChangeId());
            if (customerView && TASK.equals(e.entityType())) continue;
            if (customerView && DISPUTE.equals(e.entityType()) && internal.contains(e.entityId())) continue;
            if (customerView && e.disputeId() != null && internal.contains(e.disputeId())) e = e.withoutDispute();
            if (customerView && pc != null && pc.isInternalSource()) {
                // The request and its decision are internal; what the change did to the record is not.
                if (!e.action().startsWith(CHANGE_ACTION)) out.add(e.withoutPendingChange());
                continue;
            }
            out.add(pc == null ? e : e.withPendingStatus(statusAt(pc, point).name()));
        }
        return out;
    }

    /** Where a change stood at the point: pending until it was decided; live, its status. */
    private static PendingStatus statusAt(PendingChange pc, AsOfPoint point) {
        if (point == null) return pc.getStatus();
        return pc.getDecidedAt() == null || pc.getDecidedAt().isAfter(point.instant())
                ? PendingStatus.PENDING : pc.getStatus();
    }

    private static final String TASK = "TASK";
    private static final String DISPUTE = "DISPUTE";

    /** The internal disputes the rows name, as a row about one or as the dispute that caused one; one query. */
    private Set<Long> internalDisputes(List<Entry> entries) {
        Set<Long> named = new HashSet<>();
        for (Entry e : entries) {
            if (DISPUTE.equals(e.entityType()) && e.entityId() != null) named.add(e.entityId());
            if (e.disputeId() != null) named.add(e.disputeId());
        }
        Set<Long> internal = new HashSet<>();
        List<Long> ids = new ArrayList<>(named);
        for (int from = 0; from < ids.size(); from += CHUNK) {
            internal.addAll(disputeRepository.findInternalAmong(ids.subList(from, Math.min(from + CHUNK, ids.size()))));
        }
        return internal;
    }

    private Related related(String entityType, Long entityId, boolean includeRelated, boolean customerView) {
        boolean tasks = includeRelated && !customerView;
        Related r = new Related();
        // A customer login never takes in a dispute a rule raised for staff (§5.13).
        Consumer<Dispute> dispute = customerView
                ? d -> { if (!d.isInternal()) r.dispute(d); }
                : r::dispute;
        switch (entityType) {
            case "CUSTOMER" -> {
                Customer customer = customerRepository.findById(entityId).orElse(null);
                if (customer == null) {
                    // Deleted: the trail is all that is left of it, and the deletion is in it
                    // (CP-04). Whether this caller may read it was settled before we got here.
                    r.add(entityType, entityId, null);
                } else {
                    r.customer(customer);
                }
                if (customer != null && includeRelated) {
                    visible(customerView, ScopeResolver.Kind.INVOICE, Privileges.INVOICE_VIEW,
                            invoiceRepository.findByCustomerIdOrderByInvoiceDateDesc(entityId), Invoice::getId)
                            .forEach(r::invoice);
                    visible(customerView, ScopeResolver.Kind.PAYMENT, Privileges.PAYMENT_VIEW,
                            paymentRepository.findByCustomerIdOrderByPaidAtDesc(entityId), Payment::getId)
                            .forEach(r::payment);
                    promises(customerView, promiseRepository.findByCustomerIdOrderByPromisedDateDesc(entityId))
                            .forEach(r::promise);
                    disputes(customerView, disputeRepository.findByCustomerIdOrderByCreatedAtDesc(entityId))
                            .forEach(dispute);
                    r.allocations.addAll(allocations(customerView,
                            allocationRepository.findByCustomerIdWithPayment(entityId)));
                }
                // The customer's tasks and those on its invoices and payments, for staff (BE-12).
                if (customer != null && tasks) tasks(r, taskRepository.findByCustomerIdOrderByCreatedAtDesc(entityId));
            }
            case "INVOICE" -> {
                Invoice invoice = invoiceRepository.findById(entityId).orElse(null);
                if (invoice == null) {
                    r.add(entityType, entityId, null);
                } else {
                    r.invoice(invoice);
                }
                if (invoice != null && includeRelated) {
                    promises(customerView, promiseRepository.findByInvoiceId(entityId)).forEach(r::promise);
                    disputes(customerView, disputeRepository.findByTargetTypeAndTargetId(DisputeTargetType.INVOICE,
                            entityId)).forEach(dispute);
                    r.allocations.addAll(allocations(customerView,
                            allocationRepository.findByInvoiceIdWithPayment(entityId)));
                }
                if (invoice != null && tasks) {
                    tasks(r, taskRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(TaskEntityType.INVOICE,
                            entityId));
                }
            }
            case "PAYMENT" -> {
                Payment payment = paymentRepository.findById(entityId).orElse(null);
                if (payment == null) {
                    r.add(entityType, entityId, null);
                } else {
                    r.payment(payment);
                }
                if (payment != null && includeRelated) {
                    promises(customerView, promiseRepository.findByPaymentId(entityId)).forEach(r::promise);
                    disputes(customerView, disputeRepository.findByTargetTypeAndTargetId(DisputeTargetType.PAYMENT,
                            entityId)).forEach(dispute);
                }
                if (payment != null && tasks) {
                    tasks(r, taskRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(TaskEntityType.PAYMENT,
                            entityId));
                }
            }
            case TASK -> {
                Task task = taskRepository.findById(entityId).orElse(null);
                if (task == null) {
                    r.add(entityType, entityId, null);
                } else {
                    r.task(task);
                }
            }
            default -> r.add(entityType, entityId, null);
        }
        return r;
    }

    /**
     * Takes in the related tasks the caller could open themselves: TASK_VIEW, and a record of theirs (a customer seen
     * through a seat does not bring the tasks on its invoices outside the caller's book). As of a date, as seen then.
     */
    private void tasks(Related r, List<Task> found) {
        Set<Long> visible = taskService.visibleAmong(found.stream().map(Task::getId).toList());
        found.stream().filter(t -> visible.contains(t.getId())).forEach(r::task);
    }

    private List<PaymentPromise> promises(boolean customerView, List<PaymentPromise> found) {
        return visible(customerView, ScopeResolver.Kind.PROMISE, Privileges.PROMISE_VIEW, found,
                PaymentPromise::getId);
    }

    private List<Dispute> disputes(boolean customerView, List<Dispute> found) {
        return visible(customerView, ScopeResolver.Kind.DISPUTE, Privileges.DISPUTE_VIEW, found, Dispute::getId);
    }

    /**
     * Of these related records, those staff could open themselves (SC-2): the kind's VIEW privilege, and a record in
     * their scope as a by-id read finds it (book and regions). A customer login's own records are all its own, so its
     * trail keeps them as found (it has its own exclusions).
     */
    private <T> List<T> visible(boolean customerView, ScopeResolver.Kind kind, String viewPrivilege, List<T> found,
                                Function<T, Long> id) {
        if (customerView || found.isEmpty()) return found;
        Set<Long> seen = visibleIds(kind, viewPrivilege, found.stream().map(id).distinct().toList());
        return found.stream().filter(x -> seen.contains(id.apply(x))).toList();
    }

    /** An allocation is taken in only when both its payment and its invoice are the caller's to see. */
    private List<PaymentAllocation> allocations(boolean customerView, List<PaymentAllocation> found) {
        if (customerView || found.isEmpty()) return found;
        Set<Long> payments = visibleIds(ScopeResolver.Kind.PAYMENT, Privileges.PAYMENT_VIEW,
                found.stream().map(a -> a.getPayment().getId()).distinct().toList());
        Set<Long> invoices = visibleIds(ScopeResolver.Kind.INVOICE, Privileges.INVOICE_VIEW,
                found.stream().map(a -> a.getInvoice().getId()).distinct().toList());
        return found.stream().filter(a -> payments.contains(a.getPayment().getId())
                && invoices.contains(a.getInvoice().getId())).toList();
    }

    /** Of these ids, those of records of the kind the caller may open, as TaskService.visibleAmong reads tasks. */
    private Set<Long> visibleIds(ScopeResolver.Kind kind, String viewPrivilege, List<Long> ids) {
        if (ids.isEmpty() || !currentUser.has(viewPrivilege)) return Set.of();
        List<PredicateFactory> scope = scopeResolver.scope(kind, ScopeResolver.Access.VIEW,
                ScopeResolver.Reach.GRANTED).predicates();
        Set<Long> seen = new HashSet<>();
        for (int from = 0; from < ids.size(); from += CHUNK) {
            List<Long> chunk = List.copyOf(ids.subList(from, Math.min(from + CHUNK, ids.size())));
            List<PredicateFactory> among = new ArrayList<>(scope);
            among.add((root, q, cb) -> root.get("id").in(chunk));
            seen.addAll(queryExecutor.ids(kind.type(), kind.schema(),
                    TableQuery.parseUnpaged(kind.schema(), "id,asc", List.of()), among, chunk.size()));
        }
        return seen;
    }

    /** The records found, as they were at the point; the labels of those without a version then stay as found. */
    private Related asThen(Related found) {
        Related then = new Related();
        found.labels.forEach((type, labels) -> labels.forEach((id, label) -> then.add(type, id, label)));
        records.findAll(Customer.class, ids(found.customers, Customer::getId)).forEach(then::customer);
        records.findAll(Invoice.class, ids(found.invoices, Invoice::getId)).forEach(then::invoice);
        records.findAll(Payment.class, ids(found.payments, Payment::getId)).forEach(then::payment);
        records.findAll(PaymentPromise.class, ids(found.promises, PaymentPromise::getId)).forEach(then::promise);
        records.findAll(Dispute.class, ids(found.disputes, Dispute::getId)).forEach(then::dispute);
        records.findAll(Task.class, ids(found.tasks, Task::getId)).forEach(then::task);
        then.allocations.addAll(records.findAll(PaymentAllocation.class,
                ids(found.allocations, PaymentAllocation::getId)));
        return then;
    }

    private static <T> List<Long> ids(List<T> records, Function<T, Long> id) {
        return records.stream().map(id).toList();
    }

    private List<Entry> entries(Related r) {
        List<Entry> entries = new ArrayList<>();
        r.labels.forEach((type, labels) -> {
            List<Long> ids = new ArrayList<>(labels.keySet());
            for (int from = 0; from < ids.size(); from += CHUNK) {
                List<Long> chunk = ids.subList(from, Math.min(from + CHUNK, ids.size()));
                for (AuditLog a : auditRepository.findByEntityTypeAndEntityIdIn(type, chunk)) {
                    entries.add(new Entry(a.getId(), a.getEntityType(), a.getEntityId(),
                            labels.get(a.getEntityId()), a.getAction(),
                            a.getBeforeJson(), a.getAfterJson(), a.getChangedByUserId(),
                            a.getDisputeId(), a.getReason(), a.getCreatedAt(), false, false,
                            a.getOrigin(), a.getRuleId(), a.getPendingChangeId(), a.getApprovedByUserId(), null));
                }
            }
        });
        entries.addAll(derived(r, entries));
        entries.sort(NEWEST_FIRST);
        return entries;
    }

    /**
     * A customer login's trail: no POC and no staff identity, no staff-only document, no approver, no checker's
     * note (§6.11 nulls {@code decisionNote} for them), no request for a record that does not exist yet (a held
     * create's rows are on the customer, D17), and no change of who staff assigned (assignees are staff-only).
     */
    public List<Entry> withoutPocIdentity(List<Entry> entries, Set<Long> visibleActors) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.action().contains("POC")) continue;
            if (e.action().endsWith(ASSIGNEES_ACTION)) continue;
            if (e.action().startsWith(CHANGE_ACTION) && "CUSTOMER".equals(e.entityType())) continue;
            if (e.action().startsWith(DOCUMENT_ACTION) && !sharedDocument(e)) continue;
            String before = stripPoc(e.beforeJson());
            String after = stripPoc(e.afterJson());
            if (before != null && before.equals(after)) continue;
            boolean hide = e.changedByUserId() != null && !visibleActors.contains(e.changedByUserId());
            String reason = DECISION_ACTIONS.contains(e.action()) ? null : e.reason();
            out.add(new Entry(e.id(), e.entityType(), e.entityId(), e.entityLabel(), e.action(),
                    before, after, hide ? null : e.changedByUserId(), e.disputeId(), reason,
                    e.createdAt(), e.derived(), hide, e.origin(), e.ruleId(), e.pendingChangeId(), null,
                    e.pendingStatus()));
        }
        return out;
    }

    private List<Entry> derived(Related r, List<Entry> recorded) {
        Set<String> have = new HashSet<>();
        Set<String> appliedPayments = new HashSet<>();
        Map<String, List<Entry>> befores = new HashMap<>();
        for (Entry e : recorded) {
            have.add(key(e.entityType(), e.entityId(), e.action()));
            if ("PAYMENT_APPLIED".equals(e.action())) {
                JsonNode after = readTree(e.afterJson());
                if (after != null && after.hasNonNull("paymentId")) {
                    appliedPayments.add(e.entityId() + ":" + after.get("paymentId").asLong());
                }
            }
            if (e.beforeJson() != null) {
                befores.computeIfAbsent(e.entityType() + ":" + e.entityId(), k -> new ArrayList<>()).add(e);
            }
        }

        List<Entry> out = new ArrayList<>();
        for (Customer c : r.customers) {
            if (!have.contains(key("CUSTOMER", c.getId(), "CUSTOMER_CREATED"))) {
                out.add(derivedEntry("CUSTOMER", c.getId(), c.getName(), "CUSTOMER_CREATED",
                        fields("name", c.getName(), "phone", c.getPhone(), "email", c.getEmail()),
                        null, null, null, c.getCreatedAt()));
            }
        }
        for (Invoice i : r.invoices) {
            if (!have.contains(key("INVOICE", i.getId(), "INVOICE_CREATED"))) {
                out.add(derivedEntry("INVOICE", i.getId(), i.getInvoiceNumber(), "INVOICE_CREATED",
                        fields("invoiceNumber", i.getInvoiceNumber(), "invoiceDate", i.getInvoiceDate(),
                                "total", original(befores, "INVOICE", i.getId(), "total", i.getTotal())),
                        null, null, null,
                        i.getCreatedAt() != null ? i.getCreatedAt() : i.getInvoiceDate()));
            }
        }
        for (Payment p : r.payments) {
            if (!have.contains(key("PAYMENT", p.getId(), "PAYMENT_RECORDED"))) {
                out.add(derivedEntry("PAYMENT", p.getId(), "Payment #" + p.getId(), "PAYMENT_RECORDED",
                        fields("amount", original(befores, "PAYMENT", p.getId(), "amount", p.getAmount()),
                                "method", p.getMethod()),
                        null, null, p.getNotes(), p.getPaidAt()));
            }
        }
        for (PaymentPromise p : r.promises) {
            if (!have.contains(key("PROMISE", p.getId(), "PROMISE_CREATED"))) {
                out.add(derivedEntry("PROMISE", p.getId(), "Promise #" + p.getId(), "PROMISE_CREATED",
                        fields("amount", p.getAmount(), "promisedDate", p.getPromisedDate()),
                        p.getCreatedByUserId(), null, p.getNotes(), p.getCreatedAt()));
            }
        }
        for (Dispute d : r.disputes) {
            String label = "Dispute #" + d.getId();
            if (!have.contains(key("DISPUTE", d.getId(), "DISPUTE_OPENED"))) {
                out.add(derivedEntry("DISPUTE", d.getId(), label, "DISPUTE_OPENED",
                        fields("targetType", d.getTargetType(), "targetId", d.getTargetId()),
                        d.getOpenedByUserId(), d.getId(), d.getReason(), d.getCreatedAt()));
            }
            if (d.getStatus() == DisputeStatus.DENIED
                    && !have.contains(key("DISPUTE", d.getId(), "DISPUTE_DENIED"))) {
                out.add(derivedEntry("DISPUTE", d.getId(), label, "DISPUTE_DENIED",
                        fields("targetType", d.getTargetType(), "targetId", d.getTargetId()),
                        d.getResolvedByUserId(), d.getId(), d.getAdminNotes(),
                        d.getResolvedAt() != null ? d.getResolvedAt() : d.getUpdatedAt()));
            }
        }
        Map<Long, String> invoiceLabels = r.labels.getOrDefault("INVOICE", Map.of());
        for (PaymentAllocation a : r.allocations) {
            Payment p = a.getPayment();
            Long invoiceId = a.getInvoice().getId();
            if (!appliedPayments.contains(invoiceId + ":" + p.getId())) {
                out.add(derivedEntry("INVOICE", invoiceId, invoiceLabels.get(invoiceId),
                        "PAYMENT_APPLIED", fields("paymentId", p.getId(), "amount", a.getAmount()),
                        null, null, null, p.getPaidAt()));
            }
        }
        return out;
    }

    private Object original(Map<String, List<Entry>> befores, String type, Long id, String field,
                            Object current) {
        String quoted = "\"" + field + "\"";
        JsonNode value = befores.getOrDefault(type + ":" + id, List.of()).stream()
                .filter(e -> e.beforeJson().contains(quoted))
                .sorted(Comparator.comparing(Entry::createdAt, OLDEST_FIRST))
                .map(e -> readTree(e.beforeJson()))
                .filter(n -> n != null && n.hasNonNull(field))
                .map(n -> n.get(field))
                .findFirst().orElse(null);
        return value != null ? value : current;
    }

    private Entry derivedEntry(String type, Long id, String label, String action,
                               Map<String, Object> after, Long by, Long disputeId, String reason,
                               Instant at) {
        return new Entry(null, type, id, label, action, null, toJson(after), by, disputeId, reason,
                at, true, false, null, null, null, null, null);
    }

    private static String key(String type, Long id, String action) {
        return type + ":" + id + ":" + action;
    }

    private static Map<String, Object> fields(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return m;
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private JsonNode readTree(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static final String DOCUMENT_ACTION = "DOCUMENT_";

    private static final String SHARED = "SHARED";

    private static final String CHANGE_ACTION = "CHANGE_";
    private static final String ASSIGNEES_ACTION = "_ASSIGNEES_CHANGED";
    private static final Set<String> DECISION_ACTIONS =
            Set.of("CHANGE_APPROVED", "CHANGE_REJECTED", "CHANGE_WITHDRAWN");

    private static final Set<String> STAFF_NAME_FIELDS =
            Set.of("uploadedby", "changedby", "createdby", "resolvedby", "assignedby", "recordedby",
                    "makeruserid", "requestedby", "decidedby", "decidedbyuserid", "approvedby", "approvedbyuserid");

    private boolean sharedDocument(Entry e) {
        return isShared(e.beforeJson()) && isShared(e.afterJson());
    }

    private boolean isShared(String json) {
        if (json == null) return true;
        JsonNode node = readTree(json);
        return node != null && SHARED.equals(node.path("visibility").asText(null));
    }

    private String stripPoc(String json) {
        JsonNode node = readTree(json);
        if (node == null) return null;
        stripPoc(node);
        return toJson(node);
    }

    private void stripPoc(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            List<String> doomed = new ArrayList<>();
            obj.fieldNames().forEachRemaining(name -> {
                if (hiddenFromCustomer(name)) doomed.add(name);
            });
            obj.remove(doomed);
            obj.elements().forEachRemaining(this::stripPoc);
        } else if (node != null && node.isArray()) {
            node.forEach(this::stripPoc);
        }
    }

    private static boolean hiddenFromCustomer(String field) {
        String name = field.toLowerCase(Locale.ROOT);
        return name.contains("poc") || STAFF_NAME_FIELDS.contains(name);
    }

    private static final class Related {
        final Map<String, Map<Long, String>> labels = new LinkedHashMap<>();
        final List<Customer> customers = new ArrayList<>();
        final List<Invoice> invoices = new ArrayList<>();
        final List<Payment> payments = new ArrayList<>();
        final List<PaymentPromise> promises = new ArrayList<>();
        final List<Dispute> disputes = new ArrayList<>();
        final List<PaymentAllocation> allocations = new ArrayList<>();
        final List<Task> tasks = new ArrayList<>();

        void add(String type, Long id, String label) {
            labels.computeIfAbsent(type, t -> new LinkedHashMap<>()).put(id, label);
        }

        void customer(Customer c) {
            customers.add(c);
            add("CUSTOMER", c.getId(), c.getName());
        }

        void invoice(Invoice i) {
            invoices.add(i);
            add("INVOICE", i.getId(), i.getInvoiceNumber());
        }

        void payment(Payment p) {
            payments.add(p);
            add("PAYMENT", p.getId(), "Payment #" + p.getId());
        }

        void promise(PaymentPromise p) {
            promises.add(p);
            add("PROMISE", p.getId(), "Promise #" + p.getId());
        }

        void dispute(Dispute d) {
            disputes.add(d);
            add("DISPUTE", d.getId(), "Dispute #" + d.getId());
        }

        void task(Task t) {
            tasks.add(t);
            add(TASK, t.getId(), t.getTitle());
        }
    }
}
