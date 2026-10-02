package com.geneinvoice.automation.rule;

import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.common.scope.AsOfAware;
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Rules (§6.14). The list, the summary and a rule answer as of a date from the rules' revisions; a preview evaluates
 * as of a date on the records as they were. Every write is refused in a past view.
 */
@RestController
@AsOfAware
@RequestMapping("/api/automation/rules")
@RequiredArgsConstructor
public class RuleController {

    private static final String VIEW = "hasAuthority('" + Privileges.AUTOMATION_VIEW + "')";
    private static final String MANAGE = "hasAuthority('" + Privileges.AUTOMATION_MANAGE + "')";
    private static final String MANAGE_OR_ADMIN = "hasAnyAuthority('" + Privileges.AUTOMATION_MANAGE + "', '"
            + Privileges.AUTOMATION_ADMIN + "')";

    private final RuleService service;
    private final RulePreviewService previews;

    @GetMapping
    @PreAuthorize(VIEW)
    public PageResponse<RuleDtos.RuleSummary> list(@RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size,
                                                   @RequestParam(required = false) String sort,
                                                   HttpServletRequest request) {
        return service.page(TableQuery.parse(TableSchemas.AUTOMATION_RULES, page, size, sort,
                FilterParams.from(request)));
    }

    @GetMapping("/summary")
    @PreAuthorize(VIEW)
    public RuleDtos.SummaryDto summary(HttpServletRequest request) {
        return service.summary(TableQuery.parseUnpaged(TableSchemas.AUTOMATION_RULES, null,
                FilterParams.from(request)));
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public RuleDtos.RuleDto get(@PathVariable Long id) {
        return service.detail(id);
    }

    @PostMapping
    @PreAuthorize(MANAGE)
    public RuleDtos.RuleDto create(@RequestBody RuleDtos.RuleRequest req) {
        return service.create(req);
    }

    /** The owner, or an administrator who takes the rule over (400 until they confirm it). */
    @PutMapping("/{id}")
    @PreAuthorize(MANAGE_OR_ADMIN)
    public RuleDtos.RuleDto update(@PathVariable Long id, @RequestBody RuleDtos.RuleRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize(MANAGE_OR_ADMIN)
    public RuleDtos.RuleDto enable(@PathVariable Long id,
                                   @RequestBody(required = false) RuleDtos.ActRequest req) {
        return service.enable(id, req != null && req.confirmed());
    }

    /** The owner resumes a suspended rule; an administrator takes it over first (400 until they confirm it). */
    @PostMapping("/{id}/resume")
    @PreAuthorize(MANAGE_OR_ADMIN)
    public RuleDtos.ResumedDto resume(@PathVariable Long id,
                                      @RequestBody(required = false) RuleDtos.ResumeRequest req) {
        return service.resume(id, req != null && Boolean.TRUE.equals(req.replay()),
                req != null && Boolean.TRUE.equals(req.confirmOwnershipChange()));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize(MANAGE_OR_ADMIN)
    public RuleDtos.RuleDto disable(@PathVariable Long id) {
        return service.disable(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(MANAGE_OR_ADMIN)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** A read-only POST: what the rule in the body would match and do, as of the date viewed. */
    @PostMapping("/preview")
    @AsOfAware
    @PreAuthorize(VIEW)
    public RuleDtos.PreviewDto preview(@RequestBody RuleDtos.PreviewRequest req) {
        return previews.preview(req);
    }

    @GetMapping("/{id}/preview")
    @PreAuthorize(VIEW)
    public RuleDtos.PreviewDto preview(@PathVariable Long id,
                                       @RequestParam(required = false) Long recordId,
                                       @RequestParam(required = false) Integer sampleSize,
                                       @RequestParam(required = false) String evaluateAs) {
        return previews.preview(id, recordId, sampleSize, evaluateAs);
    }

    @GetMapping("/{id}/matches")
    @PreAuthorize(VIEW)
    public PageResponse<RuleDtos.MatchDto> matches(@PathVariable Long id,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size,
                                                   @RequestParam(required = false) String evaluateAs) {
        return previews.matches(id, page, size, evaluateAs);
    }
}
