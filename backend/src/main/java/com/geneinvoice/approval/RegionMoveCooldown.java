package com.geneinvoice.approval;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The regions a customer was moved out of lately (D7, SC-4): for {@code app.approval.move-cooldown-days} after a
 * move the gate still applies the stricter of those regions' limits, so moving a customer somewhere laxer, changing
 * it and moving it back is still held. Read from the append-only {@code customer_region_moves}.
 */
@Component
public class RegionMoveCooldown {

    @PersistenceContext
    private EntityManager em;

    private final int cooldownDays;

    public RegionMoveCooldown(@Value("${app.approval.move-cooldown-days:30}") int cooldownDays) {
        this.cooldownDays = cooldownDays;
    }

    /**
     * Every region the customer left within the cool-down before {@code now}, most recent first; usually none, and
     * one after a single move. Every one of them counts, so two quick moves cannot shed the first region's limits.
     */
    public List<Long> previousRegions(Long customerId, Instant now) {
        if (customerId == null || cooldownDays <= 0) return List.of();
        return em.createQuery("""
                        select m.fromRegionId, max(m.movedAt) from CustomerRegionMove m
                        where m.customerId = :cid and m.movedAt > :since and m.movedAt <= :now
                          and m.fromRegionId is not null
                        group by m.fromRegionId order by max(m.movedAt) desc""", Object[].class)
                .setParameter("cid", customerId)
                .setParameter("since", now.minus(cooldownDays, ChronoUnit.DAYS))
                .setParameter("now", now)
                .getResultList().stream().map(row -> (Long) row[0]).toList();
    }
}
