package com.geneinvoice.region;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface RegionRepository extends JpaRepository<Region, Long> {

    @Query("select r from Region r where r.defaultRegion = true")
    Optional<Region> findByIsDefaultTrue();

    Optional<Region> findByCode(String code);

    /** Every change to a region is made under its row lock, so a change and a make-default never interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Region r where r.id = :id")
    Optional<Region> findByIdForUpdate(@Param("id") Long id);

    /** A region and the current default, locked in id order so two make-defaults cannot deadlock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Region r where r.id = :id or r.defaultRegion = true order by r.id")
    List<Region> lockWithDefault(@Param("id") Long id);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    /** What keeps a region from being deleted: records still in it. Their children follow them (I1). */
    @Query("select count(c) > 0 from Customer c where c.region.id = :id")
    boolean hasCustomers(@Param("id") Long id);

    @Query("select count(d) > 0 from Dispute d where d.region.id = :id")
    boolean hasDisputes(@Param("id") Long id);

    /** Bulk updates, so the single-default invariant holds on H2 too (Postgres also has a partial index). */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Region r set r.defaultRegion = false where r.defaultRegion = true and r.id <> :id")
    int clearDefaultExcept(@Param("id") Long id);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Region r set r.defaultRegion = true where r.id = :id")
    int markDefault(@Param("id") Long id);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Region r where r.code <> :code")
    int deleteByCodeNot(@Param("code") String code);
}
