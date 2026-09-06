package com.geneinvoice.strategy;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Proof of the shared evaluation step: per-customer grouping with a frozen identifiable item
 * list confined to that customer (AC9 / AQ4), fan-out to the customer, every admin and each
 * selected additional user (AC5), once-ever strategy–invoice suppression (AC10 / EDGE2), and
 * the all-admin zero-match outcome when nothing newly notifiable remains (AC11 / EDGE3).
 */
@ExtendWith(MockitoExtension.class)
class StrategyRunPlannerTest {

    @Mock
    private NotificationStrategyRepository strategyRepository;
    @Mock
    private StrategyRunRepository runRepository;
    @Mock
    private StrategyDeliveryPlanRepository planRepository;
    @Mock
    private StrategyConsumptionRepository consumptionRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private UserRepository userRepository;

    private final StrategyClock clock =
            new StrategyClock(Clock.fixed(Instant.parse("2024-06-01T05:00:00Z"), ZoneOffset.UTC),
                    "05:00", "UTC");

    private StrategyRunPlanner newPlanner() {
        return new StrategyRunPlanner(strategyRepository, runRepository, planRepository,
                consumptionRepository, invoiceRepository, userRepository,
                new StrategyPredicateEvaluator(), clock);
    }

    private static Invoice invoice(long id, long customerId, String number,
                                   String date, String total, InvoiceStatus status) {
        return Invoice.builder()
                .id(id).invoiceNumber(number)
                .customer(Customer.builder().id(customerId).build())
                .invoiceDate(Instant.parse(date))
                .total(new BigDecimal(total))
                .status(status)
                .build();
    }

    private static User user(long id, String roleName, Long customerId) {
        return User.builder().id(id).username("u" + id).customerId(customerId)
                .role(Role.builder().name(roleName).build()).build();
    }

    private NotificationStrategy savedStrategy() {
        return NotificationStrategy.builder()
                .id(3L).title("Overdue large invoices")
                .statuses(new java.util.LinkedHashSet<>(Set.of(InvoiceStatus.UNPAID)))
                .dateOperator(DateOperator.AFTER).dateFrom(LocalDate.of(2024, 1, 1))
                .amountOperator(AmountOperator.GREATER_THAN).amountFrom(new BigDecimal("1000.00"))
                .additionalRecipientUserIds(new java.util.LinkedHashSet<>(Set.of(70L)))
                .active(true)
                .build();
    }

    private StrategyRun runFor(NotificationStrategy s) {
        StrategyRun run = StrategyRun.builder()
                .id(50L).strategyId(s.getId()).strategyTitle(s.getTitle())
                .trigger(RunTrigger.SCHEDULED).businessDate(LocalDate.of(2024, 6, 1))
                .status(RunStatus.ACTIVE).attemptCount(0).build();
        when(runRepository.findById(50L)).thenReturn(Optional.of(run));
        when(strategyRepository.findById(3L)).thenReturn(Optional.of(s));
        AtomicLong ids = new AtomicLong(100);
        when(planRepository.save(any())).thenAnswer(inv -> {
            StrategyDeliveryPlan p = inv.getArgument(0);
            p.setId(ids.getAndIncrement());
            return p;
        });
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return run;
    }

    @Test
    void runCreatesOneFrozenPlanPerRecipientPerCustomerGroup() {
        NotificationStrategy s = savedStrategy();
        StrategyRun run = runFor(s);
        Invoice matchA = invoice(1001L, 10L, "INV-1001", "2024-02-01T12:00:00Z", "1500.00", InvoiceStatus.UNPAID);
        Invoice tooCheap = invoice(1002L, 10L, "INV-1002", "2024-02-01T12:00:00Z", "500.00", InvoiceStatus.UNPAID);
        Invoice matchB1 = invoice(1003L, 11L, "INV-1003", "2024-02-01T12:00:00Z", "3000.00", InvoiceStatus.UNPAID);
        Invoice matchB2 = invoice(1004L, 11L, "INV-1004", "2024-03-01T12:00:00Z", "4000.00", InvoiceStatus.UNPAID);
        when(invoiceRepository.findByStatusIn(List.of(InvoiceStatus.UNPAID)))
                .thenReturn(List.of(matchA, tooCheap, matchB1, matchB2));
        when(consumptionRepository.findByStrategyId(3L)).thenReturn(List.of());
        when(userRepository.findByRoleName("ADMIN"))
                .thenReturn(List.of(user(90L, "ADMIN", null), user(91L, "ADMIN", null)));
        when(userRepository.findByCustomerId(10L)).thenReturn(Optional.of(user(60L, "CUSTOMER", 10L)));
        when(userRepository.findByCustomerId(11L)).thenReturn(Optional.empty());

        List<Long> ids = newPlanner().preparePlans(50L);

        ArgumentCaptor<StrategyDeliveryPlan> captor = ArgumentCaptor.forClass(StrategyDeliveryPlan.class);
        org.mockito.Mockito.verify(planRepository, org.mockito.Mockito.times(7)).save(captor.capture());
        assertEquals(7, ids.size());

        List<StrategyDeliveryPlan> saved = captor.getAllValues();
        List<StrategyDeliveryPlan> groupA = saved.stream()
                .filter(p -> Long.valueOf(10L).equals(p.getCustomerId())).toList();
        List<StrategyDeliveryPlan> groupB = saved.stream()
                .filter(p -> Long.valueOf(11L).equals(p.getCustomerId())).toList();

        // Customer A: its user plus both admins plus the selected additional user.
        assertEquals(Set.of(60L, 90L, 91L, 70L),
                groupA.stream().map(StrategyDeliveryPlan::getRecipientUserId).collect(java.util.stream.Collectors.toSet()));
        // Customer B has no linked user: admins plus the additional user only.
        assertEquals(Set.of(90L, 91L, 70L),
                groupB.stream().map(StrategyDeliveryPlan::getRecipientUserId).collect(java.util.stream.Collectors.toSet()));

        for (StrategyDeliveryPlan p : groupA) {
            assertEquals("STRATEGY_MATCH", p.getNotifType());
            assertEquals("Overdue large invoices", p.getTitle(), "the message carries the strategy title");
            assertEquals(PlanState.PENDING, p.getState());
            assertEquals(0, p.getAttemptCount());
            assertNull(p.getNextAttemptAt());
            assertEquals(List.of(1001L),
                    p.getItems().stream().map(StrategyDeliveryPlanItem::getInvoiceId).toList(),
                    "the frozen list is limited to this customer's matched invoices");
            assertEquals("INV-1001", p.getItems().get(0).getInvoiceNumber(),
                    "items are identifiable by invoice number");
        }
        assertEquals(Set.of(1003L, 1004L),
                groupB.get(0).getItems().stream()
                        .map(StrategyDeliveryPlanItem::getInvoiceId)
                        .collect(java.util.stream.Collectors.toSet()));

        assertEquals(RunStatus.COMPLETED, run.getStatus());
        assertEquals("DELIVERY", run.getOutcome());
    }

    @Test
    void previouslyConsumedPairsAreExcludedFromTheFrozenPlans() {
        NotificationStrategy s = savedStrategy();
        runFor(s);
        Invoice delivered = invoice(1001L, 10L, "INV-1001", "2024-02-01T12:00:00Z", "1500.00", InvoiceStatus.UNPAID);
        Invoice fresh = invoice(1003L, 11L, "INV-1003", "2024-02-01T12:00:00Z", "3000.00", InvoiceStatus.UNPAID);
        when(invoiceRepository.findByStatusIn(List.of(InvoiceStatus.UNPAID)))
                .thenReturn(List.of(delivered, fresh));
        when(consumptionRepository.findByStrategyId(3L))
                .thenReturn(List.of(StrategyConsumption.builder()
                        .strategyId(3L).invoiceId(1001L).build()));
        when(userRepository.findByRoleName("ADMIN")).thenReturn(List.of(user(90L, "ADMIN", null)));
        when(userRepository.findByCustomerId(11L)).thenReturn(Optional.empty());

        newPlanner().preparePlans(50L);

        ArgumentCaptor<StrategyDeliveryPlan> captor = ArgumentCaptor.forClass(StrategyDeliveryPlan.class);
        // customer 10's group vanished entirely; customer 11's group reaches admin + additional.
        org.mockito.Mockito.verify(planRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        for (StrategyDeliveryPlan p : captor.getAllValues()) {
            assertEquals(Long.valueOf(11L), p.getCustomerId());
            assertEquals(List.of(1003L),
                    p.getItems().stream().map(StrategyDeliveryPlanItem::getInvoiceId).toList());
        }
        // Consumption is scoped to THIS strategy: the same invoice under another strategy id is untouched.
        org.mockito.Mockito.verify(consumptionRepository).findByStrategyId(3L);
    }

    @Test
    void noMatchesOrAllPreviouslyNotifiedProducesOnlyPerAdminZeroMatchPlans() {
        NotificationStrategy s = savedStrategy();
        StrategyRun run = runFor(s);
        Invoice matching = invoice(1001L, 10L, "INV-1001", "2024-02-01T12:00:00Z", "1500.00", InvoiceStatus.UNPAID);
        when(invoiceRepository.findByStatusIn(List.of(InvoiceStatus.UNPAID)))
                .thenReturn(List.of(matching));
        when(consumptionRepository.findByStrategyId(3L))
                .thenReturn(List.of(StrategyConsumption.builder()
                        .strategyId(3L).invoiceId(1001L).build()));
        when(userRepository.findByRoleName("ADMIN"))
                .thenReturn(List.of(user(90L, "ADMIN", null), user(91L, "ADMIN", null)));

        List<Long> ids = newPlanner().preparePlans(50L);

        assertEquals(2, ids.size(), "exactly one zero-match result per admin");
        ArgumentCaptor<StrategyDeliveryPlan> captor = ArgumentCaptor.forClass(StrategyDeliveryPlan.class);
        org.mockito.Mockito.verify(planRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        for (StrategyDeliveryPlan p : captor.getAllValues()) {
            assertEquals("STRATEGY_ZERO_MATCH", p.getNotifType());
            assertNull(p.getCustomerId());
            assertTrue(p.getItems().isEmpty(), "zero-match plans carry no invoice items");
            assertTrue(Set.of(90L, 91L).contains(p.getRecipientUserId()),
                    "no customer or selected additional user receives the zero-match result");
            assertTrue(p.getTitle().contains("Overdue large invoices"));
        }
        assertEquals("ZERO_MATCH", run.getOutcome());
        assertEquals(RunStatus.COMPLETED, run.getStatus());
        // Customer lookup must never even be attempted for a zero-match run.
        org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never()).findByCustomerId(any());
    }
}
