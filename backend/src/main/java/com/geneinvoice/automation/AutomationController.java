package com.geneinvoice.automation;

import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The rules screen. Reading is AUTOMATION_VIEW; writing and running are AUTOMATION_MANAGE, held
 * apart because a rule acts for everybody — whoever writes one is deciding that tasks, promises,
 * disputes and email will be made with nobody in the loop.
 *
 * <p>No scope predicate is applied to the list. A rule is not about one customer's records, and a
 * customer login cannot hold either privilege, so there is nothing to narrow it to.
 */
@RestController
@RequestMapping("/api/automation/rules")
@RequiredArgsConstructor
public class AutomationController {

    private final AutomationService service;
    private final TableQueryExecutor queryExecutor;

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_VIEW + "')")
    public PageResponse<AutomationDtos.RuleDto> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request) {
        TableQuery query = TableQuery.parse(TableSchemas.AUTOMATION_RULES, page, size, sort,
                FilterParams.from(request));
        var result = queryExecutor.run(AutomationRule.class, TableSchemas.AUTOMATION_RULES, query,
                List.of(), List.of());
        return PageResponse.of(result.content().stream().map(service::toDto).toList(),
                query, result.total(), List.of());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_VIEW + "')")
    public AutomationDtos.RuleDto get(@PathVariable Long id) {
        return service.dto(id);
    }

    /** What this rule has been asked about lately and what came of each — including the skips. */
    @GetMapping("/{id}/runs")
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_VIEW + "')")
    public List<AutomationDtos.RuleRunDto> runs(@PathVariable Long id,
                                                @RequestParam(required = false) Integer limit) {
        return service.runs(id, limit);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_MANAGE + "')")
    public AutomationDtos.RuleDto create(@Valid @RequestBody AutomationDtos.CreateRuleRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_MANAGE + "')")
    public AutomationDtos.RuleDto update(@PathVariable Long id,
                                         @Valid @RequestBody AutomationDtos.UpdateRuleRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_MANAGE + "')")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /**
     * Runs the rule over everything it currently matches. AUTOMATION_MANAGE rather than
     * AUTOMATION_VIEW: this makes the rule act, and somebody who may only read rules must not be
     * able to raise a hundred tasks by pressing a button on a screen they were given to look at.
     */
    @PostMapping("/{id}/run")
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_MANAGE + "')")
    public AutomationDtos.RunResultDto run(@PathVariable Long id) {
        return service.run(id);
    }
}
