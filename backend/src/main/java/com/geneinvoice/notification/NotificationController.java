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
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
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
        var result = queryExecutor.run(Notification.class, TableSchemas.NOTIFICATIONS, query,
                ownedByCaller(), List.of());
        return PageResponse.of(result.content().stream().map(NotificationDto::from).toList(),
                query, result.total(), List.of());
    }

    @GetMapping("/unread-count")
    @PreAuthorize("hasAuthority('" + Privileges.NOTIFICATION_VIEW + "')")
    public Map<String, Long> unread() {
        return Map.of("count", service.unreadCount(currentUser.require().getId()));
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("hasAuthority('" + Privileges.NOTIFICATION_VIEW + "')")
    public void markRead(@PathVariable Long id) {
        service.markRead(id, currentUser.require().getId());
    }

    @PostMapping("/mark-all-read")
    @PreAuthorize("hasAuthority('" + Privileges.NOTIFICATION_VIEW + "')")
    public Map<String, Integer> markAll() {
        return Map.of("updated", service.markAllRead(currentUser.require().getId()));
    }

    public static final List<String> BULK_ACTIONS = List.of("MARK_READ", "MARK_UNREAD");

    @PostMapping("/bulk")
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

    /** A user only ever sees and acts on their own notifications; no FilterParams.from(request) can widen that. */
    private List<PredicateFactory> ownedByCaller() {
        Long me = currentUser.require().getId();
        return List.of((root, q, cb) -> cb.equal(root.get("userId"), me));
    }

    private List<Long> resolveIds(BulkDtos.BulkRequest req) {
        TableQuery query = TableQuery.parseUnpaged(TableSchemas.NOTIFICATIONS, req.sort(), req.filters());
        List<Long> permitted = queryExecutor.ids(Notification.class, TableSchemas.NOTIFICATIONS,
                query, ownedByCaller(), TableQueryExecutor.BULK_ID_LIMIT);
        if (req.allMatching()) return permitted;
        if (req.ids() == null || req.ids().isEmpty()) {
            throw new BadRequestException("Provide ids or set selectAllMatchingFilter");
        }
        return req.ids().stream().filter(permitted::contains).toList();
    }
}
