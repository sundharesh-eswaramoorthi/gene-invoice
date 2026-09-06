package com.geneinvoice.strategy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StrategyConsumptionRepository extends JpaRepository<StrategyConsumption, Long> {
    List<StrategyConsumption> findByStrategyId(Long strategyId);
    boolean existsByStrategyIdAndInvoiceId(Long strategyId, Long invoiceId);
}
