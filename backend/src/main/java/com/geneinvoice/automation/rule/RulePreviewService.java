package com.geneinvoice.automation.rule;

import com.geneinvoice.auth.AppUserDetails;
import com.geneinvoice.auth.AppUserDetailsService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.auth.RunAs;
import com.geneinvoice.automation.AutomationProperties;
import com.geneinvoice.automation.action.ActionHandlers;
import com.geneinvoice.automation.action.ActionPreview;
import com.geneinvoice.automation.action.RecordContext;
import com.geneinvoice.automation.action.RecordContexts;
import com.geneinvoice.automation.action.Repeat;
import com.geneinvoice.automation.action.RuleAction;
import com.geneinvoice.automation.eval.RuleEvaluator;
import com.geneinvoice.automation.run.AutomationEffect;
import com.geneinvoice.automation.run.AutomationEffectRepository;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.common.query.ColumnType;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.common.scope.ViewScope;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.history.Records;
import com.geneinvoice.poc.ScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * What a rule would match and do (§5.10.9): how many records, a sample, and for one record whether it matches and
 * what each action would write. It runs in a read-only transaction that is rolled back, and writes nothing.
 *
 * <p>As of a date the condition is evaluated on the records as they were then (and a saved rule's revision in force
 * then); actions are shown for today's view only, since they would be done now (D10). {@code evaluateAs=OWNER}
 * counts as the owner would, but lists only records the caller can also see (PC-7).
 */
@Service
@RequiredArgsConstructor
public class RulePreviewService {

    public static final String CALLER = "CALLER";
    public static final String OWNER = "OWNER";
    static final String AS_OF_NOTE = "Actions are shown only for today's view";
    static final String OUTSIDE_REGIONS = "The record is not in the rule's regions, so the rule would not act on it";
    static final int DEFAULT_SAMPLE = 10;

    /** The page parameters of the matches list: newest first, by id. */
    private static final TableSchema MATCHES = TableSchema.of("automation-matches", "id,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).notFilterable().build());

    private final RuleService ruleService;
    private final RuleValidator validator;
    private final RuleVisibility visibility;
    private final RuleEvaluator evaluator;
    private final RecordContexts recordContexts;
    private final ActionHandlers handlers;
    private final AutomationEffectRepository effectRepository;
    private final AppUserDetailsService principals;
    private final RunAs runAs;
    private final CurrentUser currentUser;
    private final ScopeResolver scopeResolver;
    private final TableQueryExecutor queryExecutor;
    private final Records records;
    private final AutomationProperties props;
    private final PlatformTransactionManager transactionManager;

    /** {@code POST /rules/preview}: the rule in the body, as saving it would check it. */
    public RuleDtos.PreviewDto preview(RuleDtos.PreviewRequest req) {
        if (req == null || req.rule() == null) throw new BadRequestException("A rule to preview is required");
        return readOnly(() -> {
            AutomationRule saved = req.ruleId() == null ? null : ruleService.get(req.ruleId());
            Long owner = saved == null ? currentUser.require().getId() : saved.getOwnerUserId();
            RuleValidator.Validated v = validator.validateForPreview(req.rule(), req.ruleId(), owner);
            return evaluate(v.definition(), req.ruleId(), owner, req.recordId(), req.sampleSize(), req.evaluateAs(),
                    v.warnings());
        });
    }

    /** {@code GET /rules/{id}/preview}: the saved rule, or as of a date its revision in force then. */
    public RuleDtos.PreviewDto preview(Long ruleId, Long recordId, Integer sampleSize, String evaluateAs) {
        return readOnly(() -> {
            RuleDefinition rule = ruleService.definition(ruleId);
            return evaluate(rule, ruleId, rule.ownerUserId(), recordId, sampleSize, evaluateAs,
                    validator.warnings(rule));
        });
    }

    /** {@code GET /rules/{id}/matches}: a page of what the rule matches, newest first. */
    public PageResponse<RuleDtos.MatchDto> matches(Long ruleId, Integer page, Integer size, String evaluateAs) {
        TableQuery query = TableQuery.parse(MATCHES, page, size, null, List.of());
        return readOnly(() -> {
            RuleDefinition rule = ruleService.definition(ruleId);
            boolean asOwner = ownersView(evaluateAs, rule.ownerUserId())
                    && !rule.ownerUserId().equals(currentUser.idOrNull());
            Evaluated e = as(asOwner, rule, () -> new Evaluated(evaluator.count(rule),
                    evaluator.page(rule, query.page(), query.size())));
            ScopeResolver.Scope scope = as(false, rule, () -> scopeResolver.scope(rule.entity().kind(),
                    ScopeResolver.Access.VIEW, ScopeResolver.Reach.SELECTED));
            Set<Long> seen = asOwner ? callerSees(rule, e.ids()) : new HashSet<>(e.ids());
            Map<Long, Object> loaded = loaded(rule, seen);
            List<RuleDtos.MatchDto> content = e.ids().stream().map(id -> seen.contains(id) && loaded.containsKey(id)
                    ? new RuleDtos.MatchDto(id, RecordContexts.label(rule.entity(), loaded.get(id)),
                    EmailTargets.link(rule.entity().emailType(), id), null)
                    : new RuleDtos.MatchDto(null, null, null, true)).toList();
            return PageResponse.of(content, query, e.count(), scope);
        });
    }

    /** What the evaluation found, as whoever it ran as. */
    private record Evaluated(long count, List<Long> ids) {}

    private RuleDtos.PreviewDto evaluate(RuleDefinition rule, Long ruleId, Long ownerId, Long recordId,
                                         Integer sampleSize, String evaluateAs, List<String> warnings) {
        boolean ownersView = ownersView(evaluateAs, ownerId);
        boolean asOwner = ownersView && !ownerId.equals(currentUser.idOrNull());
        int n = Math.max(1, Math.min(sampleSize == null ? DEFAULT_SAMPLE : sampleSize, props.getMaxPreviewSample()));
        AsOfPoint point = AsOf.current();
        boolean callerSeesPoc = scopeResolver.canSeePoc();
        Evaluated e = as(asOwner, rule, () -> new Evaluated(evaluator.count(rule), evaluator.sample(rule, n)));
        Set<Long> seen = asOwner ? callerSees(rule, e.ids()) : new HashSet<>(e.ids());
        Map<Long, Object> loaded = loaded(rule, seen);
        Map<Long, List<String>> done = ruleId == null ? Map.of() : alreadyDone(rule, ruleId, e.ids());
        List<RuleDtos.SampleDto> sample = e.ids().stream().map(id -> seen.contains(id) && loaded.containsKey(id)
                ? new RuleDtos.SampleDto(id, RecordContexts.label(rule.entity(), loaded.get(id)),
                EmailTargets.link(rule.entity().emailType(), id), null, done.getOrDefault(id, List.of()))
                : RuleDtos.SampleDto.hiddenOne()).toList();

        Long chosen = recordId != null ? recordId
                : sample.stream().filter(s -> s.id() != null).map(RuleDtos.SampleDto::id).findFirst().orElse(null);
        RuleDtos.RecordPreviewDto record = null;
        if (chosen != null) {
            String label = labelFor(rule, chosen, asOwner);
            if (label != null) {
                // Asked as the caller, whoever the actions are shown as: the owner's view fills in the customer's
                // fields only if the caller could open the customer too (RR-1). Actions are shown for today only.
                boolean callerSeesCustomer = point == null && recordContexts.callerSeesCustomerOf(rule.entity(), chosen);
                record = as(asOwner, rule, () -> new RuleDtos.RecordPreviewDto(chosen, label,
                        evaluator.matches(rule, chosen), point == null
                        ? actions(rule, ruleId, chosen, callerSeesPoc, callerSeesCustomer) : null));
            }
        }
        return new RuleDtos.PreviewDto(rule.entity().name(), point == null ? null : point.date().toString(),
                point == null ? null : AS_OF_NOTE, ownersView ? OWNER : CALLER, e.count(), sample, record, List.of(),
                warnings);
    }

    /**
     * The record's label, read as the caller: a record the caller cannot see is 404 as the caller's own preview, and
     * none at all (null) when evaluated as the owner.
     */
    private String labelFor(RuleDefinition rule, Long id, boolean asOwner) {
        if (asOwner && !callerSees(rule, List.of(id)).contains(id)) return null;
        Object entity = records.find(rule.entity().type(), id)
                .orElseThrow(() -> new NotFoundException(capitalised(rule.entity().noun()) + " not found"));
        if (!asOwner && !callerSees(rule, List.of(id)).contains(id)) {
            throw new NotFoundException(capitalised(rule.entity().noun()) + " not found");
        }
        return RecordContexts.label(rule.entity(), entity);
    }

    /**
     * Each action for the record, as the rule would do it now; none when whoever it runs as cannot read it. POC
     * details show only when the caller may see them too, also in the owner's view (PC-7), and so do the fields of a
     * customer the caller could not open (RR-1). The preview's transaction
     * is rolled back whatever happens, so a refusal caught here cannot spoil a commit.
     */
    private List<ActionPreview> actions(RuleDefinition rule, Long ruleId, Long id, boolean callerSeesPoc,
                                        boolean callerSeesCustomer) {
        RecordContext rc;
        try {
            rc = recordContexts.load(rule.entity(), id, ruleId, rule.name(), rule.zone(), callerSeesPoc,
                    callerSeesCustomer);
        } catch (NotFoundException e) {
            return List.of();
        }
        // A record outside the rule's regions is one the rule would not act on (SC-4), whatever the actions say.
        boolean outside = rule.restricted() && !rule.regionIds().contains(rc.regionId());
        List<ActionPreview> out = new ArrayList<>();
        for (RuleAction a : rule.actions()) {
            ActionPreview p = handlers.preview(a, rc);
            if (outside) {
                List<String> warnings = new ArrayList<>(p.warnings());
                warnings.add(OUTSIDE_REGIONS);
                p = new ActionPreview(p.key(), p.type(), p.rendered(), p.assignees(), p.email(), p.problems(),
                        warnings);
            }
            out.add(p);
        }
        return out;
    }

    /**
     * Whether the owner's view was asked for: that is for the owner and administrators, anyone else is refused
     * (403). For the owner it is their own view; only someone else's evaluation runs as the owner.
     */
    private boolean ownersView(String evaluateAs, Long ownerId) {
        String wanted = evaluateAs == null || evaluateAs.isBlank() ? CALLER : evaluateAs.trim().toUpperCase(Locale.ROOT);
        if (!wanted.equals(CALLER) && !wanted.equals(OWNER)) {
            throw new BadRequestException("evaluateAs must be CALLER or OWNER");
        }
        if (wanted.equals(CALLER)) return false;
        if (!ownerId.equals(currentUser.idOrNull()) && !visibility.isAdmin()) {
            throw new AccessDeniedException("Not allowed");
        }
        return true;
    }

    /**
     * {@code work} as the rule would see it: the regions it is restricted to, within those asked about, at the
     * point bound; as the owner (their rights, regions and book) when asked, otherwise as the caller.
     */
    private <T> T as(boolean asOwner, RuleDefinition rule, Supplier<T> work) {
        ViewScope asked = ViewScopeHolder.current();
        Set<Long> regions = asked.regionIds();
        if (rule.restricted()) {
            Set<Long> within = new TreeSet<>(rule.regionIds());
            if (regions != null) within.retainAll(regions);
            regions = within;
        }
        ViewScope scope = new ViewScope(regions, asked.asOf());
        if (!asOwner) return ViewScopeHolder.with(scope, work);
        AppUserDetails owner = principals.principalFor(rule.ownerUserId())
                .filter(p -> p.getUser().isActive() && p.getUser().getCustomerId() == null)
                .orElseThrow(() -> new BadRequestException("The rule's owner cannot act now, so it cannot be "
                        + "previewed as them"));
        return runAs.call(owner, scope, WriteOrigin.system(), work);
    }

    /** Which of these records the caller can see, at the point bound, whatever regions are asked about. */
    private Set<Long> callerSees(RuleDefinition rule, List<Long> ids) {
        if (ids.isEmpty()) return Set.of();
        List<PredicateFactory> scope = new ArrayList<>(scopeResolver.scope(rule.entity().kind(),
                ScopeResolver.Access.VIEW, ScopeResolver.Reach.GRANTED).predicates());
        scope.add((root, q, cb) -> root.get("id").in(ids));
        return new HashSet<>(queryExecutor.ids(rule.entity().type(), rule.entity().schema(),
                new TableQuery(0, ids.size(), "id", true, List.of()), scope, ids.size()));
    }

    private Map<Long, Object> loaded(RuleDefinition rule, Set<Long> ids) {
        Map<Long, Object> out = new HashMap<>();
        for (Object entity : records.findAll(rule.entity().type(), ids)) {
            out.put(RecordContexts.idOf(rule.entity(), entity), entity);
        }
        return out;
    }

    /** The once-per-record actions already done on each record, by action key. */
    private Map<Long, List<String>> alreadyDone(RuleDefinition rule, Long ruleId, List<Long> ids) {
        Map<String, Long> recordByKey = new LinkedHashMap<>();
        Map<String, String> actionByKey = new HashMap<>();
        for (RuleAction a : rule.actions()) {
            if (a.repeat() != Repeat.ONCE_PER_RECORD) continue;
            for (Long id : ids) {
                String key = AutomationEffect.oncePerRecordKey(ruleId, a.key(), rule.entity().name(), id);
                recordByKey.put(key, id);
                actionByKey.put(key, a.key());
            }
        }
        if (recordByKey.isEmpty()) return Map.of();
        Map<Long, List<String>> out = new HashMap<>();
        for (String key : effectRepository.findDoneKeys(recordByKey.keySet())) {
            out.computeIfAbsent(recordByKey.get(key), k -> new ArrayList<>()).add(actionByKey.get(key));
        }
        return out;
    }

    /** Inside a read-only transaction that is rolled back whatever happens: a preview never writes. */
    private <T> T readOnly(Supplier<T> work) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        return tx.execute(status -> {
            status.setRollbackOnly();
            return work.get();
        });
    }

    private static String capitalised(String noun) {
        return Character.toUpperCase(noun.charAt(0)) + noun.substring(1);
    }
}
