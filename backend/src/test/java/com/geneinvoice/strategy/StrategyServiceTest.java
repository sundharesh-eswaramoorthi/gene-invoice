package com.geneinvoice.strategy;

import com.geneinvoice.common.BadRequestException;
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
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Proof of the registry contract: requiredness (AC2), additional-recipient role admission
 * (AC5), active-by-default creation (AC6), explicit activation/deactivation (AC6), and
 * separately addressable strategies (AC1).
 */
@ExtendWith(MockitoExtension.class)
class StrategyServiceTest {

    @Mock
    private NotificationStrategyRepository strategyRepository;
    @Mock
    private UserRepository userRepository;

    private StrategyService newService() {
        return new StrategyService(strategyRepository, userRepository);
    }

    private static StrategyDtos.StrategyRequest validRequest() {
        return new StrategyDtos.StrategyRequest(
                "Overdue large invoices",
                "optional description",
                Set.of(InvoiceStatus.UNPAID),
                DateOperator.AFTER, LocalDate.of(2024, 1, 1), null,
                AmountOperator.GREATER_THAN, new BigDecimal("1000.00"), null,
                null);
    }

    private static User user(long id, String roleName) {
        return User.builder().id(id).username("u" + id)
                .role(Role.builder().name(roleName).build()).build();
    }

    // ------------------------------------------------------------ requiredness (AC2)

    @Test
    void missingTitleStatusesOrPredicatesAreNotSaved() {
        StrategyDtos.StrategyRequest base = validRequest();

        assertThrows(BadRequestException.class, () -> newService().create(
                new StrategyDtos.StrategyRequest(null, null, base.statuses(), base.dateOperator(),
                        base.dateFrom(), null, base.amountOperator(), base.amountFrom(), null, null)),
                "title is mandatory");
        assertThrows(BadRequestException.class, () -> newService().create(
                new StrategyDtos.StrategyRequest("  ", null, base.statuses(), base.dateOperator(),
                        base.dateFrom(), null, base.amountOperator(), base.amountFrom(), null, null)),
                "a blank title is not a title");
        assertThrows(BadRequestException.class, () -> newService().create(
                new StrategyDtos.StrategyRequest(base.title(), null, Set.of(), base.dateOperator(),
                        base.dateFrom(), null, base.amountOperator(), base.amountFrom(), null, null)),
                "at least one status is required");
        assertThrows(BadRequestException.class, () -> newService().create(
                new StrategyDtos.StrategyRequest(base.title(), null, base.statuses(), base.dateOperator(),
                        null, null, base.amountOperator(), base.amountFrom(), null, null)),
                "the date predicate is required");
        assertThrows(BadRequestException.class, () -> newService().create(
                new StrategyDtos.StrategyRequest(base.title(), null, base.statuses(), base.dateOperator(),
                        base.dateFrom(), null, base.amountOperator(), null, null, null)),
                "the amount predicate is required");
        assertThrows(BadRequestException.class, () -> newService().create(
                new StrategyDtos.StrategyRequest(base.title(), null, base.statuses(), DateOperator.BETWEEN,
                        base.dateFrom(), null, base.amountOperator(), base.amountFrom(), null, null)),
                "a date-between predicate needs its end date");
        assertThrows(BadRequestException.class, () -> newService().create(
                new StrategyDtos.StrategyRequest(base.title(), null, base.statuses(), base.dateOperator(),
                        base.dateFrom(), null, AmountOperator.BETWEEN, base.amountFrom(), null, null)),
                "an amount-between predicate needs its end amount");
    }

    @Test
    void missingDescriptionDoesNotPreventSaving() {
        when(strategyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StrategyDtos.StrategyDto saved = newService().create(
                new StrategyDtos.StrategyRequest("Overdue large invoices", null,
                        Set.of(InvoiceStatus.UNPAID),
                        DateOperator.AFTER, LocalDate.of(2024, 1, 1), null,
                        AmountOperator.GREATER_THAN, new BigDecimal("1000.00"), null, null));

        assertNull(saved.description(), "description is optional and stays absent");
        assertEquals("Overdue large invoices", saved.title());
    }

    // ------------------------------------------------------------ activation (AC6)

    @Test
    void newlySavedStrategyIsActive() {
        when(strategyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().create(validRequest());

        ArgumentCaptor<NotificationStrategy> captor = ArgumentCaptor.forClass(NotificationStrategy.class);
        org.mockito.Mockito.verify(strategyRepository).save(captor.capture());
        assertTrue(captor.getValue().isActive(), "a newly saved strategy is active (AC6)");
        assertEquals(Set.of(InvoiceStatus.UNPAID), captor.getValue().getStatuses());
        assertNull(captor.getValue().getDateTo(), "non-BETWEEN operators drop the second endpoint");
    }

    @Test
    void adminCanDeactivateAndReactivate() {
        NotificationStrategy stored = NotificationStrategy.builder()
                .id(7L).title("T").statuses(Set.of(InvoiceStatus.UNPAID))
                .dateOperator(DateOperator.AFTER).dateFrom(LocalDate.of(2024, 1, 1))
                .amountOperator(AmountOperator.GREATER_THAN).amountFrom(new BigDecimal("1.00"))
                .active(true).build();
        when(strategyRepository.findById(7L)).thenReturn(Optional.of(stored));
        when(strategyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertFalse(newService().deactivate(7L).active(), "deactivate makes it inactive");
        assertTrue(newService().activate(7L).active(), "activate makes it active again");
    }

    @Test
    void multipleStrategiesRemainSeparatelyAddressable() {
        when(strategyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StrategyDtos.StrategyDto first = newService().create(validRequest());
        StrategyDtos.StrategyDto second = newService().create(
                new StrategyDtos.StrategyRequest("Small recent invoices", null,
                        Set.of(InvoiceStatus.PARTIALLY_PAID),
                        DateOperator.BEFORE, LocalDate.of(2024, 6, 1), null,
                        AmountOperator.LESS_THAN, new BigDecimal("50.00"), null, null));

        assertEquals("Overdue large invoices", first.title());
        assertEquals("Small recent invoices", second.title());
        org.mockito.Mockito.verify(strategyRepository, org.mockito.Mockito.times(2)).save(any());
    }

    // ------------------------------------------------------------ recipient roles (AC5)

    @Test
    void customerRoleUserIsRejectedAsAdditionalRecipient() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user(42L, "CUSTOMER")));

        StrategyDtos.StrategyRequest in = new StrategyDtos.StrategyRequest(
                "T", null, Set.of(InvoiceStatus.UNPAID),
                DateOperator.AFTER, LocalDate.of(2024, 1, 1), null,
                AmountOperator.GREATER_THAN, new BigDecimal("1.00"), null, Set.of(42L));

        BadRequestException ex = assertThrows(BadRequestException.class, () -> newService().create(in));
        assertTrue(ex.getMessage().contains("customer-role"),
                "the rejection names the customer-role rule: " + ex.getMessage());
    }

    @Test
    void unknownAdditionalRecipientIsRejected() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        StrategyDtos.StrategyRequest in = new StrategyDtos.StrategyRequest(
                "T", null, Set.of(InvoiceStatus.UNPAID),
                DateOperator.AFTER, LocalDate.of(2024, 1, 1), null,
                AmountOperator.GREATER_THAN, new BigDecimal("1.00"), null, Set.of(99L));

        assertThrows(BadRequestException.class, () -> newService().create(in));
    }

    @Test
    void multipleNonCustomerUsersAreAcceptedAndEligibleRecipientsExcludeCustomers() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "ADMIN")));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "CASHIER")));
        when(strategyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StrategyDtos.StrategyRequest in = new StrategyDtos.StrategyRequest(
                "T", null, Set.of(InvoiceStatus.UNPAID),
                DateOperator.AFTER, LocalDate.of(2024, 1, 1), null,
                AmountOperator.GREATER_THAN, new BigDecimal("1.00"), null, Set.of(1L, 2L));
        StrategyDtos.StrategyDto saved = newService().create(in);
        assertEquals(Set.of(1L, 2L), saved.additionalRecipientUserIds(),
                "zero or more non-customer users may be selected");

        when(userRepository.findAll()).thenReturn(java.util.List.of(
                user(1L, "ADMIN"), user(2L, "CASHIER"), user(3L, "CUSTOMER")));
        var options = newService().eligibleRecipients();
        assertEquals(2, options.size(), "customer-role users are never listed as choices");
        assertTrue(options.stream().noneMatch(o -> o.role().equals("CUSTOMER")));
        assertTrue(options.stream().allMatch(o -> o.id() == 1L || o.id() == 2L));
    }
}
