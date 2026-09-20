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
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/inbox")
@RequiredArgsConstructor
public class InboxController {

    private final InboxService service;
    private final EmailViews views;
    private final CurrentUser currentUser;
    private final TableQueryExecutor queryExecutor;
    private final BulkExecutor bulkExecutor;

    /** Customer logins cannot filter or sort on who sent an email: that is staff names (AC-A8). */
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
        var result = queryExecutor.run(EmailRecipient.class, TableSchemas.INBOX, query,
                ownedByCaller(), List.of("email"));
        return PageResponse.of(views.toInboxItems(result.content(), views.viewer()),
                query, result.total(), List.of());
    }

    @GetMapping("/unread-count")
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_VIEW + "')")
    public Map<String, Long> unread() {
        return Map.of("count", service.unreadCount(currentUser.require().getId()));
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_VIEW + "')")
    public void markRead(@PathVariable Long id) {
        service.setRead(id, currentUser.require().getId(), true);
    }

    @PostMapping("/{id}/unread")
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_VIEW + "')")
    public void markUnread(@PathVariable Long id) {
        service.setRead(id, currentUser.require().getId(), false);
    }

    @PostMapping("/mark-all-read")
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_VIEW + "')")
    public Map<String, Integer> markAll() {
        return Map.of("updated", service.markAllRead(currentUser.require().getId()));
    }

    public static final List<String> BULK_ACTIONS = List.of("MARK_READ", "MARK_UNREAD");

    @PostMapping("/bulk")
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

    /** A user only ever sees and acts on their own To rows; no filter can widen that. */
    private List<PredicateFactory> ownedByCaller() {
        Long me = currentUser.require().getId();
        return List.of((root, q, cb) -> cb.and(
                cb.equal(root.get("userId"), me),
                cb.equal(root.get("field"), RecipientField.TO)));
    }

    private List<Long> resolveIds(BulkDtos.BulkRequest req) {
        TableQuery query = TableQuery.parseUnpaged(schema(), req.sort(), req.filters());
        List<Long> permitted = queryExecutor.ids(EmailRecipient.class, TableSchemas.INBOX,
                query, ownedByCaller(), TableQueryExecutor.BULK_ID_LIMIT);
        if (req.allMatching()) return permitted;
        if (req.ids() == null || req.ids().isEmpty()) {
            throw new BadRequestException("Provide ids or set selectAllMatchingFilter");
        }
        Set<Long> reachable = new HashSet<>(permitted);
        return req.ids().stream().filter(reachable::contains).toList();
    }
}
