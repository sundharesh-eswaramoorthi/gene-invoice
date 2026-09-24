package com.geneinvoice.region;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserRegionGrantRepository extends JpaRepository<UserRegionGrant, Long> {

    /**
     * "May that OTHER person work in this region?" — the POC picker's and the approver check's
     * question, which reads the table because the subject is not the caller (B1).
     *
     * <p>The ladder is spelled out rather than compared on the ordinal: MANAGE and APPROVE each
     * cover VIEW, and neither covers the other, exactly as {@link RegionRight#covers} says. A
     * null region_id is the wildcard grant and covers every region (B1).
     */
    @Query("""
            select count(g) > 0 from UserRegionGrant g
             where g.userId = :userId
               and (g.regionId is null or g.regionId = :regionId)
               and (g.right = :atLeast
                    or (g.right = com.geneinvoice.region.RegionRight.APPROVE
                        and :atLeast = com.geneinvoice.region.RegionRight.VIEW)
                    or (g.right = com.geneinvoice.region.RegionRight.MANAGE
                        and :atLeast = com.geneinvoice.region.RegionRight.VIEW))
            """)
    boolean covers(@Param("userId") Long userId,
                   @Param("regionId") Long regionId,
                   @Param("atLeast") RegionRight atLeast);

    List<UserRegionGrant> findByUserId(Long userId);

    /**
     * How many OTHER accounts still hold this right in EVERY branch — a null-region grant, which
     * covers branches that do not exist yet. The last-administrator guard's only question (B1).
     *
     * <p>Active internal accounts only, and that is the point rather than tidiness: a deactivated
     * login cannot sign in to use the grant, and a customer login holds no grants at all and is
     * not a person who administers anything. Counting either would let the last holder who can
     * actually act be stripped behind a row that can never act, which is the exact hole the guard
     * exists to close. The subject of the edit is excluded because the call is asking what would
     * be left AFTER their grants are replaced (B1, B2).
     */
    @Query("""
            select count(g) from UserRegionGrant g
             where g.regionId is null
               and g.right = :right
               and g.userId <> :excludingUserId
               and exists (select 1 from User u
                            where u.id = g.userId and u.active = true and u.customerId is null)
            """)
    long countOtherWildcardHolders(@Param("right") RegionRight right,
                                   @Param("excludingUserId") Long excludingUserId);
}
