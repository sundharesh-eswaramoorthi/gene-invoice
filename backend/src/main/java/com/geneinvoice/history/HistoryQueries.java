package com.geneinvoice.history;

import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.history.types.SeatHistory;
import com.geneinvoice.history.versions.CustomerPocVersion;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * The figures a record's DTO sums over other records, as they were at a point: the as-of twins of the live
 * repository queries they replace (§5.7.17). Each reads versions valid at the point; every {@code in} is chunked.
 */
@Component
public class HistoryQueries {

    /** {@code InvoiceRepository.sumOutstandingByCustomer}, over the invoices as they were. */
    private static final String OUTSTANDING = """
            select v.customerId, coalesce(sum(v.total - v.paidAmount), 0)
              from InvoiceVersion v
             where v.customerId in :ids
               and v.status <> :cancelled
               and v.validFrom <= :p and v.validTo > :p
             group by v.customerId
            """;

    /** {@code InvoiceRepository.sumOverdueByCustomer}, over the invoices as they were, overdue on {@code today}. */
    private static final String OVERDUE = """
            select v.customerId, coalesce(sum(v.total - v.paidAmount), 0)
              from InvoiceVersion v
             where v.customerId in :ids
               and v.status <> :cancelled
               and v.dueDate < :today
               and v.total - v.paidAmount > 0
               and v.validFrom <= :p and v.validTo > :p
             group by v.customerId
            """;

    @PersistenceContext
    private EntityManager em;

    private final VersionStore versions;
    private final UserRepository users;

    public HistoryQueries(VersionStore versions, UserRepository users) {
        this.versions = versions;
        this.users = users;
    }

    /** Per customer id: the balance of its invoices that were not cancelled, as they were at {@code p}. */
    @Transactional(readOnly = true)
    public List<Object[]> sumOutstandingByCustomerAt(Collection<Long> customerIds, AsOfPoint p) {
        List<Object[]> rows = new ArrayList<>();
        for (List<Long> chunk : chunks(customerIds)) {
            rows.addAll(em.createQuery(OUTSTANDING, Object[].class)
                    .setParameter("ids", chunk)
                    .setParameter("cancelled", InvoiceStatus.CANCELLED)
                    .setParameter("p", p.instant())
                    .getResultList());
        }
        return rows;
    }

    /** Per customer id: the balance overdue on {@code today} (the viewed day), of the invoices as they were at p. */
    @Transactional(readOnly = true)
    public List<Object[]> sumOverdueByCustomerAt(Collection<Long> customerIds, LocalDate today, AsOfPoint p) {
        List<Object[]> rows = new ArrayList<>();
        for (List<Long> chunk : chunks(customerIds)) {
            rows.addAll(em.createQuery(OVERDUE, Object[].class)
                    .setParameter("ids", chunk)
                    .setParameter("cancelled", InvoiceStatus.CANCELLED)
                    .setParameter("today", today)
                    .setParameter("p", p.instant())
                    .getResultList());
        }
        return rows;
    }

    /**
     * The customers' POC seats as they were at {@code p}, ordered as the POC tab orders them (type, the primary
     * first, then oldest), each held by the person as they are now.
     */
    @Transactional(readOnly = true)
    public List<CustomerPoc> seatsAt(Collection<Long> customerIds, AsOfPoint p) {
        Instant at = p.instant();
        List<CustomerPocVersion> seats = new ArrayList<>(
                versions.validAtBy(CustomerPocVersion.class, "customerId", customerIds, at));
        seats.sort(Comparator
                .comparing((CustomerPocVersion v) -> v.getPocType() == null ? "" : v.getPocType().name())
                .thenComparing(v -> !Boolean.TRUE.equals(v.getPrimary()))
                .thenComparing(VersionRow::getId));
        SeatHistory history = (SeatHistory) HistoryModel.of(CustomerPoc.class);
        return history.rehydrate(seats, new Rehydration(p, versions, em, users));
    }

    private static List<List<Long>> chunks(Collection<Long> ids) {
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(ids));
        distinct.removeIf(Objects::isNull);
        List<List<Long>> chunks = new ArrayList<>();
        for (int from = 0; from < distinct.size(); from += VersionStore.CHUNK) {
            chunks.add(distinct.subList(from, Math.min(distinct.size(), from + VersionStore.CHUNK)));
        }
        return chunks;
    }
}
