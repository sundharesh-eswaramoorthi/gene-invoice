package com.geneinvoice.strategy;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin perimeter for strategy management and manual runs. Every operation requires the
 * existing ROLE_ADMIN authority (emitting as {@code ROLE_} + role name by
 * {@code AppUserDetailsService}); the existing stateless JWT transport and the
 * {@code anyRequest().authenticated()} filter chain apply unchanged — no strategy route is
 * ever added to permitAll.
 */
@RestController
@RequestMapping("/api/notification-strategies")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyService service;
    private final StrategyExecutionEngine engine;

    @GetMapping
    public List<StrategyDtos.StrategyDto> list() {
        return service.list();
    }

    @GetMapping("/invoice-statuses")
    public List<StrategyDtos.InvoiceStatusDto> invoiceStatuses() {
        return StrategyDtos.InvoiceStatusDto.all();
    }

    @GetMapping("/eligible-recipients")
    public List<StrategyDtos.RecipientOptionDto> eligibleRecipients() {
        return service.eligibleRecipients();
    }

    @PostMapping
    public StrategyDtos.StrategyDto create(@Valid @RequestBody StrategyDtos.StrategyRequest in) {
        return service.create(in);
    }

    @PutMapping("/{id}")
    public StrategyDtos.StrategyDto update(@PathVariable Long id,
                                           @Valid @RequestBody StrategyDtos.StrategyRequest in) {
        return service.update(id, in);
    }

    @PostMapping("/{id}/activate")
    public StrategyDtos.StrategyDto activate(@PathVariable Long id) {
        return service.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    public StrategyDtos.StrategyDto deactivate(@PathVariable Long id) {
        return service.deactivate(id);
    }

    /**
     * Admin-triggered manual run: any saved strategy, including an inactive one, is admitted
     * into the shared execution and recovery pipeline without touching its active state.
     */
    @PostMapping("/{id}/run")
    public StrategyDtos.RunDto run(@PathVariable Long id) {
        return StrategyDtos.RunDto.from(engine.submitManualRun(id));
    }
}
