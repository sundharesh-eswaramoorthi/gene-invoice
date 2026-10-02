package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.Money;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.history.LiveReadGuard;
import com.geneinvoice.history.VersionRow;
import com.geneinvoice.history.VersionStore;
import com.geneinvoice.history.versions.CustomerVersion;
import com.geneinvoice.history.versions.InvoiceVersion;
import com.geneinvoice.history.versions.ProductVersion;
import com.geneinvoice.history.versions.PromiseVersion;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.ProductRepository;
import com.geneinvoice.promise.PaymentPromiseRepository;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.region.RegionAccessQueries;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * What a pending change looks like on the wire (§6.6, §6.11): the short form for lists, the full form with the
 * fields it changes, and the reply to a held write. A customer login never sees who asked or decided, the
 * decision note or the origin, and no one without POC_VIEW sees a POC field (D17, §5.11.9).
 */
@Component
@RequiredArgsConstructor
public class PendingChangeViews {

    private final ChangeAppliers appliers;
    private final PendingChangeScope scope;
    private final PayloadCodec codec;
    private final CurrentUser currentUser;
    private final ScopeResolver scopeResolver;
    private final RegionAccessQueries regionAccess;
    private final UserRepository userRepository;
    private final PendingChangeRepository pending;
    private final RegionRepository regionRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentPromiseRepository promiseRepository;
    private final ProductRepository productRepository;
    private final VersionStore versionStore;

    public record Person(Long id, String name) {}

    public record Brief(Long id, PendingStatus status, PendingStatus statusAt, ApprovalOperation operation,
                        String operationLabel, String entityType, Long entityId, String entityLabel, Long customerId,
                        String customerName, Long regionId, String regionName, BigDecimal amount,
                        BigDecimal threshold, String summary, Instant requestedAt, Person requestedBy,
                        ChangeOrigin origin, Instant decidedAt) {}

    /**
     * One field the change sets: what it is now (null when the viewer cannot see the record), and what it becomes.
     * {@code currentText}/{@code proposedText} are the server's display text where the raw value is an id or a
     * code (a user's or customer's name, a payment term's or enum's label); null otherwise, where the client
     * formats the value itself (dates, money).
     */
    public record Change(String field, String label, JsonNode current, JsonNode proposed,
                         boolean changedSinceRequest, String currentText, String proposedText) {}

    public record Result(String entityType, Long entityId, String link) {}

    public record Detail(Long id, PendingStatus status, PendingStatus statusAt, ApprovalOperation operation,
                         String operationLabel, String entityType, Long entityId, String entityLabel,
                         String affectedType, Long affectedId, Long customerId, String customerName, Long regionId,
                         String regionName, BigDecimal amount, BigDecimal threshold, String measureLabel,
                         String summary, List<Change> changes, JsonNode current, JsonNode proposed,
                         Person requestedBy, Instant requestedAt, ChangeOrigin origin, Long ruleId,
                         Person decidedBy, Instant decidedAt, String decisionNote, String failureReason,
                         Long supersededById, Result result, long approverCount, boolean canApprove,
                         boolean canReject, boolean canWithdraw, String blockedReason) {}

    /** The pending change a held write answers with. */
    public record HeldChange(Long id, PendingStatus status, ApprovalOperation operation, String operationLabel,
                             String entityType, Long entityId, String entityLabel, Long customerId,
                             String customerName, Long regionId, String regionName, BigDecimal amount,
                             BigDecimal threshold, String summary, Instant requestedAt, Person requestedBy,
                             ChangeOrigin origin, long approverCount, Long supersededId, boolean canWithdraw) {}

    /** The 202 body: no top-level {@code id}, so a client can never mistake it for the record (§6.6). */
    public record HeldReply(String status, String message, HeldChange pendingChange) {}

    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";

    public Brief brief(PendingChange pc) {
        boolean customer = currentUser.isCustomer();
        return new Brief(pc.getId(), pc.getStatus(), statusAt(pc), pc.getOperation(), pc.getOperation().label(),
                pc.getEntityType(), pc.getEntityId(), pc.getEntityLabel(), pc.getCustomerId(), pc.getCustomerName(),
                pc.getRegionId(), regionName(pc), Money.scale(pc.getAmount()),
                Money.scale(pc.getThresholdAmount()), pc.getSummary(), pc.getRequestedAt(),
                customer ? null : person(pc.getMakerUserId()), customer ? null : pc.getOrigin(),
                hiddenDecision(pc) ? null : pc.getDecidedAt());
    }

    /**
     * As of a date the change was still waiting, but it has been decided since: the decision (who, when, the note,
     * the reason it failed, what replaced it and what it made) is after that date, so it is not shown (ASO-R1-2).
     */
    private static boolean hiddenDecision(PendingChange pc) {
        return AsOf.active() && statusAt(pc) == PendingStatus.PENDING && pc.getStatus() != PendingStatus.PENDING;
    }

    public Detail detail(PendingChange pc, User caller) {
        boolean customer = caller.getCustomerId() != null;
        boolean historical = AsOf.active();
        boolean pocs = scopeResolver.canSeePoc();
        JsonNode base = shown(codec.tree(pc.getBaseJson()), pocs);
        JsonNode proposed = shown(codec.tree(pc.getProposedJson()), pocs);
        // Only while it can still be seen, and only live: what the record says today is not history.
        // Whether the caller can open the record now; as of a date that question is not asked (RGN-R3-1).
        boolean visible = !historical && scope.canSeeRecord(pc);
        JsonNode current = visible
                ? shown(codec.tree(codec.toJson(appliers.of(pc.getOperation()).currentView(pc))), pocs)
                : null;

        boolean mine = Objects.equals(pc.getMakerUserId(), caller.getId());
        boolean approver = !customer && currentUser.hasIn(Privileges.CHANGE_APPROVE, pc.getRegionId());
        boolean open = pc.isPending() && !historical;
        boolean decide = open && !customer && !mine && approver;
        String regionName = regionName(pc);
        boolean hidden = hiddenDecision(pc);

        return new Detail(pc.getId(), pc.getStatus(), statusAt(pc), pc.getOperation(), pc.getOperation().label(),
                pc.getEntityType(), pc.getEntityId(), pc.getEntityLabel(), pc.getAffectedType(), pc.getAffectedId(),
                pc.getCustomerId(), pc.getCustomerName(), pc.getRegionId(), regionName, Money.scale(pc.getAmount()),
                Money.scale(pc.getThresholdAmount()), pc.getOperation().measureLabel(), pc.getSummary(),
                changes(base, proposed, current), current, proposed,
                customer ? null : person(pc.getMakerUserId()), pc.getRequestedAt(),
                customer ? null : pc.getOrigin(), customer ? null : pc.getRuleId(),
                customer || hidden ? null : person(pc.getDecidedByUserId()), hidden ? null : pc.getDecidedAt(),
                customer || hidden ? null : pc.getDecisionNote(), hidden ? null : pc.getFailureReason(),
                hidden ? null : pc.getSupersededById(), hidden ? null : result(pc), approverCount(pc), decide,
                decide, open && mine,
                blockedReason(pc, customer, historical, visible, mine, approver, regionName));
    }

    public HeldReply heldReply(PendingChange pc) {
        HeldChange held = new HeldChange(pc.getId(), pc.getStatus(), pc.getOperation(), pc.getOperation().label(),
                pc.getEntityType(), pc.getEntityId(), pc.getEntityLabel(), pc.getCustomerId(), pc.getCustomerName(),
                pc.getRegionId(), regionName(pc), Money.scale(pc.getAmount()),
                Money.scale(pc.getThresholdAmount()), pc.getSummary(), pc.getRequestedAt(),
                person(pc.getMakerUserId()), pc.getOrigin(), approverCount(pc),
                pending.findFirstBySupersededById(pc.getId()).map(PendingChange::getId).orElse(null), true);
        return new HeldReply(PENDING_APPROVAL, "Above " + regionName(pc) + "'s approval limit of "
                + Money.format(pc.getThresholdAmount()) + " for " + pc.getOperation().label()
                + ". It takes effect once someone else approves it.", held);
    }

    /** As of a date, where the change stood then: still pending until it was decided. Null when live. */
    private static PendingStatus statusAt(PendingChange pc) {
        if (!AsOf.active()) return null;
        Instant p = AsOf.current().instant();
        return pc.getDecidedAt() == null || pc.getDecidedAt().isAfter(p) ? PendingStatus.PENDING : pc.getStatus();
    }

    /**
     * Why the caller cannot act on the change, or null: "Read-only in the as-of view", "You no longer have access to
     * this record" (its now-values are blank; approval stays a region right, SC-15), "You requested this change",
     * or "You don't hold the approval right in &lt;region&gt;".
     */
    private static String blockedReason(PendingChange pc, boolean customer, boolean historical, boolean visible,
                                        boolean mine, boolean approver, String regionName) {
        if (historical) return "Read-only in the as-of view";
        if (!visible) return "You no longer have access to this record";
        if (!pc.isPending() || customer) return null;
        if (mine) return "You requested this change";
        if (!approver) return "You don't hold the approval right in " + regionName;
        return null;
    }

    /** Active holders of the approval right in the region, not counting the maker. */
    private long approverCount(PendingChange pc) {
        return regionAccess.holdersIn(Privileges.CHANGE_APPROVE, pc.getRegionId()).stream()
                .filter(u -> !u.getId().equals(pc.getMakerUserId()))
                .count();
    }

    private Result result(PendingChange pc) {
        if (pc.getStatus() != PendingStatus.APPROVED) return null;
        Long id = pc.getResultEntityId() != null ? pc.getResultEntityId() : pc.getEntityId();
        return new Result(pc.getEntityType(), id, ApprovalNotifier.recordLink(pc));
    }

    private List<Change> changes(JsonNode base, JsonNode proposed, JsonNode current) {
        List<Change> out = new ArrayList<>();
        if (proposed == null) return out;
        record Row(String field, JsonNode now, JsonNode becomes, boolean drifted) {}
        List<Row> rows = new ArrayList<>();
        for (Iterator<String> names = proposed.fieldNames(); names.hasNext(); ) {
            String field = names.next();
            JsonNode was = base == null ? null : base.get(field);
            JsonNode becomes = proposed.get(field);
            JsonNode now = current == null ? null : current.get(field);
            boolean drifted = now != null && was != null && !same(now, was);
            // A field the change carries unchanged is still shown once it has moved: the approval checks it (CF-8).
            if (was != null && same(was, becomes) && !drifted) continue;
            rows.add(new Row(field, now, becomes, drifted));
        }
        // A create's list of ids that sets nothing is no change at all (APP-R2-1).
        if (base == null) rows.removeIf(r -> r.field().endsWith("Ids") && nothing(r.becomes()));
        // Names for the ids the rows carry, each kind read once.
        Set<Long> userIds = new HashSet<>();
        Set<Long> customerIds = new HashSet<>();
        Set<Long> invoiceIds = new HashSet<>();
        Set<Long> promiseIds = new HashSet<>();
        Set<Long> productIds = new HashSet<>();
        for (Row r : rows) {
            if (r.field().endsWith("UserId")) {
                addId(userIds, r.now());
                addId(userIds, r.becomes());
            } else if (r.field().equals("customerId")) {
                addId(customerIds, r.now());
                addId(customerIds, r.becomes());
            } else if (r.field().equals("invoiceIds")) {
                addIds(invoiceIds, r.now());
                addIds(invoiceIds, r.becomes());
            } else if (r.field().equals("promiseIds")) {
                addIds(promiseIds, r.now());
                addIds(promiseIds, r.becomes());
            } else if (r.field().equals("items")) {
                addProductIds(productIds, r.now());
                addProductIds(productIds, r.becomes());
            }
        }
        Map<Long, String> users = new HashMap<>();
        if (!userIds.isEmpty()) {
            userRepository.findAllById(userIds).forEach(u -> users.put(u.getId(), MakerChecker.displayName(u)));
        }
        Map<Long, String> customers = names(customerIds, CustomerVersion.class, CustomerVersion::getName,
                customerRepository, c -> c.getId(), c -> c.getName());
        Map<Long, String> invoices = names(invoiceIds, InvoiceVersion.class, InvoiceVersion::getInvoiceNumber,
                invoiceRepository, i -> i.getId(), i -> i.getInvoiceNumber());
        Map<Long, String> promises = names(promiseIds, PromiseVersion.class,
                p -> promiseText(p.getAmount(), p.getPromisedDate()),
                promiseRepository, p -> p.getId(), p -> promiseText(p.getAmount(), p.getPromisedDate()));
        Map<Long, String> products = names(productIds, ProductVersion.class, ProductVersion::getName,
                productRepository, p -> p.getId(), p -> p.getName());
        Names names = new Names(users, customers, invoices, promises, products);
        for (Row r : rows) {
            out.add(new Change(r.field(), label(r.field()), r.now(), r.becomes(), r.drifted(),
                    text(r.field(), r.now(), names), text(r.field(), r.becomes(), names)));
        }
        return out;
    }

    /**
     * The names of these records, keyed by id. Live, read from the records. As of a date, from the versions valid
     * then (ASO-R3-1: a live versioned record may not be loaded while a point is bound); an id with no version then
     * (a gap in history) falls back to its live name, and one missing altogether keeps the caller's fallback. The
     * version read never throws, so nothing is caught inside the caller's transaction.
     */
    private <E, V extends VersionRow> Map<Long, String> names(Set<Long> ids, Class<V> version,
                                                             Function<V, String> versionName,
                                                             CrudRepository<E, Long> repository,
                                                             Function<E, Long> idOf, Function<E, String> liveName) {
        Map<Long, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        if (!AsOf.active()) {
            repository.findAllById(ids).forEach(e -> out.put(idOf.apply(e), liveName.apply(e)));
            return out;
        }
        for (V v : versionStore.validAt(version, ids, AsOf.current().instant())) {
            out.putIfAbsent(v.getId(), versionName.apply(v));
        }
        Set<Long> missing = new HashSet<>(ids);
        missing.removeAll(out.keySet());
        if (!missing.isEmpty()) {
            LiveReadGuard.allow(() -> {
                repository.findAllById(missing).forEach(e -> out.put(idOf.apply(e), liveName.apply(e)));
                return null;
            });
        }
        return out;
    }

    private static String promiseText(BigDecimal amount, LocalDate promisedDate) {
        return "Promise of " + Money.format(amount) + " by " + promisedDate;
    }

    /** The names the changes table reads ids by, each keyed by id. */
    record Names(Map<Long, String> users, Map<Long, String> customers, Map<Long, String> invoices,
                 Map<Long, String> promises, Map<Long, String> products) {
        static final Names NONE = new Names(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static boolean nothing(JsonNode v) {
        return v == null || v.isNull() || (v.isArray() && v.isEmpty());
    }

    private static void addIds(Set<Long> ids, JsonNode v) {
        if (v != null && v.isArray()) v.forEach(e -> addId(ids, e));
    }

    private static void addProductIds(Set<Long> ids, JsonNode v) {
        if (v != null && v.isArray()) v.forEach(e -> addId(ids, e.get("productId")));
    }

    private static void addId(Set<Long> ids, JsonNode v) {
        if (v != null && v.canConvertToLong() && v.isIntegralNumber()) ids.add(v.asLong());
    }

    /** Free-text fields, whose words are the user's own and are never read as a code. */
    private static final Set<String> FREE_TEXT = Set.of("notes", "overrideReason", "reason", "description", "name");

    private static final Pattern UPPER_SNAKE = Pattern.compile("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*");

    /** The display text of a value that is an id or a code; null for anything the client formats itself. */
    static String text(String field, JsonNode v, Map<Long, String> users, Map<Long, String> customers) {
        return text(field, v, new Names(users, customers, Map.of(), Map.of(), Map.of()));
    }

    static String text(String field, JsonNode v, Names names) {
        if (v == null || v.isNull()) return null;
        if (field.endsWith("UserId")) {
            if (!v.isIntegralNumber()) return null;
            long id = v.asLong();
            return names.users().getOrDefault(id, "user #" + id);
        }
        if (field.equals("customerId")) {
            if (!v.isIntegralNumber()) return null;
            long id = v.asLong();
            return names.customers().getOrDefault(id, "customer #" + id);
        }
        if (v.isArray()) {
            return switch (field) {
                case "invoiceIds" -> idList(v, names.invoices(), "invoice #");
                case "promiseIds" -> idList(v, names.promises(), "promise #");
                case "items" -> lines(v, names.products());
                default -> null;
            };
        }
        if (!v.isTextual()) return null;
        String raw = v.asText();
        if (field.equals("paymentTerm")) {
            try {
                return PaymentTerm.valueOf(raw).label();
            } catch (IllegalArgumentException notATerm) {
                return null;
            }
        }
        if (FREE_TEXT.contains(field) || !UPPER_SNAKE.matcher(raw).matches()) return null;
        String words = raw.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** Each id by its name, in the list's order; "None" for an empty list. */
    private static String idList(JsonNode ids, Map<Long, String> byId, String missing) {
        if (ids.isEmpty()) return "None";
        List<String> parts = new ArrayList<>();
        for (JsonNode e : ids) {
            if (!e.isIntegralNumber()) return null;
            long id = e.asLong();
            parts.add(byId.getOrDefault(id, missing + id));
        }
        return String.join(", ", parts);
    }

    /** One line per item: "2 × Widget at ₹10.00", or "at list price" when it names no price. */
    private static String lines(JsonNode items, Map<Long, String> products) {
        if (items.isEmpty()) return "None";
        List<String> out = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode productId = item.get("productId");
            if (productId == null || !productId.isIntegralNumber()) return null;
            long id = productId.asLong();
            JsonNode quantity = item.get("quantity");
            JsonNode price = item.get("unitPrice");
            String qty = quantity == null || quantity.isNull() ? "?"
                    : quantity.isNumber() ? quantity.decimalValue().stripTrailingZeros().toPlainString()
                    : quantity.asText();
            String at = price == null || price.isNull() || !price.isNumber() ? "list price"
                    : Money.format(price.decimalValue());
            out.add(qty + " × " + products.getOrDefault(id, "product #" + id) + " at " + at);
        }
        return String.join("\n", out);
    }

    /** Numbers by value, at any depth: 500 and 500.00 are the same amount. */
    private static final Comparator<JsonNode> BY_VALUE = (a, b) -> a.isNumber() && b.isNumber()
            ? a.decimalValue().compareTo(b.decimalValue())
            : a.equals(b) ? 0 : 1;

    private static boolean same(JsonNode a, JsonNode b) {
        if (a == null || b == null) return a == b;
        return a.equals(BY_VALUE, b);
    }

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("salesPocUserId", "Sales POC"),
            Map.entry("collectionPocUserId", "Collection POC"),
            Map.entry("customerSuccessPocUserId", "Customer Success POC"),
            Map.entry("customerId", "Customer"),
            Map.entry("paymentTerm", "Payment term"),
            Map.entry("invoiceDate", "Invoice date"),
            Map.entry("dueDate", "Due date"),
            Map.entry("items", "Items"),
            Map.entry("invoiceIds", "Invoices"),
            Map.entry("promiseIds", "Promises"),
            Map.entry("paidAt", "Paid on"),
            Map.entry("method", "Method"));

    /**
     * The field's name for people: a known field by its name ({@code salesPocUserId} → "Sales POC"), otherwise
     * the camelCase split, without a trailing "UserId" or "Id" and with "poc" as "POC" ({@code paidAmount} →
     * "Paid amount").
     */
    static String label(String field) {
        String known = LABELS.get(field);
        if (known != null) return known;
        String stem = field;
        if (stem.endsWith("UserId") && stem.length() > "UserId".length()) {
            stem = stem.substring(0, stem.length() - "UserId".length());
        } else if (stem.endsWith("Id") && stem.length() > "Id".length()) {
            stem = stem.substring(0, stem.length() - "Id".length());
        }
        String spaced = stem.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        spaced = spaced.replaceAll("\\bpoc\\b", "POC");
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** Without POC_VIEW (and always for a customer login) every POC field is taken out. */
    private static JsonNode shown(JsonNode node, boolean pocs) {
        if (node instanceof ObjectNode obj && !pocs) {
            for (Iterator<String> names = obj.fieldNames(); names.hasNext(); ) {
                if (names.next().toLowerCase(Locale.ROOT).contains("poc")) names.remove();
            }
        }
        return node;
    }

    /** Read by id, so a held reply built after its transaction never touches the change's lazy region. */
    private String regionName(PendingChange pc) {
        return regionRepository.findById(pc.getRegionId()).map(Region::getName).orElse(null);
    }

    private Person person(Long userId) {
        if (userId == null) return null;
        return userRepository.findById(userId)
                .map(u -> new Person(u.getId(), MakerChecker.displayName(u)))
                .orElse(new Person(userId, "user #" + userId));
    }
}
