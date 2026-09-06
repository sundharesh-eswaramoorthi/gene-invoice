package com.geneinvoice.strategy;

import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers one frozen per-recipient plan through the existing in-app notification feed. The
 * recipient-notification insert and the strategy–invoice consumption writes commit atomically,
 * so a failure leaves the plan retryable and no pair is ever consumed without a delivered
 * message.
 */
@Service
@RequiredArgsConstructor
public class StrategyDeliveryExecutor {

    private final StrategyDeliveryPlanRepository planRepository;
    private final StrategyConsumptionRepository consumptionRepository;
    private final NotificationService notificationService;

    @Transactional
    public void deliver(Long planId) {
        StrategyDeliveryPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new NotFoundException("Delivery plan not found: " + planId));
        if (plan.getItems().isEmpty()) {
            // Zero-match / plain envelopes ride the existing plain insertion path.
            notificationService.notify(plan.getRecipientUserId(), plan.getNotifType(),
                    plan.getTitle(), plan.getMessage(), plan.getLink());
        } else {
            notificationService.notifyStrategy(plan.getRecipientUserId(), plan.getNotifType(),
                    plan.getTitle(), plan.getMessage(), plan.getLink(),
                    plan.getItems().stream()
                            .map(i -> new NotificationService.InvoiceRef(
                                    i.getInvoiceId(), i.getInvoiceNumber()))
                            .toList());
        }
        for (StrategyDeliveryPlanItem item : plan.getItems()) {
            if (!consumptionRepository.existsByStrategyIdAndInvoiceId(
                    plan.getStrategyId(), item.getInvoiceId())) {
                consumptionRepository.save(StrategyConsumption.builder()
                        .strategyId(plan.getStrategyId())
                        .invoiceId(item.getInvoiceId())
                        .build());
            }
        }
        plan.setState(PlanState.DELIVERED);
        planRepository.save(plan);
    }
}
