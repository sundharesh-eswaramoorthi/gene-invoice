package com.geneinvoice.strategy;

import jakarta.persistence.*;
import lombok.*;

/** Frozen snapshot of one invoice belonging to a delivery plan, taken when the run was
 *  evaluated so later retries deliver exactly the original item set. */
@Entity
@Table(name = "strategy_delivery_plan_items", indexes = {
        @Index(name = "idx_plan_item_plan", columnList = "plan_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyDeliveryPlanItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id")
    private StrategyDeliveryPlan plan;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "invoice_number", nullable = false, length = 40)
    private String invoiceNumber;
}
