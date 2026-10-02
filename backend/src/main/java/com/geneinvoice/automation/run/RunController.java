package com.geneinvoice.automation.run;

import com.geneinvoice.automation.rule.RuleDtos;
import com.geneinvoice.automation.rule.RuleService;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.common.scope.AsOfAware;
import com.geneinvoice.common.scope.AsOfIgnored;
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Running rules and what came of it (§6.14): "Run now", a rule's runs, a run and its effects (as of a date, those
 * made by then, redacted for the viewer, §5.10.10), trying again what was given up on, and the consumer's health
 * (always live). Every write is refused in a past view.
 */
@RestController
@AsOfAware
@RequestMapping("/api/automation")
@RequiredArgsConstructor
public class RunController {

    private static final String VIEW = "hasAuthority('" + Privileges.AUTOMATION_VIEW + "')";
    private static final String MANAGE_OR_ADMIN = "hasAnyAuthority('" + Privileges.AUTOMATION_MANAGE + "', '"
            + Privileges.AUTOMATION_ADMIN + "')";
    private static final String ADMIN = "hasAuthority('" + Privileges.AUTOMATION_ADMIN + "')";

    private final RuleService ruleService;
    private final RunViews views;

    /** 202 with the run queued, or 200 with the identical one already waiting. */
    @PostMapping("/rules/{id}/run")
    @PreAuthorize(MANAGE_OR_ADMIN)
    public ResponseEntity<RuleDtos.QueuedRunDto> run(@PathVariable Long id,
                                                     @RequestBody(required = false) RuleDtos.RunRequest req) {
        RuleService.RunQueued queued = ruleService.runNow(id, req);
        return ResponseEntity.status(queued.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(queued.run());
    }

    @GetMapping("/rules/{id}/runs")
    @PreAuthorize(VIEW)
    public PageResponse<RunViews.RunDto> runs(@PathVariable Long id,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size,
                                              @RequestParam(required = false) String sort,
                                              HttpServletRequest request) {
        return views.runs(id, TableQuery.parse(TableSchemas.AUTOMATION_RUNS, page, size, sort,
                FilterParams.from(request)));
    }

    @GetMapping("/runs/{id}")
    @PreAuthorize(VIEW)
    public RunViews.RunDto get(@PathVariable Long id) {
        return views.run(id);
    }

    @GetMapping("/runs/{id}/effects")
    @PreAuthorize(VIEW)
    public PageResponse<RunViews.EffectDto> effects(@PathVariable Long id,
                                                    @RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size) {
        return views.effects(id, page, size);
    }

    /** A failed run whose event was given up on, queued again (202). */
    @PostMapping("/runs/{id}/retry")
    @PreAuthorize(MANAGE_OR_ADMIN)
    public ResponseEntity<RuleDtos.QueuedRunDto> retry(@PathVariable Long id,
                                                       @RequestBody(required = false) RuleDtos.ActRequest req) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ruleService.retry(id, req != null && req.confirmed()));
    }

    /** An event given up on, queued again (202). */
    @PostMapping("/events/{id}/requeue")
    @PreAuthorize(ADMIN)
    public ResponseEntity<Map<String, Object>> requeue(@PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ruleService.requeue(id));
    }

    /** How the consumer is doing, now, whatever date is asked about. */
    @GetMapping("/health")
    @AsOfIgnored
    @PreAuthorize(ADMIN)
    public RunViews.HealthDto health() {
        return views.health();
    }
}
