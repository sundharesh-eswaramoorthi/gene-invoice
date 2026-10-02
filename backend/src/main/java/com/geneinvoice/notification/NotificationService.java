package com.geneinvoice.notification;

import com.geneinvoice.common.Fit;
import com.geneinvoice.region.RegionAccessQueries;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    /** At most this many people hear about one record through {@link #notifyHolders}. */
    static final int HOLDERS_MAX = 100;

    private final NotificationRepository repository;
    private final UserRepository userRepository;
    private final RegionAccessQueries regionAccess;

    /** A notice about nothing in particular, such as a Gmail connection to renew. */
    @Transactional
    public Notification notify(Long userId, String type, String title, String message, String link) {
        return notify(userId, type, title, message, link, null);
    }

    /** A notice about a record: it carries the record's region, so the bell can be narrowed to regions. */
    @Transactional
    public Notification notify(Long userId, String type, String title, String message, String link, Long regionId) {
        if (link != null && link.length() > Notification.LINK_MAX) {
            // A link that cannot be stored must not fail the save that is telling someone about it.
            log.warn("Notification {} for user {} dropped a link of {} characters", type, userId, link.length());
            link = null;
        }
        return repository.save(Notification.builder()
                .userId(userId).type(type)
                .title(Fit.to(title, Notification.TITLE_MAX))
                .message(Fit.to(message, Notification.MESSAGE_MAX))
                .link(link)
                .regionId(regionId)
                .build());
    }

    /** Active administrators who can reach the record's region; one without a grant there is not told. */
    @Transactional
    public void notifyAdmins(String type, String title, String message, String link, Long regionId) {
        List<User> admins = userRepository.findByRoleName("ADMIN").stream().filter(User::isActive).toList();
        if (admins.isEmpty()) return;
        Set<Long> reach = regionAccess.withAccess(admins.stream().map(User::getId).toList(), regionId);
        for (User a : admins) {
            if (reach.contains(a.getId())) notify(a.getId(), type, title, message, link, regionId);
        }
    }

    /**
     * Everyone active who holds the privilege in the region, except one person (usually whoever caused it), up to
     * {@value #HOLDERS_MAX} of them. Returns how many were told.
     */
    @Transactional
    public int notifyHolders(String privilege, Long regionId, Long exceptUserId, String type, String title,
                             String message, String link) {
        int told = 0;
        for (User holder : regionAccess.holdersIn(privilege, regionId)) {
            if (told >= HOLDERS_MAX) break;
            if (Objects.equals(holder.getId(), exceptUserId)) continue;
            notify(holder.getId(), type, title, message, link, regionId);
            told++;
        }
        return told;
    }

    @Transactional(readOnly = true)
    public List<Notification> listFor(Long userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return repository.countByUserIdAndReadFalse(userId);
    }

    /** Unread notices in these regions and those about no region; null regions means no narrowing. */
    @Transactional(readOnly = true)
    public long unreadCount(Long userId, Collection<Long> regionsOrNull) {
        if (regionsOrNull == null) return unreadCount(userId);
        return regionsOrNull.isEmpty()
                ? repository.countByUserIdAndReadFalseAndRegionIdIsNull(userId)
                : repository.countUnreadInRegions(userId, regionsOrNull);
    }

    @Transactional
    public void markRead(Long id, Long userId) {
        repository.findById(id).ifPresent(n -> {
            if (n.getUserId().equals(userId)) {
                n.setRead(true);
                repository.save(n);
            }
        });
    }

    @Transactional
    public int markAllRead(Long userId) {
        return repository.markAllRead(userId);
    }

    /** Marks read only what the narrowed bell shows: these regions and notices about none; null regions for all. */
    @Transactional
    public int markAllRead(Long userId, Collection<Long> regionsOrNull) {
        if (regionsOrNull == null) return markAllRead(userId);
        return regionsOrNull.isEmpty()
                ? repository.markAllReadAboutNoRegion(userId)
                : repository.markAllReadInRegions(userId, regionsOrNull);
    }
}
