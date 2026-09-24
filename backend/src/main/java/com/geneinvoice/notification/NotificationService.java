package com.geneinvoice.notification;

import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository repository;
    private final UserRepository userRepository;

    @Transactional
    public Notification notify(Long userId, String type, String title, String message, String link) {
        return repository.save(Notification.builder()
                .userId(userId).type(type)
                .title(fit(title, Notification.TITLE_MAX))
                .message(fit(message, Notification.MESSAGE_MAX))
                .link(link).build());
    }

    static String fit(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, max - 1) + "…";
    }

    @Transactional
    public void notifyAdmins(String type, String title, String message, String link) {
        List<User> admins = userRepository.findByRoleName("ADMIN");
        for (User a : admins) {
            notify(a.getId(), type, title, message, link);
        }
    }

    /**
     * Tell these people, except that one (B2).
     *
     * <p>notifyAdmins, one method up, is the wrong tool for maker-checker and this exists because
     * of it: it resolves the literal role name "ADMIN", with no active filter, no customer-login
     * filter and no region, so on a real installation it would wake up every administrator in the
     * company — including deactivated ones and any customer login that happened to be given the
     * role — about a change in a branch they have nothing to do with. It is left exactly as it is,
     * because the places that already call it mean "tell the people who run this system".
     *
     * <p>The caller passes ids and not a privilege: who holds an approval right where is a
     * question only the region side can answer, and answering it here would put a B1 name into
     * com.geneinvoice.notification for ever (B2, B1 INTEGRATION).
     *
     * <p>{@code exclude} is usually the maker, who is the one person in the list that cannot
     * decide their own change. A null excludes nobody — a change the automation engine raised has
     * no maker to leave out, and everybody who can decide it still needs telling (B2, A5).
     */
    @Transactional
    public void notifyEach(List<Long> userIds, Long exclude, String type, String title,
                           String message, String link) {
        if (userIds == null) return;
        for (Long id : userIds) {
            if (id != null && !id.equals(exclude)) notify(id, type, title, message, link);
        }
    }

    @Transactional(readOnly = true)
    public List<Notification> listFor(Long userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return repository.countByUserIdAndReadFalse(userId);
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
}
