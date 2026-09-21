package com.geneinvoice.automation;

import com.geneinvoice.assignee.AssigneeService;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.Money;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.email.EmailDtos;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.task.Task;
import com.geneinvoice.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Writing, reading and running the rules (R9).
 *
 * <p>Everything a rule says is checked here, when it is written. The WHERE is parsed against the
 * very schema the list page uses, so a column that does not exist or an operator that column does
 * not take is a 400 naming it — while the author is looking at the form — rather than a run that
 * skips quietly every night at seven. The THEN is checked the same way: the assignee and recipient
 * tokens go through {@link com.geneinvoice.assignee.AssigneeService}, exactly as they would if a
 * person were picking them on the record, so a role the kind of record does not offer is refused at
 * authoring instead of reaching nobody forever.
 *
 * <p>The one thing that cannot be checked here is who a role reaches: a rule has no record behind
 * it, so there is no customer whose POC book could answer. That is settled on each run, against
 * the record it ran on (A2).
 */
@Service
@RequiredArgsConstructor
public class AutomationService {

    public static final String ENTITY = "AUTOMATION_RULE";
    /**
     * More filters than this on one rule is a sign somebody is building a query rather than a rule,
     * and every one of them is evaluated against every candidate record on every run.
     */
    public static final int MAX_FILTERS = 20;
    /** A task or promise more than this far out is almost certainly a typed extra digit. */
    public static final int MAX_DUE_IN_DAYS = 3650;
    /**
     * The widest promise a rule may state. {@code payment_promises.amount} is NUMERIC(14,2) —
     * twelve digits in front of the point and two behind — and a figure that does not fit it is a
     * rule that saves and then dies on the insert on every run it ever makes (D-74).
     */
    public static final BigDecimal MAX_PROMISE_AMOUNT = new BigDecimal("999999999999.99");
    /** How many runs the runs list shows by default. */
    public static final int RUNS_PAGE = 50;

    private final AutomationRuleRepository rules;
    private final AutomationEventRepository events;
    private final AutomationEvents outbox;
    private final AutomationMatcher matcher;
    private final AutomationJson json;
    private final AssigneeService assigneeService;
    private final AuditService auditService;
    private final CurrentUser currentUser;

    // ---- reading ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AutomationRule get(Long id) {
        return rules.findById(id).orElseThrow(() -> new NotFoundException("Automation rule not found"));
    }

    @Transactional(readOnly = true)
    public AutomationDtos.RuleDto dto(Long id) {
        return toDto(get(id));
    }

    /** What this rule has been asked about lately, newest first, whatever came of it. */
    @Transactional(readOnly = true)
    public List<AutomationDtos.RuleRunDto> runs(Long ruleId, Integer limit) {
        get(ruleId);
        int size = limit == null || limit <= 0 ? RUNS_PAGE : Math.min(limit, RUNS_PAGE * 4);
        return events.findByRuleIdOrderByIdDesc(ruleId, PageRequest.of(0, size)).stream()
                .map(AutomationService::toRunDto)
                .toList();
    }

    // ---- writing ------------------------------------------------------------------------

    @Transactional
    public AutomationDtos.RuleDto create(AutomationDtos.CreateRuleRequest req) {
        Parsed parsed = check(req.name(), null, req.entityType(), req.trigger(), req.action(),
                req.filters(), req.actionSpec());
        AutomationRule rule = rules.save(AutomationRule.builder()
                .name(req.name().trim())
                .description(blankToNull(req.description()))
                .enabled(req.enabled() == null || req.enabled())
                .entityType(parsed.entityType())
                .trigger(parsed.trigger())
                .filtersJson(json.write(parsed.filters()))
                .action(parsed.action())
                .actionJson(json.write(parsed.spec()))
                .createdByUserId(currentUser.idOrNull())
                .runCount(0L)
                .build());
        auditService.record(ENTITY, rule.getId(), "AUTOMATION_RULE_CREATED", null, toDto(rule),
                currentUser.idOrNull(), null, rule.getName());
        return toDto(rule);
    }

    /**
     * An update states the whole rule, as the form does. It may move a rule to another kind of
     * record or another action: the rows already queued for it are checked against the rule as it
     * stands when they run, so one that no longer fits is skipped with the reason rather than
     * acted on under the old meaning.
     */
    @Transactional
    public AutomationDtos.RuleDto update(Long id, AutomationDtos.UpdateRuleRequest req) {
        AutomationRule rule = get(id);
        AutomationDtos.RuleDto before = toDto(rule);
        Parsed parsed = check(req.name(), id, req.entityType(), req.trigger(), req.action(),
                req.filters(), req.actionSpec());
        rule.setName(req.name().trim());
        rule.setDescription(blankToNull(req.description()));
        rule.setEnabled(req.enabled() == null || req.enabled());
        rule.setEntityType(parsed.entityType());
        rule.setTrigger(parsed.trigger());
        rule.setFiltersJson(json.write(parsed.filters()));
        rule.setAction(parsed.action());
        rule.setActionJson(json.write(parsed.spec()));
        AutomationRule saved = rules.save(rule);
        auditService.record(ENTITY, id, "AUTOMATION_RULE_UPDATED", before, toDto(saved),
                currentUser.idOrNull(), null, saved.getName());
        return toDto(saved);
    }

    /**
     * Removes the rule. Its outbox rows are left where they are: the settled ones are the record of
     * what it did, which is the thing somebody deleting a misfiring rule most wants to read, and a
     * row still queued finds no rule when it runs and skips itself.
     */
    @Transactional
    public void delete(Long id) {
        AutomationRule rule = get(id);
        AutomationDtos.RuleDto before = toDto(rule);
        rules.delete(rule);
        auditService.record(ENTITY, id, "AUTOMATION_RULE_DELETED", before, null,
                currentUser.idOrNull(), null, rule.getName());
    }

    // ---- running by hand ------------------------------------------------------------------

    /**
     * Queues the rule against every record it currently matches. Not transactional, and nothing is
     * evaluated inline: this writes outbox rows and hands them to the transport exactly as a
     * trigger would, so Run now and a real firing go down one path and behave the same — including
     * the de-duplication, which is why running a rule twice in one day does nothing the first run
     * did not already do (R4).
     *
     * @return how many pieces of work were actually queued
     */
    public int runNow(Long ruleId) {
        return run(ruleId).queued();
    }

    /** The same, with what it looked at as well as what it queued, for the button that asked. */
    public AutomationDtos.RunResultDto run(Long ruleId) {
        AutomationRule rule = get(ruleId);
        if (!rule.isEnabled()) {
            throw new BadRequestException("This rule is switched off; switch it on before running it");
        }
        // The same ceiling as a scheduled run, so pressing the button is not a way to ask for more
        // than the rule would ever do on its own.
        int cap = AutomationScheduler.RUN_LIMIT;
        List<Long> ids = matcher.idsMatching(rule.getEntityType(), json.filters(rule), cap);
        int queued = outbox.enqueueForRule(rule, ids, Instant.now()).size();
        return new AutomationDtos.RunResultDto(queued, ids.size(), cap, ids.size() >= cap);
    }

    // ---- checking what was written ----------------------------------------------------------

    /** A rule's parts, once every one of them has been read and found to mean something. */
    private record Parsed(AutomationEntityType entityType, TriggerKind trigger, ActionType action,
                          List<String> filters, AutomationDtos.ActionSpec spec) {}

    private Parsed check(String name, Long selfId, String entityType, String trigger, String action,
                         List<String> filters, AutomationDtos.ActionSpec spec) {
        requireUniqueName(name, selfId);
        AutomationEntityType type = AutomationEntityType.parse(entityType);
        TriggerKind kind = TriggerKind.parse(trigger);
        ActionType what = ActionType.parse(action);
        List<String> chips = filters == null ? List.<String>of()
                : filters.stream().filter(f -> f != null && !f.isBlank()).map(String::trim).toList();
        if (chips.size() > MAX_FILTERS) {
            throw new BadRequestException("A rule takes at most " + MAX_FILTERS + " filters");
        }
        // Parsed and thrown away: what matters is that it parses, against this kind's own schema.
        matcher.query(type, chips);
        return new Parsed(type, kind, what, chips, checkAction(type, what, spec));
    }

    /**
     * The THEN, checked against what the action actually needs. Each branch names the missing
     * thing, because "invalid rule" tells the author nothing about which box to go back to.
     *
     * <p>"What the action needs" includes the widths and the words of the columns it will write.
     * {@link AutomationDtos.ActionSpec} is one record for four actions, so its own bounds are the
     * loosest of the four and are no check at all for the other three: a rule has to be measured
     * against the table its action lands in, or it saves and then skips or fails on every run
     * for ever (D-74).
     */
    private AutomationDtos.ActionSpec checkAction(AutomationEntityType type, ActionType action,
                                                  AutomationDtos.ActionSpec raw) {
        AutomationDtos.ActionSpec spec = raw == null
                ? new AutomationDtos.ActionSpec(null, null, null, null, null, List.of(), null)
                : raw;
        List<EmailDtos.EmailToken> people = spec.assignees() == null ? List.of() : spec.assignees();
        if (spec.dueInDays() != null && (spec.dueInDays() < 0 || spec.dueInDays() > MAX_DUE_IN_DAYS)) {
            throw new BadRequestException("dueInDays must be between 0 and " + MAX_DUE_IN_DAYS);
        }
        switch (action) {
            case CREATE_TASK -> {
                requireText(spec.title(), "A task rule needs a title");
                // Against the task's own columns, not the email's. One ActionSpec answers for all
                // four actions (R6), so its title is bounded by an email subject and its body by an
                // email body — two and a half times and ten times what a task will hold. A rule
                // that stated more than that saved cleanly and then skipped on every run for ever,
                // with the reason on a runs list nobody was reading, which is the exact outcome
                // this check exists to prevent (D-74).
                requireFits(spec.title(), Task.TITLE_MAX, "A task title");
                requireFits(spec.body(), Task.NOTES_MAX, "Task notes");
                // Parsed and thrown away, like the filters above: an opening status the task
                // service will not recognise — "TODO" — is a 400 naming what there is, here, while
                // the author can still fix it.
                if (spec.status() != null && !spec.status().isBlank()) {
                    TaskStatus.parse(spec.status());
                }
                checkPeople(type.toEmailEntityType(), people);
            }
            case CREATE_PROMISE -> {
                if (spec.amount() == null || spec.amount().signum() <= 0) {
                    throw new BadRequestException("A promise rule needs an amount greater than zero");
                }
                requireAmountFits(spec.amount());
                requireFits(spec.body(), FieldLimits.PROMISE_NOTES, "The promise notes");
                checkPeople(EmailEntityType.PROMISE, people);
            }
            case CREATE_DISPUTE -> {
                // Throws for CUSTOMER, and says why: there is no invoice or payment to dispute.
                AutomationActions.disputeTarget(type);
                requireText(spec.body(), "A dispute rule needs a reason");
                requireFits(spec.body(), FieldLimits.DISPUTE_TEXT, "The dispute reason");
                checkPeople(EmailEntityType.DISPUTE, people);
            }
            case SEND_EMAIL -> {
                requireText(spec.title(), "An email rule needs a subject");
                if (people.isEmpty()) {
                    throw new BadRequestException("An email rule needs at least one recipient");
                }
                // A rule has no caller to fall back on, so an email with no From cannot be sent at
                // all: EmailBackgroundAddressing refuses it the moment the rule fires. Saying so
                // here is the whole point of checking at authoring time — the alternative was a
                // rule that saved cleanly and then skipped every run with nobody watching (R7).
                if (spec.from() == null) {
                    throw new BadRequestException("An email rule needs a sender: a rule has no one "
                            + "to send as unless it names somebody");
                }
                checkPeople(type.toEmailEntityType(), List.of(spec.from()));
                checkPeople(type.toEmailEntityType(), people);
            }
        }
        return spec;
    }

    /**
     * The picked people, read as the record itself would read them. The CUSTOMER token is let
     * through untouched: it means "the customer's own addresses", which an email may go to and an
     * assignee never can, so it is the email service's to resolve and not this check's to refuse.
     */
    private void checkPeople(EmailEntityType type, List<EmailDtos.EmailToken> tokens) {
        List<EmailDtos.EmailToken> internal = tokens.stream()
                .filter(t -> t != null && !"CUSTOMER".equalsIgnoreCase(t.type()))
                .toList();
        // No customer: a rule has no record, and the customer only ever lands on the stored row,
        // which is thrown away here. What is being checked is the shape of the pick.
        assigneeService.parse(type, null, internal);
    }

    /**
     * Two rules of the same name make the audit reason on everything they create ambiguous — the
     * trail says which rule did it by name — so the name is the one thing that has to be its own.
     */
    private void requireUniqueName(String name, Long selfId) {
        String wanted = name == null ? "" : name.trim();
        if (wanted.isEmpty()) throw new BadRequestException("A rule needs a name");
        boolean taken = selfId == null
                ? rules.existsByNameIgnoreCase(wanted)
                : rules.existsByNameIgnoreCaseAndIdNot(wanted, selfId);
        if (taken) throw new BadRequestException("Another rule is already called '" + wanted + "'");
    }

    /**
     * The text has to fit the column the action will put it in, and which column that is depends on
     * the action — a dispute reason and a promise note are not the same width. Checked here so the
     * author is told, rather than on the run where nobody is reading.
     */
    private static void requireFits(String value, int max, String what) {
        if (value != null && value.length() > max) {
            throw new BadRequestException(what + " must be at most " + max + " characters");
        }
    }

    /**
     * The same bargain for the one figure a rule states, and for the same reason. A text that is
     * too long for its column is refused above; an amount that is too wide for
     * {@code payment_promises.amount} was not, and a wider one is worse than a skip — the insert
     * itself dies, so the run is an unexpected failure, retried twice and left FAILED, once for
     * every record the rule ever matches (D-74).
     */
    private static void requireAmountFits(BigDecimal amount) {
        Money.requireCents(amount, "The promised amount");
        if (amount.compareTo(MAX_PROMISE_AMOUNT) > 0) {
            throw new BadRequestException("The promised amount must be at most "
                    + MAX_PROMISE_AMOUNT.toPlainString());
        }
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new BadRequestException(message);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ---- shapes -----------------------------------------------------------------------------

    public AutomationDtos.RuleDto toDto(AutomationRule rule) {
        return new AutomationDtos.RuleDto(
                rule.getId(), rule.getName(), rule.getDescription(), rule.isEnabled(),
                rule.getEntityType(), rule.getTrigger(), rule.getTrigger().label(),
                json.filters(rule), rule.getAction(), rule.getAction().label(), json.action(rule),
                rule.getCreatedByUserId(), rule.getLastRunAt(),
                rule.getRunCount() == null ? 0L : rule.getRunCount(),
                rule.getCreatedAt(), rule.getUpdatedAt());
    }

    private static AutomationDtos.RuleRunDto toRunDto(AutomationEvent e) {
        return new AutomationDtos.RuleRunDto(e.getId(), e.getRuleId(), e.getEntityType(), e.getEntityId(),
                e.getTrigger(), e.getStatus(), e.getAttempts(), e.getLastError(),
                e.getEnqueuedAt(), e.getNextAttemptAt(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
