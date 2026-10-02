package com.geneinvoice.email;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@AsOfIgnored
@RequestMapping("/api/inbox")
@RequiredArgsConstructor
public class InboxController {

    private final InboxService service;
    private final EmailViews views;
    private final CurrentUser currentUser;
    private final TableQueryExecutor queryExecutor;
    private final BulkExecutor bulkExecutor;

    private TableSchema schema() {
        return TableSchemas.INBOX.visibleTo(currentUser.isCustomer());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_VIEW + "')")
    public PageResponse<EmailDtos.InboxItemDto> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request) {
        TableQuery query = TableQuery.parse(schema(), page, size, sort, FilterParams.from(request));
        ScopeResolver.Scope scope = scope();
        var result = queryExecutor.run(EmailRecipient.class, TableSchemas.INBOX, query,
                scope.predicates(), List.of("email"));
        return PageResponse.of(views.toInboxItems(result.content(), views.viewer()),
                query, result.total(), scope);
    }

    @GetMapping("/unread-count")
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_VIEW + "')")
    public Map<String, Long> unread() {
        return Map.of("count", service.unreadCount(currentUser.require().getId(), askedRegions()));
    }

    @PostMapping("/{id}/read")
    @AsOfIgnored
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_VIEW + "')")
    public void markRead(@PathVariable Long id) {
        service.setRead(id, currentUser.require().getId(), true);
    }

    @PostMapping("/{id}/unread")
    @AsOfIgnored
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_VIEW + "')")
    public void markUnread(@PathVariable Long id) {
        service.setRead(id, currentUser.require().getId(), false);
    }

    @PostMapping("/mark-all-read")
    @AsOfIgnored
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_VIEW + "')")
    public Map<String, Integer> markAll() {
        return Map.of("updated", service.markAllRead(currentUser.require().getId(), askedRegions()));
    }

    public static final List<String> BULK_ACTIONS = List.of("MARK_READ", "MARK_UNREAD");

    @PostMapping("/bulk")
    @AsOfIgnored
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_VIEW + "')")
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
            switch (service.mark(id, me, read)) {
                case CHANGED -> { }
                case NOT_FOUND -> throw new BulkExecutor.IneligibleException("Inbox item not found");
                case NOT_OURS -> throw new BulkExecutor.IneligibleException("Not in your inbox");
                case ALREADY -> throw new BulkExecutor.IneligibleException("Already " + (read ? "read" : "unread"));
            }
        });
    }

    /**
     * The caller's own inbox rows. The inbox answers live (D9), but it still answers "which regions" (B1):
     * when regions are asked for, only emails about records in those of them the caller holds are kept,
     * with every email about no region at all. A customer login has no choice of region.
     */
    private ScopeResolver.Scope scope() {
        Long me = currentUser.require().getId();
        List<PredicateFactory> predicates = new ArrayList<>();
        predicates.add((root, q, cb) -> cb.and(
                cb.equal(root.get("userId"), me),
                cb.equal(root.get("field"), RecipientField.TO)));
        if (currentUser.isCustomer()) {
            return new ScopeResolver.Scope(predicates, List.of(), ScopeResolver.RegionCoverage.OWN);
        }
        Set<Long> answered = ViewScopeHolder.current().narrow(currentUser.rights().grantedRegions());
        Set<Long> asked = askedRegions();
        if (asked != null) predicates.add(EmailRegionPredicate.onRecipient(asked, service.defaultRegionId()));
        return new ScopeResolver.Scope(predicates, List.of(),
                new ScopeResolver.RegionCoverage(answered, Set.of(), false));
    }

    /** The regions asked about, of those the caller holds; null when nothing narrows the answer. */
    private Set<Long> askedRegions() {
        if (currentUser.isCustomer() || ViewScopeHolder.current().regionIds() == null) return null;
        return ViewScopeHolder.current().narrow(currentUser.rights().grantedRegions());
    }

    private List<Long> resolveIds(BulkDtos.BulkRequest req) {
        TableQuery query = TableQuery.parseUnpaged(schema(), req.sort(), req.filters());
        List<Long> permitted = queryExecutor.ids(EmailRecipient.class, TableSchemas.INBOX,
                query, scope().predicates(), TableQueryExecutor.BULK_ID_LIMIT);
        if (req.allMatching()) return permitted;
        if (req.ids() == null || req.ids().isEmpty()) {
            throw new BadRequestException("Provide ids or set selectAllMatchingFilter");
        }
        Set<Long> reachable = new HashSet<>(permitted);
        return req.ids().stream().filter(reachable::contains).toList();
    }
}
