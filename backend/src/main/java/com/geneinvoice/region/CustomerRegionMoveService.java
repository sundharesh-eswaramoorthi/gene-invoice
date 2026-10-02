package com.geneinvoice.region;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.history.HistoryTracker;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.promise.PaymentPromise;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Moves a customer, and every record of it, to another region (§5.6.8, D7). One transaction: the customer is
 * locked first and then its invoices (the order every other multi-row write takes, PPD-01), the rows are moved
 * with SQL because no ORM update writes a region (F3), and each moved row is named to {@link HistoryTracker} so
 * its history gets a version in the new region at the move's instant. Nothing about who holds what is changed:
 * people who cannot reach the new region are only named in the warnings.
 */
@Service
public class CustomerRegionMoveService {

    @PersistenceContext
    private EntityManager em;

    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final CustomerRegionMoveRepository moveRepository;
    private final CustomerService customerService;
    private final RegionService regionService;
    private final RegionAccessQueries regionAccess;
    private final ScopeResolver scopeResolver;
    private final CurrentUser currentUser;
    private final HistoryTracker historyTracker;
    private final AuditService auditService;
    private final ObjectProvider<RegionMoveParticipant> participants;
    private final Clock clock;

    public CustomerRegionMoveService(CustomerRepository customerRepository, InvoiceRepository invoiceRepository,
                                     CustomerRegionMoveRepository moveRepository, CustomerService customerService,
                                     RegionService regionService, RegionAccessQueries regionAccess,
                                     ScopeResolver scopeResolver, CurrentUser currentUser,
                                     HistoryTracker historyTracker, AuditService auditService,
                                     ObjectProvider<RegionMoveParticipant> participants, Clock clock) {
        this.customerRepository = customerRepository;
        this.invoiceRepository = invoiceRepository;
        this.moveRepository = moveRepository;
        this.customerService = customerService;
        this.regionService = regionService;
        this.regionAccess = regionAccess;
        this.scopeResolver = scopeResolver;
        this.currentUser = currentUser;
        this.historyTracker = historyTracker;
        this.auditService = auditService;
        this.participants = participants;
        this.clock = clock;
    }

    /** What the customer's history row says about the move. */
    public record RegionSnapshot(Long regionId, String regionCode) {}

    public record MovedSnapshot(Long regionId, String regionCode, RegionMoveDtos.Moved moved) {}

    @Transactional
    public RegionMoveDtos.MoveResult move(Long id, RegionMoveDtos.MoveRequest req) {
        ViewScopeHolder.requireLive();
        Customer customer = customerRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        Region source = customer.getRegion();
        Long from = Region.idOf(source);
        scopeResolver.requireVisible(ScopeResolver.Kind.CUSTOMER, id, from, "Customer not found");
        currentUser.requireIn(Privileges.CUSTOMER_MANAGE, from);

        Long to = req.regionId();
        currentUser.requireReachable(to, "Region not found");
        Region target = regionService.get(to);
        currentUser.requireIn(Privileges.CUSTOMER_MANAGE, to);
        if (!target.isActive()) throw new BadRequestException("Region " + target.getName() + " is inactive");
        if (to.equals(from)) throw new BadRequestException("The customer is already in " + target.getName());
        if (req.version() != null && !req.version().equals(customer.getVersion())) {
            throw new OptimisticLockingFailureException("Customer " + id + " changed since it was read");
        }
        String customerName = customer.getName();
        RegionSnapshot before = new RegionSnapshot(from, source.getCode());

        List<Long> invoiceIds = invoiceRepository.findByCustomerIdForUpdate(id).stream().map(Invoice::getId).toList();
        List<Long> paymentIds = idsOf("select p.id from Payment p where p.customer.id = :cid order by p.id", id);
        List<Long> promiseIds = idsOf("select p.id from PaymentPromise p where p.customer.id = :cid order by p.id",
                id);
        List<Long> disputeIds = idsOf("select d.id from Dispute d where d.customerId = :cid order by d.id", id);

        List<RegionMoveParticipant> all = participants.orderedStream().toList();
        all.forEach(p -> p.beforeCustomerMoved(id, from, to));
        List<String> warnings = warnings(id, customerName, target);

        int invoices = moveRepository.moveInvoices(id, to);
        int payments = moveRepository.movePayments(id, to);
        int promises = moveRepository.movePromises(id, to);
        int disputes = moveRepository.moveDisputes(id, to);
        moveRepository.moveCustomer(id, to);
        // The rows are locked by the updates above; their versions are written from what the SQL left at commit.
        historyTracker.touch(Customer.class, List.of(id), false);
        historyTracker.touch(Invoice.class, invoiceIds, false);
        historyTracker.touch(Payment.class, paymentIds, false);
        historyTracker.touch(PaymentPromise.class, promiseIds, false);
        historyTracker.touch(Dispute.class, disputeIds, false);

        Long mover = currentUser.require().getId();
        moveRepository.save(CustomerRegionMove.builder()
                .customerId(id)
                .fromRegionId(from)
                .toRegionId(to)
                // The versions' instant is bound only at commit, once every lock is held; stored as precisely.
                .movedAt(clock.instant().truncatedTo(ChronoUnit.MICROS))
                .movedByUserId(mover)
                .build());

        Map<String, Integer> other = new TreeMap<>();
        for (RegionMoveParticipant p : all) other.merge(p.name(), p.afterCustomerMoved(id, from, to), Integer::sum);
        RegionMoveDtos.Moved moved = new RegionMoveDtos.Moved(invoices, payments, promises, disputes, other);

        // The updates cleared the session: read the customer again, as it now is.
        Customer movedCustomer = customerRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        auditService.record(CustomerService.ENTITY, id, "CUSTOMER_REGION_CHANGED", before,
                new MovedSnapshot(to, target.getCode(), moved), mover, null, req.reason(), to);
        return new RegionMoveDtos.MoveResult(customerService.toDto(movedCustomer), moved, warnings);
    }

    private List<Long> idsOf(String jpql, Long customerId) {
        return em.createQuery(jpql, Long.class).setParameter("cid", customerId).getResultList();
    }

    /**
     * Everyone who holds a seat on the customer, or is the POC of any of its records, and holds no grant in the
     * new region: they keep what they hold, and the mover is told they can no longer reach it.
     */
    private List<String> warnings(Long customerId, String customerName, Region target) {
        List<Holder> holders = new ArrayList<>();
        for (Object[] row : em.createQuery("""
                        select u.id, u.username, s.pocType from CustomerPoc s join s.user u
                         where s.customer.id = :cid order by s.pocType, u.username""", Object[].class)
                .setParameter("cid", customerId).getResultList()) {
            holders.add(new Holder((Long) row[0], ((PocType) row[2]).label() + " of " + customerName,
                    (String) row[1]));
        }
        holders.addAll(recordPocs("Invoice", "salesPoc", PocType.SALES, "invoice", customerId));
        holders.addAll(recordPocs("Payment", "collectionPoc", PocType.COLLECTION, "payment", customerId));
        holders.addAll(recordPocs("PaymentPromise", "collectionPoc", PocType.COLLECTION, "promise", customerId));
        if (holders.isEmpty()) return List.of();

        Set<Long> reaching = regionAccess.withAccess(holders.stream().map(Holder::userId).toList(), target.getId());
        return holders.stream()
                .filter(h -> !reaching.contains(h.userId()))
                .map(h -> h.username() + " is " + h.what() + " but has no access to " + target.getName())
                .toList();
    }

    private record Holder(Long userId, String what, String username) {}

    /** The distinct POCs of one kind of the customer's records, each with how many of them they hold. */
    private Collection<Holder> recordPocs(String entity, String attribute, PocType type, String noun,
                                          Long customerId) {
        List<Holder> holders = new ArrayList<>();
        for (Object[] row : em.createQuery("select u.id, u.username, count(r) from " + entity + " r join r."
                        + attribute + " u where r.customer.id = :cid group by u.id, u.username order by u.username",
                        Object[].class)
                .setParameter("cid", customerId).getResultList()) {
            long count = (Long) row[2];
            holders.add(new Holder((Long) row[0],
                    type.label() + " on " + count + " " + noun + (count == 1 ? "" : "s"), (String) row[1]));
        }
        return holders;
    }
}
