package com.geneinvoice.automation.rule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.query.FilterOperator;
import com.geneinvoice.common.query.FilterSpec;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.scope.AsOfPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Rules as they stood at a point (D9, §5.10.10): each rule's latest revision saved by then, unless it was deleted,
 * seen by whom {@link RuleVisibility} lets see it today (with the regions the rule had then and its owner's grants
 * now). Built in memory, taking the sorts and filters of the live list but those on what only the live rule knows.
 */
@Component
@RequiredArgsConstructor
public class RuleAsOfLists {

    static final String NOT_FOR_PAST_DATES = "This filter isn't available for past dates";

    private final AutomationRuleRevisionRepository revisions;
    private final RuleVisibility visibility;
    private final ObjectMapper objectMapper;

    public record Page(List<RuleDtos.RuleSnapshot> content, long total) {}

    /** Every rule standing at the point that the caller sees, in the regions asked about. */
    @Transactional(readOnly = true)
    public List<RuleDtos.RuleSnapshot> visibleAt(AsOfPoint p) {
        return revisions.latestAt(p.instant()).stream()
                .filter(r -> !r.isDeleted())
                .map(this::snapshot)
                .filter(s -> visibility.inSet(s.ownerUserId(), s.regionIds()))
                .toList();
    }

    /** The revision in force at the point, if the rule stood then. */
    @Transactional(readOnly = true)
    public Optional<RuleDtos.RuleSnapshot> revisionAt(Long ruleId, AsOfPoint p) {
        return revisions.findFirstByRuleIdAndSavedAtLessThanEqualOrderByIdDesc(ruleId, p.instant())
                .filter(r -> !r.isDeleted())
                .map(this::snapshot);
    }

    @Transactional(readOnly = true)
    public Page page(TableQuery query, AsOfPoint p) {
        Comparator<RuleDtos.RuleSnapshot> order = order(query.sortField(), query.sortAscending());
        Predicate<RuleDtos.RuleSnapshot> matching = s -> true;
        for (FilterSpec spec : query.filters()) matching = matching.and(filter(spec));
        List<RuleDtos.RuleSnapshot> all = visibleAt(p).stream().filter(matching).sorted(order).toList();
        long from = (long) query.page() * query.size();
        List<RuleDtos.RuleSnapshot> content = from >= all.size() ? List.of()
                : all.subList((int) from, (int) Math.min(all.size(), from + query.size()));
        return new Page(content, all.size());
    }

    /**
     * Every sort the schema offers but those only the live rule knows (its last and next run): nulls last either way,
     * ties newest first, as the live list breaks them.
     */
    static Comparator<RuleDtos.RuleSnapshot> order(String field, boolean ascending) {
        if ("id".equals(field)) {
            Comparator<RuleDtos.RuleSnapshot> byId = Comparator.comparing(RuleDtos.RuleSnapshot::id);
            return ascending ? byId : byId.reversed();
        }
        Function<RuleDtos.RuleSnapshot, Comparable<?>> key = switch (field) {
            case "name" -> s -> s.name() == null ? null : s.name().toLowerCase(Locale.ROOT);
            case "enabled" -> RuleDtos.RuleSnapshot::enabled;
            case "suspendedReason" -> RuleDtos.RuleSnapshot::suspendedReason;
            case "triggerKind" -> s -> s.trigger() == null ? null : s.trigger().kind();
            case "entityType" -> s -> s.trigger() == null ? null : s.trigger().entityType();
            case "createdAt" -> RuleDtos.RuleSnapshot::createdAt;
            case "updatedAt" -> RuleDtos.RuleSnapshot::updatedAt;
            default -> throw new BadRequestException("This sort isn't available for past dates");
        };
        @SuppressWarnings({"unchecked", "rawtypes"})
        Comparator<Comparable> natural = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
        @SuppressWarnings({"unchecked", "rawtypes"})
        Comparator<RuleDtos.RuleSnapshot> primary = Comparator.comparing(s -> (Comparable) key.apply(s),
                Comparator.nullsLast(natural));
        return primary.thenComparing(RuleDtos.RuleSnapshot::id, Comparator.reverseOrder());
    }

    /**
     * The filters the schema offers, meaning what they mean live (TableSchemas.AUTOMATION_RULES), but those on the
     * rule's last and next run (only the live rule knows them) and on when it was made or changed.
     */
    static Predicate<RuleDtos.RuleSnapshot> filter(FilterSpec spec) {
        FilterOperator op = spec.operator();
        switch (spec.field()) {
            case "id" -> {
                return numeric(spec, RuleDtos.RuleSnapshot::id);
            }
            case "name" -> {
                return text(spec, RuleDtos.RuleSnapshot::name);
            }
            case "enabled" -> {
                if (op != FilterOperator.EQ) break;
                boolean wanted = Boolean.parseBoolean(spec.first().trim());
                return s -> s.enabled() == wanted;
            }
            case "status" -> {
                Predicate<RuleDtos.RuleSnapshot> any = s -> false;
                for (String raw : spec.values()) any = any.or(status(raw));
                return either(op, any, "status (ENUM)");
            }
            case "suspendedReason" -> {
                return oneOf(spec, "suspendedReason", RuleDtos.RuleSnapshot::suspendedReason,
                        SuspendReason.class);
            }
            case "entityType" -> {
                return oneOf(spec, "entityType", s -> s.trigger().entityType(), TriggerEntity.class);
            }
            case "triggerKind" -> {
                return oneOf(spec, "triggerKind", s -> s.trigger().kind(), TriggerKind.class);
            }
            case "ownerUserId" -> {
                if (op == FilterOperator.IS_EMPTY) return s -> s.ownerUserId() == null;
                if (op == FilterOperator.IS_NOT_EMPTY) return s -> s.ownerUserId() != null;
                Set<Long> wanted = spec.values().stream().map(v -> number(v, "ownerUserId"))
                        .collect(Collectors.toSet());
                Predicate<RuleDtos.RuleSnapshot> listed = s -> wanted.contains(s.ownerUserId());
                return either(op, listed, "ownerUserId (REFERENCE)");
            }
            case "regionIds" -> {
                // As TableSchemas.ruleRegionPredicate: empty is a rule restricted to no region (it acts everywhere),
                // and eq/in are the rules restricted to one of those listed, which an unrestricted one is not.
                if (op == FilterOperator.IS_EMPTY) return s -> regions(s).isEmpty();
                if (op == FilterOperator.IS_NOT_EMPTY) return s -> !regions(s).isEmpty();
                Set<Long> wanted = spec.values().stream().map(RuleAsOfLists::regionId).collect(Collectors.toSet());
                Predicate<RuleDtos.RuleSnapshot> listed = s -> regions(s).stream().anyMatch(wanted::contains);
                return switch (op) {
                    case EQ, IN -> listed;
                    case NEQ -> listed.negate();
                    default -> throw new BadRequestException(
                            "Operator " + op.wire() + " is not valid for column regionIds");
                };
            }
            default -> { }
        }
        throw new BadRequestException(NOT_FOR_PAST_DATES);
    }

    /** As TableSchemas.ruleStatusPredicate. */
    private static Predicate<RuleDtos.RuleSnapshot> status(String raw) {
        String status = raw == null ? "" : raw.trim().toUpperCase();
        return switch (status) {
            case "ACTIVE" -> s -> s.enabled() && s.suspendedReason() == null;
            case "DISABLED" -> s -> !s.enabled() && s.suspendedReason() == null;
            case "SUSPENDED" -> s -> s.suspendedReason() != null;
            default -> throw new BadRequestException("Invalid value for column status: '" + raw
                    + "' (expected ACTIVE, DISABLED or SUSPENDED)");
        };
    }

    /** eq and in keep the matching rules, neq and notIn the others. */
    private static Predicate<RuleDtos.RuleSnapshot> either(FilterOperator op, Predicate<RuleDtos.RuleSnapshot> p,
                                                           String column) {
        return switch (op) {
            case EQ, IN -> p;
            case NEQ, NOT_IN -> p.negate();
            default -> throw new BadRequestException("Operator " + op.wire() + " is not valid for column " + column);
        };
    }

    /** An enum column, its values checked as the live list checks them; neq and notIn keep a missing value. */
    private static <E extends Enum<E>> Predicate<RuleDtos.RuleSnapshot> oneOf(
            FilterSpec spec, String column, Function<RuleDtos.RuleSnapshot, String> value, Class<E> type) {
        Set<String> wanted = new HashSet<>();
        for (String raw : spec.values()) {
            try {
                wanted.add(Enum.valueOf(type, raw.trim().toUpperCase()).name());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException(
                        "Invalid value for column " + column + ": '" + raw + "' (" + e.getMessage() + ")");
            }
        }
        Predicate<RuleDtos.RuleSnapshot> listed = s -> value.apply(s) != null && wanted.contains(value.apply(s));
        return either(spec.operator(), listed, column + " (ENUM)");
    }

    /** As the live list reads text: contains ignores case, eq and neq do not; empty is missing or blank. */
    private static Predicate<RuleDtos.RuleSnapshot> text(FilterSpec spec,
                                                        Function<RuleDtos.RuleSnapshot, String> value) {
        return switch (spec.operator()) {
            case CONTAINS -> {
                String needle = spec.first().toLowerCase(Locale.ROOT);
                yield s -> value.apply(s) != null && value.apply(s).toLowerCase(Locale.ROOT).contains(needle);
            }
            case EQ -> {
                String wanted = spec.first().trim();
                yield s -> wanted.equals(value.apply(s));
            }
            case NEQ -> {
                String wanted = spec.first().trim();
                yield s -> !wanted.equals(value.apply(s));
            }
            case IS_EMPTY -> s -> value.apply(s) == null || value.apply(s).isBlank();
            case IS_NOT_EMPTY -> s -> value.apply(s) != null && !value.apply(s).isBlank();
            default -> throw new BadRequestException(NOT_FOR_PAST_DATES);
        };
    }

    private static Predicate<RuleDtos.RuleSnapshot> numeric(FilterSpec spec,
                                                          Function<RuleDtos.RuleSnapshot, Long> value) {
        List<Long> v = spec.values().stream().map(raw -> number(raw, "id")).toList();
        return switch (spec.operator()) {
            case EQ -> s -> v.get(0).equals(value.apply(s));
            case NEQ -> s -> !v.get(0).equals(value.apply(s));
            case GT -> s -> value.apply(s) > v.get(0);
            case GTE -> s -> value.apply(s) >= v.get(0);
            case LT -> s -> value.apply(s) < v.get(0);
            case LTE -> s -> value.apply(s) <= v.get(0);
            case BETWEEN -> s -> value.apply(s) >= v.get(0) && value.apply(s) <= v.get(1);
            default -> throw new BadRequestException(NOT_FOR_PAST_DATES);
        };
    }

    /** As the live list reads a number column. */
    private static Long number(String raw, String column) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException(
                    "Invalid value for column " + column + ": '" + raw + "' (" + e.getMessage() + ")");
        }
    }

    /** As TableSchemas reads a region id. */
    private static Long regionId(String raw) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Expected an id but got: " + raw);
        }
    }

    private static List<Long> regions(RuleDtos.RuleSnapshot s) {
        return s.regionIds() == null ? List.of() : s.regionIds();
    }

    private RuleDtos.RuleSnapshot snapshot(AutomationRuleRevision r) {
        try {
            return objectMapper.readValue(r.getSnapshotJson(), RuleDtos.RuleSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Revision " + r.getId() + " of rule " + r.getRuleId() + " is not readable", e);
        }
    }
}
