package com.geneinvoice.history;

import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.history.types.AllocationHistory;
import com.geneinvoice.history.types.CustomerHistory;
import com.geneinvoice.history.types.DisputeHistory;
import com.geneinvoice.history.types.InvoiceHistory;
import com.geneinvoice.history.types.PaymentHistory;
import com.geneinvoice.history.types.ProductHistory;
import com.geneinvoice.history.types.PromiseHistory;
import com.geneinvoice.history.types.SeatHistory;
import com.geneinvoice.history.types.TaskHistory;
import com.geneinvoice.history.versions.CustomerPocVersion;
import com.geneinvoice.history.versions.CustomerVersion;
import com.geneinvoice.history.versions.EntityLinkVersion;
import com.geneinvoice.history.versions.InvoiceVersion;
import com.geneinvoice.history.versions.PaymentAllocationVersion;
import com.geneinvoice.history.versions.PaymentVersion;
import com.geneinvoice.history.versions.PromiseVersion;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Which records have history, and how: per type its code, its rank (the order types are written in), its live
 * and version classes, its {@link TypeHistory}, and the associations a query over its versions follows to the
 * versions of another record. Built once; nothing in it changes at runtime.
 */
public final class HistoryModel {

    /** Joins a version root to the versions of the record an association points at. */
    @FunctionalInterface
    public interface On {
        Predicate on(From<?, ?> join, From<?, ?> from, CriteriaBuilder cb);
    }

    /** An association followed across versions: from a version class, by its live name, to a version class. */
    public record Navigation(Class<? extends VersionRow> from, String attribute,
                             Class<? extends VersionRow> target, On on) {}

    private static final List<TypeHistory<?, ?>> TYPES = Stream.<TypeHistory<?, ?>>of(
                    new CustomerHistory(),
                    new ProductHistory(),
                    new InvoiceHistory(),
                    new PaymentHistory(),
                    new AllocationHistory(),
                    new PromiseHistory(),
                    new DisputeHistory(),
                    new SeatHistory(),
                    new TaskHistory())
            .sorted(Comparator.comparingInt(TypeHistory::rank))
            .toList();

    private static final List<LinkHistory> LINKS = List.of(LinkHistory.PROMISE_INVOICES, LinkHistory.PROMISE_PAYMENTS);

    private static final List<Navigation> NAVIGATIONS = List.of(
            toCustomer(InvoiceVersion.class),
            toCustomer(PaymentVersion.class),
            toCustomer(PromiseVersion.class),
            toCustomer(CustomerPocVersion.class),
            new Navigation(PaymentAllocationVersion.class, "payment", PaymentVersion.class,
                    (j, from, cb) -> cb.equal(j.get("id"), from.get("paymentId"))),
            new Navigation(PaymentAllocationVersion.class, "invoice", InvoiceVersion.class,
                    (j, from, cb) -> cb.equal(j.get("id"), from.get("invoiceId"))),
            toLinks(LinkHistory.PROMISE_INVOICES),
            toLinks(LinkHistory.PROMISE_PAYMENTS));

    private static final Map<Class<?>, TypeHistory<?, ?>> BY_LIVE = index(TYPES, TypeHistory::liveType);
    private static final Map<Class<?>, TypeHistory<?, ?>> BY_VERSION = index(TYPES, TypeHistory::versionType);
    private static final Map<String, TypeHistory<?, ?>> BY_CODE = index(TYPES, TypeHistory::code);
    private static final Map<String, LinkHistory> LINKS_BY_CODE = index(LINKS, LinkHistory::code);

    private HistoryModel() {}

    /** Every versioned type, in rank order. */
    public static List<TypeHistory<?, ?>> types() {
        return TYPES;
    }

    public static List<LinkHistory> links() {
        return LINKS;
    }

    /** The links a type owns: a promise owns its invoice and payment links. */
    public static List<LinkHistory> linksOf(Class<?> owner) {
        return LINKS.stream().filter(l -> l.owner().equals(owner)).toList();
    }

    public static boolean isVersioned(Class<?> live) {
        return BY_LIVE.containsKey(live);
    }

    public static boolean isVersionClass(Class<?> type) {
        return BY_VERSION.containsKey(type) || type == EntityLinkVersion.class;
    }

    @SuppressWarnings("unchecked")
    public static <L> TypeHistory<L, ? extends VersionRow> of(Class<L> live) {
        TypeHistory<?, ?> history = BY_LIVE.get(live);
        if (history == null) throw new IllegalArgumentException(live.getSimpleName() + " has no history");
        return (TypeHistory<L, ? extends VersionRow>) history;
    }

    public static TypeHistory<?, ?> byCode(String code) {
        TypeHistory<?, ?> history = BY_CODE.get(code);
        if (history == null) throw new IllegalArgumentException("No history type " + code);
        return history;
    }

    public static LinkHistory linkByCode(String code) {
        LinkHistory link = LINKS_BY_CODE.get(code);
        if (link == null) throw new IllegalArgumentException("No history link " + code);
        return link;
    }

    /** What a query over {@code live} reads from: its versions while a point is bound, the live table otherwise. */
    public static Class<?> rootFor(Class<?> live) {
        return AsOf.active() && isVersioned(live) ? BY_LIVE.get(live).versionType() : live;
    }

    public static Optional<Navigation> navigation(Class<?> from, String attribute) {
        return NAVIGATIONS.stream().filter(n -> n.from() == from && n.attribute().equals(attribute)).findFirst();
    }

    private static Navigation toCustomer(Class<? extends VersionRow> from) {
        return new Navigation(from, "customer", CustomerVersion.class,
                (j, f, cb) -> cb.equal(j.get("id"), f.get("customerId")));
    }

    private static Navigation toLinks(LinkHistory link) {
        return new Navigation(PromiseVersion.class, link.collectionAttribute(), EntityLinkVersion.class,
                (j, from, cb) -> cb.and(cb.equal(j.get("kind"), link.kind()),
                        cb.equal(j.get("ownerId"), from.get("id"))));
    }

    private static <K, T> Map<K, T> index(List<T> items, Function<T, K> key) {
        Map<K, T> index = new LinkedHashMap<>();
        for (T item : items) {
            if (index.put(key.apply(item), item) != null) {
                throw new IllegalStateException("History is registered twice for " + key.apply(item));
            }
        }
        return Map.copyOf(index);
    }
}
