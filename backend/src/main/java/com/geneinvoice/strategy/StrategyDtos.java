package com.geneinvoice.strategy;

import com.geneinvoice.invoice.InvoiceStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public final class StrategyDtos {
    private StrategyDtos() {}

    public record StrategyRequest(
            @NotBlank String title,
            String description,
            @NotEmpty Set<InvoiceStatus> statuses,
            @NotNull DateOperator dateOperator,
            @NotNull LocalDate dateFrom,
            LocalDate dateTo,
            @NotNull AmountOperator amountOperator,
            @NotNull BigDecimal amountFrom,
            BigDecimal amountTo,
            Set<Long> additionalRecipientUserIds
    ) {}

    public record StrategyDto(
            Long id,
            String title,
            String description,
            Set<InvoiceStatus> statuses,
            DateOperator dateOperator,
            LocalDate dateFrom,
            LocalDate dateTo,
            AmountOperator amountOperator,
            BigDecimal amountFrom,
            BigDecimal amountTo,
            Set<Long> additionalRecipientUserIds,
            boolean active,
            Instant createdAt
    ) {
        public static StrategyDto from(NotificationStrategy s) {
            return new StrategyDto(
                    s.getId(), s.getTitle(), s.getDescription(),
                    Set.copyOf(s.getStatuses()),
                    s.getDateOperator(), s.getDateFrom(), s.getDateTo(),
                    s.getAmountOperator(), s.getAmountFrom(), s.getAmountTo(),
                    Set.copyOf(s.getAdditionalRecipientUserIds()),
                    s.isActive(), s.getCreatedAt());
        }
    }

    /** A non-customer user offered as an additional-recipient choice in the admin UI. */
    public record RecipientOptionDto(Long id, String username, String fullName, String role) {}

    /** Receipt of a manual or scheduled admission, returned to the admin UI. */
    public record RunDto(
            Long runId,
            Long strategyId,
            RunTrigger trigger,
            RunStatus status,
            String outcome,
            int attemptCount,
            String failureMessage,
            Instant nextAttemptAt
    ) {
        public static RunDto from(StrategyRun r) {
            return new RunDto(r.getId(), r.getStrategyId(), r.getTrigger(), r.getStatus(),
                    r.getOutcome(), r.getAttemptCount(), r.getFailureMessage(), r.getNextAttemptAt());
        }
    }

    public record InvoiceStatusDto(String name) {
        public static List<InvoiceStatusDto> all() {
            return java.util.Arrays.stream(InvoiceStatus.values())
                    .map(v -> new InvoiceStatusDto(v.name()))
                    .toList();
        }
    }
}
