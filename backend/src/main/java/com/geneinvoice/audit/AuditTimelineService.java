package com.geneinvoice.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeRepository;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentAllocation;
import com.geneinvoice.payment.PaymentAllocationRepository;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseRepository;
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
import java.util.Set;

/**
 * The History tab of a detail screen. A timeline is anchored on one record and can be widened to
 * the records that hang off it: a customer's covers its invoices, payments, promises and disputes;
 * an invoice's covers the payments applied to it and the promises and disputes raised on it; a
 * payment's covers the promises it counts towards and the disputes raised on it.
 *
 * <p>Records older than the audit event that describes them — an invoice created before invoice
 * creation was audited, say — get that event derived from the record itself and marked
 * {@code derived}, so an upgraded database tells the whole story rather than starting at the
 * upgrade.
 */
@Service
@RequiredArgsConstructor
public class AuditTimelineService {

    /** Ids per audit query, comfortably under every driver's bind-parameter limit. */
    private static final int CHUNK = 1000;

    private static final Comparator<Instant> OLDEST_FIRST =
            Comparator.nullsLast(Comparator.<Instant>naturalOrder());

    private static final Comparator<Entry> NEWEST_FIRST = Comparator
            .comparing(Entry::createdAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
            .thenComparing(Entry::id, Comparator.nullsLast(Comparator.<Long>reverseOrder()));

    /**
     * One timeline row. {@code actorHidden} marks a row whose actor was withheld from the viewer,
     * so the screen does not mistake it for an automatic change.
     */
    public record Entry(Long id, String entityType, Long entityId, String entityLabel,
                        String action, String beforeJson, String afterJson,
                        Long changedByUserId, Long disputeId, String reason,
                        Instant createdAt, boolean derived, boolean actorHidden) {}

    private final AuditLogRepository auditRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAllocationRepository allocationRepository;
    private final PaymentPromiseRepository promiseRepository;
    private final DisputeRepository disputeRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<Entry> timeline(String entityType, Long entityId, boolean includeRelated) {
        Related r = new Related();
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
                    invoiceRepository.findByCustomerIdOrderByInvoiceDateDesc(entityId).forEach(r::invoice);
                    paymentRepository.findByCustomerIdOrderByPaidAtDesc(entityId).forEach(r::payment);
                    promiseRepository.findByCustomerIdOrderByPromisedDateDesc(entityId).forEach(r::promise);
                    disputeRepository.findByCustomerIdOrderByCreatedAtDesc(entityId).forEach(r::dispute);
                    r.allocations.addAll(allocationRepository.findByCustomerIdWithPayment(entityId));
                }
            }
            case "INVOICE" -> {
                Invoice invoice = invoiceRepository.findById(entityId).orElse(null);
                if (invoice == null) {
                    r.add(entityType, entityId, null);
                } else {
                    r.invoice(invoice);
                }
                if (invoice != null && includeRelated) {
                    promiseRepository.findByInvoiceId(entityId).forEach(r::promise);
                    disputeRepository.findByTargetTypeAndTargetId(DisputeTargetType.INVOICE, entityId)
                            .forEach(r::dispute);
                    r.allocations.addAll(allocationRepository.findByInvoiceIdWithPayment(entityId));
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
                    promiseRepository.findByPaymentId(entityId).forEach(r::promise);
                    disputeRepository.findByTargetTypeAndTargetId(DisputeTargetType.PAYMENT, entityId)
                            .forEach(r::dispute);
                }
            }
            default -> r.add(entityType, entityId, null);
        }

        List<Entry> entries = new ArrayList<>();
        r.labels.forEach((type, labels) -> {
            List<Long> ids = new ArrayList<>(labels.keySet());
            for (int from = 0; from < ids.size(); from += CHUNK) {
                List<Long> chunk = ids.subList(from, Math.min(from + CHUNK, ids.size()));
                for (AuditLog a : auditRepository.findByEntityTypeAndEntityIdIn(type, chunk)) {
                    entries.add(new Entry(a.getId(), a.getEntityType(), a.getEntityId(),
                            labels.get(a.getEntityId()), a.getAction(),
                            a.getBeforeJson(), a.getAfterJson(), a.getChangedByUserId(),
                            a.getDisputeId(), a.getReason(), a.getCreatedAt(), false, false));
                }
            }
        });
        entries.addAll(derived(r, entries));
        entries.sort(NEWEST_FIRST);
        return entries;
    }

    /**
     * The timeline as a customer-scoped account may see it (AC-A8): no POC events, no POC identity
     * inside the snapshots, and no staff names — whoever raised an invoice or took a payment on an
     * account is usually its POC. Only {@code visibleActors}, the customer's own logins, stay named.
     * An edit that only touched a POC leaves nothing to show and is dropped rather than rendered as
     * an empty change.
     */
    public List<Entry> withoutPocIdentity(List<Entry> entries, Set<Long> visibleActors) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.action().contains("POC")) continue;
            // A customer login sees only SHARED documents (AC-C12), and the history must not be a
            // way round that: an entry about an internal file is not mentioned to them at all.
            if (e.action().startsWith(DOCUMENT_ACTION) && !sharedDocument(e)) continue;
            String before = stripPoc(e.beforeJson());
            String after = stripPoc(e.afterJson());
            if (before != null && before.equals(after)) continue;
            boolean hide = e.changedByUserId() != null && !visibleActors.contains(e.changedByUserId());
            out.add(new Entry(e.id(), e.entityType(), e.entityId(), e.entityLabel(), e.action(),
                    before, after, hide ? null : e.changedByUserId(), e.disputeId(), e.reason(),
                    e.createdAt(), e.derived(), hide));
        }
        return out;
    }

    // ---- events older than their audit trail -------------------------------------

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
            // Only what was true when it was recorded: status and credit applied are later states.
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

    /**
     * A field as it stood before the earliest recorded change that captured it — the nearest thing
     * to its value at creation, since a dispute may later have replaced an invoice's items or
     * changed a payment's amount. Falls back to the record's current value.
     */
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
                at, true, false);
    }

    private static String key(String type, Long id, String action) {
        return type + ":" + id + ":" + action;
    }

    /** An ordered map that, unlike {@code Map.of}, tolerates the nulls a legacy row may carry. */
    private static Map<String, Object> fields(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return m;
    }

    // ---- JSON --------------------------------------------------------------------

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

    /** Every audit action about a document; all of them name a file and who attached it. */
    private static final String DOCUMENT_ACTION = "DOCUMENT_";

    /** The one visibility a customer login may be told a document has (§4.5, D7). */
    private static final String SHARED = "SHARED";

    /**
     * Fields that name a member of staff rather than a POC, and so are withheld from a customer
     * login for the same reason the POC fields are (AC-A8). Matched on the whole name, since these
     * carry no marker of their own the way a "…Poc…" field does.
     */
    private static final Set<String> STAFF_NAME_FIELDS =
            Set.of("uploadedby", "changedby", "createdby", "resolvedby", "assignedby", "recordedby");

    /**
     * Whether an entry about a document is about one the customer can see anyway. Every snapshot
     * the entry carries has to say {@code SHARED}: a file that was internal at either end of the
     * change is not theirs to know about, and an entry this cannot read is withheld rather than
     * guessed at.
     */
    private boolean sharedDocument(Entry e) {
        return isShared(e.beforeJson()) && isShared(e.afterJson());
    }

    /** True for an absent snapshot (an upload has no before, a delete no after) or a SHARED one. */
    private boolean isShared(String json) {
        if (json == null) return true;
        JsonNode node = readTree(json);
        return node != null && SHARED.equals(node.path("visibility").asText(null));
    }

    /**
     * Removes every key naming a POC or a member of staff, at any depth. Unreadable input is
     * withheld, not passed on.
     */
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

    // ---- what a timeline covers --------------------------------------------------

    /** The records a timeline covers, each with the label it is shown under. */
    private static final class Related {
        final Map<String, Map<Long, String>> labels = new LinkedHashMap<>();
        final List<Customer> customers = new ArrayList<>();
        final List<Invoice> invoices = new ArrayList<>();
        final List<Payment> payments = new ArrayList<>();
        final List<PaymentPromise> promises = new ArrayList<>();
        final List<Dispute> disputes = new ArrayList<>();
        final List<PaymentAllocation> allocations = new ArrayList<>();

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
    }
}
