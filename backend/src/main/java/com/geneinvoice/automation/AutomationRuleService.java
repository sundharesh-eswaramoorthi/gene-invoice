package com.geneinvoice.automation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.Money;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.Strings;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.common.query.ConditionNode;
import com.geneinvoice.common.query.Conditions;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.email.EmailDtos;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.email.Placeholders;
import com.geneinvoice.email.RenderContext;
import com.geneinvoice.email.RoleResolver;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionAccess;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.RegionScope;
import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The rule, end to end, with the consumer nowhere in sight.
 *
 * <p>EVERY REFUSAL HERE IS AN EXISTING SENTENCE. A condition naming a column the subject does not
 * have is refused by {@code schema.requireFilterable}, which is the very call the filter bar
 * makes; a role the subject does not offer is refused by {@code EmailAddressing.offeredRole},
 * which is the very method the To field uses; an unknown placeholder is refused by
 * {@code Placeholders.validate}. There is no second vocabulary for any of the three, which is why
 * a rule author and a person typing a filter chip are told the same thing in the same words
 * (A1, A2, A3, A4).
 *
 * <p>VALIDATION HAPPENS AT SAVE, NOT AT RUN. That is the whole reason this unit exists before
 * A-CONSUMER: the author is still looking at the screen. It does not make a run infallible —
 * {@code Conditions} deliberately re-checks at build time because a column can stop being
 * filterable between the save and the run — but it moves every mistake that CAN be caught early
 * to the person who made it (A2).
 */
@Service
@RequiredArgsConstructor
public class AutomationRuleService {

    /** Deliberately NOT added to AuditController.SUPPORTED, for the same reason TASK is not (A1). */
    public static final String ENTITY = "AUTOMATION_RULE";

    /** One to five. A rule with fifty actions is a mistake at authoring time (A3). */
    public static final int MAX_ACTIONS = 5;

    private final AutomationRuleRepository repository;
    private final AutomationStepRepository stepRepository;
    private final ConditionJson conditionJson;
    private final ObjectMapper objectMapper;
    // The email seams: the ONE type the automation package needs for roles, and the catalogue.
    private final RoleResolver roleResolver;
    private final Placeholders placeholders;
    // The three READ gates. Each already answers 404 for a record outside the caller's book or
    // region, so a preview cannot become a way to read a record across a branch (A4, AUTH-08).
    private final CustomerService customerService;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final RegionRepository regionRepository;
    // The write-side gate. A region NAMED on a write is 403 and never 404, because no id space is
    // being probed — the author typed the branch in (B1, D-46).
    private final RegionAccess regionAccess;
    private final RuleRegions ruleRegions;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final ScopeResolver scopeResolver;
    private final TableQueryExecutor queryExecutor;
    private final RegionScope regionScope;
    // The two producers this service is the front door for. The dispatcher answers "what would
    // this rule match" with the very query its own fan-out pages through, and the schedules
    // component opens the run — neither is duplicated here, because a second copy of either is a
    // second answer that would drift from the one that acts (A5).
    private final AutomationDispatcher dispatcher;
    private final AutomationSchedules schedules;

    // ---- writes ---------------------------------------------------------------------------

    @Transactional
    public AutomationDtos.RuleDto create(AutomationDtos.SaveRuleRequest req) {
        Long author = currentUser.require().getId();
        Compiled compiled = validate(req, author);

        AutomationRule rule = AutomationRule.builder()
                .name(requireName(req.name()))
                .description(fit(Strings.blankToNull(req.description()), FieldLimits.RULE_DESCRIPTION))
                .subjectType(req.subjectType())
                .triggerKind(req.triggerKind())
                .scheduleHourUtc(compiled.hour())
                .scheduleDayOfWeek(compiled.dayOfWeek())
                .conditionJson(compiled.conditionJson())
                .actionsJson(compiled.actionsJson())
                .cooldownDays(compiled.cooldownDays())
                .enabled(req.enabled() == null || req.enabled())
                .definitionVersion(1)
                .regionIds(new LinkedHashSet<>(compiled.regionIds()))
                .createdByUserId(author)
                .updatedByUserId(author)
                .build();
        // The first firing is computed here and not by the scheduler, so a rule saved at 09:00 for
        // 08:00 daily is due TOMORROW and not immediately (A1).
        rule.setNextRunAt(req.triggerKind().nextAfter(Instant.now(),
                compiled.dayOfWeek(), compiled.hour()));

        AutomationRule saved = repository.save(rule);
        auditService.record(ENTITY, saved.getId(), "RULE_CREATED", null, snapshot(saved),
                author, null, null);
        return toDto(saved);
    }

    @Transactional
    public AutomationDtos.RuleDto update(Long id, AutomationDtos.SaveRuleRequest req) {
        AutomationRule rule = get(id);
        Long editor = currentUser.require().getId();
        // The AUTHOR and not the editor: the rule keeps acting as the person who wrote it, so it
        // is their reach that "no branches named" resolves against, whoever edits the text (A1, B1).
        Compiled compiled = validate(req, rule.getCreatedByUserId());
        Object before = snapshot(rule);

        boolean definitionChanged = !Objects.equals(rule.getSubjectType(), req.subjectType())
                || !Objects.equals(rule.getTriggerKind(), req.triggerKind())
                || !Objects.equals(rule.getScheduleHourUtc(), compiled.hour())
                || !Objects.equals(rule.getScheduleDayOfWeek(), compiled.dayOfWeek())
                || !Objects.equals(rule.getConditionJson(), compiled.conditionJson())
                || !Objects.equals(rule.getActionsJson(), compiled.actionsJson())
                || !Objects.equals(rule.getCooldownDays(), compiled.cooldownDays())
                || !Objects.equals(rule.getRegionIds(), compiled.regionIds());

        rule.setName(requireName(req.name()));
        rule.setDescription(fit(Strings.blankToNull(req.description()), FieldLimits.RULE_DESCRIPTION));
        rule.setSubjectType(req.subjectType());
        rule.setTriggerKind(req.triggerKind());
        rule.setScheduleHourUtc(compiled.hour());
        rule.setScheduleDayOfWeek(compiled.dayOfWeek());
        rule.setConditionJson(compiled.conditionJson());
        rule.setActionsJson(compiled.actionsJson());
        rule.setCooldownDays(compiled.cooldownDays());
        if (req.enabled() != null) rule.setEnabled(req.enabled());
        rule.getRegionIds().clear();
        rule.getRegionIds().addAll(compiled.regionIds());
        rule.setUpdatedByUserId(editor);
        if (definitionChanged) {
            // A step planned against version 4 refuses to run once the rule is at 5, which is what
            // stops a rule edited mid-flight from performing half the old definition. RENAMING a
            // rule is NOT that, and neither is turning it off — enabled is read live at act time
            // because "stop doing this" has to mean now (A1, A5).
            rule.setDefinitionVersion(rule.getDefinitionVersion() + 1);
        }
        if (definitionChanged || rule.getNextRunAt() == null) {
            rule.setNextRunAt(req.triggerKind().nextAfter(Instant.now(),
                    compiled.dayOfWeek(), compiled.hour()));
        }

        AutomationRule saved = repository.save(rule);
        auditService.record(ENTITY, id, "RULE_UPDATED", before, snapshot(saved), editor, null, null);
        return toDto(saved);
    }

    /**
     * SOFT, so the run history keeps its subject: {@code automation_steps} snapshots the rule's
     * name and carries a raw rule_id with no foreign key, so a deleted rule leaves the list and
     * its history still names it (A1, A5).
     */
    @Transactional
    public void delete(Long id) {
        AutomationRule rule = get(id);
        Long actor = currentUser.require().getId();
        Object before = snapshot(rule);
        rule.setDeletedAt(Instant.now());
        rule.setEnabled(false);
        rule.setNextRunAt(null);
        rule.setUpdatedByUserId(actor);
        AutomationRule saved = repository.save(rule);
        auditService.record(ENTITY, id, "RULE_DELETED", before, snapshot(saved), actor, null, null);
    }

    /** The bulk ENABLE / DISABLE body. No definitionVersion bump: see update above (A1). */
    @Transactional
    public AutomationDtos.RuleDto setEnabled(Long id, boolean enabled) {
        AutomationRule rule = get(id);
        Long actor = currentUser.require().getId();
        if (rule.isEnabled() == enabled) {
            // The eligibility shape: "it is already like that" is a row that did not qualify, not
            // a failure that stops the whole run (A1, TBL-05).
            throw new BulkExecutor.IneligibleException(
                    enabled ? "This rule is already on" : "This rule is already off");
        }
        Object before = snapshot(rule);
        rule.setEnabled(enabled);
        rule.setUpdatedByUserId(actor);
        if (enabled && rule.getTriggerKind().scheduled() && rule.getNextRunAt() == null) {
            rule.setNextRunAt(rule.getTriggerKind().nextAfter(Instant.now(),
                    rule.getScheduleDayOfWeek(), rule.getScheduleHourUtc()));
        }
        AutomationRule saved = repository.save(rule);
        auditService.record(ENTITY, id, "RULE_UPDATED", before, snapshot(saved), actor, null,
                enabled ? "Enabled" : "Disabled");
        return toDto(saved);
    }

    // ---- validation -----------------------------------------------------------------------

    /** Everything a validated request compiles down to, so nothing is validated twice (A1). */
    private record Compiled(Integer hour, Integer dayOfWeek, String conditionJson,
                            String actionsJson, Integer cooldownDays, Set<Long> regionIds) {}

    private Compiled validate(AutomationDtos.SaveRuleRequest req, Long authorUserId) {
        if (req.subjectType() == null) throw new BadRequestException("A rule needs a subject");
        if (req.triggerKind() == null) throw new BadRequestException("A rule needs a trigger");
        EmailEntityType emailType = req.subjectType().emailType();
        TableSchema schema = schemaOf(req.subjectType());

        Integer hour = null;
        Integer dayOfWeek = null;
        if (req.triggerKind().scheduled()) {
            hour = req.scheduleHourUtc() == null ? 0 : req.scheduleHourUtc();
            if (hour < 0 || hour > 23) {
                throw new BadRequestException("The hour must be between 0 and 23 (UTC)");
            }
            if (req.triggerKind() == TriggerKind.SCHEDULE_WEEKLY) {
                dayOfWeek = req.scheduleDayOfWeek() == null ? 1 : req.scheduleDayOfWeek();
                if (dayOfWeek < 1 || dayOfWeek > 7) {
                    throw new BadRequestException(
                            "The day of the week must be between 1 (Monday) and 7 (Sunday)");
                }
            }
        }
        // An event trigger's schedule columns are cleared rather than kept: a rule switched from
        // daily to on-change must not keep an hour nobody can see and nothing reads (A1).

        ConditionNode tree = conditionJson.parse(req.conditions());
        // The very call TableQuery.parse makes, so "Unknown column: ballance" is the filter bar's
        // own 400 and not a second copy of it (A2).
        Conditions.validate(tree, schema);

        List<ActionSpec> actions = req.actions() == null ? List.of() : req.actions();
        if (actions.isEmpty()) throw new BadRequestException("A rule needs at least one action");
        if (actions.size() > MAX_ACTIONS) {
            throw new BadRequestException("A rule may not have more than " + MAX_ACTIONS + " actions");
        }
        for (ActionSpec action : actions) {
            if (action == null) throw new BadRequestException("An action is empty");
            validateAction(action, req.subjectType(), emailType);
        }

        Integer cooldown = req.cooldownDays();
        if (cooldown != null && cooldown < 0) {
            throw new BadRequestException("A cooldown cannot be negative");
        }

        return new Compiled(hour, dayOfWeek, conditionJson.write(tree), writeActions(actions),
                cooldown, regions(req.regionIds(), authorUserId));
    }

    // if/instanceof and not a pattern switch: pattern switches are a PREVIEW feature on Java 17
    // and this build does not enable preview. The sealed interface still gives the exhaustiveness
    // an unrecognised subtype would otherwise slip past, so the final throw is a compile-time
    // impossibility that is written down anyway (A3).
    private void validateAction(ActionSpec action, SubjectType subject, EmailEntityType emailType) {
        if (action instanceof ActionSpec.CreateTask task) {
            validateCreateTask(task, emailType);
        } else if (action instanceof ActionSpec.CreatePromise promise) {
            validateCreatePromise(promise, subject, emailType);
        } else if (action instanceof ActionSpec.CreateDispute dispute) {
            validateCreateDispute(dispute, subject, emailType);
        } else if (action instanceof ActionSpec.SendEmail email) {
            validateSendEmail(email, emailType);
        } else {
            throw new BadRequestException("Unknown action: " + action.getClass().getSimpleName());
        }
    }

    private void validateCreateTask(ActionSpec.CreateTask task, EmailEntityType emailType) {
        if (Strings.blankToNull(task.title()) == null) {
            throw new BadRequestException("A task action needs a title");
        }
        template(task.title(), FieldLimits.TASK_TITLE, "The task title", emailType);
        template(task.notes(), FieldLimits.TASK_NOTES, "The task notes", emailType);
        if (task.dueInDays() != null && task.dueInDays() < 0) {
            throw new BadRequestException("A task cannot be due in a negative number of days");
        }
        for (EmailDtos.EmailToken token : nullToEmpty(task.assignees())) {
            // A customer's people resolve to a Person with a null userId — they cannot own a task,
            // because a customer cannot be given work in the supplier's own book. They ARE allowed
            // in SendEmail.to, which is the whole difference (A3, A6).
            if (kind(token, "assignee") == TokenKind.CUSTOMER) {
                throw new BadRequestException(
                        "A customer cannot be given a task; pick a person or a role");
            }
            resolveToken(token, emailType, "assignee");
        }
    }

    private void validateCreatePromise(ActionSpec.CreatePromise promise, SubjectType subject,
                                       EmailEntityType emailType) {
        if (promise.amountFrom() == null) {
            throw new BadRequestException("A promise action needs an amount");
        }
        if (promise.amountFrom() == ActionSpec.AmountSource.INVOICE_BALANCE
                && subject != SubjectType.INVOICE) {
            throw new BadRequestException(
                    "An invoice balance is only available on a rule about invoices");
        }
        if (promise.amountFrom() == ActionSpec.AmountSource.FIXED) {
            BigDecimal fixed = promise.fixedAmount();
            if (fixed == null) {
                throw new BadRequestException("A fixed amount needs a figure");
            }
            Money.requireCents(fixed, "fixedAmount");
            if (fixed.signum() <= 0) {
                throw new BadRequestException("fixedAmount must be greater than zero");
            }
        }
        if (promise.promisedInDays() != null && promise.promisedInDays() < 0) {
            throw new BadRequestException("A promise cannot be due in a negative number of days");
        }
        template(promise.notes(), FieldLimits.PROMISE_NOTES, "The promise notes", emailType);
        if (promise.collectionPoc() != null) {
            if (kind(promise.collectionPoc(), "collectionPoc") == TokenKind.CUSTOMER) {
                throw new BadRequestException(
                        "A customer cannot be the collection POC; pick a person or a role");
            }
            resolveToken(promise.collectionPoc(), emailType, "collectionPoc");
        }
    }

    private void validateCreateDispute(ActionSpec.CreateDispute dispute, SubjectType subject,
                                       EmailEntityType emailType) {
        // DisputeTargetType has exactly two constants and neither of them is a customer, so there
        // is literally nothing to dispute on a customer rule (A3).
        if (subject != SubjectType.INVOICE && subject != SubjectType.PAYMENT) {
            throw new BadRequestException("There is nothing to dispute on a customer;"
                    + " a dispute is raised against an invoice or a payment");
        }
        if (Strings.blankToNull(dispute.reason()) == null) {
            throw new BadRequestException("A dispute action needs a reason");
        }
        template(dispute.reason(), FieldLimits.DISPUTE_TEXT, "The dispute reason", emailType);
    }

    private void validateSendEmail(ActionSpec.SendEmail email, EmailEntityType emailType) {
        // A rule has no signed-in user to fall back on, so the sender is always explicit — which
        // is exactly the difference between this and EmailAddressing.plan, where a null from
        // means "me" (A3, A5).
        if (email.from() == null) {
            throw new BadRequestException("A rule's email needs a sender");
        }
        if (kind(email.from(), "from") == TokenKind.CUSTOMER) {
            throw new BadRequestException("The sender must be a person or a role");
        }
        resolveToken(email.from(), emailType, "from");
        if (nullToEmpty(email.to()).isEmpty()) {
            throw new BadRequestException("Add at least one recipient");
        }
        for (EmailDtos.EmailToken token : email.to()) {
            // CUSTOMER is allowed here and only here: writing to the account's own addresses is
            // what most dunning rules do (A3).
            if (kind(token, "to") == TokenKind.CUSTOMER) continue;
            resolveToken(token, emailType, "to");
        }
        // THE SAME SAVE-TIME REFUSAL THE TASK TITLE AND THE DISPUTE REASON ALREADY HAVE, and this
        // file's own contract — validation happens at save, not at run. Without it a blank subject
        // saved 200, the rule appeared armed on the list, and every record it ever matched settled
        // SKIPPED "The subject rendered empty" with nothing sent and nothing POISONED. The
        // run-time skip stays: it is a different check, for a non-blank TEMPLATE that RENDERS
        // empty, which no save-time check can see (A2, A3, A4).
        if (Strings.blankToNull(email.subject()) == null) {
            throw new BadRequestException("An email action needs a subject");
        }
        template(email.subject(), FieldLimits.EMAIL_SUBJECT, "The email subject", emailType);
        template(email.body(), FieldLimits.EMAIL_BODY, "The email body", emailType);
    }

    private enum TokenKind { USER, ROLE, CUSTOMER }

    private static TokenKind kind(EmailDtos.EmailToken token, String field) {
        if (token == null) throw new BadRequestException("A " + field + " entry is empty");
        String wanted = token.type() == null ? "" : token.type().trim();
        for (TokenKind k : TokenKind.values()) {
            if (k.name().equalsIgnoreCase(wanted)) return k;
        }
        throw new BadRequestException(field + " type must be one of "
                + Arrays.toString(TokenKind.values()));
    }

    /**
     * A USER token is a person who works here, and a ROLE token is a seat the subject actually
     * offers — checked through {@link RoleResolver#toRoleRef}, which is
     * {@code EmailAddressing.offeredRole} itself, so a rule author is told
     * "Sales POC (customer) is not a role on invoices" in the To field's own words (A3).
     */
    private void resolveToken(EmailDtos.EmailToken token, EmailEntityType type, String field) {
        switch (kind(token, field)) {
            case USER -> {
                if (token.userId() == null) throw new BadRequestException("A USER entry needs a userId");
                User u = userRepository.findById(token.userId()).orElseThrow(() ->
                        new BadRequestException("User #" + token.userId() + " does not exist"));
                if (!u.isActive() || u.getCustomerId() != null) {
                    throw new BadRequestException(u.getUsername() + " is not an active internal user");
                }
            }
            case ROLE -> roleResolver.toRoleRef(type, token);
            case CUSTOMER -> { /* decided by the caller: allowed in to, refused everywhere else */ }
        }
    }

    /**
     * A template is bounded by the column its RENDERED text lands in, and an unknown placeholder
     * in it is a 400 that names itself.
     *
     * <p>The TEMPLATE's own length is what is checked, never the rendered text: a forty-character
     * template can render to forty thousand characters, and a rule must not fail because a
     * customer's name is long. The trim belongs at the destination column (A3, A4).
     */
    private void template(String text, int max, String what, EmailEntityType type) {
        if (text == null) return;
        if (text.length() > max) {
            throw new BadRequestException(what + " may be at most " + max + " characters");
        }
        placeholders.validate(text, type);
    }

    /**
     * Every region a rule NAMES is checked against the caller's own MANAGE grants, so a rule can
     * never reach into a branch its author could not work in by hand. 403 and not 404, because
     * the branch was NAMED: no id space is being probed (B1, D-46).
     *
     * <p>Naming NONE is a different and legitimate answer — "every branch my author may manage,
     * as of now" — but it is refused when that set is EMPTY, because a rule that can reach nothing
     * would sit in the list looking armed and do nothing for ever (A1, B1).
     */
    private Set<Long> regions(List<Long> named, Long authorUserId) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Long regionId : nullToEmpty(named)) {
            if (regionId == null) continue;
            // The gate FIRST, so a bogus id is a refusal to somebody who manages named branches
            // rather than an oracle telling them which region ids exist (B1, D-46).
            regionAccess.requireManage(regionId);
            Region region = regionRepository.findById(regionId).orElseThrow(() ->
                    new BadRequestException("Region #" + regionId + " does not exist"));
            if (!region.isActive()) {
                throw new BadRequestException(region.getCode() + " is retired,"
                        + " so a rule cannot be pointed at it");
            }
            ids.add(regionId);
        }
        if (ids.isEmpty() && ruleRegions.manageableBy(authorUserId).isEmpty()) {
            throw new BadRequestException("A rule with no branches runs in every branch its author"
                    + " may manage, and this rule's author may manage none");
        }
        return Set.copyOf(ids);
    }

    // ---- reads ----------------------------------------------------------------------------

    /**
     * One rule by id, or 404 — never 403. A rule the caller's regions exclude answers exactly as
     * one that does not exist, and so does a soft-deleted one (AUTH-08, A1).
     */
    @Transactional(readOnly = true)
    public AutomationRule get(Long id) {
        AutomationRule rule = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Rule not found"));
        if (!queryExecutor.inScope(AutomationRule.class, AutomationSchemas.RULES, id, ruleScope())) {
            throw new NotFoundException("Rule not found");
        }
        return rule;
    }

    @Transactional(readOnly = true)
    public AutomationDtos.RuleDto dto(Long id) {
        return toDto(get(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<AutomationDtos.RuleDto> page(TableQuery query) {
        ScopeResolver.Scope book = scopeResolver.forAutomationRules();
        var page = queryExecutor.run(AutomationRule.class, AutomationSchemas.RULES, query,
                ruleScope(), List.of());
        return PageResponse.of(toDtos(page.content()), query, page.total(),
                book.lockedFilters(), regionScope.lockedFilters(AutomationRule.class));
    }

    @Transactional(readOnly = true)
    public List<Long> idsMatching(TableQuery query, int limit) {
        return queryExecutor.ids(AutomationRule.class, AutomationSchemas.RULES, query,
                ruleScope(), limit);
    }

    @Transactional(readOnly = true)
    public PageResponse<AutomationDtos.StepDto> steps(TableQuery query) {
        ScopeResolver.Scope book = scopeResolver.forAutomationSteps();
        var page = queryExecutor.run(AutomationStep.class, AutomationSchemas.STEPS, query,
                book.predicates(), List.of());
        return PageResponse.of(toStepDtos(page.content()), query, page.total(),
                book.lockedFilters(), regionScope.lockedFilters(AutomationStep.class));
    }

    /**
     * The badge's number. It deliberately does NOT go through the region axis: a poisoned step is
     * an operational alarm for whoever looks after the engine, and hiding half of them because
     * their subjects are in another branch would make the number a lie. It answers a count and
     * never a row, so nothing about any record leaks with it (A5, B1).
     */
    @Transactional(readOnly = true)
    public long poisonedCount() {
        return stepRepository.countPoisoned();
    }

    @Transactional(readOnly = true)
    public List<AutomationDtos.PlaceholderDto> placeholders(SubjectType subjectType) {
        if (subjectType == null) throw new BadRequestException("subjectType is required");
        return placeholders.offered(subjectType.emailType()).stream()
                .map(AutomationDtos.PlaceholderDto::of).toList();
    }

    /**
     * Render one template against ONE record.
     *
     * <p>THE GATE COMES FIRST. A placeholder that resolves a customer's name is a read of that
     * customer, so the record goes through the same book-and-region gate its own screen does and
     * answers 404 before a single character is rendered. Without that, a preview would be a way
     * to read a name across a region boundary (A4, AUTH-08).
     */
    @Transactional(readOnly = true)
    public AutomationDtos.PreviewResponse preview(AutomationDtos.PreviewRequest req) {
        if (req.subjectType() == null || req.subjectId() == null) {
            throw new BadRequestException("A preview needs a record to render against");
        }
        EmailEntityType type = req.subjectType().emailType();
        Subject subject = visibleSubject(req.subjectType(), req.subjectId());
        EmailTargets.Target target = roleResolver.snapshot(type, req.subjectId());
        // Values as of TODAY, and the response says so rather than pretending: until temporal
        // storage exists the renderer reads the live record (A4, B3).
        LocalDate asOf = InvoiceDates.today();
        RenderContext ctx = RenderContext.of(type, subject.entity(), subject.customer(), target, asOf);
        return new AutomationDtos.PreviewResponse(req.subjectType(), req.subjectId(),
                subject.label(), asOf,
                rendered(req.title(), ctx), rendered(req.subject(), ctx), rendered(req.body(), ctx));
    }

    private AutomationDtos.RenderedDto rendered(String template, RenderContext ctx) {
        if (template == null) return null;
        Placeholders.Rendered out = placeholders.render(template, ctx);
        return new AutomationDtos.RenderedDto(out.text(), out.unresolved());
    }

    /** What a preview is about, once the gate has let it through (A4). */
    private record Subject(Object entity, Customer customer, String label) {}

    private Subject visibleSubject(SubjectType type, Long id) {
        return switch (type) {
            case CUSTOMER -> {
                Customer c = customerService.get(id);
                yield new Subject(c, c, c.getName());
            }
            case INVOICE -> {
                Invoice i = invoiceService.get(id);
                yield new Subject(i, i.getCustomer(), i.getInvoiceNumber());
            }
            case PAYMENT -> {
                Payment p = paymentService.get(id);
                yield new Subject(p, p.getCustomer(), "Payment #" + p.getId());
            }
        };
    }

    // ---- run now, and retry -------------------------------------------------------------

    /** How many records a dry run shows; the count is the whole population, this is the head. */
    public static final int SAMPLE_SIZE = 20;

    static final String PAST_APPLY = "A rule can only be run as of today";
    static final String NEEDS_REQUEST_ID =
            "A run needs a requestId, so that clicking twice cannot run it twice";
    static final String RULE_IS_OFF = "This rule is turned off; turn it on before running it";

    /**
     * WHAT WOULD THIS RULE DO — and nothing else happens (A5).
     *
     * <p>Not {@code @Transactional}: the figures come from the dispatcher's own composed query
     * and the sample from the three read gates, each of which opens its own read. Writing nothing
     * is not a promise this method makes and then keeps — there is no write in it to keep.
     *
     * <p>The COUNT is the rule's reach. The SAMPLE is the caller's: a record the rule would act on
     * that this reader may not see is counted and not shown, because a rendered
     * {@code {{Customer.Name}}} is a read of that customer and a dry run must not become a way to
     * make one across a branch (A4, A5, AUTH-08).
     */
    public AutomationDtos.DryRunDto dryRun(Long id, LocalDate asOf) {
        AutomationRule rule = get(id);
        // THE CLOCK THIS IS ASKED AS OF, AND NOT TODAY'S (B3). `now` is consumed by exactly one
        // clause inside wouldMatch — the cooldown — and every other clause it builds is already
        // as-of: the mirror root, the twin schema, AsOf.at(T), the region ledger and the relative
        // dates in the condition tree. Anchored at the real now, the backtest answered "who has
        // this rule chased in the last N days" when it was asked "who had it chased in the N days
        // before 31 January", which is a different population in BOTH directions and was stamped
        // exact. instantOrNow, so a live dry run is byte-identical to what it always was.
        AutomationDispatcher.Prospect found =
                dispatcher.wouldMatch(rule, AsOfContext.instantOrNow(), SAMPLE_SIZE);
        LocalDate at = asOf == null ? InvoiceDates.today() : asOf;
        List<ActionSpec> specs = readActions(rule.getActionsJson());
        String title = specs.stream().filter(ActionSpec.CreateTask.class::isInstance)
                .map(s -> ((ActionSpec.CreateTask) s).title()).findFirst().orElse(null);
        String subject = specs.stream().filter(ActionSpec.SendEmail.class::isInstance)
                .map(s -> ((ActionSpec.SendEmail) s).subject()).findFirst().orElse(null);

        List<AutomationDtos.SampleDto> sample = new ArrayList<>();
        for (Long subjectId : found.sample()) {
            Subject record;
            try {
                record = visibleSubject(rule.getSubjectType(), subjectId);
            } catch (NotFoundException outsideMyReach) {
                continue;
            }
            EmailEntityType type = rule.getSubjectType().emailType();
            EmailTargets.Target target = roleResolver.snapshot(type, subjectId);
            RenderContext ctx = RenderContext.of(type, record.entity(), record.customer(), target, at);
            sample.add(new AutomationDtos.SampleDto(subjectId, record.label(),
                    title == null ? null : placeholders.render(title, ctx).text(),
                    subject == null ? null : placeholders.render(subject, ctx).text()));
        }
        return new AutomationDtos.DryRunDto(found.matched(), found.truncated(), sample);
    }

    /**
     * BACKTEST: WHAT WOULD THIS RULE HAVE DONE ON THAT DAY, AND NOTHING AT ALL BESIDES (B3).
     *
     * <p>The third of the PRD's three clauses — "every list, report and rule evaluation can be
     * asked as of a date" — and it is the one that needed no evaluation path of its own. The
     * context is already open when this is called, because {@code AsOfInterceptor} opened it for
     * {@code POST /api/automation/rules/{id}/simulate}; {@link EntitySources} therefore hands
     * {@code wouldMatch} the mirror root, the twin schema and {@code AsOf.at(T)}, and the SAME
     * query the real fan-out pages through answers as of that day. Nobody sensible switches on a
     * rule that emails customers without this.
     *
     * <p>IT CREATES NOTHING, and that is structural rather than a promise: there is no write in
     * this method, in {@code wouldMatch}, or in the three read gates the sample passes through.
     * A write here would in any case be refused by {@code HistoryWriter.drain}, which throws for
     * any non-read-only transaction that commits while an as-of context is open.
     *
     * <p>The COUNT is the rule's own reach and the SAMPLE is the caller's, exactly as
     * {@link #dryRun} has it: a record the rule would act on that this reader may not see is
     * counted and not shown, because a rendered {@code {{Customer.Name}}} is a read of that
     * customer and a backtest must not become a way to make one across a branch (A4, A5, AUTH-08).
     */
    public AutomationDtos.SimulationDto simulate(Long id) {
        AutomationDtos.DryRunDto found = dryRun(id, AsOfContext.date());
        return new AutomationDtos.SimulationDto(found.matched(), found.truncated(),
                AsOfContext.info(), true, found.sample());
    }

    static final String REPLAY_NEEDS_A_PAST_DATE =
            "A replay is for a date in the past; use run now for today";

    /**
     * CATCH-UP AFTER AN OUTAGE, AND THE ONE THING THAT MAKES A5'S PROMISE TRUE OF THE TRIGGER (B3).
     *
     * <p>The consumer was down on 2 February, so that slot never fired. Running the rule TODAY
     * evaluates September data: that is not a replay, it is a different rule firing, and for a
     * dunning rule it is the difference between chasing the people who were overdue then and
     * chasing the people who are overdue now. This opens a run whose recorded {@code as_of} IS the
     * missed day, and {@code AutomationDispatcher.planPage} opens that date around the population
     * read — so the run matches exactly the set it would have matched.
     *
     * <p>IT IS A SEPARATE DOOR FROM {@code ?asOf} AND THE DATE IS IN THE BODY, DELIBERATELY. This
     * writes, and the whole of B3's write guard is that ?asOf on a mutating mapping is 400 before
     * the handler runs. The replayed day is not a request to read the past; it is a property of
     * the run being opened, which is why it travels as one. {@code POST .../replay?asOf=...} is
     * still refused with "The past is read only", and correctly.
     *
     * <p>DEDUPED BY THE DAY, not by the click: the occasion is {@code "R" + asOf}, so two replays
     * of 2 February bounce off {@code uk_run_occasion} and read back one run, and their steps
     * bounce off {@code uk_step_occasion} — which is what makes "replay the missed day" an
     * operation somebody can safely repeat when they are not sure whether it worked the first
     * time (A5, B3).
     *
     * <p>Not {@code @Transactional}, for the same reason {@link #runNow} is not: the insert has to
     * be able to fail on its own.
     */
    public AutomationDtos.RunDto replay(Long id, LocalDate asOf) {
        AutomationRule rule = get(id);
        Long actor = currentUser.require().getId();
        if (asOf == null || !asOf.isBefore(InvoiceDates.todayForWrite())) {
            throw new BadRequestException(REPLAY_NEEDS_A_PAST_DATE);
        }
        if (!rule.live()) throw new BadRequestException(RULE_IS_OFF);

        // MANUAL and not a fourth StepSource: somebody pressed a button, which is exactly what
        // MANUAL means, and the run's own as_of is what says it was a replay (A5).
        AutomationRun run = schedules.open(rule, "R" + asOf, StepSource.MANUAL, asOf, actor,
                Instant.now());
        if (run == null) throw new BadRequestException("That run could not be opened; try again");
        return AutomationDtos.RunDto.of(run);
    }

    /**
     * RUN IT. One run, source MANUAL, occasion {@code "M" + requestId}, and the sweeper drains it
     * through the same claim, fence, backoff and settle the clock and the event path use (A5).
     *
     * <p>Not {@code @Transactional}, and that is the point of the shape: the insert has to be able
     * to FAIL on its own, because a second click carrying the same requestId bounces off
     * {@code uk_run_occasion} and is answered with the run the first click opened. Inside a
     * caller's transaction that refusal would poison it instead.
     *
     * <p>A past {@code asOf} is refused outright. Reading the past is a B3 question; creating real
     * Tasks and Emails dated from a replayed past is not what anybody asked for (A5, B3).
     */
    public AutomationDtos.RunDto runNow(Long id, String requestId, LocalDate asOf) {
        AutomationRule rule = get(id);
        Long actor = currentUser.require().getId();
        if (asOf != null && !asOf.equals(InvoiceDates.today())) throw new BadRequestException(PAST_APPLY);
        // A run of a rule that is off would plan a page of steps and settle every one of them
        // SKIPPED "The rule was turned off or removed". Refusing here says so once, on the screen
        // where it can be fixed, rather than a hundred times in the history (A1, A5).
        if (!rule.live()) throw new BadRequestException(RULE_IS_OFF);
        String key = Strings.blankToNull(requestId);
        if (key == null) throw new BadRequestException(NEEDS_REQUEST_ID);

        AutomationRun run = schedules.open(rule, "M" + key, StepSource.MANUAL,
                InvoiceDates.todayForWrite(), actor, Instant.now());
        if (run == null) throw new BadRequestException("That run could not be opened; try again");
        return AutomationDtos.RunDto.of(run);
    }

    /**
     * BACK ON THE QUEUE (A5).
     *
     * <p>QUEUED, attempts 0, no next attempt and NO STALE RESULT: the sweeper picks it up on its
     * next tick and writes a fresh one. The claim token is deliberately left alone — a settled
     * step's token is already stale and {@code claim} overwrites it, so clearing it here would be
     * a second statement that changes nothing.
     *
     * <p>Only a POISONED or a SKIPPED step may be retried. A DONE one already did its work, and
     * its {@code uk_step_occasion} row is the thing that stopped it being done twice; running it
     * again would be the one operation the whole design exists to prevent.
     */
    @Transactional
    public AutomationDtos.StepDto retry(Long id) {
        AutomationStep step = stepRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Step not found"));
        // The step's own VIA_CUSTOMER_ID axis, so a step about an account in another branch reads
        // as one that is not there rather than as one that is refused (AUTH-08, B1).
        if (!queryExecutor.inScope(AutomationStep.class, AutomationSchemas.STEPS, id,
                scopeResolver.forAutomationSteps().predicates())) {
            throw new NotFoundException("Step not found");
        }
        if (step.getStatus() != StepStatus.POISONED && step.getStatus() != StepStatus.SKIPPED) {
            throw new BadRequestException(step.getStatus() == StepStatus.DONE
                    ? "This step already did its work; running it again would do it twice"
                    : "This step has not finished yet");
        }
        Long actor = currentUser.require().getId();
        Object before = snapshot(step);
        step.setStatus(StepStatus.QUEUED);
        step.setAttempts(0);
        step.setNextAttemptAt(null);
        step.setResult(null);
        step.setFinishedAt(null);
        AutomationStep saved = stepRepository.save(step);
        // Anchored on the RULE and not on the step: "AUTOMATION_STEP" is not an audited entity
        // type anywhere, and the rule is what an operator searches the history by (A5).
        auditService.record(ENTITY, step.getRuleId(), "STEP_RETRIED", before, snapshot(saved),
                actor, null, "Step #" + id + " queued for another attempt");
        return toStepDtos(List.of(saved)).get(0);
    }

    private Object snapshot(AutomationStep step) {
        return new StepAuditSnapshot(step.getId(), step.getRuleId(), step.getOccasion(),
                step.getActionIndex(), step.getStatus(), step.getAttempts(), step.getResult());
    }

    public record StepAuditSnapshot(Long id, Long ruleId, String occasion, int actionIndex,
                                    StepStatus status, int attempts, String result) {}

    // ---- scope ----------------------------------------------------------------------------

    /**
     * WHICH RULES MAY I SEE? A rule has no branch of its own, so this cannot be a region axis and
     * has to be a predicate: I may see a rule that names a branch I may read, or that names none
     * and whose AUTHOR may manage a branch I may read, or that I wrote myself (A1, B1).
     *
     * <p>The third clause is not a courtesy. Somebody who writes a rule and is then moved to
     * another branch would otherwise lose the ability to see, edit or turn off a rule that is
     * still running as them — which is the one thing they must always be able to do.
     *
     * <p>The soft-delete clause is here rather than as a filterable column, so a deleted rule
     * leaves every list — including an export and a bulk id resolution — without anybody having
     * to remember to add {@code deletedAt:isEmpty:} (A1).
     */
    private List<PredicateFactory> ruleScope() {
        List<PredicateFactory> scope = new ArrayList<>(scopeResolver.forAutomationRules().predicates());
        scope.add((root, q, cb) -> cb.isNull(root.get("deletedAt")));
        PredicateFactory visible = visibility();
        if (visible != null) scope.add(visible);
        return scope;
    }

    private PredicateFactory visibility() {
        User me = currentUser.require();
        if (me.getCustomerId() != null) {
            // forAutomationRules has already emptied the list; nothing more to say (AUTH-08).
            return null;
        }
        var grants = currentUser.grants();
        // A wildcard VIEW holder can read every branch, so every rule is about a branch they can
        // read. allRegions FIRST: with() answers an empty set for a wildcard holder too (B1).
        if (grants.allRegions(RegionRight.VIEW)) return null;
        Set<Long> visible = grants.with(RegionRight.VIEW);
        Long meId = me.getId();
        return (root, q, cb) -> {
            List<Predicate> any = new ArrayList<>();
            any.add(cb.equal(root.get("createdByUserId"), meId));
            if (!visible.isEmpty()) {
                any.add(namesOneOf(root, q, cb, visible));
                any.add(cb.and(namesNothing(root, q, cb), authorManagesOneOf(root, q, cb, visible)));
            }
            return cb.or(any.toArray(new Predicate[0]));
        };
    }

    private static Predicate namesOneOf(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb,
                                        Collection<Long> visible) {
        Subquery<Long> sq = q.subquery(Long.class);
        Root<AutomationRule> other = sq.from(AutomationRule.class);
        Join<?, ?> region = other.join("regionIds");
        sq.select(cb.literal(1L)).where(
                cb.equal(other.get("id"), root.get("id")),
                region.in(visible));
        return cb.exists(sq);
    }

    // NOT cb.isEmpty over the plural path: an explicit correlated NOT EXISTS is the same shape as
    // namesOneOf above, so the two halves of "names one of mine" and "names none at all" are read
    // side by side and cannot drift (A1, B1).
    private static Predicate namesNothing(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb) {
        Subquery<Long> sq = q.subquery(Long.class);
        Root<AutomationRule> other = sq.from(AutomationRule.class);
        other.join("regionIds");
        sq.select(cb.literal(1L)).where(cb.equal(other.get("id"), root.get("id")));
        return cb.not(cb.exists(sq));
    }

    private static Predicate authorManagesOneOf(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb,
                                                Collection<Long> visible) {
        Subquery<Long> sq = q.subquery(Long.class);
        Root<UserRegionGrant> grant = sq.from(UserRegionGrant.class);
        sq.select(cb.literal(1L)).where(
                cb.equal(grant.get("userId"), root.get("createdByUserId")),
                cb.equal(grant.get("right"), RegionRight.MANAGE),
                // A null region on a grant is the wildcard: that author manages everywhere, so
                // their unbranched rule is about every branch I can read (B1).
                cb.or(cb.isNull(grant.get("regionId")), grant.get("regionId").in(visible)));
        return cb.exists(sq);
    }

    // ---- mapping --------------------------------------------------------------------------

    private List<AutomationDtos.RuleDto> toDtos(List<AutomationRule> rules) {
        if (rules.isEmpty()) return List.of();
        Map<Long, User> authors = people(rules.stream().map(AutomationRule::getCreatedByUserId).toList());
        Map<Long, Region> branches = branches(rules.stream()
                .flatMap(r -> r.getRegionIds().stream()).toList());
        return rules.stream().map(r -> toDto(r, authors, branches)).toList();
    }

    private AutomationDtos.RuleDto toDto(AutomationRule rule) {
        return toDto(rule, people(List.of(rule.getCreatedByUserId())),
                branches(List.copyOf(rule.getRegionIds())));
    }

    private AutomationDtos.RuleDto toDto(AutomationRule rule, Map<Long, User> authors,
                                         Map<Long, Region> branches) {
        User author = authors.get(rule.getCreatedByUserId());
        List<AutomationDtos.RuleRegionDto> named = rule.getRegionIds().stream().sorted()
                .map(branches::get).filter(Objects::nonNull)
                .map(r -> new AutomationDtos.RuleRegionDto(r.getId(), r.getCode(), r.getName()))
                .toList();
        return new AutomationDtos.RuleDto(
                rule.getId(), rule.getName(), rule.getDescription(), rule.getSubjectType(),
                rule.getTriggerKind(), rule.getScheduleHourUtc(), rule.getScheduleDayOfWeek(),
                tree(rule.getConditionJson()), readActions(rule.getActionsJson()),
                rule.getCooldownDays(), rule.isEnabled(), rule.getDefinitionVersion(),
                named, rule.getRegionIds().isEmpty(),
                rule.getNextRunAt(), rule.getLastRunAt(),
                rule.getCreatedByUserId(), author == null ? null : author.getFullName(),
                rule.getCreatedAt(), rule.getUpdatedAt(), rule.getVersion());
    }

    private List<AutomationDtos.StepDto> toStepDtos(List<AutomationStep> steps) {
        if (steps.isEmpty()) return List.of();
        Map<Long, Customer> accounts = accounts(steps.stream()
                .map(AutomationStep::getCustomerId).toList());
        return steps.stream().map(s -> {
            Customer account = accounts.get(s.getCustomerId());
            return new AutomationDtos.StepDto(
                    s.getId(), s.getCreatedAt(), s.getRuleId(), s.getRuleName(), s.getRuleVersion(),
                    s.getSubjectType(), s.getSubjectId(), s.getCustomerId(),
                    account == null ? null : account.getName(),
                    s.getActionKind(), s.getActionIndex(), s.getSource(), s.getStatus(),
                    s.getAttempts(), s.getRunId(), s.getOccasion(), s.getProducedType(),
                    s.getProducedId(), s.getUnresolved(), s.getResult(), s.getFinishedAt(),
                    // customers.region_id is NOT NULL, so an account that is here has a branch (B1).
                    account == null ? null : account.getRegion().getId(),
                    account == null ? null : account.getRegion().getName());
        }).toList();
    }

    // ---- the pieces -----------------------------------------------------------------------

    /**
     * The subject's own registered table schema: a rule's conditions are filter chips over the
     * very table the reader sees on screen, which is what "filters over that entity's fields"
     * means and why there is no second column vocabulary (A2).
     */
    public static TableSchema schemaOf(SubjectType type) {
        return switch (type) {
            case CUSTOMER -> TableSchemas.CUSTOMERS;
            case INVOICE -> TableSchemas.INVOICES;
            case PAYMENT -> TableSchemas.PAYMENTS;
        };
    }

    private JsonNode tree(String stored) {
        if (stored == null || stored.isBlank()) return null;
        try {
            return objectMapper.readTree(stored);
        } catch (JsonProcessingException e) {
            // Stored text that will not parse is a bug, not a caller's mistake; the rule still has
            // to be readable so somebody can fix it, so the tree reads as "no conditions" (A2).
            return null;
        }
    }

    /**
     * writerFor AND NOT writeValueAsString, and the difference is not stylistic (A3, A5).
     *
     * <p>{@code writeValueAsString(List)} has no static element type to work from, so Jackson
     * serialises each action by its runtime class and the {@code @JsonTypeInfo} discriminator on
     * ActionSpec is NEVER WRITTEN. The stored JSON then reads back as
     * "missing type id property 'kind'" — and {@link #readActions} swallows that to an empty list
     * so the rule still opens, which is precisely why nothing noticed: every rule looked saved,
     * answered its DTO with no actions at all, and would have done nothing when it ran. Naming the
     * element type here is what puts "kind" back. Found by A-CONSUMER, whose first end-to-end run
     * planned no steps (A3).
     */
    private String writeActions(List<ActionSpec> actions) {
        try {
            return objectMapper.writerFor(new TypeReference<List<ActionSpec>>() {})
                    .writeValueAsString(actions);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Those actions could not be stored: " + e.getOriginalMessage());
        }
    }

    private List<ActionSpec> readActions(String stored) {
        if (stored == null || stored.isBlank()) return List.of();
        try {
            return objectMapper.readValue(stored, new TypeReference<List<ActionSpec>>() {});
        } catch (JsonProcessingException e) {
            // Same reasoning as tree(): a rule whose actions will not parse must still be
            // openable, or nobody can repair it (A3).
            return List.of();
        }
    }

    private Map<Long, User> people(Collection<Long> userIds) {
        Set<Long> ids = new LinkedHashSet<>(userIds.stream().filter(Objects::nonNull).toList());
        if (ids.isEmpty()) return Map.of();
        Map<Long, User> byId = new LinkedHashMap<>();
        for (User u : userRepository.findAllById(ids)) byId.put(u.getId(), u);
        return byId;
    }

    private Map<Long, Region> branches(Collection<Long> regionIds) {
        Set<Long> ids = new LinkedHashSet<>(regionIds.stream().filter(Objects::nonNull).toList());
        if (ids.isEmpty()) return Map.of();
        Map<Long, Region> byId = new LinkedHashMap<>();
        for (Region r : regionRepository.findAllById(ids)) byId.put(r.getId(), r);
        return byId;
    }

    /** The accounts a page of steps names, once, for their names and their branches (A5). */
    private Map<Long, Customer> accounts(Collection<Long> customerIds) {
        Set<Long> ids = new LinkedHashSet<>(customerIds.stream().filter(Objects::nonNull).toList());
        if (ids.isEmpty()) return Map.of();
        Map<Long, Customer> byId = new LinkedHashMap<>();
        for (Customer c : customerRepository.findAllById(ids)) byId.put(c.getId(), c);
        return byId;
    }

    private static String requireName(String raw) {
        String name = Strings.blankToNull(raw);
        if (name == null) throw new BadRequestException("A rule needs a name");
        return fit(name, FieldLimits.RULE_NAME);
    }

    private static String fit(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, max - 1) + "…";
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private Object snapshot(AutomationRule rule) {
        return new RuleAuditSnapshot(rule.getId(), rule.getName(), rule.getDescription(),
                rule.getSubjectType(), rule.getTriggerKind(), rule.getScheduleHourUtc(),
                rule.getScheduleDayOfWeek(), rule.getConditionJson(), rule.getActionsJson(),
                rule.getCooldownDays(), rule.isEnabled(), rule.getDefinitionVersion(),
                rule.getRegionIds().stream().sorted().toList(), rule.getCreatedByUserId(),
                rule.getDeletedAt());
    }

    public record RuleAuditSnapshot(Long id, String name, String description, SubjectType subjectType,
                                    TriggerKind triggerKind, Integer scheduleHourUtc,
                                    Integer scheduleDayOfWeek, String conditionJson,
                                    String actionsJson, Integer cooldownDays, boolean enabled,
                                    int definitionVersion, List<Long> regionIds,
                                    Long createdByUserId, Instant deletedAt) {}
}
