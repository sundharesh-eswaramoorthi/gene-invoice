package com.geneinvoice.strategy;

import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One shared evaluation step for scheduled and manual runs: select invoices by saved status,
 * apply every configured date and amount predicate, drop strategy–invoice pairs consumed by
 * earlier successful deliveries, group the remainder by customer, and freeze one durable
 * delivery plan per recipient per customer group. A run with no remaining group produces
 * exactly one zero-match plan per admin.
 */
@Service
@RequiredArgsConstructor
public class StrategyRunPlanner {

    public static final String OUTCOME_DELIVERY = "DELIVERY";
    public static final String OUTCOME_ZERO_MATCH = "ZERO_MATCH";

    private final NotificationStrategyRepository strategyRepository;
    private final StrategyRunRepository runRepository;
    private final StrategyDeliveryPlanRepository planRepository;
    private final StrategyConsumptionRepository consumptionRepository;
    private final InvoiceRepository invoiceRepository;
    private final UserRepository userRepository;
    private final StrategyPredicateEvaluator evaluator;
    private final StrategyClock clock;

    /** Evaluates a run and durably creates its delivery plans, then marks it COMPLETED. */
    @Transactional
    public List<Long> preparePlans(Long runId) {
        StrategyRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException("Strategy run not found: " + runId));
        NotificationStrategy strategy = strategyRepository.findById(run.getStrategyId())
                .orElseThrow(() -> new NotFoundException("Strategy not found: " + run.getStrategyId()));

        List<Invoice> candidates =
                invoiceRepository.findByStatusIn(List.copyOf(strategy.getStatuses()));
        List<Invoice> matched = new ArrayList<>();
        for (Invoice inv : candidates) {
            if (evaluator.matches(inv, strategy, clock.zoneId())) {
                matched.add(inv);
            }
        }

        // Once-ever suppression keyed by strategy id: another strategy has a different key and
        // still sees these invoices.
        Set<Long> consumed = consumptionRepository.findByStrategyId(strategy.getId()).stream()
                .map(StrategyConsumption::getInvoiceId)
                .collect(Collectors.toSet());
        List<Invoice> fresh = matched.stream().filter(i -> !consumed.contains(i.getId())).toList();

        List<Long> planIds = new ArrayList<>();
        if (fresh.isEmpty()) {
            // No filter matches, or every match was already notified: admins only.
            for (User admin : userRepository.findByRoleName("ADMIN")) {
                planIds.add(savePlan(run, strategy, admin.getId(), null,
                        StrategyExecutionEngine.TYPE_ZERO_MATCH,
                        "Strategy \"" + strategy.getTitle() + "\": no new matching invoices",
                        "The run found no matching invoices, or every matching invoice was "
                                + "already notified successfully.",
                        List.of()));
            }
            run.setOutcome(OUTCOME_ZERO_MATCH);
        } else {
            Map<Long, List<Invoice>> byCustomer = fresh.stream().collect(Collectors.groupingBy(
                    inv -> inv.getCustomer().getId(), LinkedHashMap::new, Collectors.toList()));
            List<Long> adminIds = userRepository.findByRoleName("ADMIN").stream()
                    .map(User::getId).toList();
            List<Long> additional = List.copyOf(strategy.getAdditionalRecipientUserIds());
            for (Map.Entry<Long, List<Invoice>> entry : byCustomer.entrySet()) {
                // Mandatory recipients for the group: the owning customer and every admin,
                // plus the saved additional non-customer users.
                LinkedHashSet<Long> recipients = new LinkedHashSet<>();
                userRepository.findByCustomerId(entry.getKey())
                        .ifPresent(u -> recipients.add(u.getId()));
                recipients.addAll(adminIds);
                recipients.addAll(additional);

                List<Invoice> group = entry.getValue();
                String message = group.size() + " invoice(s) newly matched strategy \""
                        + strategy.getTitle() + "\" for one customer.";
                for (Long recipientId : recipients) {
                    planIds.add(savePlan(run, strategy, recipientId, entry.getKey(),
                            StrategyExecutionEngine.TYPE_STRATEGY_MATCH,
                            strategy.getTitle(), message, group));
                }
            }
            run.setOutcome(OUTCOME_DELIVERY);
        }
        run.setStatus(RunStatus.COMPLETED);
        run.setNextAttemptAt(null);
        runRepository.save(run);
        return planIds;
    }

    private Long savePlan(StrategyRun run, NotificationStrategy strategy, Long recipientUserId,
                          Long customerId, String type, String title, String message,
                          List<Invoice> invoices) {
        StrategyDeliveryPlan plan = StrategyDeliveryPlan.builder()
                .runId(run.getId())
                .strategyId(strategy.getId())
                .strategyTitle(strategy.getTitle())
                .customerId(customerId)
                .recipientUserId(recipientUserId)
                .notifType(type)
                .title(title)
                .message(message)
                .state(PlanState.PENDING)
                .attemptCount(0)
                .build();
        for (Invoice inv : invoices) {
            plan.getItems().add(StrategyDeliveryPlanItem.builder()
                    .plan(plan)
                    .invoiceId(inv.getId())
                    .invoiceNumber(inv.getInvoiceNumber())
                    .build());
        }
        return planRepository.save(plan).getId();
    }
}
