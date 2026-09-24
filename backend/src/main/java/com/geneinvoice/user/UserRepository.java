package com.geneinvoice.user;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);

    /**
     * Usernames are unique whatever their case, the way emails already are: "ADMIN" must not be
     * creatable beside "admin", and signing in must find the one account either way (CP-06).
     */
    Optional<User> findByUsernameIgnoreCase(String username);
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByUsernameIgnoreCaseAndIdNot(String username, Long id);
    boolean existsByEmail(String email);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);
    long countByRoleId(Long roleId);

    @Query("select u.id from User u where u.role.id = :roleId and u.customerId is null order by u.id")
    List<Long> findInternalIdsByRoleId(@Param("roleId") Long roleId);

    @Modifying
    @Query("update User u set u.email = null where trim(u.email) = ''")
    int clearBlankEmails();
    Optional<User> findByCustomerId(Long customerId);
    List<User> findByRoleName(String roleName);

    /**
     * The POC picker, narrowed to the people who can actually work on the account. A seat is only
     * worth offering to somebody who may MANAGE the branch the customer is in, so the directory is
     * semi-joined to user_region_grants rather than filtered by a column on customer_pocs — a
     * seat's region IS its customer's, by definition, and a column would be a second source of
     * truth that goes stale on every move (B1).
     *
     * <p>{@code right <> VIEW} rather than {@code = MANAGE}: the ladder is deliberately not a
     * total order, and somebody who may APPROVE in a branch demonstrably works there. The seat
     * itself is still checked at MANAGE by PocService.requireAssignable, which is the gate; this
     * query is the picker, and offering one name too many is better than hiding a colleague (B1).
     *
     * <p>A null regionId means the branch was not named — a wildcard caller browsing the whole
     * directory — and then the EXISTS asks only that the person works SOMEWHERE at more than
     * VIEW, so nobody is offered who could not be a POC anywhere at all (B1).
     */
    @Query("""
            select distinct u from User u
              join u.role r
              join r.privileges p
            where u.active = true
              and u.customerId is null
              and p.name = :privilege
              and exists (select 1 from UserRegionGrant g
                           where g.userId = u.id
                             and (:regionId is null or g.regionId is null or g.regionId = :regionId)
                             and g.right <> com.geneinvoice.region.RegionRight.VIEW)
              and (:q is null
                   or lower(u.username) like :q escape '\\'
                   or lower(coalesce(u.fullName, '')) like :q escape '\\'
                   or lower(coalesce(u.email, '')) like :q escape '\\')
            order by u.fullName asc, u.username asc
            """)
    List<User> findAssignableInRegion(@Param("privilege") String privilege,
                                      @Param("regionId") Long regionId,
                                      @Param("q") String lowercaseLikePattern,
                                      Pageable pageable);

    @Query("""
            select count(u) > 0 from User u
              join u.role r
              join r.privileges p
            where u.id = :userId and p.name = :privilege
            """)
    boolean hasPrivilege(@Param("userId") Long userId, @Param("privilege") String privilege);

    @Query("""
            select count(distinct u.id) from User u
              join u.role r
              join r.privileges p
            where u.active = true
              and u.customerId is null
              and p.name = :privilege
              and (:excludeUserId is null or u.id <> :excludeUserId)
              and (:excludeRoleId is null or r.id <> :excludeRoleId)
            """)
    long countActiveHolders(@Param("privilege") String privilege,
                            @Param("excludeUserId") Long excludeUserId,
                            @Param("excludeRoleId") Long excludeRoleId);
}
