package com.geneinvoice.notification;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.common.scope.AsOfIgnored;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@AsOfIgnored
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;
    private final NotificationRepository repository;
    private final CurrentUser currentUser;
    private final TableQueryExecutor queryExecutor;
    private final BulkExecutor bulkExecutor;

    public record NotificationDto(Long id, String type, String title, String message,
                                  String link, boolean read, Instant createdAt) {
        static NotificationDto from(Notification n) {
            return new NotificationDto(n.getId(), n.getType(), n.getTitle(), n.getMessage(),
                    n.getLink(), n.isRead(), n.getCreatedAt());
        }
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.NOTIFICATION_VIEW + "')")
    public PageResponse<NotificationDto> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request) {
        TableQuery query = TableQuery.parse(TableSchemas.NOTIFICATIONS, page, size, sort, FilterParams.from(request));
        ScopeResolver.Scope scope = scope();
        var result = queryExecutor.run(Notification.class, TableSchemas.NOTIFICATIONS, query,
                scope.predicates(), List.of());
        return PageResponse.of(result.content().stream().map(NotificationDto::from).toList(),
                query, result.total(), scope);
    }

    @GetMapping("/unread-count")
    @PreAuthorize("hasAuthority('" + Privileges.NOTIFICATION_VIEW + "')")
    public Map<String, Long> unread() {
        return Map.of("count", service.unreadCount(currentUser.require().getId(), askedRegions()));
    }

    @PostMapping("/{id}/read")
    @AsOfIgnored
    @PreAuthorize("hasAuthority('" + Privileges.NOTIFICATION_VIEW + "')")
    public void markRead(@PathVariable Long id) {
        service.markRead(id, currentUser.require().getId());
    }

    @PostMapping("/mark-all-read")
    @AsOfIgnored
    @PreAuthorize("hasAuthority('" + Privileges.NOTIFICATION_VIEW + "')")
    public Map<String, Integer> markAll() {
        return Map.of("updated", service.markAllRead(currentUser.require().getId(), askedRegions()));
    }

    public static final List<String> BULK_ACTIONS = List.of("MARK_READ", "MARK_UNREAD");

    @PostMapping("/bulk")
    @AsOfIgnored
    @PreAuthorize("hasAuthority('" + Privileges.NOTIFICATION_VIEW + "')")
    public BulkDtos.BulkResult bulk(@Valid @RequestBody BulkDtos.BulkRequest req) {
        boolean read = switch (req.action()) {
            case "MARK_READ" -> true;
            case "MARK_UNREAD" -> false;
            default -> throw new BadRequestException(
                    "Unknown bulk action: " + req.action() + " (expected one of " + BULK_ACTIONS + ")");
        };
        List<Long> ids = resolveIds(req);
        boolean truncated = req.allMatching() && ids.size() >= TableQueryExecutor.BULK_ID_LIMIT;
        Long me = currentUser.require().getId();
        return bulkExecutor.run(req, ids, truncated, id -> {
            Notification n = repository.findById(id)
                    .orElseThrow(() -> new BulkExecutor.IneligibleException("Notification not found"));
            if (!n.getUserId().equals(me)) {
                throw new BulkExecutor.IneligibleException("Not your notification");
            }
            if (n.isRead() == read) {
                throw new BulkExecutor.IneligibleException("Already " + (read ? "read" : "unread"));
            }
            n.setRead(read);
            repository.save(n);
        });
    }

    /**
     * The caller's own notifications. They answer live (D9), but they still answer "which regions" (B1, PC-9):
     * when regions are asked for, only notices about records in those of them the caller holds are kept, with
     * every notice about no region at all. A customer login has no choice of region.
     */
    private ScopeResolver.Scope scope() {
        Long me = currentUser.require().getId();
        List<PredicateFactory> predicates = new ArrayList<>();
        predicates.add((root, q, cb) -> cb.equal(root.get("userId"), me));
        if (currentUser.isCustomer()) {
            return new ScopeResolver.Scope(predicates, List.of(), ScopeResolver.RegionCoverage.OWN);
        }
        Set<Long> answered = ViewScopeHolder.current().narrow(currentUser.rights().grantedRegions());
        Set<Long> asked = askedRegions();
        if (asked != null) {
            List<Long> regions = List.copyOf(asked);
            predicates.add((root, q, cb) -> regions.isEmpty()
                    ? cb.isNull(root.get("regionId"))
                    : cb.or(cb.isNull(root.get("regionId")), root.get("regionId").in(regions)));
        }
        return new ScopeResolver.Scope(predicates, List.of(),
                new ScopeResolver.RegionCoverage(answered, Set.of(), false));
    }

    /** The regions asked about, of those the caller holds; null when nothing narrows the answer. */
    private Set<Long> askedRegions() {
        if (currentUser.isCustomer() || ViewScopeHolder.current().regionIds() == null) return null;
        return ViewScopeHolder.current().narrow(currentUser.rights().grantedRegions());
    }

    private List<Long> resolveIds(BulkDtos.BulkRequest req) {
        TableQuery query = TableQuery.parseUnpaged(TableSchemas.NOTIFICATIONS, req.sort(), req.filters());
        List<Long> permitted = queryExecutor.ids(Notification.class, TableSchemas.NOTIFICATIONS,
                query, scope().predicates(), TableQueryExecutor.BULK_ID_LIMIT);
        if (req.allMatching()) return permitted;
        if (req.ids() == null || req.ids().isEmpty()) {
            throw new BadRequestException("Provide ids or set selectAllMatchingFilter");
        }
        return req.ids().stream().filter(permitted::contains).toList();
    }
}
