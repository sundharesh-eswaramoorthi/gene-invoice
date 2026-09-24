package com.geneinvoice.automation;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * EXACTLY FIVE GET MAPPINGS, and a sixth is a change that has to be agreed with B3-CLOSE, whose
 * as-of allowlist already names these by path (A1, A5, B3 INTEGRATION).
 *
 * <p>AUTOMATION_VIEW and AUTOMATION_MANAGE are COMPANY-WIDE privileges, on purpose: the privilege
 * says you may author rules, and WHERE a rule may reach is bounded by automation_rule_regions,
 * validated at save time against the author's own MANAGE grants. Putting the region question on
 * the privilege instead would mean a company with four branches needs four kinds of rule author
 * (A1, B1).
 */
@RestController
@RequestMapping("/api/automation")
@RequiredArgsConstructor
public class AutomationController {

    private final AutomationRuleService service;
    private final BulkExecutor bulkExecutor;
    private final CurrentUser currentUser;

    public static final List<String> BULK_ACTIONS = List.of("ENABLE", "DISABLE", "RUN_NOW");

    private TableSchema rules() {
        return AutomationSchemas.RULES.visibleTo(currentUser.isCustomer());
    }

    private TableSchema steps() {
        return AutomationSchemas.STEPS.visibleTo(currentUser.isCustomer());
    }

    @GetMapping("/rules")
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_VIEW + "')")
    public PageResponse<AutomationDtos.RuleDto> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request) {
        return service.page(TableQuery.parse(rules(), page, size, sort, FilterParams.from(request)));
    }

    @GetMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_VIEW + "')")
    public AutomationDtos.RuleDto get(@PathVariable Long id) {
        return service.dto(id);
    }

    /**
     * The run history: one list for the event path, the schedule path and "run now", because a
     * reader asking what automation has done to an account should not have to know which door the
     * work came in through (A5).
     */
    @GetMapping("/steps")
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_VIEW + "')")
    public PageResponse<AutomationDtos.StepDto> steps(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request) {
        return service.steps(TableQuery.parse(steps(), page, size, sort, FilterParams.from(request)));
    }

    /**
     * The sidebar badge. Not as-of capable and deliberately so: a count of what is stuck is a fact
     * about now, and B3-CLOSE's allowlist names the other four (A5, B3).
     */
    @GetMapping("/steps/poisoned-count")
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_VIEW + "')")
    public Map<String, Long> poisonedCount() {
        return Map.of("count", service.poisonedCount());
    }

    /** Pure metadata: no rows, no record, nothing to scope (A4). */
    @GetMapping("/placeholders")
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_VIEW + "')")
    public List<AutomationDtos.PlaceholderDto> placeholders(@RequestParam SubjectType subjectType) {
        return service.placeholders(subjectType);
    }

    @PostMapping("/rules")
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_MANAGE + "')")
    public AutomationDtos.RuleDto create(@Valid @RequestBody AutomationDtos.SaveRuleRequest req) {
        return service.create(req);
    }

    // PUT and not PATCH: a rule is edited as a whole on one screen and a half-sent rule is a rule
    // that does something nobody asked for. The client sends back what it was given (A1).
    @PutMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_MANAGE + "')")
    public AutomationDtos.RuleDto update(@PathVariable Long id,
                                         @Valid @RequestBody AutomationDtos.SaveRuleRequest req) {
        return service.update(id, req);
    }

    /** SOFT: the run history keeps its subject and there is no foreign key on steps.rule_id (A5). */
    @DeleteMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_MANAGE + "')")
    public Map<String, Object> delete(@PathVariable Long id) {
        service.delete(id);
        return Map.of("deleted", true);
    }

    /**
     * Through BulkExecutor.eligibility, so "this one was already on" is reported as a row that did
     * not qualify rather than failing the whole run (A1, TBL-05).
     */
    @PostMapping("/rules/bulk")
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_MANAGE + "')")
    public BulkDtos.BulkResult bulk(@Valid @RequestBody BulkDtos.BulkRequest req) {
        List<Long> ids = resolveIds(req);
        boolean truncated = req.allMatching() && ids.size() >= TableQueryExecutor.BULK_ID_LIMIT;
        return switch (req.action()) {
            case "ENABLE" -> bulkExecutor.run(req, ids, truncated,
                    BulkExecutor.eligibility(id -> service.setEnabled(id, true)));
            case "DISABLE" -> bulkExecutor.run(req, ids, truncated,
                    BulkExecutor.eligibility(id -> service.setEnabled(id, false)));
            // THE ONE ARM WITH A SECOND AUTHORITY CHECK, and it is not a style lapse: this mapping
            // is gated on AUTOMATION_MANAGE, running a rule is gated on AUTOMATION_RUN, and the
            // two are separate privileges on purpose. Without this line, the bulk door would hand
            // a rule author the one thing the single-rule door refuses them (A5, AUTH-05).
            case "RUN_NOW" -> {
                if (!currentUser.has(Privileges.AUTOMATION_RUN)) {
                    throw new AccessDeniedException(
                            "You may edit automation rules but not run them");
                }
                // ONE request id for the whole click, so the same rule cannot be opened twice by
                // one bulk call; a second bulk call is a second intent and gets its own (A5).
                String requestId = "B" + UUID.randomUUID().toString().replace("-", "");
                yield bulkExecutor.run(req, ids, truncated, BulkExecutor.eligibility(
                        id -> service.runNow(id, requestId, null)));
            }
            default -> throw new BadRequestException(
                    "Unknown bulk action: " + req.action() + " (expected one of " + BULK_ACTIONS + ")");
        };
    }

    /**
     * THE THIRD PRODUCER, and a DRY RUN BY DEFAULT — the three good habits of
     * {@code POST /api/promises/recompute}: keyed on a privilege and never on a role name
     * (AUTH-05); {@code apply} defaults to FALSE; and the dry run is the same code path, so what
     * it shows and what would happen cannot disagree (A5).
     *
     * <p>Two shapes come back because two things happen: a dry run answers
     * {@code {matched, truncated, sample}} and writes nothing at all, and an applied run answers
     * the run row and returns immediately — the sweeper drains it.
     */
    @PostMapping("/rules/{id}/run")
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_RUN + "')")
    public Object run(@PathVariable Long id,
                      @RequestParam(required = false, defaultValue = "false") boolean apply,
                      @RequestParam(required = false) String asOf,
                      @Valid @RequestBody(required = false) AutomationDtos.RunRequest body) {
        // The query string wins, because it is the form the URL documents; the body carries it
        // too so a client that posts one object does not have to build a URL as well (A5).
        LocalDate at = asOf == null ? (body == null ? null : body.asOf()) : parseDate(asOf);
        if (!apply) return service.dryRun(id, at);
        return service.runNow(id, body == null ? null : body.requestId(), at);
    }

    /**
     * BACKTEST: WHAT WOULD THIS RULE HAVE DONE ON 31 JANUARY (B3).
     *
     * <p>The third of the PRD's three clauses, and the one endpoint in this application that is a
     * POST and is nevertheless on the as-of allowlist for a reason other than a CSV body. It is a
     * POST because it is beside {@code /run}, whose shape it copies, and because the answer is a
     * computation over the whole population rather than a resource; it writes nothing at all, and
     * the interceptor opens the context exactly as it does for a list.
     *
     * <p>TWO ENDPOINTS AND NOT ONE MODE PARAMETER, and the reason is a fact about this codebase
     * rather than a preference. B3's design has {@code mode=SIMULATE|REPLAY} on this one path.
     * {@code HistoryWriter.drain} throws for ANY non-read-only transaction that commits while an
     * as-of context is open, and the interceptor has already opened one by the time this handler
     * runs — so a REPLAY arm here could only write by suspending the very guard that makes an
     * accidental write inside a simulation loud instead of silent. The replay therefore lives at
     * {@code POST .../replay} with its date in the body, where nothing is open (A5, B3).
     *
     * <p>AUTOMATION_RUN and not AUTOMATION_VIEW: this returns the same two figures
     * {@code POST .../run?apply=false} returns and that mapping has always required RUN. Making a
     * backtest cheaper to reach than the dry run it generalises would widen who can learn a rule's
     * reach, which is not a decision this unit gets to take on the way past (AUTH-05).
     *
     * <p>Without {@code ?asOf} it is the dry run at today, which is what makes "as of today equals
     * live" askable of this endpoint too.
     */
    @PostMapping("/rules/{id}/simulate")
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_RUN + "')")
    public AutomationDtos.SimulationDto simulate(@PathVariable Long id) {
        return service.simulate(id);
    }

    /**
     * CATCH-UP: RE-RUN THE SLOT THAT NEVER FIRED, AS OF ITS OWN DAY (A5, B3).
     *
     * <p>{@code {"requestId": null, "asOf": "2026-02-02"}}. The date is in the BODY and not in the
     * query string, deliberately: this mapping WRITES, and {@code ?asOf} on a mutating mapping is
     * 400 "The past is read only" before the handler runs — a guard this endpoint keeps rather
     * than steps around. The day being replayed is a property of the run being opened, not a
     * request to read the past, so it travels as one.
     *
     * <p>{@code requestId} is ignored: the occasion is the DAY, so two clicks on "replay 2
     * February" resolve to one run through {@code uk_run_occasion} and one set of steps through
     * {@code uk_step_occasion}. That is the dedupe the design asks for, keyed on
     * {@code (rule, subject type, subject id, occurrence date)} exactly as A5's step key already
     * is.
     */
    @PostMapping("/rules/{id}/replay")
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_RUN + "')")
    public AutomationDtos.RunDto replay(@PathVariable Long id,
                                        @Valid @RequestBody(required = false)
                                        AutomationDtos.RunRequest body) {
        return service.replay(id, body == null ? null : body.asOf());
    }

    /**
     * The Retry button beside a poisoned step. AUTOMATION_MANAGE: deciding that something stuck
     * should be tried again is an edit to what the engine is doing, not a reading of it (A5).
     */
    @PostMapping("/steps/{id}/retry")
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_MANAGE + "')")
    public AutomationDtos.StepDto retry(@PathVariable Long id) {
        return service.retry(id);
    }

    private static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("asOf must be a date in the form YYYY-MM-DD");
        }
    }

    /**
     * The builder's live preview. AUTOMATION_VIEW and not MANAGE, because reading what a rule
     * would say is part of reviewing somebody else's rule; the record itself is gated by the same
     * book-and-region check its own screen applies, inside the service and BEFORE a character is
     * rendered (A4, AUTH-08).
     */
    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('" + Privileges.AUTOMATION_VIEW + "')")
    public AutomationDtos.PreviewResponse preview(
            @Valid @RequestBody AutomationDtos.PreviewRequest req) {
        return service.preview(req);
    }

    private List<Long> resolveIds(BulkDtos.BulkRequest req) {
        TableQuery query = TableQuery.parseUnpaged(rules(), req.sort(), req.filters());
        List<Long> permitted = service.idsMatching(query, TableQueryExecutor.BULK_ID_LIMIT);
        if (req.allMatching()) return permitted;
        if (req.ids() == null || req.ids().isEmpty()) {
            throw new BadRequestException("Provide ids or set selectAllMatchingFilter");
        }
        return req.ids().stream().filter(permitted::contains).toList();
    }
}
