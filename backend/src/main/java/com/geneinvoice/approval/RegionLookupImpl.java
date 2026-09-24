package com.geneinvoice.approval;

import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.dispute.DisputeRepository;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.promise.PaymentPromiseRepository;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.RegionRights;
import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.region.UserRegionGrantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The one file in com.geneinvoice.approval that knows regions exist. Everything else in the
 * package asks {@link RegionLookup} and gets Longs back, which is what keeps the dependency edge
 * approval -&gt; region to a single class a reviewer can read in one sitting (B2, B1).
 *
 * <p>Read-only transactional throughout: every method walks a LAZY association or two, and
 * open-in-view is off, so a caller outside a transaction — a test, a scheduled job — would
 * otherwise meet a LazyInitializationException instead of an answer (B2).
 */
@Component
@RequiredArgsConstructor
public class RegionLookupImpl implements RegionLookup {

    @PersistenceContext
    private EntityManager em;

    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentPromiseRepository promiseRepository;
    private final DisputeRepository disputeRepository;
    private final RegionRepository regionRepository;
    private final UserRegionGrantRepository userRegionGrantRepository;

    /**
     * Region is anchored on customers and nowhere else, so every branch below is one walk to a
     * customer. {@code .getId()} on the LAZY Region proxy initialises nothing — the id is the
     * proxy's own key — so this costs one select and never loads a region row (B2, B1).
     *
     * <p>The wording of each NotFoundException is the mutator's own, because the gate runs inside
     * the mutator and a caller must not be able to tell the two apart (B2).
     */
    @Override
    @Transactional(readOnly = true)
    public Long regionOf(PendingTargetType type, Long id) {
        return switch (type) {
            // A threshold change names its region outright; there is no record to walk (B2).
            case REGION -> id;
            case CUSTOMER -> customerRepository.findById(id)
                    .map(c -> c.getRegion().getId())
                    .orElseThrow(() -> new NotFoundException("Customer not found"));
            case INVOICE -> invoiceRepository.findById(id)
                    .map(i -> i.getCustomer().getRegion().getId())
                    .orElseThrow(() -> new NotFoundException("Invoice not found"));
            case PAYMENT -> paymentRepository.findById(id)
                    .map(p -> p.getCustomer().getRegion().getId())
                    .orElseThrow(() -> new NotFoundException("Payment not found"));
            case PROMISE -> promiseRepository.findById(id)
                    .map(p -> p.getCustomer().getRegion().getId())
                    .orElseThrow(() -> new NotFoundException("Payment promise not found: " + id));
            // A dispute carries a flat customer_id and no association, so it takes two reads (B2).
            case DISPUTE -> regionOf(PendingTargetType.CUSTOMER,
                    disputeRepository.findById(id)
                            .orElseThrow(() -> new NotFoundException("Dispute not found"))
                            .getCustomerId());
        };
    }

    // covers() already treats a null region_id as the wildcard, so the "plus the wildcard" half of
    // this question is the query's business and not a second branch here (B2, B1).
    @Override
    @Transactional(readOnly = true)
    public boolean hasAnyRight(Long userId, Long regionId) {
        if (userId == null || regionId == null) return false;
        return userRegionGrantRepository.covers(userId, regionId, RegionRight.VIEW);
    }

    /**
     * Who to tell, and who may decide. Both halves must hold: the privilege says WHAT, the grant
     * says WHERE, and an approver who has the privilege but works in another branch is not an
     * approver here (B2, B1).
     *
     * <p>Inactive accounts and customer logins are excluded by the query rather than by the
     * caller, because every caller would have to remember and one of them would not (B2).
     */
    @Override
    @Transactional(readOnly = true)
    public List<Long> usersWith(String privilege, Long regionId) {
        if (privilege == null || regionId == null) return List.of();
        RegionRight needed = RegionRights.needed(privilege);   // throws for an unclassified name
        if (needed == null) {
            // Company-wide: the privilege is exercisable everywhere, so there is nothing to narrow.
            return em.createQuery("""
                            select distinct u.id from User u
                              join u.role r
                              join r.privileges p
                             where u.active = true and u.customerId is null and p.name = :privilege
                             order by u.id
                            """, Long.class)
                    .setParameter("privilege", privilege)
                    .getResultList();
        }
        // The ladder is read off RegionRight.covers rather than re-spelled in JPQL, so the day a
        // fourth right is added there is one place that decides what covers what (B2, B1).
        List<RegionRight> covering = Arrays.stream(RegionRight.values())
                .filter(r -> r.covers(needed))
                .toList();
        return em.createQuery("""
                        select distinct u.id from User u
                          join u.role r
                          join r.privileges p
                         where u.active = true and u.customerId is null and p.name = :privilege
                           and exists (select 1 from UserRegionGrant g
                                        where g.userId = u.id
                                          and (g.regionId is null or g.regionId = :regionId)
                                          and g.right in :covering)
                         order by u.id
                        """, Long.class)
                .setParameter("privilege", privilege)
                .setParameter("regionId", regionId)
                .setParameter("covering", covering)
                .getResultList();
    }

    /**
     * The branches this person may decide in. A wildcard APPROVE holder gets every ACTIVE region
     * — including ones opened after their grant was written, which is the whole point of a
     * null-region grant — and a retired region is left out because nothing new is raised there
     * and an empty extra chip helps nobody (B2, B1).
     */
    @Override
    @Transactional(readOnly = true)
    public List<Long> approvableRegions(Long userId) {
        if (userId == null) return List.of();
        List<UserRegionGrant> grants = userRegionGrantRepository.findByUserId(userId);
        boolean everywhere = grants.stream().anyMatch(
                g -> g.getRegionId() == null && g.getRight() != null
                        && g.getRight().covers(RegionRight.APPROVE));
        if (everywhere) {
            return regionRepository.findAll().stream()
                    .filter(Region::isActive)
                    .map(Region::getId)
                    .toList();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (UserRegionGrant g : grants) {
            if (g.getRegionId() != null && g.getRight() != null
                    && g.getRight().covers(RegionRight.APPROVE)) {
                ids.add(g.getRegionId());
            }
        }
        return new ArrayList<>(ids);
    }

    // Null rather than a placeholder for a region that is not there: the callers build a sentence,
    // and "the null approval limit" is worse than a sentence that leaves the name out (B2).
    @Override
    @Transactional(readOnly = true)
    public String regionName(Long regionId) {
        if (regionId == null) return null;
        return regionRepository.findById(regionId).map(Region::getName).orElse(null);
    }
}
