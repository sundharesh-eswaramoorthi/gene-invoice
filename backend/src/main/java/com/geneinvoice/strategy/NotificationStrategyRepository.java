package com.geneinvoice.strategy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationStrategyRepository extends JpaRepository<NotificationStrategy, Long> {
    List<NotificationStrategy> findByActiveTrue();
    List<NotificationStrategy> findAllByOrderByCreatedAtDesc();
}
