package com.geneinvoice.approval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.common.Money;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionAccessQueries;
import com.geneinvoice.region.RegionCreatedListener;
import com.geneinvoice.region.RegionDeletingListener;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.user.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * A region's approval limits (§5.11.11, §6.11). Tightening one (a new row, a lower amount, a longer window) takes
 * effect at once; loosening one (a higher amount, a shorter window, a row removed) is itself held for another
 * approver in the region as one THRESHOLD_CHANGE (SC-11), so nobody can quietly lift the limit their own change is
 * held by. Every change is audited and told to the region's other approvers. A new region starts with the default
 * region's limits (SC-4).
 */
@Service
public class ApprovalThresholdService implements RegionCreatedListener, RegionDeletingListener {

    public static final String CHANGED = "APPROVAL_THRESHOLDS_CHANGED";
    public static final int MAX_WINDOW_HOURS = ApprovalHousekeeping.MAX_WINDOW_HOURS;

    private static final String REGION = "REGION";

    /** One row of a PUT: a null amount removes the limit; a missing window keeps the row's (24 on a new row). */
    public record ThresholdInput(String operation, BigDecimal amount, Integer windowHours) {}

    public record PutRequest(List<ThresholdInput> thresholds) {}

    /** What a held loosening keeps: the rows as they are to become. */
    public record Loosening(List<ThresholdInput> thresholds) {}

    /** One limit as an approver compares it: before, proposed and now. */
    public record Limit(BigDecimal amount, Integer windowHours) {}

    public record Row(ApprovalOperation operation, String label, String measure, BigDecimal amount,
                      Integer windowHours, Instant updatedAt, PendingChangeViews.Person updatedBy) {}

    public record ThresholdsView(Long regionId, String regionName, boolean canEdit, long activeApprovers,
                                 PendingChangeViews.Brief pendingChange, List<Row> thresholds,
                                 @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> warnings) {}

    /** What a PUT did: the limits now, and the loosening it held, if any. */
    public record Saved(ThresholdsView view, PendingChange held) {}

    private final ApprovalThresholdRepository thresholds;
    private final PendingChangeRepository pending;
    private final RegionRepository regionRepository;
    private final UserRepository userRepository;
    private final RegionAccessQueries regionAccess;
    private final MakerChecker makerChecker;
    private final PendingChangeViews views;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final CurrentUser currentUser;
    private final Clock clock;

    public ApprovalThresholdService(ApprovalThresholdRepository thresholds, PendingChangeRepository pending,
                                    RegionRepository regionRepository, UserRepository userRepository,
                                    RegionAccessQueries regionAccess, MakerChecker makerChecker,
                                    PendingChangeViews views, AuditService auditService,
                                    NotificationService notificationService, CurrentUser currentUser, Clock clock) {
        this.thresholds = thresholds;
        this.pending = pending;
        this.regionRepository = regionRepository;
        this.userRepository = userRepository;
        this.regionAccess = regionAccess;
        this.makerChecker = makerChecker;
        this.views = views;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /** The region's limits, to those who set them or approve changes there; 404 for an unknown region. */
    @Transactional(readOnly = true)
    public ThresholdsView get(Long regionId) {
        Region region = region(regionId);
        if (!currentUser.hasIn(Privileges.APPROVAL_THRESHOLD_MANAGE, regionId)
                && !currentUser.hasIn(Privileges.CHANGE_APPROVE, regionId)) {
            throw new AccessDeniedException("Not allowed");
        }
        return view(region, List.of());
    }

    /**
     * Upserts the listed limits (§6.11): the tightening part at once, the loosening part held for another approver.
     * The region and its limits are locked first, as an approval of a held loosening locks them (§5.11.5).
     */
    @Transactional
    public Saved put(Long regionId, PutRequest req) {
        ViewScopeHolder.requireLive();
        Region region = region(regionId);
        currentUser.requireIn(Privileges.APPROVAL_THRESHOLD_MANAGE, regionId);
        Map<ApprovalOperation, ThresholdInput> asked = validate(req);
        Map<ApprovalOperation, ApprovalThreshold> current = lock(regionId);
        Map<String, Limit> before = limits(current, null);
        Long caller = currentUser.require().getId();
        Instant now = clock.instant();

        List<ThresholdInput> loosening = new ArrayList<>();
        asked.forEach((op, in) -> {
            ApprovalThreshold row = current.get(op);
            if (in.amount() == null) {
                if (row != null) loosening.add(new ThresholdInput(op.name(), null, null));
                return;
            }
            int window = in.windowHours() != null ? in.windowHours()
                    : row != null ? row.getWindowHours() : ApprovalThreshold.DEFAULT_WINDOW_HOURS;
            if (row == null) {
                current.put(op, thresholds.save(ApprovalThreshold.builder().region(region).operation(op)
                        .amount(Money.scale(in.amount())).windowHours(window)
                        .updatedByUserId(caller).updatedAt(now).build()));
                return;
            }
            // The stricter of each part now; anything looser waits for another approver.
            BigDecimal amount = in.amount().min(row.getAmount());
            int longer = Math.max(window, row.getWindowHours());
            if (amount.compareTo(row.getAmount()) != 0 || longer != row.getWindowHours()) {
                row.setAmount(Money.scale(amount));
                row.setWindowHours(longer);
                row.setUpdatedByUserId(caller);
                row.setUpdatedAt(now);
            }
            if (in.amount().compareTo(row.getAmount()) > 0 || window < row.getWindowHours()) {
                loosening.add(new ThresholdInput(op.name(), Money.scale(in.amount()), window));
            }
        });
        thresholds.flush();
        Map<String, Limit> after = limits(current, null);

        PendingChange held = loosening.isEmpty() ? null : hold(region, loosening, current, caller, now);
        if (!after.equals(before) || held != null) {
            auditService.record(REGION, regionId, CHANGED, before, after, caller, null,
                    held == null ? null : "Loosening held for approval as #" + held.getId());
            tellApprovers(region, caller, held == null
                    ? "The approval limits in " + region.getName() + " were tightened"
                    : "A loosening of the approval limits in " + region.getName() + " awaits approval");
        }
        return new Saved(view(region, warnings(region)), held);
    }

    /**
     * An approved loosening, applied as its maker (the approval runs as them): each row becomes exactly what was
     * asked for, a null amount removing it.
     */
    @Transactional
    public ThresholdsView applyLoosening(Long regionId, Loosening change) {
        Region region = region(regionId);
        currentUser.requireIn(Privileges.APPROVAL_THRESHOLD_MANAGE, regionId);
        Map<ApprovalOperation, ApprovalThreshold> current = lock(regionId);
        Map<String, Limit> before = limits(current, null);
        Long maker = currentUser.require().getId();
        Instant now = clock.instant();
        for (ThresholdInput in : change.thresholds()) {
            ApprovalOperation op = ApprovalOperation.valueOf(in.operation());
            ApprovalThreshold row = current.get(op);
            if (in.amount() == null) {
                if (row != null) {
                    thresholds.delete(row);
                    current.remove(op);
                }
                continue;
            }
            if (row == null) {
                row = ApprovalThreshold.builder().region(region).operation(op).build();
                current.put(op, row);
            }
            row.setAmount(Money.scale(in.amount()));
            row.setWindowHours(in.windowHours() == null ? ApprovalThreshold.DEFAULT_WINDOW_HOURS : in.windowHours());
            row.setUpdatedByUserId(maker);
            row.setUpdatedAt(now);
            thresholds.save(row);
        }
        thresholds.flush();
        auditService.record(REGION, regionId, CHANGED, before, limits(current, null), maker, null, null);
        tellApprovers(region, maker, "The approval limits in " + region.getName() + " were loosened, as approved");
        return view(region, List.of());
    }

    /** A new region takes the default region's limits, in the transaction that creates it (SC-4). */
    @Override
    public void regionCreated(Region created) {
        Region defaultRegion = regionRepository.findByIsDefaultTrue().orElse(null);
        if (defaultRegion == null || defaultRegion.getId().equals(created.getId())) return;
        List<ApprovalThreshold> copied = new ArrayList<>();
        Instant now = clock.instant();
        for (ApprovalThreshold t : thresholds.findByRegionId(defaultRegion.getId())) {
            copied.add(thresholds.save(ApprovalThreshold.builder().region(created).operation(t.getOperation())
                    .amount(t.getAmount()).windowHours(t.getWindowHours())
                    .updatedByUserId(currentUser.idOrNull()).updatedAt(now).build()));
        }
        if (copied.isEmpty()) return;
        Map<ApprovalOperation, ApprovalThreshold> rows = new EnumMap<>(ApprovalOperation.class);
        copied.forEach(t -> rows.put(t.getOperation(), t));
        auditService.record(REGION, created.getId(), CHANGED, Map.of(), limits(rows, null), currentUser.idOrNull(),
                null, "Copied from the default region, " + defaultRegion.getName());
    }

    /**
     * An empty region's limits go with it, and so do its decided changes of them: nothing else refers to them, and
     * the region's audit trail keeps what they were. A change of them still waiting is decided or withdrawn first;
     * a region some record's change was asked for in keeps that history, so it is deactivated instead.
     */
    @Override
    public void regionDeleting(Region region) {
        Long id = region.getId();
        if (pending.existsInRegion(id, ApprovalOperation.THRESHOLD_CHANGE, PendingStatus.PENDING)) {
            throw new BadRequestException(
                    "Region has a change of its approval limits waiting; decide or withdraw it first");
        }
        if (pending.existsInRegionOtherThan(id, ApprovalOperation.THRESHOLD_CHANGE)) {
            throw new BadRequestException("Region has changes that were held for approval; deactivate it instead");
        }
        pending.deleteInRegion(id, ApprovalOperation.THRESHOLD_CHANGE);
        thresholds.deleteByRegionId(id);
    }

    /** The limits of these operations as they are now, in the shape a held change compares (null where none). */
    Map<String, Limit> currentLimits(Long regionId, Set<String> keys) {
        Map<ApprovalOperation, ApprovalThreshold> rows = new EnumMap<>(ApprovalOperation.class);
        thresholds.findByRegionId(regionId).forEach(t -> rows.put(t.getOperation(), t));
        return limits(rows, keys);
    }

    /** {@code INVOICE_CREATE} → {@code invoiceCreate}: the key a limit goes by in a held change's before and after. */
    static String key(ApprovalOperation op) {
        String[] words = op.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) {
            out.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
        }
        return out.toString();
    }

    static Limit limitOf(JsonNode node) {
        if (node == null || node.isNull()) return null;
        JsonNode amount = node.get("amount");
        JsonNode window = node.get("windowHours");
        return new Limit(amount == null || amount.isNull() ? null : amount.decimalValue(),
                window == null || window.isNull() ? null : window.asInt());
    }

    static boolean sameLimit(Limit a, Limit b) {
        if (a == null || b == null) return a == b;
        return ChangeConflictException.same(a.amount(), b.amount()) && Objects.equals(a.windowHours(), b.windowHours());
    }

    private PendingChange hold(Region region, List<ThresholdInput> loosening,
                               Map<ApprovalOperation, ApprovalThreshold> current, Long caller, Instant now) {
        Set<String> keys = new HashSet<>();
        Map<String, Limit> proposed = new LinkedHashMap<>();
        List<String> parts = new ArrayList<>();
        for (ThresholdInput in : loosening) {
            ApprovalOperation op = ApprovalOperation.valueOf(in.operation());
            keys.add(key(op));
            Limit to = in.amount() == null ? null : new Limit(in.amount(), in.windowHours());
            proposed.put(key(op), to);
            ApprovalThreshold row = current.get(op);
            parts.add(op.label() + " " + describe(row == null ? null : new Limit(row.getAmount(), row.getWindowHours()))
                    + " → " + describe(to));
        }
        Map<String, Limit> base = limits(current, keys);
        Gated g = new Gated(ApprovalOperation.THRESHOLD_CHANGE, region.getId(),
                "Approval limits in " + region.getName(), null, null, null, null, null, region.getId(), null,
                BigDecimal.ZERO, new Loosening(loosening), base, proposed,
                "Loosen approval limits in " + region.getName() + ": " + String.join("; ", parts), false);
        return makerChecker.hold(g, BigDecimal.ZERO, caller, null, now);
    }

    private static String describe(Limit limit) {
        if (limit == null) return "no limit";
        return Money.format(limit.amount()) + (limit.windowHours() == null || limit.windowHours() == 0
                ? "" : " over " + limit.windowHours() + " h");
    }

    private void tellApprovers(Region region, Long exceptUserId, String message) {
        notificationService.notifyHolders(Privileges.CHANGE_APPROVE, region.getId(), exceptUserId, CHANGED,
                "Approval limits changed in " + region.getName(), message,
                "/approval-thresholds?regionId=" + region.getId());
    }

    private ThresholdsView view(Region region, List<String> warnings) {
        Map<ApprovalOperation, ApprovalThreshold> rows = new EnumMap<>(ApprovalOperation.class);
        thresholds.findByRegionId(region.getId()).forEach(t -> rows.put(t.getOperation(), t));
        List<Row> out = new ArrayList<>();
        for (ApprovalOperation op : ApprovalOperation.values()) {
            if (!op.amountGated()) continue;
            ApprovalThreshold t = rows.get(op);
            out.add(t == null
                    ? new Row(op, op.label(), op.measureLabel(), null, null, null, null)
                    : new Row(op, op.label(), op.measureLabel(), Money.scale(t.getAmount()), t.getWindowHours(),
                            t.getUpdatedAt(), person(t.getUpdatedByUserId())));
        }
        PendingChangeViews.Brief outstanding = pending.findByOutstandingKey(Gated.THRESHOLDS_KEY + region.getId())
                .map(views::brief).orElse(null);
        boolean canEdit = !AsOf.active() && currentUser.hasIn(Privileges.APPROVAL_THRESHOLD_MANAGE, region.getId());
        return new ThresholdsView(region.getId(), region.getName(), canEdit, approvers(region.getId()), outstanding,
                out, warnings);
    }

    private long approvers(Long regionId) {
        return regionAccess.holdersIn(Privileges.CHANGE_APPROVE, regionId).size();
    }

    /** With fewer than two approvers, a held change may have nobody to approve it. */
    private List<String> warnings(Region region) {
        long n = approvers(region.getId());
        if (n >= 2) return List.of();
        return List.of((n == 0 ? "Nobody" : "Only 1 person") + " can approve changes in " + region.getName()
                + ", and nobody can approve their own changes");
    }

    private PendingChangeViews.Person person(Long userId) {
        if (userId == null) return null;
        return userRepository.findById(userId)
                .map(u -> new PendingChangeViews.Person(u.getId(), MakerChecker.displayName(u)))
                .orElse(new PendingChangeViews.Person(userId, "user #" + userId));
    }

    private Region region(Long regionId) {
        return regionRepository.findById(regionId).orElseThrow(() -> new NotFoundException("Region not found"));
    }

    /** The region row, then its limits (§5.11.5), keyed by operation. */
    private Map<ApprovalOperation, ApprovalThreshold> lock(Long regionId) {
        regionRepository.findByIdForUpdate(regionId);
        Map<ApprovalOperation, ApprovalThreshold> rows = new EnumMap<>(ApprovalOperation.class);
        thresholds.findByRegionIdForUpdate(regionId).forEach(t -> rows.put(t.getOperation(), t));
        return rows;
    }

    /** The limits as a held change shows them, by key; only {@code keys} when given, with null where there is none. */
    private static Map<String, Limit> limits(Map<ApprovalOperation, ApprovalThreshold> rows, Set<String> keys) {
        Map<String, Limit> out = new TreeMap<>();
        for (ApprovalOperation op : ApprovalOperation.values()) {
            if (!op.amountGated()) continue;
            ApprovalThreshold t = rows.get(op);
            if (keys == null && t == null) continue;
            if (keys != null && !keys.contains(key(op))) continue;
            out.put(key(op), t == null ? null : new Limit(Money.scale(t.getAmount()), t.getWindowHours()));
        }
        return out;
    }

    /** Each row names one amount-gated operation once; amounts in whole cents, 0 or more; windows 0 to 720 hours. */
    private static Map<ApprovalOperation, ThresholdInput> validate(PutRequest req) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<ApprovalOperation, ThresholdInput> out = new EnumMap<>(ApprovalOperation.class);
        if (req == null || req.thresholds() == null) {
            throw new GlobalExceptionHandler.InvalidFieldsException(Map.of("thresholds", "must not be null"));
        }
        for (int i = 0; i < req.thresholds().size(); i++) {
            ThresholdInput in = req.thresholds().get(i);
            String at = "thresholds[" + i + "].";
            if (in == null) {
                errors.put("thresholds[" + i + "]", "must not be null");
                continue;
            }
            ApprovalOperation op = operation(in.operation());
            if (op == null) {
                errors.put(at + "operation", "Choose one of the operations a limit can apply to");
            } else if (out.containsKey(op)) {
                errors.put(at + "operation", op.label() + " is listed twice");
            }
            BigDecimal amount = in.amount();
            if (amount != null) {
                if (amount.signum() < 0) {
                    errors.put(at + "amount", "must be 0 or more");
                } else if (amount.stripTrailingZeros().scale() > 2) {
                    errors.put(at + "amount", Money.CENTS_MESSAGE);
                } else if (amount.precision() - amount.scale() > 12) {
                    errors.put(at + "amount", "is too large");
                }
            }
            Integer window = in.windowHours();
            if (window != null && (window < 0 || window > MAX_WINDOW_HOURS)) {
                errors.put(at + "windowHours", "must be between 0 and " + MAX_WINDOW_HOURS + " hours");
            }
            if (op != null && !out.containsKey(op)) out.put(op, in);
        }
        if (!errors.isEmpty()) throw new GlobalExceptionHandler.InvalidFieldsException(errors);
        return out;
    }

    private static ApprovalOperation operation(String raw) {
        if (raw == null) return null;
        try {
            ApprovalOperation op = ApprovalOperation.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            return op.amountGated() ? op : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
