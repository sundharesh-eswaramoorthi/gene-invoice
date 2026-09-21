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

    @Query("""
            select distinct u from User u
              join u.role r
              join r.privileges p
            where u.active = true
              and u.customerId is null
              and p.name = :privilege
              and (:q is null
                   or lower(u.username) like :q escape '\\'
                   or lower(coalesce(u.fullName, '')) like :q escape '\\'
                   or lower(coalesce(u.email, '')) like :q escape '\\')
            order by u.fullName asc, u.username asc
            """)
    List<User> findAssignable(@Param("privilege") String privilege,
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
