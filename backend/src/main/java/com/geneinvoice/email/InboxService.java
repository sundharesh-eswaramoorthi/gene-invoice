package com.geneinvoice.email;

import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.region.RegionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** A user's Inbox: the emails they are a To recipient of, whatever became of delivery (E11). */
@Service
@RequiredArgsConstructor
public class InboxService {

    /** How many rows one "mark all read" update names at a time. */
    static final int CHUNK = 1000;

    private final EmailRecipientRepository repository;
    private final RegionService regionService;
    private final Clock clock;

    @PersistenceContext
    private EntityManager em;

    /** {@code regionIds}: only emails about records in these regions, or about no region; null for all. */
    @Transactional(readOnly = true)
    public long unreadCount(Long userId, Collection<Long> regionIds) {
        if (regionIds == null) {
            return repository.countByUserIdAndFieldAndReadFalse(userId, RecipientField.TO);
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<EmailRecipient> row = cq.from(EmailRecipient.class);
        cq.select(cb.count(row)).where(
                cb.equal(row.get("userId"), userId),
                cb.equal(row.get("field"), RecipientField.TO),
                cb.isFalse(row.get("read")),
                EmailRegionPredicate.of(row.join("email"), cq, cb, regionIds, defaultRegionId()));
        return em.createQuery(cq).getSingleResult();
    }

    /** The region a deleted record's emails fall in when its history names none (see {@link EmailRegionPredicate}). */
    @Transactional(readOnly = true)
    public Long defaultRegionId() {
        return regionService.defaultRegion().getId();
    }

    @Transactional
    public void setRead(Long id, Long userId, boolean read) {
        Marked marked = mark(id, userId, read);
        if (marked == Marked.NOT_FOUND || marked == Marked.NOT_OURS) throw new NotFoundException("Inbox item not found");
    }

    /** Marks read only what the narrowed inbox shows: these regions and emails about none; null regions for all. */
    @Transactional
    public int markAllRead(Long userId, Collection<Long> regionsOrNull) {
        Instant now = clock.instant();
        if (regionsOrNull == null) return repository.markAllRead(userId, now);
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<EmailRecipient> row = cq.from(EmailRecipient.class);
        cq.select(row.get("id")).where(
                cb.equal(row.get("userId"), userId),
                cb.equal(row.get("field"), RecipientField.TO),
                cb.isFalse(row.get("read")),
                EmailRegionPredicate.onRecipient(regionsOrNull, defaultRegionId()).build(row, cq, cb));
        List<Long> ids = em.createQuery(cq).getResultList();
        int updated = 0;
        for (int from = 0; from < ids.size(); from += CHUNK) {
            updated += repository.markReadAmong(ids.subList(from, Math.min(from + CHUNK, ids.size())), userId, now);
        }
        return updated;
    }

    enum Marked { CHANGED, ALREADY, NOT_FOUND, NOT_OURS }

    Marked mark(Long id, Long userId, boolean read) {
        int changed = read ? repository.markRead(id, userId, clock.instant()) : repository.markUnread(id, userId);
        if (changed > 0) return Marked.CHANGED;
        return repository.findById(id)
                .map(r -> isInboxRowOf(r, userId) ? Marked.ALREADY : Marked.NOT_OURS)
                .orElse(Marked.NOT_FOUND);
    }

    static boolean isInboxRowOf(EmailRecipient row, Long userId) {
        return userId.equals(row.getUserId()) && row.getField() == RecipientField.TO;
    }
}
