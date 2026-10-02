package com.geneinvoice.region;

import com.geneinvoice.user.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Every caller short-circuits an empty collection before calling a query that takes one, so no query here
 * is ever run with {@code in ()}.
 */
public interface UserRegionGrantRepository extends JpaRepository<UserRegionGrant, Long> {

    /** The one grants query a principal costs (C-4). */
    @Query("select new com.geneinvoice.region.GrantRow(g.region.id, g.accessLevel, g.recordScope)"
            + " from UserRegionGrant g where g.user.id = :userId")
    List<GrantRow> rowsFor(@Param("userId") Long userId);

    Optional<UserRegionGrant> findByUserIdAndRegionId(Long userId, Long regionId);

    @Query("select g from UserRegionGrant g join fetch g.region where g.user.id = :userId order by g.region.code")
    List<UserRegionGrant> findWithRegionByUserId(@Param("userId") Long userId);

    @Query("select g from UserRegionGrant g join fetch g.user u where g.region.id = :regionId order by u.username")
    List<UserRegionGrant> findWithUserByRegionId(@Param("regionId") Long regionId);

    @Query("""
            select distinct g.user.id from UserRegionGrant g
            where g.user.id in :userIds and g.region.id = :regionId and g.accessLevel in :levels
            """)
    List<Long> usersWithAccess(@Param("userIds") Collection<Long> userIds,
                               @Param("regionId") Long regionId,
                               @Param("levels") Collection<AccessLevel> levels);

    @Query("""
            select count(g) > 0 from UserRegionGrant g
              join g.user u
              join u.role r
              join r.privileges p
            where u.id = :userId and g.region.id = :regionId and p.name = :privilege and g.accessLevel in :levels
            """)
    boolean hasPrivilegeInRegion(@Param("userId") Long userId,
                                 @Param("privilege") String privilege,
                                 @Param("regionId") Long regionId,
                                 @Param("levels") Collection<AccessLevel> levels);

    @Query("""
            select distinct u from UserRegionGrant g
              join g.user u
              join u.role r
              join r.privileges p
            where g.region.id in :regionIds
              and g.accessLevel in :levels
              and u.active = true
              and u.customerId is null
              and p.name = :privilege
              and (:q is null
                   or lower(u.username) like :q escape '\\'
                   or lower(coalesce(u.fullName, '')) like :q escape '\\'
                   or lower(coalesce(u.email, '')) like :q escape '\\')
            order by u.fullName asc, u.username asc
            """)
    List<User> findAssignableInRegions(@Param("privilege") String privilege,
                                       @Param("regionIds") Collection<Long> regionIds,
                                       @Param("levels") Collection<AccessLevel> levels,
                                       @Param("q") String lowercaseLikePattern,
                                       Pageable pageable);

    @Query("""
            select distinct u from UserRegionGrant g
              join g.user u
              join u.role r
              join r.privileges p
            where g.region.id = :regionId
              and g.accessLevel in :levels
              and p.name = :privilege
              and u.active = true
              and u.customerId is null
            """)
    List<User> findHoldersInRegion(@Param("privilege") String privilege,
                                   @Param("regionId") Long regionId,
                                   @Param("levels") Collection<AccessLevel> levels);

    @Query("""
            select distinct u from UserRegionGrant g
              join g.user u
            where g.region.id in :regionIds
              and u.active = true
              and u.customerId is null
              and (:q is null
                   or lower(u.username) like :q escape '\\'
                   or lower(coalesce(u.fullName, '')) like :q escape '\\'
                   or lower(coalesce(u.email, '')) like :q escape '\\')
            order by u.fullName asc, u.username asc
            """)
    List<User> findStaffInRegions(@Param("regionIds") Collection<Long> regionIds,
                                  @Param("q") String lowercaseLikePattern,
                                  Pageable pageable);

    /** Executed at once, so a following insert of the same (user, region) pair cannot collide (F7). */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("delete from UserRegionGrant g where g.user.id = :userId")
    int deleteAllOfUser(@Param("userId") Long userId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserRegionGrant g where g.region.id in (select r.id from Region r where r.code <> :code)")
    int deleteByRegionCodeNot(@Param("code") String code);

    long countByRegionId(Long regionId);

    long countByRegionIdAndUserIdNot(Long regionId, Long userId);

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("delete from UserRegionGrant g where g.region.id = :regionId")
    int deleteAllInRegion(@Param("regionId") Long regionId);
}
