package com.geneinvoice.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
    boolean existsByUsername(String username);

    /** Who last set this login's password for it, when it was not its owner (§5.11.8). */
    @Query("select u.credentialsSetByUserId from User u where u.id = :id")
    Optional<Long> findCredentialsSetBy(@Param("id") Long userId);

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
            select count(u) > 0 from User u
              join u.role r
              join r.privileges p
            where u.id = :userId and p.name = :privilege
            """)
    boolean hasPrivilege(@Param("userId") Long userId, @Param("privilege") String privilege);

    /** Active staff whose role holds a global privilege: who hears that a rule was suspended (§5.10.7). */
    @Query("""
            select distinct u from User u
              join u.role r
              join r.privileges p
            where u.active = true and u.customerId is null and p.name = :privilege
            order by u.id
            """)
    List<User> findActiveStaffHolding(@Param("privilege") String privilege);

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
