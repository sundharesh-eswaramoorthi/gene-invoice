package com.geneinvoice.strategy;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Durable once-ever marker: one row per strategy–invoice pair whose in-app delivery succeeded.
 * Consulted only by later runs; no cross-run locking is introduced, so overlapping runs may
 * race and duplicate delivery, which is the sanctioned behaviour.
 */
@Entity
@Table(name = "strategy_invoice_consumption", uniqueConstraints = {
        @UniqueConstraint(name = "uq_strategy_invoice", columnNames = {"strategy_id", "invoice_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyConsumption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_id", nullable = false)
    private Long strategyId;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
