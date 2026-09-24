package com.geneinvoice.region;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RegionRepository extends JpaRepository<Region, Long> {

    /** How the seeder and the schema upgrade find the default region idempotently (B1). */
    Optional<Region> findByCode(String code);

    /** Just the code, for a rejection message that must not load the whole row (B1). */
    @Query("select r.code from Region r where r.id = :id")
    String codeOf(@Param("id") Long id);
}
