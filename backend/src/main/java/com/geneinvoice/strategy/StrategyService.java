package com.geneinvoice.strategy;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent registry of invoice-notification strategies. Validates the complete strategy DTO
 * on every create or update (title, at least one status, complete date and amount predicates,
 * optional description), resolves every additional-recipient id against the authoritative user
 * role before commit, and keeps activation as an explicit admin-only state transition.
 */
@Service
@RequiredArgsConstructor
public class StrategyService {

    private final NotificationStrategyRepository repository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<StrategyDtos.StrategyDto> list() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(StrategyDtos.StrategyDto::from).toList();
    }

    @Transactional
    public StrategyDtos.StrategyDto create(StrategyDtos.StrategyRequest in) {
        validate(in);
        NotificationStrategy s = NotificationStrategy.builder()
                .title(in.title().trim())
                .description(in.description())
                .statuses(new LinkedHashSet<>(in.statuses()))
                .dateOperator(in.dateOperator())
                .dateFrom(in.dateFrom())
                .dateTo(normalizedDateTo(in))
                .amountOperator(in.amountOperator())
                .amountFrom(in.amountFrom())
                .amountTo(normalizedAmountTo(in))
                .additionalRecipientUserIds(new LinkedHashSet<>(recipients(in)))
                // Newly saved strategies are always active; only the explicit activate /
                // deactivate operations may move the flag afterwards.
                .active(true)
                .build();
        return StrategyDtos.StrategyDto.from(repository.save(s));
    }

    @Transactional
    public StrategyDtos.StrategyDto update(Long id, StrategyDtos.StrategyRequest in) {
        NotificationStrategy s = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Strategy not found"));
        validate(in);
        s.setTitle(in.title().trim());
        s.setDescription(in.description());
        s.getStatuses().clear();
        s.getStatuses().addAll(in.statuses());
        s.setDateOperator(in.dateOperator());
        s.setDateFrom(in.dateFrom());
        s.setDateTo(normalizedDateTo(in));
        s.setAmountOperator(in.amountOperator());
        s.setAmountFrom(in.amountFrom());
        s.setAmountTo(normalizedAmountTo(in));
        s.getAdditionalRecipientUserIds().clear();
        s.getAdditionalRecipientUserIds().addAll(recipients(in));
        // The active flag is deliberately not part of editing; activation is explicit.
        return StrategyDtos.StrategyDto.from(repository.save(s));
    }

    @Transactional
    public StrategyDtos.StrategyDto activate(Long id) {
        return setActive(id, true);
    }

    @Transactional
    public StrategyDtos.StrategyDto deactivate(Long id) {
        return setActive(id, false);
    }

    /** The multi-select source for additional recipients: every account whose authoritative
     *  role is not CUSTOMER (customers are delivery-time mandatory recipients, never a choice). */
    @Transactional(readOnly = true)
    public List<StrategyDtos.RecipientOptionDto> eligibleRecipients() {
        return userRepository.findAll().stream()
                .filter(u -> u.getRole() != null && !"CUSTOMER".equalsIgnoreCase(u.getRole().getName()))
                .map(u -> new StrategyDtos.RecipientOptionDto(
                        u.getId(), u.getUsername(), u.getFullName(), u.getRole().getName()))
                .sorted(Comparator.comparing(StrategyDtos.RecipientOptionDto::username))
                .toList();
    }

    private StrategyDtos.StrategyDto setActive(Long id, boolean active) {
        NotificationStrategy s = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Strategy not found"));
        s.setActive(active);
        return StrategyDtos.StrategyDto.from(repository.save(s));
    }

    private void validate(StrategyDtos.StrategyRequest in) {
        if (in == null) {
            throw new BadRequestException("Strategy payload is required");
        }
        if (in.title() == null || in.title().isBlank()) {
            throw new BadRequestException("Title is required");
        }
        if (in.statuses() == null || in.statuses().isEmpty()) {
            throw new BadRequestException("At least one invoice status is required");
        }
        if (in.dateOperator() == null) {
            throw new BadRequestException("A date predicate operator is required");
        }
        if (in.dateFrom() == null) {
            throw new BadRequestException("A date predicate value is required");
        }
        if (in.dateOperator() == DateOperator.BETWEEN && in.dateTo() == null) {
            throw new BadRequestException("A date-between predicate requires an end date");
        }
        if (in.amountOperator() == null) {
            throw new BadRequestException("An amount predicate operator is required");
        }
        if (in.amountFrom() == null) {
            throw new BadRequestException("An amount predicate value is required");
        }
        if (in.amountOperator() == AmountOperator.BETWEEN && in.amountTo() == null) {
            throw new BadRequestException("An amount-between predicate requires an end amount");
        }
        for (Long userId : recipients(in)) {
            User u = userRepository.findById(userId)
                    .orElseThrow(() -> new BadRequestException(
                            "Additional recipient user not found: " + userId));
            if (u.getRole() != null && "CUSTOMER".equalsIgnoreCase(u.getRole().getName())) {
                throw new BadRequestException(
                        "Additional recipients must not be customer-role users: " + userId);
            }
        }
    }

    private static java.time.LocalDate normalizedDateTo(StrategyDtos.StrategyRequest in) {
        return in.dateOperator() == DateOperator.BETWEEN ? in.dateTo() : null;
    }

    private static java.math.BigDecimal normalizedAmountTo(StrategyDtos.StrategyRequest in) {
        return in.amountOperator() == AmountOperator.BETWEEN ? in.amountTo() : null;
    }

    private static Set<Long> recipients(StrategyDtos.StrategyRequest in) {
        return in.additionalRecipientUserIds() == null ? Set.of() : in.additionalRecipientUserIds();
    }
}
