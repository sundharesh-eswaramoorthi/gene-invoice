package com.geneinvoice.approval;

import com.geneinvoice.common.asof.AsOf;
import com.geneinvoice.common.asof.AsOfContext;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PendingChangeRepository extends JpaRepository<PendingChange, Long> {

    /** The gate's in-transaction check. uq_pending_open is what actually holds the line when two
     *  makers both pass this, because the target's row lock is released by the rollback before the
     *  pending row is ever written (B2). */
    boolean existsByPendingKey(String pendingKey);

    Optional<PendingChange> findByPendingKey(String pendingKey);

    // The DisputeRepository:16 idiom: two approvers reaching the same change serialise on the row
    // rather than both reading PENDING and both applying it (B2).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PendingChange p where p.id = :id")
    Optional<PendingChange> findByIdForUpdate(@Param("id") Long id);

    /**
     * Whose account this change is about, WITHOUT loading the change. The decision path has to
     * lock the customer row before the change's own — that is the one lock order this application
     * takes, and RegionCustodyService.move and CustomerService.delete reach the same two tables in
     * it — so it needs the customer id before it has the row, and a findById here would put an
     * unlocked copy of the change into the persistence context first (PPD-01, B2).
     */
    @Query("select p.customerId from PendingChange p where p.id = :id")
    Optional<Long> customerIdOf(@Param("id") Long id);

    List<PendingChange> findByBatchIdAndStatus(String batchId, PendingChangeStatus status);

    List<PendingChange> findByCustomerIdAndStatus(Long customerId, PendingChangeStatus status);

    List<PendingChange> findByTargetTypeAndTargetIdAndStatus(PendingTargetType targetType,
                                                            Long targetId,
                                                            PendingChangeStatus status);

    boolean existsByCustomerIdAndStatus(Long customerId, PendingChangeStatus status);

    /**
     * Was ANYTHING waiting on this account on the date asked about — the as-of twin of
     * {@link #existsByCustomerIdAndStatus}, which can only ever answer "is one waiting now" because
     * a decided change has released its key and left PENDING behind (B2, B3).
     *
     * <p>Same coalesce-onto-OPEN expression as {@link #outstandingTargetIdList}, for the same
     * reason: an undecided row and a row decided after {@code at} have to answer identically.
     */
    @Query("""
            select count(p) > 0 from PendingChange p
             where p.customerId = :customerId
               and p.requestedAt <= :at
               and coalesce(p.decidedAt, :open) > :at
            """)
    boolean existsOutstandingOnCustomer(@Param("customerId") Long customerId,
                                        @Param("at") Instant at,
                                        @Param("open") Instant open);

    /**
     * Is anything waiting on this account — now, or on the date the reader asked about? The switch
     * is HERE for the same reason {@link #openTargetIds}' is: a caller that forgot would put
     * today's answer beside a January credit balance (B2, B3).
     */
    default boolean anyOutstandingOnCustomer(Long customerId) {
        if (AsOfContext.isActive()) {
            return existsOutstandingOnCustomer(customerId, AsOfContext.instant(), AsOf.OPEN);
        }
        return existsByCustomerIdAndStatus(customerId, PendingChangeStatus.PENDING);
    }

    @Query("""
            select p.targetId from PendingChange p
             where p.targetType = :type
               and p.status = com.geneinvoice.approval.PendingChangeStatus.PENDING
               and p.targetId in :ids
            """)
    List<Long> openTargetIdList(@Param("type") PendingTargetType type,
                                @Param("ids") Collection<Long> ids);

    /**
     * The same question asked of the decision LOG rather than of the current status: which of
     * these records had a change raised by {@code at} that nobody had decided yet.
     *
     * <p>The coalesce onto the OPEN sentinel is AsOf.outstandingAt's expression, spelled in JPQL
     * because this one is a batched id lookup and not a criteria predicate. Deliberately the same
     * expression and not an {@code is null or >} rewrite of it: an undecided row and a row decided
     * after {@code at} have to answer identically, and two spellings of one rule is the drift this
     * codebase already has a rider against. Verified on a real Postgres by B3-CONTEXT: the
     * sentinel binds as a parameter and plans, because coalesce's other argument is typed (B2, B3).
     */
    @Query("""
            select p.targetId from PendingChange p
             where p.targetType = :type
               and p.requestedAt <= :at
               and coalesce(p.decidedAt, :open) > :at
               and p.targetId in :ids
            """)
    List<Long> outstandingTargetIdList(@Param("type") PendingTargetType type,
                                       @Param("ids") Collection<Long> ids,
                                       @Param("at") Instant at,
                                       @Param("open") Instant open);

    /** Which of these records were awaiting a second pair of eyes at {@code at} (B2, B3). */
    default Set<Long> outstandingTargetIds(PendingTargetType type, Collection<Long> ids, Instant at) {
        if (ids == null || ids.isEmpty()) return Set.of();
        return new LinkedHashSet<>(outstandingTargetIdList(type, ids, at, AsOf.OPEN));
    }

    /**
     * Which of these records have a change waiting — the one query behind the approvalPending flag
     * on a whole page of rows. An empty id list answers an empty set WITHOUT going to the database,
     * because {@code in ()} is a syntax error on Postgres and a full scan waiting to happen (B2).
     *
     * <p>UNDER AN AS-OF DATE IT ASKS THE LOG INSTEAD, and it switches HERE rather than at the
     * eight call sites that fill the flag on invoices, payments, promises, customers and disputes.
     * A caller that forgot would put today's answer on a page of January rows with nothing to say
     * it had — exactly the silent-wrong-answer failure the whole feature exists to prevent. The
     * live branch is untouched and still tests {@code status = PENDING} (B2, B3).
     */
    default Set<Long> openTargetIds(PendingTargetType type, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Set.of();
        if (AsOfContext.isActive()) return outstandingTargetIds(type, ids, AsOfContext.instant());
        return new LinkedHashSet<>(openTargetIdList(type, ids));
    }
}
