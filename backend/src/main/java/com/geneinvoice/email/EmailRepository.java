package com.geneinvoice.email;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmailRepository extends JpaRepository<Email, Long> {
    List<Email> findByCustomerId(Long customerId);
}
