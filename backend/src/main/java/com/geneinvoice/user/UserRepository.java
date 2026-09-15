package com.geneinvoice.user;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    Optional<User> findByCustomerId(Long customerId);
    List<User> findByRoleName(String roleName);

    /**
     * Users offerable in a POC dropdown: active, not customer-scoped, and holding a role that
     * carries the assignability privilege for that POC kind. Paged so the query stays cheap on a
     * large user table (AC-A3).
     */
    @Query("""
            select distinct u from User u
              join u.role r
              join r.privileges p
            where u.active = true
              and u.customerId is null
              and p.name = :privilege
              and (:q is null
                   or lower(u.username) like :q
                   or lower(coalesce(u.fullName, '')) like :q
                   or lower(coalesce(u.email, '')) like :q)
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
}
