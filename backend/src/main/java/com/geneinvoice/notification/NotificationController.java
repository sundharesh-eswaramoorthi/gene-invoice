package com.geneinvoice.notification;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.privilege.Privileges;
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
    private final CurrentUser currentUser;

    public record NotificationDto(Long id, String type, String title, String message,
                                  String link, boolean read, Instant createdAt) {
        static NotificationDto from(Notification n) {
            return new NotificationDto(n.getId(), n.getType(), n.getTitle(), n.getMessage(),
                    n.getLink(), n.isRead(), n.getCreatedAt());
        }
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.NOTIFICATION_VIEW + "')")
    public List<NotificationDto> list() {
        return service.listFor(currentUser.require().getId()).stream()
                .map(NotificationDto::from).toList();
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
}
