package com.geneinvoice.automation.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.automation.AutomationProperties;
import com.geneinvoice.automation.action.ActionHandlers;
import com.geneinvoice.automation.action.RuleAction;
import com.geneinvoice.automation.action.RuleActions;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.common.query.Condition;
import com.geneinvoice.common.query.ConditionJson;
import com.geneinvoice.common.query.FilterOperator;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks a rule as it is saved or previewed (§5.10.2, §6.14 field rules) and turns it into a {@link RuleDefinition}.
 * Every problem is a 400 under its own path ({@code trigger.time}, {@code condition.children[1].filter},
 * {@code actions[2].title}). Whoever saves the rule becomes the one it runs as, so they must hold everything it
 * needs, in every region it is restricted to.
 */
@Component
@RequiredArgsConstructor
public class RuleValidator {

    public static final int MAX_RECORDS_PER_RUN = 5000;
    static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    static final String NOT_AN_UPDATE = "Becoming overdue is not an update; use a daily schedule to catch it";

    /** Columns whose value changes without any write to the record (§5.8.3). */
    private static final Set<String> CLOCK_DRIVEN = Set.of("overdue", "overdueAmount");
    private static final Set<String> PEOPLES_NAMES = Set.of("salesPocName", "collectionPocName");

    private final ObjectMapper objectMapper;
    private final ActionHandlers handlers;
    private final AutomationRuleRepository ruleRepository;
    private final RegionRepository regionRepository;
    private final TableQueryExecutor queryExecutor;
    private final CurrentUser currentUser;
    private final AutomationProperties props;

    /** The rule checked, and what is worth telling its author (§5.8.3 "not an update"). */
    public record Validated(RuleDefinition definition, List<String> warnings) {}

    /**
     * {@code ruleId} is the rule being changed (null for a new one), for the name check; {@code ownerUserId} is who
     * it will run as: the signed-in person, who is checked for the rights it needs.
     */
    public Validated validate(RuleDtos.RuleRequest req, Long ruleId, Long ownerUserId) {
        return validate(req, ruleId, ownerUserId, true);
    }

    /**
     * As a save checks it, for a preview: the same rules for what the rule says, but not whether its name is free
     * or whether the one previewing could save it, which a preview does not do.
     */
    public Validated validateForPreview(RuleDtos.RuleRequest req, Long ruleId, Long ownerUserId) {
        return validate(req, ruleId, ownerUserId, false);
    }

    private Validated validate(RuleDtos.RuleRequest req, Long ruleId, Long ownerUserId, boolean saving) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (req == null) throw new GlobalExceptionHandler.InvalidFieldsException(Map.of("rule", "A rule is required"));

        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) {
            errors.put("name", "must not be blank");
        } else if (name.length() > AutomationRule.NAME_MAX) {
            errors.put("name", "must be at most " + AutomationRule.NAME_MAX + " characters");
        } else if (saving && ruleRepository.nameTaken(name, ruleId)) {
            errors.put("name", "Another rule is called " + name);
        }
        String description = req.description() == null || req.description().isBlank() ? null
                : req.description().trim();
        if (description != null && description.length() > AutomationRule.DESCRIPTION_MAX) {
            errors.put("description", "must be at most " + AutomationRule.DESCRIPTION_MAX + " characters");
        }

        Trigger trigger = trigger(req.trigger(), errors);
        int maxRecords = req.maxRecordsPerRun() == null ? props.getDefaultMaxRecordsPerRun() : req.maxRecordsPerRun();
        if (maxRecords < 1 || maxRecords > MAX_RECORDS_PER_RUN) {
            errors.put("maxRecordsPerRun", "must be between 1 and " + MAX_RECORDS_PER_RUN);
        }
        Set<Long> regionIds = new LinkedHashSet<>(req.regionIds() == null ? List.of() : req.regionIds());
        regionIds.remove(null);

        Condition condition = null;
        List<RuleAction> actions = new ArrayList<>();
        if (trigger.entity() != null) {
            condition = condition(req, trigger.entity(), errors);
            RuleActions.parse(req.actions(), objectMapper, errors).forEach((i, action) ->
                    actions.add(handlers.normalize(action, trigger.entity(), "actions[" + i + "]", errors)));
        } else {
            RuleActions.parse(req.actions(), objectMapper, errors);
        }
        if (saving && errors.isEmpty()) rights(trigger.entity(), actions, regionIds, errors);
        if (!errors.isEmpty()) throw new GlobalExceptionHandler.InvalidFieldsException(errors);

        RuleDefinition definition = new RuleDefinition(ruleId, name, description,
                req.enabled() == null || req.enabled(), trigger.kind(), trigger.entity(), trigger.time(),
                trigger.day(), trigger.zone(), trigger.catchUp(), regionIds, condition, actions, maxRecords,
                ownerUserId);
        return new Validated(definition, warnings(definition));
    }

    private record Trigger(TriggerKind kind, TriggerEntity entity, String time, String day, ZoneId zone,
                           String catchUp) {}

    private Trigger trigger(RuleDtos.TriggerDto t, Map<String, String> errors) {
        if (t == null) {
            errors.put("trigger", "A trigger is required");
            return new Trigger(null, null, null, null, ZoneId.of(props.getDefaultZone()), AutomationRule.CATCH_UP_ONE);
        }
        TriggerKind kind = parse(TriggerKind.class, t.kind(), "trigger.kind", errors);
        TriggerEntity entity = parse(TriggerEntity.class, t.entityType(), "trigger.entityType", errors);
        ZoneId zone = ZoneId.of(props.getDefaultZone());
        if (t.zone() != null && !t.zone().isBlank()) {
            try {
                zone = ZoneId.of(t.zone().trim());
            } catch (DateTimeException e) {
                errors.put("trigger.zone", "is not a time zone");
            }
        }
        if (kind == null || !kind.isSchedule()) {
            return new Trigger(kind, entity, null, null, zone, AutomationRule.CATCH_UP_ONE);
        }
        String time = t.time() == null ? "" : t.time().trim();
        if (!TIME.matcher(time).matches()) errors.put("trigger.time", "must be a time of day, HH:mm");
        String day = null;
        if (kind == TriggerKind.SCHEDULE_WEEKLY) {
            DayOfWeek parsed = parse(DayOfWeek.class, t.day(), "trigger.day", errors);
            day = parsed == null ? null : parsed.name();
        }
        String catchUp = t.catchUp() == null || t.catchUp().isBlank() ? AutomationRule.CATCH_UP_ONE
                : t.catchUp().trim().toUpperCase(Locale.ROOT);
        if (!catchUp.equals(AutomationRule.CATCH_UP_ONE) && !catchUp.equals(AutomationRule.CATCH_UP_ALL)) {
            errors.put("trigger.catchUp", "must be ONE or ALL");
        }
        return new Trigger(kind, entity, time, day, zone, catchUp);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw, String path, Map<String, String> errors) {
        String wanted = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(wanted)) return e;
        }
        errors.put(path, "must be one of " + List.of(type.getEnumConstants()));
        return null;
    }

    /**
     * The condition read, checked against the list's full staff schema and the limits, and then run once so that
     * every value is coerced now rather than when the rule first acts: a bad value is found at save (§5.10.2).
     */
    private Condition condition(RuleDtos.RuleRequest req, TriggerEntity entity, Map<String, String> errors) {
        int before = errors.size();
        Condition condition = ConditionJson.parse(req.condition(), "condition", errors);
        if (condition == null) return null;
        condition.validate(entity.schema(), "condition", errors);
        if (errors.size() > before) return condition;
        try {
            queryExecutor.count(entity.type(), entity.schema(), new TableQuery(0, 1, "id", true, List.of()),
                    List.of(condition.toPredicate(entity.schema()), (root, q, cb) -> cb.disjunction()));
        } catch (Condition.InvalidLeafException e) {
            errors.put(e.path(), e.getMessage());
        } catch (BadRequestException e) {
            errors.put("condition", e.getMessage());
        }
        return condition;
    }

    /**
     * The one saving becomes the one the rule runs as: they must hold what it needs somewhere, and every region it
     * is restricted to must be one where they hold the regional part of it (§5.10.7).
     */
    private void rights(TriggerEntity entity, List<RuleAction> actions, Set<Long> regionIds,
                        Map<String, String> errors) {
        Set<String> required = RequiredPrivileges.of(entity, actions);
        List<String> missing = required.stream().filter(p -> !currentUser.has(p)).toList();
        if (!missing.isEmpty()) {
            errors.put("actions", "This rule needs " + String.join(", ", missing)
                    + ", which you do not hold, so it could never act");
            return;
        }
        Set<String> regional = RequiredPrivileges.regional(required);
        List<String> refused = new ArrayList<>();
        for (Long regionId : regionIds) {
            boolean ok = regionRepository.existsById(regionId)
                    && regional.stream().allMatch(p -> currentUser.hasIn(p, regionId));
            if (!ok) {
                refused.add(regionRepository.findById(regionId).map(Region::getName).orElse("Region #" + regionId));
            }
        }
        if (!refused.isEmpty()) {
            errors.put("regionIds", "You cannot do what this rule does in " + String.join(", ", refused));
        }
    }

    /** Whoever makes a stored rule act (turns it on, takes it over) must hold what it needs, where it acts. */
    public void requireRights(RuleDefinition rule) {
        Map<String, String> errors = new LinkedHashMap<>();
        rights(rule.entity(), rule.actions(), rule.regionIds(), errors);
        if (!errors.isEmpty()) throw new GlobalExceptionHandler.InvalidFieldsException(errors);
    }

    /**
     * What a rule set off by updates should know (§5.8.3): a clock-driven column, or a person's name, changes without
     * any write to the record, so a condition on it never sets the rule off by itself.
     */
    public List<String> warnings(RuleDefinition rule) {
        if (rule.condition() == null || rule.triggerKind() == null || !rule.triggerKind().firesOnUpdate()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Condition.Leaf leaf : rule.condition().leaves()) {
            String field = leaf.spec().field();
            if (CLOCK_DRIVEN.contains(field) || leaf.spec().operator() == FilterOperator.RELATIVE) {
                out.add(NOT_AN_UPDATE);
            } else if (PEOPLES_NAMES.contains(field)) {
                out.add("A change to a POC's name is not an update to the record, so " + field
                        + " never sets this rule off by itself");
            }
        }
        return out.stream().collect(Collectors.toList());
    }
}
